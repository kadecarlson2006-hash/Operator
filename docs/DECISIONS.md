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

**Status:** Accepted (Milestone 6) — revisited at Milestone 13 (see ADR-042)

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

## ADR-036: ElevenLabs audio streams through the backend as raw 24 kHz PCM

**Status:** Accepted (Milestone 9)

The Android app never receives the ElevenLabs key. The backend calls the official HTTP streaming
endpoint with configured voice and model IDs and relays signed 16-bit little-endian mono PCM.
HTTP streaming fits Milestone 9 because the complete model answer exists before TTS starts; the
bidirectional WebSocket endpoint becomes useful only if later work speaks partial LLM tokens. Raw
`pcm_24000` lets `AudioTrack` play the first received chunk without waiting for an MP3 decoder or
a complete file. Cancellation closes the phone request, backend stream, provider channel, and
`AudioTrack`. No audio is written to disk.

## ADR-037: Glasses audio is half-duplex at the application boundary

**Status:** Accepted (Milestone 10)

The Ray-Ban microphone and speaker share Android's Bluetooth communication routing. Operator
therefore pauses active listening before speech playback and resumes it only after playback ends.
This prevents Operator from transcribing its own voice and avoids concurrent calls competing for
`AudioManager.setCommunicationDevice`. An explicit listening stop or emergency mute cancels the
pending resume. The policy sits above the independent hearing and speaking controllers so those
subsystems remain separately testable and usable with the phone's built-in audio routes.

## ADR-038: The decision engine is local rules, then a model, then the local rules again

**Status:** Accepted (Milestone 12)

The ordering carries the guarantee. `ConversationPolicy` runs first and can only refuse, so a
moment in a mode that does not volunteer, too soon after the last comment, or over the
five-minute cap never becomes a paid model call. It runs again afterwards, because a model
returning `shouldSpeak = true` is a suggestion and not an instruction (ADR-009): the same rules
apply confidence and relevance floors, the three-sentence limit, and a repetition check.

An engine that could be talked into speaking by a model would be hoping for silence rather than
enforcing it. This one cannot: the model chooses only what would be worth saying, never whether
saying anything is allowed.

## ADR-039: Every decision failure is silence

**Status:** Accepted (Milestone 12)

An unreachable model, an unparseable reply, JSON wrapped in prose, scores out of range, or a
reply that says to speak but carries no text all produce silence rather than an error. This needs
no special handling because silence is the expected outcome anyway, and the alternative — an
assistant that speaks when its judgement failed — is exactly the behaviour the brief forbids.

`POST /decide` therefore returns silence as an ordinary 200. Silence is a decision, not a fault.

## ADR-040: The decision model is a job, not a tier

**Status:** Accepted (Milestone 12)

`OPERATOR_DECISION_MODEL_ID` is read directly rather than added to `ModelTier`. FAST, DEEP and
VISION are tiers of the same answering path, chosen per request by the router (ADR-022); deciding
whether to speak is a different job that happens before answering exists. Adding it as a tier
would also let a caller ask `/ai/respond` for it, which is meaningless. It falls back to the fast
model when unset.

## ADR-041: The decision stage does not speak

**Status:** Accepted (Milestone 12)

`POST /decide` returns a judgement and stops. Turning it into audio is the caller's job, so the
judgement stays testable on its own and the phone keeps control of delivery — including the
half-duplex rules from Milestone 10, which can still refuse a decision that was made while
Operator was already speaking. A decision to speak is consequently not the same as having
spoken, and the app records only what actually came out.

## ADR-042: A budget for asking, separate from the limits on speaking

**Status:** Accepted (Milestone 13)

Every limit in `ConversationPolicy` was keyed on when Operator last *spoke*. Silence is the common
case by design, so while Operator says nothing those limits bound nothing: under Milestone 12 the
user's finger was the only thing preventing unbounded spend, and Milestone 13 removes the finger.

`minDecisionIntervalSeconds` and `maxDecisionsPer5Minutes` are the only limits that hold when
Operator stays quiet. They are enforced on the backend rather than the phone, for the same reason
the transcript cap is (ADR-033): a client bug or a chatty room must not be able to spend without
limit. An invited decision is never refused by the budget — the user asked — but is counted
against it, because it costs the same. The call is recorded before it is made: a failed call still
cost a round trip, and a provider that is timing out is when an unbudgeted retry loop hurts most.

