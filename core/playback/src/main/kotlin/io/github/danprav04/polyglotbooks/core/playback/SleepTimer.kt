package io.github.danprav04.polyglotbooks.core.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages sleep timer countdown for audiobook playback.
 */
class SleepTimer(private val externalScope: CoroutineScope, private val onTimerFinished: () -> Unit) {

    private val _remainingTimeMs = MutableStateFlow(0L)
    val remainingTimeMs: StateFlow<Long> = _remainingTimeMs.asStateFlow()

    private val _isTimerRunning = MutableStateFlow(false)
    val isTimerRunning: StateFlow<Boolean> = _isTimerRunning.asStateFlow()

    private var timerJob: Job? = null

    fun start(durationMs: Long) {
        cancel()
        if (durationMs <= 0L) return

        _remainingTimeMs.value = durationMs
        _isTimerRunning.value = true

        timerJob = externalScope.launch {
            val interval = 1000L
            while (isActive && _remainingTimeMs.value > 0L) {
                delay(interval)
                val next = (_remainingTimeMs.value - interval).coerceAtLeast(0L)
                _remainingTimeMs.value = next
                if (next == 0L) {
                    _isTimerRunning.value = false
                    onTimerFinished()
                    break
                }
            }
        }
    }

    fun extend(additionalMs: Long) {
        if (_isTimerRunning.value) {
            _remainingTimeMs.value += additionalMs
        } else {
            start(additionalMs)
        }
    }

    fun cancel() {
        timerJob?.cancel()
        timerJob = null
        _isTimerRunning.value = false
        _remainingTimeMs.value = 0L
    }
}
