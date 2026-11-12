package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.model.NarrationLine
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * NarrationCoordinator manages pre-fetching/synthesizing N lines ahead,
 * caching synthesized PCM segments, and emitting active line cfi ranges for synchronized highlighting.
 */
class NarrationCoordinator(
    private val engine: NarrationEngine,
    private val prefetchAheadCount: Int = 3
) {
    private val pcmCache = mutableMapOf<String, Flow<PcmChunk>>()

    fun prepareLines(lines: List<NarrationLine>, voiceMap: Map<String, String>) {
        for (line in lines.take(prefetchAheadCount)) {
            val voiceId = voiceMap[line.speakerId] ?: "Charon"
            val request = SynthesisRequest(
                text = "Line ${line.id}",
                voiceId = voiceId,
                style = line.style
            )
            pcmCache[line.id] = engine.synthesize(request)
        }
    }

    fun getCachedPcm(lineId: String): Flow<PcmChunk>? = pcmCache[lineId]

    fun getActiveCfiRange(line: NarrationLine): String {
        return line.cfi ?: "epubcfi(/6/4!/4/2/1:${line.startOffset})"
    }
}
