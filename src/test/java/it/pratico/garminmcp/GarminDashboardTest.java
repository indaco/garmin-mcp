package it.pratico.garminmcp;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GarminDashboardTest {

    private static final LocalDate FROM = LocalDate.parse("2026-08-03"), TO = LocalDate.parse("2026-10-26");

    private static String ranges(List<LocalDate[]> r) {
        return r.stream().map(x -> x[0] + ".." + x[1]).toList().toString();
    }

    @Test
    void emptyCacheFetchesTheWholeRange() {
        assertEquals("[2026-08-03..2026-10-26]", ranges(GarminDashboard.toFetch(null, null, FROM, TO)));
    }

    @Test
    void warmCacheFetchesOnlyTheLastTwoDaysAndTheNewOnes() {
        // last sync on 20 Oct: refetch 19-20 Oct (they could still change) and everything after
        assertEquals("[2026-10-19..2026-10-26]",
                ranges(GarminDashboard.toFetch(FROM, LocalDate.parse("2026-10-20"), FROM, TO)));
        assertEquals("[2026-10-25..2026-10-26]", ranges(GarminDashboard.toFetch(FROM, TO, FROM, TO)));
    }

    @Test
    void longerWindowAlsoFetchesTheMissingStart() {
        assertEquals("[2026-05-04..2026-08-02, 2026-10-25..2026-10-26]",
                ranges(GarminDashboard.toFetch(FROM, TO, LocalDate.parse("2026-05-04"), TO)));
    }

    @Test
    void cacheOutsideTheRangeIsIgnored() {
        assertEquals("[2026-08-03..2026-10-26]",
                ranges(GarminDashboard.toFetch(LocalDate.parse("2025-01-01"), LocalDate.parse("2025-03-01"), FROM, TO)));
    }

    @Test
    void recoveryRowKeepsTheDashboardFields() {
        ObjectMapper json = JsonMapper.builder().build();
        GarminDashboard d = new GarminDashboard(null, null, null, json);
        var row = d.recoveryRow("2026-10-25",
                json.readTree("""
                        {"sleepTimeSeconds":21240,"deepSleepSeconds":6000,"remSleepSeconds":840,
                         "sleepScores":{"overall":{"value":44}},"sleepNeed":{"actual":450},
                         "sleepStartTimestampLocal":1792885868000}"""),
                json.readTree("""
                        {"restingHeartRate":49,"bodyBatteryHighestValue":53,"bodyBatteryLowestValue":12,
                         "totalSteps":15037,"dailyStepGoal":14520,"floorsAscended":93.14501}"""));
        assertEquals("{\"date\":\"2026-10-25\",\"sleepH\":5.9,\"deep\":1.67,\"rem\":0.23,\"sleepScore\":44,"
                + "\"needH\":7.5,\"bedMin\":1431,\"rhr\":49,\"bbHigh\":53,\"bbLow\":12,\"steps\":15037,"
                + "\"stepGoal\":14520,\"floors\":93}", row.toString());
    }
}
