package io.github.danprav04.polyglotbooks.feature.library

import io.github.danprav04.polyglotbooks.core.data.CoverExtractor
import io.github.danprav04.polyglotbooks.core.model.Chapter
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Interface for parsing chapters out of ebook and audiobook containers.
 */
interface ChapterParser {
    suspend fun parseChapters(file: File, format: BookFormat): List<Chapter>
    suspend fun parseChapters(inputStream: InputStream, format: BookFormat): List<Chapter>
}

class DefaultChapterParser : ChapterParser {
    override suspend fun parseChapters(file: File, format: BookFormat): List<Chapter> {
        if (format.isAudiobook) {
            // Check companion .cue file
            val parent = file.parentFile
            val cueFile = if (parent != null) File(parent, "${file.nameWithoutExtension}.cue") else File("${file.nameWithoutExtension}.cue")
            if (cueFile.exists() && cueFile.canRead()) {
                val chapters = try {
                    cueFile.inputStream().use { CueSheetChapterParser().parseChapters(it, BookFormat.CUE) }
                } catch (_: Exception) {
                    emptyList()
                }
                if (chapters.isNotEmpty()) return chapters
            }

            if (format == BookFormat.M4B) {
                val chapters = Mp4ChapterParser().parseChapters(file)
                if (chapters.isNotEmpty()) return chapters
            }
        }
        return file.inputStream().use { parseChapters(it, format) }
    }

    override suspend fun parseChapters(inputStream: InputStream, format: BookFormat): List<Chapter> {
        return when (format) {
            BookFormat.CUE -> CueSheetChapterParser().parseChapters(inputStream, format)
            BookFormat.M4B, BookFormat.MP3 -> {
                if (format == BookFormat.M4B && inputStream is FileInputStream) {
                    val channel = inputStream.channel
                    val chapters = CoverExtractor.extractChapters(channel, channel.size())
                    if (chapters.isNotEmpty()) return chapters
                }
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
                    if (size > 0 && frameDataStart + size <= bytes.size) {
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
                                    if (subSize > 0 && subIdx + 10 + subSize <= subDataEnd) {
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
                        i += 10 + size
                    } else {
                        i++
                    }
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
    fun parseChapters(bytes: ByteArray, bookId: String = ""): List<Chapter> {
        return CoverExtractor.extractChapters(bytes, bookId)
    }

    fun parseChapters(file: File, bookId: String = ""): List<Chapter> {
        return CoverExtractor.extractChapters(file, bookId)
    }

    fun parseChapters(channel: FileChannel, fileSize: Long, bookId: String = ""): List<Chapter> {
        return CoverExtractor.extractChapters(channel, fileSize, bookId)
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
