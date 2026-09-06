# OPERATOR — Private Assistance System

A private AI companion that lives in smart glasses. An Android phone is the compute and
network hub; the glasses provide microphone and speaker. Operator listens when explicitly
activated, understands the conversation, and — most of the time — says nothing. Occasionally
it whispers something useful, corrective, or funny.

> Silence is the default. `NO_RESPONSE` is the most common outcome by design.

**Status:** Milestones 0–10 implemented; glasses audio still needs target-device verification. See [CURRENT_STATUS.md](CURRENT_STATUS.md) and [docs/META_GLASSES.md](docs/META_GLASSES.md).

## Hardware target

- Ray-Ban Meta Gen 2 smart glasses (Milestone 3+; isolated behind `MetaGlassesManager`)
- Samsung Galaxy flagship Android phone (Android 12 / API 31 minimum)

The Meta toolkit (0.9.0) has no microphone or speaker API, so Operator's audio always uses
standard Bluetooth routes: phone mic or glasses mic over HFP → Operator → glasses speakers.
The SDK adds camera, device state, registration, and a mock device for testing.

## Architecture

Two Gradle modules today, more later:

| Module | Purpose |
|--------|---------|
| `:core` | Pure Kotlin/JVM. Domain model (`OperatorMode`, `WitLevel`, `OperatorState`), `OperatorStateManager`, provider contracts (`AIProvider`, `TTSProvider`, `TranscriptionProvider`, `MemoryRepository`), `ResponseDecision`, latency timeline, audio loopback state machine. No Android. |
| `:glasses-meta` | Optional. The only module that touches the Meta Wearables Device Access Toolkit; implements `GlassesProvider`. Included when a GitHub Packages token is present. |
| `:backend` | Ktor server (ADR-017). Owns provider credentials, PostgreSQL + pgvector memory store (Flyway migrations), `/health`, memory REST API. Pure JVM. |
| `:app` | Android app. Jetpack Compose UI, `AudioRecord`/`AudioTrack` implementations with explicit route selection, Bluetooth communication-link handling, permission handling, diagnostics. |

Full layout and data flow: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
Decisions: [docs/DECISIONS.md](docs/DECISIONS.md). Unknowns: [docs/RISKS_AND_UNKNOWNS.md](docs/RISKS_AND_UNKNOWNS.md).

The backend is a Kotlin/Ktor modular monolith that owns provider credentials and the
PostgreSQL + pgvector memory store; the app never holds provider secrets.

## Setup

Requirements: JDK 17+, Android Studio (current stable) with SDK Platform 37, a device or
emulator running Android 12+. For the glasses module: a GitHub personal access token (classic)
with `read:packages` in `local.properties` as `github_token` (the Meta SDK is on GitHub
Packages), the Meta AI app on the phone with Developer Mode enabled, and the glasses paired.

```bash
cp local.properties.example local.properties   # optional: tweak defaults, add sdk.dir
```

`local.properties` and `.env` are git-ignored. Only non-secret values go in
`local.properties`; secrets belong to the backend `.env` (template: `.env.example`).

## Build

```bash
# Core module only — works on any machine with a JDK, no Android SDK needed
./gradlew :core:test -Poperator.skipAndroid=true

# Full build (requires Android SDK; set sdk.dir in local.properties or ANDROID_HOME)
./gradlew :app:assembleDebug

# All unit tests
./gradlew test
```

Install and launch on a connected device:

```bash
./gradlew :app:installDebug
adb shell am start -n com.operator.app/.MainActivity
adb logcat -s AndroidAudioRecorder AndroidAudioPlayer AudioRouteMonitor BluetoothStatusMonitor
```

### Building without the Android SDK

`:core` deliberately has no Android dependency. Passing `-Poperator.skipAndroid=true` excludes
`:app` from the build and keeps the Android Gradle Plugin off the classpath, so
`./gradlew :core:test -Poperator.skipAndroid=true` never resolves AGP or AndroidX and works on
locked-down CI agents and plain laptops. (All plugins otherwise share one root classpath — see
the comment in `build.gradle.kts`.)

## Backend

```bash
cp .env.example .env                                   # fill in keys later; DATABASE_URL matches compose
docker compose -f backend/docker-compose.yml up -d     # PostgreSQL 17 + pgvector 0.8.6 on :5432
./gradlew :backend:run -Poperator.skipAndroid=true     # http://localhost:8080
curl -s localhost:8080/health                          # 200 "ok" with pgvector version; 503 "degraded" if the DB is down
```

Flyway runs the migrations under `backend/src/main/resources/db/migration` at startup
(V1 enables `vector`, V2 creates the memory schema). Without `DATABASE_URL` the backend still
runs with an in-memory store and says so in `/health` (`memoryBackend`).

