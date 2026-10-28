# garmin-mcp

> [!IMPORTANT]
> **This fork is deprecated and gets no more updates.**
>
> - Use [garmin-personal-mcp](https://github.com/indaco/garmin-personal-mcp) instead. It runs with
>   `npx` (no Java), and the server writes the dashboard from widgets that you can edit.
> - The work in this fork is offered to the original project in
>   [apratico/garmin-mcp#1](https://github.com/apratico/garmin-mcp/issues/1).

An [MCP](https://modelcontextprotocol.io) server that connects Claude to your own Garmin Connect
data through the official **Garmin Connect Personal API**: activities, original `.FIT` files,
HRV, sleep, daily heart rate, HR zones and daily summaries, with no manual exports.

It ships with three ready-made prompts:

- **training-dashboard**: Claude pulls your last weeks of data and builds an interactive
  dashboard that puts training load next to HRV, sleep and resting heart rate;
- **training-dashboard-update**: Claude adds the new data since the last build to an existing
  dashboard and keeps the rest of the page;
- **social-card**: Claude turns a workout into a prompt for an image model (e.g. Gemini
  "Nano Banana") that adds your real stats to your own photo, for Instagram or WhatsApp.

Works with **Claude Desktop** and **Claude Code** (and any other MCP client that speaks stdio).

- Java 21, Spring Boot 4.1, Spring AI 2.0, MCP Java SDK 2.1 (stdio transport, protocol 2025-06-18)
- Read-only: the Personal API has no write endpoints
- The API key never leaves your machine and is never logged

```mermaid
flowchart LR
    C["Claude Desktop / Claude Code"] -- "MCP over stdio" --> S["garmin-mcp (JVM)"]
    S -- "API key → access token (24h)" --> T["Garmin token endpoint"]
    S -- "Bearer token, GET" --> G["Garmin Connect Personal API"]
    S -- "exercise catalogue, no token" --> E["connect.garmin.com"]
    S -- ".FIT files and caches" --> F[("~/workspace/garmin-data")]
```

## Contents

- [Requirements](#requirements)
- [1. Create the personal API key](#1-create-the-personal-api-key)
- [2. Build](#2-build)
- [3a. Connect to Claude Desktop](#3a-connect-to-claude-desktop)
- [3b. Connect to Claude Code](#3b-connect-to-claude-code)
- [The training dashboard prompt](#the-training-dashboard-prompt):
  [update the dashboard](#update-the-dashboard), [all tabs and widgets](docs/dashboard.md)
- [The social card prompt](#the-social-card-prompt)
- [Tools](#tools)
- [Configuration](#configuration)
- [Troubleshooting](#troubleshooting)
- [How it works](#how-it-works)
- [Security and terms](#security-and-terms)

## Requirements

- JDK **21 or newer** (the jar will not start on older JVMs)
- Maven 3.9+
- A Garmin Connect account with access to the
  [Personal API](https://developer.garmin.com/gc-developer-program/personal/)

## 1. Create the personal API key

1. Garmin Connect web → <https://connect.garmin.com/app/settings/personalApiToken>
2. *Create Key* → accept the terms → choose the expiry (max 1 year) → *Create*
3. The key is shown **only once**: save it in a file readable only by you:

```bash
mkdir -p ~/.config/garmin-mcp
nano ~/.config/garmin-mcp/api-key      # paste the key, save
chmod 600 ~/.config/garmin-mcp/api-key
```

Alternatively, set the environment variable `GARMIN_PERSONAL_API_KEY` (it wins over the file).

Check the key (prints only the HTTP status, `200` = OK):

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  'https://personal.connectapi.garmin.com/personalapitoken-service/personal-api/personal-api-key/token' \
  -H "Authorization: ApiKey $(cat ~/.config/garmin-mcp/api-key)"
```

## 2. Build

```bash
git clone https://github.com/apratico/garmin-mcp.git
cd garmin-mcp
mvn -q -DskipTests package
ls target/garmin-mcp.jar
```

You will need two absolute paths for the next step:

```bash
JAVA_BIN="$(dirname "$(readlink -f "$(which java)")")/java"   # must be Java 21+
JAR="$(pwd)/target/garmin-mcp.jar"
"$JAVA_BIN" -version && echo "$JAVA_BIN" && echo "$JAR"
```

> Use an absolute path to a Java 21+ binary. GUI apps do not read your shell profile, so a bare
> `java` may resolve to a different (older) JVM. With SDKMAN, prefer a pinned version such as
> `~/.sdkman/candidates/java/21.0.4-tem/bin/java` over `current`, which can change.

## 3a. Connect to Claude Desktop

Settings → Developer → *Edit Config* opens `claude_desktop_config.json`:

| OS | Path |
|---|---|
| Linux | `~/.config/Claude/claude_desktop_config.json` |
| macOS | `~/Library/Application Support/Claude/claude_desktop_config.json` |
| Windows | `%APPDATA%\Claude\claude_desktop_config.json` |

Add a top-level `mcpServers` key (next to any existing keys, not inside `preferences`):

```json
{
  "mcpServers": {
    "garmin": {
      "command": "/absolute/path/to/java21/bin/java",
      "args": ["-jar", "/absolute/path/to/garmin-mcp/target/garmin-mcp.jar"]
    }
  }
}
```

Quit Claude Desktop **completely** (including the tray icon) and start it again: the app spawns
the server at startup. The `garmin` tools appear in the connectors menu, and the prompts in the
**+** menu.

## 3b. Connect to Claude Code

One command, available in every project (`--scope user`):

```bash
claude mcp add garmin --scope user -- "$JAVA_BIN" -jar "$JAR"
claude mcp list          # garmin: ... ✔ Connected
```

Use `--scope project` instead to write a shareable `.mcp.json` in the current repository.
Inside a session, `/mcp` shows the server status and its tools.

## The training dashboard prompt

The server exposes an MCP prompt, `training-dashboard`, with one optional argument `weeks`
(default 12, max 52). Claude gets all the data with one `getDashboardData` call and builds a
dashboard with:

- a tab for each sport (Run, Strength, Walk, Bike, Padel, and Hike and Swim when you have them),
  each with its own colour and icon;
- on the *All* tab: readiness for today, fitness age, these weeks against the weeks before and
  the records. Also weekly volume, load safety, intensity minutes, a consistency calendar, load
  against recovery, the recovery panel and coach notes;
- on the *Run* tab: best efforts, races side by side, aerobic fitness, running form, a race
  predictor and the pace split of each race;
- on the *Strength* tab: every exercise with its sets, grouped by muscle group, its trend over
  time, and the balance between the muscle groups;
- a week selector on the page, and a theme dropdown with dark, light and high-contrast themes.

The dashboard uses Garmin's own training load, HR zones and training effect. It labels every value
that it calculates itself as an estimate. Every tab, widget and formula:
[docs/dashboard.md](docs/dashboard.md).

It never invents missing values: a failed call shows up as "n/a" in the affected widget.

### Before you run a prompt

- **Permissions**: the dashboard gets its data with one `getDashboardData` call. The first call
  sends about 35 requests to Garmin. Later calls use the local cache and send about 8. In Claude
  Desktop, answer **Allow always** the first time a garmin tool asks for permission. In Claude
  Code, allow them for good with `/permissions` → add `mcp__garmin`.
- **Fresh session**: after a rebuild, prompts and tools change only in a new Claude Code session
  or after a full Claude Desktop restart.
- **Activity id**: the number at the end of the activity page URL on Garmin Connect web, or ask
  Claude "list my last 5 activities".

### Run it

| Client | How to run it | Output |
|---|---|---|
| Claude Desktop | **+** in the message box → *garmin* → `training-dashboard` → set `weeks` (or leave empty) → send | interactive artifact |
| Claude Code | `/mcp__garmin__training-dashboard 8` | `garmin-dashboard.html` in the current directory; open it with `xdg-open` (Linux) or `open` (macOS) |

In Claude Code the file is ignored by git (`.gitignore`), so running the prompt from inside this
repository will not dirty it.

The prompt text lives in
[`src/main/resources/prompts/training-dashboard.md`](src/main/resources/prompts/training-dashboard.md):
edit it and rebuild to change the dashboard, or copy it into any chat by hand.

### Update the dashboard

You do not have to build the dashboard again to see new data. The `training-dashboard-update`
prompt finds the existing dashboard and replaces only its data and its coach notes. It keeps the
code, the layout and the styles.

| Client | How to run it | What it updates |
|---|---|---|
| Claude Desktop | **+** → *garmin* → `training-dashboard-update` → send | the dashboard artifact in the same conversation |
| Claude Code | `/mcp__garmin__training-dashboard-update` | `garmin-dashboard.html` in the current directory |

Leave `weeks` empty to keep the weeks of the existing dashboard. In a new Claude Desktop
conversation there is no artifact to update, so the prompt builds a new dashboard. It also builds a
new dashboard when the existing one comes from older build instructions, and says so.

The server keeps the data of each call in `garmin.data-dir/dashboard-cache.json`. The next call
asks Garmin only for the days after the last call, plus the last two days again, because Garmin
can still add data to them. The `.FIT` files of strength sessions and laps stay in
`garmin.data-dir/fit`, so their sets and laps need no new download.

## The social card prompt

Turn a photo from your run into an Instagram / WhatsApp card with your real stats on it.
The `social-card` prompt makes Claude fetch the workout from Garmin and write a ready-to-paste
prompt for an image-editing model such as **Gemini 2.5 Flash Image ("Nano Banana")**; you attach
your photo there and get the edited image back.

| Argument | Values | Default |
|---|---|---|
| `activityId` | a Garmin activity id | latest activity |
| `style` | `clean`, `poster`, `neon`, `bib`, `magazine`, `splits` | Claude shows the menu |
| `format` | `story` (9:16), `post` (4:5), `square` (1:1) | Claude asks |

| Style | Look |
|---|---|
| `clean` | minimal bottom gradient, one row of thin white stats |
| `poster` | huge condensed number behind the runner (depth effect) |
| `neon` | night grade, glowing cyan/magenta stats |
| `bib` | stats printed on a pinned paper race bib |
| `magazine` | running magazine cover with cover lines |
| `splits` | glass card with a bar chart of the km splits |

What you get back:

1. the stats used, to double-check them;
2. the image prompt, in one code block;
3. an Instagram caption and a WhatsApp status line;
4. a tip on fixing the text if the image model misspells a number.

Workflow:

1. **Ask Claude for the prompt**
   - Claude Desktop: **+** in the message box → *garmin* → `social-card` → fill the arguments
     (all optional) → send.
   - Claude Code: arguments are positional, in the order `activityId style format`; use
     `latest` for the most recent activity:

     ```
     /mcp__garmin__social-card latest poster story
     /mcp__garmin__social-card 24843355045 bib post
     ```
2. **Copy** the image prompt from the code block.
3. **Generate the image**
   - [Gemini app](https://gemini.google.com): pick the image model (Nano Banana), attach your
     photo, paste the prompt, send.
   - [Google AI Studio](https://aistudio.google.com): model `gemini-2.5-flash-image`, set the
     aspect ratio to match the format, attach the photo, paste the prompt, run.
4. **Check the numbers** on the image against the stats line; if one is wrong, reply to the
   image model with the fix suggested in the tip (e.g. `fix the text: the time must read 1:38:42`).
5. Post it, with the caption Claude wrote.

The prompt tells the image model to keep everyone in the photo **identical** (no beautifying, no body changes),
to keep graphics off faces and out of the areas Instagram covers in stories, and to render only
the quoted text. Image models can still
misspell numbers, so the stats line is there for a final check. By default no map or start
location is used, and heart rate is left out on request.

Prompt text and worked examples:
[`src/main/resources/prompts/social-card.md`](src/main/resources/prompts/social-card.md).

## Tools

| Tool | Endpoint | Notes |
|---|---|---|
| `listActivities` | `/activitylist-service/activities/search/activities` | compact summary (km, moving and elapsed time, pace, HR, cadence, ascent, HR-zone seconds, Garmin training load and training effect, intensity minutes, race flag, best efforts, power, running form, run/walk segments, swim SWOLF/lengths/strokes); `raw=true` for full JSON |
| `getActivity` | `/activity-service/activity/{id}` | summary, metadata, segments by type |
| `getActivityLaps` | `/download-service/files/activity/{id}` | laps decoded from the .FIT file: km, time, pace or speed, HR, cadence, ascent, power, intensity |
| `getActivitySets` | `/download-service/files/activity/{id}` | strength sets decoded from the .FIT file: exercise, movement category, primary and secondary muscles, reps, weight (kg), set time, rest after the set |
| `downloadActivityFit` | `/download-service/files/activity/{id}` | saves `{id}.fit` under `garmin.data-dir/fit` (cached: downloaded once) |
| `getHrvRange` | `/hrv-service/hrv/daily/{from}/{to}` | overnight HRV |
| `getDailyHeartRate` | `/wellness-service/wellness/dailyHeartRate` | includes resting HR |
| `getSleep` | `/sleep-service/sleep/dailySleep` | date = the morning you woke up |
| `getHeartRateZones` | `/biometric-service/heartRateZones/v2` | |
| `getFitnessAge` | `/fitnessage-service/fitnessage/v2/{date}` | fitness age compared with actual age, and its components |
| `getDailySummary` | `/usersummary-service/usersummary/daily` | steps, stress, body battery |
| `getDashboardData` | the endpoints above | all the data of the dashboard in one call; keeps a local cache and asks Garmin only for new days |
| `garminGet` | any other GET | relative path + query string |

These Garmin Connect data are not in the Personal API (the endpoints answer 404): VO2 max,
training status and readiness, race predictions, personal records, weight, gear, activity weather,
endurance and hill score, lactate threshold, exercise sets. Where the dashboard needs one of these
values, it calculates its own estimate and labels it.

Dates are `yyyy-MM-dd`. The Swagger summary does not always spell out query parameter names: if
an endpoint answers 400, `garminGet` lets Claude try another name without a rebuild.
API reference: <https://developer.garmin.com/gc-developer-program/personal/swagger-docs/>

## Configuration

`src/main/resources/application.yml`; every value can be overridden at launch with
`--garmin.data-dir=...` style arguments (after the jar in `args`) or environment variables
(`GARMIN_DATADIR`, `GARMIN_BASEURL`, ...).

| Property | Default | Meaning |
|---|---|---|
| `garmin.base-url` | `https://personal.connectapi.garmin.com` | Personal API host |
| `garmin.api-key-file` | `~/.config/garmin-mcp/api-key` | used when `GARMIN_PERSONAL_API_KEY` is not set |
| `garmin.data-dir` | `~/workspace/garmin-data` | `.FIT` files, the dashboard cache and the exercise catalogue |
| `logging.file.name` | `~/.cache/garmin-mcp/garmin-mcp.log` | server log |

## Troubleshooting

| Symptom | Fix |
|---|---|
| Server does not appear / fails at start | Check the Java path is 21+: `/path/to/java -version`. `UnsupportedClassVersionError` means an older JVM. |
| Code changes have no effect | The client keeps the old JVM running: rebuild, then restart Claude Desktop (fully) or the Claude Code session. |
| `Token request failed: HTTP 401/403` | Key wrong, expired or revoked: create a new one. The error includes Garmin's Content-Type and body. |
| An endpoint answers 400 | Parameter name mismatch: use `garminGet` with the right query string. |
| Need details | Server log: `~/.cache/garmin-mcp/garmin-mcp.log`. Claude Desktop also writes `~/.config/Claude/logs/mcp-server-garmin.log` (Linux). |

Manual smoke test (the server must answer with a single JSON line):

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}' \
  | timeout 10 "$JAVA_BIN" -jar "$JAR"
```

## How it works

- **Transport**: stdio. The client starts the jar as a child process and talks JSON-RPC over
  stdin/stdout; the process lives as long as the client. Nothing else may be written to stdout,
  so logging goes to a file only (`logback-spring.xml`).
- **Auth**: API key → `POST .../personal-api-key/token` → access token valid 24h (no refresh
  token). The token is cached in memory, renewed before expiry and after a `401`. Garmin may
  return it as JSON or as plain text; both are handled.
- **Content negotiation**: JSON calls send `Accept: application/json` and retry once with `*/*`
  on `406`.
- **Laps and sets**: the Personal API has no endpoint for laps or exercise sets.
  `getActivityLaps` and `getActivitySets` download the original .FIT file one time and keep it
  under `garmin.data-dir/fit`. They decode its lap and set messages with the official
  [Garmin FIT SDK](https://github.com/garmin/fit-java-sdk). The .FIT file does not contain
  exercise changes that you make later in Garmin Connect.
- **Muscles**: the .FIT file has no muscle data. `getActivitySets` reads the primary and secondary
  muscles of each exercise from Garmin's public exercise catalogue, the file that the Garmin
  Connect web app uses. The server downloads it without your token, keeps a copy in
  `garmin.data-dir/exercises.json` and refreshes it after 30 days. If the download fails, the
  server uses the old copy. Without any copy, the sets have no muscles.
- **Code map**:
  - `GarminApiClient`: HTTP, token lifecycle, retries
  - `GarminTools`: MCP tools (`@Tool` methods)
  - `GarminDashboard`: the `getDashboardData` tool and its local cache
  - `GarminPrompts`: MCP prompts, texts in `src/main/resources/prompts/`
  - `GarminProperties`: `garmin.*` configuration

## Security and terms

- The API key is read from a `chmod 600` file or an environment variable, never logged, never
  sent anywhere but Garmin's token endpoint. The access token is kept in memory only.
- `.gitignore` excludes `api-key` and `*.fit` files.
- Revoke the key at any time from the Garmin Connect page where you created it.
- The Personal API is for personal, non-commercial use under Garmin's terms. This project is not
  affiliated with or endorsed by Garmin.

## License

[MIT](LICENSE). The Garmin FIT SDK dependency is distributed under the
[FIT Protocol License](https://github.com/garmin/fit-java-sdk/blob/main/LICENSE.txt).
