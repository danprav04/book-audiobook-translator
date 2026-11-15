package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.data.db.CharacterEntity
import io.github.danprav04.polyglotbooks.core.data.db.NarrationLineEntity
import io.github.danprav04.polyglotbooks.core.gemini.Blob
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
}
