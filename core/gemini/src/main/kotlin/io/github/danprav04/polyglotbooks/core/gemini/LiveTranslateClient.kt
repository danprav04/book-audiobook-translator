package io.github.danprav04.polyglotbooks.core.gemini

import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

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
 * Production implementation of [LiveTranslateClient] wrapping [LiveSession] and [LiveSessionFactory].
 */
class DefaultLiveTranslateClient(
    private val sessionFactory: LiveSessionFactory = DefaultLiveSessionFactory()
) : LiveTranslateClient {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var liveSession: LiveSession? = null

    private val _translationStream = MutableSharedFlow<TranslationOutput>(extraBufferCapacity = 64)
    override val translationStream: Flow<TranslationOutput> = _translationStream.asSharedFlow()

    private var isRunning = false

    override suspend fun start(
        apiKey: String,
        targetLanguageCode: String,
        echoTargetLanguage: Boolean,
        model: String
    ) {
        val session = sessionFactory.create()
        liveSession = session

        val setup = SetupConfig(
            model = model,
            generationConfig = GenerationConfig(
                responseModalities = listOf("AUDIO"),
                inputAudioTranscription = JsonObject(emptyMap()),
                outputAudioTranscription = JsonObject(emptyMap()),
                translationConfig = TranslationConfig(
                    targetLanguageCode = targetLanguageCode,
                    echoTargetLanguage = echoTargetLanguage
                )
            ),
            sessionResumption = SessionResumptionConfig(),
            contextWindowCompression = ContextWindowCompressionConfig(
                slidingWindow = JsonObject(emptyMap())
            )
        )

        scope.launch {
            try {
                session.incomingMessages.collect { serverMsg ->
                    val output = parseServerMessage(serverMsg)
                    if (output != null) {
                        _translationStream.emit(output)
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e("LiveTranslateClient", "Live session incoming messages failed", e)
                isRunning = false
            }
        }

        session.connect(apiKey, setup)
        isRunning = true
    }

    override suspend fun sendAudio(chunk: PcmChunk) {
        val session = liveSession ?: return
        if (!session.isConnected) return

        val base64 = android.util.Base64.encodeToString(chunk.data, android.util.Base64.NO_WRAP)
        val msg = LiveClientMessage.RealtimeInputMessage(
            RealtimeInputData(
                audio = Blob(
                    mimeType = "audio/pcm;rate=${chunk.sampleRate}",
                    data = base64
                )
            )
        )
        session.send(msg)
    }

    override suspend fun stop() {
        isRunning = false
        liveSession?.disconnect()
        liveSession = null
    }

    private fun parseServerMessage(serverMsg: LiveServerMessage): TranslationOutput? {
        return when (serverMsg) {
            is LiveServerMessage.ServerContentMessage -> {
                val content = serverMsg.serverContent
                var pcmChunk: PcmChunk? = null

                content.modelTurn?.parts?.forEach { part ->
                    part.inlineData?.let { blob ->
                        if (blob.mimeType.startsWith("audio/")) {
                            try {
                                val decodedBytes = android.util.Base64.decode(blob.data, android.util.Base64.DEFAULT)
                                pcmChunk = PcmChunk(
                                    data = decodedBytes,
                                    sampleRate = 24000,
                                    channels = 1
                                )
                            } catch (e: Exception) {
                                // Ignore decode error
                            }
                        }
                    }
                }

                val sourceText = content.inputTranscription?.text ?: content.interimInputTranscription?.text
                val targetText = content.outputTranscription?.text
                val isComplete = content.turnComplete == true || content.generationComplete == true

                if (pcmChunk != null || sourceText != null || targetText != null || isComplete) {
                    TranslationOutput(
                        audioChunk = pcmChunk,
                        sourceTranscript = sourceText,
                        targetTranscript = targetText,
                        isTurnComplete = isComplete
                    )
                } else {
                    null
                }
            }
            else -> null
        }
    }
}
