package com.operator.backend.config

import com.operator.core.config.OperatorConfig
import java.io.File

/**
 * Backend configuration. Secrets live here and only here (ADR-005); [redacted] is what the
 * health/diagnostics endpoints are allowed to show.
 */
data class BackendConfig(
    val port: Int = 8080,
    val host: String = "0.0.0.0",
    val databaseUrl: String? = null,
    val databaseUser: String? = null,
    val databasePassword: String? = null,
    val openRouterApiKey: String? = null,
    val elevenLabsApiKey: String? = null,
    /** Speech-to-text credential (Milestone 8). Separate from the model key: they may be different vendors. */
    val transcriptionApiKey: String? = null,
    val promptVersion: String = "operator-system-v1",
    /** Allows POST /memory/demo-seed. Off by default; on for local development and CI. */
    val demoSeedEnabled: Boolean = false,
    /** Shared, non-secret Operator settings (model IDs, voice IDs, thresholds). */
    val operator: OperatorConfig = OperatorConfig(),
) {
    val databaseConfigured: Boolean get() = !databaseUrl.isNullOrBlank()
    val openRouterConfigured: Boolean get() = !openRouterApiKey.isNullOrBlank()
    val elevenLabsConfigured: Boolean get() = !elevenLabsApiKey.isNullOrBlank()
    val transcriptionConfigured: Boolean
        get() = !transcriptionApiKey.isNullOrBlank() && !operator.transcriptionModelId.isNullOrBlank()

    /** JDBC form of [databaseUrl]. Accepts `postgresql://user:pass@host:port/db` or a `jdbc:` URL. */
    val jdbc: JdbcTarget? get() = databaseUrl?.let { JdbcTarget.from(it, databaseUser, databasePassword) }

    /** Everything an operator may see: no key material, ever. */
    fun redacted(): Map<String, String?> = mapOf(
        "port" to port.toString(),
        "databaseUrl" to jdbc?.redactedUrl,
        "openRouterApiKey" to mask(openRouterApiKey),
        "elevenLabsApiKey" to mask(elevenLabsApiKey),
        "transcriptionApiKey" to mask(transcriptionApiKey),
        "promptVersion" to promptVersion,
        "fastModelId" to operator.fastModelId,
        "deepModelId" to operator.deepModelId,
        "decisionModelId" to operator.decisionModelId,
        "visionModelId" to operator.visionModelId,
        // Whether this is set is the difference between semantic and lexical retrieval, so its
        // absence from the startup line reads as "no embeddings" when it is really "not logged".
        "embeddingModelId" to operator.embeddingModelId,
        // Reported for the same reason as the model IDs: a setting nobody can see is a setting
        // nobody can confirm took effect, and both of these change what leaves this machine.
        "webSearch" to operator.webSearchEnabled.toString(),
        "zeroDataRetention" to operator.zeroDataRetention.toString(),
        "providerSort" to operator.providerSort,
        "ttsProvider" to operator.ttsProvider,
        "elevenLabsVoiceId" to operator.elevenLabsVoiceId,
        "elevenLabsModelId" to operator.elevenLabsModelId,
        "transcriptionProvider" to operator.transcriptionProvider,
        "transcriptionModelId" to operator.transcriptionModelId,
        "transcriptionBaseUrl" to operator.transcriptionBaseUrl,
        "transcriptionLanguage" to operator.transcriptionLanguage,
    )

    companion object {
        fun fromMap(values: Map<String, String?>): BackendConfig {
            fun str(key: String) = values[key]?.trim()?.takeIf { it.isNotEmpty() }
            val defaults = BackendConfig()
            return BackendConfig(
                port = str(Keys.PORT)?.toIntOrNull()?.takeIf { it in 1..65535 } ?: defaults.port,
                host = str(Keys.HOST) ?: defaults.host,
                databaseUrl = str(Keys.DATABASE_URL),
                databaseUser = str(Keys.DATABASE_USER),
                databasePassword = str(Keys.DATABASE_PASSWORD),
                openRouterApiKey = str(Keys.OPENROUTER_API_KEY),
                elevenLabsApiKey = str(Keys.ELEVENLABS_API_KEY),
                transcriptionApiKey = str(Keys.TRANSCRIPTION_API_KEY),
                promptVersion = str(Keys.PROMPT_VERSION) ?: defaults.promptVersion,
                demoSeedEnabled = str(Keys.DEMO_SEED_ENABLED)?.toBoolean() ?: defaults.demoSeedEnabled,
                operator = OperatorConfig.fromMap(values),
            )
        }

        /**
         * Real environment first, then an `.env` file. Values from the file never override real
         * environment variables.
         *
         * The file is searched for from the working directory upwards, because the working
         * directory is not where anyone thinks it is: `gradle :backend:run` starts the JVM in
         * `backend/`, while the README (correctly) tells you to put `.env` in the repo root.
         * Looking only in the working directory meant the file was silently ignored and every
         * provider reported itself unconfigured with nothing to explain why. `OPERATOR_ENV_FILE`
         * still wins outright when set. Mirrors how PromptLibrary finds prompts.
         */
        fun fromEnvironment(env: Map<String, String> = System.getenv()): BackendConfig {
            val explicit = env[Keys.ENV_FILE]?.let(::File)
            val file = explicit ?: findEnvFile()
            val merged = LinkedHashMap<String, String?>()
            merged.putAll(EnvFile.load(file ?: File(".env")))
            merged.putAll(env)
            return fromMap(merged).also { loadedEnvFile = file?.takeIf { f -> f.isFile }?.absolutePath }
        }

        /** Absolute path of the `.env` actually read, or null when none was found. For startup logging. */
        @Volatile
        var loadedEnvFile: String? = null
            private set

        /** `.env` in the working directory or any parent, nearest first. */
        internal fun findEnvFile(start: File = File(".").absoluteFile): File? =
            generateSequence(start) { it.parentFile }
                .take(MAX_ENV_SEARCH_DEPTH)
                .map { File(it, ".env") }
                .firstOrNull { it.isFile }

        private const val MAX_ENV_SEARCH_DEPTH = 6

        private fun mask(secret: String?): String? = when {
            secret.isNullOrBlank() -> null
            secret.length <= 8 -> "set"
            else -> "set (…${secret.takeLast(4)})"
        }
    }

    object Keys {
        const val PORT = "OPERATOR_BACKEND_PORT"
        const val HOST = "OPERATOR_BACKEND_HOST"
        const val ENV_FILE = "OPERATOR_ENV_FILE"
        const val DATABASE_URL = "DATABASE_URL"
        const val DATABASE_USER = "DATABASE_USER"
        const val DATABASE_PASSWORD = "DATABASE_PASSWORD"
        const val OPENROUTER_API_KEY = "OPENROUTER_API_KEY"
        const val ELEVENLABS_API_KEY = "ELEVENLABS_API_KEY"
        const val TRANSCRIPTION_API_KEY = "TRANSCRIPTION_API_KEY"
        const val PROMPT_VERSION = "OPERATOR_PROMPT_VERSION"
        const val DEMO_SEED_ENABLED = "OPERATOR_DEMO_SEED_ENABLED"
    }
}

/** A JDBC URL plus credentials, derived from either a libpq-style or a jdbc-style DATABASE_URL. */
data class JdbcTarget(val url: String, val user: String?, val password: String?) {
    val redactedUrl: String get() = url

    companion object {
        private val LIBPQ = Regex("""^postgres(?:ql)?://(?:([^:@/]+)(?::([^@/]*))?@)?([^/:?]+)(?::(\d+))?/([^?]+)(\?.*)?$""")

        fun from(raw: String, userOverride: String?, passwordOverride: String?): JdbcTarget {
            if (raw.startsWith("jdbc:")) return JdbcTarget(raw, userOverride, passwordOverride)
            val m = LIBPQ.matchEntire(raw) ?: throw IllegalArgumentException("Unrecognised DATABASE_URL format")
            val (user, pass, host, port, db, query) = m.destructured
            val url = "jdbc:postgresql://$host${if (port.isNotEmpty()) ":$port" else ""}/$db$query"
            return JdbcTarget(
                url = url,
                user = userOverride ?: user.takeIf { it.isNotEmpty() },
                password = passwordOverride ?: pass.takeIf { it.isNotEmpty() },
            )
        }
    }
}
