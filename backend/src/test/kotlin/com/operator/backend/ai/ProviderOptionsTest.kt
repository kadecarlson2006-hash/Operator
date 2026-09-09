package com.operator.backend.ai

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.operator.core.ai.AIRequest

/** ADR-052 and ADR-053: what actually goes on the wire when these are configured. */
class ProviderOptionsTest {

    private val ok = """{"model":"m","choices":[{"message":{"role":"assistant","content":"hello"}}]}"""

    private fun provider(sent: MutableList<String>) = OpenRouterProvider(
        apiKey = "k",
        engine = MockEngine { request ->
            sent += (request.body as io.ktor.http.content.TextContent).text
            respond(ok, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        },
    )

    private fun ask(p: OpenRouterProvider) = runBlocking {
        p.generate(AIRequest(modelId = "m", systemPrompt = "s", userContent = "u"))
    }

    @Test
    fun `nothing extra is sent when nothing is configured`() {
        val sent = mutableListOf<String>()
        ask(provider(sent))
        val body = Json.parseToJsonElement(sent.single()).jsonObject
        // encodeDefaults is off, so absent means absent: a request that asked for neither must not
        // start paying for search or restricting routing by accident.
        assertFalse(body.containsKey("web_search_options"), "search must be opt-in")
        assertFalse(body.containsKey("provider"), "routing preferences must be opt-in")
    }

    @Test
    fun `web search travels with the request when enabled`() {
        val sent = mutableListOf<String>()
        val p = provider(sent).apply { webSearch = WebSearchOptions(maxResults = 5) }
        ask(p)
        val opts = Json.parseToJsonElement(sent.single()).jsonObject["web_search_options"]!!.jsonObject
        assertEquals(5, opts["max_results"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `zero data retention refuses providers that store prompts`() {
        // The reason ADR-053 exists: Operator sends transcripts of rooms containing people who
        // never agreed to any of this.
        val sent = mutableListOf<String>()
        val p = provider(sent).apply {
            providerPreferences = ProviderPreferences(dataCollection = "deny", zdr = true, sort = "latency")
        }
        ask(p)
        val prefs = Json.parseToJsonElement(sent.single()).jsonObject["provider"]!!.jsonObject
        assertEquals("deny", prefs["data_collection"]!!.jsonPrimitive.content)
        assertTrue(prefs["zdr"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("latency", prefs["sort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sorting by latency alone does not imply any privacy claim`() {
        // Someone reading a request with sort=latency should not conclude prompts are protected.
        val sent = mutableListOf<String>()
        val p = provider(sent).apply { providerPreferences = ProviderPreferences(sort = "latency") }
        ask(p)
        val prefs = Json.parseToJsonElement(sent.single()).jsonObject["provider"]!!.jsonObject
        assertFalse(prefs.containsKey("zdr"))
        assertFalse(prefs.containsKey("data_collection"))
    }
}
