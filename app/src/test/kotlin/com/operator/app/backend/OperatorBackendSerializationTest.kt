package com.operator.app.backend

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class OperatorBackendSerializationTest {
    @Test
    fun `ask request has a generated serializer`() {
        val encoded = Json.encodeToString(
            AskRequest(
                prompt = "Hello",
                tier = "FAST",
                mode = "WORK",
                wit = "DRY",
                transcript = listOf("Earlier line"),
            ),
        )

        val decoded = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("Hello", decoded.getValue("prompt").jsonPrimitive.content)
        assertEquals("FAST", decoded.getValue("tier").jsonPrimitive.content)
        assertEquals("Earlier line", decoded.getValue("transcript").jsonArray.single().jsonPrimitive.content)
    }
}
