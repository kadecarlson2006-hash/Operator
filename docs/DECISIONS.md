# Architecture Decision Records — OPERATOR

Short records of decisions that would otherwise be re-litigated. Newest at the bottom.

---

## ADR-001: OpenRouter is the primary model gateway

**Status:** Accepted (design; implementation in Milestone 6)

Operator talks to models through an `AIProvider` interface. The first implementation is
OpenRouter, which gives one API and one bill across vendors, provider fallback, and easy model
swapping via configuration (`OPERATOR_FAST_MODEL_ID`, `OPERATOR_DEEP_MODEL_ID`, …). Direct
vendor providers may be added later where latency or features justify it. Model names are
never hard-coded.

## ADR-002: PostgreSQL + pgvector for memory

**Status:** Accepted (design; implementation in Milestone 5)

Memory is a core feature with relational structure (people, projects, commitments) *and*
semantic retrieval. One database that does both keeps operations simple. Normalised tables
first; embeddings in pgvector; no "everything in one JSON blob".

## ADR-003: Ambient raw audio is never stored permanently

**Status:** Accepted (in force from Milestone 1)

Audio lives in rolling in-memory buffers and is discarded automatically. The Milestone 1
loopback clip is a `ShortArray` in process memory and is dropped on DISCARD CLIP or process
death. Raw-audio logging is OFF by default and will remain a developer-only, explicit opt-in.

## ADR-004: Ray-Ban Meta integration is isolated behind `MetaGlassesManager`

**Status:** Accepted (design; implementation in Milestone 3)

Everything Meta-specific lives in one package behind one interface. The rest of Operator uses
standard Android audio routing, so the product still works with phone mic → glasses speakers
(or any Bluetooth headset) if third-party microphone access turns out to be unavailable.

## ADR-005: Provider secrets live in the backend, not the APK

**Status:** Accepted (in force from Milestone 0)

The Android app carries no OpenRouter/ElevenLabs/database credentials. `local.properties`
holds only non-secret defaults (mode, wit, model *IDs*, test durations) and feeds
`BuildConfig`. Secrets go in the backend `.env`. Both files are git-ignored with committed
`.example` templates.

## ADR-006: Toolchain — AGP 9.4.0, Gradle 9.6.0, Kotlin 2.4.10, Compose BOM 2026.08.00, compileSdk 37

**Status:** Accepted (Milestone 0, 2026-09)

- AGP 9.x provides built-in Kotlin: the `org.jetbrains.kotlin.android` plugin is *not*
  applied. Only `com.android.application` + `org.jetbrains.kotlin.plugin.compose`.
- Current AndroidX (core 1.19, lifecycle 2.11, Compose 1.12 via BOM 2026.08.00) refuses to
  compile against anything below compileSdk 37 (second CI run failed on exactly this), and
  AGP 9.4 is the release that supports API 37. AGP 9.4 requires Gradle ≥ 9.6.0.
