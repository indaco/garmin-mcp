package it.pratico.garminmcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * All the data of the training dashboard in one call, with a local cache in {@code <data-dir>/dashboard-cache.json}:
 * each call fetches from Garmin only the days it does not have yet, plus the last two days, which can still change.
 */
@Component
public class GarminDashboard {

    private static final Logger log = LoggerFactory.getLogger(GarminDashboard.class);
    private static final int DEFAULT_WEEKS = 12;
    private static final int MAX_WEEKS = 52;
    private static final int RECOVERY_DAYS = 14;
    /** The last days of a cached range are fetched again: Garmin can still add activities or finish a night's data. */
    private static final int REFRESH_DAYS = 2;
    private static final int PAGE = 200;

    private final GarminTools tools;
    private final GarminApiClient api;
    private final GarminProperties props;
    private final ObjectMapper mapper;
    private int calls;

    public GarminDashboard(GarminTools tools, GarminApiClient api, GarminProperties props, ObjectMapper mapper) {
        this.tools = tools;
        this.api = api;
        this.props = props;
        this.mapper = mapper;
    }

    @Tool(description = """
            All the data of the training dashboard in one call: HR zones, fitness age, the activities of the
            last `weeks` weeks (compact summaries, with strength sets and laps where they apply), the activities
            of the weeks before for the period comparison, daily HRV, and the last 14 days of sleep and daily
            summaries. Keeps a local cache and fetches from Garmin only what is new since the last call, so a
            rebuild or an update needs few calls. `sync` in the result says what was fetched.""")
    public synchronized String getDashboardData(
            @ToolParam(required = false, description = "Weeks to cover, default 12, max 52") Integer weeks)
            throws IOException {
        int w = weeks == null ? DEFAULT_WEEKS : Math.clamp(weeks, 1, MAX_WEEKS);
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusWeeks(w);
        // one extra week before: the dashboard shows whole Monday-Sunday weeks
        LocalDate prevStart = start.minusWeeks(w + 1L);
        calls = 0;

        ObjectNode cache = load();
        int before = cache.path("activities").size();
        syncActivities(cache, prevStart, today);
        syncHrv(cache, start, today);
        syncRecovery(cache, today.minusDays(RECOVERY_DAYS - 1), today);
        cache.put("syncedAt", Instant.now().toString());
        save(cache);

        ObjectNode out = mapper.createObjectNode();
        out.put("generated", today.toString());
        out.put("weeks", w);
        out.put("start", start.toString());
        out.put("end", today.toString());
        out.put("prevStart", prevStart.toString());
        out.put("prevEnd", start.minusDays(1).toString());
        out.set("zones", call(() -> tools.getHeartRateZones()));
        out.set("fitnessAge", call(() -> tools.getFitnessAge(today.toString())));

        List<JsonNode> current = activitiesBetween(cache, start, today);
        ArrayNode acts = out.putArray("activities");
        for (JsonNode a : current) {
            acts.add(a.deepCopy());
        }
        attachDetails(acts);
        ArrayNode prev = out.putArray("previousActivities");
        for (JsonNode a : activitiesBetween(cache, prevStart, start.minusDays(1))) {
            prev.add(compact(a));
        }
        out.set("hrv", between(cache.path("hrv"), start, today));
        out.set("recovery", between(cache.path("recovery"), today.minusDays(RECOVERY_DAYS - 1), today));
        out.putObject("sync")
                .put("garminCalls", calls) // one-time .FIT downloads for new sets and laps not included
                .put("newActivities", Math.max(0, cache.path("activities").size() - before))
                .put("cachedActivities", cache.path("activities").size());
        return out.toString();
    }

    // ------------------------------------------------------------------ sync

    private void syncActivities(ObjectNode cache, LocalDate from, LocalDate to) {
        ObjectNode byId = cache.withObjectProperty("activities");
        for (LocalDate[] r : toFetch(date(cache, "activitiesFrom"), date(cache, "activitiesTo"), from, to)) {
            // refetched days replace what the cache has, so a deleted or edited activity disappears too
            for (Iterator<Map.Entry<String, JsonNode>> it = byId.properties().iterator(); it.hasNext(); ) {
                LocalDate d = dayOf(it.next().getValue());
                if (d != null && !d.isBefore(r[0]) && !d.isAfter(r[1])) {
                    it.remove();
                }
            }
            for (int page = 0; ; page += PAGE) {
                Map<String, String> q = new HashMap<>();
                q.put("start", String.valueOf(page));
                q.put("limit", String.valueOf(PAGE));
                q.put("startDate", r[0].toString());
                q.put("endDate", r[1].toString());
                calls++;
                JsonNode json = api.getJson("/activitylist-service/activities/search/activities", q);
                JsonNode list = json.isArray() ? json : GarminTools.firstArray(json);
                if (list == null) {
                    break;
                }
                for (JsonNode a : list) {
                    byId.set(a.path("activityId").asString(), tools.summarize(a));
                }
                if (list.size() < PAGE) {
                    break;
                }
            }
        }
        widen(cache, "activitiesFrom", "activitiesTo", from, to);
    }

