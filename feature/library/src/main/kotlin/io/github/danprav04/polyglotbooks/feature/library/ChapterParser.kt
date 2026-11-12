package io.github.danprav04.polyglotbooks.feature.library

import io.github.danprav04.polyglotbooks.core.model.Chapter
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Interface for parsing chapters out of ebook and audiobook containers.
 */
interface ChapterParser {
    suspend fun parseChapters(file: File, format: BookFormat): List<Chapter>
    suspend fun parseChapters(inputStream: InputStream, format: BookFormat): List<Chapter>
}

class DefaultChapterParser : ChapterParser {
    override suspend fun parseChapters(file: File, format: BookFormat): List<Chapter> {
        return file.inputStream().use { parseChapters(it, format) }
    }

    override suspend fun parseChapters(inputStream: InputStream, format: BookFormat): List<Chapter> {
        return when (format) {
            BookFormat.CUE -> CueSheetChapterParser().parseChapters(inputStream, format)
            BookFormat.M4B, BookFormat.MP3 -> {
                // Read up to 8MB max to avoid OOM on large audiobook files
                val maxRead = 8 * 1024 * 1024
                val buffer = ByteArray(maxRead)
                val readTotal = inputStream.read(buffer)
                val bytes = if (readTotal > 0) buffer.copyOf(readTotal) else byteArrayOf()
                if (format == BookFormat.M4B) {
                    Mp4ChapterParser().parseChapters(bytes)
                } else {
                    Id3ChapChapterParser().parseChapters(bytes)
                }
            }
            else -> emptyList()
        }
    }
}

class CueSheetChapterParser {
    fun parseChapters(inputStream: InputStream, format: BookFormat = BookFormat.CUE): List<Chapter> {
        val lines = BufferedReader(InputStreamReader(inputStream)).readLines()
        val chapters = mutableListOf<Chapter>()
        var currentTitle: String? = null
        var startTimeMs: Long = 0L
        var trackNumber = 1

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("TITLE ") -> {
                    currentTitle = trimmed.removePrefix("TITLE").trim().removeSurrounding("\"")
                }
                trimmed.startsWith("INDEX 01 ") -> {
                    val timeStr = trimmed.removePrefix("INDEX 01").trim()
                    val parts = timeStr.split(":")
                    if (parts.size >= 3) {
                        val mm = parts[0].toLongOrNull() ?: 0L
                        val ss = parts[1].toLongOrNull() ?: 0L
                        val ff = parts[2].toLongOrNull() ?: 0L
                        val totalMs = (mm * 60 + ss) * 1000 + (ff * 1000) / 75
                        
                        if (chapters.isNotEmpty()) {
                            val prev = chapters.last()
                            chapters[chapters.size - 1] = prev.copy(endMs = totalMs)
                        }
                        startTimeMs = totalMs
                        chapters.add(
                            Chapter(
                                id = "cue_track_${trackNumber}",
                                bookId = "",
                                index = trackNumber - 1,
                                title = currentTitle ?: "Track $trackNumber",
                                startMs = startTimeMs,
                                endMs = startTimeMs + 60000L
                            )
                        )
                        trackNumber++
                    }
                }
            }
        }
        return chapters
    }
}

