package io.github.danprav04.polyglotbooks.core.gemini

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class InteractionsTtsClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `buildPromptText always returns strictly verbatim text without Say prefix`() {
        val client = OkHttpInteractionsTtsClient()
        val bookText = "It is a truth universally acknowledged."

        assertEquals(bookText, client.buildPromptText(TtsRequest(text = bookText)))
        assertEquals(bookText, client.buildPromptText(TtsRequest(text = bookText, style = "", speaker = "   ")))
        assertEquals(bookText, client.buildPromptText(TtsRequest(text = bookText, style = "neutral")))
        assertEquals(bookText, client.buildPromptText(TtsRequest(text = bookText, style = "Neutral")))
        assertEquals(bookText, client.buildPromptText(TtsRequest(text = bookText, style = "  NEUTRAL  ", languageCode = "en")))
        assertEquals("Hello world.", client.buildPromptText(TtsRequest(text = "Hello world.", style = "warm")))
        assertEquals("Good morning.", client.buildPromptText(TtsRequest(text = "Good morning.", speaker = "Elizabeth", style = "neutral")))
        assertEquals("Quite so.", client.buildPromptText(TtsRequest(text = "Quite so.", speaker = "Darcy", style = "calm, wry")))
        assertEquals("* * *", client.buildPromptText(TtsRequest(text = "* * *", speaker = "Narrator", style = "dramatic")))
        assertEquals("---", client.buildPromptText(TtsRequest(text = "---", speaker = "Narrator", style = "dramatic")))
        assertEquals("\"......?\"", client.buildPromptText(TtsRequest(text = "\"......?\"", speaker = "Alice", style = null)))
        assertEquals("\"?!\"", client.buildPromptText(TtsRequest(text = "\"?!\"")))
    }

    @Test
    fun `buildMetadataStyleCue includes character and scene direction while filtering meta-instructions`() {
        val client = OkHttpInteractionsTtsClient()

        assertNull(client.buildMetadataStyleCue(speaker = "Narrator", style = "neutral", personality = null, storyContext = null))
        assertNull(
            client.buildMetadataStyleCue(
                speaker = "Narrator:\nRead aloud without commentary",
                style = "Read aloud verbatim in English without translating or adding commentary",
                personality = null,
                storyContext = null
            )
        )

        val cue = client.buildMetadataStyleCue(
            speaker = "Kim Dokja",
            style = "quiet, wary disbelief",
            personality = "guarded, analytical reader",
            storyContext = "Subway stops mid-tunnel as scenarios begin."
        )
        assertTrue(cue.orEmpty().contains("quiet, wary disbelief"))
        assertTrue(cue.orEmpty().contains("portraying Kim Dokja (guarded, analytical reader)"))
        assertTrue(cue.orEmpty().contains("scene (Subway stops mid-tunnel as scenarios begin.)"))
        assertFalse(cue.orEmpty().contains("Say as"))
    }

    @Test
    fun `OkHttpInteractionsTtsClient sends clean text without neutral prefix or languageCode field on the wire`() = runTest {
        val capturedBodies = mutableListOf<String>()
        val rawPcm = byteArrayOf(11, 22, 33, 44, 55, 66)
        val wavBytes = wrapPcmAsWav(rawPcm)
        val b64Audio = Base64.getEncoder().encodeToString(wavBytes)

        val okHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val buffer = Buffer()
                req.body?.writeTo(buffer)
                capturedBodies.add(buffer.readUtf8())

                val responseJson = """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              { "inlineData": { "mimeType": "audio/wav", "data": "$b64Audio" } }
                            ]
                          }
                        }
                      ],
                      "usageMetadata": { "totalTokenCount": 64 }
                    }
                """.trimIndent()

                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseJson.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val client = OkHttpInteractionsTtsClient(
            okHttpClient = okHttp,
            baseUrl = "https://mock.local"
        )

        val resultPcm = client.synthesizeUnary(
            apiKey = "test-key",
            request = TtsRequest(
                text = "Call me Ishmael.",
                voiceName = "Charon",
                style = "neutral",
                languageCode = "en"
            )
        )

        assertArrayEquals(rawPcm, resultPcm)
        assertEquals(1, capturedBodies.size)

        val root = json.parseToJsonElement(capturedBodies[0]).jsonObject
        val sentText = root["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("Call me Ishmael.", sentText)
        assertFalse(sentText.contains("Say in a neutral tone", ignoreCase = true))
        assertFalse(sentText.contains("Read aloud", ignoreCase = true))
        assertFalse(sentText.contains("commentary", ignoreCase = true))

        val speechConfig = root["generationConfig"]!!.jsonObject["speechConfig"]!!.jsonObject
        assertNull(speechConfig["languageCode"])
        assertEquals(
            "Charon",
            speechConfig["voiceConfig"]!!.jsonObject["prebuiltVoiceConfig"]!!.jsonObject["voiceName"]!!.jsonPrimitive.content
        )

        val streamedChunks = client.synthesizeStream(
            apiKey = "test-key",
            request = TtsRequest(
                text = "Bonjour!",
                voiceName = "Aoede",
                style = "warm",
                languageCode = "fr"
            )
        ).toList()

        assertEquals(1, streamedChunks.size)
        assertArrayEquals(rawPcm, streamedChunks[0].data)
        val secondPart = json.parseToJsonElement(capturedBodies[1]).jsonObject["contents"]!!
            .jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject
        assertEquals("Bonjour!", secondPart["text"]!!.jsonPrimitive.content)
        assertEquals("warm", secondPart["speechMetadata"]!!.jsonObject["style"]!!.jsonPrimitive.content)
        assertNull(secondPart["speechMetadata"]!!.jsonObject["speaker"])
    }

    private fun wrapPcmAsWav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun writeLeInt(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }
        fun writeLeShort(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeLeInt(36 + pcm.size)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeLeInt(16)
        writeLeShort(1)
        writeLeShort(1)
        writeLeInt(24000)
        writeLeInt(48000)
        writeLeShort(2)
        writeLeShort(16)
        out.write("data".toByteArray(Charsets.US_ASCII))
        writeLeInt(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    @Test
    fun `buildRequestJson batches multi-segment acting cues via speechMetadata and strips on 2_5 fallback`() {
        val client = OkHttpInteractionsTtsClient()
        val request = TtsRequest(
            text = "Line 1\n\nLine 2\n\nLine 3",
            voiceName = "Charon",
            storyContext = "Subway stops mid-tunnel as scenarios begin.",
            segments = listOf(
                TtsSegment(text = "The train jolted.", style = "tense", speaker = "Narrator"),
                TtsSegment(text = "What is happening?", style = "tense", speaker = "Narrator"),
                TtsSegment(
                    text = "Hey, shut up!",
                    style = "brash, aggressive",
                    speaker = "Kim Namwoon",
                    personality = "impulsive delinquent"
                )
            )
        )

        val v38Json = json.parseToJsonElement(
            client.buildRequestJson(request, modelId = "gemini-3.8-flash-tts")
        ).jsonObject
        val parts38 = v38Json["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        // Segments 1 & 2 share narrator/tense cue and merge into part 1 (with scene), while segment 3 stays distinct.
        assertEquals(2, parts38.size)
        assertEquals("The train jolted.\n\nWhat is happening?", parts38[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(parts38[0].jsonObject["speechMetadata"]!!.jsonObject["style"]!!.jsonPrimitive.content.contains("scene (Subway stops mid-tunnel as scenarios begin.)"))
        assertTrue(parts38.all { !it.jsonObject["text"]!!.jsonPrimitive.content.contains("Say as") })
        assertEquals("Hey, shut up!", parts38[1].jsonObject["text"]!!.jsonPrimitive.content)
        val namwoonPart = parts38[1].jsonObject
        val namwoonStyle = namwoonPart["speechMetadata"]!!.jsonObject["style"]!!.jsonPrimitive.content
        assertTrue(namwoonStyle.contains("portraying Kim Namwoon (impulsive delinquent)"))
        assertNull(namwoonPart["speechMetadata"]!!.jsonObject["speaker"])

        val v25Json = json.parseToJsonElement(
            client.buildRequestJson(request, modelId = "gemini-2.5-flash-preview-tts")
        ).jsonObject
        val parts25 = v25Json["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertTrue(parts25.all { it.jsonObject["speechMetadata"] == null })
        assertTrue(parts25.all { !it.jsonObject["text"]!!.jsonPrimitive.content.contains("Say as") })
    }

    @Test
    fun `OkHttpInteractionsTtsClient retries plain verbatim text on HTTP 400 when speechMetadata was rejected`() = runTest {
        val capturedBodies = mutableListOf<String>()
        val rawPcm = byteArrayOf(1, 2, 3, 4)
        val b64Audio = Base64.getEncoder().encodeToString(wrapPcmAsWav(rawPcm))
        var calls = 0

        val okHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                calls += 1
                val req = chain.request()
                val buffer = Buffer()
                req.body?.writeTo(buffer)
                capturedBodies.add(buffer.readUtf8())

                if (calls == 1) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(400)
                        .message("Bad Request")
                        .body("""{"error":{"message":"Unknown field speechMetadata"}}""".toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    val responseJson = """
                        {
                          "candidates": [
                            { "content": { "parts": [ { "inlineData": { "mimeType": "audio/wav", "data": "$b64Audio" } } ] } }
                          ]
                        }
                    """.trimIndent()
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(responseJson.toResponseBody("application/json".toMediaType()))
                        .build()
                }
            })
            .build()

        val client = OkHttpInteractionsTtsClient(okHttpClient = okHttp, baseUrl = "https://mock.local")
        val pcm = client.synthesizeUnary(
            apiKey = "test-key",
            request = TtsRequest(
                text = "Hold on!",
                voiceName = "Charon",
                speaker = "Kim Dokja",
                style = "urgent"
            )
        )

        assertArrayEquals(rawPcm, pcm)
        assertEquals(2, calls)
        val firstPart = json.parseToJsonElement(capturedBodies[0]).jsonObject["contents"]!!
            .jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject
        val retryPart = json.parseToJsonElement(capturedBodies[1]).jsonObject["contents"]!!
            .jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject
        assertTrue(firstPart["speechMetadata"] != null)
        assertNull(retryPart["speechMetadata"])
        assertEquals("Hold on!", retryPart["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildPromptText and buildRequestJson normalize paired single quotes while keeping apostrophes`() {
        val client = OkHttpInteractionsTtsClient(
            okHttpClient = OkHttpClient(),
            baseUrl = "https://mock.local"
        )
        val input = "[The constellation 'Demon-like Judge of Fire' wasn't pleased with Kim Namwoon's choice.]"
        val expected = "[The constellation \u201CDemon-like Judge of Fire\u201D wasn't pleased with Kim Namwoon's choice.]"
        val request = TtsRequest(text = input, voiceName = "Charon")

        assertEquals(expected, client.buildPromptText(request))

        val payload = json.parseToJsonElement(client.buildRequestJson(request)).jsonObject
        val text = payload["contents"]!!.jsonArray[0]
            .jsonObject["parts"]!!.jsonArray[0]
            .jsonObject["text"]!!.jsonPrimitive.content
        assertEquals(expected, text)
    }
}
