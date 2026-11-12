package io.github.danprav04.polyglotbooks.feature.audiobook

import androidx.lifecycle.ViewModel
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudiobookPlayerUiState(
    val bookTitle: String = "Polyglot Audiobook",
    val author: String = "Author",
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 3600000L,
    val bufferAheadMs: Long = 15000L,
    val playbackSpeed: Float = 1.0f,
    val selectedLanguage: LanguageCode = LanguageCode.HEBREW,
    val isDuckingEnabled: Boolean = false,
    val duckLevel: Float = 0.15f,
    val sourceSubtitle: String = "Source subtitle transcript...",
    val translatedSubtitle: String = "Translated subtitle transcript..."
)

class AudiobookPlayerViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(AudiobookPlayerUiState())
    val uiState: StateFlow<AudiobookPlayerUiState> = _uiState.asStateFlow()

    fun togglePlayPause() {
        _uiState.value = _uiState.value.copy(isPlaying = !_uiState.value.isPlaying)
    }

    fun seekBy(deltaMs: Long) {
        val newPos = (_uiState.value.currentPositionMs + deltaMs).coerceIn(0L, _uiState.value.durationMs)
        _uiState.value = _uiState.value.copy(currentPositionMs = newPos)
    }

    fun seekTo(positionMs: Long) {
        val newPos = positionMs.coerceIn(0L, _uiState.value.durationMs)
        _uiState.value = _uiState.value.copy(currentPositionMs = newPos)
    }

    fun setLanguage(language: LanguageCode) {
        _uiState.value = _uiState.value.copy(selectedLanguage = language)
    }

    fun toggleDucking() {
        _uiState.value = _uiState.value.copy(isDuckingEnabled = !_uiState.value.isDuckingEnabled)
    }

    fun setPlaybackSpeed(speed: Float) {
        _uiState.value = _uiState.value.copy(playbackSpeed = speed)
    }
}
