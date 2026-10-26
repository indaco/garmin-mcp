package it.pratico.garminmcp;

import com.garmin.fit.ExerciseCategory;
import com.garmin.fit.ExerciseTitleMesg;
import com.garmin.fit.FitDecoder;
import com.garmin.fit.FitMessages;
import com.garmin.fit.FitRuntimeException;
import com.garmin.fit.LapMesg;
import com.garmin.fit.SetMesg;
import com.garmin.fit.SetType;
import com.garmin.fit.Sport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * MCP tools over the Garmin Connect Personal API (read-only).
 * Endpoints from the official Swagger: https://developer.garmin.com/gc-developer-program/personal/swagger-docs/
 */
@Component
public class GarminTools {

    private static final Logger log = LoggerFactory.getLogger(GarminTools.class);

    private final GarminApiClient api;
    private final GarminProperties props;
    private final ObjectMapper mapper;
    private JsonNode catalogue;

    public GarminTools(GarminApiClient api, GarminProperties props, ObjectMapper mapper) {
        this.api = api;
        this.props = props;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ activities

    @Tool(description = """
            List the user's Garmin activities (most recent first) as a compact summary: id, name, type,
            local start time, distance (km), moving duration, average pace, average/max heart rate,
            cadence, ascent; when Garmin has them: elapsed time (if longer than moving time), race flag,
            Garmin training load, aerobic/anaerobic training effect and label, seconds in HR zones 1-5,
            calories, moderate/vigorous intensity minutes, body battery change, steps, swim SWOLF, pool
            length, lengths and strokes, power, running form (stride, ground contact, vertical
            oscillation and ratio), grade-adjusted pace, best efforts (1k, mile, 5k, 10k, half,
            marathon) and run/walk segments. Dates are yyyy-MM-dd. Set raw=true for Garmin's full JSON.""")
    public String listActivities(
            @ToolParam(required = false, description = "Start date yyyy-MM-dd (optional)") String startDate,
            @ToolParam(required = false, description = "End date yyyy-MM-dd (optional)") String endDate,
            @ToolParam(required = false, description = "Activity type key, e.g. running (optional)") String activityType,
            @ToolParam(required = false, description = "Max number of activities, default 20") Integer limit,
            @ToolParam(required = false, description = "Return Garmin's raw JSON instead of the summary") Boolean raw) {
        Map<String, String> q = new HashMap<>();
        q.put("start", "0");
        q.put("limit", String.valueOf(limit == null || limit <= 0 ? 20 : Math.min(limit, 200)));
        q.put("startDate", startDate);
        q.put("endDate", endDate);
        q.put("activityType", activityType);
        JsonNode json = api.getJson("/activitylist-service/activities/search/activities", q);
        if (Boolean.TRUE.equals(raw)) {
            return json.toString();
        }
        JsonNode list = json.isArray() ? json : firstArray(json);
        if (list == null) {
            return json.toString();
        }
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode a : list) {
            out.add(summarize(a));
        }
        return out.toString();
    }

    @Tool(description = "Get the full detail of one activity (summary, metadata, splits/laps) as JSON.")
    public String getActivity(@ToolParam(description = "Garmin activity id") long activityId) {
        return api.getJson("/activity-service/activity/" + activityId, Map.of()).toString();
    }

    @Tool(description = """
            Download the ORIGINAL .FIT file of an activity and save it on the user's computer.
            Returns the absolute path of the saved .fit file (second-by-second HR, cadence, speed, laps).""")
    public String downloadActivityFit(@ToolParam(description = "Garmin activity id") long activityId)
            throws IOException {
        Path fit = fetchFit(activityId);
        return "{\"path\":\"" + fit.toAbsolutePath() + "\",\"bytes\":" + Files.size(fit) + "}";
    }

