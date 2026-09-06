# CURRENT STATUS — OPERATOR

**Current milestone:** 12 — Response decision engine (merged to `main`).

**`main` contains Milestones 0 through 12.** Milestones 1 to 3 still await verification on real
hardware, and no live model, transcription, or TTS call has ever been made. Both gaps are listed
below.

**Last updated:** 2026-09-05

## What works (verified)

- Milestone 12: the decision stage. `ConversationPolicy` refuses locally — on mute, OFF, modes
  that never volunteer, an empty transcript, the comment interval and the five-minute cap —
  before any model is asked, so a refused moment costs nothing. What survives goes to the
  decision model, and what the model returns is reviewed by the same rules: confidence and
  relevance floors for unprompted comments, three sentences maximum, and no repeating itself.
  Every failure is silence. `POST /decide` reports the outcome, whether it was free, and whether
  the model was overruled. COMMENT NOW now actually asks. 42 new tests.
- Milestone 11: rolling transcription. `RollingTranscript` holds the last
  `ROLLING_CONTEXT_SECONDS` of conversation in memory, bounded by age and by entry
  count and pruned on read as well as on write. Transcripts join it as they arrive;
  `POST /ai/respond` accepts the window and puts it in front of the model as conversation, capped
  server-side at 80 lines of 500 characters. `TranscriptionService` is a foreground service with
  `FOREGROUND_SERVICE_MICROPHONE` so listening survives the screen going off, with an ongoing
  notification that says so and stops it in one tap. FORGET WHAT WAS SAID drops the window.
- Milestone 8: the hearing path. `VoiceActivityDetector` gates on RMS against a noise floor it
  calibrates to the room, with onset and hangover hysteresis; `SpeechSegmenter` turns frames into
  whole utterances using bounded in-memory buffers and a pre-roll, discards anything too short to
  be speech, and caps a monologue rather than buffering it; `WavEncoder` containerises in memory.
  On the backend, `OpenAiCompatibleTranscriptionProvider` posts multipart to
  `/audio/transcriptions` at a configurable base URL and `POST /transcribe` takes raw PCM-16.
  On the phone, `ContinuousMicrophone` streams frames and `ListenController` drives
  microphone → detection → utterance → backend → transcript, with a Listen panel that shows the
  level, the status, and the transcripts it is holding in memory. 54 new tests (core 39 to 61,
  backend 73 to 95, app 8 to 18), all green in CI run #14 along with `assembleDebug`.
- Milestone 7: memory-aware answering. `MemoryRetrievalEngine` blends semantic, lexical, and
  entity-linked candidates and applies a relevance floor; `MemoryScopePolicy` decides which
  privacy scopes a mode may read; `MemoryWriteEngine` recognises "remember that…" and stores it
  without a model call; `ContextAssembler` builds the runtime block and labels memories as memory
  rather than live data. 73 backend tests pass, including the brief's acceptance scenario end to
  end over HTTP: storing "Chris handles the west" and then answering "Who handles the west?" with
  that memory in front of the model.

- Milestone 6: OpenRouter provider (wire format verified against OpenRouter's own
  published SDK, see risk 27), `ModelRouter` for the FAST/DEEP/VISION tiers, `PromptLibrary`
  loading versioned personality prompts, `POST /ai/respond`, `GET /usage`, and an in-process
  `UsageTracker`. 45 backend unit tests pass locally, 23 of them new, including the provider
  against a mock HTTP engine (success, error envelope on both 4xx and 200, unknown fields,
  transport failure) and the route (routing, failure mapping, usage counting, no prompt echo).
  On the phone, the Ask Operator panel and its backend client, with 3 unit tests. CI run #6 is
  green on core and backend tests, app unit tests, `assembleDebug`, and the pgvector integration
  job.

- Memory database v1: normalized schema (users, organizations, people, projects, memories,
  memory_embeddings on pgvector, memory_events, conversation_sessions), `MemoryStore` with
  create / get / search / update / delete / disable / mark-incorrect / touch / events /
  embeddings / cosine similarity, REST API, idempotent demo memories. 22 backend unit tests
  pass; the CI integration job runs `backend/scripts/memory_api_smoke.py` against real
  PostgreSQL + pgvector (create, duplicate 409, search filters, patch, embeddings + similarity,
  disable/enable, mark-incorrect, events, delete → 404, demo seed idempotence).
