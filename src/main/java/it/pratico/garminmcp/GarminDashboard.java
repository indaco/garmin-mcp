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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

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
    private int failedDays;

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
        failedDays = 0;

        ObjectNode cache = load();
        Set<String> before = new HashSet<>(cache.path("activities").propertyNames());
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
                .put("newActivities", cache.path("activities").propertyNames().stream().filter(id -> !before.contains(id)).count())
                .put("failedDays", failedDays)
                .put("cachedActivities", cache.path("activities").size());
        return out.toString();
    }

    // ------------------------------------------------------------------ sync

    private void syncActivities(ObjectNode cache, LocalDate from, LocalDate to) {
        ObjectNode byId = cache.withObjectProperty("activities");
        LocalDate cachedFrom = date(cache, "activitiesFrom"), cachedTo = date(cache, "activitiesTo");
        for (LocalDate[] r : toFetch(cachedFrom, cachedTo, from, to)) {
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
                    // abort before save(): the days of this range are already removed from the cache
                    throw new GarminApiClient.GarminApiException("Unexpected activity list response: "
                            + json.toString().substring(0, Math.min(200, json.toString().length())));
                }
                for (JsonNode a : list) {
                    byId.set(a.path("activityId").asString(), tools.summarize(a));
                }
                if (list.size() < PAGE) {
                    break;
                }
            }
        }
        setCovered(cache, "activitiesFrom", "activitiesTo", covered(cachedFrom, cachedTo, from, to));
    }

    private void syncHrv(ObjectNode cache, LocalDate from, LocalDate to) {
        ObjectNode byDate = cache.withObjectProperty("hrv");
        LocalDate cachedFrom = date(cache, "hrvFrom"), cachedTo = date(cache, "hrvTo");
        for (LocalDate[] r : toFetch(cachedFrom, cachedTo, from, to)) {
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
        setCovered(cache, "hrvFrom", "hrvTo", covered(cachedFrom, cachedTo, from, to));
    }

    private void syncRecovery(ObjectNode cache, LocalDate from, LocalDate to) {
        ObjectNode byDate = cache.withObjectProperty("recovery");
        LocalDate refreshFrom = recoveryRefreshFrom(date(cache, "recoveryTo"), to);
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (byDate.has(d.toString()) && d.isBefore(refreshFrom)) {
                continue;
            }
            String day = d.toString();
            try {
                byDate.set(day, recoveryRow(day, call(() -> tools.getSleep(day)), call(() -> tools.getDailySummary(day))));
            } catch (GarminApiClient.GarminApiException e) {
                // keep the old row, if any; the day is fetched again on the next call
                failedDays++;
                log.warn("Recovery data of {} not fetched: {}", day, e.getMessage());
            }
        }
        cache.put("recoveryTo", to.toString());
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

    /**
     * The range that the cache covers after fetching {@link #toFetch}'s ranges: the union when the old and
     * the new range touch or overlap, else only [from, to], because the days between them were never fetched.
     */
    static LocalDate[] covered(LocalDate cachedFrom, LocalDate cachedTo, LocalDate from, LocalDate to) {
        if (cachedFrom == null || cachedTo == null || cachedTo.plusDays(1).isBefore(from) || cachedFrom.isAfter(to.plusDays(1))) {
            return new LocalDate[]{from, to};
        }
        return new LocalDate[]{cachedFrom.isBefore(from) ? cachedFrom : from, cachedTo.isAfter(to) ? cachedTo : to};
    }

    /** Recovery rows from the last {@value #REFRESH_DAYS} days before the last sync on are fetched again. */
    static LocalDate recoveryRefreshFrom(LocalDate lastSync, LocalDate today) {
        LocalDate last = lastSync == null || lastSync.isAfter(today) ? today : lastSync;
        return last.minusDays(REFRESH_DAYS - 1L);
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

    ObjectNode load() {
        try {
            if (Files.isRegularFile(file()) && mapper.readTree(Files.readAllBytes(file())) instanceof ObjectNode o
                    && Stream.of("activities", "hrv", "recovery").allMatch(f -> o.path(f).isMissingNode() || o.path(f).isObject())) {
                return o;
            }
        } catch (IOException | JacksonException e) {
            log.warn("Dashboard cache unreadable, starting again: {}", e.getMessage());
        }
        return mapper.createObjectNode();
    }

    private void save(ObjectNode cache) throws IOException {
        GarminTools.writeAtomically(file(), mapper.writeValueAsBytes(cache));
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

    /** A date field of the cache; null when missing or not a date, so the range is fetched again. */
    private static LocalDate date(JsonNode cache, String field) {
        return parse(cache.path(field).asString(""));
    }

    private static void setCovered(ObjectNode cache, String fromField, String toField, LocalDate[] range) {
        cache.put(fromField, range[0].toString());
        cache.put(toField, range[1].toString());
    }

    private static LocalDate dayOf(JsonNode a) {
        String s = a.path("start").asString("");
        return s.length() >= 10 ? parse(s.substring(0, 10)) : null;
    }

    private static LocalDate parse(String s) {
        try {
            return s.isEmpty() ? null : LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
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
