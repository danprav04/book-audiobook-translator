package io.github.danprav04.polyglotbooks.feature.reader

import io.github.danprav04.polyglotbooks.core.audio.AudioTrackAdapter
import io.github.danprav04.polyglotbooks.core.audio.AudioTrackAdapterFactory
import io.github.danprav04.polyglotbooks.core.audio.AudioTrackPcmPlayer
import io.github.danprav04.polyglotbooks.core.gemini.DefaultLiveSessionFactory
import io.github.danprav04.polyglotbooks.core.gemini.GeminiQuotaTracker
import io.github.danprav04.polyglotbooks.core.gemini.LiveClientMessage
import io.github.danprav04.polyglotbooks.core.gemini.LiveSessionFactory
import io.github.danprav04.polyglotbooks.core.model.DiagnosticLogBuffer
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import io.github.danprav04.polyglotbooks.core.model.PunctuationDialogueHelper
import io.github.danprav04.polyglotbooks.core.model.SpeechTextNormalizer
import io.github.danprav04.polyglotbooks.feature.narration.EngineCapabilities
import io.github.danprav04.polyglotbooks.feature.narration.EngineId
import io.github.danprav04.polyglotbooks.feature.narration.SynthesisRequest
import io.github.danprav04.polyglotbooks.feature.narration.VoiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.bytebuddy.ByteBuddy
import net.bytebuddy.implementation.InvocationHandlerAdapter
import net.bytebuddy.matcher.ElementMatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import io.github.danprav04.polyglotbooks.feature.narration.NarrationEngine as FeatureNarrationEngine

@OptIn(ExperimentalCoroutinesApi::class)
class OrvNarrationPipelineTest {

    @Before
    fun setUp() {
        DiagnosticLogBuffer.clear()
    }

    // =========================================================================
    // 1. Real wire JSON MissingFieldException regression (Issues #15 & #16)
    // =========================================================================

