# HANDOFF - live testing

Written 2026-09-06 for whoever continues the live testing, Codex included. Milestones 0-16 are
merged and green in CI; the roadmap is in `README.md`.

**Milestones 14, 15 and 16 were built without hardware and have never run on the phone.** They
compile, their logic is unit-tested, and two of them are deliberately partial: Milestone 15 has no
ring to test against, and Milestone 16 produces no image on the device. Treat all three as
unverified in the way the rest of this file means it - see "What landed on 2026-09-06" below.

The running order is [TESTING.md](TESTING.md). This file is the state of play, the traps, and
the rules that are not negotiable.

## Live search - where it stands (2026-09-25)

Goal: "Operator, what's the weather in Salina today" and "Operator, did you see the Rams, Aaron
Donald isn't traveling to AUS with the rest of the team" must both answer from live search.

- **Weather: PASSED live** (searched=true, 6.2s): "In Salina, Kansas, today, Friday, September 25,
  expect showers, then a chance of thunderstorms, with a high near 79F" - from forecast.weather.gov.
- **Rams: PASSED live 2026-09-24** (searched=true, ~7s), 7 of 8 in a batch: "Yes - Sean McVay
  announced on September 8, 2026, that Aaron Donald would not travel with the Rams to Melbourne
  for their September 10 opener against the 49ers and would be inactive." Earlier fixes:
  `02c1a63` effort low, max_tokens 1500; `319ade0` SpeakableText; `46b0a75` LIVE SEARCH RESULTS;
  `242ae65` user message is the question only; `a50ac99` query rewrite. Then, from live runs:
  `9e1f48e` "today" is the backend's zone, not UTC (at 11pm CDT it was Friday's forecast);
  `8ffb7ec` no dates in current-conditions queries (dated queries found history pages) and the
  answer is told the local time; `e463381` an invited prose reply is spoken, not dropped as
  UNREADABLE_DECISION (it was 2 in 8 Rams runs), and ** is stripped; `f2d9593` ambiguous
  abbreviations stay as spoken - the rewrite guessed Austin for AUS a quarter of the time.
- **Still open:** about 1 Rams run in 8, the search itself finds nothing and the model falls back to
  "Donald retired in 2024". Next lever: `OPERATOR_WEB_SEARCH_MAX_RESULTS=5` (default 3) - costs
  more per search, so it is the user's call. Late at night the weather answer sometimes still
  calls tomorrow "today" despite being told the local time.
- `EmbeddingBackfillServiceTest` "backfills only eligible ..." fails in the full backend run on
  main and passes alone - a pre-existing ordering or timing flake, not yet looked at.

User's live config (no secrets): decision model `google/gemini-3.5-flash-lite` since 2026-09-25 (fallback `openai/gpt-5.6-luna`, a reasoning model served by
Azure), ZDR on, sort latency, web search on. Postgres is not running; that is fine for this.

To test without the user, from the repo root with OPENROUTER_API_KEY set:

    OPERATOR_DECISION_MODEL_ID=openai/gpt-5.6-luna OPERATOR_ZERO_DATA_RETENTION=true \
      OPERATOR_PROVIDER_SORT=latency ./gradlew :backend:run -Poperator.skipAndroid=true &
    curl -s localhost:8080/decide -H 'Content-Type: application/json' -d \
      '{"trigger":"DIRECT_ADDRESS","mode":"ACTIVE","transcript":["Someone: operator did you see the rams aaron donald isn'"'"'t traveling to AUS with the rest of the team"]}'

Pass = searched true, and the response says Donald stayed home from Melbourne, with a reason.
If it still misses: try the web plugin's `engine` ("native" vs "exa", see
@openrouter/ai-sdk-provider types) and max_results 5 before anything bigger.


**Speed pass (unmeasured live).** Searched answers took 6.8-8.4s. Three cuts: the query rewrite
now runs only for conversational news or shorthand (QueryNeedsRewrite; the weather question skips
it), is abandoned after 2s, and searched answers use `OPERATOR_SEARCH_REASONING_EFFORT=minimal`
(was low). The backend logs one line per decision - "Decided in N ms: rewrite, memory, model" -
and the panel's BREAKDOWN shows the same. To verify: run each sentence ~8 times, compare time and
accuracy to 8.4s / 6.8s and Rams 7-of-8. If accuracy drops, set the effort back to `low`.

