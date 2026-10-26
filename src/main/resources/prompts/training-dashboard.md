You are a data-minded endurance coach and a front-end designer. Using the Garmin tools of the
`garmin-personal` MCP server, build me a personal **training + recovery dashboard** covering the
last **{{weeks}} weeks** (today is {{today}}).

The point of this dashboard is what social training apps do not show: how training load and
recovery (HRV, sleep, resting heart rate, body battery) move together. Answer in my language.

## 1. Collect the data (tools, in this order)

1. `getHeartRateZones` - my configured HR zones.
2. `listActivities` with `startDate` = {{startDate}}, `endDate` = {{today}}, `limit` = 200.
   If the summary lacks a field you need, call it again with `raw=true`.
3. `getHrvRange` from {{startDate}} to {{today}}. If Garmin rejects the range, split it into
   chunks of 4 weeks.
4. For the **last 14 days** only: `getSleep` and `getDailySummary`, one call per day.
5. Call `getActivityLaps` only for the 3 most recent activities, to show their laps (km splits,
   intervals, warm-up/cool-down).

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
| Padel | any key containing `padel` |
| Other | all other keys; in chat, name the keys that are in this group |

- **Weekly volume** for each type: distance (km), moving time, number of sessions.
- **This week vs. the previous 4-week average** for distance, time and sessions (delta in %).
- **Training load** per activity = moving minutes x intensity factor, where intensity factor is
  avg HR / max HR (from the zones) - label it clearly as an *estimate*. Then the acute (7-day)
  vs. chronic (28-day) load ratio, with the 0.8-1.3 band highlighted.
- **Running**: average pace and average HR per week, plus efficiency (metres per heartbeat =
  speed in m/min / avg HR) to show aerobic progress.
- **Per type**, for the type tabs: sessions, total and average moving time, average and max HR,
  share of the total load. Add distance and pace for Run and Walk, and distance, average speed
  (km/h) and ascent for Bike. Strength and Padel have no distance: never show 0 km for them.
- **Time in HR zones** per week, from the activities' average HR mapped onto my zones (say that
  it is approximate when per-second data is not used).
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

1. **KPI tiles**: the numbers for each type from section 2, this week compared with the 4-week
   average.
2. **Weekly chart**: sessions and moving time for each week (bars). Add distance for Run, Walk
   and Bike.
3. **Type trends**: Run - pace and efficiency for each week, HR zone distribution for each week.
   Bike - average speed and ascent for each week. Walk - pace for each week. Strength and Padel -
   average HR and average session length for each week.
4. **Activities**: the table for that type only, with laps where you got them.

Design: dark, clean, data-dense but airy. Use one fixed colour for each type, on its tab, chip,
bars and lines in every chart. Use tabular numbers. Make the page work down to phone width (the
tab bar scrolls horizontally, the page does not). Make the tabs work with the keyboard. Put
tooltips on every chart and units on every axis. Use your own visual identity: no third-party app
names, logos or brand colours.

Finish with two lines in chat: what is in the dashboard, and anything that was missing.
