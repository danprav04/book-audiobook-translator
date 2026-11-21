package io.github.danprav04.polyglotbooks.feature.reader

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Pure-Kotlin disk cache for chapter paragraph translations and synthesized Gemini audio segments.
 * Helps preserve free-tier Gemini API quota across chapter revisits, re-toggles, and re-listens.
 */
class TranslationDiskCache(
    private val cacheRootDir: File,
    private val maxAudioCacheBytes: Long = DEFAULT_MAX_AUDIO_BYTES,
    private val maxTranslationFiles: Int = DEFAULT_MAX_TRANSLATION_FILES
) {
    private val translationsDir = File(cacheRootDir, "translations")
    private val audioDir = File(cacheRootDir, "audio")
    private val contextDir = File(cacheRootDir, "narration_context")
    private val lock = Any()

    data class BookNarrationContext(
        val storySummary: String = "",
        val chapterSummaries: Map<Int, String> = emptyMap(),
        val characterPersonalities: Map<String, String> = emptyMap(),
        val contextVersion: Int = CURRENT_NARRATION_CONTEXT_VERSION
    )

    data class CachedAudioSegment(
        val sampleRate: Int,
        val channels: Int,
        val pcmBytes: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CachedAudioSegment) return false
            return sampleRate == other.sampleRate &&
                channels == other.channels &&
                pcmBytes.contentEquals(other.pcmBytes)
        }

        override fun hashCode(): Int {
            var result = sampleRate
            result = 31 * result + channels
            result = 31 * result + pcmBytes.contentHashCode()
            return result
        }
    }

    fun loadTranslations(
        bookId: String,
        chapterIndex: Int,
        targetLang: String,
        currentParagraphsByCfi: Map<String, String>
    ): Map<String, String> {
        if (bookId.isBlank() || currentParagraphsByCfi.isEmpty()) return emptyMap()
        synchronized(lock) {
            val file = translationFile(bookId, chapterIndex, targetLang)
            if (!file.exists() || !file.isFile) return emptyMap()
            val stored = readTranslationEntries(file)
            if (stored.isEmpty()) return emptyMap()
            val result = LinkedHashMap<String, String>()
            for ((cfi, sourceText) in currentParagraphsByCfi) {
                val entry = stored[cfi] ?: continue
                if (entry.first == sha256(sourceText) && entry.second.isNotBlank()) {
                    result[cfi] = entry.second
                }
            }
            if (result.isNotEmpty()) {
                file.setLastModified(System.currentTimeMillis())
            }
            return result
        }
    }

    fun saveTranslations(
        bookId: String,
        chapterIndex: Int,
        targetLang: String,
        sourceParagraphsByCfi: Map<String, String>,
        translatedByCfi: Map<String, String>
    ) {
        if (bookId.isBlank() || translatedByCfi.isEmpty()) return
        synchronized(lock) {
            if (!translationsDir.exists()) translationsDir.mkdirs()
            val file = translationFile(bookId, chapterIndex, targetLang)
            val merged = if (file.exists()) readTranslationEntries(file).toMutableMap() else LinkedHashMap()
            for ((cfi, translated) in translatedByCfi) {
                val source = sourceParagraphsByCfi[cfi] ?: continue
                if (translated.isNotBlank()) {
                    merged[cfi] = sha256(source) to translated
                }
            }
            if (merged.isEmpty()) return
            writeTranslationEntries(file, merged)
            pruneTranslationFilesLocked()
        }
    }

    fun loadNarrationContext(bookId: String): BookNarrationContext {
        if (bookId.isBlank()) return BookNarrationContext()
        synchronized(lock) {
            val file = narrationContextFile(bookId)
            if (!file.exists() || !file.isFile) return BookNarrationContext()
            return readNarrationContext(file)
        }
    }

    fun saveNarrationContext(bookId: String, context: BookNarrationContext) {
        if (bookId.isBlank()) return
        synchronized(lock) {
            if (!contextDir.exists()) contextDir.mkdirs()
            val file = narrationContextFile(bookId)
            writeNarrationContext(file, context)
        }
    }

    fun hasAudioSegment(
        engineTag: String,
        voiceId: String,
        style: String?,
        text: String
    ): Boolean {
        if (text.isBlank()) return false
        synchronized(lock) {
            val key = audioCacheKey(engineTag, voiceId, style, text)
            val file = File(audioDir, "$key.pcm")
            return file.exists() && file.isFile && file.length() > 8L
        }
    }

    fun loadAudioSegment(
        engineTag: String,
        voiceId: String,
        style: String?,
        text: String
    ): CachedAudioSegment? {
        if (text.isBlank()) return null
        synchronized(lock) {
            val key = audioCacheKey(engineTag, voiceId, style, text)
            val file = File(audioDir, "$key.pcm")
            if (!file.exists() || !file.isFile || file.length() <= 8L) return null
            return try {
                DataInputStream(FileInputStream(file)).use { dis ->
                    val sampleRate = dis.readInt()
                    val channels = dis.readInt()
                    val pcmBytes = dis.readBytes()
                    if (sampleRate <= 0 || channels <= 0 || pcmBytes.isEmpty()) {
                        null
                    } else {
                        file.setLastModified(System.currentTimeMillis())
                        CachedAudioSegment(sampleRate = sampleRate, channels = channels, pcmBytes = pcmBytes)
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    fun saveAudioSegment(
        engineTag: String,
        voiceId: String,
        style: String?,
        text: String,
        sampleRate: Int,
        channels: Int,
        pcmBytes: ByteArray,
        maxBytes: Long = maxAudioCacheBytes
    ) {
        if (text.isBlank() || pcmBytes.isEmpty() || sampleRate <= 0 || channels <= 0) return
        val effectiveCap = maxBytes.coerceAtMost(DEFAULT_MAX_AUDIO_BYTES).coerceAtLeast(pcmBytes.size.toLong() + 16L)
        synchronized(lock) {
            if (!audioDir.exists()) audioDir.mkdirs()
            val key = audioCacheKey(engineTag, voiceId, style, text)
            val file = File(audioDir, "$key.pcm")
            val tmpFile = File(audioDir, "$key.pcm.tmp")
            try {
                DataOutputStream(FileOutputStream(tmpFile)).use { dos ->
                    dos.writeInt(sampleRate)
                    dos.writeInt(channels)
                    dos.write(pcmBytes)
                }
                if (file.exists()) file.delete()
                if (!tmpFile.renameTo(file)) {
                    tmpFile.copyTo(file, overwrite = true)
                    tmpFile.delete()
                }
                file.setLastModified(System.currentTimeMillis())
                enforceAudioLruCapLocked(effectiveCap)
            } catch (_: Exception) {
                tmpFile.delete()
            }
        }
    }

    private fun translationFile(bookId: String, chapterIndex: Int, targetLang: String): File {
        val safeBook = sanitizeFilename(bookId)
        val safeLang = sanitizeFilename(targetLang.lowercase())
        return File(translationsDir, "${safeBook}_${chapterIndex}_${safeLang}.json")
    }

    private fun narrationContextFile(bookId: String): File {
        return File(contextDir, "${sanitizeFilename(bookId)}_context.json")
    }

    private fun audioCacheKey(engineTag: String, voiceId: String, style: String?, text: String): String {
        return sha256("$engineTag|$voiceId|${style.orEmpty()}|$text")
    }

    private fun pruneTranslationFilesLocked() {
        val files = translationsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        if (files.size <= maxTranslationFiles) return
        val sorted = files.sortedBy { it.lastModified() }
        val toDelete = files.size - maxTranslationFiles
        for (i in 0 until toDelete) {
            sorted[i].delete()
        }
    }

    private fun enforceAudioLruCapLocked(maxBytes: Long) {
        val files = audioDir.listFiles { f -> f.isFile && f.name.endsWith(".pcm") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        val sorted = files.sortedBy { it.lastModified() }
        for (file in sorted) {
            if (total <= maxBytes) break
            val len = file.length()
            if (file.delete()) {
                total -= len
            }
        }
    }

    private fun readTranslationEntries(file: File): Map<String, Pair<String, String>> {
        return try {
            val content = file.readText(Charsets.UTF_8)
            val parsed = CacheJsonCodec.parseObject(content) ?: return emptyMap()
            val out = LinkedHashMap<String, Pair<String, String>>(parsed.size)
            for ((cfi, value) in parsed) {
                val entry = value as? Map<*, *> ?: continue
                val hash = entry["hash"] as? String ?: continue
                val text = entry["text"] as? String ?: continue
                out[cfi] = hash to text
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun writeTranslationEntries(file: File, entries: Map<String, Pair<String, String>>) {
        val sb = StringBuilder()
        sb.append('{')
        var first = true
        for ((cfi, pair) in entries) {
            if (!first) sb.append(',')
            first = false
            sb.append(CacheJsonCodec.quote(cfi))
            sb.append(':')
            sb.append("{\"hash\":")
            sb.append(CacheJsonCodec.quote(pair.first))
            sb.append(",\"text\":")
            sb.append(CacheJsonCodec.quote(pair.second))
            sb.append('}')
        }
        sb.append('}')
        writeAtomicText(file, sb.toString())
    }

    private fun readNarrationContext(file: File): BookNarrationContext {
        return try {
            val parsed = CacheJsonCodec.parseObject(file.readText(Charsets.UTF_8))
                ?: return BookNarrationContext()
            val version = (parsed["version"] as? String)?.toIntOrNull() ?: 1
            val storySummary = (parsed["storySummary"] as? String).orEmpty()
            val chaptersRaw = parsed["chapterSummaries"] as? Map<*, *>
            val chapterSummaries = LinkedHashMap<Int, String>()
            chaptersRaw?.forEach { (k, v) ->
                val idx = (k as? String)?.toIntOrNull()
                val text = v as? String
                if (idx != null && !text.isNullOrBlank()) {
                    chapterSummaries[idx] = text
                }
            }
            val personalitiesRaw = parsed["characterPersonalities"] as? Map<*, *>
            val characterPersonalities = LinkedHashMap<String, String>()
            personalitiesRaw?.forEach { (k, v) ->
                val cid = (k as? String)?.trim()
                val desc = (v as? String)?.trim()
                if (!cid.isNullOrEmpty() && !desc.isNullOrEmpty()) {
                    characterPersonalities[cid] = desc
                }
            }
            BookNarrationContext(
                storySummary = storySummary,
                chapterSummaries = chapterSummaries,
                characterPersonalities = characterPersonalities,
                contextVersion = version
            )
        } catch (_: Exception) {
            BookNarrationContext()
        }
    }

    private fun writeNarrationContext(file: File, context: BookNarrationContext) {
        val sb = StringBuilder()
        sb.append("{\"version\":")
        sb.append(CacheJsonCodec.quote(context.contextVersion.toString()))
        sb.append(",\"storySummary\":")
        sb.append(CacheJsonCodec.quote(context.storySummary))
        sb.append(",\"chapterSummaries\":{")
        var firstChap = true
        for ((idx, summary) in context.chapterSummaries.toSortedMap()) {
            if (summary.isBlank()) continue
            if (!firstChap) sb.append(',')
            firstChap = false
            sb.append(CacheJsonCodec.quote(idx.toString()))
            sb.append(':')
            sb.append(CacheJsonCodec.quote(summary))
        }
        sb.append("},\"characterPersonalities\":{")
        var firstChar = true
        for ((id, personality) in context.characterPersonalities) {
            if (id.isBlank() || personality.isBlank()) continue
            if (!firstChar) sb.append(',')
            firstChar = false
            sb.append(CacheJsonCodec.quote(id))
            sb.append(':')
            sb.append(CacheJsonCodec.quote(personality))
        }
        sb.append("}}")
        writeAtomicText(file, sb.toString())
    }

    private fun writeAtomicText(file: File, content: String) {
        val tmpFile = File(file.parentFile, "${file.name}.tmp")
        tmpFile.writeText(content, Charsets.UTF_8)
        if (file.exists()) file.delete()
        if (!tmpFile.renameTo(file)) {
            tmpFile.copyTo(file, overwrite = true)
            tmpFile.delete()
        }
    }

    private fun sanitizeFilename(raw: String): String {
        return raw.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "default" }
    }

    companion object {
        const val DEFAULT_MAX_AUDIO_BYTES = 200L * 1024L * 1024L
        const val DEFAULT_MAX_TRANSLATION_FILES = 128
        const val CURRENT_NARRATION_CONTEXT_VERSION = 2

        fun sha256(text: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                sb.append(HEX_CHARS[v ushr 4])
                sb.append(HEX_CHARS[v and 0x0F])
            }
            return sb.toString()
        }

        private val HEX_CHARS = "0123456789abcdef".toCharArray()
    }
}

private object CacheJsonCodec {
    fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append(String.format("\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun parseObject(input: String): Map<String, Any?>? {
        val cursor = Cursor(input)
        cursor.skipWhitespace()
        if (!cursor.hasNext() || cursor.peek() != '{') return null
        return cursor.readObject()
    }

    private class Cursor(private val s: String) {
        private var pos = 0

        fun hasNext(): Boolean = pos < s.length
        fun peek(): Char = s[pos]

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun readObject(): Map<String, Any?> {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (hasNext() && s[pos] == '}') {
                pos++
                return map
            }
            while (hasNext()) {
                skipWhitespace()
                if (s[pos] == '}') {
                    pos++
                    break
                }
                val key = readString()
                skipWhitespace()
                if (!hasNext() || s[pos] != ':') throw IllegalArgumentException("Expected ':'")
                pos++
                skipWhitespace()
                val value = when {
                    !hasNext() -> null
                    s[pos] == '{' -> readObject()
                    s[pos] == '"' -> readString()
                    else -> throw IllegalArgumentException("Unexpected token at $pos")
                }
                map[key] = value
                skipWhitespace()
                if (hasNext() && s[pos] == ',') {
                    pos++
                } else if (hasNext() && s[pos] == '}') {
                    pos++
                    break
                } else {
                    throw IllegalArgumentException("Expected ',' or '}'")
                }
            }
            return map
        }

        private fun readString(): String {
            if (!hasNext() || s[pos] != '"') throw IllegalArgumentException("Expected '\"'")
            pos++
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) break
                        when (val esc = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 <= s.length) {
                                    sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                    pos += 4
                                }
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }
    }
}