Measured (16 runs a sentence): weather faster and right every time, rewrite skipped. Rams worse at
minimal - but the 2s rewrite cap was the cause, not the effort: rewrites take 1-3s, and the cap cut
off 7 of 8 at minimal and 5 of 8 at low, paying the wait and then searching "AUS" as spoken. Cap
raised to 3.5s (`OPERATOR_SEARCH_REWRITE_TIMEOUT_MS`). The user's .env is at effort `low` for now;
re-measure Rams at `minimal` with the new cap before moving it back.

## Test run 2026-09-25 - results (running log)

Backend on `7d14345`, decision model `openai/gpt-5.6-luna`, ZDR on, sort latency. Times are the
`/decide` latency.

**Part A - backend only**

1. **Rams, minimal vs low (final prompt, 3.5s rewrite cap):** minimal 8/8 correct, avg 6.8s;
   low 14/16, avg 7.7s. `.env` set to `OPERATOR_SEARCH_REASONING_EFFORT=minimal`.
2. **8 more runs each on final settings:** Rams 8/8 correct, avg 6.8s. Weather 8/8 searched and
   answered from the forecast, avg 4.9s - one run gave a high of 87F where the other seven said
   76-79F (probably a different source).
3. **Other current questions** (all DIRECT_ADDRESS, all searched=true):
   | Question | Time | Answer | Checked |
   |---|---|---|---|
   | who won Thursday night football last night | 4.3-7.5s | Falcons beat Packers 35-14 | matchup right; score not independently confirmed |
   | what's Apple stock trading at | 4.3s | closed $335.92 Sept 24, ~$335.42 overnight | not checked |
   | who's the Chiefs quarterback | 5.0-8.9s | Mahomes starts, Fields backup | right |
   | weather tomorrow in Salina | 5.2-7.3s | Sat Sept 26, high ~83F, 25% rain | plausible |
   | did the Fed cut rates this month | 4.8-6.7s | No - raised 0.25 on Sept 16 to 3.75-4.00% | right (CNBC) |
   **Fixed `3cfa644`:** the Fed answer was suppressed as TOO_LONG - `sentenceCount` counted every
   "." so "0.25" and "3.75%-4.00%" made one sentence four. After the fix it spoke 3 of 3.
4. **Drill** - ambient spoke **0 of 4** before, **0 of 4** after (`drill-baseline.csv`,
   `drill-after.csv`); grounded invoice spoke both times, invited both answered. Fixed in the drill
   itself: its FREE group still expected STANDBY to be refused free, which stopped being true with
   ADR-050 (`192dc55`); on a clean backend that row made a paid call and blocked the first ambient
   case (DECIDED_RECENTLY), so only 3 of 4 were really tested. The row is gone. Also: run the drill
   on a freshly started backend - invited calls made just before it use the decision budget and
   the ambient cases come back RATE_LIMITED. The 2026-09-06 baseline was copied aside.

**Part B - the phone** (now an SM-S948U "S26 Ultra", Android 16, at 192.168.1.218; PC still
192.168.1.215, matching `local.properties`)

5. **Install: PASS.** `:app:installDebug` with `:glasses-meta` compiled in; the phone reaches
   `/usage` over Wi-Fi (200). First cable was charge-only - Windows saw no device at all.
6. **Stage 4 hearing:**
   - Silence, 30 s: **PASS** - 0 utterances, 0 uploads. But **a phone vibration opens the gate
     every time** (twice per buzz, 2 uploads each time, even under Do Not Disturb). Not fixed;
     test with the phone muted until it is.
   - Phone mic: first try **FAIL** - one sentence became three utterances ("the blue package" /
     "arrives" / "friday at 7:30"): the gate closed after 240 ms of quiet. **Fixed `7728e94`**
     (700 ms hangover in ListenController). Retest: one line, exact, transcribed in 0.58s.
   - Glasses mic (SCO): one utterance, "blue package arrives friday at 7:30" - the first word lost,
     otherwise exact; transcribed in 1.7s. Onset clipping on SCO, likely risk 36.
