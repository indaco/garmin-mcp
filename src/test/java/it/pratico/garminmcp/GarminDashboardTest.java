package it.pratico.garminmcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
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
    void coveredRangeNeverClaimsDaysThatWereNotFetched() {
        LocalDate cf = FROM, ct = TO;
        // three weeks without calls, then a 1-week window: only the new range is fetched and covered
        LocalDate from = LocalDate.parse("2026-11-16"), to = LocalDate.parse("2026-11-23");
        assertEquals("[2026-11-16..2026-11-23]", ranges(GarminDashboard.toFetch(cf, ct, from, to)));
        LocalDate[] c = GarminDashboard.covered(cf, ct, from, to);
        assertEquals("2026-11-16..2026-11-23", c[0] + ".." + c[1]);
        // a later, longer window fetches the gap instead of trusting it
        assertEquals("[2026-10-01..2026-11-15, 2026-11-22..2026-11-23]",
                ranges(GarminDashboard.toFetch(c[0], c[1], LocalDate.parse("2026-10-01"), to)));
    }

    @Test
    void coveredRangeJoinsTouchingAndOverlappingRanges() {
        LocalDate[] touching = GarminDashboard.covered(FROM, TO, TO.plusDays(1), LocalDate.parse("2026-11-02"));
        assertEquals("2026-08-03..2026-11-02", touching[0] + ".." + touching[1]);
        LocalDate[] overlap = GarminDashboard.covered(FROM, TO, LocalDate.parse("2026-07-01"), TO);
        assertEquals("2026-07-01..2026-10-26", overlap[0] + ".." + overlap[1]);
        LocalDate[] empty = GarminDashboard.covered(null, null, FROM, TO);
        assertEquals("2026-08-03..2026-10-26", empty[0] + ".." + empty[1]);
    }

    @Test
    void recoveryIsRefreshedFromTheLastSyncNotFromToday() {
        // last call on Monday 19 Oct, next on Monday 26 Oct: 18 and 19 Oct may be incomplete
        assertEquals(LocalDate.parse("2026-10-18"), GarminDashboard.recoveryRefreshFrom(LocalDate.parse("2026-10-19"), TO));
        assertEquals(LocalDate.parse("2026-10-25"), GarminDashboard.recoveryRefreshFrom(null, TO));
        assertEquals(LocalDate.parse("2026-10-25"), GarminDashboard.recoveryRefreshFrom(TO.plusDays(5), TO));
    }

    @Test
    void cacheWithTheWrongShapeStartsAgain(@TempDir Path dir) throws Exception {
        ObjectMapper json = JsonMapper.builder().build();
        GarminDashboard d = new GarminDashboard(null, null, new GarminProperties("", "", dir.toString()), json);
        Files.writeString(dir.resolve("dashboard-cache.json"), "{\"activities\":[1,2],\"activitiesFrom\":\"x\"}");
        assertEquals("{}", d.load().toString());
        Files.writeString(dir.resolve("dashboard-cache.json"), "{\"activities\":{},\"recoveryTo\":\"2026-10-26\"}");
        assertEquals("2026-10-26", d.load().path("recoveryTo").asString());
    }

    @Test
    void atomicWriteLeavesNoTempFile(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("sub").resolve("a.fit");
        GarminTools.writeAtomically(f, new byte[]{1, 2, 3});
        GarminTools.writeAtomically(f, new byte[]{4});
        assertEquals(1, Files.size(f));
        assertEquals(1, Files.list(f.getParent()).count());
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