    private void syncHrv(ObjectNode cache, LocalDate from, LocalDate to) {
        ObjectNode byDate = cache.withObjectProperty("hrv");
        for (LocalDate[] r : toFetch(date(cache, "hrvFrom"), date(cache, "hrvTo"), from, to)) {
            // Garmin may reject long ranges: ask 4 weeks at a time
            for (LocalDate a = r[0]; !a.isAfter(r[1]); a = a.plusDays(28)) {
                String path = "/hrv-service/hrv/daily/" + a + "/" + (a.plusDays(27).isAfter(r[1]) ? r[1] : a.plusDays(27));
                JsonNode json = call(() -> api.getJson(path, Map.of()));
                for (JsonNode h : json.path("hrvSummaries")) {
                    ObjectNode o = mapper.createObjectNode();
                    o.put("date", h.path("calendarDate").asString());
                    copy(h, o, "lastNightAvg", "hrv");
                    copy(h, o, "weeklyAvg", "weeklyAvg");
                    copy(h, o, "status", "status");
                    copy(h.path("baseline"), o, "balancedLow", "low");
                    copy(h.path("baseline"), o, "balancedUpper", "high");
                    byDate.set(o.path("date").asString(), o);
                }
            }
        }
        widen(cache, "hrvFrom", "hrvTo", from, to);
    }

