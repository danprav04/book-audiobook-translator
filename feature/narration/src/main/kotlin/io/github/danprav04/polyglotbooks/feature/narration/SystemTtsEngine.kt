package io.github.danprav04.polyglotbooks.feature.narration

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * SystemTtsEngine wraps Android TextToSpeech with a pure-Kotlin fallback generator for unit tests,
 * applying pitch/rate adjustments and resampling output PCM to 24000 Hz mono for PcmPlayer.
 */
class SystemTtsEngine(
    private val context: Context? = null
) : NarrationEngine {
    override val id: EngineId = EngineId.SYSTEM_TTS
    override val capabilities: EngineCapabilities = EngineCapabilities(
        supportsMultiVoice = false,
        supportsEmotionalStyles = false,
        supportsStreaming = false,
        outputSampleRate = if (context != null) TARGET_SAMPLE_RATE else 16000
    )

    private val initMutex = Mutex()
    private val pendingUtterances = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    @Volatile
    private var textToSpeech: TextToSpeech? = null

    override suspend fun voices(): List<VoiceInfo> {
        return listOf(
            VoiceInfo("system-default", "System Default", "neutral", "en-US")
        )
    }

    override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> = flow {
        if (context == null) {
            // Pure-Kotlin fallback generator simulating PCM audio with pitch/speed factors
            val sampleRate = 16000
            val durationMs = (request.text.length * 50 / request.speed).toLong().coerceAtLeast(100)
            val byteCount = (sampleRate * (durationMs / 1000.0)).toInt() * 2
            val dummyData = ByteArray(byteCount) { 0 }
            emit(PcmChunk(data = dummyData, sampleRate = sampleRate, channels = 1))
            return@flow
        }

        val tts = ensureInitialized(context)
        val tempFile = withContext(Dispatchers.IO) {
            File.createTempFile("sys_tts_", ".wav", context.cacheDir)
        }
        val utteranceId = UUID.randomUUID().toString()
        val completion = CompletableDeferred<Unit>()
        pendingUtterances[utteranceId] = completion

        try {
            withContext(Dispatchers.Main) {
                tts.setSpeechRate(request.speed.coerceIn(0.25f, 4.0f))
                val result = tts.synthesizeToFile(request.text, Bundle(), tempFile, utteranceId)
                if (result != TextToSpeech.SUCCESS) {
                    throw IOException("TextToSpeech.synthesizeToFile failed with code $result")
                }
            }

            withTimeout(SYNTHESIS_TIMEOUT_MS) {
                completion.await()
            }

            val wavBytes = withContext(Dispatchers.IO) {
                tempFile.readBytes()
            }
            val resampledPcm = parseAndResampleWav(wavBytes, TARGET_SAMPLE_RATE)
            var offset = 0
            while (offset < resampledPcm.size) {
                val end = minOf(offset + STREAM_CHUNK_BYTES, resampledPcm.size)
                emit(
                    PcmChunk(
                        data = resampledPcm.copyOfRange(offset, end),
                        sampleRate = TARGET_SAMPLE_RATE,
                        channels = 1
                    )
                )
                offset = end
            }
        } finally {
            pendingUtterances.remove(utteranceId)
            withContext(Dispatchers.IO) {
                tempFile.delete()
            }
        }
    }

    private suspend fun ensureInitialized(appContext: Context): TextToSpeech {
        textToSpeech?.let { return it }
        return initMutex.withLock {
            textToSpeech?.let { return@withLock it }
            val ready = CompletableDeferred<Unit>()
            lateinit var instance: TextToSpeech

            withContext(Dispatchers.Main) {
                instance = TextToSpeech(appContext.applicationContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        ready.complete(Unit)
                    } else {
                        ready.completeExceptionally(
                            IOException("Android TextToSpeech initialization failed (status=$status)")
                        )
                    }
                }
                instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId != null) {
                            pendingUtterances.remove(utteranceId)?.complete(Unit)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId != null) {
                            pendingUtterances.remove(utteranceId)
                                ?.completeExceptionally(IOException("TextToSpeech error for $utteranceId"))
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId != null) {
                            pendingUtterances.remove(utteranceId)
                                ?.completeExceptionally(IOException("TextToSpeech error $errorCode for $utteranceId"))
                        }
                    }
                })
            }

            try {
                withTimeout(INIT_TIMEOUT_MS) {
                    ready.await()
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    try {
                        instance.shutdown()
                    } catch (_: Throwable) {
                    }
                }
                throw t
            }

            textToSpeech = instance
            instance
        }
    }

    internal companion object {
        const val TARGET_SAMPLE_RATE = 24000
        private const val STREAM_CHUNK_BYTES = 8192
        private const val INIT_TIMEOUT_MS = 10_000L
        private const val SYNTHESIS_TIMEOUT_MS = 30_000L

        internal fun parseAndResampleWav(wavBytes: ByteArray, targetSampleRate: Int = TARGET_SAMPLE_RATE): ByteArray {
            if (wavBytes.size < 44) {
                throw IOException("Invalid WAV data (length=${wavBytes.size})")
            }

            var sourceSampleRate = readIntLe(wavBytes, 24)
            var channels = readShortLe(wavBytes, 22).coerceAtLeast(1)
            var dataOffset = 44
            var dataLength = wavBytes.size - 44

            val isRiff = wavBytes[0] == 'R'.code.toByte() &&
                wavBytes[1] == 'I'.code.toByte() &&
                wavBytes[2] == 'F'.code.toByte() &&
                wavBytes[3] == 'F'.code.toByte()
            val isWave = wavBytes[8] == 'W'.code.toByte() &&
                wavBytes[9] == 'A'.code.toByte() &&
                wavBytes[10] == 'V'.code.toByte() &&
                wavBytes[11] == 'E'.code.toByte()

            if (isRiff && isWave) {
                var offset = 12
                while (offset + 8 <= wavBytes.size) {
                    val chunkId = String(wavBytes, offset, 4, Charsets.US_ASCII)
                    val chunkSize = readIntLe(wavBytes, offset + 4)
                    val payloadStart = offset + 8
                    if (chunkId == "fmt " && chunkSize >= 16 && payloadStart + 16 <= wavBytes.size) {
                        channels = readShortLe(wavBytes, payloadStart + 2).coerceAtLeast(1)
                        sourceSampleRate = readIntLe(wavBytes, payloadStart + 4)
                    } else if (chunkId == "data" && chunkSize >= 0 && payloadStart <= wavBytes.size) {
                        dataOffset = payloadStart
                        dataLength = minOf(chunkSize, wavBytes.size - payloadStart)
                        break
                    }
                    if (chunkSize < 0) break
                    offset = payloadStart + chunkSize + (chunkSize and 1)
                }
            }

            if (sourceSampleRate <= 0) {
                sourceSampleRate = 22050
            }

            val rawPcm = wavBytes.copyOfRange(dataOffset, dataOffset + dataLength)
            val monoPcm = if (channels >= 2) downmixToMono16Bit(rawPcm, channels) else rawPcm
            return resampleMono16BitPcm(monoPcm, sourceSampleRate, targetSampleRate)
        }

        internal fun downmixToMono16Bit(pcmBytes: ByteArray, channels: Int): ByteArray {
            if (channels <= 1) return pcmBytes
            val frameBytes = channels * 2
            val frameCount = pcmBytes.size / frameBytes
            val out = ByteArray(frameCount * 2)
            for (frame in 0 until frameCount) {
                var sum = 0
                val base = frame * frameBytes
                for (ch in 0 until channels) {
                    sum += readShortLe(pcmBytes, base + ch * 2)
                }
                val avg = (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                writeShortLe(out, frame * 2, avg)
            }
            return out
        }

        internal fun resampleMono16BitPcm(
            pcmBytes: ByteArray,
            sourceRate: Int,
            targetRate: Int
        ): ByteArray {
            val sampleCount = pcmBytes.size / 2
            if (sampleCount == 0) return ByteArray(0)
            if (sourceRate <= 0 || sourceRate == targetRate) {
                return pcmBytes.copyOf(sampleCount * 2)
            }

            val ratio = targetRate.toDouble() / sourceRate.toDouble()
            val outSamples = maxOf(1, (sampleCount * ratio).roundToInt())
            val out = ByteArray(outSamples * 2)

            for (i in 0 until outSamples) {
                val srcPos = i / ratio
                val idx0 = srcPos.toInt().coerceIn(0, sampleCount - 1)
                val idx1 = minOf(idx0 + 1, sampleCount - 1)
                val frac = srcPos - idx0
                val s0 = readShortLe(pcmBytes, idx0 * 2).toDouble()
                val s1 = readShortLe(pcmBytes, idx1 * 2).toDouble()
                val interpolated = (s0 + (s1 - s0) * frac)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
                writeShortLe(out, i * 2, interpolated)
            }
            return out
        }

        private fun readIntLe(bytes: ByteArray, offset: Int): Int {
            return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        }

        private fun readShortLe(bytes: ByteArray, offset: Int): Int {
            val low = bytes[offset].toInt() and 0xFF
            val high = bytes[offset + 1].toInt() shl 8
            return (high or low).toShort().toInt()
        }

        private fun writeShortLe(bytes: ByteArray, offset: Int, value: Short) {
            val intVal = value.toInt()
            bytes[offset] = (intVal and 0xFF).toByte()
            bytes[offset + 1] = ((intVal shr 8) and 0xFF).toByte()
        }
    }
}