class Id3ChapChapterParser {
    fun parseChapters(bytes: ByteArray): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        var i = 0
        var orderIndex = 0
        while (i < bytes.size - 16) {
            if (bytes[i] == 'C'.code.toByte() &&
                bytes[i+1] == 'H'.code.toByte() &&
                bytes[i+2] == 'A'.code.toByte() &&
                bytes[i+3] == 'P'.code.toByte()) {
                
                try {
                    val size = ByteBuffer.wrap(bytes, i + 4, 4).order(ByteOrder.BIG_ENDIAN).int
                    val frameDataStart = i + 10
                    if (frameDataStart + size <= bytes.size) {
                        var idx = frameDataStart
                        while (idx < frameDataStart + size && bytes[idx] != 0.toByte()) {
                            idx++
                        }
                        val elementId = String(bytes, frameDataStart, idx - frameDataStart, Charsets.ISO_8859_1)
                        idx++
                        
                        if (idx + 12 <= frameDataStart + size) {
                            val startTime = ByteBuffer.wrap(bytes, idx, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                            val endTime = ByteBuffer.wrap(bytes, idx + 4, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                            
                            var title = "Chapter $elementId"
                            val subDataEnd = frameDataStart + size
                            var subIdx = idx + 16
                            while (subIdx < subDataEnd - 10) {
                                if (bytes[subIdx] == 'T'.code.toByte() &&
                                    bytes[subIdx+1] == 'I'.code.toByte() &&
                                    bytes[subIdx+2] == 'T'.code.toByte() &&
                                    bytes[subIdx+3] == '2'.code.toByte()) {
                                    val subSize = ByteBuffer.wrap(bytes, subIdx + 4, 4).order(ByteOrder.BIG_ENDIAN).int
                                    if (subIdx + 10 + subSize <= subDataEnd) {
                                        val encoding = bytes[subIdx + 10]
                                        val textBytes = bytes.copyOfRange(subIdx + 11, subIdx + 10 + subSize)
                                        title = if (encoding == 1.toByte() || encoding == 2.toByte()) {
                                            String(textBytes, Charsets.UTF_16)
                                        } else {
                                            String(textBytes, Charsets.UTF_8)
                                        }.trim('\u0000', ' ')
                                    }
                                    break
                                }
                                subIdx++
                            }

                            chapters.add(
                                Chapter(
                                    id = elementId,
                                    bookId = "",
                                    index = orderIndex++,
                                    title = title,
                                    startMs = startTime,
                                    endMs = endTime
                                )
                            )
                        }
                    }
                    i += 10 + size
                } catch (e: Exception) {
                    i++
                }
            } else {
                i++
            }
        }
        return chapters
    }
}

class Mp4ChapterParser {
    fun parseChapters(bytes: ByteArray): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        var i = 0
        while (i < bytes.size - 8) {
            if (bytes[i] == 'c'.code.toByte() &&
                bytes[i+1] == 'h'.code.toByte() &&
                bytes[i+2] == 'p'.code.toByte() &&
                bytes[i+3] == 'l'.code.toByte()) {
                
                try {
                    val dataStart = i + 4
                    if (dataStart < bytes.size) {
                        val count = bytes[dataStart + 4].toInt() and 0xFF
                        var cursor = dataStart + 5
                        for (idx in 0 until count) {
                            if (cursor + 9 > bytes.size) break
                            val startTime100ns = ByteBuffer.wrap(bytes, cursor, 8).order(ByteOrder.BIG_ENDIAN).long
                            val startTimeMs = startTime100ns / 10000L
                            val titleLen = bytes[cursor + 8].toInt() and 0xFF
                            cursor += 9
                            if (cursor + titleLen > bytes.size) break
                            val title = String(bytes, cursor, titleLen, Charsets.UTF_8)
                            cursor += titleLen

                            val endTimeMs = if (idx > 0 && chapters.isNotEmpty()) {
                                val prev = chapters.last()
                                chapters[chapters.size - 1] = prev.copy(endMs = startTimeMs)
                                startTimeMs + 60000L
                            } else {
                                startTimeMs + 60000L
                            }

                            chapters.add(
                                Chapter(
                                    id = "mp4_chap_$idx",
                                    bookId = "",
                                    index = idx,
                                    title = title,
                                    startMs = startTimeMs,
                                    endMs = endTimeMs
                                )
                            )
                        }
                    }
                    break
                } catch (e: Exception) {
                    i++
                }
            } else {
                i++
            }
        }
        return chapters
    }
}

class FolderChapterParser {
    fun parseChapters(audioFiles: List<AudioFileEntry>): List<Chapter> {
        var cumulativeTime = 0L
        return audioFiles.mapIndexed { index, entry ->
            val startTime = cumulativeTime
            cumulativeTime += entry.durationMs
            Chapter(
                id = "folder_chap_$index",
                bookId = "",
                index = index,
                title = entry.name.substringBeforeLast('.'),
                startMs = startTime,
                endMs = cumulativeTime
            )
        }
    }
}

data class AudioFileEntry(
    val name: String,
    val uri: String,
    val durationMs: Long
)
