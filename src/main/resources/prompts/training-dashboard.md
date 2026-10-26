You are a data-minded endurance coach and a front-end designer. Using the Garmin tools of the
`garmin-personal` MCP server, build me a personal **training + recovery dashboard** covering the
last **{{weeks}} weeks** (today is {{today}}).

The point of this dashboard is what social training apps do not show: how training load and
recovery (HRV, sleep, resting heart rate, body battery) move together. Answer in my language.

## 1. Collect the data (tools, in this order)

1. `getHeartRateZones` - my configured HR zones.
2. `listActivities` with `startDate` = {{startDate}}, `endDate` = {{today}}, `limit` = 200.
   If the summary lacks a field you need, call it again with `raw=true`. Do not send
   `activityType`, because Garmin rejects it. Group by type yourself (section 2).
3. `getHrvRange` from {{startDate}} to {{today}}. If Garmin rejects the range, split it into
   chunks of 4 weeks.
4. For the **last 14 days** only: `getSleep` and `getDailySummary`, one call per day.
5. `getActivityLaps` for the 3 most recent activities, every race (`race: true`) and every run
   over 15 km. Show their laps (km splits, intervals, warm-up and cool-down).
6. `getActivitySets` for every Strength activity in the period (at most the 30 most recent; if
   you skip some, say so): exercises with reps, weight and time for each set.

If a tool fails, say which one and why in one line, keep going, and show "n/a" in the affected
widget. **Never invent, interpolate or estimate missing values.**

## 2. Compute

Group every activity into one of these types by its `type` key:

| Type | Garmin type keys |
|---|---|
| Run | `running`, `trail_running`, `treadmill_running`, `track_running`, any key ending in `_running` |
| Strength | `strength_training`, any key containing `strength` |
| Walk | `walking`, `hiking`, `casual_walking`, `speed_walking` |
| Bike | `cycling`, `road_biking`, `mountain_biking`, `gravel_cycling`, `indoor_cycling`, `virtual_ride`, any key containing `biking` or `cycling` |
| Padel | `paddelball`, any key containing `padel` or `paddel` |
| Other | all other keys; in chat, name the keys that are in this group |

- **Session time**: `duration` is moving time. Strength and Padel have little moving time, so use
  `elapsed` for them when it is present. Run, Walk and Bike use `duration`.
- **Weekly volume** for each type: distance (km), time, number of sessions.
- **The last 7 days compared with the 4-week average before them** for distance, time and
  sessions (difference in %).
- **Training load** of each activity = Garmin's `trainingLoad`. Only when it is missing, use
  moving minutes x avg HR / max HR (from the zones), and label it an *estimate* where you show it.
  Then calculate the acute (7-day) to chronic (28-day) load ratio, and highlight the 0.8-1.3 band.
- **Running**: average pace and average HR for each week, and efficiency (metres per heartbeat =
  speed in m/min / avg HR) to show aerobic progress. Best efforts in the period: the fastest of
  each `best` distance (1k, mile, 5k, 10k, half, marathon), with its date and activity. Races
  (`race: true`). For each week, the share of HR-zone time in Z1-2 (easy) and Z4-5 (hard). Also
  the weekly running form averages (cadence, `strideCm`, `gctMs`, `vertOscCm`, `vertRatioPct`,
  `avgPowerW`, `normPowerW`), only for the metrics that are present. Run Walk Run sessions
  (`runWalk`): the pace of the run segments against the overall pace, and the walk time. Aerobic
  and anaerobic training effect for each run, and label counts for each week.
- **Per type**, for the type tabs: sessions, total and average moving time, average and max HR,
  share of the total load. Add distance and pace for Run and Walk, and distance, average speed
  (km/h) and ascent for Bike. Strength and Padel have no useful distance or pace. Do not show them,
  also when Garmin records a few metres.
- **Strength, per exercise** (group the sets by the `exercise` name across sessions): sessions,
  last date, total sets and reps. For each session: the top set (heaviest weight, with its reps),
  the volume (sum of kg x reps) and the estimated 1RM of the best set. Use Epley: kg x (1 + reps /
  30), only for sets of 1-12 reps, and label it an *estimate*. Exercises with reps but no kg: max
  reps in a set and total reps. Timed exercises (no reps): longest set in seconds. For each
  exercise, the change of the top set and of the estimated 1RM from its first to its last session
  in the period. Weekly completed sets for each movement `category` (Bench Press, Curl, Squat...).
  A set with 0 reps was not completed: list it, but do not use it in any number.
- **Time in HR zones** for each week and each type, from the `hrZonesSec` of each activity. Only
  for an activity without it: put all its minutes in the zone of its average HR, and label it
  *approximate*.
- **Recovery**: nightly HRV vs. its 7-day rolling average, resting HR trend, sleep duration and
  score, body battery high/low.

## 3. Build the dashboard

Pick the output that fits where you are running:

- **Claude desktop / claude.ai** (artifacts available): one self-contained React artifact, with
  Recharts for the charts.
- **Claude Code** or any client without artifacts: write a single self-contained file
  `garmin-dashboard.html` in the current directory (Chart.js from cdnjs, everything else inline),
  then tell me its path and the command to open it (`xdg-open` / `open`).

