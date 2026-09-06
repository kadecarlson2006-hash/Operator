package com.operator.backend.providers

import com.operator.backend.ai.OpenRouterProvider
import com.operator.backend.config.BackendConfig
import com.operator.backend.tts.ElevenLabsTTSProvider
import com.operator.backend.transcription.OpenAiCompatibleTranscriptionProvider
import com.operator.core.ai.AIProvider
import com.operator.core.ai.AIRequest
import com.operator.core.ai.AIResponse
import com.operator.core.audio.PcmClip
import com.operator.core.transcription.Transcript
import com.operator.core.transcription.TranscriptionProvider
import com.operator.core.tts.TTSProvider
import com.operator.core.tts.TTSRequest
import com.operator.core.tts.TTSAudioStream
import kotlinx.serialization.Serializable

/**
 * The backend's provider slots, wired from configuration. Milestone 4 only decides *which*
 * provider each slot would use and whether it is configured; no network calls exist yet.
 * Milestone 6 replaces the AI slot with OpenRouterProvider, Milestone 8 the transcription slot,
 * Milestone 9 the TTS slot.
 */
class ProviderRegistry(config: BackendConfig) {
    /** Real OpenRouter client once a key is configured, otherwise a provider that fails loudly. */
    val ai: AIProvider = if (config.openRouterConfigured) {
        OpenRouterProvider(apiKey = config.openRouterApiKey!!, appTitle = "Operator")
    } else {
        NotConfiguredAIProvider
    }
    val tts: TTSProvider = if (
        config.operator.ttsProvider.equals("elevenlabs", ignoreCase = true) && config.elevenLabsConfigured
    ) {
        ElevenLabsTTSProvider(config.elevenLabsApiKey!!)
    } else {
        NotConfiguredTTSProvider
    }

    /** Real speech-to-text once a key and a model are configured, otherwise a provider that fails loudly. */
    val transcription: TranscriptionProvider = if (config.transcriptionConfigured) {
        OpenAiCompatibleTranscriptionProvider(
            apiKey = config.transcriptionApiKey!!,
            modelId = config.operator.transcriptionModelId!!,
            baseUrl = config.operator.transcriptionBaseUrl ?: OpenAiCompatibleTranscriptionProvider.DEFAULT_BASE_URL,
            languageCode = config.operator.transcriptionLanguage,
            name = config.operator.transcriptionProvider ?: "openai-compatible",
        )
    } else {
        NotConfiguredTranscriptionProvider
    }

    val status = ProviderStatus(
        ai = SlotStatus(
            provider = if (config.openRouterConfigured) "openrouter" else "none",
            configured = config.openRouterConfigured && !config.operator.fastModelId.isNullOrBlank(),
            detail = listOfNotNull(
                config.operator.fastModelId?.let { "fast=$it" },
                config.operator.deepModelId?.let { "deep=$it" },
                config.operator.decisionModelId?.let { "decision=$it" },
                config.operator.visionModelId?.let { "vision=$it" },
            ).joinToString(" ").ifEmpty { "no model IDs configured" },
        ),
        tts = SlotStatus(
            provider = config.operator.ttsProvider ?: "none",
            configured = config.operator.ttsProvider.equals("elevenlabs", ignoreCase = true) &&
                config.elevenLabsConfigured &&
                !config.operator.elevenLabsVoiceId.isNullOrBlank() &&
                !config.operator.elevenLabsModelId.isNullOrBlank(),
            detail = listOf(
                "voice=${config.operator.elevenLabsVoiceId ?: "missing"}",
                "model=${config.operator.elevenLabsModelId ?: "missing"}",
            ).joinToString(" "),
        ),
        transcription = SlotStatus(
            provider = if (config.transcriptionConfigured) (config.operator.transcriptionProvider ?: "openai-compatible") else "none",
            configured = config.transcriptionConfigured,
            detail = listOfNotNull(
                config.operator.transcriptionModelId?.let { "model=$it" },
                config.operator.transcriptionBaseUrl?.let { "base=$it" },
                config.operator.transcriptionLanguage?.let { "language=$it" },
            ).joinToString(" ").ifEmpty { "no transcription model configured" },
        ),
    )
}

@Serializable
data class SlotStatus(val provider: String, val configured: Boolean, val detail: String)

@Serializable
data class ProviderStatus(val ai: SlotStatus, val tts: SlotStatus, val transcription: SlotStatus)

fun ProviderRegistry.close() {
    (ai as? OpenRouterProvider)?.close()
    (transcription as? OpenAiCompatibleTranscriptionProvider)?.close()
    (tts as? ElevenLabsTTSProvider)?.close()
}

class ProviderNotConfiguredException(slot: String) : IllegalStateException("$slot provider is not configured")

object NotConfiguredAIProvider : AIProvider {
    override suspend fun generate(request: AIRequest): AIResponse = throw ProviderNotConfiguredException("AI")
}

object NotConfiguredTTSProvider : TTSProvider {
    override val name = "none"
    override suspend fun open(request: TTSRequest): TTSAudioStream = throw ProviderNotConfiguredException("TTS")
    override fun cancel() = Unit
}

object NotConfiguredTranscriptionProvider : TranscriptionProvider {
    override val name = "none"
    override suspend fun transcribe(clip: PcmClip): Transcript = throw ProviderNotConfiguredException("Transcription")
}
