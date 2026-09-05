package com.operator.backend.transcription

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for the OpenAI-compatible speech-to-text endpoint (Milestone 8).
 *
 * Verified on 2026-09-05 against the official `openai` npm package 7.10.0, whose generated
 * client posts multipart/form-data to `{baseUrl}/audio/transcriptions` with bearer auth and the
 * fields `file`, `model`, and the optional `language`, `prompt`, `response_format`,
 * `temperature`. The hosted API reference could not be read from this environment
 * (api.openai.com is blocked by the egress proxy), which is the same substitution recorded for
 * the model provider in docs/RISKS_AND_UNKNOWNS.md item 27; the transcription equivalent is
 * item 34.
 *
 * The base URL is configuration, not a constant, because this shape is implemented by more than
 * one vendor and by self-hosted Whisper servers. Operator does not hard-code a speech vendor.
 */
@Serializable
data class TranscriptionResponse(
    /** The transcript. Present in every response format we ask for. */
    val text: String = "",
    /** Only in `verbose_json`. */
    val language: String? = null,
    /** Only in `verbose_json`; seconds of audio. */
    val duration: Double? = null,
    val usage: TranscriptionUsage? = null,
)

@Serializable
data class TranscriptionUsage(
    /** `tokens` or `duration`, depending on how the model bills. */
    val type: String? = null,
    val seconds: Double? = null,
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

/** `{"error": {"code", "message", "param", "type"}}` — the same envelope the model API uses. */
@Serializable
data class TranscriptionErrorEnvelope(val error: TranscriptionErrorBody? = null)

@Serializable
data class TranscriptionErrorBody(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
    val param: String? = null,
)