### Text AI (Milestone 6)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/ai/respond` `{prompt, tier?, maxOutputTokens?, sessionId?, promptVersion?}` | Ask Operator. Returns text, model, tier, routing reason, prompt version, latency, tokens, reported cost. |
| `GET` | `/usage` | Rolling token, latency, and cost counters by day, month, and model. |

Memory-aware answering (Milestone 7): before answering, the backend retrieves a small number of
relevant memories and puts them in the prompt, labelled as memory rather than live data. An
explicit "remember that…" is stored directly and costs no model call. The response reports which
memories were used and why, so the selection can be audited. Which memories are readable depends
on the Operator mode (ADR-026): only WORK mode reads work memories, and PRIVATE ones answer a
direct question but are never volunteered.

Set `OPERATOR_EMBEDDING_MODEL_ID` to enable semantic retrieval. Without it, retrieval falls back
to lexical and entity-linked matching, which is a supported mode rather than a failure.

The phone never holds a provider key: it posts a prompt and renders the answer. Tier selection
lives in `ModelRouter` (ADR-022) and model IDs come from configuration. The personality prompt is
a versioned file under `operator-prompts/system/` (ADR-023) and the version used is returned with
every answer.

In the app, the **Ask Operator** panel needs `OPERATOR_BACKEND_URL` in `local.properties`
(`http://10.0.2.2:8080` from the emulator, `http://<laptop-ip>:8080` from a phone; debug builds
allow cleartext HTTP, release builds do not).

### Hearing (Milestone 8)

The phone opens the microphone, runs voice-activity detection locally, and uploads only complete
utterances — silence never leaves the device. The backend holds the speech credential and posts
the audio to an OpenAI-compatible `/audio/transcriptions` endpoint.

```
POST /transcribe?sampleRateHz=16000&channels=1[&sessionId=]
Content-Type: application/octet-stream
body: raw little-endian PCM-16

200 {"text":"...","provider":"...","languageCode":"en","audioSeconds":1.5,
     "latencyMillis":420,"empty":false}
```

Configure `TRANSCRIPTION_API_KEY` and `OPERATOR_TRANSCRIPTION_MODEL_ID` in the backend `.env`;
`OPERATOR_TRANSCRIPTION_BASE_URL` points the same wire format at another vendor or a self-hosted
Whisper server. The phone needs no transcription credential. An empty transcript is returned as an
empty result, not an error.

### Memory API (Milestone 5)

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/memory/search?text=&type=&scope=&personId=&projectId=&organizationId=&includeInactive=&includeExpired=&limit=` | Filtered search (importance-ordered) |
| `POST` | `/memory/search/similar` `{vector, model?, limit?, …}` | pgvector cosine similarity |
| `POST` | `/memory` | Create (409 on an active duplicate of the same type) |
| `GET` / `PATCH` / `DELETE` | `/memory/{id}` | Read, partial update (`isActive` disables/enables), hard delete |
| `POST` | `/memory/{id}/mark-incorrect` `{reason?}` | Confidence 0, inactive, audited |
| `POST` | `/memory/{id}/touch` | Records a use |
| `GET` | `/memory/{id}/events` | Provenance / audit trail |
| `PUT` | `/memory/{id}/embedding` `{model, vector}` | Store or replace an embedding |
| `GET` / `POST` | `/people`, `/projects`, `/organizations` | Linked entities |
| `POST` | `/memory/demo-seed` | Fictional demo memories (needs `OPERATOR_DEMO_SEED_ENABLED=true`) |

Schema: `docs/MEMORY_SCHEMA.md`. Smoke test against a running backend:
`python3 backend/scripts/memory_api_smoke.py http://localhost:8080`.
Configuration comes from real environment variables first, then `.env`
(`OPERATOR_ENV_FILE` to point elsewhere). `/health` shows a redacted view of it.

## Testing

- `:core` unit tests: JUnit 5 + kotlinx-coroutines-test + Turbine. Run with
  `./gradlew :core:test`.
- `:backend` tests: JUnit 5 + Ktor test host with the in-memory store and a fake database
  (`./gradlew :backend:test`); the CI integration job runs `backend/scripts/memory_api_smoke.py`
  against real PostgreSQL + pgvector.
- `:app` unit tests: JUnit 4 (`./gradlew :app:testDebugUnitTest`).
- CI: `.github/workflows/android.yml` runs core and backend tests, app unit tests, and
  `assembleDebug` on every push, uploads the debug APK, and in a second job boots the backend
  against a real pgvector PostgreSQL service container and asserts `/health` is `ok`.
- Device verification steps for the current milestone are in `CURRENT_STATUS.md`.

## Milestone 1 walk-through

1. Launch → header shows **STATUS: STANDING BY**.
2. **GRANT MICROPHONE** → allow.
3. **RECORD TEST** records ~4 s (progress bar). The clip stays in memory only.
4. **PLAY TEST** plays it back on the current default output (speaker, wired, or Bluetooth).
5. The Audio Test panel shows the device Android *actually* used for capture and playback
   (`routedDevice`), plus all available inputs/outputs.
