package io.github.danprav04.polyglotbooks.feature.translate

/**
 * Interface and stub for mapping source audiobook playback timestamp to translated audio timeline.
 */
interface TimelineMapper {
    fun sourceToTranslated(sourceMs: Long): Long
    fun translatedToSource(translatedMs: Long): Long
    fun recordAnchor(sourceMs: Long, translatedMs: Long)
}

/**
 * Default linear / piece-wise timeline mapper implementation.
 */
class DefaultTimelineMapper : TimelineMapper {
    private val anchors = mutableListOf<Pair<Long, Long>>()

    override fun sourceToTranslated(sourceMs: Long): Long {
        if (anchors.isEmpty()) return sourceMs
        // Find nearest anchor
        val anchor = anchors.minByOrNull { kotlin.math.abs(it.first - sourceMs) } ?: return sourceMs
        val delta = sourceMs - anchor.first
        return (anchor.second + delta).coerceAtLeast(0L)
    }

    override fun translatedToSource(translatedMs: Long): Long {
        if (anchors.isEmpty()) return translatedMs
        val anchor = anchors.minByOrNull { kotlin.math.abs(it.second - translatedMs) } ?: return translatedMs
        val delta = translatedMs - anchor.second
        return (anchor.first + delta).coerceAtLeast(0L)
    }

    override fun recordAnchor(sourceMs: Long, translatedMs: Long) {
        anchors.add(sourceMs to translatedMs)
    }
}
