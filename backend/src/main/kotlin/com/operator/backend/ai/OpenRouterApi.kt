package com.operator.backend.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for the OpenRouter chat-completions API.
 *
 * Verified on 2026-09-05 against OpenRouter's own published SDK
 * (`@openrouter/ai-sdk-provider` 3.0.0, https://github.com/OpenRouterTeam/ai-sdk-provider):
 *   base URL  https://openrouter.ai/api/v1
 *   endpoint  POST /chat/completions
 *   headers   Authorization: Bearer <key>, optional X-OpenRouter-Title and HTTP-Referer
 *   request   model, models (fallback list), messages, max_tokens, temperature, top_p, stream
 *   response  id, model, provider, choices[].message.content, choices[].finish_reason,
 *             usage{prompt_tokens, completion_tokens, total_tokens, cost, *_details}
 *   error     {"error":{"code","message","type","param"}}
 * openrouter.ai itself is unreachable from the build sandbox, so the hosted reference could not
 * be cross-checked; see docs/RISKS_AND_UNKNOWNS.md item 27.
 */
@Serializable
data class ChatMessage(val role: String, val content: String) {
    companion object {
        const val SYSTEM = "system"
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

/**
 * OpenRouter's built-in web search (ADR-052).
 *
 * Shape taken from `@openrouter/ai-sdk-provider`, like the rest of this file - the hosted
 * reference is unreachable from the build sandbox, so this is the same substitution recorded in
 * risk 27, and carries the same caveat (risk 62).
 */
@Serializable
data class WebSearchOptions(
    @SerialName("max_results") val maxResults: Int? = null,
    @SerialName("search_prompt") val searchPrompt: String? = null,
    /** "native" uses the provider's own search, "exa" uses Exa. Null lets OpenRouter choose. */
    val engine: String? = null,
)

/**
 * Provider routing preferences (ADR-053).
 *
 * The privacy fields are the reason this exists. Operator sends transcripts of conversations that
 * include people who never agreed to any of this - a baby, a partner, whoever is in the room - and
 * routing them to an endpoint that retains prompts would put exactly the permanent record of other
 * people the brief forbids on somebody else's disk. `zdr` restricts routing to endpoints that do
 * not retain prompts; `dataCollection = "deny"` refuses providers that may store data.
 *
 * `sort = "latency"` is the other reason: slow responses are the recurring finding in every live
 * measurement, and OpenRouter can prefer the quickest endpoint for the same model.
 */
@Serializable
data class ProviderPreferences(
    @SerialName("data_collection") val dataCollection: String? = null,
    val zdr: Boolean? = null,
    /** "price", "throughput" or "latency". */
    val sort: String? = null,
    @SerialName("allow_fallbacks") val allowFallbacks: Boolean? = null,
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    /** OpenRouter's fallback list: tried in order if `model` is unavailable. */
    val models: List<String>? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    val stream: Boolean = false,
    /** Absent unless the caller asked for it: search costs money and time on every call. */
    @SerialName("web_search_options") val webSearchOptions: WebSearchOptions? = null,
    val provider: ProviderPreferences? = null,
)

@Serializable
data class ChatCompletionResponse(
    val id: String? = null,
    val model: String? = null,
    val provider: String? = null,
    val choices: List<Choice> = emptyList(),
    val usage: TokenUsage? = null,
)

@Serializable
data class Choice(
    val message: ResponseMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ResponseMessage(val role: String? = null, val content: String? = null)

@Serializable
data class TokenUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    /** OpenRouter reports credits spent when the account exposes it. */
    val cost: Double? = null,
)

@Serializable
data class ErrorEnvelope(val error: ErrorBody? = null)

@Serializable
data class ErrorBody(val message: String = "unknown error", val type: String? = null, val code: String? = null)
