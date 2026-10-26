package it.pratico.garminmcp;

import com.garmin.fit.BenchPressExerciseName;
import com.garmin.fit.ExerciseCategory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GarminToolsTest {

    @Test
    void exerciseNameFromTheSdkNameList() {
        assertEquals("Incline Smith Machine Bench Press", GarminTools.exerciseName(ExerciseCategory.BENCH_PRESS,
                BenchPressExerciseName.INCLINE_SMITH_MACHINE_BENCH_PRESS, "Panca inclinata"));
    }

    @Test
    void exerciseNameFallsBackToTheCategory() {
        assertEquals("Leg Raise", GarminTools.exerciseName(ExerciseCategory.LEG_RAISE, null, null));
        assertEquals("Curl", GarminTools.exerciseName(ExerciseCategory.CURL, 9999, null));
    }

    @Test
    void customExerciseUsesTheWorkoutStepName() {
        assertEquals("My Press", GarminTools.exerciseName(ExerciseCategory.UNKNOWN, 3, "My Press"));
        assertEquals("Unknown exercise", GarminTools.exerciseName(null, null, null));
    }

    private final GarminTools tools = new GarminTools(null, null, JsonMapper.builder().build());
    private final ObjectMapper json = JsonMapper.builder().build();

    @Test
    void summaryShowsElapsedTimeWhenMovingTimeIsShort() {
        JsonNode padel = json.readTree("""
                {"activityId":1,"activityName":"Padel","startTimeLocal":"2026-10-08 17:34:51",
                 "activityType":{"typeKey":"paddelball"},"eventType":{"typeKey":"uncategorized"},
                 "distance":81.85,"duration":1040.11,"movingDuration":77.0,"averageHR":96.0,"maxHR":138.0,
                 "calories":96.0,"aerobicTrainingEffect":0.4000000059604645,"anaerobicTrainingEffect":0.0,
                 "trainingEffectLabel":"RECOVERY","activityTrainingLoad":5.1422882080078125,
                 "differenceBodyBattery":-1,"splitSummaries":[],
                 "hrTimeInZone_1":596.769,"hrTimeInZone_2":25.005,"hrTimeInZone_3":9.998,
                 "hrTimeInZone_4":0.0,"hrTimeInZone_5":0.0}""");
        ObjectNode s = tools.summarize(padel);
        assertEquals("1:17", s.get("duration").asString());
        assertEquals("17:20", s.get("elapsed").asString());
        assertEquals("[597,25,10,0,0]", s.get("hrZonesSec").toString());
        assertEquals(5.1, s.get("trainingLoad").asDouble());
        assertEquals(0.4, s.get("aerobicTE").asDouble());
        assertEquals("recovery", s.get("teLabel").asString());
        assertEquals(-1, s.get("bodyBatteryDelta").asInt());
        assertFalse(s.has("race"));
        assertFalse(s.has("best"));
        assertFalse(s.has("runWalk"));
    }

    @Test
    void summaryOfARaceHasBestEffortsAndRunWalkSegments() {
        JsonNode race = json.readTree("""
                {"activityId":2,"activityName":"Half Marathon","startTimeLocal":"2026-10-18 08:11:58",
                 "activityType":{"typeKey":"running"},"eventType":{"typeKey":"race"},
                 "distance":21363.42,"duration":7849.83,"movingDuration":7845.71,"averageHR":162.0,
                 "avgPower":306.0,"normPower":316.0,"avgStrideLength":102.5,"avgGroundContactTime":294.8,
                 "avgVerticalOscillation":8.86,"avgVerticalRatio":8.64,"avgGradeAdjustedSpeed":2.72,
                 "fastestSplit_1000":330.3,"fastestSplit_5000":1679.05,"fastestSplit_21098":7748.61,
                 "splitSummaries":[
                   {"splitType":"RWD_WALK","duration":837.0,"distance":1549.85,"averageSpeed":1.852},
                   {"splitType":"INTERVAL_ACTIVE","duration":7849.83,"distance":21363.45,"averageSpeed":2.722},
                   {"splitType":"RWD_RUN","duration":7012.75,"distance":19813.57,"averageSpeed":2.825}]}""");
        ObjectNode s = tools.summarize(race);
        assertTrue(s.get("race").asBoolean());
        assertFalse(s.has("elapsed"));
        assertEquals("{\"1k\":\"5:30\",\"5k\":\"27:59\",\"half\":\"2:09:09\"}", s.get("best").toString());
        assertEquals("{\"walkTime\":\"13:57\",\"runPace\":\"5:54/km\",\"runKm\":19.81}", s.get("runWalk").toString());
        assertEquals("6:08/km", s.get("gap").asString());
        assertEquals(306, s.get("avgPowerW").asInt());
        assertEquals(295, s.get("gctMs").asInt());
        assertEquals(8.9, s.get("vertOscCm").asDouble());
    }
}