- Kotlin 2.4.10's *tested* Gradle range ends at 9.5.0; it runs on 9.6 with at most a warning
  (Google's nowinandroid runs Kotlin 2.3 on Gradle 9.7). Re-check the Kotlin compatibility
  table on the next Kotlin bump.
- targetSdk stays at 36 for now: compileSdk only unlocks APIs, targetSdk opts into new
  runtime behaviour, which is a deliberate later step.
- Versions are centralised in `gradle/libs.versions.toml`. Plugins are placed on the root
  `buildscript` classpath (not per-module `plugins { alias(...) }`) so AGP, KGP, and the Compose
  compiler plugin share one class loader; the first CI run failed with
  `NoClassDefFoundError: com/android/build/gradle/api/BaseVariant` when they were split.
  `-Poperator.skipAndroid=true` omits AGP and `:app` for core-only builds.

## ADR-007: Two modules from day one: `:core` (pure JVM) and `:app` (Android)

**Status:** Accepted (Milestone 0)

Domain model, state manager, provider contracts, config parsing, latency timeline, decision
model, and the audio loopback state machine live in `:core` with no Android dependency. This
gives sub-second unit tests with fakes, keeps Android out of business logic, and lets the
core be compiled on machines without the Android SDK. `:app` implements the ports
(`AudioRecorder`, `AudioPlayer`) with `AudioRecord`/`AudioTrack` and hosts Compose UI.

## ADR-008: Manual dependency container, no DI framework (for now)

**Status:** Accepted (Milestone 0)

`OperatorContainer` wires the object graph by hand. Hilt/Koin would add build complexity
(annotation processing / KSP) for a graph of a dozen objects. Revisit when the graph or the
number of scopes grows; the container is the single place to swap.

## ADR-009: Silence is a first-class outcome

**Status:** Accepted (Milestone 0)

`ResponseCategory.NO_RESPONSE` exists from the first commit, `ResponseDecision.silence()` is
the canonical default, and `ResponseDecision.suppressed(reason)` lets local rules override a
model's `shouldSpeak=true`. `SilentDecisionEngine` is the only engine until Milestone 12.
The state manager also refuses COMMENT NOW while muted or OFF.

## ADR-010: Operator lives in its own private repository

**Status:** Superseded on 2026-09-05 (originally: subdirectory of an unrelated docs repo)

Milestones 0–3 were first developed under `operator/` inside the Syteline IDO documentation
repository. That mixed an unrelated product into a repo other people can access, so the
history was extracted with `git subtree split` into this dedicated private repository, with
the project at the root and CI in `.github/workflows/android.yml`.

## ADR-011: Bluetooth headset links use `setCommunicationDevice`, never `startBluetoothSco`

**Status:** Accepted (Milestone 2)

`AudioManager.startBluetoothSco()` is deprecated since API 34 in favour of
`setCommunicationDevice(AudioDeviceInfo)` (API 31), which also covers LE-audio headsets. Per the
platform reference only *sink* devices from `getAvailableCommunicationDevices()` can be
selected, the source is picked automatically, and the request must be cleared when done.
`CommunicationLink` wraps exactly that: set `MODE_IN_COMMUNICATION`, select the sink, wait up
to 4 s for `getCommunicationDevice()` to confirm, run the capture/playback, then clear and
restore the mode. Capture uses `VOICE_COMMUNICATION`, playback `USAGE_VOICE_COMMUNICATION`,
because SCO is a voice link. A2DP output stays `USAGE_MEDIA` with `setPreferredDevice`.
Consequence: explicit SCO selection is unsupported on API 29–30; DEFAULT routing still works.

## ADR-012: Routing truth comes from `routedDevice`, requests are only requests

**Status:** Accepted (Milestone 2)

`setPreferredDevice` and `setCommunicationDevice` are requests the platform may refuse or
override. The UI therefore shows both the *selected* route and the *actual* route read back
from `AudioRecord.getRoutedDevice()` / `AudioTrack.getRoutedDevice()`, and every capture and
playback logs its real route to the in-memory `RouteEventLog` and logcat. Milestone 10's
glasses questions ("can the app use the Ray-Ban mic?") will be answered from this evidence, not
from what was requested.

## ADR-013: The Meta toolkit is an optional module resolved from GitHub Packages

**Status:** Accepted (Milestone 3)

`com.meta.wearable:mwdat-*` is published only on GitHub Packages, which rejects anonymous
downloads. Rather than make every build depend on a personal token, `settings.gradle.kts`
includes `:glasses-meta` and the Meta repository only when a token is present (or
`-Poperator.metaSdk=true`), and `:app` reaches the provider through a factory class looked up
by name. Builds without the token get `NoGlassesProvider` and say so on screen. CI supplies its
`GITHUB_TOKEN` and forces the module on so the integration is always compiled.

## ADR-014: minSdk 31 (Android 12)

**Status:** Accepted (Milestone 3)

Meta's samples build with `minSdk 31`, and Operator's Bluetooth path already needs
`setCommunicationDevice` (API 31). Supporting Android 10–11 would mean two audio code paths
for phones the product never targets.

## ADR-015: Opt out of Meta analytics and crash reporting

**Status:** Accepted (Milestone 3)

The SDK collects usage analytics and SDK crash reports by default. Operator sets
`ANALYTICS_OPT_OUT=true` and `CRASH_REPORTING_OPT_OUT=true` in the manifest. Operator hears
other people; the less telemetry leaves the phone, the better. Revisit only if Meta support
needs crash data for a specific bug.

## ADR-016: Glasses audio never goes through the vendor SDK

**Status:** Accepted (Milestone 3) — documents a finding, not a preference

DAT 0.9.0 has no microphone or speaker API (docs/META_GLASSES.md). `GlassesProvider` therefore
has no audio methods at all; audio stays in the audio subsystem over standard Bluetooth. If a
later SDK adds audio, it will be a new capability behind the same interface, not a rewrite.

## ADR-017: Backend is a Kotlin/Ktor modular monolith sharing `:core`

**Status:** Accepted (Milestone 4)

Options weighed, per the brief:

| Option | For | Against |
|---|---|---|
| **Kotlin / Ktor** (chosen) | Shares `:core` (provider contracts, `ResponseDecision`, config keys) with the phone in one Gradle build; one language; coroutines/Flow end to end; native WebSockets and streaming; plain HTTP is all OpenRouter/ElevenLabs need; PostgreSQL via JDBC, pgvector via SQL; fat-jar/Docker deploy; builds and tests on a plain JDK (this repo's CI and the authoring sandbox). | Smaller AI-library ecosystem than Python; pgvector has no Kotlin client, so vectors are SQL text. |
| Python / FastAPI | Richest AI SDK ecosystem; pgvector helpers; quick prototyping. | Second language; duplicated domain model that drifts from the Kotlin app; two build systems. |
| TypeScript / Node | Good streaming and SDK coverage. | Second language; weaker typing for the memory schema; same duplication cost. |

Structure: one deployable, packages by concern (`config`, `db`, `health`, `providers`, later
`ai`, `memory`, `tts`, `transcription`). No microservices.

## ADR-018: `/health` tells the truth and degrades instead of crashing

**Status:** Accepted (Milestone 4)

A configured-but-unreachable database does not stop the process: startup logs the error and
`/health` answers **503** with the full report (database error, provider slots, redacted
config). A reachable database answers **200** with the pgvector version and migrations applied.
Secrets are masked to their last four characters and never returned; the JDBC URL is shown
without credentials. Load balancers get a real signal, humans get a diagnosis.

## ADR-019: One generalized `memories` table with a typed enum and real foreign keys

**Status:** Accepted (Milestone 5)

The brief lists many candidate tables (personal_facts, work_facts, preferences, goals,
decisions, commitments, …). Retrieval (Milestone 7) needs *one* semantic search across all of
them, and a table per type would mean a union view plus an embedding table per type. So every
memory lives in `memories` with a `memory_type` check constraint (the 12 types from the
brief), the generalized fields the brief specifies (source, importance, confidence, expiry,
privacy scope, last used, active flag), real foreign keys to normalized `people`,
`organizations`, and `projects`, and a small `metadata jsonb` for type-specific extras. That is
normalization where relationships exist and a single search surface where retrieval needs it.
`memory_embeddings` is a separate one-to-one table so a memory can exist before its embedding
and be re-embedded when the model changes; the `vector` column is dimension-agnostic until the
embedding model is chosen (an HNSW index is a later migration). `memory_events` is append-only
and not FK-bound so provenance survives hard deletes.

## ADR-020: Plain JDBC, no ORM

**Status:** Accepted (Milestone 5)

The store is a handful of parameterized statements; pgvector needs `?::vector` casts and
`<=>` ordering that ORMs get in the way of. `PostgresMemoryStore` wraps each call in one
transaction, and `InMemoryMemoryStore` implements identical semantics so unit tests stay fast
and the app can run without a database. Revisit if the query surface grows past what reads
comfortably as SQL.

## ADR-021: Single default user until authentication exists

**Status:** Accepted (Milestone 5) — temporary

Every table is already keyed by `user_id`; the API pins it to a seeded default user. Adding
authentication later means resolving the user from the request instead of a constant, not a
schema change.

## ADR-022: Tier selection is code, model identity is configuration

**Status:** Accepted (Milestone 6)

`ModelRouter` decides FAST, DEEP, or VISION and then resolves that tier to a model ID from
configuration. No model name appears in Kotlin. An explicit tier from the caller always wins, an
image forces VISION, a small set of analysis cues or a very long prompt promotes to DEEP, and
everything else is FAST. The heuristic is deliberately shallow and unit-tested: the decision is
what needs to be reviewable, and the model behind it must stay swappable per the brief. Tiers
that are unset fall back to the fast model, and DEEP or VISION also pass the fast model to
OpenRouter as its `models` fallback list.

## ADR-023: System prompts are versioned files, loaded at runtime

**Status:** Accepted (Milestone 6)

`operator-prompts/system/<version>.txt` holds the personality prompt; `PromptLibrary` loads it by
version and caches it, and the version used is returned on every answer and recorded per session.
Nothing about Operator's character lives in Kotlin string literals, so the prompt can be revised
and A/B compared without a rebuild. A missing version is reported and the call proceeds without a
system prompt rather than silently substituting an invented one.

## ADR-024: Usage accounting is in-process and bounded, not billing

**Status:** Accepted (Milestone 6) — revisit at Milestone 13

`UsageTracker` keeps a bounded ring of recent calls plus running totals, and `GET /usage` derives
daily, monthly, per-model, and all-time views from it. It deliberately does not write to the
database: usage recording sits on the latency path of every spoken interaction, and the brief
puts latency above cost reporting. Costs appear only when the provider reports them, never
estimated. Persisting usage and enforcing budgets is a later step, once the shape has settled.

## ADR-025: Retrieval is hybrid, ranked, and floored

**Status:** Accepted (Milestone 7)

Memory retrieval gathers candidates from three cheap sources and then ranks them: semantic
neighbours from pgvector, lexical matches on the significant words of the query, and anything
linked to a person or project the query named. Ranking blends similarity, importance, confidence,
recency of use, and entity linkage.

Two properties matter more than the exact weights. Retrieval degrades instead of failing: with no
embedding model, or when the embedding call fails, lexical and structured matching still answer,
which is why memories written before an embedding model existed remain findable. And a relevance
floor drops weak candidates entirely, because nearest-neighbour search always returns *something*:
without a distance ceiling an unrelated question pulls in the closest memory, which is precisely
how a model ends up inventing a callback. Every returned memory carries the reason it was chosen,
so the selection can be audited rather than trusted.

Marking retrieved memories as used is a database write, so it happens off the answer's latency
path in a separate scope.

## ADR-026: Memory scope is decided by mode, and by whether the user asked

**Status:** Accepted (Milestone 7)

Only WORK mode reads WORK-scoped memories, so work facts cannot surface in a social setting.
PRIVATE memories answer a direct question but are never volunteered from ambient conversation.
RESTRICTED is never retrieved automatically at all and waits for an explicit unlock that does not
exist yet. OFF reads nothing. Writing is stricter than reading: nothing is ever written into
RESTRICTED, and an explicit memory spoken in WORK mode is stored as WORK.

## ADR-027: Explicit memory commands are matched deterministically, not by a model

**Status:** Accepted (Milestone 7)

"Remember that X" is recognised by string matching before any model call. The brief requires
explicit user commands to be high confidence, and a phrase the user deliberately spoke should not
be lost to a model's judgement or cost a round trip. Storing a memory therefore takes no model
call at all. Type inference is a short list of cues; a wrong guess is visible and correctable in
the memory screen, whereas a model call would add latency and a second way to be wrong. Saying the
same thing twice reaffirms the existing memory rather than failing or duplicating it.

## ADR-028: Memories are labelled as memory in the prompt

**Status:** Accepted (Milestone 7)

The assembled context marks retrieved memories as things the user said or stored earlier, and
explicitly not as live data, because the brief forbids presenting one as the other. When nothing
relevant was found the block says so and tells the model not to invent anything, rather than
leaving an empty section the model might fill in. Empty sections are omitted entirely.

## ADR-029: Voice activity detection runs on the phone, transcription runs on the backend

**Status:** Accepted (Milestone 8)

The split follows the privacy rule rather than convenience. Detection is cheap, needs no
credential, and its whole purpose is to decide what must *not* be uploaded, so it belongs on the
device where the microphone is; the brief's "do not permanently store raw ambient audio, use
rolling in-memory buffers" is enforceable only there. Transcription needs a provider key, and the
brief forbids putting long-term keys in the Android app, so it belongs on the backend. The
consequence is deliberate: the backend never sees audio the detector rejected, and the phone never
holds a speech credential.

The detector calibrates to the room before it may open the gate. Without that, starting up inside
a noisy room latches the gate open on the noise itself and the floor can never adapt, because it
only adapts while the gate is shut — Operator would stream a café to the backend continuously.

## ADR-030: The transcription endpoint's base URL is configuration, not a constant

**Status:** Accepted (Milestone 8)

`OpenAiCompatibleTranscriptionProvider` speaks the `/audio/transcriptions` shape, which is
implemented by several vendors and by self-hosted Whisper servers. Hard-coding one host would make
the speech vendor a code change and would contradict the project rule that model and provider
identity is configuration. The class is named for the wire format it speaks, not for a company.

## ADR-031: Hearing and speaking are separate subsystems

**Status:** Accepted (Milestone 8)

`Subsystem.VOICE` previously covered both. They fail independently, are configured independently,
and land in different milestones, so a single status line could not tell the truth about either.
`HEARING` (Milestone 8) now reports the transcription path and `VOICE` (Milestone 9) the speech
path.

## ADR-032: /transcribe takes raw PCM, and does not chain into the model

**Status:** Accepted (Milestone 8)

Raw little-endian PCM-16 rather than JSON, because base64 would add a third to every upload on the
latency path and the phone already holds the samples in that layout. The route returns a
transcript and stops there: turning speech into an answer needs the decision engine and rolling
context from later milestones, and wiring it early would make two subsystems untestable at once.
An empty transcript is returned as an empty result rather than an error — the gate can open on a
door slam, and silence is a first-class outcome.

## ADR-033: The rolling transcript lives on the phone, not the backend

**Status:** Accepted (Milestone 11)

The window is held in memory on the device and sent with a question, rather than accumulated
server-side. A backend that kept the conversation would be a permanent record of everything heard
in a room, which the brief forbids; a phone-side buffer bounded by age and count is the rolling
buffer it allows. The backend reads the window into one prompt and drops it.

The size limit is nonetheless enforced on the backend as well. The phone bounds its own window,
but the server must not depend on a well-behaved client to keep prompts — or bills — finite.

## ADR-034: Listening continues in a foreground service, with a visible notification

**Status:** Accepted (Milestone 11)

Continuous capture is only possible from a foreground service, and from API 34 it must declare
the `microphone` type. The service is started from a user action while the app is visible, which
is the path Android 14+ still permits; it is never started from the background.

The ongoing notification is treated as a feature, not a platform tax. An assistant that keeps the
microphone open with no visible sign of it is what the privacy rules exist to prevent, so the
notification states plainly that Operator is listening and carries a STOP action that works
without opening the app. It is `VISIBILITY_PUBLIC` because it deliberately carries no transcript
text: the user should be able to see the microphone is open from a lock screen without any of
what was said appearing there. Milestone 8's behaviour of stopping when the screen goes away is
therefore replaced — deliberately, and only because the notification makes it visible instead.

## ADR-035: Speakers are not identified

**Status:** Accepted (Milestone 11)

Every captured line is attributed to `UNKNOWN`, rendered "Someone". The transcription provider
returns text, not diarisation, so labelling lines with a name would be inventing a capability the
system does not have — and the brief forbids both that and building profiles of the people around
the user. Only Operator's own replies are attributed, because those it does know it produced.