    private void syncRecovery(ObjectNode cache, LocalDate from, LocalDate to) {
        ObjectNode byDate = cache.withObjectProperty("recovery");
        LocalDate refreshFrom = to.minusDays(REFRESH_DAYS - 1L);
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (byDate.has(d.toString()) && d.isBefore(refreshFrom)) {
                continue;
            }
            String day = d.toString();
            byDate.set(day, recoveryRow(day, call(() -> tools.getSleep(day)), call(() -> tools.getDailySummary(day))));
        }
        // the cache keeps only what a dashboard can show
        byDate.properties().removeIf(e -> LocalDate.parse(e.getKey()).isBefore(from.minusDays(RECOVERY_DAYS)));
    }

    /** One compact row per day: sleep, sleep need, bedtime, resting HR, body battery, stress, steps. */
    ObjectNode recoveryRow(String day, JsonNode sleep, JsonNode summary) {
        ObjectNode r = mapper.createObjectNode();
        r.put("date", day);
        hours(sleep, r, "sleepTimeSeconds", "sleepH");
        hours(sleep, r, "deepSleepSeconds", "deep");
        hours(sleep, r, "lightSleepSeconds", "light");
        hours(sleep, r, "remSleepSeconds", "rem");
        hours(sleep, r, "awakeSleepSeconds", "awake");
        copy(sleep.path("sleepScores").path("overall"), r, "value", "sleepScore");
        if (sleep.path("sleepNeed").path("actual").isNumber()) {
            r.put("needH", round2(sleep.path("sleepNeed").path("actual").asDouble() / 60));
        }
        if (sleep.path("sleepStartTimestampLocal").isNumber()) {
            // the *Local timestamp is local wall time encoded as epoch ms: read it as UTC
            var t = Instant.ofEpochMilli(sleep.path("sleepStartTimestampLocal").asLong()).atZone(ZoneOffset.UTC);
            r.put("bedMin", t.getHour() * 60 + t.getMinute());
        }
        copy(summary, r, "restingHeartRate", "rhr");
        copy(summary, r, "bodyBatteryHighestValue", "bbHigh");
        copy(summary, r, "bodyBatteryLowestValue", "bbLow");
        copy(summary, r, "averageStressLevel", "stress");
        copy(summary, r, "totalSteps", "steps");
        copy(summary, r, "dailyStepGoal", "stepGoal");
        if (summary.path("floorsAscended").isNumber()) {
            r.put("floors", Math.round(summary.path("floorsAscended").asDouble()));
        }
        return r;
    }

    /**
     * Date ranges to fetch so the cache covers [from, to]: the part before the cached range, and from
     * the last {@value #REFRESH_DAYS} cached days to {@code to}. An empty cache fetches all of [from, to].
     */
    static List<LocalDate[]> toFetch(LocalDate cachedFrom, LocalDate cachedTo, LocalDate from, LocalDate to) {
        List<LocalDate[]> out = new ArrayList<>();
        if (cachedFrom == null || cachedTo == null || cachedTo.isBefore(from) || cachedFrom.isAfter(to)) {
            out.add(new LocalDate[]{from, to});
            return out;
        }
        if (from.isBefore(cachedFrom)) {
            out.add(new LocalDate[]{from, cachedFrom.minusDays(1)});
        }
        LocalDate tail = cachedTo.minusDays(REFRESH_DAYS - 1L);
        if (tail.isBefore(from)) {
            tail = from;
        }
        if (!tail.isAfter(to)) {
            out.add(new LocalDate[]{tail, to});
        }
        return out;
    }

    // ------------------------------------------------------------------ output

    /** Strength sets for every strength session (30 most recent), laps for the 3 most recent activities, races and runs over 15 km. */
    private void attachDetails(ArrayNode acts) {
        int strength = 0;
        for (int i = 0; i < acts.size(); i++) {
            ObjectNode a = (ObjectNode) acts.get(i);
            long id = a.path("id").asLong();
            String type = a.path("type").asString("");
            try {
                if (type.contains("strength") && strength++ < 30) {
                    a.set("exercises", mapper.readTree(tools.getActivitySets(id)).path("exercises"));
                }
                boolean longRun = type.endsWith("running") && a.path("km").asDouble(0) > 15;
                if (i < 3 || a.path("race").asBoolean(false) || longRun) {
                    a.set("laps", mapper.readTree(tools.getActivityLaps(id)).path("laps"));
                }
            } catch (IOException | GarminApiClient.GarminApiException | JacksonException e) {
                a.put("detailError", e.getMessage());
            }
        }
    }

    /** Most recent first, as listActivities returns them. */
    private List<JsonNode> activitiesBetween(ObjectNode cache, LocalDate from, LocalDate to) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode a : cache.path("activities")) {
            LocalDate d = dayOf(a);
            if (d != null && !d.isBefore(from) && !d.isAfter(to)) {
                out.add(a);
            }
        }
        out.sort(Comparator.comparing((JsonNode a) -> a.path("start").asString("")).reversed());
        return out;
    }

    /** The fields the period comparison needs. */
    private ObjectNode compact(JsonNode a) {
        ObjectNode o = mapper.createObjectNode();
        for (String f : new String[]{"id", "start", "type", "km", "duration", "elapsed", "trainingLoad"}) {
            copy(a, o, f, f);
        }
        return o;
    }

    private ArrayNode between(JsonNode byDate, LocalDate from, LocalDate to) {
        ArrayNode out = mapper.createArrayNode();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (byDate.has(d.toString())) {
                out.add(byDate.get(d.toString()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ cache file

    private Path file() {
        return Path.of(props.dataDir(), "dashboard-cache.json");
    }

    private ObjectNode load() {
        try {
            if (Files.isRegularFile(file()) && mapper.readTree(Files.readAllBytes(file())) instanceof ObjectNode o) {
                return o;
            }
        } catch (IOException | JacksonException e) {
            log.warn("Dashboard cache unreadable, starting again: {}", e.getMessage());
        }
        return mapper.createObjectNode();
    }

    private void save(ObjectNode cache) throws IOException {
        Files.createDirectories(file().getParent());
        Path tmp = file().resolveSibling("dashboard-cache.json.tmp");
        Files.write(tmp, mapper.writeValueAsBytes(cache));
        // replace in one step, so an interrupted write never leaves a half-written cache
        Files.move(tmp, file(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    // ------------------------------------------------------------------ helpers

    private interface Fetch {
        Object get() throws IOException;
    }

    /** Counts the Garmin call and parses a tool's JSON string. */
    private JsonNode call(Fetch f) {
        calls++;
        try {
            Object r = f.get();
            return r instanceof JsonNode n ? n : mapper.readTree(r.toString());
        } catch (IOException e) {
            throw new GarminApiClient.GarminApiException(e.getMessage(), e);
        }
    }

    private static LocalDate date(JsonNode cache, String field) {
        String v = cache.path(field).asString(null);
        return v == null ? null : LocalDate.parse(v);
    }

    private static void widen(ObjectNode cache, String fromField, String toField, LocalDate from, LocalDate to) {
        LocalDate f = date(cache, fromField), t = date(cache, toField);
        cache.put(fromField, (f == null || from.isBefore(f) ? from : f).toString());
        cache.put(toField, (t == null || to.isAfter(t) ? to : t).toString());
    }

    private static LocalDate dayOf(JsonNode a) {
        String s = a.path("start").asString("");
        return s.length() >= 10 ? LocalDate.parse(s.substring(0, 10)) : null;
    }

    private static void copy(JsonNode from, ObjectNode to, String field, String as) {
        JsonNode v = from.get(field);
        if (v != null && !v.isNull()) {
            to.set(as, v);
        }
    }

    private static void hours(JsonNode from, ObjectNode to, String field, String as) {
        if (from.path(field).isNumber()) {
            to.put(as, round2(from.path(field).asDouble() / 3600));
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
