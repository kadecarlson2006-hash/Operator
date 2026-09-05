package com.operator.backend.providers

import com.operator.backend.config.BackendConfig
import com.operator.core.ai.AIProvider
import com.operator.core.ai.AIRequest
import com.operator.core.ai.AIResponse
import com.operator.core.audio.PcmClip
import com.operator.core.transcription.Transcript
import com.operator.core.transcription.TranscriptionProvider
import com.operator.core.tts.TTSProvider
import com.operator.core.tts.TTSRequest
import com.operator.core.tts.TTSResult
import kotlinx.serialization.Serializable

/**
 * The backend's provider slots, wired from configuration. Milestone 4 only decides *which*
 * provider each slot would use and whether it is configured; no network calls exist yet.
 * Milestone 6 replaces the AI slot with OpenRouterProvider, Milestone 8/9 the others.
 */
class ProviderRegistry(config: BackendConfig) {
    val ai: AIProvider = NotConfiguredAIProvider
    val tts: TTSProvider = NotConfiguredTTSProvider
    val transcription: TranscriptionProvider = NotConfiguredTranscriptionProvider

    val status = ProviderStatus(
        ai = SlotStatus(
            provider = if (config.openRouterConfigured) "openrouter (pending Milestone 6)" else "none",
            configured = config.openRouterConfigured,
            detail = listOfNotNull(
                config.operator.fastModelId?.let { "fast=$it" },
                config.operator.deepModelId?.let { "deep=$it" },
                config.operator.decisionModelId?.let { "decision=$it" },
                config.operator.visionModelId?.let { "vision=$it" },
            ).joinToString(" ").ifEmpty { "no model IDs configured" },
        ),
        tts = SlotStatus(
            provider = config.operator.ttsProvider ?: "none",
            configured = config.operator.ttsProvider == "elevenlabs" && config.elevenLabsConfigured && !config.operator.elevenLabsVoiceId.isNullOrBlank(),
            detail = config.operator.elevenLabsVoiceId?.let { "voice=$it" } ?: "no voice ID configured",
        ),
        transcription = SlotStatus(provider = "none", configured = false, detail = "arrives in Milestone 8"),
    )
}

@Serializable
data class SlotStatus(val provider: String, val configured: Boolean, val detail: String)

@Serializable
data class ProviderStatus(val ai: SlotStatus, val tts: SlotStatus, val transcription: SlotStatus)

class ProviderNotConfiguredException(slot: String) : IllegalStateException("$slot provider is not configured")

object NotConfiguredAIProvider : AIProvider {
    override suspend fun generate(request: AIRequest): AIResponse = throw ProviderNotConfiguredException("AI")
}

object NotConfiguredTTSProvider : TTSProvider {
    override val name = "none"
    override suspend fun speak(request: TTSRequest): TTSResult = throw ProviderNotConfiguredException("TTS")
    override fun cancel() = Unit
}

object NotConfiguredTranscriptionProvider : TranscriptionProvider {
    override val name = "none"
    override suspend fun transcribe(clip: PcmClip): Transcript = throw ProviderNotConfiguredException("Transcription")
}
