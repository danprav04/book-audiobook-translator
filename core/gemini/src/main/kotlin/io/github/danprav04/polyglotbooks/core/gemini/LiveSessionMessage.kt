package io.github.danprav04.polyglotbooks.core.gemini

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Top-level sealed interface for messages sent from the client to the Gemini Live WebSocket session.
 */
@Serializable(with = LiveClientMessageSerializer::class)
sealed interface LiveClientMessage {

    /**
     * Client setup message sent once upon establishing the WebSocket connection.
     */
    @Serializable
    @SerialName("setup")
    data class SetupMessage(
        val setup: SetupConfig
    ) : LiveClientMessage

    /**
     * Realtime streaming input frame containing audio PCM chunks, stream markers, or activity signals.
     */
    @Serializable
    @SerialName("realtimeInput")
    data class RealtimeInputMessage(
        val realtimeInput: RealtimeInputData
    ) : LiveClientMessage

    /**
     * Direct client content message (e.g. text/turns sent from client).
     */
    @Serializable
    @SerialName("clientContent")
    data class ClientContentMessage(
        val clientContent: ClientContentData
    ) : LiveClientMessage
}

/**
 * Custom polymorphic serializer for [LiveClientMessage] matching Gemini Live WebSocket protocol frames.
 */
object LiveClientMessageSerializer : JsonContentPolymorphicSerializer<LiveClientMessage>(LiveClientMessage::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<LiveClientMessage> {
        val jsonObject = element as? JsonObject
            ?: throw SerializationException("LiveClientMessage must be a JSON object, but was: $element")
        return when {
            "setup" in jsonObject -> LiveClientMessage.SetupMessage.serializer()
            "realtimeInput" in jsonObject -> LiveClientMessage.RealtimeInputMessage.serializer()
            "clientContent" in jsonObject -> LiveClientMessage.ClientContentMessage.serializer()
            else -> throw SerializationException("Unknown LiveClientMessage envelope: $element")
        }
    }
}

/**
 * Setup configuration for the Live session.
 */
@Serializable
data class SetupConfig(
    val model: String,
    val generationConfig: GenerationConfig? = null,
    val systemInstruction: ContentTurn? = null,
    val inputAudioTranscription: JsonObject? = null,
    val outputAudioTranscription: JsonObject? = null,
    val sessionResumption: SessionResumptionConfig? = null,
    val contextWindowCompression: ContextWindowCompressionConfig? = null,
    val realtimeInputConfig: RealtimeInputConfig? = null
)

/**
 * Generation parameters for Live session.
 */
@Serializable
data class GenerationConfig(
    val responseModalities: List<String>? = null,
    val translationConfig: TranslationConfig? = null,
    val speechConfig: SpeechConfig? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null
)

/**
 * Live speech-to-speech translation parameters.
 */
@Serializable
data class TranslationConfig(
    val targetLanguageCode: String,
    val echoTargetLanguage: Boolean = false
)

/**
 * Voice selection / speech synthesis config.
 */
@Serializable
data class SpeechConfig(
    val voiceConfig: VoiceConfig? = null
)

@Serializable
data class VoiceConfig(
    val prebuiltVoiceConfig: PrebuiltVoiceConfig? = null
)

@Serializable
data class PrebuiltVoiceConfig(
    val voiceName: String
)

/**
 * Resumption configuration in setup message.
 */
@Serializable
data class SessionResumptionConfig(
    val handle: String? = null
)

/**
 * Automatic sliding window context compression configuration.
 */
@Serializable
data class ContextWindowCompressionConfig(
    val slidingWindow: JsonObject? = null
)

/**
 * Realtime input detection settings.
 */
@Serializable
data class RealtimeInputConfig(
    val automaticActivityDetection: AutomaticActivityDetectionConfig? = null
)

@Serializable
data class AutomaticActivityDetectionConfig(
    val disabled: Boolean? = null
)

/**
 * Realtime input payload sent by client. Supports modern `audio` blob as well as legacy `mediaChunks`.
 */
@Serializable
data class RealtimeInputData(
    val audio: Blob? = null,
    val mediaChunks: List<Blob>? = null,
    val audioStreamEnd: Boolean? = null,
    val activityStart: JsonObject? = null,
    val activityEnd: JsonObject? = null
)

/**
 * Binary data blob with MIME type.
 */
@Serializable
data class Blob(
    val mimeType: String = "",
    val data: String = ""
)

/**
 * Multi-turn client content.
 */
@Serializable
data class ClientContentData(
    val turns: List<ContentTurn>? = null,
    val turnComplete: Boolean? = null
)

