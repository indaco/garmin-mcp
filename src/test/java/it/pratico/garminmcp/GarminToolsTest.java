package it.pratico.garminmcp;

import com.garmin.fit.BenchPressExerciseName;
import com.garmin.fit.ExerciseCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