This is the revisit that ADR-024 anticipated for Milestone 13.

## ADR-043: Active Operator is off by default and does not persist itself

**Status:** Accepted (Milestone 13)

Deciding on its own is a deliberate act each time. The confidence and relevance floors it depends
on were chosen to be testable rather than measured, and no model has yet judged a real
conversation (risks 43-44); switching it on before those numbers have been checked against a real
room is precisely how an assistant becomes the nuisance the policy exists to prevent.

It is also not persisted. A setting that quietly survives a restart is one that ends up switched
on in a room where nobody expected it, which is the wrong failure for something holding a
microphone. Emergency mute turns it off rather than merely stopping speech.

## ADR-044: Ambient decisions wait for a lull

**Status:** Accepted (Milestone 13)

The decider debounces on the rolling transcript rather than firing per line. Someone mid-sentence
has not finished the thought, and interrupting a thought is worse than being slow to it; waiting
also means the model sees a complete exchange rather than half of one, and one decision covers a
whole exchange instead of one per utterance. Operator's own last line is never a trigger, because
replying to itself is how a loop starts.

The lull is an optimisation and a courtesy, not a spending control. The backend's budget is what
actually bounds cost, since the phone cannot be trusted to.

## ADR-045: Feedback can only make Operator quieter

**Status:** Accepted (Milestone 14)

Recent complaints raise the confidence and relevance floors for uninvited comments. Approval does
not lower them. It only stops the penalty accruing, and the floors never fall below what the
configuration already allows.

The asymmetry is the decision, and it is deliberate rather than cautious. ADR-038 gives
`ConversationPolicy` the property that it may refuse but never permit, and feedback that could
loosen a floor would take that away: "silence is the default" would hold only until somebody
tapped approve a few times. The costs are not symmetric either. A remark that was wanted and never
came is a small loss the user will rarely notice. A remark that was not wanted is the one that
makes people switch the thing off — and that failure is what most of the anti-annoyance machinery
in Milestones 12 and 13 exists to prevent.

Complaints decay linearly to nothing over an hour. Feedback older than that describes a
conversation that has moved on, and a penalty that never expired would eventually silence Operator
permanently on the strength of one bad afternoon. The decay is linear rather than exponential
because this number has to be explainable to the person it is silencing.

`WRONG` is excluded from the penalty. Being incorrect is a content fault, and making Operator speak
less often does not make it more accurate; that verdict reaches the model through the prompt
instead. `TOO_LATE` is included, because a comment that arrived after the moment passed should not
have been made — and it points at the settle delay and provider latency (risks 47 and 7) rather
than at the decision itself, which is why it is a separate verdict at all.

The penalty is a nudge, not a mute. A comment Operator is confident about still gets through a
complaint. Feedback that could silence outright would be an off switch with extra steps, and
Operator already has an off switch.

## ADR-046: Feedback stores what Operator said, not the conversation around it

**Status:** Accepted (Milestone 14)

A stored verdict keeps Operator's own line, the verdict, the trigger, and the confidence and
relevance the model claimed at the time. It does not keep the transcript that prompted the comment.

The brief forbids silently building detailed permanent records of the people around the user, and
the surrounding talk is exactly that: other people's words, kept indefinitely, without their
knowledge. Everything else in the system treats conversation as a rolling in-memory window that
ages out. Feedback is the one place where something is written down and kept, so it is the one
place where that boundary could quietly be crossed.

Nothing is lost by omitting it. The signal the milestone needs is "you said this, and it was
unwanted" — the shape of an unwelcome remark, and the scores that let it through. What everyone
else was saying at the time does not improve that, and the scores are the part that finally makes
the floors measurable: until now the confidence and relevance thresholds have been guesses nothing
could test, because they only judge a model that wants to speak (risk 44). The mean scores of the
comments a user actually rejected are the first evidence of what those floors should have been.

## ADR-047: The remote is a set of gestures, not a device driver

**Status:** Accepted (Milestone 15)

