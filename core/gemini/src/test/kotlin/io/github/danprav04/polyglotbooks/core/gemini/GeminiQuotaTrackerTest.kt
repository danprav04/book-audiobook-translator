package io.github.danprav04.polyglotbooks.core.gemini

import io.github.danprav04.polyglotbooks.core.model.ModelRole
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiQuotaTrackerTest {

    @Test
    fun `profileFor matches AI Studio free-tier limits for Flash Lite, Gemma 4, TTS, and Live`() {
        val tracker = GeminiQuotaTracker()

        val flashLite = tracker.profileFor("gemini-3.5-flash-lite")
        assertEquals(15, flashLite.rpmLimit)
        assertEquals(250_000, flashLite.tpmLimit)
        assertTrue(flashLite.supportsSystemInstruction)
        assertTrue(flashLite.supportsJsonMimeType)

        val gemma4 = tracker.profileFor("gemma-4-31b-it")
        assertEquals(30, gemma4.rpmLimit)
        assertEquals(16_000, gemma4.tpmLimit)
        assertEquals(14_400, gemma4.rpdLimit)
        assertFalse(gemma4.supportsSystemInstruction)
        assertFalse(gemma4.supportsJsonMimeType)

        val tts = tracker.profileFor("gemini-3.8-flash-tts")
        assertEquals(3, tts.rpmLimit)
        assertEquals(10_000, tts.tpmLimit)
        assertEquals(10, tts.rpdLimit)

        val nativeAudio = tracker.profileFor("gemini-2.5-flash-native-audio-dialog")
        assertEquals(Int.MAX_VALUE, nativeAudio.rpmLimit)
        assertEquals(1_000_000, nativeAudio.tpmLimit)

        val live38 = tracker.profileFor("gemini-3.8-live")
        assertEquals(Int.MAX_VALUE, live38.rpmLimit)
        assertEquals(65_000, live38.tpmLimit)

        val flash20 = tracker.profileFor("gemini-2.0-flash")
        assertEquals(0, flash20.rpmLimit)
        assertFalse(tracker.isModelAvailable("gemini-2.0-flash"))
    }

    @Test
    fun `recordHttpError parses 429 retryDelay, limit 0, and daily exhaustion`() {
        var nowMs = 1_000_000L
        val tracker = GeminiQuotaTracker(clockMs = { nowMs })

        tracker.recordHttpError(
            modelId = "gemini-3.5-flash-lite",
            httpCode = 429,
            responseBody = """{"error":{"message":"Quota exceeded. Please retry in 24.5s.","status":"RESOURCE_EXHAUSTED"}}"""
        )
        assertFalse(tracker.isModelAvailable("gemini-3.5-flash-lite"))
        val snap1 = tracker.quotaSnapshots.value["gemini-3.5-flash-lite"]!!
        assertEquals(24_500L, snap1.cooldownRemainingMs)

        // Advance clock past 24.5s cooldown
        nowMs += 25_000L
        assertTrue(tracker.isModelAvailable("gemini-3.5-flash-lite"))

        // limit: 0 marks permanently unavailable
        tracker.recordHttpError(
            modelId = "gemini-2.5-flash-lite",
            httpCode = 429,
            responseBody = """{"error":{"message":"Quota exceeded for metric free_tier_requests, limit: 0"}}"""
        )
        assertFalse(tracker.isModelAvailable("gemini-2.5-flash-lite"))
        assertTrue(tracker.quotaSnapshots.value["gemini-2.5-flash-lite"]!!.permanentlyUnavailable)

        // RPD exhaustion marks exhaustedForDay
        tracker.recordHttpError(
            modelId = "gemini-3.8-flash-tts",
            httpCode = 429,
            responseBody = """{"error":{"message":"GenerateRequestsPerDayPerProjectPerModel-FreeTier exceeded"}}"""
        )
        assertTrue(tracker.quotaSnapshots.value["gemini-3.8-flash-tts"]!!.exhaustedForDay)

        tracker.resetCooldowns()
        assertFalse(tracker.quotaSnapshots.value["gemini-3.8-flash-tts"]!!.exhaustedForDay)
    }

    @Test
    fun `isTtsQuotaLikelyExhausted triggers when TTS models hit cooldown or 3 RPM cap`() {
        val nowMs = 5_000_000L
        val tracker = GeminiQuotaTracker(clockMs = { nowMs })
        assertFalse(tracker.isTtsQuotaLikelyExhausted())

        repeat(3) { tracker.recordRequestStarted("gemini-3.8-flash-tts") }
        repeat(3) { tracker.recordRequestStarted("gemini-2.5-flash-preview-tts") }

        assertTrue(tracker.isTtsQuotaLikelyExhausted())
    }

    @Test
    fun `OkHttpTextModelClient rotates across Flash Lite 429s to Gemma 4 and adjusts Gemma payload`() = runTest {
        val capturedRequests = mutableListOf<Pair<String, String>>()
        val quotaTracker = GeminiQuotaTracker()

        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val path = req.url.encodedPath
                val bodyBuf = Buffer()
                req.body?.writeTo(bodyBuf)
                val bodyStr = bodyBuf.readUtf8()
                capturedRequests.add(path to bodyStr)

                val (code, json) = when {
                    path.contains("gemma-4-31b-it") -> 200 to """
                        {"candidates":[{"content":{"parts":[{"text":"[\"Hola\"]"}]}}],"usageMetadata":{"totalTokenCount":42}}
                    """.trimIndent()
                    path.contains("gemini-3.1-flash-lite") -> 404 to """
                        {"error":{"code":404,"message":"Model not found"}}
                    """.trimIndent()
                    else -> 429 to """
                        {"error":{"code":429,"message":"Rate limit exceeded. Please retry in 30s"}}
                    """.trimIndent()
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("Mock")
                    .body(json.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val textClient = OkHttpTextModelClient(
            okHttpClient = client,
            baseUrl = "https://mock.local",
            quotaTracker = quotaTracker,
            maxCooldownWaitMs = 0L
        )

        val result = textClient.generateContent(
            apiKey = "test-key",
            prompt = "[\"Hello\"]",
            systemInstruction = "Translate to Spanish",
            responseJsonSchema = """{"type":"array"}""",
            model = "gemini-3.5-flash-lite"
        )

        assertEquals("[\"Hola\"]", result)
        val gemmaReq = capturedRequests.first { it.first.contains("gemma-4-31b-it") }
        assertFalse("Gemma payload must not contain systemInstruction field", gemmaReq.second.contains("\"systemInstruction\""))
        assertFalse("Gemma payload must not contain responseMimeType", gemmaReq.second.contains("\"responseMimeType\""))
        assertTrue("Gemma payload must inline system instruction into user prompt", gemmaReq.second.contains("Translate to Spanish"))
    }

    @Test
    fun `OkHttpTextModelClient throws concise QuotaExhaustedException when all candidates fail`() = runTest {
        val quotaTracker = GeminiQuotaTracker()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .body("""{"error":{"code":429,"message":"Quota exceeded. Please retry in 45s."}}""".toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val textClient = OkHttpTextModelClient(
            okHttpClient = client,
            baseUrl = "https://mock.local",
            quotaTracker = quotaTracker,
            maxCooldownWaitMs = 0L
        )

        var caught: QuotaExhaustedException? = null
        try {
            textClient.generateContent(apiKey = "test-key", prompt = "Hi", model = "gemini-3.5-flash-lite")
        } catch (e: QuotaExhaustedException) {
            caught = e
        }
        assertNotNull(caught)
        assertTrue(caught!!.message!!.contains("Try again in ~45s"))
        assertFalse(caught.message!!.contains("\"error\""))
        assertTrue(caught.attemptedModels.isNotEmpty())
    }

    @Test
    fun `ModelCatalog excludes non-conversational models and provides freeTierHint and recommendations`() {
        val models = listOf(
            ModelItem("models/gemini-3.5-flash-lite", supportedGenerationMethods = listOf("generateContent")),
            ModelItem("models/deep-research-pro", supportedGenerationMethods = listOf("generateContent")),
            ModelItem("models/embedding-001", supportedGenerationMethods = listOf("generateContent")),
            ModelItem("models/imagen-3.0", supportedGenerationMethods = listOf("generateContent")),
            ModelItem("models/gemma-4-31b-it", supportedGenerationMethods = listOf("generateContent"))
        )

        val filtered = ModelCatalog.filterModelsForRole(models, ModelRole.TEXT_TRANSLATION).map { it.id }
        assertTrue("gemini-3.5-flash-lite" in filtered)
        assertTrue("gemma-4-31b-it" in filtered)
        assertFalse("deep-research-pro" in filtered)
        assertFalse("embedding-001" in filtered)
        assertFalse("imagen-3.0" in filtered)

        assertTrue(ModelCatalog.freeTierHint("gemini-3.8-flash-tts").contains("10 RPD"))
        assertTrue(ModelCatalog.freeTierHint("gemini-2.5-flash-native-audio-dialog").contains("1M TPM"))
        val recs = ModelCatalog.recommendedModelsForRole(ModelRole.TEXT_TRANSLATION, models)
        assertTrue("gemma-4-31b-it" in recs)
    }
}
