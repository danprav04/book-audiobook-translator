package io.github.danprav04.polyglotbooks.feature.reader

import io.github.danprav04.polyglotbooks.core.audio.AudioTrackAdapter
import io.github.danprav04.polyglotbooks.core.audio.AudioTrackAdapterFactory
import io.github.danprav04.polyglotbooks.core.audio.AudioTrackPcmPlayer
import io.github.danprav04.polyglotbooks.core.gemini.DefaultLiveSessionFactory
import io.github.danprav04.polyglotbooks.core.gemini.GeminiQuotaTracker
import io.github.danprav04.polyglotbooks.core.gemini.LiveClientMessage
import io.github.danprav04.polyglotbooks.core.gemini.LiveServerMessage
import io.github.danprav04.polyglotbooks.core.gemini.LiveSession
import io.github.danprav04.polyglotbooks.core.gemini.LiveSessionFactory
import io.github.danprav04.polyglotbooks.core.gemini.SetupConfig
import io.github.danprav04.polyglotbooks.core.model.DiagnosticLogBuffer
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import io.github.danprav04.polyglotbooks.core.model.PunctuationDialogueHelper
import io.github.danprav04.polyglotbooks.feature.narration.EngineCapabilities
import io.github.danprav04.polyglotbooks.feature.narration.EngineId
import io.github.danprav04.polyglotbooks.feature.narration.SynthesisRequest
import io.github.danprav04.polyglotbooks.feature.narration.VoiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import io.github.danprav04.polyglotbooks.feature.narration.NarrationEngine as FeatureNarrationEngine

class OrvLiveApiSmokeTest {

    @Before
    fun setUp() {
        DiagnosticLogBuffer.clear()
    }

