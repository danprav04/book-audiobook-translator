package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.data.db.CharacterEntity
import io.github.danprav04.polyglotbooks.core.data.db.NarrationLineEntity
import io.github.danprav04.polyglotbooks.core.gemini.Blob
import io.github.danprav04.polyglotbooks.core.gemini.GeminiQuotaTracker
import io.github.danprav04.polyglotbooks.core.gemini.GoAwayData
import io.github.danprav04.polyglotbooks.core.gemini.LiveClientMessage
import io.github.danprav04.polyglotbooks.core.gemini.LiveErrorData
import io.github.danprav04.polyglotbooks.core.gemini.LiveServerMessage
import io.github.danprav04.polyglotbooks.core.gemini.LiveSession
import io.github.danprav04.polyglotbooks.core.gemini.ModelTurn
import io.github.danprav04.polyglotbooks.core.gemini.Part
import io.github.danprav04.polyglotbooks.core.gemini.ServerContentData
import io.github.danprav04.polyglotbooks.core.gemini.SetupConfig
import io.github.danprav04.polyglotbooks.core.gemini.TranscriptionData
import io.github.danprav04.polyglotbooks.core.model.Character
import io.github.danprav04.polyglotbooks.core.model.NarrationLine
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class GeminiLiveEngineStreamingTest {

    private class FakeReplayLiveSession(
        private val replayCount: Int = 16,
        private val onSend: suspend (FakeReplayLiveSession, LiveClientMessage) -> Unit
    ) : LiveSession {
        private val incoming = MutableSharedFlow<LiveServerMessage>(
            replay = replayCount,
            extraBufferCapacity = 64
        )
        override val incomingMessages: Flow<LiveServerMessage> = incoming.asSharedFlow()

        override var isConnected: Boolean = false
            private set

        var connectCount = 0
            private set
        var disconnectCount = 0
            private set
        val setups = mutableListOf<SetupConfig>()
        val sentMessages = mutableListOf<LiveClientMessage>()

        override suspend fun connect(apiKey: String, setup: SetupConfig) {
            connectCount++
            setups += setup
            isConnected = true
        }

        override suspend fun send(message: LiveClientMessage) {
            sentMessages += message
            onSend(this, message)
        }

        suspend fun emitServerMessage(message: LiveServerMessage) {
            incoming.emit(message)
        }

        override suspend fun disconnect() {
            disconnectCount++
            isConnected = false
        }
    }

    private fun audioServerMessage(
        pcmBytes: ByteArray,
        transcript: String? = null,
        turnComplete: Boolean = false
    ): LiveServerMessage.ServerContentMessage = LiveServerMessage.ServerContentMessage(
        ServerContentData(
            modelTurn = ModelTurn(
                parts = listOf(
                    Part(
                        inlineData = Blob(
                            mimeType = "audio/pcm;rate=24000",
                            data = Base64.getEncoder().encodeToString(pcmBytes)
                        )
                    )
                )
            ),
            outputTranscription = transcript?.let { TranscriptionData(it) },
            turnComplete = turnComplete
        )
    )

    @Test
    fun `VoiceCaster and NarrationCoordinator honor configurable default narrator voice`() = runTest {
        val caster = VoiceCaster(defaultNarratorVoiceId = "kore")
        assertEquals("Kore", caster.getNarratorVoice())

        val assigned = caster.autoAssignVoices(
            listOf(
                Character(id = "narrator", bookId = "b1", name = "Narrator", gender = "male", age = "mature", voiceTraits = "", assignedVoiceId = null),
                Character(id = "c1", bookId = "b1", name = "Alice", gender = "female", age = "adult", voiceTraits = "", assignedVoiceId = null)
            )
        )
        assertEquals("Kore", assigned["narrator"])
        assertTrue(assigned["c1"] != "Kore")

        val capturedRequests = mutableListOf<SynthesisRequest>()
        val spyEngine = object : NarrationEngine {
            override val id = EngineId.GEMINI_TTS
            override val capabilities = EngineCapabilities(true, true, true, 24000)
            override suspend fun voices(): List<VoiceInfo> = emptyList()
            override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> {
                capturedRequests += request
                return flowOf(PcmChunk(ByteArray(4), 24000, 1))
            }
        }
        val coordinator = NarrationCoordinator(
            engine = spyEngine,
            prefetchAheadCount = 2,
            defaultNarratorVoiceId = "Aoede"
        )
        coordinator.prepareLines(
            lines = listOf(
                NarrationLine(id = "l1", chapterId = "ch1", paraIndex = 0, startOffset = 0, endOffset = 5, speakerId = "unknown", cfi = null),
                NarrationLine(id = "l2", chapterId = "ch1", paraIndex = 1, startOffset = 6, endOffset = 12, speakerId = "cast", cfi = null)
            ),
            voiceMap = mapOf("cast" to "Puck"),
            lineTexts = mapOf("l1" to "Hello", "l2" to "World")
        )
        coordinator.prepareLines(
            lines = listOf(NarrationLineEntity("l3", "ch1", 2, 0, 5, "narrator", null, null)),
            characters = listOf(CharacterEntity("narrator", "b1", "Narrator", "", "male", "mature", "", null))
        )

        assertEquals("Aoede", capturedRequests[0].voiceId)
        assertEquals("Puck", capturedRequests[1].voiceId)
        assertEquals("Aoede", capturedRequests[2].voiceId)
    }

    @Test
    fun `streaming session reuses single WebSocket across turns and rotates every 6 turns`() = runTest {
        val createdSessions = mutableListOf<FakeReplayLiveSession>()
        val engine = GeminiLiveEngine(
            liveSessionFactory = {
                val idx = createdSessions.size
                FakeReplayLiveSession(replayCount = 16) { session, msg ->
                    val text = (msg as LiveClientMessage.ClientContentMessage)
                        .clientContent.turns?.firstOrNull()?.parts?.firstOrNull()?.text.orEmpty()
                    val marker = ((idx * 10) + text.last().digitToIntOrNull().let { it ?: 1 }).toByte()
                    session.emitServerMessage(
                        audioServerMessage(
                            pcmBytes = byteArrayOf(marker, 2, 3, 4),
                            transcript = text,
                            turnComplete = true
                        )
                    )
                }.also { createdSessions += it }
            },
            apiKeyProvider = { "test-key" }
        )

        val dispatcher = StandardTestDispatcher(testScheduler)
        val live = engine.openStreamingSession(dispatcher = dispatcher)
        try {
            // Turns 1..6 must all reuse session 0 despite MutableSharedFlow(replay = 16).
            for (turn in 1..6) {
                val chunks = live.synthesizeTurnStreaming(
                    SynthesisRequest(text = "Paragraph $turn", voiceId = "Charon")
                ).toList()
                assertEquals(1, chunks.size)
                assertEquals(turn.toByte(), chunks[0].data[0])
                assertEquals(1, createdSessions.size)
                assertEquals(turn, createdSessions[0].sentMessages.size)
                assertEquals(turn, live.turnsInCurrentSession)
                assertEquals(0, live.sessionRotationCount)
            }

            // Turn 7 crosses default limit (6 turns) -> disconnects session 0 and opens session 1.
            val turn7Chunks = live.synthesizeTurnStreaming(
                SynthesisRequest(text = "Paragraph 7", voiceId = "Charon")
            ).toList()
            assertEquals(1, turn7Chunks.size)
            assertEquals(17.toByte(), turn7Chunks[0].data[0])
            assertEquals(2, createdSessions.size)
            assertEquals(1, createdSessions[0].disconnectCount)
            assertEquals(1, live.turnsInCurrentSession)
            assertEquals(1, live.sessionRotationCount)
        } finally {
            live.close()
        }
        assertEquals(1, createdSessions[1].disconnectCount)
    }

    @Test
    fun `streaming session rotates when char budget is exceeded or voice style language changes`() = runTest {
        val createdSessions = mutableListOf<FakeReplayLiveSession>()
        val engine = GeminiLiveEngine(
            liveSessionFactory = {
                FakeReplayLiveSession { session, msg ->
                    val text = (msg as LiveClientMessage.ClientContentMessage)
                        .clientContent.turns?.firstOrNull()?.parts?.firstOrNull()?.text.orEmpty()
                    session.emitServerMessage(
                        audioServerMessage(
                            pcmBytes = byteArrayOf(9, 8),
                            transcript = text,
                            turnComplete = true
                        )
                    )
                }.also { createdSessions += it }
            },
            apiKeyProvider = { "test-key" }
        )

        val dispatcher = StandardTestDispatcher(testScheduler)
        val live = engine.openStreamingSession(
            maxTurnsPerSession = 10,
            maxCharsPerSession = 4_000,
            dispatcher = dispatcher
        )
        try {
            // 2,500 chars fits in session 0.
            live.synthesizeTurnStreaming(SynthesisRequest(text = "a".repeat(2500), voiceId = "Charon")).toList()
            assertEquals(1, createdSessions.size)

            // Another 2,000 chars would push session 0 to 4,500 > 4,000 -> rotates to session 1.
            live.synthesizeTurnStreaming(SynthesisRequest(text = "b".repeat(2000), voiceId = "Charon")).toList()
            assertEquals(2, createdSessions.size)
            assertEquals(1, live.sessionRotationCount)

            // Same voice/style/language fits within 4,000 -> reuses session 1.
            live.synthesizeTurnStreaming(SynthesisRequest(text = "c".repeat(500), voiceId = "Charon")).toList()
            assertEquals(2, createdSessions.size)

            // Voice change rotates cleanly to session 2.
            live.synthesizeTurnStreaming(SynthesisRequest(text = "Next voice", voiceId = "Aoede")).toList()
            assertEquals(3, createdSessions.size)
            assertEquals(2, live.sessionRotationCount)
        } finally {
            live.close()
        }
    }

    @Test
    fun `chunks stream immediately as ServerContentMessage frames arrive before turnComplete`() = runTest {
        val firstFrameReceived = CompletableDeferred<Unit>()
        val releaseSecondFrame = CompletableDeferred<Unit>()
        lateinit var activeSession: FakeReplayLiveSession

        val engine = GeminiLiveEngine(
            liveSessionFactory = {
                FakeReplayLiveSession { _, _ -> }.also { activeSession = it }
            },
            apiKeyProvider = { "test-key" }
        )

        val dispatcher = StandardTestDispatcher(testScheduler)
        val live = engine.openStreamingSession(dispatcher = dispatcher)
        val receivedChunks = mutableListOf<PcmChunk>()

        val collectJob = launch(dispatcher) {
            live.synthesizeTurnStreaming(SynthesisRequest(text = "Hello world", voiceId = "Charon"))
                .collect { chunk ->
                    receivedChunks += chunk
                    if (receivedChunks.size == 1) {
                        firstFrameReceived.complete(Unit)
                    }
                }
        }

        // Run immediate tasks at virtual t=0 without advancing virtual clock past turn timeout.
        runCurrent()
        assertEquals(1, activeSession.sentMessages.size)

        // Emit chunk 1 WITHOUT turnComplete -> collector must receive chunk 1 immediately!
        activeSession.emitServerMessage(audioServerMessage(byteArrayOf(11, 12), turnComplete = false))
        runCurrent()
        assertTrue(firstFrameReceived.isCompleted)
        assertEquals(1, receivedChunks.size)
        assertEquals(11.toByte(), receivedChunks[0].data[0])

        // Emit chunk 2 with turnComplete -> collector receives chunk 2 and completes.
        releaseSecondFrame.complete(Unit)
        activeSession.emitServerMessage(
            audioServerMessage(byteArrayOf(21, 22), transcript = "Hello world", turnComplete = true)
        )
        runCurrent()
        collectJob.join()
        live.close()

        assertEquals(2, receivedChunks.size)
        assertEquals(21.toByte(), receivedChunks[1].data[0])
    }

    @Test
    fun `dynamic turn timeout scales between 20s and 90s and falls back when turn times out`() = runTest {
        // Formula verification: (15_000 + len * 140).coerceIn(20_000, 90_000)
        assertEquals(20_000L, GeminiLiveEngine.computeTurnTimeoutMs(0))
        assertEquals(20_000L, GeminiLiveEngine.computeTurnTimeoutMs(10))
        assertEquals(29_000L, GeminiLiveEngine.computeTurnTimeoutMs(100))
        assertEquals(71_000L, GeminiLiveEngine.computeTurnTimeoutMs(400))
        assertEquals(90_000L, GeminiLiveEngine.computeTurnTimeoutMs(1_000))

        val fallbackMarker = byteArrayOf(77, 88)
        val recordedTimeouts = mutableListOf<Long>()
        val silentFallbackEngine = object : NarrationEngine {
            override val id = EngineId.GEMINI_TTS
            override val capabilities = EngineCapabilities(true, true, true, 24000)
            override suspend fun voices(): List<VoiceInfo> = emptyList()
            override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> =
                flowOf(PcmChunk(fallbackMarker, 24000, 1))
        }

        val engine = GeminiLiveEngine(
            fallbackEngine = silentFallbackEngine,
            liveSessionFactory = {
                FakeReplayLiveSession { _, _ ->
                    // Intentionally emit nothing so the turn times out.
                }
            },
            apiKeyProvider = { "test-key" },
            turnTimeoutMsProvider = { text ->
                // Verify real scaled formula runs, while using small virtual timeout in test.
                val computed = GeminiLiveEngine.computeTurnTimeoutMs(text.length)
                recordedTimeouts += computed
                1_200L
            }
        )

        val chunks = engine.synthesize(SynthesisRequest(text = "x".repeat(400), voiceId = "Charon")).toList()
        assertTrue(recordedTimeouts.isNotEmpty())
        assertEquals(71_000L, recordedTimeouts.first())
        assertEquals(1, chunks.size)
        assertTrue(fallbackMarker.contentEquals(chunks[0].data))
    }

    @Test
    fun `GoAway message finishes current turn then rotates cleanly before next turn`() = runTest {
        val createdSessions = mutableListOf<FakeReplayLiveSession>()
        val engine = GeminiLiveEngine(
            liveSessionFactory = {
                FakeReplayLiveSession { session, msg ->
                    val text = (msg as LiveClientMessage.ClientContentMessage)
                        .clientContent.turns?.firstOrNull()?.parts?.firstOrNull()?.text.orEmpty()
                    if (createdSessions.size == 1) {
                        session.emitServerMessage(LiveServerMessage.GoAwayMessage(GoAwayData(timeLeft = "30s")))
                    }
                    session.emitServerMessage(
                        audioServerMessage(
                            pcmBytes = byteArrayOf(33, 44),
                            transcript = text,
                            turnComplete = true
                        )
                    )
                }.also { createdSessions += it }
            },
            apiKeyProvider = { "test-key" }
        )

        val dispatcher = StandardTestDispatcher(testScheduler)
        val live = engine.openStreamingSession(dispatcher = dispatcher)
        try {
            val t1 = live.synthesizeTurnStreaming(SynthesisRequest(text = "First turn", voiceId = "Charon")).toList()
            assertEquals(1, t1.size)
            assertEquals(1, createdSessions.size)

            val t2 = live.synthesizeTurnStreaming(SynthesisRequest(text = "Second turn", voiceId = "Charon")).toList()
            assertEquals(1, t2.size)
            assertEquals(2, createdSessions.size)
            assertEquals(1, createdSessions[0].disconnectCount)
            assertEquals(1, live.sessionRotationCount)
        } finally {
            live.close()
        }
    }

    @Test
    fun `mid-turn Live server error rotates session cleanly on next turn`() = runTest {
        val createdSessions = mutableListOf<FakeReplayLiveSession>()
        val engine = GeminiLiveEngine(
            liveSessionFactory = {
                val sessionIdx = createdSessions.size
                FakeReplayLiveSession { session, msg ->
                    val text = (msg as LiveClientMessage.ClientContentMessage)
                        .clientContent.turns?.firstOrNull()?.parts?.firstOrNull()?.text.orEmpty()
                    if (sessionIdx == 0) {
                        session.emitServerMessage(audioServerMessage(byteArrayOf(5, 6), turnComplete = false))
                        session.emitServerMessage(LiveServerMessage.ErrorMessage(LiveErrorData(code = 503, message = "Overloaded")))
                    } else {
                        session.emitServerMessage(audioServerMessage(byteArrayOf(7, 8), transcript = text, turnComplete = true))
                    }
                }.also { createdSessions += it }
            },
            apiKeyProvider = { "test-key" }
        )

        val dispatcher = StandardTestDispatcher(testScheduler)
        val live = engine.openStreamingSession(dispatcher = dispatcher)
        try {
            val first = live.synthesizeTurnStreaming(SynthesisRequest(text = "One", voiceId = "Charon")).toList()
            assertEquals(1, first.size)
            assertEquals(5.toByte(), first[0].data[0])

            val second = live.synthesizeTurnStreaming(SynthesisRequest(text = "Two", voiceId = "Charon")).toList()
            assertEquals(2, createdSessions.size)
            assertEquals(1, second.size)
            assertEquals(7.toByte(), second[0].data[0])
        } finally {
            live.close()
        }
    }

    @Test
    fun `generationComplete waits for trailing audio and transcription until turnComplete arrives`() = runTest {
        lateinit var activeSession: FakeReplayLiveSession
        val engine = GeminiLiveEngine(
            liveSessionFactory = {
                FakeReplayLiveSession { _, _ -> }.also { activeSession = it }
            },
            apiKeyProvider = { "test-key" }
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val live = engine.openStreamingSession(
            dispatcher = dispatcher,
            bufferAndVerifyTurns = true
        )
        val receivedChunks = mutableListOf<PcmChunk>()
        val collectJob = launch(dispatcher) {
            live.synthesizeTurnStreaming(
                SynthesisRequest(text = "It was the best of times, it was the worst of times.", voiceId = "Charon")
            ).collect { receivedChunks += it }
        }

        runCurrent()
        // 1. First half arrives with generationComplete=true BEFORE turnComplete=true -> must NOT emit or terminate early.
        activeSession.emitServerMessage(
            LiveServerMessage.ServerContentMessage(
                ServerContentData(
                    modelTurn = ModelTurn(
                        parts = listOf(Part(inlineData = Blob("audio/pcm;rate=24000", Base64.getEncoder().encodeToString(byteArrayOf(11, 12)))))
                    ),
                    outputTranscription = TranscriptionData("It was the best of times,"),
                    generationComplete = true,
                    turnComplete = false
                )
            )
        )
        runCurrent()
        assertTrue("Turn must still be awaiting trailing chunks and turnComplete", receivedChunks.isEmpty())

        // 2. Trailing tail audio + final transcript + turnComplete=true arrives -> emits full turn cleanly.
        activeSession.emitServerMessage(
            audioServerMessage(
                pcmBytes = byteArrayOf(21, 22),
                transcript = "it was the worst of times.",
                turnComplete = true
            )
        )
        runCurrent()
        collectJob.join()
        live.close()

        assertEquals(2, receivedChunks.size)
        assertEquals(11.toByte(), receivedChunks[0].data[0])
        assertEquals(21.toByte(), receivedChunks[1].data[0])
    }

    @Test
    fun `bufferAndVerifyTurns discards partial interrupted or errored turns and avoids double playback on fallback`() = runTest {
        val createdSessions = mutableListOf<FakeReplayLiveSession>()
        val fallbackMarker = byteArrayOf(91, 92, 93)
        val fallbackEngine = object : NarrationEngine {
            override val id = EngineId.GEMINI_TTS
            override val capabilities = EngineCapabilities(true, true, true, 24000)
            override suspend fun voices(): List<VoiceInfo> = emptyList()
            override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> =
                flowOf(PcmChunk(fallbackMarker, 24000, 1))
        }

        val engine = GeminiLiveEngine(
            fallbackEngine = fallbackEngine,
            liveSessionFactory = {
                val idx = createdSessions.size
                FakeReplayLiveSession { session, _ ->
                    when (idx) {
                        0 -> {
                            // Candidate 0 emits partial audio then server interrupts turn with turnComplete=true
                            session.emitServerMessage(audioServerMessage(byteArrayOf(1, 2), turnComplete = false))
                            session.emitServerMessage(
                                LiveServerMessage.ServerContentMessage(
                                    ServerContentData(
                                        modelTurn = null,
                                        interrupted = true,
                                        turnComplete = true
                                    )
                                )
                            )
                        }
                        1 -> {
                            // Candidate 1 emits partial audio then mid-turn 503 error
                            session.emitServerMessage(audioServerMessage(byteArrayOf(3, 4), turnComplete = false))
                            session.emitServerMessage(LiveServerMessage.ErrorMessage(LiveErrorData(code = 503, message = "Overloaded")))
                        }
                        else -> {
                            // Candidate 2 completes full verbatim turn cleanly
                            session.emitServerMessage(
                                audioServerMessage(
                                    pcmBytes = byteArrayOf(55, 56),
                                    transcript = "Reliable complete narration",
                                    turnComplete = true
                                )
                            )
                        }
                    }
                }.also { createdSessions += it }
            },
            apiKeyProvider = { "test-key" }
        )

        val dispatcher = StandardTestDispatcher(testScheduler)
        val live = engine.openStreamingSession(
            dispatcher = dispatcher,
            bufferAndVerifyTurns = true
        )
        val chunks = try {
            live.synthesizeTurnStreaming(
                SynthesisRequest(text = "Reliable complete narration", voiceId = "Charon")
            ).toList()
        } finally {
            live.close()
        }

        // Only candidate 2's complete verified audio must be emitted (no partial chunks 1/3, no fallback double audio!)
        assertEquals(3, createdSessions.size)
        assertEquals(1, chunks.size)
        assertEquals(55.toByte(), chunks[0].data[0])
    }

    @Test
    fun `split BPE transcription fragments assemble cleanly without mid-word spaces and complete after idle audio`() = runTest {
        lateinit var activeSession: FakeReplayLiveSession
        var fallbackCalled = false
        val fallbackEngine = object : NarrationEngine {
            override val id = EngineId.GEMINI_TTS
            override val capabilities = EngineCapabilities(true, true, true, 24000)
            override suspend fun voices(): List<VoiceInfo> = emptyList()
            override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> {
                fallbackCalled = true
                return flowOf(PcmChunk(byteArrayOf(99), 24000, 1))
            }
        }

        val engine = GeminiLiveEngine(
            fallbackEngine = fallbackEngine,
            liveSessionFactory = {
                FakeReplayLiveSession { _, _ -> }.also { activeSession = it }
            },
            apiKeyProvider = { "test-key" }
        )

        val dispatcher = StandardTestDispatcher(testScheduler)
        val live = engine.openStreamingSession(
            dispatcher = dispatcher,
            bufferAndVerifyTurns = true
        )
        val received = mutableListOf<PcmChunk>()
        val collectJob = launch(dispatcher) {
            live.synthesizeTurnStreaming(
                SynthesisRequest(
                    text = "Three Ways to Survive in a Ruined World",
                    voiceId = "Charon",
                    speakerName = "Kim Dokja",
                    characterPersonality = "dry, analytical",
                    storySummary = "A lone reader on a subway",
                    style = "wry reflection"
                )
            ).collect { received += it }
        }

        runCurrent()
        // Verify tailored narrative/personality context reaches systemInstruction
        val sysText = activeSession.setups.single().systemInstruction?.parts?.firstOrNull()?.text.orEmpty()
        assertTrue(sysText.contains("Kim Dokja"))
        assertTrue(sysText.contains("dry, analytical"))
        assertTrue(sysText.contains("A lone reader on a subway"))
        assertTrue(sysText.contains("wry reflection"))

        // Stream BPE split pieces WITHOUT turnComplete (simulating server omitting turnComplete envelope)
        activeSession.emitServerMessage(audioServerMessage(byteArrayOf(11, 12), transcript = "Three", turnComplete = false))
        activeSession.emitServerMessage(audioServerMessage(byteArrayOf(13, 14), transcript = " Ways to Sur", turnComplete = false))
        activeSession.emitServerMessage(audioServerMessage(byteArrayOf(15, 16), transcript = "vival in a", turnComplete = false))
        activeSession.emitServerMessage(audioServerMessage(byteArrayOf(17, 18), transcript = " Ruined World", turnComplete = false))
        runCurrent()
        // Must still wait briefly for trailing turnComplete or post-audio idle timeout
        assertTrue(received.isEmpty())

        // Advance past 2.5s post-audio idle timeout -> must finalize & emit valid audio instead of waiting 20-90s!
        advanceTimeBy(2_600L)
        runCurrent()
        collectJob.join()
        live.close()

        assertTrue(!fallbackCalled)
        assertEquals(4, received.size)
        assertEquals(11.toByte(), received[0].data[0])
        assertEquals(17.toByte(), received[3].data[0])
    }

    @Test
    fun `streaming Live session reuses setup across style changes and rejects repeated hallucinated transcript`() = runTest {
        val createdSessions = mutableListOf<FakeReplayLiveSession>()
        val fallbackTexts = mutableListOf<String>()
        val fallbackEngine = object : NarrationEngine {
            override val id = EngineId.SYSTEM_TTS
            override val capabilities = EngineCapabilities(false, false, false, 24000)
            override suspend fun voices(): List<VoiceInfo> = emptyList()
            override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> {
                fallbackTexts += request.text
                return flowOf(PcmChunk(byteArrayOf(99, 100), 24000, 1))
            }
        }
        val engine = GeminiLiveEngine(
            fallbackEngine = fallbackEngine,
            liveSessionFactory = {
                FakeReplayLiveSession { _, _ -> }.also { createdSessions += it }
            },
            apiKeyProvider = { "test-key" }
        )

        val dispatcher = StandardTestDispatcher(testScheduler)
        val live = engine.openStreamingSession(
            dispatcher = dispatcher,
            bufferAndVerifyTurns = true
        )
        try {
            // Turn 1 -> Narrator
            val t1Job = launch(dispatcher) {
                live.synthesizeTurnStreaming(
                    SynthesisRequest(text = "The train stopped cleanly.", voiceId = "Charon", speakerName = "Narrator", style = "calm")
                ).toList()
            }
            runCurrent()
            createdSessions.last().emitServerMessage(audioServerMessage(byteArrayOf(1, 2), transcript = "The train stopped cleanly.", turnComplete = true))
            t1Job.join()

            // Turn 2 -> Different character & style keeps the exact same WebSocket session (only 1 setup sent)
            val t2Job = launch(dispatcher) {
                live.synthesizeTurnStreaming(
                    SynthesisRequest(text = "Why are you staring at me?", voiceId = "Charon", speakerName = "Kim Namwoon", style = "angry")
                ).toList()
            }
            runCurrent()
            createdSessions.last().emitServerMessage(audioServerMessage(byteArrayOf(3, 4), transcript = "Why are you staring at me?", turnComplete = true))
            t2Job.join()
            assertEquals(1, createdSessions.size)
            assertEquals(1, createdSessions.single().setups.size)

            // Turn 3 -> Doubled hallucinated transcript exceeds word expansion guard -> rejected across candidates and falls back cleanly
            val t3Chunks = mutableListOf<PcmChunk>()
            val sourceLine = "Why would you do such a thing?"
            val doubledTranscript = "$sourceLine $sourceLine $sourceLine"
            val t3Job = launch(dispatcher) {
                live.synthesizeTurnStreaming(
                    SynthesisRequest(text = sourceLine, voiceId = "Charon")
                ).collect { t3Chunks += it }
            }
            repeat(8) {
                runCurrent()
                createdSessions.lastOrNull()?.emitServerMessage(
                    audioServerMessage(byteArrayOf(5, 6), transcript = doubledTranscript, turnComplete = true)
                )
            }
            t3Job.join()

            assertEquals(listOf(sourceLine), fallbackTexts)
            assertEquals(1, t3Chunks.size)
            assertEquals(99.toByte(), t3Chunks[0].data[0])
        } finally {
            live.close()
        }
    }

    @Test
    fun `streaming Live session normalizes paired single quotes before sending turn`() = runTest {
        val createdSessions = mutableListOf<FakeReplayLiveSession>()
        val engine = GeminiLiveEngine(
            fallbackEngine = object : NarrationEngine {
                override val id = EngineId.SYSTEM_TTS
                override val capabilities = EngineCapabilities(false, false, false, 24000)
                override suspend fun voices(): List<VoiceInfo> = emptyList()
                override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> = flowOf()
            },
            liveSessionFactory = {
                FakeReplayLiveSession { _, _ -> }.also { createdSessions += it }
            },
            apiKeyProvider = { "test-key" }
        )

        val dispatcher = StandardTestDispatcher(testScheduler)
        val live = engine.openStreamingSession(
            dispatcher = dispatcher,
            bufferAndVerifyTurns = true
        )
        try {
            val line1 = "[The constellation 'Demon-like Judge of Fire' wasn't pleased.]"
            val expected1 = "[The constellation \u201CDemon-like Judge of Fire\u201D wasn\u2019t pleased.]"
            val j1 = launch(dispatcher) {
                live.synthesizeTurnStreaming(SynthesisRequest(text = line1, voiceId = "Charon")).toList()
            }
            runCurrent()
            createdSessions.last().emitServerMessage(audioServerMessage(byteArrayOf(1, 2), transcript = line1, turnComplete = true))
            j1.join()

            val line2 = "He muttered, 'Wait 'til I'm home.'"
            val expected2 = "He muttered, \u201CWait \u2019til I\u2019m home.\u201D"
            val j2 = launch(dispatcher) {
                live.synthesizeTurnStreaming(SynthesisRequest(text = line2, voiceId = "Charon")).toList()
            }
            runCurrent()
            createdSessions.last().emitServerMessage(audioServerMessage(byteArrayOf(3, 4), transcript = line2, turnComplete = true))
            j2.join()

            val sentTexts = createdSessions.single().sentMessages
                .filterIsInstance<LiveClientMessage.ClientContentMessage>()
                .mapNotNull { it.clientContent.turns?.firstOrNull()?.parts?.firstOrNull()?.text }
            assertEquals(listOf(expected1, expected2), sentTexts)
            assertTrue(sentTexts.none { '\'' in it })
        } finally {
            live.close()
        }
    }

    @Test
    fun `trimExcessiveEdgeSilence trims oversized silence while preserving speech and leaving short turns untouched`() {
        fun encodeSamples(samples: ShortArray): ByteArray {
            val out = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val s = samples[i].toInt()
                out[i * 2] = (s and 0xFF).toByte()
                out[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
            }
            return out
        }

        fun decodeSample(bytes: ByteArray, frameIndex: Int): Short {
            val idx = frameIndex * 2
            return ((bytes[idx + 1].toInt() shl 8) or (bytes[idx].toInt() and 0xFF)).toShort()
        }

        // Tiny synthetic unit-test chunk (< 300 ms) must remain untouched.
        val tiny = listOf(PcmChunk(byteArrayOf(1, 2, 3, 4), sampleRate = 24000, channels = 1))
        assertEquals(tiny, GeminiLiveEngine.trimExcessiveEdgeSilence(tiny))

        // 400 ms codec floor (9600 frames @ +-50) + 200 ms speech (4800 frames @ 5000) + 350 ms silence (8400 frames @ 0)
        val totalFrames = 9600 + 4800 + 8400
        val samples = ShortArray(totalFrames) { i ->
            when {
                i < 9600 -> if (i % 2 == 0) 50 else -50
                i < 9600 + 4800 -> 5000
                else -> 0
            }
        }
        val fullBytes = encodeSamples(samples)
        val splitByte = 12000 * 2
        val chunks = listOf(
            PcmChunk(fullBytes.copyOfRange(0, splitByte), sampleRate = 24000, channels = 1),
            PcmChunk(fullBytes.copyOfRange(splitByte, fullBytes.size), sampleRate = 24000, channels = 1)
        )

        val trimmed = GeminiLiveEngine.trimExcessiveEdgeSilence(chunks)
        assertEquals(1, trimmed.size)
        // Kept: 60 ms lead (1440) + 200 ms speech (4800) + 150 ms tail (3600) = 9840 frames
        val trimmedBytes = trimmed.single().data
        assertEquals(9840 * 2, trimmedBytes.size)
        assertEquals(0.toShort(), decodeSample(trimmedBytes, 0))
        assertEquals(5000.toShort(), decodeSample(trimmedBytes, 1440))
        assertEquals(0.toShort(), decodeSample(trimmedBytes, 9839))

        // Modest edge silence (100 ms lead, 250 ms tail <= 300 ms threshold, total 550 ms) stays untouched.
        val modestSamples = ShortArray(2400 + 4800 + 6000) { i ->
            if (i in 2400 until 7200) 5000 else 0
        }
        val modestChunks = listOf(PcmChunk(encodeSamples(modestSamples), sampleRate = 24000, channels = 1))
        assertEquals(modestChunks, GeminiLiveEngine.trimExcessiveEdgeSilence(modestChunks))
    }

    @Test
    fun `tokenizeWordsOrdered preserves contractions and apostrophes cleanly`() {
        val tokens = GeminiLiveEngine.tokenizeWordsOrdered(
            "He whispered, 'Don’t move, Dokja's watching—'til it's over.'"
        )
        assertEquals(
            listOf("he", "whispered", "don't", "move", "dokja's", "watching", "til", "it's", "over"),
            tokens
        )
    }

    @Test
    fun `hasRepeatedSentenceOrPhrase detects repeated trailing sentences while allowing legitimate source repeats`() {
        // Multi-sentence chunk repeating ONLY the last sentence (13 -> 19 words, under 1.75x + 3)
        val multiSource = "The train stopped cleanly. Emergency lights flickered. Why are you staring at me?"
        val multiRepeated = "$multiSource Why are you staring at me?"
        assertTrue(GeminiLiveEngine.hasRepeatedSentenceOrPhrase(multiSource, multiRepeated))

        // Slight wording variation / restart of the final sentence
        val variedRepeated = "$multiSource Why are you just staring at me?"
        assertTrue(GeminiLiveEngine.hasRepeatedSentenceOrPhrase(multiSource, variedRepeated))

        // Unpunctuated clause echo at the end of a turn
        val clauseSource = "Nobody saw him enter the dark room."
        val clauseRepeated = "Nobody saw him enter the dark room, the dark room."
        assertTrue(GeminiLiveEngine.hasRepeatedSentenceOrPhrase(clauseSource, clauseRepeated))

        // Doubled short single-sentence turns (1, 3, and 7 words)
        assertTrue(GeminiLiveEngine.hasRepeatedSentenceOrPhrase("Wait.", "Wait. Wait."))
        assertTrue(GeminiLiveEngine.hasRepeatedSentenceOrPhrase("Wait for me.", "Wait for me. Wait for me."))
        assertTrue(
            GeminiLiveEngine.hasRepeatedSentenceOrPhrase(
                "Why would you do such a thing?",
                "Why would you do such a thing? Why would you do such a thing?"
            )
        )

        // Contraction repeat at sentence end
        assertTrue(
            GeminiLiveEngine.hasRepeatedSentenceOrPhrase(
                "He knew he couldn't stop.",
                "He knew he couldn't stop. He knew he couldn’t stop."
            )
        )

        // Legitimate intentional repeats in the source text must NOT be rejected
        assertFalse(
            GeminiLiveEngine.hasRepeatedSentenceOrPhrase(
                "Wait for me! Wait for me!",
                "Wait for me! Wait for me!"
            )
        )
        assertFalse(
            GeminiLiveEngine.hasRepeatedSentenceOrPhrase(
                "She'd gone home. She'd gone home.",
                "She had gone home. She had gone home."
            )
        )
        assertFalse(
            GeminiLiveEngine.hasRepeatedSentenceOrPhrase(
                "Bye-bye.",
                "Bye, bye."
            )
        )
        assertFalse(
            GeminiLiveEngine.hasRepeatedSentenceOrPhrase(
                "Mr. Kim walked out, and it was raining.",
                "Mr. Kim walked out. And it was raining."
            )
        )
        // But adding an EXTRA 3rd copy of an already-repeated line IS rejected
        assertTrue(
            GeminiLiveEngine.hasRepeatedSentenceOrPhrase(
                "Wait for me! Wait for me!",
                "Wait for me! Wait for me! Wait for me!"
            )
        )
    }

    @Test
    fun `streaming Live session rejects multi-sentence turn that repeats only the last sentence and rotates to clean retry`() = runTest {
        val createdSessions = mutableListOf<FakeReplayLiveSession>()
        val fallbackEngine = object : NarrationEngine {
            override val id = EngineId.SYSTEM_TTS
            override val capabilities = EngineCapabilities(false, false, false, 24000)
            override suspend fun voices(): List<VoiceInfo> = emptyList()
            override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> =
                flowOf(PcmChunk(byteArrayOf(99, 100), 24000, 1))
        }
        val engine = GeminiLiveEngine(
            fallbackEngine = fallbackEngine,
            liveSessionFactory = {
                FakeReplayLiveSession { _, _ -> }.also { createdSessions += it }
            },
            apiKeyProvider = { "test-key" }
        )

        val dispatcher = StandardTestDispatcher(testScheduler)
        val live = engine.openStreamingSession(
            dispatcher = dispatcher,
            bufferAndVerifyTurns = true
        )
        try {
            val sourceText = "The train stopped cleanly. Emergency lights flickered. Why are you staring at me?"
            val repeatedLastSentenceTranscript = "$sourceText Why are you staring at me?"
            val received = mutableListOf<PcmChunk>()

            val job = launch(dispatcher) {
                live.synthesizeTurnStreaming(
                    SynthesisRequest(text = sourceText, voiceId = "Charon")
                ).collect { received += it }
            }

            // First attempt (session 0, gemini-3.8-live) repeats the last sentence -> discarded & rotated
            runCurrent()
            createdSessions[0].emitServerMessage(
                audioServerMessage(
                    pcmBytes = byteArrayOf(5, 6),
                    transcript = repeatedLastSentenceTranscript,
                    turnComplete = true
                )
            )
            runCurrent()

            // Candidate 2 (session 1, fallback Live model) delivers clean verbatim transcript -> emitted
            assertEquals(2, createdSessions.size)
            assertEquals(1, createdSessions[0].disconnectCount)
            createdSessions[1].emitServerMessage(
                audioServerMessage(
                    pcmBytes = byteArrayOf(7, 8),
                    transcript = sourceText,
                    turnComplete = true
                )
            )
            job.join()

            assertEquals(1, received.size)
            assertEquals(7.toByte(), received[0].data[0])
        } finally {
            live.close()
        }
    }

    @Test
    fun `buildSystemInstruction instructs smooth pronunciation of contractions and apostrophes`() {
        val instruction = GeminiLiveEngine.buildSystemInstruction(
            SynthesisRequest(text = "It's fine.", voiceId = "Charon")
        )
        assertTrue(instruction.contains("contractions, possessives, and apostrophe-containing words", ignoreCase = true))
        assertTrue(instruction.contains("without pausing or breaking before an apostrophe", ignoreCase = true))
        assertTrue(instruction.contains("never repeat, echo, or restart the final sentence", ignoreCase = true))
        assertTrue(instruction.contains("continue reading the full text through to the very end", ignoreCase = true))
    }

    @Test
    fun `generationComplete with finished transcription finalizes fast without turnComplete reuses session and ignores stray turnComplete`() = runTest {
        val createdSessions = mutableListOf<FakeReplayLiveSession>()
        var fallbackCalled = false
        val fallbackEngine = object : NarrationEngine {
            override val id = EngineId.SYSTEM_TTS
            override val capabilities = EngineCapabilities(false, false, false, 24000)
            override suspend fun voices(): List<VoiceInfo> = emptyList()
            override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> {
                fallbackCalled = true
                return flowOf(PcmChunk(byteArrayOf(99), 24000, 1))
            }
        }
        val engine = GeminiLiveEngine(
            fallbackEngine = fallbackEngine,
            liveSessionFactory = {
                FakeReplayLiveSession { _, _ -> }.also { createdSessions += it }
            },
            apiKeyProvider = { "test-key" }
        )

        val dispatcher = StandardTestDispatcher(testScheduler)
        val live = engine.openStreamingSession(
            dispatcher = dispatcher,
            bufferAndVerifyTurns = true
        )
        try {
            // Turn 1: generationComplete=true + outputTranscription.finished=true without turnComplete -> completes ~300ms and keeps WebSocket open.
            val turn1Chunks = mutableListOf<PcmChunk>()
            val t1Job = launch(dispatcher) {
                live.synthesizeTurnStreaming(
                    SynthesisRequest(text = "It was a dark night.", voiceId = "Charon")
                ).collect { turn1Chunks += it }
            }
            runCurrent()
            createdSessions.single().emitServerMessage(
                LiveServerMessage.ServerContentMessage(
                    ServerContentData(
                        modelTurn = ModelTurn(
                            parts = listOf(
                                Part(inlineData = Blob("audio/pcm;rate=24000", Base64.getEncoder().encodeToString(byteArrayOf(11, 12))))
                            )
                        ),
                        outputTranscription = TranscriptionData(text = "It was a dark night.", finished = true),
                        generationComplete = true,
                        turnComplete = false
                    )
                )
            )
            runCurrent()
            assertTrue("Should still wait briefly after generationComplete for stray tail packets", turn1Chunks.isEmpty())

            advanceTimeBy(350L)
            runCurrent()
            t1Job.join()

            assertFalse(fallbackCalled)
            assertEquals(1, turn1Chunks.size)
            assertEquals(11.toByte(), turn1Chunks[0].data[0])
            assertEquals(1, createdSessions.size)
            assertEquals(0, createdSessions.single().disconnectCount)
            assertEquals(0, live.sessionRotationCount)

            // Turn 2: reuses exact same WebSocket; delayed stray turnComplete from turn 1 arrives first and must be ignored.
            val turn2Chunks = mutableListOf<PcmChunk>()
            val t2Job = launch(dispatcher) {
                live.synthesizeTurnStreaming(
                    SynthesisRequest(text = "Then the bell rang.", voiceId = "Charon")
                ).collect { turn2Chunks += it }
            }
            runCurrent()
            assertEquals(1, createdSessions.size)
            assertEquals(2, createdSessions.single().sentMessages.size)

            // Delayed turnComplete from turn 1 arrives before turn 2 emits anything -> must NOT abort turn 2!
            createdSessions.single().emitServerMessage(
                LiveServerMessage.ServerContentMessage(ServerContentData(turnComplete = true))
            )
            runCurrent()
            assertFalse("Stray turnComplete from turn 1 must be consumed without terminating turn 2", t2Job.isCompleted)
            assertTrue(turn2Chunks.isEmpty())

            // Now turn 2 emits its own audio + transcript + turnComplete -> succeeds on same session.
            createdSessions.single().emitServerMessage(
                audioServerMessage(
                    pcmBytes = byteArrayOf(21, 22),
                    transcript = "Then the bell rang.",
                    turnComplete = true
                )
            )
            runCurrent()
            t2Job.join()

            assertFalse(fallbackCalled)
            assertEquals(1, createdSessions.size)
            assertEquals(1, turn2Chunks.size)
            assertEquals(21.toByte(), turn2Chunks[0].data[0])
        } finally {
            live.close()
        }
    }

    @Test
    fun `sole Live candidate failing softly once retries on fresh Live session without TTS fallback`() = runTest {
        val createdSessions = mutableListOf<FakeReplayLiveSession>()
        var fallbackCalled = false
        val fallbackEngine = object : NarrationEngine {
            override val id = EngineId.SYSTEM_TTS
            override val capabilities = EngineCapabilities(false, false, false, 24000)
            override suspend fun voices(): List<VoiceInfo> = emptyList()
            override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> {
                fallbackCalled = true
                return flowOf(PcmChunk(byteArrayOf(99), 24000, 1))
            }
        }
        val tracker = GeminiQuotaTracker().apply {
            recordHttpError("gemini-2.5-flash-native-audio-dialog", 404, "not found")
            recordHttpError("gemini-3-flash-live", 404, "not found")
        }
        val engine = GeminiLiveEngine(
            fallbackEngine = fallbackEngine,
            liveSessionFactory = {
                FakeReplayLiveSession { _, _ -> }.also { createdSessions += it }
            },
            apiKeyProvider = { "test-key" },
            quotaTracker = tracker
        )

        val dispatcher = StandardTestDispatcher(testScheduler)
        val live = engine.openStreamingSession(
            dispatcher = dispatcher,
            bufferAndVerifyTurns = true
        )
        try {
            val received = mutableListOf<PcmChunk>()
            val job = launch(dispatcher) {
                live.synthesizeTurnStreaming(
                    SynthesisRequest(text = "The lighthouse beam swept across the harbor.", voiceId = "Charon")
                ).collect { received += it }
            }

            // Pass 1 on sole candidate (gemini-3.8-live) fails softly with mismatched transcript -> rotated.
            runCurrent()
            assertEquals(1, createdSessions.size)
            createdSessions[0].emitServerMessage(
                audioServerMessage(
                    pcmBytes = byteArrayOf(5, 6),
                    transcript = "Completely unrelated hallucination",
                    turnComplete = true
                )
            )
            runCurrent()

            // Soft-retry opens a fresh session on gemini-3.8-live and succeeds WITHOUT falling back to TTS.
            assertEquals(2, createdSessions.size)
            assertEquals(1, createdSessions[0].disconnectCount)
            assertFalse(fallbackCalled)

            createdSessions[1].emitServerMessage(
                audioServerMessage(
                    pcmBytes = byteArrayOf(7, 8),
                    transcript = "The lighthouse beam swept across the harbor.",
                    turnComplete = true
                )
            )
            runCurrent()
            job.join()

            assertFalse(fallbackCalled)
            assertEquals(1, received.size)
            assertEquals(7.toByte(), received[0].data[0])
        } finally {
            live.close()
        }
    }

    @Test
    fun `hasRepeatedSentenceOrPhrase catches 1-word repeats fuzzy ASR drift contraction drift partial echoes clause repeats and restarts`() {
        // 1-word final sentence repeat in multi-sentence turn
        assertTrue(
            GeminiLiveEngine.hasRepeatedSentenceOrPhrase(
                "I looked at him. Run.",
                "I looked at him. Run. Run."
            )
        )
        assertFalse(
            GeminiLiveEngine.hasRepeatedSentenceOrPhrase(
                "I looked at him. Run. Run.",
                "I looked at him. Run. Run."
            )
        )

        // Fuzzy ASR spelling drift across repeated sentence
        assertTrue(
            GeminiLiveEngine.hasRepeatedSentenceOrPhrase(
                "Bihyung smiled coldly.",
                "Bihyung smiled coldly. Bihyoung smiled coldly."
            )
        )

        // Contraction expansion drift across repeated sentence
        assertTrue(
            GeminiLiveEngine.hasRepeatedSentenceOrPhrase(
                "I'm ready.",
                "I'm ready. I am ready."
            )
        )
        assertFalse(
            GeminiLiveEngine.hasRepeatedSentenceOrPhrase(
                "I'm ready.",
                "I am ready."
            )
        )

        // Partial trailing echo
        assertTrue(
            GeminiLiveEngine.hasRepeatedSentenceOrPhrase(
                "I opened the door. Nobody was inside.",
                "I opened the door. Nobody was inside. Nobody was."
            )
        )

        // Comma-joined clause repeat
        assertTrue(
            GeminiLiveEngine.hasRepeatedSentenceOrPhrase(
                "He stepped back, then turned around.",
                "He stepped back, then turned around, then turned around."
            )
        )
        assertTrue(
            GeminiLiveEngine.hasRepeatedSentenceOrPhrase(
                "He stepped back and waited.",
                "He stepped back and waited, stepped back and waited."
            )
        )

        // False-start restart mid-sentence
        assertTrue(
            GeminiLiveEngine.hasRepeatedSentenceOrPhrase(
                "We had to survive the first scenario.",
                "We had to survive— We had to survive the first scenario."
            )
        )

        // Existing legitimate repeat negative cases must stay false
        assertFalse(
            GeminiLiveEngine.hasRepeatedSentenceOrPhrase(
                "Wait for me! Wait for me!",
                "Wait for me! Wait for me!"
            )
        )
        assertFalse(
            GeminiLiveEngine.hasRepeatedSentenceOrPhrase(
                "She'd gone home. She'd gone home.",
                "She had gone home. She had gone home."
            )
        )
        assertFalse(
            GeminiLiveEngine.hasRepeatedSentenceOrPhrase(
                "Bye-bye.",
                "Bye, bye."
            )
        )
        assertFalse(
            GeminiLiveEngine.hasRepeatedSentenceOrPhrase(
                "Mr. Kim walked out, and it was raining.",
                "Mr. Kim walked out. And it was raining."
            )
        )
        assertFalse(
            GeminiLiveEngine.hasRepeatedSentenceOrPhrase(
                "Mr. Kim didn't walk out, and it was raining.",
                "Mr. Kim did not walk out. And it was raining."
            )
        )
    }

    @Test
    fun `compressExcessiveInternalSilence shortens long internal stalls smoothly while preserving normal pauses and tiny chunks`() {
        fun encodeSamples(samples: ShortArray): ByteArray {
            val out = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val s = samples[i].toInt()
                out[i * 2] = (s and 0xFF).toByte()
                out[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
            }
            return out
        }

        // Tiny synthetic chunk stays untouched.
        val tiny = listOf(PcmChunk(byteArrayOf(1, 2, 3, 4), sampleRate = 24000, channels = 1))
        assertEquals(tiny, GeminiLiveEngine.compressExcessiveInternalSilence(tiny))

        // Normal 400ms internal pause (9,600 frames <= 750ms) stays untouched.
        val normalSamples = ShortArray(12_000 + 9_600 + 12_000) { i ->
            when {
                i < 12_000 || i >= 21_600 -> (4000.0 * Math.sin(i * 0.08)).toInt().toShort()
                else -> 0
            }
        }
        val normalChunks = listOf(PcmChunk(encodeSamples(normalSamples), sampleRate = 24000, channels = 1))
        assertEquals(normalChunks, GeminiLiveEngine.compressExcessiveInternalSilence(normalChunks))

        // 500ms speech + 2,500ms low-floor internal silence stall + 500ms speech -> compressed to ~420ms silence with no glitch!
        val speechFrames = 12_000
        val stallFrames = 60_000
        val totalFrames = speechFrames + stallFrames + speechFrames
        val stallSamples = ShortArray(totalFrames) { i ->
            when {
                i < speechFrames -> {
                    val env = if (i >= speechFrames - 480) (speechFrames - i) / 480.0 else 1.0
                    (5000.0 * env * Math.sin(i * 0.08)).toInt().toShort()
                }
                i >= speechFrames + stallFrames -> {
                    val offset = i - (speechFrames + stallFrames)
                    val env = if (offset < 480) offset / 480.0 else 1.0
                    (5000.0 * env * Math.sin(i * 0.08)).toInt().toShort()
                }
                else -> if (i % 2 == 0) 80 else -80
            }
        }
        val stallBytes = encodeSamples(stallSamples)
        val splitPoint = 30_000 * 2
        val stallChunks = listOf(
            PcmChunk(stallBytes.copyOfRange(0, splitPoint), sampleRate = 24000, channels = 1),
            PcmChunk(stallBytes.copyOfRange(splitPoint, stallBytes.size), sampleRate = 24000, channels = 1)
        )

        val compressed = GeminiLiveEngine.compressExcessiveInternalSilence(stallChunks)
        assertEquals(1, compressed.size)
        assertFalse("Compressed PCM must have no glitch or click", GeminiLiveEngine.hasPcmGlitch(compressed))

        // Expected silence compressed from 2,500ms (60,000 frames) down to 420ms (10,080 frames), removing ~49,920 frames.
        val compressedFrames = compressed.single().data.size / 2
        val expectedApproxFrames = totalFrames - (stallFrames - 10_080)
        assertTrue(
            "Expected compressed frame count around $expectedApproxFrames, got $compressedFrames",
            compressedFrames in (expectedApproxFrames - 200)..(expectedApproxFrames + 200)
        )
    }

    @Test
    fun `empty-transcript duration sanity guard rejects 20-word turn with 500ms audio and retries cleanly`() = runTest {
        val createdSessions = mutableListOf<FakeReplayLiveSession>()
        var fallbackCalled = false
        val fallbackEngine = object : NarrationEngine {
            override val id = EngineId.SYSTEM_TTS
            override val capabilities = EngineCapabilities(false, false, false, 24000)
            override suspend fun voices(): List<VoiceInfo> = emptyList()
            override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> {
                fallbackCalled = true
                return flowOf(PcmChunk(byteArrayOf(99), 24000, 1))
            }
        }
        val engine = GeminiLiveEngine(
            fallbackEngine = fallbackEngine,
            liveSessionFactory = {
                FakeReplayLiveSession { _, _ -> }.also { createdSessions += it }
            },
            apiKeyProvider = { "test-key" }
        )

        fun smoothToneBytes(durationMs: Int): ByteArray {
            val frames = 24 * durationMs
            val out = ByteArray(frames * 2)
            for (i in 0 until frames) {
                val sample = (4000.0 * Math.sin(i * 0.08)).toInt()
                out[i * 2] = (sample and 0xFF).toByte()
                out[i * 2 + 1] = ((sample ushr 8) and 0xFF).toByte()
            }
            return out
        }

        val twentyWords = "One two three four five six seven eight nine ten " +
            "eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty."
        val short500ms = smoothToneBytes(500)
        val valid3000ms = smoothToneBytes(3_000)

        val dispatcher = StandardTestDispatcher(testScheduler)
        val live = engine.openStreamingSession(
            dispatcher = dispatcher,
            bufferAndVerifyTurns = true
        )
        try {
            val received = mutableListOf<PcmChunk>()
            val job = launch(dispatcher) {
                live.synthesizeTurnStreaming(
                    SynthesisRequest(text = twentyWords, voiceId = "Charon")
                ).collect { received += it }
            }

            // Candidate 0 emits 500ms audio with empty transcript (< 2,000ms minimum for 20 words) -> rejected!
            runCurrent()
            createdSessions[0].emitServerMessage(
                audioServerMessage(pcmBytes = short500ms, transcript = null, turnComplete = true)
            )
            runCurrent()

            assertEquals(2, createdSessions.size)
            assertEquals(1, createdSessions[0].disconnectCount)
            assertTrue(received.isEmpty())

            // Candidate 1 emits 3,000ms audio with empty transcript -> passes duration sanity check and emits!
            createdSessions[1].emitServerMessage(
                audioServerMessage(pcmBytes = valid3000ms, transcript = null, turnComplete = true)
            )
            runCurrent()
            job.join()

            assertFalse(fallbackCalled)
            assertEquals(valid3000ms.size, received.sumOf { it.data.size })
        } finally {
            live.close()
        }
    }

    @Test
    fun `partial mid-stream transcript waits up to 4500ms while generationComplete without finished finalizes at 500ms`() = runTest {
        val createdSessions = mutableListOf<FakeReplayLiveSession>()
        val engine = GeminiLiveEngine(
            liveSessionFactory = {
                FakeReplayLiveSession { _, _ -> }.also { createdSessions += it }
            },
            apiKeyProvider = { "test-key" }
        )

        val dispatcher = StandardTestDispatcher(testScheduler)
        val live = engine.openStreamingSession(
            dispatcher = dispatcher,
            bufferAndVerifyTurns = true
        )
        try {
            // 1. Mid-stream partial transcript (< 85% of 10 words) without generationComplete waits past 2,600ms up to 4,500ms.
            val tenWords = "One two three four five six seven eight nine ten."
            val chunks1 = mutableListOf<PcmChunk>()
            val j1 = launch(dispatcher) {
                live.synthesizeTurnStreaming(SynthesisRequest(text = tenWords, voiceId = "Charon")).collect { chunks1 += it }
            }
            runCurrent()
            createdSessions[0].emitServerMessage(
                audioServerMessage(byteArrayOf(11, 12), transcript = "One two three", turnComplete = false)
            )
            runCurrent()
            advanceTimeBy(3_000L)
            runCurrent()
            assertFalse("Mid-stream <85% transcript must not time out at 2,500ms", j1.isCompleted)

            // Remaining transcript arrives at 3,000ms -> now >= 85% caught up, finalizes on 2,500ms post-audio idle timeout.
            createdSessions[0].emitServerMessage(
                audioServerMessage(byteArrayOf(13, 14), transcript = " four five six seven eight nine ten.", turnComplete = false)
            )
            runCurrent()
            advanceTimeBy(2_600L)
            runCurrent()
            j1.join()
            assertEquals(2, chunks1.size)

            // 2. generationComplete=true without transcription.finished finalizes after 500ms idle and reuses session.
            val chunks2 = mutableListOf<PcmChunk>()
            val j2 = launch(dispatcher) {
                live.synthesizeTurnStreaming(SynthesisRequest(text = "Short line.", voiceId = "Charon")).collect { chunks2 += it }
            }
            runCurrent()
            createdSessions[1].emitServerMessage(
                LiveServerMessage.ServerContentMessage(
                    ServerContentData(
                        modelTurn = ModelTurn(
                            parts = listOf(Part(inlineData = Blob("audio/pcm;rate=24000", Base64.getEncoder().encodeToString(byteArrayOf(21, 22)))))
                        ),
                        outputTranscription = TranscriptionData(text = "Short line.", finished = null),
                        generationComplete = true,
                        turnComplete = false
                    )
                )
            )
            runCurrent()
            advanceTimeBy(350L)
            runCurrent()
            assertFalse("Without finished=true should wait up to 500ms", j2.isCompleted)

            advanceTimeBy(200L)
            runCurrent()
            j2.join()
            assertEquals(1, chunks2.size)
            assertEquals(0, createdSessions[1].disconnectCount)
        } finally {
            live.close()
        }
    }
}
