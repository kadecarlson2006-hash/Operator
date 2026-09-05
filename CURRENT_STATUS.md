# CURRENT STATUS — OPERATOR

**Current milestone:** 6 — Basic text AI (implemented; backend verified locally, Android and CI not yet verified)

**Branch note:** this work sits on `claude/milestone-6-text-ai`, which is stacked on
`claude/milestone-5-memory`. Neither is merged: `main` currently ends at Milestone 4.

**Last updated:** 2026-09-05

## What works (verified)

- Milestone 6 backend: OpenRouter provider (wire format verified against OpenRouter's own
  published SDK, see risk 27), `ModelRouter` for the FAST/DEEP/VISION tiers, `PromptLibrary`
  loading versioned personality prompts, `POST /ai/respond`, `GET /usage`, and an in-process
  `UsageTracker`. 45 backend unit tests pass locally, 23 of them new, including the provider
  against a mock HTTP engine (success, error envelope on both 4xx and 200, unknown fields,
  transport failure) and the route (routing, failure mapping, usage counting, no prompt echo).

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

## What is implemented but NOT verified anywhere yet

- Milestone 6 Android: the "Ask Operator" panel (question box, SEND, answer, model, tier, prompt
  version, round-trip and model latency, token counts) and the backend client behind an
  `OperatorBackend` interface. The module has not compiled since the Ktor client dependency was
  added, because the authoring sandbox has no Android SDK. CI has not run on this branch.
- Milestone 6 end to end: no live model call has been made. It needs `OPENROUTER_API_KEY` and
  `OPERATOR_FAST_MODEL_ID` in the backend `.env`.

## What is implemented but NOT yet verified on a device

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

- No TTS or transcription yet (Milestones 8/9); those provider slots report "not configured".
- No embedding generation or semantic retrieval yet (Milestone 7): memories are stored and
  searchable, but nothing retrieves them into a prompt.
- No authentication (single default user, ADR-021).
- Camera streaming/photo (Milestone 16), AI, TTS, transcription, rolling context, decision
  engine, BLE ring, integrations.
- No launcher icon.

## Current blockers

- Human verification on hardware. Milestone 3 additionally needs: the Meta AI app with
  Developer Mode enabled, the glasses paired to it, and a GitHub token for the build.

## Next test (device checks still owed for Milestones 1–3; Milestone 6 can proceed in parallel)

Milestone 1 (phone audio): launch → GRANT MICROPHONE → RECORD TEST → PLAY TEST → speech
understandable → actual devices correct.

Milestone 2 (Bluetooth): GRANT BLUETOOTH → paired list shows headset/glasses → OUTPUT=A2DP
PLAY TEST → INPUT=SCO RECORD TEST with route-log "Communication device active" → OUTPUT=SCO
PLAY TEST → disconnect falls back to DEFAULT → note SCO bring-up time.

Milestone 3 (Meta SDK): follow the device test plan in `docs/META_GLASSES.md` (register,
device list, session start/stop, camera permission, mock kit) and record the glasses' reported
device type string.