- `:backend` (Ktor 3.5, HikariCP, Flyway, pgvector): `/health` with truthful database and
  provider status, redacted config, `.env` loading; 10 unit tests pass locally and in CI, and the
  CI integration job boots it against `pgvector/pgvector:0.8.6-pg17` and gets `status: ok` with
  the pgvector version reported.
- `:core` compiles and its 39 unit tests pass locally and in CI.
- `:app` and `:glasses-meta` compile (`assembleDebug`, SDK pulled from GitHub Packages with the
  workflow token) and app unit tests pass in GitHub Actions (run #11); debug APK attached to each
  green run as `operator-debug-apk` (about 21 MB with the Meta SDK).

## What is implemented but NOT yet exercised for real

- ElevenLabs TTS: backend-only credentials, configurable voice/model IDs, streaming
  `pcm_24000` synthesis through `POST /tts/synthesize`, incremental Android `AudioTrack`
  playback using the selected output route, STOP SPEAKING, emergency-mute cancellation,
  first-audio timing, TTS character accounting, and mock-backed provider, route, and controller
  tests are implemented. Configure `ELEVENLABS_API_KEY`, `OPERATOR_TTS_PROVIDER=elevenlabs`,
  `OPERATOR_ELEVENLABS_VOICE_ID`, and `OPERATOR_ELEVENLABS_MODEL_ID` on the backend, then use
  SPEAK ANSWER in the app. Audio streams as raw signed 16-bit little-endian mono PCM at 24 kHz;
  the API key never enters the APK.
- Milestone 6 end to end: no live model call has ever been made. Every provider test uses a mock
  HTTP engine. To try it for real, put `OPENROUTER_API_KEY` and `OPERATOR_FAST_MODEL_ID` in the
  backend `.env`, `OPERATOR_BACKEND_URL` in the app's `local.properties`, then use the Ask
  Operator panel. Risks 27 and 28.
- Milestone 8 end to end: no live transcription call has ever been made. Every test uses a mock
  HTTP engine or a fake provider. To try it for real, put `TRANSCRIPTION_API_KEY` and
  `OPERATOR_TRANSCRIPTION_MODEL_ID` in the backend `.env` (optionally
  `OPERATOR_TRANSCRIPTION_BASE_URL` to point at another vendor or a self-hosted Whisper), then
  use the Listen panel. Risks 34 and 35.
- Voice-activity thresholds have never been measured on a real microphone; they were chosen to be
  testable. Risk 33. Speech recognition over a narrowband Bluetooth SCO link is also unmeasured
  (risk 36).
- Semantic retrieval has never run against a real embedding model. Set
  `OPERATOR_EMBEDDING_MODEL_ID` alongside the OpenRouter key to enable it; without it retrieval is
  lexical and structured only, which is a supported mode rather than a failure. Risks 30 to 32.

## What is implemented but NOT yet verified on a device

- Milestone 10: `GlassesAudioCoordinator` enforces half-duplex use of the shared Bluetooth
  communication route. If listening was active, speech pauses the microphone and resumes it after
  playback; explicit stop/mute revokes pending resume. The Glasses Audio panel shows selected and
  actual hearing/voice routes and whether Bluetooth endpoints are ready for the device check.
  This cannot establish Ray-Ban route reliability, Meta AI coexistence, or battery impact without
  the target phone and glasses.
- Milestones 1–2: audio loopback, route selection, Bluetooth diagnostics (see earlier checklist
  items below).
- Milestone 3: `:glasses-meta` wraps the Meta Wearables Device Access Toolkit 0.9.0 behind
  `GlassesProvider`: SDK initialisation, registration with the Meta AI app, linked-device list
  with metadata, device session start/stop, camera-permission check, firmware-update link, and
  MockDeviceKit developer actions. Glasses panel + GLASSES subsystem indicator. Capability
  findings are in `docs/META_GLASSES.md`.

## Key finding

The Meta toolkit exposes **no microphone or speaker API** (0.9.0). Glasses audio stays on the
standard Bluetooth path built in Milestone 2. The SDK contributes camera, device state,
registration, and mock testing.

## Build notes

- The SDK lives on GitHub Packages and needs a token with `read:packages`. With
  `github_token` in `local.properties` (or `GITHUB_TOKEN` in the environment) the
  `:glasses-meta` module is included; without it the app still builds and the Glasses panel
  says "not compiled in". CI passes `GITHUB_TOKEN` and forces `-Poperator.metaSdk=true`.
- minSdk is now 31 (Android 12), matching Meta's samples and the communication-device API.

## What does not work / not started

- Transcription does not feed the model: `POST /transcribe` returns a transcript and stops there.
  Turning speech into an answer needs the decision engine and rolling context (Milestones 11-12).
- Listening is still started by hand: the user presses START LISTENING. It now continues in the
  background until stopped, but Operator never decides on its own to start.
- Operator's own replies never reach the rolling window: nothing speaks yet (Milestone 9).
- Nothing decides on its own *when* to consider speaking: the decision runs when the user presses
  a button, not when new speech arrives. Firing it automatically is Milestone 13, and is
  deliberately held until the thresholds have been tuned against a real conversation (risks 44-45).
- No authentication (single default user, ADR-021).
- Camera streaming/photo (Milestone 16), rolling context, decision
  engine, BLE ring, integrations.
- No launcher icon.

## Current blockers

- Human verification on hardware. Milestone 3 additionally needs: the Meta AI app with
  Developer Mode enabled, the glasses paired to it, and a GitHub token for the build.
- In this authoring sandbox, Gradle 9.6 cannot close/read its distribution JARs and fails before
  project configuration with `AccessDeniedException`. Fresh Gradle homes and JDK 17/21 produced
  the same result, so the combined M8/M9 tests need a normal local or CI run.

## Next tests

Milestone 9 (voice): run the mock test suites in CI, configure a test ElevenLabs voice/model,
ask a typed question, tap SPEAK ANSWER, verify audio begins incrementally on the selected route,
then verify STOP SPEAKING and emergency mute stop it immediately. Record first-audio latency.

Milestone 10 (glasses audio): select the Ray-Ban Bluetooth input and output, start listening,
then speak an answer. Confirm the hearing route is the glasses mic, listening pauses during
speech, the voice route is the glasses speaker, and listening resumes afterward. Repeat while
music and Meta AI are active, disconnect/reconnect once, and record route changes and battery
impact over a one-hour session.

Milestone 1 (phone audio): launch → GRANT MICROPHONE → RECORD TEST → PLAY TEST → speech
understandable → actual devices correct.

Milestone 2 (Bluetooth): GRANT BLUETOOTH → paired list shows headset/glasses → OUTPUT=A2DP
PLAY TEST → INPUT=SCO RECORD TEST with route-log "Communication device active" → OUTPUT=SCO
PLAY TEST → disconnect falls back to DEFAULT → note SCO bring-up time.

Milestone 8 (hearing): configure `TRANSCRIPTION_API_KEY` + `OPERATOR_TRANSCRIPTION_MODEL_ID` on
the backend → GRANT MICROPHONE → START LISTENING → the status should read "Learning the room",
then "Listening" → stay silent for 30 s and confirm the utterance count stays at 0 and nothing
reaches the backend → speak one sentence → status goes to "Capturing speech" then "Transcribing"
and the transcript appears → note the round trip → STOP LISTENING mid-sentence and confirm no
transcript arrives afterwards → repeat with INPUT set to the glasses/headset (SCO) and compare
accuracy against the phone's own microphone (risk 36) → repeat in a noisy room and confirm the
gate does not latch open (risk 33).

Milestone 11 (rolling transcription): START LISTENING → confirm the "Operator is listening"
notification appears → lock the screen, talk, unlock, and confirm the lines are in the Rolling
conversation panel (risk 38) → tap STOP on the notification and confirm the microphone closes →
ask a question in the Ask Operator panel and check "Conversation sent" reports the lines →
FORGET WHAT WAS SAID empties the panel and the next question sends 0 lines → leave it running an
hour and note battery drain and the `GET /usage` transcription spend (risks 39-41).

Milestone 12 (deciding): configure `OPERATOR_DECISION_MODEL_ID` → talk for a minute with
listening on → ANYTHING TO SAY? → expect SILENT far more often than SPOKE, and check the REASON
and whether it was free or a model call → press it twice quickly and confirm the second is
refused as RECENTLY_SPOKE → set mode to QUIET and confirm it is refused free, without a model
call → mute and confirm COMMENT NOW is refused → the real question is whether the model stays
quiet in an ordinary conversation (risk 43); if it chatters, the prompt and the floors need work
before ambient deciding is wired up.

Milestone 3 (Meta SDK): follow the device test plan in `docs/META_GLASSES.md` (register,
device list, session start/stop, camera permission, mock kit) and record the glasses' reported
device type string.