7. **Stage 5 voice: PASS.**
   - Backend TTS first byte 0.24 s warm (0.91 s cold). In the app, **Voice first audio 352 ms**,
     heard in the glasses (A2DP).
   - First typed question failed "Backend unreachable": the app's OkHttp default 10 s read
     timeout was shorter than a searched answer, and that one request also hit a 60 s stall
     upstream (`/ai/respond` 503 after 60.6 s; retries 3.7-9.4 s). **Fixed `2713c4d`** (70 s).
   - STOP SPEAKING: audio focus released ~0.1 s after the tap, user heard it stop. The user's
     first manual try reported no stop - not reproduced; probably the tap missed while scrolling.
   - EMERGENCY MUTE: released at once, user heard it stop; RELEASE MUTE restored it.
   - Half-duplex with glasses SCO in and out: mic stopped at the SPEAK tap (11:38:14.8), speech
     11:38:15.8-16.6 on the glasses, mic resumed 11:38:17.6 on a new session; status back to
     Listening. User heard "Hello." Selections are not persisted - a reinstall resets INPUT and
     OUTPUT to system default.
   Driven over adb (`input tap` + `uiautomator dump`, timing from `dumpsys audio` focus and
   recording events), which needs no hands and gives exact times.
8. **The goal test: PASS, both sentences, spoken to the glasses** (mode ACTIVE, Active Operator
   ON - spoken questions only reach /decide while it is on; glasses SCO in and out):
   - Weather: SOURCE live web search; BREAKDOWN 2 ms rewrite / 0 ms memory / 5370 ms model
     (5.4 s); said "In Salina, Kansas, today-Friday, September 25-will be mild, with showers and
     possible thunderstorms, a high near 79F, and a low around 66F." Heard in the glasses.
   - Rams: SOURCE live web search; BREAKDOWN 2288 ms rewrite / 0 ms memory / 4758 ms model
     (7.0 s); said "Yes-the Rams confirmed on September 8, 2026, that Aaron Donald would not travel
     to Melbourne for their Week 1 game against the 49ers, giving him more time to ramp up after
     coming out of retirement." One utterance (the hangover fix held), heard in the glasses.
   - No URL or asterisks in either spoken answer. Add ~0.7 s pause + 2.5 s settle + transcription
     (~2 s on SCO) to the decide time for what the user waits.
