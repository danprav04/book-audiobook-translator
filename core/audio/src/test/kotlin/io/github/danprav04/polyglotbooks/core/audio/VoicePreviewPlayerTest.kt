package io.github.danprav04.polyglotbooks.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException

class VoicePreviewPlayerTest {

    private class FakeBackend(
        val voiceId: String,
        val assetPath: String,
        val onCompletion: () -> Unit,
        val onError: (String) -> Unit,
        private val startError: Throwable? = null
    ) : VoicePreviewBackend {
        var started = false
        var stoppedAndReleased = false

        override fun start() {
            started = true
            if (startError != null) {
                throw startError
            }
        }

        override fun stopAndRelease() {
            stoppedAndReleased = true
        }
    }

    @Test
    fun `togglePreview plays canonical lowercase m4a asset invokes onBeforeStart and toggles off cleanly`() {
        val created = mutableListOf<FakeBackend>()
        val player = AssetVoicePreviewPlayer(
            VoicePreviewBackendFactory { voiceId, assetPath, onCompletion, onError ->
                FakeBackend(voiceId, assetPath, onCompletion, onError).also { created += it }
            }
        )

        assertNull(player.playingVoiceId.value)
        assertNull(player.previewError.value)

        var beforeStartCalls = 0
        player.togglePreview("charon") { beforeStartCalls++ }

        assertEquals(1, beforeStartCalls)
        assertEquals("Charon", player.playingVoiceId.value)
        assertNull(player.previewError.value)
        assertEquals(1, created.size)
        assertEquals("voice_previews/charon.m4a", created[0].assetPath)
        assertTrue(created[0].started)

        // Toggling the same voice stops playback without calling onBeforeStart again.
        player.togglePreview("Charon") { beforeStartCalls++ }

        assertEquals(1, beforeStartCalls)
        assertNull(player.playingVoiceId.value)
        assertTrue(created[0].stoppedAndReleased)
    }

    @Test
    fun `switching to a different voice auto stops previous preview first`() {
        val events = mutableListOf<String>()
        val backends = mutableMapOf<String, FakeBackend>()
        val player = AssetVoicePreviewPlayer(
            VoicePreviewBackendFactory { voiceId, assetPath, onCompletion, onError ->
                val backend = object : VoicePreviewBackend {
                    override fun start() {
                        events += "start:$voiceId"
                    }

                    override fun stopAndRelease() {
                        events += "release:$voiceId"
                    }
                }
                backends[voiceId] = FakeBackend(voiceId, assetPath, onCompletion, onError)
                backend
            }
        )

        player.togglePreview("Charon") { events += "before:Charon" }
        assertEquals("Charon", player.playingVoiceId.value)

        player.togglePreview("Kore") { events += "before:Kore" }
        assertEquals("Kore", player.playingVoiceId.value)

        assertEquals(
            listOf("before:Charon", "start:Charon", "release:Charon", "before:Kore", "start:Kore"),
            events
        )
    }

    @Test
    fun `completion resets playingVoiceId and ignores stale completion from previous clip`() {
        val created = mutableListOf<FakeBackend>()
        val player = AssetVoicePreviewPlayer(
            VoicePreviewBackendFactory { voiceId, assetPath, onCompletion, onError ->
                FakeBackend(voiceId, assetPath, onCompletion, onError).also { created += it }
            }
        )

        player.togglePreview("Charon")
        player.togglePreview("Puck")
        assertEquals("Puck", player.playingVoiceId.value)

        // Stale completion from Charon must not interrupt Puck.
        created[0].onCompletion()
        assertEquals("Puck", player.playingVoiceId.value)

        // Completion for active voice resets to null and releases backend.
        created[1].onCompletion()
        assertNull(player.playingVoiceId.value)
        assertTrue(created[1].stoppedAndReleased)
    }

    @Test
    fun `missing asset sets friendly error and subsequent valid preview clears it`() {
        var shouldThrowMissing = true
        val player = AssetVoicePreviewPlayer(
            VoicePreviewBackendFactory { voiceId, assetPath, onCompletion, onError ->
                if (shouldThrowMissing) {
                    throw FileNotFoundException("Missing asset: $assetPath")
                }
                FakeBackend(voiceId, assetPath, onCompletion, onError)
            }
        )

        player.togglePreview("Aoede")
        assertNull(player.playingVoiceId.value)
        assertNotNull(player.previewError.value)
        assertTrue(player.previewError.value!!.contains("Aoede"))

        shouldThrowMissing = false
        player.togglePreview("Aoede")
        assertEquals("Aoede", player.playingVoiceId.value)
        assertNull(player.previewError.value)
    }

    @Test
    fun `start exception or async error cleans up backend and stop clears state`() {
        val created = mutableListOf<FakeBackend>()
        var throwOnStart = true
        val player = AssetVoicePreviewPlayer(
            VoicePreviewBackendFactory { voiceId, assetPath, onCompletion, onError ->
                FakeBackend(
                    voiceId = voiceId,
                    assetPath = assetPath,
                    onCompletion = onCompletion,
                    onError = onError,
                    startError = if (throwOnStart) IllegalStateException("decoder fail") else null
                ).also { created += it }
            }
        )

        player.togglePreview("Fenrir")
        assertNull(player.playingVoiceId.value)
        assertTrue(created[0].stoppedAndReleased)
        assertTrue(player.previewError.value?.contains("decoder fail") == true)

        throwOnStart = false
        player.togglePreview("Fenrir")
        assertEquals("Fenrir", player.playingVoiceId.value)
        assertNull(player.previewError.value)

        created[1].onError("MediaPlayer error")
        assertNull(player.playingVoiceId.value)
        assertEquals("MediaPlayer error", player.previewError.value)
        assertTrue(created[1].stoppedAndReleased)

        player.stop()
        assertNull(player.playingVoiceId.value)
        assertNull(player.previewError.value)
    }

    @Test
    fun `blank voice id sets error and stops active preview without invoking onBeforeStart`() {
        val created = mutableListOf<FakeBackend>()
        val player = AssetVoicePreviewPlayer(
            VoicePreviewBackendFactory { voiceId, assetPath, onCompletion, onError ->
                FakeBackend(voiceId, assetPath, onCompletion, onError).also { created += it }
            }
        )

        player.togglePreview("Charon")
        var beforeCalls = 0
        player.togglePreview("   ") { beforeCalls++ }

        assertEquals(0, beforeCalls)
        assertNull(player.playingVoiceId.value)
        assertTrue(created[0].stoppedAndReleased)
        assertNotNull(player.previewError.value)
    }
}