    @Tool(description = """
            Laps of an activity as recorded by the watch (manual laps, auto-lap every km, or workout
            steps), read from the original .FIT file. One row per lap: distance (km), timer time,
            pace (min/km, foot sports) or speed (km/h), average/max HR, cadence (spm for running),
            ascent/descent, average power, intensity (active/rest/warmup/cooldown) and lap trigger.
            Use it for intervals and per-km splits; getActivity does not have them.""")
    public String getActivityLaps(@ToolParam(description = "Garmin activity id") long activityId)
            throws IOException {
        FitMessages fit = decodeFit(activityId);
        Sport sport = fit.getSessionMesgs().isEmpty() ? null : fit.getSessionMesgs().getFirst().getSport();
        boolean foot = sport == Sport.RUNNING || sport == Sport.WALKING || sport == Sport.HIKING;

        ArrayNode laps = mapper.createArrayNode();
        int n = 0;
        for (LapMesg lap : fit.getLapMesgs()) {
            ObjectNode o = laps.addObject();
            o.put("lap", ++n);
            float dist = orZero(lap.getTotalDistance());
            float timer = orZero(lap.getTotalTimerTime());
            Float speed = lap.getEnhancedAvgSpeed() != null ? lap.getEnhancedAvgSpeed() : lap.getAvgSpeed();
            o.put("km", round(dist / 1000.0, 2));
            o.put("time", hms(timer));
            if (speed != null && speed > 0) {
                if (foot) {
                    o.put("pace", hms(1000.0 / speed) + "/km");
                } else {
                    o.put("kmh", round(speed * 3.6, 1));
                }
            }
            putIfSet(o, "avgHR", lap.getAvgHeartRate());
            putIfSet(o, "maxHR", lap.getMaxHeartRate());
            if (lap.getAvgCadence() != null) {
                double cadence = lap.getAvgCadence() + orZero(lap.getAvgFractionalCadence());
                // FIT stores running cadence as strides per minute; Garmin Connect shows steps per minute
                o.put(foot ? "cadenceSpm" : "cadence", Math.round(foot ? cadence * 2 : cadence));
            }
            putIfSet(o, "ascentM", lap.getTotalAscent());
            putIfSet(o, "descentM", lap.getTotalDescent());
            putIfSet(o, "avgPowerW", lap.getAvgPower());
            if (lap.getIntensity() != null) {
                o.put("intensity", lap.getIntensity().name().toLowerCase());
            }
            if (lap.getLapTrigger() != null) {
                o.put("trigger", lap.getLapTrigger().name().toLowerCase());
            }
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("activityId", activityId);
        if (sport != null) {
            out.put("sport", sport.name().toLowerCase());
        }
        out.set("laps", laps);
        return out.toString();
    }

    @Tool(description = """
            Strength training sets of an activity, read from the original .FIT file. One entry per
            exercise, in the order first done, with its movement category, its primary and secondary
            muscles (from Garmin's exercise catalogue) and its active sets: reps,
            weight (kg, absent when none was recorded), set time in seconds and the rest after the set
            (restSec). Exercise names are stable across sessions, so they can be used for per-exercise
            trends. Edits made later in Garmin Connect (renamed exercises, corrected reps or weights)
            are not in the .FIT file.""")
    public String getActivitySets(@ToolParam(description = "Garmin activity id") long activityId)
            throws IOException {
        FitMessages fit = decodeFit(activityId);
        Map<String, String> stepNames = new HashMap<>();
        for (ExerciseTitleMesg t : fit.getExerciseTitleMesgs()) {
            if (t.getNumWktStepName() > 0) {
                stepNames.put(t.getExerciseCategory() + "/" + t.getExerciseName(), t.getWktStepName(0));
            }
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("activityId", activityId);
        out.set("exercises", groupSets(fit.getSetMesgs(), stepNames, exerciseCatalogue()));
        return out.toString();
    }

    /**
     * One entry per exercise, in the order first done; a rest period becomes restSec on the set before it.
     * Muscles come from Garmin's exercise catalogue ({@code catalogue} = its "categories" node, may be null).
     */
    ArrayNode groupSets(List<SetMesg> sets, Map<String, String> stepNames, JsonNode catalogue) {
        Map<String, ObjectNode> exercises = new LinkedHashMap<>();
        ObjectNode previous = null;
        for (SetMesg s : sets) {
            if (s.getSetType() == null || s.getSetType() != SetType.ACTIVE) {
                if (previous != null && s.getSetType() != null && s.getSetType() == SetType.REST
                        && s.getDuration() != null) {
                    previous.put("restSec", Math.round(s.getDuration()));
                }
                continue;
            }
            Integer category = s.getNumCategory() > 0 ? s.getCategory(0) : null;
            Integer subtype = s.getNumCategorySubtype() > 0 ? s.getCategorySubtype(0) : null;
            ObjectNode exercise = exercises.computeIfAbsent(category + "/" + subtype, key -> {
                ObjectNode e = mapper.createObjectNode();
                e.put("exercise", exerciseName(category, subtype, stepNames.get(key)));
                String cat = category == null ? "" : ExerciseCategory.getStringFromValue(category);
                e.put("category", cat.isEmpty() ? "Unknown" : words(cat));
                if (catalogue != null && !cat.isEmpty()) {
                    String name = exerciseConstant(cat, subtype);
                    // exercises without a subtype are listed under the category's own name, e.g. PLANK/PLANK
                    JsonNode entry = catalogue.path(cat).path("exercises").path(name.isEmpty() ? cat : name);
                    if (entry.isObject()) {
                        e.set("primaryMuscles", muscles(entry.path("primaryMuscles")));
                        e.set("secondaryMuscles", muscles(entry.path("secondaryMuscles")));
                    }
                }
                e.putArray("sets");
                return e;
            });
            ObjectNode set = ((ArrayNode) exercise.get("sets")).addObject();
            putIfSet(set, "reps", s.getRepetitions());
            if (s.getWeight() != null && s.getWeight() > 0) {
                set.put("kg", round(s.getWeight(), 1));
            }
            if (s.getDuration() != null) {
                set.put("seconds", Math.round(s.getDuration()));
            }
            previous = set;
        }
        return mapper.createArrayNode().addAll(exercises.values());
    }

    // ------------------------------------------------------------------ health

    @Tool(description = "Daily HRV (overnight) summaries for a date range, yyyy-MM-dd.")
    public String getHrvRange(@ToolParam(description = "From date yyyy-MM-dd") String fromDate,
                              @ToolParam(description = "To date yyyy-MM-dd") String toDate) {
        return api.getJson("/hrv-service/hrv/daily/" + fromDate + "/" + toDate, Map.of()).toString();
    }

    @Tool(description = "Daily heart rate for one date (includes resting heart rate), yyyy-MM-dd.")
    public String getDailyHeartRate(@ToolParam(description = "Date yyyy-MM-dd") String date) {
        return api.getJson("/wellness-service/wellness/dailyHeartRate", Map.of("date", date)).toString();
    }

    @Tool(description = "Sleep data for one night, yyyy-MM-dd (the date of the morning you woke up).")
    public String getSleep(@ToolParam(description = "Date yyyy-MM-dd") String date) {
        return api.getJson("/sleep-service/sleep/dailySleep", Map.of("date", date)).toString();
    }

    @Tool(description = "Heart rate zones currently configured in Garmin Connect.")
    public String getHeartRateZones() {
        return api.getJson("/biometric-service/heartRateZones/v2", Map.of()).toString();
    }

    @Tool(description = """
            Garmin fitness age for a date, yyyy-MM-dd: fitness age vs chronological age, the achievable
            fitness age, and its components (body fat, vigorous days and minutes per week, resting HR)
            with the targets Garmin suggests.""")
    public String getFitnessAge(@ToolParam(description = "Date yyyy-MM-dd") String date) {
        return api.getJson("/fitnessage-service/fitnessage/v2/" + date, Map.of()).toString();
    }

    @Tool(description = "Daily user summary for one date (steps, calories, stress, body battery, resting HR), yyyy-MM-dd.")
    public String getDailySummary(@ToolParam(description = "Date yyyy-MM-dd") String date) {
        return api.getJson("/usersummary-service/usersummary/daily", Map.of("calendarDate", date)).toString();
    }

    // ------------------------------------------------------------------ generic

    @Tool(description = """
            Generic read-only GET on the Garmin Connect Personal API, for endpoints not covered by the
            other tools or when a query parameter name has to be adjusted. 'path' is relative and starts
            with '/', e.g. /fitnessage-service/fitnessage/v2/2026-10-25 . 'query' is an optional
            URL query string without '?', e.g. date=2026-10-25&foo=bar .""")
    public String garminGet(@ToolParam(description = "Relative API path starting with /") String path,
                            @ToolParam(required = false, description = "Query string, e.g. a=1&b=2") String query) {
        Map<String, String> q = new HashMap<>();
        if (query != null && !query.isBlank()) {
            for (String pair : query.split("&")) {
                int i = pair.indexOf('=');
                if (i > 0) {
                    q.put(pair.substring(0, i), pair.substring(i + 1));
                }
            }
        }
        return api.getJson(path, q).toString();
    }

    // ------------------------------------------------------------------ helpers

    ObjectNode summarize(JsonNode a) {
        ObjectNode o = mapper.createObjectNode();
        copy(a, o, "activityId", "id");
        copy(a, o, "activityName", "name");
        if (a.has("activityType")) {
            o.put("type", a.path("activityType").path("typeKey").asString(null));
        }
        copy(a, o, "startTimeLocal", "start");
        double distM = a.path("distance").asDouble(0);
        double durS = a.path("movingDuration").asDouble(a.path("duration").asDouble(0));
        if (distM > 0) {
            o.put("km", round(distM / 1000.0, 2));
        }
        if (durS > 0) {
            o.put("duration", hms(durS));
        }
        if (distM > 0 && durS > 0) {
            o.put("pace", hms(durS / (distM / 1000.0)) + "/km");
        }
        copy(a, o, "averageHR", "avgHR");
        copy(a, o, "maxHR", "maxHR");
        copy(a, o, "averageRunningCadenceInStepsPerMinute", "cadenceSpm");
        copy(a, o, "elevationGain", "ascentM");

        // moving time is near zero for padel or strength: the elapsed time is the session length
        double elapsedS = a.path("duration").asDouble(0);
        if (elapsedS - durS >= 60) {
            o.put("elapsed", hms(elapsedS));
        }
        if ("race".equals(a.path("eventType").path("typeKey").asString(null))) {
            o.put("race", true);
        }
        putRounded(a, o, "activityTrainingLoad", "trainingLoad", 1);
        putRounded(a, o, "aerobicTrainingEffect", "aerobicTE", 1);
        putRounded(a, o, "anaerobicTrainingEffect", "anaerobicTE", 1);
        if (a.hasNonNull("trainingEffectLabel")) {
            o.put("teLabel", a.get("trainingEffectLabel").asString().toLowerCase());
        }
        if (a.has("hrTimeInZone_1")) {
            ArrayNode zones = o.putArray("hrZonesSec");
            for (int z = 1; z <= 5; z++) {
                zones.add(Math.round(a.path("hrTimeInZone_" + z).asDouble(0)));
            }
        }
        putRounded(a, o, "calories", "calories", 0);
        copy(a, o, "moderateIntensityMinutes", "moderateMinutes");
        copy(a, o, "vigorousIntensityMinutes", "vigorousMinutes");
        putRounded(a, o, "averageSwolf", "swolf", 0);
        double pool = a.path("poolLength").asDouble(0);
        if (pool > 0) {
            // shortcut: assumes Garmin's centimetres (2500 = 25 m); unconfirmed on a real swim, check unitOfPoolLength if wrong
            o.put("poolLengthM", round(pool / 100.0, 1));
        }
        copy(a, o, "activeLengths", "lengths");
        putRounded(a, o, "strokes", "strokes", 0);
        copy(a, o, "differenceBodyBattery", "bodyBatteryDelta");
        copy(a, o, "steps", "steps");
        putRounded(a, o, "avgPower", "avgPowerW", 0);
        putRounded(a, o, "normPower", "normPowerW", 0);
        putRounded(a, o, "avgStrideLength", "strideCm", 1);
        putRounded(a, o, "avgGroundContactTime", "gctMs", 0);
        putRounded(a, o, "avgVerticalOscillation", "vertOscCm", 1);
        putRounded(a, o, "avgVerticalRatio", "vertRatioPct", 1);
        double gap = a.path("avgGradeAdjustedSpeed").asDouble(0);
        if (gap > 0) {
            o.put("gap", hms(1000.0 / gap) + "/km");
        }

        ObjectNode best = mapper.createObjectNode();
        for (String[] f : new String[][]{{"1000", "1k"}, {"1609", "mile"}, {"5000", "5k"}, {"10000", "10k"},
                {"21098", "half"}, {"42195", "marathon"}}) {
            double s = a.path("fastestSplit_" + f[0]).asDouble(0);
            if (s > 0) {
                best.put(f[1], hms(s));
            }
        }
        if (!best.isEmpty()) {
            o.set("best", best);
        }

        // Run Walk Run sessions: pace of the run segments only, and the time spent walking
        for (JsonNode split : a.path("splitSummaries")) {
            String type = split.path("splitType").asString("");
            double speed = split.path("averageSpeed").asDouble(0);
            if (type.equals("RWD_RUN") && speed > 0) {
                o.withObjectProperty("runWalk").put("runPace", hms(1000.0 / speed) + "/km")
                        .put("runKm", round(split.path("distance").asDouble(0) / 1000.0, 2));
            } else if (type.equals("RWD_WALK")) {
                o.withObjectProperty("runWalk").put("walkTime", hms(split.path("duration").asDouble(0)));
            }
        }
        return o;
    }

    private static void putRounded(JsonNode from, ObjectNode to, String field, String as, int decimals) {
        JsonNode v = from.get(field);
        if (v != null && v.isNumber()) {
            if (decimals == 0) {
                to.put(as, Math.round(v.asDouble()));
            } else {
                to.put(as, round(v.asDouble(), decimals));
            }
        }
    }

    private static void copy(JsonNode from, ObjectNode to, String field, String as) {
        JsonNode v = from.get(field);
        if (v != null && !v.isNull()) {
            to.set(as, v);
        }
    }

    private static JsonNode firstArray(JsonNode json) {
        for (String k : List.of("activityList", "activities", "items", "data")) {
            if (json.path(k).isArray()) {
                return json.get(k);
            }
        }
        return null;
    }

    /** Downloads the original .FIT file once and keeps it in {@code <data-dir>/fit}. */
    private Path fetchFit(long activityId) throws IOException {
        Path dir = Path.of(props.dataDir(), "fit");
        Path target = dir.resolve(activityId + ".fit");
        if (Files.isRegularFile(target) && Files.size(target) > 0) {
            return target;
        }
        byte[] data = api.getBytes("/download-service/files/activity/" + activityId, Map.of());
        Files.createDirectories(dir);
        if (!isZip(data)) {
            Files.write(target, data);
            return target;
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (!e.isDirectory() && e.getName().toLowerCase().endsWith(".fit")) {
                    Files.write(target, zip.readAllBytes());
                    return target;
                }
            }
        }
        throw new GarminApiClient.GarminApiException("The download of activity " + activityId
                + " is a zip without a .fit file");
    }

    private FitMessages decodeFit(long activityId) throws IOException {
        try (InputStream in = Files.newInputStream(fetchFit(activityId))) {
            return new FitDecoder().decode(in);
        } catch (FitRuntimeException e) {
            throw new GarminApiClient.GarminApiException("Cannot decode the .FIT file of " + activityId
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * Garmin's exercise name (e.g. "Incline Smith Machine Bench Press"), falling back to the category,
     * then to the workout step name for custom exercises, which have no known category.
     */
    static String exerciseName(Integer category, Integer subtype, String stepName) {
        String cat = category == null ? "" : ExerciseCategory.getStringFromValue(category);
        if (cat.isEmpty() || cat.equals("UNKNOWN")) {
            return stepName == null || stepName.isBlank() ? "Unknown exercise" : stepName;
        }
        String name = exerciseConstant(cat, subtype);
        return words(name.isEmpty() ? cat : name);
    }

    /** The SDK constant of an exercise, e.g. INCLINE_SMITH_MACHINE_BENCH_PRESS; "" when the SDK has no name for it. */
    private static String exerciseConstant(String cat, Integer subtype) {
        if (subtype == null) {
            return "";
        }
        try {
            // the SDK has one name list per category, e.g. BENCH_PRESS -> BenchPressExerciseName
            return (String) Class.forName("com.garmin.fit." + words(cat).replace(" ", "") + "ExerciseName")
                    .getMethod("getStringFromValue", Integer.class).invoke(null, subtype);
        } catch (ReflectiveOperationException e) {
            return "";
        }
    }

    private ArrayNode muscles(JsonNode list) {
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode m : list) {
            if (!m.asString("").isBlank()) {
                out.add(words(m.asString()));
            }
        }
        return out;
    }

    /**
     * Garmin's exercise catalogue, cached in {@code <data-dir>/exercises.json} and refreshed after 30 days.
     * Returns its "categories" node, or null when there is no copy at all: sets still work, without muscles.
     */
    private synchronized JsonNode exerciseCatalogue() {
        if (catalogue != null) {
            return catalogue;
        }
        Path file = Path.of(props.dataDir(), "exercises.json");
        try {
            boolean fresh = Files.isRegularFile(file)
                    && Files.getLastModifiedTime(file).toInstant().isAfter(Instant.now().minus(Duration.ofDays(30)));
            if (!fresh) {
                try {
                    byte[] data = api.getExerciseCatalogue();
                    mapper.readTree(data);
                    Files.createDirectories(file.getParent());
                    Files.write(file, data);
                } catch (GarminApiClient.GarminApiException | JacksonException e) {
                    log.warn("Exercise catalogue not refreshed ({}); {}", e.getMessage(),
                            Files.isRegularFile(file) ? "using the cached copy" : "sets will have no muscles");
                }
            }
            if (Files.isRegularFile(file)) {
                catalogue = mapper.readTree(Files.readAllBytes(file)).path("categories");
            }
        } catch (IOException | JacksonException e) {
            log.warn("Cannot read the exercise catalogue: {}", e.getMessage());
        }
        return catalogue;
    }

    /** BENCH_PRESS -> Bench Press */
    private static String words(String constant) {
        StringBuilder b = new StringBuilder();
        for (String w : constant.toLowerCase().split("_")) {
            if (!w.isEmpty()) {
                b.append(b.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return b.toString();
    }

    private static void putIfSet(ObjectNode o, String name, Number v) {
        if (v != null) {
            o.put(name, v.longValue());
        }
    }

    private static float orZero(Float v) {
        return v == null ? 0f : v;
    }

    private static boolean isZip(byte[] d) {
        return d.length > 4 && d[0] == 'P' && d[1] == 'K';
    }

    private static double round(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }

    private static String hms(double seconds) {
        long s = Math.round(seconds);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
    }
}
