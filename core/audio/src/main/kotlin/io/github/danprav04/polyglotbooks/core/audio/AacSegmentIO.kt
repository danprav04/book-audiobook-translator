package io.github.danprav04.polyglotbooks.core.audio

import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import java.io.File

/**
 * Encodes PCM audio chunks into AAC segments (ADTS or MP4 container) for offline caching.
 */
interface AacSegmentWriter {
    fun open(outputFile: File, sampleRate: Int = 24000, channels: Int = 1, bitrate: Int = 64000)
    fun writeChunk(chunk: PcmChunk)
    fun close()
}

/**
 * Decodes cached AAC audio segments back to raw PCM chunks for playback.
 */
interface AacSegmentReader {
    fun open(inputFile: File)
    fun readNextChunk(): PcmChunk?
    fun close()
}

/**
 * Stub implementation of [AacSegmentWriter].
 */
class StubAacSegmentWriter : AacSegmentWriter {
    private var file: File? = null

    override fun open(outputFile: File, sampleRate: Int, channels: Int, bitrate: Int) {
        file = outputFile
        outputFile.parentFile?.mkdirs()
    }

    override fun writeChunk(chunk: PcmChunk) {
        file?.appendBytes(chunk.data)
    }

    override fun close() {
        file = null
    }
}

/**
 * Stub implementation of [AacSegmentReader].
 */
class StubAacSegmentReader : AacSegmentReader {
    private var file: File? = null

    override fun open(inputFile: File) {
        file = inputFile
    }

    override fun readNextChunk(): PcmChunk? {
        return null
    }

    override fun close() {
        file = null
    }
}
