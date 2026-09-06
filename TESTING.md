# TESTING — OPERATOR

How to test Operator for real, in the order that makes the results mean something.

**Why the order matters.** Every stage assumes the one before it works. If the first live model
call fails, an Active Operator that never speaks tells you nothing — you cannot tell a
well-behaved decision engine from a broken provider. Do not skip ahead.

**What this is for.** Milestones 0–13 are built and green in CI, but almost all of it has only
ever run against mock HTTP engines and fake providers. Testing is not a formality here; it is the
first time most of this code meets reality. `docs/RISKS_AND_UNKNOWNS.md` lists what is genuinely
unknown, and each stage below names the risk numbers it closes.

Per-milestone detail lives in `CURRENT_STATUS.md` under "Next tests". This file is the running
order and the setup around it.

---

## Before anything

```powershell
cd C:\Users\Vector\Operator
git pull
```

`git pull` is not optional. Before commit `bf2d66f` the backend looked for `.env` in its own
working directory, which is `backend/` under Gradle — so a `.env` in the repo root was silently
ignored and every key read as null. If the backend logs `openRouterApiKey=null` with a filled-in
`.env`, this is why.

### Keys

`.env` lives in the repo root and is git-ignored. `.env.example` is **tracked** — never put real
keys in it. If you already did:

```powershell
Copy-Item .env.example .env -Force
git checkout -- .env.example
git status --short          # .env.example must not appear
```

Fill in `.env` as each stage needs it. Nothing needs all of it at once.

| Stage | Keys needed |
|-------|-------------|
| 1 | none |
| 2 | `OPENROUTER_API_KEY`, `OPERATOR_FAST_MODEL_ID` |
| 3 | + `OPERATOR_EMBEDDING_MODEL_ID` (optional) |
| 4 | + `TRANSCRIPTION_API_KEY`, `OPERATOR_TRANSCRIPTION_MODEL_ID` |
| 5 | + `ELEVENLABS_API_KEY`, `OPERATOR_ELEVENLABS_VOICE_ID`, `OPERATOR_ELEVENLABS_MODEL_ID` |
| 6 | + `OPERATOR_DECISION_MODEL_ID` (empty falls back to the fast model) |

### Preflight, then the backend

Run preflight **first**. It checks that `.env` exists and is untracked, that no keys leaked into
`.env.example`, brings Postgres up itself, and then reads `/health`. It never prints secrets — it
reports each provider slot as configured or not.

```powershell
.\scripts\preflight.ps1
```

With nothing running yet it will tell you to start the backend. Do that in a second terminal:

```powershell
.\gradlew.bat :backend:run "-Poperator.skipAndroid=true"
```

The quotes are not optional. Unquoted, PowerShell splits the argument at the dot and Gradle sees
`-Poperator` and `.skipAndroid=true`, then fails with `Task '.skipAndroid=true' not found`. Bash
and CI do not do this, which is why the same line works unquoted everywhere else in the docs.

Watch the first lines of output for:

```
Loaded .env from C:\Users\Vector\Operator\.env
```

If it says `No .env found (searched the working directory and its parents)`, stop and fix that
before going further — everything downstream will fail in confusing ways.

Then run preflight again. This time it reaches `/health` and reports which provider slots are
actually live. It reads the backend's own health report rather than re-deriving it, so it cannot
disagree with the server.

`.\scripts\preflight.ps1 -SkipDatabase` if you are not running Docker. To bring the database up
by hand instead: `docker compose -f backend\docker-compose.yml up -d`.

> `/health` answers **503 when degraded** — no database, say — while still returning the full body
> describing what is wrong. That is a report, not an outage. `Invoke-RestMethod` throws on any
> non-2xx and would hide it, so query it with `curl.exe` (or `Invoke-WebRequest
> -SkipHttpErrorCheck`, which exists only on PowerShell 6 and later).

### How the request examples work

Two PowerShell traps are worth knowing before the first one:

- `curl` is an **alias for `Invoke-WebRequest`**. The examples all use `curl.exe`, which is the
  real thing and ships with Windows 10 and later.
