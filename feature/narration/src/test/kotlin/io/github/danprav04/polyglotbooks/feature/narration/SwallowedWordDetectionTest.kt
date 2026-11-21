package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwallowedWordDetectionTest {
    private val source = "The old lighthouse keeper climbed the spiral stairs every single evening at dusk."

    @Test
    fun `identical transcript has no swallowed words`() {
        assertEquals(0, GeminiLiveEngine.countSwallowedSourceWords(source, source))
    }

    @Test
    fun `single swallowed word is detected even if word repeats elsewhere`() {
        val out = "The old lighthouse keeper climbed spiral stairs every single evening at dusk."
        assertEquals(1, GeminiLiveEngine.countSwallowedSourceWords(source, out))
    }

    @Test
    fun `clipped word fragment counts as swallowed`() {
        val out = "The old lighthouse keeper climbed the sp stairs every single evening at dusk."
        assertEquals(1, GeminiLiveEngine.countSwallowedSourceWords(source, out))
    }

    @Test
    fun `hyphen and apostrophe differences are tolerated`() {
        assertEquals(0, GeminiLiveEngine.countSwallowedSourceWords("A well-known man's story ends here today", "A well known mans story ends here today"))
    }

    @Test
    fun `late arriving tail words are ignored`() {
        assertEquals(0, GeminiLiveEngine.countSwallowedSourceWords(source, "The old lighthouse keeper climbed the spiral stairs every single evening"))
    }

    @Test
    fun `short turns tolerate none while long turns tolerate a few`() {
        assertEquals(0, GeminiLiveEngine.allowedSwallowedWords(20))
        assertEquals(2, GeminiLiveEngine.allowedSwallowedWords(70))
    }

    private fun pcm(samples: IntArray): PcmChunk {
        val b = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, v -> b[i * 2] = (v and 0xFF).toByte(); b[i * 2 + 1] = ((v shr 8) and 0xFF).toByte() }
        return PcmChunk(b, 24000, 1)
    }

    private fun tone(n: Int, amp: Int = 6000) = IntArray(n) { (amp * Math.sin(it * 0.1)).toInt() }

    @Test
    fun `clean tone has no glitch`() {
        assertFalse(GeminiLiveEngine.hasPcmGlitch(listOf(pcm(tone(24000)))))
    }

    @Test
    fun `abrupt dropout inside speech is a glitch`() {
        val s = tone(24000)
        for (i in 10000 until 10600) s[i] = 0
        s[9999] = 5000; s[10600] = 5000
        assertTrue(GeminiLiveEngine.hasPcmGlitch(listOf(pcm(s))))
    }

    @Test
    fun `smooth pause is not a glitch`() {
        val s = tone(24000)
        for (i in 10000 until 14000) s[i] = (s[i] * (14000 - i) / 4000.0).toInt()
        assertFalse(GeminiLiveEngine.hasPcmGlitch(listOf(pcm(s))))
    }

    @Test
    fun `sample discontinuity click is a glitch`() {
        val s = tone(24000)
        s[5000] = 25000; s[5001] = -25000
        assertTrue(GeminiLiveEngine.hasPcmGlitch(listOf(pcm(s))))
    }

    @Test
    fun `clean high-sibilant speech jump under 45k is not flagged as glitch while 50k click is caught`() {
        val s = tone(24000)
        // Observed clean 24kHz Zephyr and 2.5-native-audio speech jumps reach ~22,845..36,283 sample delta.
        s[4000] = 18_500
        s[4001] = -18_500
        assertFalse(GeminiLiveEngine.hasPcmGlitch(listOf(pcm(s))))

        s[5000] = 25_000
        s[5001] = -25_000
        assertTrue(GeminiLiveEngine.hasPcmGlitch(listOf(pcm(s))))
    }

    @Test
    fun `ignoredTailWords 3 tolerates trailing ASR lag on long turn while still catching mid-sentence drop`() {
        val longSource = "The fallen constellations who were watching from above were genuinely startled by his bold words but that was for then."
        val tailLagged = "The fallen constellations who were watching from above were genuinely startled by his bold words but that"
        assertEquals(0, GeminiLiveEngine.countSwallowedSourceWords(longSource, tailLagged, ignoredTailWords = 3))

        val midDrop = "The fallen constellations were watching from above were genuinely startled by his bold words but that was for then."
        assertEquals(1, GeminiLiveEngine.countSwallowedSourceWords(longSource, midDrop, ignoredTailWords = 3))
    }

    @Test
    fun `compressExcessiveInternalSilence preserves smooth transitions across multiple long gaps`() {
        val sr = 24000
        val speech = tone(sr / 4, amp = 5000) // 250ms active
        for (i in 0 until 240) {
            speech[i] = (speech[i] * i / 240.0).toInt()
            speech[speech.lastIndex - i] = (speech[speech.lastIndex - i] * i / 240.0).toInt()
        }
        val longGap = IntArray(sr * 2) { if (it % 2 == 0) 60 else -60 } // 2s low-floor silence
        val combined = speech + longGap + speech + longGap + speech
        val chunks = listOf(pcm(combined))
        assertFalse(GeminiLiveEngine.hasPcmGlitch(chunks))

        val compressed = GeminiLiveEngine.compressExcessiveInternalSilence(chunks)
        assertEquals(1, compressed.size)
        assertFalse(GeminiLiveEngine.hasPcmGlitch(compressed))
        // 3 * 6000 speech + 2 * ~10080 compressed gaps = ~38160 frames (original was 114000 frames)
        val compressedFrames = compressed.single().data.size / 2
        assertTrue("Expected < 40000 frames, got $compressedFrames", compressedFrames in 36000..40000)
    }
}
