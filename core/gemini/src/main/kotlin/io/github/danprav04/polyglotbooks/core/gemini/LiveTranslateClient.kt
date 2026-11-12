package io.github.danprav04.polyglotbooks.core.gemini

import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Result data from a live translation turn.
 */
data class TranslationOutput(
    val audioChunk: PcmChunk? = null,
    val sourceTranscript: String? = null,
    val targetTranscript: String? = null,
    val isTurnComplete: Boolean = false
)

/**
 * High-level client for live speech-to-speech translation streams.
 */
interface LiveTranslateClient {
    val translationStream: Flow<TranslationOutput>
    suspend fun start(
        apiKey: String,
        targetLanguageCode: String,
        echoTargetLanguage: Boolean = false,
        model: String = DefaultModels.get(ModelRole.LIVE_TRANSLATE)
    )
    suspend fun sendAudio(chunk: PcmChunk)
    suspend fun stop()
}

/**
 * Stub implementation of [LiveTranslateClient].
 */
class StubLiveTranslateClient(
    private val liveSession: LiveSession = StubLiveSession()
) : LiveTranslateClient {

    private val _translationStream = MutableSharedFlow<TranslationOutput>(extraBufferCapacity = 64)
    override val translationStream: Flow<TranslationOutput> = _translationStream.asSharedFlow()

    private var running = false

    override suspend fun start(
        apiKey: String,
        targetLanguageCode: String,
        echoTargetLanguage: Boolean,
        model: String
    ) {
        val setup = SetupConfig(
            model = model,
            generationConfig = GenerationConfig(
                responseModalities = listOf("AUDIO"),
                translationConfig = TranslationConfig(
                    targetLanguageCode = targetLanguageCode,
                    echoTargetLanguage = echoTargetLanguage
                )
            )
        )
        liveSession.connect(apiKey, setup)
        running = true
    }

    override suspend fun sendAudio(chunk: PcmChunk) {
        if (!running) return
        val base64 = android.util.Base64.encodeToString(chunk.data, android.util.Base64.NO_WRAP)
        val msg = LiveClientMessage.RealtimeInputMessage(
            RealtimeInputData(
                audio = Blob(
                    mimeType = "audio/pcm;rate=${chunk.sampleRate}",
                    data = base64
                )
            )
        )
        liveSession.send(msg)
    }

    override suspend fun stop() {
        running = false
        liveSession.disconnect()
    }
}