@Serializable
data class ContentTurn(
    val role: String? = null,
    val parts: List<Part> = emptyList()
)

@Serializable
data class Part(
    val text: String? = null,
    val inlineData: Blob? = null
)

/**
 * Top-level sealed interface for messages received from the Gemini Live WebSocket session.
 */
@Serializable(with = LiveServerMessageSerializer::class)
sealed interface LiveServerMessage {

    /**
     * Server response content including model audio turns, transcriptions, and completion indicators.
     */
    @Serializable
    @SerialName("serverContent")
    data class ServerContentMessage(
        val serverContent: ServerContentData
    ) : LiveServerMessage

    /**
     * GoAway warning from the server indicating connection termination within timeLeft.
     */
    @Serializable
    @SerialName("goAway")
    data class GoAwayMessage(
        val goAway: GoAwayData
    ) : LiveServerMessage

    /**
     * Session resumption token update.
     */
    @Serializable
    @SerialName("sessionResumptionUpdate")
    data class SessionResumptionUpdateMessage(
        val sessionResumptionUpdate: SessionResumptionUpdateData
    ) : LiveServerMessage

    /**
     * Setup complete acknowledgement frame.
     */
    @Serializable
    @SerialName("setupComplete")
    data class SetupCompleteMessage(
        val setupComplete: JsonObject? = null
    ) : LiveServerMessage

    /**
     * Error frame returned by the server when setup or generation fails.
     */
    @Serializable
    @SerialName("error")
    data class ErrorMessage(
        val error: LiveErrorData
    ) : LiveServerMessage

    /**
     * Token usage metadata frame returned by the server.
     */
    @Serializable
    @SerialName("usageMetadata")
    data class UsageMetadataMessage(
        val usageMetadata: UsageMetadata? = null
    ) : LiveServerMessage

    /**
     * Empty heartbeat/acknowledgement frame (e.g. `{}`) safely ignored by callers.
     */
    @Serializable
    data object IgnoredMessage : LiveServerMessage
}

/**
 * Custom polymorphic serializer for [LiveServerMessage] matching Gemini Live WebSocket protocol frames.
 */
object LiveServerMessageSerializer : JsonContentPolymorphicSerializer<LiveServerMessage>(LiveServerMessage::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<LiveServerMessage> {
        val jsonObject = element as? JsonObject
            ?: throw SerializationException("LiveServerMessage must be a JSON object, but was: $element")
        return when {
            "serverContent" in jsonObject -> LiveServerMessage.ServerContentMessage.serializer()
            "goAway" in jsonObject -> LiveServerMessage.GoAwayMessage.serializer()
            "sessionResumptionUpdate" in jsonObject -> LiveServerMessage.SessionResumptionUpdateMessage.serializer()
            "setupComplete" in jsonObject -> LiveServerMessage.SetupCompleteMessage.serializer()
            "error" in jsonObject -> LiveServerMessage.ErrorMessage.serializer()
            "usageMetadata" in jsonObject -> LiveServerMessage.UsageMetadataMessage.serializer()
            jsonObject.isEmpty() -> LiveServerMessage.IgnoredMessage.serializer()
            else -> throw SerializationException("Unknown LiveServerMessage envelope: $element")
        }
    }
}

/**
 * Content payload generated by the server.
 */
@Serializable
data class ServerContentData(
    val modelTurn: ModelTurn? = null,
    val inputTranscription: TranscriptionData? = null,
    val interimInputTranscription: TranscriptionData? = null,
    val outputTranscription: TranscriptionData? = null,
    val turnComplete: Boolean? = null,
    val interrupted: Boolean? = null,
    val generationComplete: Boolean? = null
)

@Serializable
data class ModelTurn(
    val parts: List<Part> = emptyList()
)

/**
 * Speech transcript data.
 */
@Serializable
data class TranscriptionData(
    val text: String = "",
    val languageCode: String? = null,
    val finished: Boolean? = null
)

/**
 * GoAway frame data.
 */
@Serializable
data class GoAwayData(
    val timeLeft: String? = null
)

/**
 * Resumption update data from the server.
 */
@Serializable
data class SessionResumptionUpdateData(
    val resumable: Boolean = false,
    val newHandle: String? = null,
    val lastConsumedClientMessageIndex: Int? = null
)

/**
 * Structured error payload returned by the Gemini Live server.
 */
@Serializable
data class LiveErrorData(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null
)

/**
 * Token usage metadata reported by the Gemini Live server.
 */
@Serializable
data class UsageMetadata(
    val promptTokenCount: Int? = null,
    val responseTokenCount: Int? = null,
    val totalTokenCount: Int? = null
)
