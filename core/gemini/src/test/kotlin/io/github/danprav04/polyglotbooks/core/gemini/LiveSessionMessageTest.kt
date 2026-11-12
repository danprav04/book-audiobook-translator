package io.github.danprav04.polyglotbooks.core.gemini

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader

class LiveSessionMessageTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private fun loadFixture(filename: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/$filename")
            ?: throw IllegalArgumentException("Fixture not found: $filename")
        return InputStreamReader(stream).readText()
    }

    @Test
    fun testSetupMessageTranslateDeserializationAndRoundTrip() {
        val rawJson = loadFixture("ws_client_setup_translate.json")
        val message = json.decodeFromString<LiveClientMessage.SetupMessage>(rawJson)

        assertEquals("models/gemini-3.5-live-translate-preview", message.setup.model)
        val genConfig = message.setup.generationConfig
        assertNotNull(genConfig)
        assertEquals(listOf("AUDIO"), genConfig?.responseModalities)
        assertNotNull(genConfig?.inputAudioTranscription)
        assertNotNull(genConfig?.outputAudioTranscription)
        assertEquals("pl", genConfig?.translationConfig?.targetLanguageCode)
        assertEquals(true, genConfig?.translationConfig?.echoTargetLanguage)

        assertEquals("session_handle_abc123", message.setup.sessionResumption?.handle)
        assertNotNull(message.setup.contextWindowCompression?.slidingWindow)

        // Verify round-trip encoding
        val encoded = json.encodeToString(LiveClientMessage.SetupMessage.serializer(), message)
        val decodedAgain = json.decodeFromString<LiveClientMessage.SetupMessage>(encoded)
        assertEquals(message, decodedAgain)
    }

    @Test
    fun testSetupMessageLiveDeserialization() {
        val rawJson = loadFixture("ws_client_setup_live.json")
        val message = json.decodeFromString<LiveClientMessage.SetupMessage>(rawJson)

        assertEquals("models/gemini-3.8-live", message.setup.model)
        assertEquals(listOf("AUDIO"), message.setup.generationConfig?.responseModalities)
        assertNotNull(message.setup.sessionResumption)
    }

    @Test
    fun testRealtimeInputAudioDeserialization() {
        val rawJson = loadFixture("ws_client_realtime_audio.json")
        val message = json.decodeFromString<LiveClientMessage.RealtimeInputMessage>(rawJson)

        val audio = message.realtimeInput.audio
        assertNotNull(audio)
        assertEquals("audio/pcm;rate=16000", audio?.mimeType)
        assertTrue(audio?.data?.isNotEmpty() == true)

        // Verify round-trip
        val encoded = json.encodeToString(LiveClientMessage.RealtimeInputMessage.serializer(), message)
        val decodedAgain = json.decodeFromString<LiveClientMessage.RealtimeInputMessage>(encoded)
        assertEquals(message, decodedAgain)
    }

    @Test
    fun testRealtimeInputStreamEndDeserialization() {
        val rawJson = loadFixture("ws_client_realtime_stream_end.json")
        val message = json.decodeFromString<LiveClientMessage.RealtimeInputMessage>(rawJson)

        assertEquals(true, message.realtimeInput.audioStreamEnd)
    }

    @Test
    fun testServerContentAudioDeserialization() {
        val rawJson = loadFixture("ws_server_content_audio.json")
        val message = json.decodeFromString<LiveServerMessage.ServerContentMessage>(rawJson)

        val parts = message.serverContent.modelTurn?.parts
        assertNotNull(parts)
        assertEquals(1, parts?.size)
        val inlineData = parts?.firstOrNull()?.inlineData
        assertNotNull(inlineData)
        assertEquals("audio/pcm;rate=24000", inlineData?.mimeType)
        assertEquals(false, message.serverContent.turnComplete)
        assertEquals(false, message.serverContent.interrupted)
    }

    @Test
    fun testServerContentTranslateTranscriptsDeserialization() {
        val rawJson = loadFixture("ws_server_content_translate_transcripts.json")
        val message = json.decodeFromString<LiveServerMessage.ServerContentMessage>(rawJson)

        val serverContent = message.serverContent
        assertNotNull(serverContent.modelTurn)
        val inlineData = serverContent.modelTurn?.parts?.firstOrNull()?.inlineData
        assertNotNull(inlineData)
        assertEquals("audio/pcm;rate=24000", inlineData?.mimeType)

        assertNotNull(serverContent.inputTranscription)
        assertEquals("Hello world, this is a test.", serverContent.inputTranscription?.text)
        assertEquals("en", serverContent.inputTranscription?.languageCode)

        assertNotNull(serverContent.outputTranscription)
        assertEquals("Cześć świecie, to jest test.", serverContent.outputTranscription?.text)
        assertEquals("pl", serverContent.outputTranscription?.languageCode)

        assertEquals(true, serverContent.turnComplete)
        assertEquals(false, serverContent.interrupted)

        // Verify round-trip
        val encoded = json.encodeToString(LiveServerMessage.ServerContentMessage.serializer(), message)
        val decodedAgain = json.decodeFromString<LiveServerMessage.ServerContentMessage>(encoded)
        assertEquals(message, decodedAgain)
    }

    @Test
    fun testGoAwayDeserialization() {
        val rawJson = loadFixture("ws_server_go_away.json")
        val message = json.decodeFromString<LiveServerMessage.GoAwayMessage>(rawJson)

        assertEquals("30s", message.goAway.timeLeft)

        val encoded = json.encodeToString(LiveServerMessage.GoAwayMessage.serializer(), message)
        val decodedAgain = json.decodeFromString<LiveServerMessage.GoAwayMessage>(encoded)
        assertEquals(message, decodedAgain)
    }

    @Test
    fun testSessionResumptionUpdateDeserialization() {
        val rawJson = loadFixture("ws_server_session_resumption_update.json")
        val message = json.decodeFromString<LiveServerMessage.SessionResumptionUpdateMessage>(rawJson)

        val update = message.sessionResumptionUpdate
        assertEquals(true, update.resumable)
        assertEquals("session_handle_xyz789_resumed", update.newHandle)
        assertEquals(42, update.lastConsumedClientMessageIndex)

        val encoded = json.encodeToString(LiveServerMessage.SessionResumptionUpdateMessage.serializer(), message)
        val decodedAgain = json.decodeFromString<LiveServerMessage.SessionResumptionUpdateMessage>(encoded)
        assertEquals(message, decodedAgain)
    }
}