9. **Stage 6 button checks** (ANYTHING TO SAY? sends AMBIENT, so the phone's rules apply):
   - After talking: model call, SILENT. **PASS**.
   - Second press ~10 s later in STANDBY: DECIDED_RECENTLY, free, 1 ms. **PASS**. In ACTIVE it is
     *not* refused - ACTIVE's intervalFactor 0.2 makes the interval 4 s (ADR-050, by design), and
     a quick double tap is ignored by the app while the first is still deciding.
   - QUIET: MODE_DOES_NOT_VOLUNTEER, free. **PASS**.
   - Muted + COMMENT NOW: nothing reached the backend. **PASS** (the button stays enabled while
     muted; cosmetic).
   - No transcript in the last 60 s: NOTHING_HEARD, free.
   - **Transcription stalls upstream.** A minute of talk: 10 utterances, 7 uploads, 2 of them hung
     to the 15 s client timeout (502) and the phone - one upload at a time, 4 queued, oldest
     dropped - lost 3 utterances. **Fixed in part `29bce0d`** (10 s per attempt, one retry). Since:
     1 of ~13 uploads still failed on both attempts (20 s). Still open: serial uploads mean one
     stall holds up everything behind it.
   - **First real provider error, verbatim** (risk 27): a 200 whose body was
     `{"id":"gen-...","error":{"message":"openai/gpt-5.6-luna is temporarily rate-limited upstream.
     Please retry shortly, or add your own key to accumulate your rate limits: ...","code":429,...}}`.
     The decision engine read it as MODEL_UNAVAILABLE and stayed silent - the right failure.
   - **Active Operator, STANDBY mode, ~4 min** (not 10) of a phone call with the user's wife on
     another device: **spoke 0 times.** 26 lulls considered: 18 refused free by the phone's rules,
     5 model calls chose silence, 3 were 429 rate-limited upstream (silent). Stayed quiet through
     "I wonder what time the Rams play the Cowboys Dec 20th" - by design: not addressed, and ambient
     never searches. Transcription over the call: ~35 uploads, 2 failed after the retry (20 s),
     several took 6.5-11.7 s.
10. **M3 not run.** The instruction was "only if everything above passes", and three things are
    still open (below).

**Decision fallback (added after the run)** - `OPERATOR_DECISION_FALLBACK_MODEL_IDS`, empty by
default; the user's `.env` has `google/gemini-3.5-flash-lite`.
- Candidates tried live as the *only* decision model (chit-chat, weather x2, Rams x2):
  gemini-3.5-flash-lite 1.5-3.1 s, all right, silent on chit-chat; claude-haiku-4.5 5.9-8.5 s, one
  weather wrong; gpt-5.4-nano 3.5-9.3 s, one weather wrong ("high near 101F ... Sources and.");
  deepseek-v4-flash (the fast model) 24-35 s and **leaked its reasoning into the response field**
  - never use it here.
- Two layers, both needed: OpenRouter `models` covers a request that fails outright (the 429s);
  it does not cover a 200 whose choice ends `finish_reason: "error"` (Azure failing mid-answer,
  1 in 8 searched questions), so the engine then asks the fallback directly, once.
- Live, 16 direct questions: 16 spoke, 0 silent, all right; Luna answered 1, Gemini 15 (13 via
  `models`, 2 via the engine retry) - Luna was rate-limited most of the time. Drill with the
  fallback: ambient spoke 0 of 4, grounded invoice spoke. COMMENT NOW answered with only a
  question ("Which game were you watching?").
- The panel's model now shows which model actually answered.

**Gemini made the main decision model (user's call, `.env` only)** -
`OPERATOR_DECISION_MODEL_ID=google/gemini-3.5-flash-lite`, fallback `openai/gpt-5.6-luna`.
- Drill on a fresh backend: ambient spoke 0 of 4, grounded invoice spoke, decisions 1.0-1.8 s.
- Rams 8/8 right, avg 2.7 s (Luna 6.8 s). Weather 8/8 searched and spoke, avg 2.3 s (Luna 4.9 s);
  no fallbacks needed, no failures.
- **But at night it names the wrong day.** At 9:20 pm Friday (already Saturday in UTC) it read
  Saturday's forecast (high 82-85F) as "today" in 5 of 8, then 8 of 8 on a second run; NWS said
  tonight low ~66F, Saturday high ~84F. Luna, given the same "It is <local time>" line, got it right
  4 of 5. A stronger instruction ("a result for a named day belongs to that day") made no
  difference and was not committed. Likely cause: sites label Saturday "Today" after 7 pm CDT and
  Gemini follows the source. Not yet tested in daytime, where the problem should not arise.
- To go back: swap the two values in `.env`.

**Still open after this run**
- Gemini as decision model mislabels "today" at night (above).
- Luna (`openai/gpt-5.6-luna`, Azure) is rate-limited upstream much of the time.
- Transcription (OpenRouter whisper) stalls: even with the retry, 3 uploads in ~50 failed after
  20 s and their speech was lost; uploads are serial, so a stall delays everything behind it.
- A phone vibration opens the voice gate (2 uploads per buzz).
- Glasses (SCO) mic dropped the first word of the test sentence.
- `EmbeddingBackfillServiceTest` flakes in the full backend run (passes alone).

## Where testing got to

Stages 2 and 3, M1/M2 from stage 1, and the backend half of stage 6 are done. Stage 4 is
partially complete. What remains needs the phone or further device setup.

| Stage | State |
|-------|-------|
| 1 - device checks (M1-M3) | **M1 and M2 done on the Galaxy.** Phone capture, A2DP and SCO playback, SCO capture, actual-route reporting, communication-device confirmation, and disconnect fallback passed on 2026-09-06. The GitHub token is now present and the installed APK includes `:glasses-meta`; M3 is ready when the phone reconnects. |
| 2 - first live model call | **done.** `deepseek/deepseek-v4-flash`, 2019 ms, $0.0000688. Risks 27, 28 closed. |
| 3 - memory round trip | **done.** Stored free, recalled at semantic 0.62 with real embeddings. |
| 4 - hearing | **partially done.** OpenRouter transcription works live. About 45 s of silence produced zero backend calls; speech transcribed with middling accuracy; STOP produced no late transcript or error. Controlled phone-vs-SCO accuracy and noisy-room checks remain. |
| 5 - voice and glasses | **not started as a deliberate test.** A TTS request did return 200 in 1046 ms while testing Ask, but playback, first-audio timing, STOP, and half-duplex were not recorded. |
| 6 - deciding | **backend half done.** 0 of 4 ambient scenarios spoke. Phone half not started. |

Numbers and reason codes are in `CURRENT_STATUS.md` under "What works (verified)"; every risk
number below is in `docs/RISKS_AND_UNKNOWNS.md`.

## The three findings that should shape what comes next

**Silence is coming from the prompt, not the safety net.** In the stage 6 drill,
`suppressedAfterModel` was false on every ambient row: the model declined on its own and
`ConversationPolicy`'s floors were never consulted. That is the good version of a zero.

**Which means the thresholds are still completely untested (risk 44).** The confidence and
relevance floors only judge a model that wants to speak, and it never did. Do not read
"ambient spoke: 0" as evidence that 0.6 and 0.5 are good numbers. It says nothing about them.

**Latency is the recurring problem.** `/ai/respond` 2019 ms without memory, 3782 ms with;
ambient decisions 1447-1981 ms. Stacked on the 2.5 s settle delay, the room waits about four
seconds before Operator speaks, before synthesis. Risk 47 now says the settle delay may need to
come *down* rather than being tuned alone. Measure the whole path before moving either number.

## Next actions, in order of value

1. ~~Run the grounded invoice scenario.~~ **Done 2026-09-06, and it worked.** The same
   conversation that was silent for want of grounding spoke once the terms were in memory, at
   confidence 0.95 / relevance 0.9, and labelled the memory *as* memory rather than asserting it.
   The chain memory -> retrieval -> decision -> response is proven against live providers. Re-run
   `.\scripts\decide-drill.ps1` after any prompt or threshold change: it is the cheapest
   regression check that needs no phone.
2. ~~**Finish Stage 1 on the phone.**~~ **M1 and M2 done 2026-09-06.** Milestone 1 capture used
   `SM-S908U1 (built-in mic)` via `MIC, system default routing`; the 4 s clip peaked at 40% and
   played clearly through `RB Meta 01T8 (Bluetooth A2DP)` via `USAGE_MEDIA, system default
   routing`. Android reported both actual routes and the app showed no error. For Milestone 2:
   GRANT BLUETOOTH, then A2DP output, SCO input, SCO output, then disconnect and confirm it falls
   back to DEFAULT. Record the SCO bring-up time and watch for `routedDevice was never reported`
   in the log, which would mean the phone is not telling us where audio actually went (risk 13).
   SCO playback is already proven: with input and output set to `RB Meta 01T8 (Bluetooth SCO)`,
   `USAGE_VOICE_COMMUNICATION via RB Meta 01T8` played clearly through the glasses with no error.
   SCO capture also produced understandable audio with the same selected routes and no error; the
   communication link took about 1 s to start. The route event log confirmed the glasses as the
   active communication device and reported both capture and playback routed to the glasses.
   `routedDevice` was therefore available (risk 13 did not occur). Disconnect reset both explicit
   route selections to DEFAULT; the glasses reconnected immediately and Android then chose them
   again as the default output, with the phone as default input.
3. **Finish Stage 4 hearing.** Reconnect the phone, set INPUT to DEFAULT, clear the Listen panel,
   and say exactly `The blue package arrives Friday at seven thirty.` Record the exact transcript
   and round trip. Repeat the identical sentence with INPUT set to `RB Meta 01T8 (Bluetooth SCO)`
   and compare accuracy. Then repeat in a noisy room and confirm the gate does not latch open.
   The privacy and STOP checks already passed; details are below.
4. **Run M3**, since `github_token` is now present and the APK built with `:glasses-meta`.
5. **Stage 5, then the phone half of stage 6.** In that order: Active Operator is only
   interpretable once speech out works.

## The transcription key question - DECIDED 2026-09-06

At the original handoff, `.env` had `OPERATOR_TRANSCRIPTION_BASE_URL=https://openrouter.ai/api/v1`
and `OPERATOR_TRANSCRIPTION_MODEL_ID=openai/whisper-large-v3-turbo`, but
`TRANSCRIPTION_API_KEY` was empty. The backend reads that key separately and **will not** fall
back to `OPENROUTER_API_KEY`.

**Resolved by configuration: `TRANSCRIPTION_API_KEY` is set to the same OpenRouter key.** No
code change, and none is wanted - the alternative was to make the key fall back when the base URL
matches the model provider's host, which couples two settings that ADR-005 keeps deliberately
separate. Transcription may yet move to a different vendor, and the separate variable is what
makes that a one-line change. Do not implement the fallback unless the user asks.

Stage 4 is therefore unblocked and needs only the APK. Note the backend must be restarted after
editing `.env`; config is read once at startup.

The first phone `ASK OPERATOR` attempt exposed a missing serialization compiler plugin in `:app`:
`AskRequest` had `@Serializable`, but no serializer was generated. The plugin and a regression
test were added, the fixed APK was installed, and a live phone request then succeeded. The
configured `.env` also still had an empty `TRANSCRIPTION_API_KEY` despite the earlier note; it was
filled from the existing OpenRouter key as decided above, the backend was restarted, and `/health`
now reports the OpenRouter transcription provider configured.

## Codex continuation on 2026-09-06

Two Android defects were found and fixed on the phone:

- `AskRequest` had `@Serializable`, but `:app` did not apply
  `org.jetbrains.kotlin.plugin.serialization`. ASK OPERATOR failed locally with `Serializer for
  class 'AskRequest' is not found`. The plugin and a regression test were added; the fixed APK was
  built, installed, and a live phone -> backend -> model request then succeeded.
- `OperatorBackendClient` caught `CancellationException` as a generic network exception. Stopping
  listening while a transcription was in flight therefore displayed `Backend unreachable ...
  StandaloneCoroutine was cancelled` even though the backend was healthy. All request/response
  paths now preserve cancellation. The rebuilt APK was installed; STOP LISTENING midway through
  a sentence then produced no late transcript, no error, and status Idle.

Stage 4 evidence after the cancellation fix: the detector's learning phase is only 8 x 20 ms =
160 ms, so it went from START LISTENING to Listening too quickly for the user to see. The backend
usage counter was zero at start and stayed at zero through about 45 s of silence, proving the
privacy gate on this quiet-room run. After speech began, OpenRouter completed 11 transcription
requests with zero failures and 682 ms average provider latency. The user described accuracy as
"halfway decent, not the best"; no controlled reference sentence has been captured yet.

Local `main` contains these unpushed commits:

- `696eaf5` - record Galaxy and Ray-Ban audio tests
- `aaec7d7` - generate Android backend serializers and test them
- `0969620` - preserve backend request cancellation

The docs handoff update follows those commits. `:app:testDebugUnitTest`, `:app:assembleDebug`, and
`:app:installDebug` passed from `C:\Users\Vector\Operator`; Gradle reported installation on
`SM-S908U1 - 16`. Pushing `main` from the Codex shell failed with
`SEC_E_NO_CREDENTIALS`; `origin/main` therefore remains at `ee1a7a1` until a user-authenticated
shell pushes the local commits.

The Android Studio checkout at `C:\Users\Vector\Operator` intentionally also contains the two app
fixes so the installed APK could be built. It has other pre-existing local changes and drill CSVs;
do not reset or commit that checkout wholesale. Port only the app changes above or use the commits
from this Codex worktree.

## The phone is set up

The APK builds and runs on the Galaxy as of 2026-09-06, from Android Studio (Open the
`C:\Users\Vector\Operator` folder, then Run). That was `:app`'s first build outside CI and it
needed no code changes. Building from the command line also works:
`.\gradlew.bat :app:assembleDebug`, APK at `app\build\outputs\apk\debug\app-debug.apk`.

`local.properties` (git-ignored, not in the repo) is configured. Two things about it:

- **`OPERATOR_BACKEND_URL` is the laptop's LAN IP**, not localhost - the phone cannot reach
  localhost. **DHCP moves it**: it changed three times in one evening (.124, .122, .215), and
  every move looks exactly like a broken app, because the error just says "backend unreachable".
  **This is the first thing to check, before anything else.** Run `.\scripts\set-backend-ip.ps1`,
  which writes the current address in, then rebuild - a Gradle sync is not enough, the URL is
  compiled into the APK. A DHCP reservation on the router removes the problem for good.
  Windows also blocks inbound 8080 by default; the rule only needs adding once:
  `New-NetFirewallRule -DisplayName "Operator backend 8080" -Direction Inbound -LocalPort 8080 -Protocol TCP -Action Allow`
  from an administrator prompt. Phone and laptop must be on the same Wi-Fi.
- **The model IDs in it are display-only.** The app reads them into `BuildConfig` for its
  diagnostics panel; it asks the backend for a *tier* (FAST/DEEP/VISION) and never names a model.
  Changing them there changes nothing but the readout. `OPERATOR_EMBEDDING_MODEL_ID` is not read
  by the app at all.

`github_token` is now present in `local.properties`, and the latest build compiled and packaged
`:glasses-meta`. Do not print or commit the token. M3 is ready after the phone is reconnected;
Milestones 1, 2, and stages 4 and 5 do not depend on it.

The user had not used Android Studio before this session. Sync after any `local.properties`
change: **File -> Sync Project with Gradle Files**, or the "Sync Now" link in the bar across the
top of the editor. Logcat is the bottom panel; filter it on `Operator` to see the route logs,
which is most of what stage 1 involves. If Android Studio offers to upgrade AGP or Gradle,
**decline** - 9.4.0 and 9.6.0 are pinned and CI is green on them.

## What landed on 2026-09-06 (Milestones 14-16)

All three are backend-and-logic complete, CI green, and untried on hardware.

**14 - feedback learning.** Four buttons under whatever Operator just said. The design rule is the
asymmetry (ADR-045): complaints raise the confidence and relevance floors, approval never lowers
them, and the penalty decays over an hour. The reason it matters: `GET /feedback/summary` reports
the mean scores of the comments the user actually rejected, which is the first instrument for
risk 44. **Give real verdicts during the next session** - that is the only way those floors stop
being guesses, and a handful of honest taps is worth more than any amount of further reasoning
about them.

**15 - a physical button.** No ring, so this is a gesture mapping over whatever key codes arrive
(ADR-047), not a driver. Hold = push-to-talk, tap = ask or interrupt, double tap = toggle mute.
**Cheapest thing to try: press the Ray-Ban capacitive button while the app is in the foreground**
and watch Logcat for `RemoteControl`. The SDK does not deliver gestures (META_GLASSES.md), but
whether the glasses send ordinary AVRCP media keys as a headset would is a separate and open
question (risk 54). If they do, Milestone 15 is testable with no extra hardware at all.

**16 - camera context.** `POST /look` takes image bytes and returns a description; the image is
never written anywhere (ADR-049). There is no ambient trigger and that is the design, not a gap
(ADR-048). **The glasses do not take a picture yet** (risk 57) - the SDK supports it, nothing calls
it. To try the backend half now, POST any JPEG with `OPERATOR_VISION_MODEL_ID` set. Risk 58 is the
one to take seriously: whether a model honours the "do not identify anyone" rules under pressure
is untested, and it should be tested deliberately with a photo containing a person and a visible
name badge before this is pointed at anything real.

**Also fixed:** voice-activity calibration was 8 frames - 160 ms, shorter than a syllable - which
is why "Learning the room" was never visible. Now one second, with a test pinning the property
that the gate cannot open while calibrating. The noisy-room case it exists for still has not been
run (risk 33).

## Traps that have already cost time

**PowerShell scripts must be pure ASCII.** Windows PowerShell 5.1 reads a `.ps1` without a BOM as
ANSI. A UTF-8 em-dash becomes three mojibake characters, one of which maps to a quote in code page
1252, which closes the string it sits in and shifts every argument after it. This broke
`decide-drill.ps1` with an exception three steps downstream of the fault. Both scripts say so at
the top. Verifying under PowerShell 7 does **not** catch it - 7 defaults to UTF-8.

**Quote Gradle properties in PowerShell.** `.\gradlew.bat :backend:run -Poperator.skipAndroid=true`
splits at the dot; Gradle sees `-Poperator` and a task named `.skipAndroid=true`. Quote it:
`"-Poperator.skipAndroid=true"`. Bash and CI are unaffected, so the unquoted form in `README.md`
is correct there.

**Do not inline JSON into curl from PowerShell.** 5.1 and 7 disagree about how embedded double
quotes reach a native executable. Write the body to a file and use `-d "@file.json"`, as TESTING.md
does throughout.

**Decision reason codes are model-authored free text.** The same scenario gave
NO_VERIFIED_INFORMATION on one run and NO_CONFIRMED_FACT on the next. Read them, do not count or
branch on them (risk 51). Only `ConversationPolicy`'s own codes - MUTED, RECENTLY_SPOKE,
DECIDED_RECENTLY and the rest - are a fixed vocabulary.

**`Export-Csv` defaults to ASCII on PowerShell 5.1**, which turned a curly apostrophe in
Operator's output into `?`. Fixed in `decide-drill.ps1` with `-Encoding UTF8`; remember it
anywhere else results get exported, since names and quoted speech hit it constantly.

**Assertion argument order is inverted between modules.** `:core` and `:backend` use `kotlin.test`
(message **last**); `:app` uses JUnit 4 (message **first**). This has caused CI failures three
times. `-Werror` is on for `:core` and `:backend` only.

**CI runs backend tests before the app compiles**, so a backend failure masks every Android error
behind it. A green backend is not evidence the app compiles.

**A green `:core` is not evidence either, and this one bites.** Changing a default in `:core` that
`:app` consumes will break `:app` tests that no local run can see - this sandbox has no Android
SDK. Lengthening the voice-activity calibration did exactly that: six `ListenControllerTest` cases
failed on a value they had hard-coded, and the fix then failed a second time because the constant
they needed lived in a `private companion object`. **Before changing any `:core` default, grep
`:app` for callers and for tests that encode the old value.** Where a test has to wait for a
constant, read it from the code rather than writing the number down again.

**`.env` lives in the repo root** and is found by searching upward from the working directory
(`gradle :backend:run` starts in `backend/`). The startup line `Loaded .env from ...` says which
file was read; `No .env found` means the keys are not loaded no matter what the file contains.

## Rules that do not bend

- **Never commit a key.** `.env` is ignored; **`.env.example` is tracked** and has had real keys
  pasted into it twice now - the names differ by eight characters and editors autocomplete to the
  wrong one. This is now enforced rather than remembered: `scripts/check-secrets.sh` fails CI on
  any credential in a tracked file, and runs first so it cannot be masked by a later failure.
  **Enable the local hook once per clone** so it is caught before the commit rather than after
  the push: `git config core.hooksPath .githooks`. If it does happen:
  `Copy-Item .env.example .env -Force` then `git checkout -- .env.example`.
- **No raw audio to disk, ever.** Rolling in-memory buffers only. `.pcm` and `.wav` are
  git-ignored for this reason. Raw audio logging off by default; transcripts off or minimal.
- **Never fabricate a result.** If a call was not made, say so. An UNKNOWN TO VERIFY entry is a
  better deliverable than a plausible number - most of this project's risk register exists because
  wire formats were inferred from SDKs rather than observed, and every one of those is labelled.
- **Silence is the default.** `NO_RESPONSE` is a first-class outcome, not a failure to handle.
- Report what failed, with the raw text. The error envelope from OpenRouter is still only
  SDK-derived because no live call has failed yet (risk 27) - the first real failure is worth
  capturing verbatim.

## Useful commands

```powershell
cd C:\Users\Vector\Operator
.\scripts\preflight.ps1                       # config, database, provider slots
.\gradlew.bat :backend:run "-Poperator.skipAndroid=true"
.\scripts\decide-drill.ps1 -OutFile run2.csv  # stage 6 backend, ~2 min
curl.exe -s http://localhost:8080/usage
```

Tests: `./gradlew :core:test :backend:test -Poperator.skipAndroid=true` - 239 pass, 3 skip
without Docker (`PostgresMemoryStoreTest`, Testcontainers). `:app` compiles only in CI or Android
Studio.

Docker is not installed on the test machine, so the memory store is in-memory and does not survive
a restart. Every retrieval test above still ran correctly; only persistence is missing.