- Passing JSON inline (`-d '{"a":"b"}'`) is **not portable**. Windows PowerShell 5.1 strips the
  embedded double quotes on the way to a native executable, and PowerShell 7 changed the rules
  again. So every example writes the body to a file first and passes `-d "@file.json"`. This
  behaves identically on both versions and needs no escaping at all.

`curl.exe` also prints the response body on an error status, which `Invoke-RestMethod` does not —
and the raw error body is exactly what is worth sending back.

Write the body files anywhere; they are scratch. Delete them when you are done.

---

## Stage 1 · The phone, with no keys at all

Independent of the backend, so run it while waiting on credits. These are the **oldest unverified
things in the project** — written in the earliest sessions, never once run on hardware.

```powershell
.\gradlew.bat :app:assembleDebug
```

Install the APK on the Galaxy. Then work through, in order:

- **Milestone 1 (phone audio):** GRANT MICROPHONE → RECORD TEST → PLAY TEST. Speech should be
  understandable and the actual devices should be the ones you expect. *Risks 11, 13.*
- **Milestone 2 (Bluetooth):** GRANT BLUETOOTH → the paired list shows the headset/glasses →
  OUTPUT=A2DP PLAY TEST → INPUT=SCO RECORD TEST, and the route log should say
  `Communication device active` → OUTPUT=SCO PLAY TEST → disconnect and confirm it falls back to
  DEFAULT. Note the SCO bring-up time. *Risks 3, 14, 16, 17, 18.*
- **Milestone 3 (Meta SDK):** needs the Meta AI app with Developer Mode on, the glasses paired to
  it, and `github_token` in `local.properties`. Follow the device plan in `docs/META_GLASSES.md`:
  register, device list, session start/stop, camera permission, mock kit. **Record the device type
  string the glasses report.** *Risks 21, 22.*

**Record:** whether `routedDevice` was ever reported as null (the code logs
`routedDevice was never reported` if so), SCO bring-up time, and the glasses' device type string.

---

## Stage 2 · The first live model call

**This is the single highest-value action left in the project.** OpenRouter's wire format was
verified against their own published SDK (`@openrouter/ai-sdk-provider` 3.0.0) because
`openrouter.ai` is blocked from the build sandbox. No real call has ever been made. Everything
downstream — memory, decisions, Active Operator — assumes this shape is right.

```powershell
Set-Content -Path ask.json -Encoding ascii -Value '{"prompt":"Say hello in one short sentence.","useMemory":false}'
curl.exe -s -X POST http://localhost:8080/ai/respond -H "Content-Type: application/json" -d "@ask.json"
```

Expect a JSON body with `text`, `model`, `tier`, `routingReason`, `latencyMillis`, and token
counts. *Closes risks 27, 28. Answers risk 7 (first-token latency) with a real number.*

If it fails, **paste the raw error** — the error envelope shape is exactly the part that was
guessed, and a mismatch there is a five-minute fix that would otherwise poison every later stage.

Then check what it cost:

```powershell
curl.exe -s http://localhost:8080/usage
```

---

## Stage 3 · Memory round trip

Two calls, in this order.

```powershell
Set-Content -Path remember.json -Encoding ascii -Value '{"prompt":"Remember that Chris handles the west territory."}'
curl.exe -s -X POST http://localhost:8080/ai/respond -H "Content-Type: application/json" -d "@remember.json"
```

Expect `"model": "none"` and a `memoryWritten` block. An explicit "remember that…" is stored
directly — the user already said exactly what to keep, so a model round trip would only add
latency and a way to get it wrong. This call is free.

```powershell
Set-Content -Path recall.json -Encoding ascii -Value '{"prompt":"Who handles the west?"}'
curl.exe -s -X POST http://localhost:8080/ai/respond -H "Content-Type: application/json" -d "@recall.json"
```

Expect Chris in `text`, and the memory listed in `memoriesUsed` with a `why`.

**With an embedding model configured** (`OPERATOR_EMBEDDING_MODEL_ID`), also expect
`semanticRetrieval: true` and a `retrievalMillis`. Without one, retrieval is lexical and
structured only — a supported mode, not a failure.

Backfill vectors for anything stored before the embedding model was set:

```powershell
curl.exe -s -X POST http://localhost:8080/memory/backfill-embeddings
```