6. **EMERGENCY MUTE** stops playback immediately and blocks further audio until released.

## Milestone 2 walk-through (Bluetooth audio diagnostics)

1. Pair and connect a Bluetooth headset (or the Ray-Ban Meta glasses) in Android settings.
2. **GRANT BLUETOOTH** (Android 12+) so paired devices are listed by name.
3. In the Audio Test panel pick an **INPUT** and **OUTPUT** chip. DEFAULT leaves routing to
   Android; wired/USB entries use `setPreferredDevice`; Bluetooth SCO / BLE-headset entries
   raise the link with `AudioManager.setCommunicationDevice` for the duration of the test.
4. RECORD TEST / PLAY TEST as before. "Capture path" and "Playback path" say what was
   requested; "Input (actual)" and "Output (actual)" say what Android really did.
5. The **Bluetooth diagnostics** panel lists every `AudioDeviceInfo` with rates, channels,
   encodings, and address, plus the communication-device state. The **Route event log** panel
   (also in logcat) records device changes and the route of every capture/playback.

## Milestone 3 walk-through (Meta glasses)

1. Put `github_token=<PAT with read:packages>` in `local.properties`, rebuild. The Glasses panel
   shows "SDK present · v0.9.0". Without the token the panel says "not compiled in" and the
   rest of the app is unaffected.
2. Enable Developer Mode in the Meta AI app, then **REGISTER WITH META AI**; Registration
   becomes REGISTERED after the round trip.
3. Linked devices lists the glasses with type, link state, and compatibility.
4. **START SESSION** / **STOP SESSION**, **CHECK CAMERA PERMISSION**.
5. No glasses at hand: **MOCK: ENABLE KIT** → **PAIR RAY-BAN META** → **POWER ON + UNFOLD + DON**.
6. The capability table at the bottom of the panel is the SUPPORTED / UNSUPPORTED / UNKNOWN
   verdict from `docs/META_GLASSES.md`.

## Configuration

All keys are documented in `local.properties.example` (app) and `.env.example` (backend).
Highlights:

| Key | Purpose |
|-----|---------|
| `OPERATOR_DEFAULT_MODE`, `OPERATOR_DEFAULT_WIT` | Startup mode/wit |
| `OPERATOR_RECORD_TEST_DURATION_MILLIS` | Milestone 1 recording length |
| `github_token`, `MWDAT_APPLICATION_ID`, `MWDAT_CLIENT_TOKEN` | Meta toolkit download token and attestation (0/0 = Developer Mode) |
| `OPERATOR_*_MODEL_ID` | Fast / deep / decision / vision model IDs (never hard-coded) |
| `OPERATOR_TTS_PROVIDER`, `OPERATOR_ELEVENLABS_VOICE_ID`, `OPERATOR_ELEVENLABS_MODEL_ID` | Voice |
| `TRANSCRIPTION_API_KEY`, `OPERATOR_TRANSCRIPTION_MODEL_ID`, `OPERATOR_TRANSCRIPTION_BASE_URL`, `OPERATOR_TRANSCRIPTION_LANGUAGE` | Hearing (backend only) |
| `ROLLING_CONTEXT_SECONDS`, `MIN_COMMENT_INTERVAL_SECONDS`, `MAX_COMMENTS_PER_5_MINUTES` | Anti-annoyance |

## Privacy model

- No raw ambient audio is ever written to disk. Milestone 1's clip is a `ShortArray` in RAM.
- Transcript logging is off by default (nothing is transcribed yet).
- Listening state is always visible on screen; EMERGENCY MUTE is one tap and absolute.
- Debug logs never contain API keys (the app has none).
- Ambient memory will require explicit request or an opt-in policy (Milestone 7+).

## Known limitations

- Explicit Bluetooth SCO selection requires Android 12+ (`setCommunicationDevice`); on
  Android 10–11 only DEFAULT routing is available.
- Meta toolkit: no microphone/speaker API; camera not yet wired (Milestone 16); display N/A.
- No launcher icon.
- `:app` compilation is verified in CI; the authoring environment lacked the Android SDK.
- No release signing configuration.

## Roadmap

0. Project skeleton ✅  1. Phone audio loopback ✅  2. Bluetooth audio diagnostics ✅
3. Meta device access ✅ (1–3 pending device check)  4. Backend skeleton ✅
5. Memory database v1 ✅  6. Basic text AI ✅  7. Memory-aware text AI ✅  8. Push to talk ✅  9. ElevenLabs voice ✅  10. Glasses audio ✅ (device check pending)  11. Rolling transcription ✅ (device check pending)
12. Response decision engine  13. Active Operator  14. Feedback learning
15. BLE ring / remote  16. Camera context  17. Work integrations
