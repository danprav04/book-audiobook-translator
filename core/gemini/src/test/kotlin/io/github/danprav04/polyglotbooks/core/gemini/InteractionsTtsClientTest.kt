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
    fun `buildPromptText omits Say prefix for null, blank, and neutral styles`() {
        val client = OkHttpInteractionsTtsClient()
        val bookText = "It is a truth universally acknowledged."

        assertEquals(bookText, client.buildPromptText(TtsRequest(text = bookText)))
        assertEquals(bookText, client.buildPromptText(TtsRequest(text = bookText, style = "", speaker = "   ")))
        assertEquals(bookText, client.buildPromptText(TtsRequest(text = bookText, style = "neutral")))
        assertEquals(bookText, client.buildPromptText(TtsRequest(text = bookText, style = "Neutral")))
        assertEquals(bookText, client.buildPromptText(TtsRequest(text = bookText, style = "  NEUTRAL  ", languageCode = "en")))
    }

    @Test
    fun `buildPromptText formats non-neutral style and speaker cleanly without leaking meta-instructions`() {
        val client = OkHttpInteractionsTtsClient()

        assertEquals(
            "Say in a warm tone: Hello world.",
            client.buildPromptText(TtsRequest(text = "Hello world.", style = "warm"))
        )
        assertEquals(
            "Say in a warm tone: Hello world.",
            client.buildPromptText(TtsRequest(text = "Hello world.", style = "warm tone"))
        )
        assertEquals(
            "Say as Elizabeth: Good morning.",
            client.buildPromptText(TtsRequest(text = "Good morning.", speaker = "Elizabeth", style = "neutral"))
        )
        assertEquals(
            "Say as Darcy in a calm, wry tone: Quite so.",
            client.buildPromptText(TtsRequest(text = "Quite so.", speaker = "Darcy", style = "calm, wry"))
        )

        // Structural dividers never receive a Say prefix even when style/speaker is set
        assertEquals(
            "* * *",
            client.buildPromptText(TtsRequest(text = "* * *", speaker = "Narrator", style = "dramatic"))
        )
        assertEquals(
            "---",
            client.buildPromptText(TtsRequest(text = "---", speaker = "Narrator", style = "dramatic"))
        )

        // Expressive punctuation-only dialogue resolves into spoken non-verbal interjection + style cue
        assertEquals(
            "Say as Alice in a hesitant, puzzled questioning murmur tone: Hmm...?",
            client.buildPromptText(TtsRequest(text = "\"......?\"", speaker = "Alice", style = null))
        )
        assertEquals(
            "Say in a startled, incredulous gasp tone: Huh?!",
            client.buildPromptText(TtsRequest(text = "\"?!\""))
        )

        // Meta-instruction phrases in style/speaker must never leak into TTS prompt text
        assertEquals(
            "Chapter One.",
            client.buildPromptText(
                TtsRequest(
                    text = "Chapter One.",
                    style = "Read aloud verbatim in English without translating or adding commentary"
                )
            )
        )
        assertEquals(
            "Chapter Two.",
            client.buildPromptText(
                TtsRequest(
                    text = "Chapter Two.",
                    speaker = "Narrator:\nRead aloud without commentary"
                )
            )
        )

        // Legitimate prose containing those words must remain intact
        val prose = "Please read aloud the letter without commentary, she asked."
        assertEquals(
            prose,
            client.buildPromptText(TtsRequest(text = prose, style = "neutral", languageCode = "en"))
        )
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
        val secondSentText = json.parseToJsonElement(capturedBodies[1]).jsonObject["contents"]!!
            .jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("Say in a warm tone: Bonjour!", secondSentText)
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
    fun `buildPromptText includes personality and storyContext only when provided`() {
        val client = OkHttpInteractionsTtsClient()
        val prompt = client.buildPromptText(
            TtsRequest(
                text = "Everything changed at 7:00 PM.",
                speaker = "Kim Dokja",
                style = "quiet, wary disbelief",
                personality = "guarded, analytical reader",
                storyContext = "Subway stops mid-tunnel as scenarios begin."
            )
        )
        assertTrue(prompt.contains("Kim Dokja (guarded, analytical reader)"))
        assertTrue(prompt.contains("quiet, wary disbelief tone"))
        assertTrue(prompt.contains("given the scene (Subway stops mid-tunnel as scenarios begin.)"))
        assertTrue(prompt.endsWith(": Everything changed at 7:00 PM."))
    }
}