*Closes risks 30, 31, and completes 32.* Note `retrievalMillis` — retrieval embeds the query
before searching, so the answer path now carries two provider calls. If that hurts, we cache query
embeddings or retrieve lexically first and embed only on a miss.

---

## Stage 4 · Hearing

Needs a transcription key, which **may be a different vendor** from the model provider —
`TRANSCRIPTION_API_KEY` is separate from `OPENROUTER_API_KEY`. Any endpoint speaking the
OpenAI-compatible `/audio/transcriptions` shape works, including a self-hosted Whisper; point
`OPERATOR_TRANSCRIPTION_BASE_URL` at it.

Same substitution risk as stage 2: the multipart fields came from the official `openai` npm
package 7.10.0, not the hosted reference. *Risks 34, 35.*

In the app: GRANT MICROPHONE → START LISTENING.

1. Status reads **"Learning the room"**, then **"Listening"**.
2. **Stay silent for 30 seconds.** The utterance count must stay at 0 and nothing may reach the
   backend. *This is the privacy gate, and it is the single most important assertion in this
   file* — if silence uploads audio, stop and tell me before going further.
3. Speak one sentence. Status goes "Capturing speech" → "Transcribing", the transcript appears.
   Note the round trip.
4. STOP LISTENING mid-sentence: no transcript may arrive afterwards.
5. Repeat with INPUT set to the glasses/headset (SCO) and **compare accuracy against the phone's
   own microphone**. SCO is narrowband on many headsets and this may be materially worse. *Risk 36.*
6. Repeat in a noisy room and confirm the gate does not latch open. *Risk 33.*

**Record:** round-trip time, accuracy phone vs SCO, and whether the gate held in noise. The
VAD thresholds — activation factor, onset/hangover counts, calibration length, minimum utterance
length — were chosen to be testable, not measured. Expect to tune them.

Then **Milestone 11**, which is the same path left running: confirm the "Operator is listening"
notification appears, lock the screen, talk, unlock, and check the lines are in the Rolling
conversation panel *(risk 38)*. STOP on the notification must close the microphone. Ask a question
and check "Conversation sent" reports the lines. FORGET WHAT WAS SAID empties the panel and the
next question sends 0 lines. Leave it running an hour and note battery drain and the `GET /usage`
transcription spend *(risks 39, 40, 41)*.

---

## Stage 5 · Voice, and the glasses

Configure the ElevenLabs key, voice ID, and model ID. The key never enters the APK — audio streams
from the backend as raw signed 16-bit little-endian mono PCM at 24 kHz.

Backend alone first:

```powershell
Set-Content -Path say.json -Encoding ascii -Value '{"text":"Testing one two three."}'
curl.exe -s -X POST http://localhost:8080/tts/synthesize -H "Content-Type: application/json" -d "@say.json" --output test.pcm
```

A 503 with "ElevenLabs voice and model IDs must be configured" means the IDs are missing, not the
key. `test.pcm` is headerless PCM — Audacity will import it as raw 24 kHz mono 16-bit signed LE.

Then in the app: ask a typed question, tap SPEAK ANSWER. Audio should begin **incrementally**, not
after the whole clip arrives. **Record first-audio latency.** Confirm STOP SPEAKING and emergency
mute both stop it immediately. *Risks 8, 9.*

**Milestone 10 (half-duplex):** select the Ray-Ban Bluetooth input and output, start listening,
then speak an answer. Confirm the hearing route is the glasses mic, that listening **pauses during
speech and resumes afterward**, and that the voice route is the glasses speaker. Repeat while music
and Meta AI are active, disconnect/reconnect once, and record route changes and battery impact over
an hour. *Risks 1, 2, 4, 42.*

---

## Stage 6 · Deciding, and Active Operator

Set `OPERATOR_DECISION_MODEL_ID` (empty falls back to the fast model).

**Milestone 12 first — on a button, not ambient.** Talk for a minute with listening on, then press
ANYTHING TO SAY?

- Expect SILENT far more often than SPOKE. Check the REASON code and whether it was free or a
  model call.
- Press it twice quickly: the second must be refused as `RECENTLY_SPOKE`.
- Set mode to QUIET: refused **free**, with no model call.
- Mute: COMMENT NOW refused.