Milestone 15 was written down as "BLE ring / remote". No ring exists to test against, and risk 10
has said since the start to design around generic Android HID first. So what is built is a mapping
from button presses to Operator actions, driven by whatever key codes the platform delivers, with
no knowledge of any particular product.

The classification lives in `:core` as a pure function of press timings, which matters more here
than usual: with no hardware, unit tests are the only thing exercising any of it. A driver written
against a device nobody has would be untested code pretending to be a feature.

The gestures:

- **Hold** is push-to-talk. Holding to speak is the gesture people already know from every
  walkie-talkie, and a hold cannot be triggered by brushing the button.
- **Single tap** asks for a comment, or stops Operator if it is mid-sentence. Interrupting is what
  a tap should do while something is talking at you, and which of the two it means is read when the
  tap resolves rather than when it lands, because that is the moment it takes effect.
- **Double tap** toggles mute. Two taps is hard to do by accident and quick to do in a hurry.

Mute is deliberately *not* the long press, even though it is the most serious action: a long press
is indistinguishable from a button snagged on a sleeve. It toggles rather than latches, because a
remote you cannot un-mute from is worse than one you cannot mute from.

A single tap cannot be classified when it happens, since it may be the first half of a double, so
it waits out the double-press window. Every tap therefore costs that delay. Acting immediately and
then undoing it would be worse on a device that speaks out loud: Operator would start a sentence
and then stop, which is precisely the failure the anti-annoyance work exists to prevent.

Key events arrive through the activity, so this is foreground only. Capturing media buttons
system-wide needs a MediaSession that survives the screen going off, which is worth building once
there is hardware to point at it (risk 54).

## ADR-048: Operator only looks when asked

**Status:** Accepted (Milestone 16)

There is exactly one trigger for the camera: the user asking. `LookTrigger` has one value, and
`CameraContextPolicy` refuses anything else by name.

The microphone works differently, and the difference is the whole argument. Continuous listening is
safe to build because there is a cheap local test — voice activity — for whether a moment is worth
sending, so silence never leaves the device. A camera has no equivalent. There is no local check
for "this frame is worth uploading", so an always-on camera means uploading everything or guessing,
and both are the surveillance device the brief is explicit about not building, pointed at people
who never agreed to it.

The absence of an ambient path is therefore not a gap to be filled in a later milestone. Automatic
looking is the thing that turns a pair of glasses into something nobody around the wearer consented
to, and a person cannot tell by looking whether the camera is running.

The policy also refuses on mute, on OFF, without the camera permission, without the glasses, and
on an interval and a five-minute budget — the same shape as `ConversationPolicy`, and for the same
reason: it can only refuse, and it refuses before anything is captured or sent.

## ADR-049: A look produces text, and the image is dropped

**Status:** Accepted (Milestone 16)

The image is held in memory, sent, and discarded. It is never written to disk, never stored, and
never attached to a memory. Only the description survives, and the log records the failure, never
the description.

This is the audio rule — rolling in-memory buffers, nothing permanent — applied to the sense that
would be worse to get wrong. A stored photograph of a room is a record of everyone in it.

Because the description is the only artefact that survives, the constraints live on the
description rather than on the capture. A model asked to describe a scene will identify people,
guess their jobs, read their badges aloud and speculate about their moods, and every one of those
is a detailed permanent record of a stranger. `VisionConstraints` forbids identification,
appearance, and reading personal information out of the scene, and those rules are sent with every
request rather than configured once somewhere — a prompt that can be edited without them is a
prompt that will eventually be edited without them.

The vision wire format uses its own message types rather than widening `ChatMessage.content` to be
either a string or an array of parts. The text format is verified against live calls and works
(risk 27); putting the one proven path at risk for a feature that has never run would be a poor
trade, and two types cost only a little duplication.

## ADR-050: Modes carry their own restraint

**Status:** Accepted (2026-09-09)

Each mode now sets its own adjustment to the confidence and relevance floors, and its own factor on
the comment interval, the decision interval and the five-minute caps. STANDBY applies none of them
and is the baseline.

Before this, every mode that allowed volunteering behaved identically. ACTIVE, WORK, SOCIAL and
CHAOS differed only in prompt personality; all the frequency lived in fixed thresholds. The mode
selector looked like it controlled how much Operator talked, and it did not — which is a worse
kind of wrong than a missing feature, because the control appears to work.