    @Test
    fun `real wire JSON handles large Base64 PCM frames and empty transcription or unknown envelopes with zero errors`() = runTest {
        val tempDir = Files.createTempDirectory("orv-wire-regression").toFile()
        try {
            val frame1Pcm = generateSineWavePcm(
                totalFrames = 14_700, // 29,400 bytes -> 39,200 Base64 chars (~39.2 KB)
                startFrameOffset = 0,
                fadeInFrames = 240,
                fadeOutFrames = 0
            )
            val frame2Pcm = generateSineWavePcm(
                totalFrames = 16_800, // 33,600 bytes -> 44,800 Base64 chars (~44.8 KB)
                startFrameOffset = 14_700,
                fadeInFrames = 0,
                fadeOutFrames = 240
            )
            val frame1B64 = Base64.getEncoder().encodeToString(frame1Pcm)
            val frame2B64 = Base64.getEncoder().encodeToString(frame2Pcm)
            assertTrue("Frame 1 Base64 should be 35KB-46KB, was ${frame1B64.length}", frame1B64.length in 35_000..46_000)
            assertTrue("Frame 2 Base64 should be 35KB-46KB, was ${frame2B64.length}", frame2B64.length in 35_000..46_000)

            val expectedCombinedPcm = frame1Pcm + frame2Pcm
            val sourceParagraph = chapter1Paragraphs().first { it.contains("three ways to survive", ignoreCase = true) }

            val server = FakeWireGeminiLiveServer(this) { conn, _, rawJson ->
                val sentText = extractPromptText(conn.decodeClientMessage(rawJson))
                conn.sendServerJson("""{"voiceActivity":{}}""")
                conn.sendServerJson(
                    """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$frame1B64"}}]},"outputTranscription":{}}}"""
                )
                conn.sendServerJson("""{"goAway":{}}""")
                conn.sendServerJson(
                    """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$frame2B64"}}]},"outputTranscription":{"text":${jsonEscape(sentText)}}}}"""
                )
                conn.sendServerJson(
                    """{"serverContent":{"outputTranscription":{"finished":true},"generationComplete":true,"turnComplete":true}}"""
                )
            }

            val adapterFactory = CapturingAudioTrackAdapterFactory()
            val fallbackEngine = RecordingSystemTtsEngine()
            val managerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            try {
                val manager = createManager(
                    managerScope = managerScope,
                    testScheduler = testScheduler,
                    tempDir = tempDir,
                    liveSessionFactory = server.liveSessionFactory,
                    adapterFactory = adapterFactory,
                    systemFallback = fallbackEngine
                )
                runCurrent()

                manager.startOrResume(
                    bookId = "orv-t1",
                    bookTitle = "Omniscient Reader's Viewpoint",
                    paragraphs = listOf(ReaderParagraph(0, "p0", sourceParagraph, "epubcfi(/6/6!/4/2/1:0)")),
                    chapters = emptyList()
                )
                runCurrent()
                advanceUntilIdle()

                val okHttpErrors = DiagnosticLogBuffer.recentEntries(200).filter {
                    it.level == "ERROR" && it.tag == "OkHttpLiveSession"
                }
                assertTrue("Expected zero ERROR/OkHttpLiveSession logs, found: $okHttpErrors", okHttpErrors.isEmpty())
                assertEquals(0, fallbackEngine.requests.size)
                assertNull(manager.sessionState.value.statusMessage)
                assertNull(manager.sessionState.value.errorMessage)
                assertEquals(1, adapterFactory.utterances.size)
                assertArrayEquals(expectedCombinedPcm, adapterFactory.allWrittenBytes())
                assertEquals(expectedCombinedPcm.size.toLong() / 2L, adapterFactory.totalFramesWritten())
            } finally {
                managerScope.cancel()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 2. gemini-3.8-live omitted turnComplete + session reuse
    // =========================================================================

    @Test
    fun `omitted turnComplete finalizes via generationComplete and rotates cleanly per generationComplete-only turn`() = runTest {
        val tempDir = Files.createTempDirectory("orv-omitted-turn-complete").toFile()
        try {
            val sixParagraphs = chapter1Paragraphs()
                .filter { it.any(Char::isLetterOrDigit) }
                .take(6)
            assertEquals(6, sixParagraphs.size)

            val turnPcms = sixParagraphs.indices.map { idx ->
                generateSineWavePcm(
                    totalFrames = 9_600 + idx * 480,
                    freqHz = 220.0 + idx * 30.0,
                    fadeInFrames = 240,
                    fadeOutFrames = 240
                )
            }

            val turnCounter = AtomicInteger(0)
            val server = FakeWireGeminiLiveServer(this) { conn, _, rawJson ->
                val turnIndex = turnCounter.getAndIncrement()
                val sentText = extractPromptText(conn.decodeClientMessage(rawJson))
                val b64 = Base64.getEncoder().encodeToString(turnPcms[turnIndex])

                conn.sendServerJson(
                    """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64"}}]},"outputTranscription":{"text":${jsonEscape(sentText)},"finished":true},"generationComplete":true}}"""
                )
            }

            val adapterFactory = CapturingAudioTrackAdapterFactory()
            val fallbackEngine = RecordingSystemTtsEngine()
            val managerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            try {
                val manager = createManager(
                    managerScope = managerScope,
                    testScheduler = testScheduler,
                    tempDir = tempDir,
                    liveSessionFactory = server.liveSessionFactory,
                    adapterFactory = adapterFactory,
                    systemFallback = fallbackEngine
                )
                runCurrent()

                manager.startOrResume(
                    bookId = "orv-t2",
                    bookTitle = "Omniscient Reader's Viewpoint",
                    paragraphs = sixParagraphs.mapIndexed { i, text ->
                        ReaderParagraph(i, "p$i", text, "epubcfi(/6/6!/4/2/${i + 1}:0)")
                    },
                    chapters = emptyList()
                )
                runCurrent()
                advanceUntilIdle()

                assertEquals("Must rotate cleanly per generationComplete-only turn to avoid server barge-in", 6, server.connections.size)
                assertEquals(6, server.clientContentMessages.size)
                assertEquals(0, fallbackEngine.requests.size)
                assertNull(manager.sessionState.value.statusMessage)
                assertNull(manager.sessionState.value.errorMessage)
                assertFalse(manager.sessionState.value.isPlaying)
                assertEquals(6, adapterFactory.utterances.size)
                turnPcms.forEachIndexed { idx, expectedPcm ->
                    assertArrayEquals("Utterance $idx PCM mismatch", expectedPcm, adapterFactory.utterances[idx])
                }
            } finally {
                managerScope.cancel()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 3. Real ORV dash gauntlet & internal silence compression
    // =========================================================================

    @Test
    fun `real ORV dash gauntlet normalizes prose dashes skips silent dividers and compresses 3s freeze`() = runTest {
        val tempDir = Files.createTempDirectory("orv-dash-gauntlet").toFile()
        try {
            // 3a. Standalone dividers must NEVER open a WebSocket or send a Live turn, and must emit brief silent audio.
            val standaloneDividers = listOf("—–", "······.", "\"----!!\"")
            val dividerOnlyServer = FakeWireGeminiLiveServer(this) { _, _, _ ->
                error("Standalone dividers must never open a Live WebSocket or send a text turn")
            }
            val dividerAdapterFactory = CapturingAudioTrackAdapterFactory()
            val dividerFallback = RecordingSystemTtsEngine()
            val dividerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            try {
                val dividerManager = createManager(
                    managerScope = dividerScope,
                    testScheduler = testScheduler,
                    tempDir = File(tempDir, "dividers").apply { mkdirs() },
                    liveSessionFactory = dividerOnlyServer.liveSessionFactory,
                    adapterFactory = dividerAdapterFactory,
                    systemFallback = dividerFallback
                )
                runCurrent()

                dividerManager.startOrResume(
                    bookId = "orv-t3-div",
                    bookTitle = "ORV Dividers",
                    paragraphs = standaloneDividers.mapIndexed { i, text ->
                        ReaderParagraph(i, "d$i", text, "epubcfi(/6/6!/4/2/${i + 1}:0)")
                    },
                    chapters = emptyList()
                )
                runCurrent()
                advanceUntilIdle()

                assertEquals("Dividers alone must open zero WebSocket connections", 0, dividerOnlyServer.connections.size)
                assertEquals(0, dividerOnlyServer.clientContentMessages.size)
                assertEquals(0, dividerFallback.requests.size)
                assertEquals(3, dividerAdapterFactory.utterances.size)
                dividerAdapterFactory.utterances.forEach { pcm ->
                    assertEquals(4800, pcm.size)
                    assertTrue("Divider PCM must be silent", pcm.all { it == 0.toByte() })
                }
            } finally {
                dividerScope.cancel()
            }

            // 3b. Real ORV dash gauntlet across Ch 1, 14, 15, 503, 514, 529, 535 + preserved compounds/ranges/grades + 3s silence freeze.
            val gauntletParagraphs = buildDashGauntletParagraphs()
            assertEquals(14, gauntletParagraphs.size)
            val spokenTexts = gauntletParagraphs.filter {
                PunctuationDialogueHelper.resolve(it).kind == PunctuationDialogueHelper.PunctuationKind.REGULAR_TEXT
            }
            assertEquals(11, spokenTexts.size)

            val normalTurnPcm = generateSineWavePcm(totalFrames = 12_000, fadeInFrames = 240, fadeOutFrames = 240)
            val frozenTurnPcm = buildSpeechWithInternalFreezePcm(
                speechMs = 500,
                freezeMs = 3_000
            )
            val freezeTargetIdx = 0

            val sentTurnCounter = AtomicInteger(0)
            val gauntletServer = FakeWireGeminiLiveServer(this) { conn, _, rawJson ->
                val idx = sentTurnCounter.getAndIncrement()
                val sentText = extractPromptText(conn.decodeClientMessage(rawJson))
                val pcmToSend = if (idx == freezeTargetIdx) frozenTurnPcm else normalTurnPcm
                val b64 = Base64.getEncoder().encodeToString(pcmToSend)
                conn.sendServerJson(
                    """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64"}}]},"outputTranscription":{"text":${jsonEscape(sentText)},"finished":true},"turnComplete":true}}"""
                )
            }

            val adapterFactory = CapturingAudioTrackAdapterFactory()
            val fallbackEngine = RecordingSystemTtsEngine()
            val managerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            try {
                val manager = createManager(
                    managerScope = managerScope,
                    testScheduler = testScheduler,
                    tempDir = File(tempDir, "gauntlet").apply { mkdirs() },
                    liveSessionFactory = gauntletServer.liveSessionFactory,
                    adapterFactory = adapterFactory,
                    systemFallback = fallbackEngine
                )
                runCurrent()

                manager.startOrResume(
                    bookId = "orv-t3-gauntlet",
                    bookTitle = "Omniscient Reader's Viewpoint",
                    paragraphs = gauntletParagraphs.mapIndexed { i, text ->
                        ReaderParagraph(i, "g$i", text, "epubcfi(/6/6!/4/2/${i + 1}:0)")
                    },
                    chapters = emptyList()
                )
                runCurrent()
                advanceUntilIdle()

                assertEquals(0, fallbackEngine.requests.size)
                assertEquals(spokenTexts.size, gauntletServer.clientContentMessages.size)
                assertEquals(gauntletParagraphs.size, adapterFactory.utterances.size)

                val sentWireTexts = gauntletServer.rawClientContentJsons.map { rawJson ->
                    val msg = FakeWireGeminiLiveServer.decodeClientMessageWithProductionJson(rawJson)
                    extractPromptText(msg)
                }
                sentWireTexts.forEach { text ->
                    assertFalse("Raw '--' leaked to Live turn: $text", text.contains("--"))
                    assertFalse("Raw '---' leaked to Live turn: $text", text.contains("---"))
                    assertFalse("Raw em-dash '—' leaked to Live turn: $text", text.contains('\u2014'))
                    assertFalse("Raw horizontal bar '―' leaked to Live turn: $text", text.contains('\u2015'))
                }

                val joinedSent = sentWireTexts.joinToString("\n")
                assertTrue("Compound 'Demon-like' must stay intact", joinedSent.contains("Demon-like"))
                assertTrue("Compound 'well-known' must stay intact", joinedSent.contains("well-known"))
                assertTrue("Range '10–20' must stay intact", joinedSent.contains("10\u201320"))
                assertTrue("Grade 'Difficulty: S-' must stay intact", joinedSent.contains("Difficulty: S-"))

                // Standalone dividers inside the mixed list must never appear in ClientContentMessage JSON.
                standaloneDividers.forEach { divider ->
                    assertFalse(
                        "Divider $divider must never appear in ClientContentMessage JSON",
                        gauntletServer.rawClientContentJsons.any { it.contains(divider) }
                    )
                }

                // First utterance had a 3-second mid-sentence freeze; verify compressed silence <= 500ms and zero PCM glitch!
                val firstSpokenIdx = gauntletParagraphs.indexOfFirst {
                    PunctuationDialogueHelper.resolve(it).kind == PunctuationDialogueHelper.PunctuationKind.REGULAR_TEXT
                }
                val writtenCompressedPcm = adapterFactory.utterances[firstSpokenIdx]
                val maxSilenceMs = measureMaxInternalSilenceMs(writtenCompressedPcm)
                assertTrue("3s freeze should compress to <= 500ms, got ${maxSilenceMs}ms", maxSilenceMs <= 500L)
                assertFalse("Compressed PCM must have hasPcmGlitch == false", detectLocalPcmGlitch(writtenCompressedPcm))
            } finally {
                managerScope.cancel()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 4. Issue #15 soft-retry & automatic recovery + banner clearing
    // =========================================================================

    @Test
    fun `sole Live model retries transient soft failures cleanly without device TTS and clears banner after sustained outage recovers`() = runTest {
        val tempDir = Files.createTempDirectory("orv-soft-retry-recovery").toFile()
        try {
            // 4a: Sole Live model (gemini-3.8-live) recovers from partial transcript, PCM glitch, and mid-turn WS 1011 drop.
            val subCases = listOf("partial_transcript", "pcm_glitch", "mid_turn_1011")
            val testLines = listOf(
                "Three Ways to Survive in a Ruined World had finally reached its long-awaited conclusion on my screen.",
                "Bihyung stared down at the floating contract window with a bewildered expression in the subway.",
                "I opened the catalog window carefully while checking how many coins remained in my pocket."
            )

            subCases.forEachIndexed { idx, mode ->
                val soleLiveModel = io.github.danprav04.polyglotbooks.core.model.DefaultModels.get(
                    io.github.danprav04.polyglotbooks.core.model.ModelRole.LIVE_NARRATION
                )
                val tracker = GeminiQuotaTracker().apply {
                    liveAudioCandidatePool(soleLiveModel)
                        .filter { it != soleLiveModel }
                        .forEach { recordHttpError(it, 404, "not found") }
                }
                val cleanPcm = generateSineWavePcm(
                    totalFrames = 14_400,
                    freqHz = 260.0 + idx * 40.0,
                    fadeInFrames = 240,
                    fadeOutFrames = 240
                )
                val badPartialPcm = generateSineWavePcm(
                    totalFrames = 7_200,
                    freqHz = 440.0,
                    fadeInFrames = 240,
                    fadeOutFrames = 240
                )
                val glitchedPcm = generateGlitchedPcm(totalFrames = 14_400)

                val attempts = AtomicInteger(0)
                val server = FakeWireGeminiLiveServer(this) { conn, testScope, rawJson ->
                    val attempt = attempts.getAndIncrement()
                    val sentText = extractPromptText(conn.decodeClientMessage(rawJson))
                    if (attempt == 0) {
                        when (mode) {
                            "partial_transcript" -> {
                                val b64 = Base64.getEncoder().encodeToString(badPartialPcm)
                                conn.sendServerJson(
                                    """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64"}}]},"outputTranscription":{"text":"Three ways","finished":true},"turnComplete":true}}"""
                                )
                            }
                            "pcm_glitch" -> {
                                val b64 = Base64.getEncoder().encodeToString(glitchedPcm)
                                conn.sendServerJson(
                                    """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64"}}]},"outputTranscription":{"text":${jsonEscape(sentText)},"finished":true},"turnComplete":true}}"""
                                )
                            }
                            "mid_turn_1011" -> {
                                val b64 = Base64.getEncoder().encodeToString(badPartialPcm)
                                conn.sendServerJson(
                                    """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64"}}]}}}"""
                                )
                                testScope.launch(StandardTestDispatcher(testScheduler)) {
                                    delay(10)
                                    conn.closeUnexpectedly(1011, "Internal server error")
                                }
                            }
                        }
                    } else {
                        val b64 = Base64.getEncoder().encodeToString(cleanPcm)
                        conn.sendServerJson(
                            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64"}}]},"outputTranscription":{"text":${jsonEscape(sentText)},"finished":true},"turnComplete":true}}"""
                        )
                    }
                }

                val adapterFactory = CapturingAudioTrackAdapterFactory()
                val fallbackEngine = RecordingSystemTtsEngine()
                val managerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
                try {
                    val manager = createManager(
                        managerScope = managerScope,
                        testScheduler = testScheduler,
                        tempDir = File(tempDir, "case-$mode").apply { mkdirs() },
                        liveSessionFactory = server.liveSessionFactory,
                        adapterFactory = adapterFactory,
                        systemFallback = fallbackEngine,
                        quotaTracker = tracker
                    )
                    runCurrent()

                    manager.startOrResume(
                        bookId = "orv-t4-$mode",
                        bookTitle = "ORV",
                        paragraphs = listOf(ReaderParagraph(0, "p0", testLines[idx], "epubcfi(/6/6!/4/2/1:0)")),
                        chapters = emptyList()
                    )
                    runCurrent()
                    advanceUntilIdle()

                    assertEquals("[$mode] must retry once on fresh Live session", 2, server.connections.size)
                    val soleLiveModelExpected = io.github.danprav04.polyglotbooks.core.model.DefaultModels.get(
                        io.github.danprav04.polyglotbooks.core.model.ModelRole.LIVE_NARRATION
                    )
                    assertTrue(
                        "[$mode] must use sole Live model $soleLiveModelExpected on both attempts",
                        server.setupMessages.all { it.setup.model.endsWith(soleLiveModelExpected) }
                    )
                    assertEquals("[$mode] must NEVER fall back to device TTS", 0, fallbackEngine.requests.size)
                    assertNull("[$mode] statusMessage must remain null", manager.sessionState.value.statusMessage)
                    assertEquals("[$mode] must produce exactly 1 clean utterance (zero duplicate audio)", 1, adapterFactory.utterances.size)
                    assertArrayEquals("[$mode] only clean retry PCM must reach AudioTrackPcmPlayer", cleanPcm, adapterFactory.allWrittenBytes())
                } finally {
                    managerScope.cancel()
                }
            }

            // 4b: Sustained outage across all attempts on paragraph 1 -> device TTS fallback banner,
            // then Live comes back online for paragraph 2 -> restores Gemini Live & clears statusMessage to null.
            val p1Text = "Paragraph one suffers a sustained Live outage across all retry attempts."
            val p2Text = "Paragraph two restores Gemini Live and clears the fallback status banner."
            val deviceFallbackPcm = generateSineWavePcm(totalFrames = 4_800, freqHz = 180.0, fadeInFrames = 120, fadeOutFrames = 120)
            val liveRecoveredPcm = generateSineWavePcm(totalFrames = 12_000, freqHz = 320.0, fadeInFrames = 240, fadeOutFrames = 240)

            val p2GateReached = CompletableDeferred<Unit>()
            val allowP2Live = CompletableDeferred<Unit>()
            var statusWhileFallbackActive: String? = null

            val soleLiveModel4b = io.github.danprav04.polyglotbooks.core.model.DefaultModels.get(
                io.github.danprav04.polyglotbooks.core.model.ModelRole.LIVE_NARRATION
            )
            val tracker4b = GeminiQuotaTracker().apply {
                liveAudioCandidatePool(soleLiveModel4b)
                    .filter { it != soleLiveModel4b }
                    .forEach { recordHttpError(it, 404, "not found") }
            }

            val outageServer = FakeWireGeminiLiveServer(this) { conn, testScope, rawJson ->
                val sentText = extractPromptText(conn.decodeClientMessage(rawJson))
                if (sentText.contains("Paragraph one")) {
                    conn.closeUnexpectedly(1011, "Sustained Live outage")
                } else {
                    p2GateReached.complete(Unit)
                    testScope.launch(StandardTestDispatcher(testScheduler)) {
                        allowP2Live.await()
                        val b64 = Base64.getEncoder().encodeToString(liveRecoveredPcm)
                        conn.sendServerJson(
                            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64"}}]},"outputTranscription":{"text":${jsonEscape(sentText)},"finished":true},"turnComplete":true}}"""
                        )
                    }
                }
            }

            val adapterFactory4b = CapturingAudioTrackAdapterFactory()
            val fallback4b = RecordingSystemTtsEngine(pcmData = deviceFallbackPcm)
            val scope4b = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            try {
                val manager4b = createManager(
                    managerScope = scope4b,
                    testScheduler = testScheduler,
                    tempDir = File(tempDir, "outage-recovery").apply { mkdirs() },
                    liveSessionFactory = outageServer.liveSessionFactory,
                    adapterFactory = adapterFactory4b,
                    systemFallback = fallback4b,
                    quotaTracker = tracker4b
                )
                runCurrent()

                val observedStatuses = CopyOnWriteArrayList<String?>()
                val statusCollector = backgroundScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
                    manager4b.sessionState.collect { st ->
                        if (observedStatuses.lastOrNull() != st.statusMessage) {
                            observedStatuses += st.statusMessage
                        }
                    }
                }

                manager4b.startOrResume(
                    bookId = "orv-t4b",
                    bookTitle = "ORV",
                    paragraphs = listOf(
                        ReaderParagraph(0, "p1", p1Text, "epubcfi(/6/6!/4/2/1:0)"),
                        ReaderParagraph(1, "p2", p2Text, "epubcfi(/6/6!/4/2/2:0)")
                    ),
                    chapters = emptyList()
                )
                runCurrent()

                assertTrue("Paragraph 2 should reach Live after paragraph 1 fell back", p2GateReached.isCompleted)
                statusWhileFallbackActive = manager4b.sessionState.value.statusMessage
                assertEquals("Using device TTS fallback (Gemini Live unavailable)", statusWhileFallbackActive)
                assertEquals(1, fallback4b.requests.size)

                allowP2Live.complete(Unit)
                runCurrent()
                advanceUntilIdle()
                statusCollector.cancel()

                assertTrue(observedStatuses.contains("Using device TTS fallback (Gemini Live unavailable)"))
                assertNull("Recovered Live turn must clear statusMessage back to null", manager4b.sessionState.value.statusMessage)
                assertEquals(3, outageServer.connections.size)
                assertEquals(2, adapterFactory4b.utterances.size)
                assertArrayEquals(deviceFallbackPcm, adapterFactory4b.utterances[0])
                assertArrayEquals(liveRecoveredPcm, adapterFactory4b.utterances[1])
            } finally {
                scope4b.cancel()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 5. Last-sentence repetition rejection on real ORV multi-sentence paragraphs
    // =========================================================================

    @Test
    fun `last-sentence repetition guard rejects all 6 repeat patterns on attempt 1 and emits only clean retry PCM`() = runTest {
        val tempDir = Files.createTempDirectory("orv-repeat-rejection").toFile()
        try {
            data class RepeatCase(
                val label: String,
                val sourceParagraph: String,
                val badTranscriptBuilder: (normalizedSource: String) -> String
            )

            val cases = listOf(
                RepeatCase(
                    label = "exact_repeat",
                    sourceParagraph = findOrvPara("chap_015.xhtml", { it.contains("My existence was his last chance.") })
                        ?: "[······.] Bihyung's expression changed to a threatening one. He looked at my head like it would explode at any minute. However, I knew that he would never give up the contract. My existence was his last chance.",
                    badTranscriptBuilder = { "$it My existence was his last chance." }
                ),
                RepeatCase(
                    label = "fuzzy_asr_drift_bihyung",
                    sourceParagraph = findOrvPara("chap_012.xhtml", { it.contains("Dokkaebi Bihyung showed confusion") })
                        ?: "If there was a crack in the face, it would look exactly like this. Dokkaebi Bihyung showed confusion for the first time.",
                    badTranscriptBuilder = { "$it Dokkaebi Bihyoung showed confusion for the first time." }
                ),
                RepeatCase(
                    label = "contraction_drift_im_to_i_am",
                    sourceParagraph = findOrvPara("chap_534.xhtml", { it.contains("I'm here at Yanggu.") })
                        ?: "– It's Kim Dokja. I'm here at Yanggu.",
                    badTranscriptBuilder = { "$it I am here at Yanggu." }
                ),
                RepeatCase(
                    label = "one_word_final_sentence",
                    sourceParagraph = findOrvPara("chap_014.xhtml", { it.contains("digging at the air. [What?]") })
                        ?: "Bihyung stopped his useless action of digging at the air. [What?]",
                    badTranscriptBuilder = { "$it What?" }
                ),
                RepeatCase(
                    label = "partial_trailing_echo",
                    sourceParagraph = findOrvPara("chap_001.xhtml", { it.contains("How many times have I been doing this?") })
                        ?: "A web novel platform filled the screen of my old smartphone. I scrolled down and then up again. How many times have I been doing this?",
                    badTranscriptBuilder = { "$it How many times?" }
                ),
                RepeatCase(
                    label = "comma_joined_repeat",
                    sourceParagraph = findOrvPara("chap_001.xhtml", { it.contains("and comments quickly appeared.") })
                        ?: "The author wrote me a free completed novel, so I should give him a recommendation. I clicked on the completed button, and comments quickly appeared.",
                    badTranscriptBuilder = { "$it, and comments quickly appeared." }
                )
            )

            cases.forEachIndexed { idx, testCase ->
                val badAttemptPcm = generateSineWavePcm(
                    totalFrames = 14_400,
                    freqHz = 520.0,
                    fadeInFrames = 240,
                    fadeOutFrames = 240
                )
                val cleanAttemptPcm = generateSineWavePcm(
                    totalFrames = 14_400,
                    freqHz = 240.0 + idx * 20.0,
                    fadeInFrames = 240,
                    fadeOutFrames = 240
                )

                val attemptCounter = AtomicInteger(0)
                val server = FakeWireGeminiLiveServer(this) { conn, _, rawJson ->
                    val attempt = attemptCounter.getAndIncrement()
                    val sentText = extractPromptText(conn.decodeClientMessage(rawJson))
                    if (attempt == 0) {
                        val b64 = Base64.getEncoder().encodeToString(badAttemptPcm)
                        val badTranscript = testCase.badTranscriptBuilder(sentText)
                        conn.sendServerJson(
                            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64"}}]},"outputTranscription":{"text":${jsonEscape(badTranscript)},"finished":true},"turnComplete":true}}"""
                        )
                    } else {
                        val b64 = Base64.getEncoder().encodeToString(cleanAttemptPcm)
                        conn.sendServerJson(
                            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64"}}]},"outputTranscription":{"text":${jsonEscape(sentText)},"finished":true},"turnComplete":true}}"""
                        )
                    }
                }

                val adapterFactory = CapturingAudioTrackAdapterFactory()
                val fallbackEngine = RecordingSystemTtsEngine()
                val managerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
                try {
                    val manager = createManager(
                        managerScope = managerScope,
                        testScheduler = testScheduler,
                        tempDir = File(tempDir, testCase.label).apply { mkdirs() },
                        liveSessionFactory = server.liveSessionFactory,
                        adapterFactory = adapterFactory,
                        systemFallback = fallbackEngine
                    )
                    runCurrent()

                    manager.startOrResume(
                        bookId = "orv-t5-${testCase.label}",
                        bookTitle = "ORV Repeat Guard",
                        paragraphs = listOf(ReaderParagraph(0, "p0", testCase.sourceParagraph, "epubcfi(/6/6!/4/2/1:0)")),
                        chapters = emptyList()
                    )
                    runCurrent()
                    advanceUntilIdle()

                    assertEquals("[${testCase.label}] must discard attempt 1 and retry on attempt 2", 2, attemptCounter.get())
                    assertEquals("[${testCase.label}] must not fall back to device TTS", 0, fallbackEngine.requests.size)
                    assertEquals("[${testCase.label}] must write exactly 1 utterance", 1, adapterFactory.utterances.size)
                    assertArrayEquals(
                        "[${testCase.label}] only clean retry PCM must reach AudioTrackPcmPlayer",
                        cleanAttemptPcm,
                        adapterFactory.allWrittenBytes()
                    )
                } finally {
                    managerScope.cancel()
                }
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 6. Full-book Omniscient Reader's Viewpoint.epub extraction & pipeline audit
    // =========================================================================

    @Test
    fun `full-book ORV EPUB audit extracts all 553 chapters cleanly and plays 25-paragraph Chapter 15 slice end-to-end`() = runTest {
        val epubFile = findOrvEpubFile()
        Assume.assumeTrue(
            "Omniscient Reader's Viewpoint.epub not found (checked -Dpolyglot.test.epub, POLYGLOT_TEST_EPUB, Downloads)",
            epubFile != null
        )
        val book = EbookContentExtractor.extractBook(null, epubFile!!.absolutePath, "EPUB")
        assertTrue("Expected > 500 chapters from ORV EPUB, got ${book.chapters.size}", book.chapters.size > 500)

        val auditManager = ReaderNarrationSessionManager(
            bookDao = FakeBookDao(),
            chapterDao = FakeChapterDao(emptyList()),
            applicationScope = backgroundScope
        )

        var totalParagraphsAudited = 0
        var regularSpokenCount = 0
        var silentDividerCount = 0

        for (chapter in book.chapters) {
            val readerParas = chapter.paragraphs.mapIndexedNotNull { idx, text ->
                val trimmed = text.trim()
                if (trimmed.isEmpty()) null
                else ReaderParagraph(idx, "c${chapter.index}_p$idx", trimmed, "epubcfi(/6/${(chapter.index + 1) * 2}!/4/2/${idx + 1}:0)")
            }
            if (readerParas.isEmpty()) continue

            val chunks = auditManager.buildSpeechChunks(
                paragraphs = readerParas,
                characters = emptyList(),
                engine = NarrationEngine.GEMINI_LIVE,
                defaultNarratorVoiceId = "Charon"
            )
            assertEquals("GEMINI_LIVE builds 1 SpeechChunk per non-blank paragraph in ch=${chapter.index}", readerParas.size, chunks.size)

            for (chunk in chunks) {
                totalParagraphsAudited++
                val resolved = PunctuationDialogueHelper.resolve(chunk.text, chunk.style)
                when (resolved.kind) {
                    PunctuationDialogueHelper.PunctuationKind.SILENT_DIVIDER -> {
                        silentDividerCount++
                    }
                    PunctuationDialogueHelper.PunctuationKind.REGULAR_TEXT -> {
                        regularSpokenCount++
                        val normalized = SpeechTextNormalizer.normalizeForSpeech(chunk.text)
                        assertFalse(
                            "ASCII apostrophe leaked in ch=${chapter.index} (${chapter.href}) para=${chunk.leadIndex}: raw=${chunk.text} -> norm=$normalized",
                            normalized.contains('\'')
                        )
                        assertFalse(
                            "Raw '--' leaked in ch=${chapter.index} (${chapter.href}) para=${chunk.leadIndex}: raw=${chunk.text} -> norm=$normalized",
                            normalized.contains("--")
                        )
                        assertFalse(
                            "Raw '---' leaked in ch=${chapter.index} (${chapter.href}) para=${chunk.leadIndex}: raw=${chunk.text} -> norm=$normalized",
                            normalized.contains("---")
                        )
                        assertFalse(
                            "Raw em-dash '—' leaked in ch=${chapter.index} (${chapter.href}) para=${chunk.leadIndex}: raw=${chunk.text} -> norm=$normalized",
                            normalized.contains('\u2014')
                        )
                        assertFalse(
                            "Raw horizontal bar '―' leaked in ch=${chapter.index} (${chapter.href}) para=${chunk.leadIndex}: raw=${chunk.text} -> norm=$normalized",
                            normalized.contains('\u2015')
                        )
                    }
                    PunctuationDialogueHelper.PunctuationKind.EMPTY,
                    PunctuationDialogueHelper.PunctuationKind.EXPRESSIVE_DIALOGUE -> Unit
                }
            }
        }

        assertTrue("Expected > 40,000 paragraphs across 553 chapters, got $totalParagraphsAudited", totalParagraphsAudited > 40_000)
        assertTrue("Expected regular spoken paragraphs > 35,000, got $regularSpokenCount", regularSpokenCount > 35_000)
        assertTrue("Expected silent dividers > 50, got $silentDividerCount", silentDividerCount > 50)
        println("ORV full-book audit: chapters=${book.chapters.size} paragraphs=$totalParagraphsAudited regularSpoken=$regularSpokenCount dividers=$silentDividerCount")

        // End-to-end 25-paragraph slice of real Chapter 15 (indices 82..106: dividers, Contract Agreement, pledge―], multi-sentence dialogue).
        val chap15 = book.chapters.first { it.href.endsWith("chap_015.xhtml", ignoreCase = true) }
        val chap15Slice = chap15.paragraphs.subList(82, 107)
        assertEquals(25, chap15Slice.size)
        assertTrue(chap15Slice.contains("—–"))
        assertTrue(chap15Slice.contains("[Stream Contract Agreement]"))
        assertTrue(chap15Slice.any { it.contains("pledge\u2015]") })

        val expectedSpokenTurns = chap15Slice.count {
            PunctuationDialogueHelper.resolve(it).kind == PunctuationDialogueHelper.PunctuationKind.REGULAR_TEXT
        }
        val expectedDividerTurns = chap15Slice.size - expectedSpokenTurns
        assertTrue("Chapter 15 slice should contain multiple dividers", expectedDividerTurns >= 6)

        val tempDir = Files.createTempDirectory("orv-ch15-slice").toFile()
        try {
            val turnPcm = generateSineWavePcm(totalFrames = 9_600, fadeInFrames = 240, fadeOutFrames = 240)
            val server = FakeWireGeminiLiveServer(this) { conn, _, rawJson ->
                val sentText = extractPromptText(conn.decodeClientMessage(rawJson))
                val b64 = Base64.getEncoder().encodeToString(turnPcm)
                conn.sendServerJson(
                    """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64"}}]},"outputTranscription":{"text":${jsonEscape(sentText)},"finished":true},"turnComplete":true}}"""
                )
            }
            val adapterFactory = CapturingAudioTrackAdapterFactory()
            val fallbackEngine = RecordingSystemTtsEngine()
            val managerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            try {
                val manager = createManager(
                    managerScope = managerScope,
                    testScheduler = testScheduler,
                    tempDir = tempDir,
                    liveSessionFactory = server.liveSessionFactory,
                    adapterFactory = adapterFactory,
                    systemFallback = fallbackEngine
                )
                runCurrent()

                manager.startOrResume(
                    bookId = "orv-ch15-slice",
                    bookTitle = book.title.ifBlank { "Omniscient Reader's Viewpoint" },
                    paragraphs = chap15Slice.mapIndexed { idx, text ->
                        ReaderParagraph(idx, "ch15_$idx", text, "epubcfi(/6/34!/4/2/${idx + 1}:0)")
                    },
                    chapters = emptyList()
                )
                runCurrent()
                advanceUntilIdle()

                assertEquals(expectedSpokenTurns, server.clientContentMessages.size)
                assertEquals(0, fallbackEngine.requests.size)
                assertNull(manager.sessionState.value.statusMessage)
                assertNull(manager.sessionState.value.errorMessage)
                assertEquals(25, adapterFactory.utterances.size)
                val okHttpErrors = DiagnosticLogBuffer.recentEntries(200).filter {
                    it.level == "ERROR" && it.tag == "OkHttpLiveSession"
                }
                assertTrue("Expected zero ERROR/OkHttpLiveSession logs, got $okHttpErrors", okHttpErrors.isEmpty())
                val sentPledge = server.clientContentMessages
                    .map { extractPromptText(it) }
                    .first { it.contains("soul pledge") }
                assertFalse("Raw '―' must be normalized in pledge line", sentPledge.contains('\u2015'))
                assertTrue("Trailing bar should normalize to ellipsis before ]", sentPledge.contains("soul pledge\u2026]"))
            } finally {
                managerScope.cancel()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // =========================================================================
    // Test fixtures & ORV EPUB helpers
    // =========================================================================

    private val cachedEpubBook: EbookContentExtractor.ExtractedBook? by lazy {
        findOrvEpubFile()?.let { file ->
            EbookContentExtractor.extractBook(null, file.absolutePath, "EPUB")
        }
    }

    private fun findOrvEpubFile(): File? {
        val candidates = listOfNotNull(
            System.getProperty("polyglot.test.epub")?.trim()?.takeIf { it.isNotEmpty() },
            System.getenv("POLYGLOT_TEST_EPUB")?.trim()?.takeIf { it.isNotEmpty() },
            "/mnt/c/Users/Daniel/Downloads/Omniscient Reader's Viewpoint.epub",
            File(System.getProperty("user.home", "."), "Downloads/Omniscient Reader's Viewpoint.epub").absolutePath
        )
        return candidates.map { File(it) }.firstOrNull { it.exists() && it.isFile }
    }

    private fun chapter1Paragraphs(): List<String> {
        val fromBook = cachedEpubBook?.chapters
            ?.firstOrNull { it.href.endsWith("chap_001.xhtml", ignoreCase = true) }
            ?.paragraphs
            ?.filter { it.isNotBlank() }
        if (!fromBook.isNullOrEmpty()) return fromBook
        return listOf(
            "Chapter 1: Prologue – Three Ways to Survive in a Ruined World.",
            "「There are three ways to survive in a ruined world. I have forgotten some of them now. However, one thing is certain: you who are currently reading these words will survive.",
            "–Three Ways to Survive in a Ruined World [Complete]」",
            "A web novel platform filled the screen of my old smartphone. I scrolled down and then up again. How many times have I been doing this?",
            "\"Really? This is the end?\"",
            "I looked again, and the 'complete' was unmistakable. The story was over.",
            "From the end of my childhood all the way to adulthood—a long journey spanning over ten years. I felt a mixture of desolation that a world was coming to an end along with a sense of fulfillment from finally being able to reach the conclusion of that world.",
            "–tls123: Thank you."
        )
    }

    private fun findOrvPara(chapSuffix: String, predicate: (String) -> Boolean): String? =
        cachedEpubBook?.chapters
            ?.firstOrNull { it.href.endsWith(chapSuffix, ignoreCase = true) }
            ?.paragraphs
            ?.firstOrNull(predicate)

    private fun buildDashGauntletParagraphs(): List<String> {
        val book = cachedEpubBook
        if (book != null) {
            fun reqPara(chapSuffix: String, predicate: (String) -> Boolean): String =
                requireNotNull(findOrvPara(chapSuffix, predicate)) {
                    "Expected matching ORV paragraph in $chapSuffix"
                }

            return listOf(
                reqPara("chap_001.xhtml") { it.contains("childhood") && it.contains("\u2014") },
                reqPara("chap_001.xhtml") { it.startsWith("\u2013tls123:") },
                reqPara("chap_014.xhtml") { it.contains("honor of the dokkaebi\u2015]") },
                reqPara("chap_015.xhtml") { it == "\u2014\u2013" },
                reqPara("chap_015.xhtml") { it == "\u00B7\u00B7\u00B7\u00B7\u00B7\u00B7." },
                reqPara("chap_015.xhtml") { it.contains("soul pledge\u2015]") },
                reqPara("chap_503.xhtml") { it.contains("Kuwaaaaah---!") },
                reqPara("chap_514.xhtml") { it == "\"----!!\"" },
                reqPara("chap_529.xhtml") { it.contains("Ranvir Khan--") },
                reqPara("chap_535.xhtml") { it.startsWith("\u2013 Author-nim!") },
                reqPara("chap_007.xhtml") { it.contains("Demon-like Judge of Fire") },
                reqPara("chap_048.xhtml") { it.contains("well-known") },
                reqPara("chap_083.xhtml") { it.contains("Difficulty: S-") },
                "Approximately 10\u201320 constellations remained on the channel."
            )
        }
        return listOf(
            "From the end of my childhood all the way to adulthood—a long journey spanning over ten years.",
            "–tls123: Thank you.",
            "[This can't be! I am Dokkaebi Bihyung. If I can't solve this situation, the honor of the dokkaebi―]",
            "—–",
            "······.",
            "[What do you mean? You can just say that you agree. The stream contract is a soul pledge―]",
            "[Kuwaaaaah---! I shall block this!]",
            "\"----!!\"",
            "\"Ranvir Khan--\"",
            "– Author-nim! I'll be living by myself starting this year!",
            "The second one, the Demon-like Judge of Fire.",
            "\"Yoo Joonghyuk-nim's reputation is well-known.\"",
            "Difficulty: S-",
            "Approximately 10–20 constellations remained on the channel."
        )
    }

    // =========================================================================
    // Pipeline wiring helpers (Real OkHttpLiveSession + Real AudioTrackPcmPlayer)
    // =========================================================================

    private fun createManager(
        managerScope: CoroutineScope,
        testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        tempDir: File,
        liveSessionFactory: LiveSessionFactory,
        adapterFactory: AudioTrackAdapterFactory,
        systemFallback: FeatureNarrationEngine,
        quotaTracker: GeminiQuotaTracker? = null
    ): ReaderNarrationSessionManager {
        val settingsRepo = FakeSettingsRepository().apply {
            runBlockingSetLiveEngine(this)
        }
        return ReaderNarrationSessionManager(
            bookDao = FakeBookDao(),
            chapterDao = FakeChapterDao(emptyList()),
            settingsRepository = settingsRepo,
            secureKeyStore = FakeSecureKeyStore("orv-harness-test-key"),
            liveSessionFactory = liveSessionFactory,
            quotaTracker = quotaTracker,
            applicationScope = managerScope
        ).apply {
            ioDispatcher = StandardTestDispatcher(testScheduler)
            testDiskCache = TranslationDiskCache(tempDir)
            playerFactory = { AudioTrackPcmPlayer(adapterFactory) }
            systemTtsFactory = { systemFallback }
        }
    }

    private fun runBlockingSetLiveEngine(repo: FakeSettingsRepository) {
        kotlinx.coroutines.runBlocking {
            repo.setReaderNarrationEngine(NarrationEngine.GEMINI_LIVE.name)
            repo.setDefaultNarratorVoiceId("Charon")
        }
    }

    private class RecordingSystemTtsEngine(
        private val pcmData: ByteArray = byteArrayOf(0x01, 0x02, 0x03, 0x04)
    ) : FeatureNarrationEngine {
        override val id: EngineId = EngineId.SYSTEM_TTS
        override val capabilities = EngineCapabilities(
            supportsMultiVoice = false,
            supportsEmotionalStyles = false,
            supportsStreaming = false,
            outputSampleRate = 24000
        )
        val requests = CopyOnWriteArrayList<SynthesisRequest>()
        override suspend fun voices(): List<VoiceInfo> = emptyList()
        override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> = flow {
            requests += request
            emit(PcmChunk(pcmData, sampleRate = 24000, channels = 1))
        }
    }

    private class CapturingAudioTrackAdapterFactory : AudioTrackAdapterFactory {
        private val allBytes = ByteArrayOutputStream()
        private var currentUtterance = ByteArrayOutputStream()
        val utterances = CopyOnWriteArrayList<ByteArray>()
        private var trackFramesWritten: Long = 0L
        private var totalFrames: Long = 0L
        private val lock = Any()

        override fun create(sampleRate: Int, channels: Int): AudioTrackAdapter {
            val bytesPerFrame = (channels.coerceAtLeast(1)) * 2
            return object : AudioTrackAdapter {
                override fun write(data: ByteArray, offset: Int, size: Int): Int {
                    synchronized(lock) {
                        allBytes.write(data, offset, size)
                        currentUtterance.write(data, offset, size)
                        val frames = (size / bytesPerFrame).toLong()
                        trackFramesWritten += frames
                        totalFrames += frames
                    }
                    return size
                }

                override fun play() {}
                override fun pause() {}
                override fun flush() {
                    synchronized(lock) {
                        trackFramesWritten = 0L
                    }
                }

                override fun stop() {
                    synchronized(lock) {
                        trackFramesWritten = 0L
                    }
                }

                override fun release() {}
                override fun setSpeed(speed: Float): Boolean = true
                override fun setVolume(volume: Float) {}

                override fun endUtterance() {
                    synchronized(lock) {
                        if (currentUtterance.size() > 0) {
                            utterances += currentUtterance.toByteArray()
                            currentUtterance = ByteArrayOutputStream()
                        }
                    }
                }

                override val playbackHeadPositionFrames: Long
                    get() = synchronized(lock) { trackFramesWritten }
            }
        }

        fun allWrittenBytes(): ByteArray = synchronized(lock) { allBytes.toByteArray() }
        fun totalFramesWritten(): Long = synchronized(lock) { totalFrames }
    }

    /**
     * Bridges an OkHttpClient mock created via ByteBuddy + Java Proxy into the REAL
     * [DefaultLiveSessionFactory] -> [OkHttpLiveSession] -> [OkHttpClient.newWebSocket] ->
     * real [okhttp3.WebSocketListener] wire JSON path.
     */
    private class FakeWireGeminiLiveServer(
        private val testScope: TestScope,
        private val onClientContent: (conn: ScriptedConnection, testScope: TestScope, rawJson: String) -> Unit
    ) {
        class ScriptedConnection(
            private val wsProxy: Any,
            private val listener: Any
        ) {
            private val onMessageMethod = webSocketListenerClass.getMethod(
                "onMessage",
                webSocketClass,
                String::class.java
            )
            private val onClosingMethod = webSocketListenerClass.getMethod(
                "onClosing",
                webSocketClass,
                Int::class.javaPrimitiveType,
                String::class.java
            )

            fun sendServerJson(rawJson: String) {
                onMessageMethod.invoke(listener, wsProxy, rawJson)
            }

            fun closeUnexpectedly(code: Int, reason: String) {
                onClosingMethod.invoke(listener, wsProxy, code, reason)
            }

            fun decodeClientMessage(rawJson: String): LiveClientMessage =
                decodeClientMessageWithProductionJson(rawJson)
        }

        val connections = CopyOnWriteArrayList<ScriptedConnection>()
        val setupMessages = CopyOnWriteArrayList<LiveClientMessage.SetupMessage>()
        val clientContentMessages = CopyOnWriteArrayList<LiveClientMessage.ClientContentMessage>()
        val rawClientContentJsons = CopyOnWriteArrayList<String>()

        private val mockOkHttpClient: Any = createMockOkHttpClient()
        val liveSessionFactory: LiveSessionFactory = DefaultLiveSessionFactory::class.java
            .getConstructor(okHttpClientClass)
            .newInstance(mockOkHttpClient) as LiveSessionFactory

        private fun createMockOkHttpClient(): Any {
            val builder = okHttpClientBuilderClass.getConstructor().newInstance()
            okHttpClientBuilderClass
                .getMethod("pingInterval", Long::class.javaPrimitiveType, TimeUnit::class.java)
                .invoke(builder, 20L, TimeUnit.SECONDS)
            okHttpClientBuilderClass
                .getMethod("readTimeout", Long::class.javaPrimitiveType, TimeUnit::class.java)
                .invoke(builder, 0L, TimeUnit.MILLISECONDS)

            val subClass = ByteBuddy()
                .subclass(okHttpClientClass)
                .method(ElementMatchers.named("newWebSocket"))
                .intercept(
                    InvocationHandlerAdapter.of { _, _, args ->
                        val request = args[0]
                        val listener = args[1]
                        handleNewWebSocket(request, listener)
                    }
                )
                .make()
                .load(okHttpClientClass.classLoader)
                .loaded

            return subClass.getConstructor(okHttpClientBuilderClass).newInstance(builder)
        }

        private fun handleNewWebSocket(request: Any, listener: Any): Any {
            var connectionRef: ScriptedConnection? = null
            val wsProxy = Proxy.newProxyInstance(
                webSocketClass.classLoader,
                arrayOf(webSocketClass)
            ) { _, method, args ->
                when (method.name) {
                    "send" -> {
                        val payload = args?.firstOrNull()
                        if (payload is String) {
                            when (val decoded = decodeClientMessageWithProductionJson(payload)) {
                                is LiveClientMessage.SetupMessage -> setupMessages += decoded
                                is LiveClientMessage.ClientContentMessage -> {
                                    rawClientContentJsons += payload
                                    clientContentMessages += decoded
                                    val conn = connectionRef!!
                                    onClientContent(conn, testScope, payload)
                                }
                                is LiveClientMessage.RealtimeInputMessage -> Unit
                            }
                        }
                        true
                    }
                    "close" -> true
                    "cancel" -> null
                    "request" -> request
                    "queueSize" -> 0L
                    else -> null
                }
            }

            val conn = ScriptedConnection(wsProxy, listener)
            connectionRef = conn
            connections += conn

            val response = buildHttp101Response(request)
            webSocketListenerClass
                .getMethod("onOpen", webSocketClass, responseClass)
                .invoke(listener, wsProxy, response)
            conn.sendServerJson("""{"setupComplete":{}}""")
            return wsProxy
        }

        private fun buildHttp101Response(request: Any): Any {
            val builder = responseBuilderClass.getConstructor().newInstance()
            responseBuilderClass.getMethod("request", requestClass).invoke(builder, request)
            val http11 = protocolClass.getField("HTTP_1_1").get(null)
            responseBuilderClass.getMethod("protocol", protocolClass).invoke(builder, http11)
            responseBuilderClass.getMethod("code", Int::class.javaPrimitiveType).invoke(builder, 101)
            responseBuilderClass.getMethod("message", String::class.java).invoke(builder, "Switching Protocols")
            return responseBuilderClass.getMethod("build").invoke(builder)
        }

        companion object {
            private val okHttpClientClass = Class.forName("okhttp3.OkHttpClient")
            private val okHttpClientBuilderClass = Class.forName("okhttp3.OkHttpClient\$Builder")
            private val webSocketClass = Class.forName("okhttp3.WebSocket")
            private val webSocketListenerClass = Class.forName("okhttp3.WebSocketListener")
            private val requestClass = Class.forName("okhttp3.Request")
            private val responseClass = Class.forName("okhttp3.Response")
            private val responseBuilderClass = Class.forName("okhttp3.Response\$Builder")
            private val protocolClass = Class.forName("okhttp3.Protocol")

            private val jsonClass = Class.forName("kotlinx.serialization.json.Json")
            private val jsonDefault = jsonClass.getField("Default").get(null)
            private val deserializationStrategyClass = Class.forName("kotlinx.serialization.DeserializationStrategy")
            private val decodeFromStringMethod = jsonClass.getMethod(
                "decodeFromString",
                deserializationStrategyClass,
                String::class.java
            )
            private val clientSerializer = Class.forName(
                "io.github.danprav04.polyglotbooks.core.gemini.LiveClientMessageSerializer"
            ).getField("INSTANCE").get(null)

            fun decodeClientMessageWithProductionJson(rawJson: String): LiveClientMessage =
                decodeFromStringMethod.invoke(jsonDefault, clientSerializer, rawJson) as LiveClientMessage
        }
    }

    private fun extractPromptText(message: LiveClientMessage): String =
        (message as LiveClientMessage.ClientContentMessage)
            .clientContent.turns.orEmpty()
            .flatMap { it.parts }
            .mapNotNull { it.text }
            .joinToString("\n")
            .removePrefix("<read>")
            .removeSuffix("</read>")

    // =========================================================================
    // PCM waveform synthesis & inspection helpers
    // =========================================================================

    private fun generateSineWavePcm(
        totalFrames: Int,
        sampleRate: Int = 24_000,
        freqHz: Double = 220.0,
        amplitude: Double = 4_500.0,
        startFrameOffset: Int = 0,
        fadeInFrames: Int = 0,
        fadeOutFrames: Int = 0
    ): ByteArray {
        val out = ByteArray(totalFrames * 2)
        for (i in 0 until totalFrames) {
            val envIn = if (fadeInFrames > 0 && i < fadeInFrames) i.toDouble() / fadeInFrames.toDouble() else 1.0
            val envOut = if (fadeOutFrames > 0 && i >= totalFrames - fadeOutFrames) {
                (totalFrames - 1 - i).toDouble() / fadeOutFrames.toDouble()
            } else {
                1.0
            }
            val sampleVal = (amplitude * envIn * envOut * sin(2.0 * PI * freqHz * (startFrameOffset + i) / sampleRate))
                .roundToInt()
                .coerceIn(-32_768, 32_767)
            out[i * 2] = (sampleVal and 0xFF).toByte()
            out[i * 2 + 1] = ((sampleVal ushr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun buildSpeechWithInternalFreezePcm(
        speechMs: Int,
        freezeMs: Int,
        sampleRate: Int = 24_000
    ): ByteArray {
        val speechFrames = sampleRate * speechMs / 1000
        val freezeFrames = sampleRate * freezeMs / 1000
        val totalFrames = speechFrames * 2 + freezeFrames
        val fadeFrames = sampleRate * 20 / 1000
        val out = ByteArray(totalFrames * 2)

        for (i in 0 until totalFrames) {
            val sampleVal = when {
                i < speechFrames -> {
                    val envIn = if (i < fadeFrames) i.toDouble() / fadeFrames else 1.0
                    val envOut = if (i >= speechFrames - fadeFrames) (speechFrames - 1 - i).toDouble() / fadeFrames else 1.0
                    (5_000.0 * envIn * envOut * sin(2.0 * PI * 220.0 * i / sampleRate)).roundToInt()
                }
                i >= speechFrames + freezeFrames -> {
                    val local = i - (speechFrames + freezeFrames)
                    val envIn = if (local < fadeFrames) local.toDouble() / fadeFrames else 1.0
                    val envOut = if (local >= speechFrames - fadeFrames) (speechFrames - 1 - local).toDouble() / fadeFrames else 1.0
                    (5_000.0 * envIn * envOut * sin(2.0 * PI * 220.0 * local / sampleRate)).roundToInt()
                }
                else -> if (i % 2 == 0) 60 else -60
            }.coerceIn(-32_768, 32_767)
            out[i * 2] = (sampleVal and 0xFF).toByte()
            out[i * 2 + 1] = ((sampleVal ushr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun generateGlitchedPcm(totalFrames: Int): ByteArray {
        val pcm = generateSineWavePcm(totalFrames = totalFrames, amplitude = 4_000.0, fadeInFrames = 120, fadeOutFrames = 120)
        // Inject a sharp step discontinuity > 16,000 mid-waveform to trigger hasPcmGlitch.
        val midFrame = totalFrames / 2
        val jumpSample = 26_000
        pcm[midFrame * 2] = (jumpSample and 0xFF).toByte()
        pcm[midFrame * 2 + 1] = ((jumpSample ushr 8) and 0xFF).toByte()
        val oppSample = -26_000
        pcm[(midFrame + 1) * 2] = (oppSample and 0xFF).toByte()
        pcm[(midFrame + 1) * 2 + 1] = ((oppSample ushr 8) and 0xFF).toByte()
        return pcm
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

    private fun jsonEscape(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) {
                    append("\\u%04x".format(ch.code))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }
}
