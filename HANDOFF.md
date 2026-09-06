# HANDOFF - live testing

Written 2026-09-06 for whoever continues the live testing, Codex included. `main` is at the
commit that added this file. Milestones 0-13 are merged; the roadmap is in `README.md`.

The running order is [TESTING.md](TESTING.md). This file is the state of play, the traps, and
the rules that are not negotiable.

## Where testing got to

Stages 1-3 and the backend half of stage 6 are done. What is left needs the phone, a
transcription key, or both.

| Stage | State |
|-------|-------|
| 1 - device checks (M1-M3) | **ready to run.** The app builds and launches on the Galaxy as of 2026-09-06. M1 and M2 need no keys; M3 needs a GitHub token (see below). Oldest unverified code in the project - start here. |
| 2 - first live model call | **done.** `deepseek/deepseek-v4-flash`, 2019 ms, $0.0000688. Risks 27, 28 closed. |
| 3 - memory round trip | **done.** Stored free, recalled at semantic 0.62 with real embeddings. |
| 4 - hearing | **unblocked** - key configured 2026-09-06. Needs the APK. Wire format still SDK-derived (risks 34, 35). |
| 5 - voice and glasses | **not started.** Needs the APK and ElevenLabs. |
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
2. **Stage 1 on the phone - do this next.** The app is installed and running; nothing else is
   needed for M1 and M2. `CURRENT_STATUS.md` has the step lists. Milestone 1: GRANT MICROPHONE,
   RECORD TEST, PLAY TEST, and check the reported devices are the ones expected. Milestone 2:
   GRANT BLUETOOTH, then A2DP output, SCO input, SCO output, then disconnect and confirm it falls
   back to DEFAULT. Record the SCO bring-up time and watch for `routedDevice was never reported`
   in the log, which would mean the phone is not telling us where audio actually went (risk 13).
3. **Stage 4**, once the transcription key question below is answered.
4. **Stage 5, then the phone half of stage 6.** In that order: Active Operator is only
   interpretable once speech out works.

## The transcription key question - DECIDED 2026-09-06

`.env` currently has `OPERATOR_TRANSCRIPTION_BASE_URL=https://openrouter.ai/api/v1` and
`OPERATOR_TRANSCRIPTION_MODEL_ID=openai/whisper-large-v3-turbo`, but `TRANSCRIPTION_API_KEY` is
null. The backend reads that key separately and **will not** fall back to `OPENROUTER_API_KEY`.

**Resolved by configuration: `TRANSCRIPTION_API_KEY` is set to the same OpenRouter key.** No
code change, and none is wanted - the alternative was to make the key fall back when the base URL
matches the model provider's host, which couples two settings that ADR-005 keeps deliberately
separate. Transcription may yet move to a different vendor, and the separate variable is what
makes that a one-line change. Do not implement the fallback unless the user asks.

Stage 4 is therefore unblocked and needs only the APK. Note the backend must be restarted after
editing `.env`; config is read once at startup.

## The phone is set up

The APK builds and runs on the Galaxy as of 2026-09-06, from Android Studio (Open the
`C:\Users\Vector\Operator` folder, then Run). That was `:app`'s first build outside CI and it
needed no code changes. Building from the command line also works:
`.\gradlew.bat :app:assembleDebug`, APK at `app\build\outputs\apk\debug\app-debug.apk`.

`local.properties` (git-ignored, not in the repo) is configured. Two things about it:

- **`OPERATOR_BACKEND_URL` is the laptop's LAN IP**, currently `http://192.168.1.124:8080`, not
  localhost - the phone cannot reach localhost. **It changes when the network does**, so if the
  app suddenly cannot reach the backend, check this before assuming anything is broken. Phone and
  laptop must be on the same Wi-Fi, and Windows Firewall prompts on the first connection.
- **The model IDs in it are display-only.** The app reads them into `BuildConfig` for its
  diagnostics panel; it asks the backend for a *tier* (FAST/DEEP/VISION) and never names a model.
  Changing them there changes nothing but the readout. `OPERATOR_EMBEDDING_MODEL_ID` is not read
  by the app at all.

`github_token` is still empty, so `:glasses-meta` is not compiled in and the Glasses panel reads
"not compiled in". That blocks **Milestone 3 only**. To enable it: a *classic* GitHub token with
`read:packages` and nothing else, into `local.properties`, then re-sync. Milestones 1, 2, and
stages 4 and 5 do not need it.

The user had not used Android Studio before this session. Sync after any `local.properties`
change: **File -> Sync Project with Gradle Files**, or the "Sync Now" link in the bar across the
top of the editor. Logcat is the bottom panel; filter it on `Operator` to see the route logs,
which is most of what stage 1 involves. If Android Studio offers to upgrade AGP or Gradle,
**decline** - 9.4.0 and 9.6.0 are pinned and CI is green on them.

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

**`.env` lives in the repo root** and is found by searching upward from the working directory
(`gradle :backend:run` starts in `backend/`). The startup line `Loaded .env from ...` says which
file was read; `No .env found` means the keys are not loaded no matter what the file contains.

## Rules that do not bend

- **Never commit a key.** `.env` is ignored; **`.env.example` is tracked** and has had real keys
  pasted into it once already. If it happens: `Copy-Item .env.example .env -Force` then
  `git checkout -- .env.example`, and check whether it was committed.
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
