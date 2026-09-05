# Risks and Unknowns — OPERATOR

Status values: **UNKNOWN TO VERIFY**, **VERIFIED**, **PARTIALLY VERIFIED**, **BLOCKED**.
Update this file whenever a test answers a question.

| # | Item | Status | Notes / next test |
|---|------|--------|-------------------|
| 1 | Continuous Ray-Ban Meta Gen 2 microphone access for third-party apps | PARTIALLY VERIFIED | **Via the Meta toolkit: UNSUPPORTED** (0.9.0 has no audio API; docs/META_GLASSES.md). Via Bluetooth HFP/SCO as a plain headset: UNKNOWN until Milestone 10. Fallback phone mic → glasses speakers stands. |
| 2 | Meta AI coexistence (does Meta AI grab the mic/speaker or gestures?) | UNKNOWN TO VERIFY | SDK docs: a session is paused when another experience takes over the device. Captouch gestures act on the SDK stream, not on apps. Milestone 10 real-device test. |
| 3 | Bluetooth audio focus and route flapping between SCO/A2DP/LE Audio | UNKNOWN TO VERIFY | Milestone 2 diagnostics log every actual `routedDevice` and communication-device change; run the CURRENT_STATUS.md Milestone 2 checks with a headset, then Milestone 10 with glasses. |
| 4 | Glasses battery impact of continuous audio | UNKNOWN TO VERIFY | Measure over 1 h sessions in Milestone 10. |
| 5 | Android background service restrictions (foreground service type `microphone`, Doze) | UNKNOWN TO VERIFY | Milestone 11. Continuous capture needs a foreground service with `FOREGROUND_SERVICE_MICROPHONE`; Android 14+ restricts starting mic FGS from background. |
| 6 | Ambient transcription cost | UNKNOWN TO VERIFY | Local VAD before cloud; UsageTracker in Milestone 8+. |
| 7 | OpenRouter latency (first token) | UNKNOWN TO VERIFY | Measurement now exists on both sides: the backend reports model latency and the phone reports round trip. Needs a real key and one live call to produce numbers. |
| 8 | ElevenLabs latency (first audio) and streaming support | UNKNOWN TO VERIFY | Milestone 9. Check current official docs for streaming/websocket endpoints before implementing. |
| 9 | Voice interruption behaviour (barge-in while Operator speaks) | UNKNOWN TO VERIFY | Milestone 9/11. |
| 10 | BLE ring compatibility (HID vs custom GATT) | UNKNOWN TO VERIFY | Milestone 15. Design around generic Android HID first. |
| 11 | Android microphone capture/playback on the phone itself | PARTIALLY VERIFIED | Milestone 1 code compiles and unit-tests in CI but has not yet been run on a device — see CURRENT_STATUS.md "next test". |
| 12 | Build toolchain compatibility (AGP 9.4 + Kotlin 2.4.10 + Gradle 9.6.0) | VERIFIED | `:core` builds/tests locally and in CI; `:app` assembles and unit-tests in CI (run #3 green). |
| 13 | `AudioRecord.routedDevice` / `AudioTrack.routedDevice` reliability on Samsung | UNKNOWN TO VERIFY | Recorder/player poll after every buffer and log "routedDevice was never reported" if it stays null; look for that line in the route log. |
| 14 | 16 kHz mono capture support on every input route | UNKNOWN TO VERIFY | Bluetooth SCO is 8/16 kHz; LE Audio may differ. The Bluetooth diagnostics panel now shows `sampleRates`/`channelCounts`/`encodings` per device (empty list = arbitrary rates). |
| 15 | Meta developer documentation host (`wearables.developer.meta.com`) reachable from the dev environment | PARTIALLY VERIFIED | Host still blocked in the sandbox; the official SDK repository (README, CHANGELOG, AGENTS.md, skills, samples) was used instead. Cross-check the hosted docs from a workstation. |
| 16 | `setCommunicationDevice` bring-up time and whether Samsung confirms the device via `getCommunicationDevice()` | UNKNOWN TO VERIFY | `CommunicationLink` waits up to 4 s and logs "NOT confirmed" otherwise; capture proceeds either way so the route log shows what really happened. |
| 17 | Bluetooth device names in `AudioDeviceInfo.productName` / `getAddress()` without BLUETOOTH_CONNECT | UNKNOWN TO VERIFY | The reference lists no permission for either; if names come back blank, compare against the paired list (which does need BLUETOOTH_CONNECT). |
| 18 | Ray-Ban Meta appear as classic HFP/A2DP or LE Audio (`TYPE_BLE_HEADSET`) | UNKNOWN TO VERIFY | Determines which link path Milestone 10 uses; read it off the device table. |
| 19 | GitHub Actions `GITHUB_TOKEN` can download public GitHub Packages from another repository | VERIFIED | CI run #11 resolved `com.meta.wearable:mwdat-*:0.9.0` with the workflow token (`permissions: packages: read`). A `MWDAT_GITHUB_TOKEN` secret is still honoured if ever needed. |
| 20 | Meta SDK 0.9.0 AAR compatibility with AGP 9.4 / Kotlin 2.4 / compileSdk 37 | VERIFIED (compile) | `:glasses-meta` compiles and packages in CI run #11 with no warnings; runtime behaviour still needs the phone. |
| 21 | Registration return deep link: does the URL scheme need to be declared anywhere besides the manifest? | UNKNOWN TO VERIFY | Samples only declare the intent filter; verify the round trip on the phone. |
| 22 | Device type string reported for Ray-Ban Meta Gen 2 (classic vs `META_GLASSES`) and Bluetooth profile (HFP vs LE Audio) | UNKNOWN TO VERIFY | Read off the Glasses panel and the Bluetooth device table. |
| 23 | Flyway 13 + pgvector migration on a fresh database | VERIFIED (CI) | The CI integration job runs the backend against `pgvector/pgvector:0.8.6-pg17` and `/health` reports the extension version; a workstation `docker compose up` should behave the same. |
| 24 | `/health` exposing anything sensitive | MITIGATED | Redacted config only (last four characters of keys, JDBC URL without credentials); unit test asserts raw secrets never appear in the body. |
| 25 | pgvector column is dimension-agnostic, so no ANN index yet; similarity search is a sequential scan | ACCEPTED (v1) | Fine for personal-scale memory counts; add `vector(N)` + HNSW migration once the embedding model (Milestone 7) fixes N. |
| 26 | Text search is ILIKE only | ACCEPTED (v1) | GIN tsvector index already exists; Milestone 7 combines lexical + semantic + structured filters. |
| 27 | OpenRouter wire format was verified against their published SDK, not the hosted reference | UNKNOWN TO VERIFY | `openrouter.ai` is blocked by the build sandbox's egress proxy, so the request/response/error shapes in `OpenRouterApi.kt` come from `@openrouter/ai-sdk-provider` 3.0.0 (OpenRouter's own package). Cross-check against the hosted docs from a workstation, and confirm with one live call. |
| 28 | No live model call has been made yet | UNKNOWN TO VERIFY | Everything is exercised with a mock HTTP engine. The first real call needs `OPENROUTER_API_KEY` and `OPERATOR_FAST_MODEL_ID` in the backend `.env`. |
| 29 | The Android module has not compiled since the Ktor client was added | VERIFIED | CI run #6: app unit tests and `assembleDebug` both green with the Ktor/OkHttp client in place. |
| 30 | Semantic retrieval adds an embedding round trip to every question | UNKNOWN TO VERIFY | Retrieval embeds the query before searching, so the answer path now carries two provider calls. Measure with a real key; if it hurts, cache query embeddings or retrieve lexically first and embed only on a miss. |
| 31 | Retrieval thresholds are guesses until real embeddings exist | UNKNOWN TO VERIFY | The distance ceiling and score floor in `MemoryRetrievalEngine` were tuned against a deterministic fake. Re-tune against a real embedding model and real questions before relying on ambient retrieval in Milestone 13. |
| 32 | Memories written before an embedding model is configured have no vector | ACCEPTED | They stay findable lexically and by entity link. A backfill endpoint that embeds existing memories would close the gap; it is a good delegated task. |