    @Test
    fun `real Gemini Live API narrates 10 representative ORV paragraphs cleanly and writes playable WAVs`() {
        val apiKey = resolveGeminiApiKey()
        Assume.assumeTrue(
            "Set GEMINI_API_KEY env var or -Dgemini.api.key to run live API smoke test",
            !apiKey.isNullOrBlank()
        )

        val sampleParagraphs = extractRepresentativeOrvParagraphs()
        assertEquals(10, sampleParagraphs.size)

        val tempDir = Files.createTempDirectory("orv-live-smoke-cache").toFile()
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val realFactory = createRealDefaultLiveSessionFactory()
            val wsConnectionsCreated = AtomicInteger(0)
            val turnLatenciesMs = CopyOnWriteArrayList<Long>()
            val instrumentedFactory = object : LiveSessionFactory {
                override fun create(): LiveSession = create(reconnectOnGoAway = true)
                override fun create(reconnectOnGoAway: Boolean): LiveSession {
                    wsConnectionsCreated.incrementAndGet()
                    val delegate = realFactory.create(reconnectOnGoAway)
                    var currentTurnSendMs = 0L
                    var recordedForCurrentTurn = false
                    return object : LiveSession {
                        override val incomingMessages: Flow<LiveServerMessage> =
                            delegate.incomingMessages.onEach { msg ->
                                if (msg is LiveServerMessage.ServerContentMessage) {
                                    val c = msg.serverContent
                                    if (!recordedForCurrentTurn && currentTurnSendMs > 0L &&
                                        (c.turnComplete == true || c.generationComplete == true)
                                    ) {
                                        recordedForCurrentTurn = true
                                        turnLatenciesMs += (System.currentTimeMillis() - currentTurnSendMs).coerceAtLeast(0L)
                                    }
                                }
                            }
                        override val isConnected: Boolean
                            get() = delegate.isConnected

                        override suspend fun connect(apiKey: String, setup: SetupConfig) {
                            delegate.connect(apiKey, setup)
                        }

                        override suspend fun send(message: LiveClientMessage) {
                            if (message is LiveClientMessage.ClientContentMessage) {
                                currentTurnSendMs = System.currentTimeMillis()
                                recordedForCurrentTurn = false
                            }
                            delegate.send(message)
                        }

                        override suspend fun disconnect() {
                            delegate.disconnect()
                        }
                    }
                }
            }

            val adapterFactory = RecordingWavAdapterFactory()
            val fallbackCounter = AtomicInteger(0)
            val trackingFallback = object : FeatureNarrationEngine {
                override val id: EngineId = EngineId.SYSTEM_TTS
                override val capabilities = EngineCapabilities(
                    supportsMultiVoice = false,
                    supportsEmotionalStyles = false,
                    supportsStreaming = false,
                    outputSampleRate = 24_000
                )
                override suspend fun voices(): List<VoiceInfo> = emptyList()
                override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> = flow {
                    fallbackCounter.incrementAndGet()
                    emit(PcmChunk(ByteArray(4_800), sampleRate = 24_000, channels = 1))
                }
            }

            val settingsRepo = FakeSettingsRepository().apply {
                runBlocking {
                    setReaderNarrationEngine(NarrationEngine.GEMINI_LIVE.name)
                    setDefaultNarratorVoiceId("Charon")
                }
            }

            val manager = ReaderNarrationSessionManager(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao(emptyList()),
                settingsRepository = settingsRepo,
                secureKeyStore = FakeSecureKeyStore(apiKey),
                liveSessionFactory = instrumentedFactory,
                quotaTracker = GeminiQuotaTracker(),
                applicationScope = ioScope
            ).apply {
                ioDispatcher = Dispatchers.IO
                testDiskCache = TranslationDiskCache(tempDir)
                playerFactory = { AudioTrackPcmPlayer(adapterFactory) }
                systemTtsFactory = { trackingFallback }
            }

            runBlocking {
                // Allow observeSettings() to apply GEMINI_LIVE before startOrResume.
                delay(100)
                val runStartMs = System.currentTimeMillis()
                manager.startOrResume(
                    bookId = "orv-live-smoke",
                    bookTitle = "Omniscient Reader's Viewpoint",
                    paragraphs = sampleParagraphs.mapIndexed { idx, (_, text) ->
                        ReaderParagraph(idx, "smoke_$idx", text, "epubcfi(/6/6!/4/2/${idx + 1}:0)")
                    },
                    chapters = emptyList()
                )

                val deadlineMs = System.currentTimeMillis() + 180_000L
                while (System.currentTimeMillis() < deadlineMs) {
                    val state = manager.sessionState.value
                    if (!state.isPlaying) {
                        break
                    }
                    delay(200)
                }

                val smokeDir = resolveSmokeOutputDir()
                smokeDir.mkdirs()

                val entries = DiagnosticLogBuffer.recentEntries(200)
                val wireParseErrors = entries.count { it.level == "ERROR" && it.tag == "OkHttpLiveSession" }
                val engineFallbacksLogged = entries.count {
                    it.message.contains("falling back to TTS", ignoreCase = true) ||
                        it.message.contains("Switched to device TTS fallback", ignoreCase = true)
                }
                val fallbackUsedOverall = fallbackCounter.get() > 0 || engineFallbacksLogged > 0

                val combinedPcm = ByteArrayOutputStream()
                var spokenTurnIdx = 0
                println("=== ORV Live API Smoke Diagnostic Table (totalElapsedMs=${System.currentTimeMillis() - runStartMs}) ===")
                println(
                    String.format(
                        "%-3s | %-6s | %-5s | %-9s | %-9s | %-6s | %-9s | %-9s | %-8s | %-7s | %-9s",
                        "#", "Chap", "Chars", "Kind", "LatencyMs", "WSConn", "WireErrs", "AudioMs", "MaxSilMs", "Glitch?", "Fallback?"
                    )
                )

                sampleParagraphs.forEachIndexed { idx, (chapLabel, text) ->
                    val kind = PunctuationDialogueHelper.resolve(text).kind
                    val utterance = adapterFactory.utterances.getOrNull(idx)
                    val pcm = utterance?.pcmBytes ?: ByteArray(0)
                    if (pcm.isNotEmpty()) {
                        combinedPcm.write(pcm)
                        writeWavFile(File(smokeDir, "para_%02d.wav".format(idx + 1)), pcm)
                    }
                    val audioMs = (pcm.size.toLong() * 1000L) / (24_000L * 2L)
                    val maxSilMs = measureMaxInternalSilenceMs(pcm)
                    val glitch = detectLocalPcmGlitch(pcm)
                    val latencyMs = if (kind == PunctuationDialogueHelper.PunctuationKind.SILENT_DIVIDER) {
                        0L
                    } else {
                        turnLatenciesMs.getOrNull(spokenTurnIdx++) ?: -1L
                    }
                    println(
                        String.format(
                            "%-3d | %-6s | %-5d | %-9s | %-9d | %-6d | %-9d | %-9d | %-8d | %-7s | %-9s",
                            idx + 1,
                            chapLabel,
                            text.length,
                            kind.name.take(9),
                            latencyMs,
                            wsConnectionsCreated.get(),
                            wireParseErrors,
                            audioMs,
                            maxSilMs,
                            glitch,
                            fallbackUsedOverall
                        )
                    )
                }

                val combinedBytes = combinedPcm.toByteArray()
                writeWavFile(File(smokeDir, "combined_orv_sample.wav"), combinedBytes)

                assertEquals("Expected zero ERROR/OkHttpLiveSession parse errors", 0, wireParseErrors)
                assertNull("Expected no session errorMessage", manager.sessionState.value.errorMessage)
                assertNull("Expected no fallback statusMessage", manager.sessionState.value.statusMessage)
                assertEquals("Expected zero system TTS fallbacks", 0, fallbackCounter.get())
                assertEquals("Expected zero engine fallback log lines", 0, engineFallbacksLogged)
                assertTrue("Expected at least 1 Live WebSocket connection", wsConnectionsCreated.get() >= 1)
                assertEquals("All 10 paragraphs must complete", sampleParagraphs.size, adapterFactory.utterances.size)
                assertTrue("Combined WAV must contain audio", combinedBytes.size > 4800)
            }
        } finally {
            ioScope.cancel()
            tempDir.deleteRecursively()
        }
    }

    private fun resolveGeminiApiKey(): String? =
        System.getProperty("gemini.api.key")?.trim()?.takeIf { it.isNotEmpty() }
            ?: System.getenv("GEMINI_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }

    private fun findOrvEpubFile(): File? {
        val candidates = listOfNotNull(
            System.getProperty("polyglot.test.epub")?.trim()?.takeIf { it.isNotEmpty() },
            System.getenv("POLYGLOT_TEST_EPUB")?.trim()?.takeIf { it.isNotEmpty() },
            "/mnt/c/Users/Daniel/Downloads/Omniscient Reader's Viewpoint.epub",
            File(System.getProperty("user.home", "."), "Downloads/Omniscient Reader's Viewpoint.epub").absolutePath
        )
        return candidates.map { File(it) }.firstOrNull { it.exists() && it.isFile }
    }

    private fun extractRepresentativeOrvParagraphs(): List<Pair<String, String>> {
        val epub = findOrvEpubFile()
        if (epub != null) {
            val book = EbookContentExtractor.extractBook(null, epub.absolutePath, "EPUB")
            fun findIn(chapSuffix: String, predicate: (String) -> Boolean): String? =
                book.chapters.firstOrNull { it.href.endsWith(chapSuffix, ignoreCase = true) }
                    ?.paragraphs
                    ?.firstOrNull(predicate)

            val extracted = listOfNotNull(
                findIn("chap_001.xhtml") { it.contains("childhood") && it.contains("\u2014") }?.let { "Ch1" to it },
                findIn("chap_001.xhtml") { it.contains("tls123\u2014it") }?.let { "Ch1" to it },
                findIn("chap_014.xhtml") { it.contains("honor of the dokkaebi\u2015]") }?.let { "Ch14" to it },
                findIn("chap_015.xhtml") { it == "\u2014\u2013" }?.let { "Ch15" to it },
                findIn("chap_015.xhtml") { it == "[Stream Contract Agreement]" }?.let { "Ch15" to it },
                findIn("chap_015.xhtml") { it.contains("soul pledge\u2015]") }?.let { "Ch15" to it },
                findIn("chap_015.xhtml") { it == "\"10:0.\"" }?.let { "Ch15" to it },
                findIn("chap_015.xhtml") { it.contains("incarnation without a sponsor") }?.let { "Ch15" to it },
                findIn("chap_503.xhtml") { it.contains("Kuwaaaaah---!") }?.let { "Ch503" to it },
                findIn("chap_529.xhtml") { it.contains("Ranvir Khan--") }?.let { "Ch529" to it }
            )
            if (extracted.size == 10) return extracted
        }
        return listOf(
            "Ch1" to "From the end of my childhood all the way to adulthood—a long journey spanning over ten years.",
            "Ch1" to "tls123—it was the author of 'Ways of Survival'.",
            "Ch14" to "[This can't be! I am Dokkaebi Bihyung. If I can't solve this situation, the honor of the dokkaebi―]",
            "Ch15" to "—–",
            "Ch15" to "[Stream Contract Agreement]",
            "Ch15" to "[What do you mean? You can just say that you agree. The stream contract is a soul pledge―]",
            "Ch15" to "\"10:0.\"",
            "Ch15" to "\"I'm an incarnation without a sponsor. When donating to an incarnation without a sponsor, the constellations must pay a huge fee to the dokkaebi. Haven't you already enjoyed a lot thanks to me?\"",
            "Ch503" to "[Kuwaaaaah---! I shall block this!]",
            "Ch529" to "\"Ranvir Khan--\""
        )
    }

    private fun createRealDefaultLiveSessionFactory(): LiveSessionFactory {
        val okHttpClientClass = Class.forName("okhttp3.OkHttpClient")
        val companionClass = Class.forName("io.github.danprav04.polyglotbooks.core.gemini.OkHttpLiveSession\$Companion")
        val companionInstance = Class.forName("io.github.danprav04.polyglotbooks.core.gemini.OkHttpLiveSession")
            .getField("Companion")
            .get(null)
        val defaultClient = companionClass.getMethod("defaultOkHttpClient").invoke(companionInstance)
        return DefaultLiveSessionFactory::class.java
            .getConstructor(okHttpClientClass)
            .newInstance(defaultClient) as LiveSessionFactory
    }

    private fun resolveSmokeOutputDir(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (cwd.name == "reader" && cwd.parentFile?.name == "feature") {
            File(cwd, "build/narration-smoke")
        } else {
            File(cwd, "feature/reader/build/narration-smoke")
        }
    }

    private fun writeWavFile(file: File, pcm24kMono16Bit: ByteArray) {
        val sampleRate = 24_000
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)
        val dataSize = pcm24kMono16Bit.size
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort())
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()
        file.outputStream().use { out ->
            out.write(header)
            out.write(pcm24kMono16Bit)
        }
    }

    private data class RecordedUtterance(
        val pcmBytes: ByteArray,
        val completedAtMs: Long
    )

    private class RecordingWavAdapterFactory : AudioTrackAdapterFactory {
        private var currentUtterance = ByteArrayOutputStream()
        val utterances = CopyOnWriteArrayList<RecordedUtterance>()
        private var trackFramesWritten: Long = 0L
        private val lock = Any()

        override fun create(sampleRate: Int, channels: Int): AudioTrackAdapter {
            val bytesPerFrame = (channels.coerceAtLeast(1)) * 2
            return object : AudioTrackAdapter {
                override fun write(data: ByteArray, offset: Int, size: Int): Int {
                    synchronized(lock) {
                        currentUtterance.write(data, offset, size)
                        trackFramesWritten += (size / bytesPerFrame).toLong()
                    }
                    return size
                }

                override fun play() {}
                override fun pause() {}
                override fun flush() {
                    synchronized(lock) { trackFramesWritten = 0L }
                }
                override fun stop() {
                    synchronized(lock) { trackFramesWritten = 0L }
                }
                override fun release() {}
                override fun setSpeed(speed: Float): Boolean = true
                override fun setVolume(volume: Float) {}

                override fun endUtterance() {
                    synchronized(lock) {
                        if (currentUtterance.size() > 0) {
                            utterances += RecordedUtterance(
                                pcmBytes = currentUtterance.toByteArray(),
                                completedAtMs = System.currentTimeMillis()
                            )
                            currentUtterance = ByteArrayOutputStream()
                        }
                    }
                }

                override val playbackHeadPositionFrames: Long
                    get() = synchronized(lock) { trackFramesWritten }
            }
        }
    }

    private fun measureMaxInternalSilenceMs(pcm: ByteArray, sampleRate: Int = 24_000): Long {
        val frames = pcm.size / 2
        if (frames <= 0) return 0L
        fun sampleAt(f: Int): Int = (pcm[f * 2 + 1].toInt() shl 8) or (pcm[f * 2].toInt() and 0xFF)
        val firstActive = (0 until frames).firstOrNull { abs(sampleAt(it)) > 300 } ?: return 0L
        val lastActive = (frames - 1 downTo firstActive).firstOrNull { abs(sampleAt(it)) > 300 } ?: return 0L
        var maxRun = 0
        var run = 0
        for (f in (firstActive + 1) until lastActive) {
            if (abs(sampleAt(f)) <= 300) {
                run++
                if (run > maxRun) maxRun = run
            } else {
                run = 0
            }
        }
        return maxRun.toLong() * 1000L / sampleRate.toLong()
    }

    private fun detectLocalPcmGlitch(pcm: ByteArray, sampleRate: Int = 24_000): Boolean {
        val frames = pcm.size / 2
        if (frames < 2) return false
        fun sampleAt(f: Int): Int = (pcm[f * 2 + 1].toInt() shl 8) or (pcm[f * 2].toInt() and 0xFF)
        val minDropout = (sampleRate * 3 / 1000).coerceAtLeast(2)
        val maxDropout = sampleRate * 150 / 1000
        var f = 1
        while (f < frames) {
            val prev = sampleAt(f - 1)
            val cur = sampleAt(f)
            if (abs(cur - prev) > 16_000) return true
            if (cur == 0 && abs(prev) > 1_500) {
                var end = f
                while (end < frames && sampleAt(end) == 0) end++
                val run = end - f
                if (run in minDropout..maxDropout && end < frames && abs(sampleAt(end)) > 1_500) {
                    return true
                }
                f = end
                continue
            }
            f++
        }
        return false
    }
}
