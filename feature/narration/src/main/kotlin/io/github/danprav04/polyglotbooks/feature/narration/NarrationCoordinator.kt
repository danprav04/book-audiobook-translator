package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.data.db.CharacterEntity
import io.github.danprav04.polyglotbooks.core.data.db.NarrationLineEntity
import io.github.danprav04.polyglotbooks.core.model.NarrationLine
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.flow.Flow

/**
 * NarrationCoordinator manages pre-fetching/synthesizing N lines ahead,
 * caching synthesized PCM segments, and emitting active line cfi ranges for synchronized highlighting.
 */
class NarrationCoordinator(
    private val engine: NarrationEngine,
    private val prefetchAheadCount: Int = 3
) {
    private val pcmCache = mutableMapOf<String, Flow<PcmChunk>>()

    fun prepareLines(
        lines: List<NarrationLine>,
        voiceMap: Map<String, String>,
        lineTexts: Map<String, String> = emptyMap()
    ) {
        for (line in lines.take(prefetchAheadCount)) {
            val voiceId = voiceMap[line.speakerId] ?: "Charon"
            val request = SynthesisRequest(
                text = lineTexts[line.id] ?: "Line ${line.id}",
                voiceId = voiceId,
                style = line.style
            )
            pcmCache[line.id] = engine.synthesize(request)
        }
    }

    @JvmName("prepareLineEntities")
    fun prepareLines(
        lines: List<NarrationLineEntity>,
        characters: List<CharacterEntity>,
        lineTexts: Map<String, String> = emptyMap()
    ) {
        val voiceMap = characters.associate { character ->
            character.id to (character.assignedVoiceId ?: "Charon")
        }
        prepareLines(
            lines = lines.map { it.toDomain() },
            voiceMap = voiceMap,
            lineTexts = lineTexts
        )
    }

    fun getCachedPcm(lineId: String): Flow<PcmChunk>? = pcmCache[lineId]

    fun getActiveCfiRange(line: NarrationLine): String {
        return line.cfi ?: "epubcfi(/6/4!/4/2/1:${line.startOffset})"
    }
}