Either way, no runtime calls to Garmin: embed the processed data as a constant at the top of the
file, aggregated - not raw Garmin JSON. Tag every activity and every daily value with its type.
Then the page can filter in the browser without new data.

### Tabs and filter

Put a tab bar under the header: **All**, **Run**, **Strength**, **Walk**, **Bike**, **Padel**, and
**Other** only if it has activities. Each tab label shows its session count. A type with no
activities in the period stays visible and shows an empty state ("no sessions in the last
{{weeks}} weeks"), not empty charts. The URL hash keeps the selected tab, so a reload or a shared
link opens the same tab.

On the **All** tab, put a row of toggle chips: one for each type, in the colour of that type, all
on by default. The chips filter the header totals, weekly volume, consistency calendar, the load
bars of load vs. recovery, and the activities table. Recovery data (HRV, sleep, resting HR, body
battery) is not per type, and the filter never changes it. The **load ratio always uses all
activities**: say this next to the tile when a filter is active.

### All tab, top to bottom

1. **Header**: period covered, total km / hours / sessions, and 4 KPI tiles with deltas
   (distance, time, load ratio, last-night HRV vs. baseline).
2. **Weekly volume**: stacked bars for each type, with a line for the 4-week moving average.
3. **Consistency calendar**: a GitHub-style heatmap of daily training minutes.
4. **Load vs. recovery**: one chart with daily load (bars, stacked by type) and HRV (line) on
   twin axes - the heart of the dashboard.
5. **Recovery panel**: HRV with baseline band, resting HR, sleep hours and score (last 14 days).
6. **Recent activities**: table with date, type, name, distance, time, pace, avg HR, ascent;
   the 3 most recent expandable to show laps.
7. **Coach notes**: at most 5 bullet points, each tied to a specific number in the dashboard
   (e.g. "HRV 12% below baseline for 3 nights while load ratio is 1.4"). Training suggestions
   only, no medical advice.

### Type tabs

Each type tab shows only that type's data:

1. **KPI tiles**: the numbers for each type from section 2, the last 7 days compared with the
   4-week average, and Garmin training load.
2. **Weekly chart**: sessions and time for each week (bars). Add distance for Run, Walk and Bike.
3. **Time in HR zones** for each week, stacked Z1-Z5.
4. The sections of that type, below.
5. **Activities**: the table for that type only, with laps where you got them.

If a type has only one session, show a session card (its numbers and zones) and no trend
charts ("one session so far"). Trends start at 2 sessions.

**Run** adds:

1. **Best efforts**: the fastest 1k, mile, 5k, 10k, half (and marathon) in the period, with date
   and activity name.
2. **Races**: one row for each race, side by side: date, name, time, pace, avg HR, avg power,
   aerobic training effect.
3. **Aerobic fitness**: a scatter of pace against avg HR, one dot for each run, coloured from
   oldest to newest. Also pace and efficiency for each week.
4. **Intensity**: the easy (Z1-2) and hard (Z4-5) share of running time for each week.
5. **Running form**: weekly cadence, stride, ground contact time, vertical oscillation and ratio,
   power. Use small multiples, one for each metric that is present, each with its unit.
6. **Run Walk Run**: for each session with `runWalk`, the pace of the run segments against the
   overall pace, and the walk time.
7. **Training effect**: aerobic and anaerobic TE for each run (dots over time), and label counts
   for each week.

**Walk** adds pace and avg HR for each week, and steps, cadence and body battery change for each
walk. **Bike** adds average speed and ascent for each week. **Padel** adds avg HR and time in Z3+
for each session, calories and training effect.

The **Strength** tab adds, after the weekly chart and a weekly chart of completed sets for each
movement category:

1. **Exercises**: a table with one row for each exercise. Show the name, sessions, last date and
   last top set (for example "70 kg x 6"). Also show the best estimated 1RM, the change since the
   first session (arrow and %) and a sparkline of the top set. The user can sort by every column
   and use a search box.
2. **Exercise detail**: when the user selects a row (click or Enter), a panel opens with
   - a trend chart over time for each session: top-set weight and estimated 1RM (lines, kg) and
     volume (bars, kg). Use reps instead of kg for exercises without weight, and seconds for timed
     ones;
   - every session of that exercise, newest first: date, workout name and its sets
     (for example "35 x 12, 50 x 10, 60 x 8, 70 x 6").
   The URL hash also keeps the selected exercise (for example `#strength/barbell-box-squat`).
3. **Sessions**: each Strength activity opens to show all its exercises and sets, in the order
   the user did them.

If an exercise has only one session, show its sets but no trend chart ("one session so far").

Design: dark, clean, data-dense but airy. Use one fixed colour for each type, on its tab, chip,
bars and lines in every chart. Use tabular numbers. Make the page work down to phone width (the
tab bar scrolls horizontally, the page does not). Make the tabs work with the keyboard. Put
tooltips on every chart and units on every axis. Use your own visual identity: no third-party app
names, logos or brand colours.

Finish with two lines in chat: what is in the dashboard, and anything that was missing.
