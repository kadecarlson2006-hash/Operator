package com.operator.backend.camera

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The multimodal shape of an OpenAI-compatible chat request (Milestone 16).
 *
 * Deliberately separate from `ChatMessage` in `OpenRouterApi.kt` rather than making that type's
 * `content` polymorphic. The text wire format was verified against a live call and works (risk 27);
 * widening it to carry either a string or an array of parts would put the one proven path at risk
 * for the sake of a feature that has never run. Two types cost a little duplication and nothing
 * else.
 *
 * **This shape is unverified.** It follows the OpenAI content-parts convention that OpenRouter
 * documents, but no vision call has been made (risk 56), which is the same substitution the
 * project has recorded twice before.
 */
@Serializable
internal data class VisionContentPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: VisionImageUrl? = null,
) {
    companion object {
        const val TEXT = "text"
        const val IMAGE_URL = "image_url"
    }
}

@Serializable
internal data class VisionImageUrl(val url: String)

@Serializable
internal data class VisionMessage(val role: String, val content: List<VisionContentPart>)

@Serializable
internal data class VisionRequestBody(
    val model: String,
    val messages: List<VisionMessage>,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
)

/**
 * Builds the `data:` URL an image travels in.
 *
 * The image never touches disk on the way (ADR-049): it arrives as bytes, becomes a string, is
 * sent, and both are dropped when the call returns.
 */
internal fun dataUrl(bytes: ByteArray, mimeType: String): String =
    "data:$mimeType;base64," + java.util.Base64.getEncoder().encodeToString(bytes)