The meanings also move. STANDBY becomes "follows the conversation and may occasionally offer
something", which is what ACTIVE used to be, and ACTIVE becomes actively participating: floors
lowered, intervals shortened. That matches what the words suggest — standing by is being ready and
mostly quiet; being active is taking part — and the previous naming had them the wrong way round
for anyone reading the labels rather than the source.

Two properties are preserved deliberately. No mode may drop a floor below 0.2, because a comment
the model itself is barely confident in is not worth hearing however forward the posture. And
feedback may still only *raise* the bar (ADR-045), so a complaint tightens even ACTIVE — the most
talkative mode is not exempt from being told to be quiet.

QUIET and OFF remain the modes that never volunteer. An unspecified mode on the backend routes now
defaults to STANDBY rather than ACTIVE, since a caller that did not choose should not get the most
forward behaviour.

The cost is real and belongs in the record: ACTIVE shortens the decision interval fivefold and
quadruples the budget, and asking is what is billed (risk 40). It is the right mode alone in a car
and the wrong one in a room full of people.

## ADR-051: Being named is being asked

**Status:** Accepted (2026-09-09)

A transcript line that opens by addressing Operator by name is sent as `DIRECT_ADDRESS` rather
than `AMBIENT`.

Until this, `DIRECT_ADDRESS` could only be produced by the Milestone 15 remote button, so speaking
to Operator by name did nothing at all: "Operator, what are the colours in the rainbow" was judged
by the uninvited rules — strict floors, silence when unsure — and produced silence. Being addressed
by name is the most natural way to ask something aloud, and it was the one route in that nothing
listened for.

Matching is strict about position and loose at the edges. The name must fall in the first two
words, because "the switchboard operator called" is somebody talking *about* an operator while
"operator, what time is it" is somebody talking *to* one — three words was tried and was enough to
confuse the two. Near-misses of the same shape are accepted, since transcription mangles a
four-syllable word regularly and returns "operater" or "opperator"; one edit of tolerance, and only
between words of similar length, so "operate" and "operation" are not caught.

The asymmetry justifies the looseness: a false positive means Operator answers something nobody
quite asked, while a false negative means it ignores you when you used its name. The second is
worse, and it is the one people notice.

## ADR-052: Live search on the answer path, never on the decision path

**Status:** Accepted (2026-09-09)

`POST /ai/respond` may use OpenRouter's built-in web search. `POST /decide` may not.

A model answers from a snapshot of its training data and has no idea how stale that snapshot is.
Asked who wears 95 for the Rams, it gave a name that was plausible and out of date — and nothing in
a prompt fixes that, because the model cannot tell a fact that has changed from one that has not.
Search is the only thing that does.

The split is about what each path costs. The answer path runs when somebody asks a question:
seconds of latency and a search fee are acceptable there. The decision path runs on every lull in
conversation, dozens of times an hour, mostly to conclude that nothing needs saying — searching
there would multiply both the bill and the delay for no benefit, since the question is whether to
speak, not what the facts are.

Off by default. Most questions do not need it, and a feature that quietly bills per call should be
switched on deliberately.

## ADR-053: Route to endpoints that do not retain prompts

**Status:** Accepted (2026-09-09)

`OPERATOR_ZERO_DATA_RETENTION` restricts routing to endpoints that do not retain prompts, and
refuses providers that may store data.

This is the setting that matches what Operator actually sends. Transcripts leaving this device
contain other people — a baby, a partner, a colleague, whoever was in the room — none of whom
agreed to anything. The brief forbids silently building permanent records of the people around the
user, and a provider that retains prompts builds exactly that, on somebody else's disk, outside
any control this project has.

It is off by default, which is a compromise rather than a preference: enabling it restricts which
providers may serve a given model, and could make a configured model unavailable with a failure
that looks unrelated. The documentation recommends it in the strongest terms available short of
forcing it, and `.env.example` says why.

`OPERATOR_PROVIDER_SORT=latency` is separate and unrelated to privacy — it asks OpenRouter to
prefer the quickest endpoint for a model. Latency is the recurring finding in every live
measurement, and this is the cheapest thing that addresses it. A request carrying `sort` alone
makes no privacy claim, and a test pins that so the two are never conflated.
