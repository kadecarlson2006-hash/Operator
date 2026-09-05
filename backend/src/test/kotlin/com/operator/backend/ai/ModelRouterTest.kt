package com.operator.backend.ai

import com.operator.core.config.OperatorConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelRouterTest {
    private val full = ModelRouter(OperatorConfig(fastModelId = "v/fast", deepModelId = "v/deep", visionModelId = "v/vision"))

    @Test
    fun `short conversational prompts go to FAST`() {
        val d = full.route("Who is that actor?")
        assertEquals(ModelTier.FAST, d.tier)
        assertEquals("v/fast", d.modelId)
        assertTrue(d.fallbacks.isEmpty(), "FAST has nothing to fall back to")
    }

    @Test
    fun `analysis prompts and very long prompts go to DEEP with a FAST fallback`() {
        val d = full.route("Analyze whether this property is a good investment.")
        assertEquals(ModelTier.DEEP, d.tier)
        assertEquals("v/deep", d.modelId)
        assertEquals(listOf("v/fast"), d.fallbacks)
        assertEquals(ModelTier.DEEP, full.route("Should I take the contract?").tier)
        assertEquals(ModelTier.DEEP, full.route("x".repeat(700)).tier)
    }

    @Test
    fun `an image forces VISION and an explicit tier always wins`() {
        assertEquals(ModelTier.VISION, full.route("What am I looking at?", hasImage = true).tier)
        assertEquals(ModelTier.FAST, full.route("Analyze this in detail", requested = ModelTier.FAST).tier)
        assertEquals("requested explicitly", full.route("hi", requested = ModelTier.DEEP).reason)
    }

    @Test
    fun `unset tiers fall back to the fast model and an unset fast model fails loudly`() {
        val fastOnly = ModelRouter(OperatorConfig(fastModelId = "v/fast"))
        assertEquals("v/fast", fastOnly.route("Analyze the numbers").modelId)
        assertTrue(fastOnly.route("Analyze the numbers").fallbacks.isEmpty(), "no duplicate fallback when tiers share a model")

        val none = ModelRouter(OperatorConfig())
        val e = assertFailsWith<ModelNotConfiguredException> { none.route("hello") }
        assertTrue(e.message!!.contains(OperatorConfig.Keys.FAST_MODEL_ID))
    }
}
