package io.github.danprav04.polyglotbooks.core.audio

import io.github.danprav04.polyglotbooks.core.data.db.TranslationCacheDao
import io.github.danprav04.polyglotbooks.core.data.db.TranslatedSegmentEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranslatedCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheDao: TranslationCacheDao
    private lateinit var cache: DiskTranslatedCache
    private lateinit var cacheDir: java.io.File

    @Before
    fun setUp() {
        cacheDao = mockk(relaxed = true)
        cacheDir = tempFolder.newFolder("audio_cache")
        cache = DiskTranslatedCache(cacheDir, cacheDao)
    }

    @Test
    fun testPutAndGetSegment() = runTest {
        val pcmData = byteArrayOf(1, 2, 3, 4, 5, 6)
        val entity = TranslatedSegmentEntity(
            id = "b1_es_0_1000",
            bookId = "b1",
            targetLang = "es",
            sourceStartMs = 0L,
            sourceEndMs = 1000L,
            aacFilePath = java.io.File(cacheDir, "test.pcm").absolutePath,
            fileSizeBytes = 6L,
            createdAt = 100L
        )

        coEvery { cacheDao.getSegmentAt("b1", "es", 500L) } returns entity

        cache.putSegment("b1", "es", 0L, 1000L, pcmData)
        val found = cache.findCachedRange("b1", "es", 500L)

        assertNotNull(found)
        assertEquals("b1", found?.bookId)
        assertEquals("es", found?.targetLang)
    }

    @Test
    fun testEvictToMaxBytes() = runTest {
        val seg1 = TranslatedSegmentEntity(
            id = "s1", bookId = "b1", targetLang = "en",
            sourceStartMs = 0, sourceEndMs = 1000,
            aacFilePath = java.io.File(cacheDir, "seg1.pcm").absolutePath.also { java.io.File(it).writeBytes(ByteArray(100)) },
            fileSizeBytes = 100L, createdAt = 10L
        )
        val seg2 = TranslatedSegmentEntity(
            id = "s2", bookId = "b1", targetLang = "en",
            sourceStartMs = 1000, sourceEndMs = 2000,
            aacFilePath = java.io.File(cacheDir, "seg2.pcm").absolutePath.also { java.io.File(it).writeBytes(ByteArray(100)) },
            fileSizeBytes = 100L, createdAt = 20L
        )

        coEvery { cacheDao.getTotalCacheSizeBytes() } returns 200L
        coEvery { cacheDao.getOldestSegments() } returns listOf(seg1, seg2)

        cache.evictToMaxBytes(120L)

        coVerify { cacheDao.deleteSegment(seg1) }
    }
}
