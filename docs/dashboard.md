# The training dashboard

The `training-dashboard` prompt tells Claude to build an interactive dashboard from your Garmin
data. The dashboard puts training load next to recovery: HRV, sleep, resting heart rate and body
battery. This page lists what each part of the dashboard shows and how it calculates its numbers.

To build or update the dashboard, see
[The training dashboard prompt](../README.md#the-training-dashboard-prompt) in the README.

## Contents

- [The data](#the-data)
- [Tabs, filter and time window](#tabs-filter-and-time-window)
- [All tab](#all-tab)
- [Run tab](#run-tab)
- [Strength tab](#strength-tab)
- [Walk, Hike, Bike, Swim and Padel tabs](#walk-hike-bike-swim-and-padel-tabs)
- [Estimates and formulas](#estimates-and-formulas)
- [What Garmin does not provide](#what-garmin-does-not-provide)

## The data

Claude gets all the data with one call to the `getDashboardData` tool:

| Field | Contents |
|---|---|
| `activities` | The activities of the selected weeks, as compact summaries. Strength sessions have their sets, rest and muscles. The 3 most recent activities, every race and every run over 15 km have their laps. |
| `previousActivities` | The activities of the weeks before, for the period comparison. |
| `hrv` | One row for each night: HRV, its weekly average, its status and Garmin's balanced range. |
| `recovery` | One row for each of the last 14 days. Each row has sleep by stage, sleep score, sleep need and bedtime. It also has resting HR, body battery, stress, steps, step goal and floors. |
| `zones`, `fitnessAge` | Your HR zones and Garmin's fitness age. |
| `sync` | The number of Garmin requests and new activities of this call, and the number of days that it could not fetch. |

The server keeps the data in `garmin.data-dir/dashboard-cache.json`. Each call asks Garmin only for
the days that the cache does not have, plus the last two days, because Garmin can still change
them. The new data of a day replaces its old data, so a deleted activity also goes away.

The page contains all its data, so it makes no calls to Garmin. The data is between the comments
`/* garmin-data:start */` and `/* garmin-data:end */`: the `getDashboardData` result, the coach
notes and a version number. The `training-dashboard-update` prompt replaces only this block. When
the version of a page is older than the current build instructions, the prompt builds a new page,
so that the page gets the newest features.

## Tabs, filter and time window

- **Tabs**: All, Run, Strength, Walk, Bike and Padel. Hike, Swim and Other show only when you have
  these activities. Each tab shows its number of sessions.
- **Sport types**: each type has a colour and an icon on its tab, its filter chip and in the tables.
  The Other type shows each activity with a readable name, for example HIIT or Yoga.
- **Filter**: on the All tab, a chip for each type. The filter changes the totals, the weekly
  volume, the calendar, the load bars and the activities table. It does not change the recovery
  data or the load ratio, which always uses all activities.
- **Time window**: presets (last 4, 8 or 12 weeks, and all) and a from-week and a to-week selector.
  All training widgets follow the window. The widgets about today do not: readiness, fitness age
  and the 14-day recovery panel.
- **Links**: the URL keeps the tab, the selected exercise and the window, for example
  `#run@2026-09-07_2026-10-26`. A reload or a shared link opens the same view.
- **Theme**: a dropdown with dark, light and high-contrast themes, and System, which follows your
  operating system. Midnight, a dark theme, is the default.
- **Few sessions**: a type with no sessions shows an empty state. A type with one session shows a
  session card instead of trend charts. Trends start at 2 sessions.

## All tab

| Widget | What it shows |
|---|---|
| Header | Period, total km, hours and sessions. Tiles: distance and time of the last 7 days against the 4-week average, the load ratio, and last night's HRV against its balanced range. |
| Readiness today | A verdict ("go easy", "normal" or "ready to push") and its four inputs. See [Readiness](#readiness). |
| Fitness age | Garmin's fitness age against your actual age, the achievable fitness age, and the components with Garmin's targets. |
| Period comparison | Sessions, km, time and load of the window against the same number of weeks before it, for each type and in total. |
| Records | The best efforts, the longest run and the longest ride. Also the heaviest top set and the best estimated 1RM of each exercise. Each record has its date. |
| Weekly volume | Time for each week, stacked by type, with a 4-week moving average. |
| Load safety | The weekly load with its ramp (the weeks above +10% are highlighted), and the monotony and strain of the last 7 days. |
| Intensity minutes | The intensity minutes of each week against the guideline of 150 minutes. |
| Consistency | A heatmap of daily training minutes. Point to a day (or touch it on a phone) to see its activities. |
| Load vs. recovery | Daily load, stacked by type, and nightly HRV with its balanced range. |
| Recovery | The last 14 days: HRV, resting HR, sleep by stage with sleep score and sleep need, bedtime regularity, body battery. |
| Recent activities | Date, type, name, distance, time, pace, average HR and ascent of each activity. Rows with laps open to show them. |
| Coach notes | At most 5 training notes. Each note refers to a number in the dashboard. They are not medical advice. |

## Run tab

| Widget | What it shows |
|---|---|
| Tiles | Sessions, time, average session, average and max HR, training load and its share of the total, distance, average pace. |
| Weekly charts | Time and sessions, distance, time in HR zones. |
| Pace and efficiency | Weekly pace and metres per heartbeat. |
| Best efforts | The fastest 1 km, mile, 5 km, 10 km, half and marathon inside any run of the window. |
| Races | The activities that Garmin marks as a race, side by side: time, pace, HR, power, training effect. |
| Aerobic fitness | The pace of each run against its average HR. A faster pace at the same HR shows better fitness. |
| Easy and hard | The share of running time in zones 1-2 and in zones 4-5, each week. |
| Running form | Weekly cadence, stride length, ground contact time, vertical oscillation and ratio, and power. |
| Run Walk Run | For sessions with run and walk segments: the pace of the run segments against the overall pace, and the walk time. |
| Training effect | Garmin's aerobic and anaerobic training effect of each run, and its labels each week. |
| Race predictor | Predicted times for 5 km, 10 km, half and marathon. See [Race predictor](#race-predictor). |
| Pace split | First half and second half pace of each race and long run: even, negative or positive split. |
| Long run share | The long run of each week as a % of the weekly km. The weeks above 35% are flagged. |
| Grade-adjusted pace | Weekly pace against grade-adjusted pace, and the ascent. |

## Strength tab

| Widget | What it shows |
|---|---|
| Tiles and weekly charts | Sessions, time, HR, training load, time in HR zones. |
| Sets per movement category | Completed sets of each week, for each exercise category that the watch recorded. |
| Muscle-group balance | The average completed sets per week of each muscle group, against the usual range of 10-20 sets. Also the push:pull ratio. See [Muscle groups](#muscle-groups). |
| Exercises | One row for each exercise, grouped by muscle group: sessions, last date, last top set, best estimated 1RM, change since the first session, trend. You can sort, search and filter by group. |
| Exercise detail | A trend chart of the top set, the estimated 1RM and the volume. Every session of the exercise, with each set's % of the 1RM and the rest after it. |
| Sessions | Every strength session, with its exercises and sets in the order you did them. |

A set with 0 reps was not completed: the dashboard lists it, but does not use it in any number.
For an exercise with no weight, the dashboard shows reps instead of kg. The sets are what the watch
recorded: changes that you make later in Garmin Connect are not in the data.

## Walk, Hike, Bike, Swim and Padel tabs

Each tab has the tiles, the weekly charts and the time in HR zones of its sport. It also shows:

| Tab | Also shows |
|---|---|
| Walk | Pace and HR each week, steps and cadence of each walk, body battery change, daily steps against the step goal with floors (last 14 days). |
| Hike | Vertical metres per hour. |
| Bike | Average speed and ascent each week, ascent of each ride, indoor and outdoor rides. |
| Swim | Pace per 100 m, SWOLF, lengths and strokes. |
| Padel | The share of time in zones 4-5, peak HR, calories per hour and training effect. Also its load against your average run of the same length. |

Strength and Padel use the elapsed time of a session, because their moving time is almost zero.
They show no distance or pace.

## Estimates and formulas

The dashboard uses Garmin's own training load, HR zones and training effect. It labels every
value that it calculates itself as an estimate.

### Load

- **Training load**: Garmin's load of each activity. Only for an activity without it: moving
  minutes x average HR / max HR.
- **Load ratio**: the load of the last 7 days against the weekly average of the last 28 days. The
  dashboard highlights the band from 0.8 to 1.3.
- **Ramp**: the weekly load against the week before. The dashboard flags a ramp above +10%.
- **Monotony and strain** (Foster): monotony = mean daily load / standard deviation of the daily
  load of the last 7 days. A rest day counts as 0. Strain = weekly load x monotony. The dashboard
  flags a monotony above 2.0.

### Readiness

The dashboard's own heuristic, not Garmin's training readiness:

- "Go easy" if HRV is low, the sleep score is under 60, or resting HR is 5 bpm or more above its
  7-day average.
- "Ready to push" if HRV is balanced, the sleep score is 80 or more, and the load ratio is under
  1.3.
- "Normal" in all other cases.

### Race predictor

Riegel: T2 = T1 x (D2 / D1)^1.06. The dashboard uses only best efforts of 5 km or longer, and
shows the fastest prediction for each distance.

### Strength

- **Estimated 1RM** (Epley): weight x (1 + reps / 30), only for sets of 1 to 12 reps.
- **Muscle groups**: see below.

### Muscle groups

The muscles come from Garmin's public exercise catalogue. An exercise is in the group of its first
primary muscle:

| Group | Muscles |
|---|---|
| Chest | Chest |
| Back | Lats, Traps, Lower Back |
| Shoulders, Biceps, Triceps | the muscle with the same name |
| Forearms | Forearm |
| Quads, Hamstrings, Glutes, Calves | the muscle with the same name |
| Abs | Abs, Obliques |
| Hips | Hips, Abductors, Adductors |
| Other | exercises with no muscle data |

For the balance, a completed set counts 1 for each primary muscle group and 0.5 for each secondary
one. The push:pull ratio compares Chest, Shoulders and Triceps with Back and Biceps.

### Other values

- **Intensity minutes**: moderate minutes + 2 x vigorous minutes of the activities of a week. They
  count only activities, not the minutes of the rest of the day.
- **Efficiency**: speed in metres per minute / average HR.
- **Time in HR zones**: Garmin's seconds in each zone. Only for an activity without them: all its
  minutes in the zone of its average HR, labelled as approximate.

## What Garmin does not provide

The Garmin Connect Personal API has no VO2 max, training status or readiness, race predictions,
personal records, weight, gear or activity weather. The dashboard does not show these values. Where
it needs a similar value, it calculates its own estimate, as this page describes.

Missing data never becomes a guess: when a call fails, the widget shows "n/a".