**The backend half needs no phone at all.** `scripts\decide-drill.ps1` puts a fixed set of
conversations to `/decide` and tabulates what came back:

```powershell
.\scripts\decide-drill.ps1
.\scripts\decide-drill.ps1 -OutFile drill-baseline.csv
```

It runs three groups in a deliberate order. The **free** cases are refused by the local rules
before any model call — `gate()` returns before `recordDecision()`, so they cost nothing *and* do
not consume the decision interval, which is why they can run back to back. The **ambient** cases
are the real test, spaced by `-GapSeconds` (21 by default) because each one that reaches the model
blocks the next for `MIN_DECISION_INTERVAL_SECONDS`. The **invited** cases run last, since
`DIRECT_ADDRESS` and `COMMENT_NOW` bypass the interval and budget and would otherwise disturb the
ambient timing.

Read the summary line `ambient spoke`. Save a CSV with `-OutFile` before changing a prompt or a
threshold, so the next run is a comparison rather than an impression.

Or drive a single case by hand:

```powershell
Set-Content -Path decide.json -Encoding ascii -Value '{"trigger":"AMBIENT","transcript":["Someone: what time is the meeting"]}'
curl.exe -s -X POST http://localhost:8080/decide -H "Content-Type: application/json" -d "@decide.json"
```

Silence comes back as a normal 200 with `shouldSpeak: false`. It is a decision, not a failure.
`gatedLocally: true` means the local rules refused before any model was asked — that path is free.

**Milestone 13 last.** Switch ACTIVE OPERATOR on. Talk normally for ten minutes.

The question is not whether it speaks well. **The question is whether it stays quiet.** Count how
often it speaks and whether any of it was worth hearing. Confirm it waits for a pause rather than
cutting in, that it never reacts to its own last line, and that mute switches it off. Check
`GET /usage` for what the decisions cost against the budget.

**Expect this stage to disappoint at first.** The confidence and relevance floors, the 2.5-second
settle delay, the 20-second decision interval, and the 12-per-five-minutes budget are all estimates
chosen to be testable, not measurements. *Risks 43, 44, 47, 48, 49.* If it chatters, that is risk
43 landing exactly where it was predicted, and the fix is prompt and threshold work — not code.

Active Operator is off by default and not persisted. It does not survive a restart, by design.

---

## What to send back

| Stage | What is worth reporting |
|-------|------------------------|
| 1 | Route log lines, SCO bring-up time, glasses device type string |
| 2 | The raw JSON — **including errors, verbatim** |
| 3 | `memoriesUsed`, `semanticRetrieval`, `retrievalMillis` |
| 4 | Round-trip time, phone vs SCO accuracy, whether silence stayed silent |
| 5 | First-audio latency, whether listening paused and resumed |
| 6 | How often it spoke in ten minutes, and whether it was worth hearing |

Plus `GET /usage` after each stage that costs money.

Raw errors matter more than descriptions of them. Two of the risks above exist precisely because a
wire format was inferred rather than observed, and the error envelope is the part most likely to be
wrong.

---

## If something is wrong

- **`openRouterApiKey=null` with a filled-in `.env`** — you have not pulled `bf2d66f`. See the top.
- **Preflight says the backend is not running, but it is** — check you are on the current
  `preflight.ps1`; an older copy used `Invoke-RestMethod`, which throws on the 503 that `/health`
  returns when degraded.
- **`/health` returns 503** — read the body. It describes exactly what is degraded, usually the
  database.
- **The app cannot reach the backend** — `OPERATOR_BACKEND_URL` in `local.properties`. Emulator:
  `http://10.0.2.2:8080`. Phone on the same Wi-Fi: `http://<your-laptop-ip>:8080`. Debug builds
  allow cleartext HTTP; release builds do not.
- **The Glasses panel says "not compiled in"** — `github_token` with `read:packages` is missing
  from `local.properties`.
- **Anything contradicts this file** — the code wins. Tell me about the mismatch rather than
  working around it.

## Never, while testing

- Never put a real key in `.env.example`. It is tracked.
- Never commit `.env`, `local.properties`, or a `.pcm`/`.wav` capture.
- Never paste a key into a chat, an issue, or a log. Report keys as set/not set.
