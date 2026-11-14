package io.github.danprav04.polyglotbooks.core.gemini

import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.roundToLong

data class ModelQuotaProfile(
    val modelPattern: String,
    val rpmLimit: Int,
    val tpmLimit: Int,
    val rpdLimit: Int,
    val recommendedBatchChars: Int,
    val recommendedBatchItems: Int,
    val supportsSystemInstruction: Boolean = true,
    val supportsJsonMimeType: Boolean = true
)

data class ModelQuotaSnapshot(
    val modelId: String,
    val rpmUsedLastMinute: Int,
    val rpmLimit: Int,
    val tpmUsedLastMinute: Int,
    val tpmLimit: Int,
    val rpdUsedToday: Int,
    val rpdLimit: Int,
    val cooldownRemainingMs: Long,
    val permanentlyUnavailable: Boolean,
    val exhaustedForDay: Boolean,
    val quotaNote: String? = null
)

data class BatchBudget(
    val maxChars: Int,
    val maxItems: Int,
    val targetModel: String
)

class QuotaExhaustedException(
    message: String,
    val retryAfterMs: Long? = null,
    val attemptedModels: List<String> = emptyList()
) : IOException(message)

/**
 * Tracks per-model Gemini API free-tier quotas (RPM, TPM, RPD), parses HTTP 429 retry hints,
 * manages cooldowns, and provides priority-ordered multi-model fallback pools.
 */
class GeminiQuotaTracker(
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private data class TokenUsageEntry(
        val timestampMs: Long,
        var tokens: Int
    )

    private class ModelState(val modelId: String) {
        val minuteRequests = mutableListOf<Long>()
        val minuteTokens = mutableListOf<TokenUsageEntry>()
        var dayBucket: Long = 0L
        var rpdUsedToday: Int = 0
        var cooldownUntilMs: Long = 0L
        var permanentlyUnavailable: Boolean = false
        var exhaustedForDay: Boolean = false
        var quotaNote: String? = null
    }

    private val lock = Any()
    private val modelStates = linkedMapOf<String, ModelState>()
    private val discoveredModels = mutableListOf<ModelItem>()
    private val catalogKeysLoaded = mutableSetOf<String>()

    private val _quotaSnapshots = MutableStateFlow<Map<String, ModelQuotaSnapshot>>(emptyMap())
    val quotaSnapshots: StateFlow<Map<String, ModelQuotaSnapshot>> = _quotaSnapshots.asStateFlow()

    fun profileFor(modelId: String): ModelQuotaProfile {
        val id = normalizeId(modelId).lowercase()
        return when {
            id.startsWith("gemini-2.0-flash") -> ModelQuotaProfile(
                modelPattern = "gemini-2.0-flash*",
                rpmLimit = 0,
                tpmLimit = 0,
                rpdLimit = 0,
                recommendedBatchChars = 0,
                recommendedBatchItems = 0
            )
            id.contains("tts") -> ModelQuotaProfile(
                modelPattern = "*-tts*",
                rpmLimit = 3,
                tpmLimit = 10_000,
                rpdLimit = 10,
                recommendedBatchChars = 1_200,
                recommendedBatchItems = 8
            )
            id.startsWith("gemini-2.5-flash-native-audio") -> ModelQuotaProfile(
                modelPattern = "gemini-2.5-flash-native-audio*",
                rpmLimit = Int.MAX_VALUE,
                tpmLimit = 1_000_000,
                rpdLimit = Int.MAX_VALUE,
                recommendedBatchChars = 2_000,
                recommendedBatchItems = 12
            )
            id.startsWith("gemini-3.8-live") || id.startsWith("gemini-3-flash-live") -> ModelQuotaProfile(
                modelPattern = "gemini-3*-live*",
                rpmLimit = Int.MAX_VALUE,
                tpmLimit = 65_000,
                rpdLimit = Int.MAX_VALUE,
                recommendedBatchChars = 1_600,
                recommendedBatchItems = 10
            )
            id.startsWith("gemini-3.5-live-translate") || id.startsWith("gemini-3.5-transcribe-live") -> ModelQuotaProfile(
                modelPattern = "gemini-3.5-live-*",
                rpmLimit = Int.MAX_VALUE,
                tpmLimit = 20_000,
                rpdLimit = Int.MAX_VALUE,
                recommendedBatchChars = 1_000,
                recommendedBatchItems = 6
            )
            id.contains("live") -> ModelQuotaProfile(
                modelPattern = "*live*",
                rpmLimit = Int.MAX_VALUE,
                tpmLimit = 65_000,
                rpdLimit = Int.MAX_VALUE,
                recommendedBatchChars = 1_600,
                recommendedBatchItems = 10
            )
            id.startsWith("gemma-4") || id.startsWith("gemma-3") || id.startsWith("gemma") -> ModelQuotaProfile(
                modelPattern = "gemma-4*",
                rpmLimit = 30,
                tpmLimit = 16_000,
                rpdLimit = 14_400,
                recommendedBatchChars = 3_200,
                recommendedBatchItems = 35,
                supportsSystemInstruction = false,
                supportsJsonMimeType = false
            )
            id.startsWith("gemini-3.5-flash-lite") ||
                id.startsWith("gemini-3.1-flash-lite") ||
                id.startsWith("gemini-2.5-flash-lite") ||
                id.contains("flash-lite") -> ModelQuotaProfile(
                modelPattern = "*-flash-lite*",
                rpmLimit = 15,
                tpmLimit = 250_000,
                rpdLimit = 500,
                recommendedBatchChars = 14_000,
                recommendedBatchItems = 100
            )
            id.startsWith("gemini-2.5-flash") ||
                id.startsWith("gemini-3-flash") ||
                id.startsWith("gemini-3.5-flash") -> ModelQuotaProfile(
                modelPattern = "gemini-2.5-flash",
                rpmLimit = 10,
                tpmLimit = 250_000,
                rpdLimit = 250,
                recommendedBatchChars = 12_000,
                recommendedBatchItems = 80
            )
            else -> ModelQuotaProfile(
                modelPattern = id.ifEmpty { "default" },
                rpmLimit = 10,
                tpmLimit = 128_000,
                rpdLimit = 250,
                recommendedBatchChars = 8_000,
                recommendedBatchItems = 60,
                supportsSystemInstruction = !id.startsWith("gemma"),
                supportsJsonMimeType = !id.startsWith("gemma")
            )
        }
    }

    fun registerCatalogModels(models: List<ModelItem>) {
        synchronized(lock) {
            val now = clockMs()
            discoveredModels.clear()
            discoveredModels.addAll(models)
            for (model in models) {
                val id = normalizeId(model.id)
                if (id.isEmpty()) continue
                val isRelevant = ModelCatalog.matchesRole(model, ModelRole.TEXT_TRANSLATION) ||
                    ModelCatalog.matchesRole(model, ModelRole.TTS) ||
                    ModelCatalog.matchesRole(model, ModelRole.LIVE_NARRATION)
                if (isRelevant) {
                    val state = stateForLocked(id, now)
                    if (id.lowercase().startsWith("gemini-2.0-flash")) {
                        state.permanentlyUnavailable = true
                        state.quotaNote = "0/0 free-tier quota"
                    }
                }
            }
            publishSnapshotsLocked(now)
        }
    }

    fun hasRegisteredCatalogForKey(apiKey: String): Boolean {
        val digest = hashKey(apiKey) ?: return false
        synchronized(lock) {
            return digest in catalogKeysLoaded
        }
    }

    fun markCatalogLoadedForKey(apiKey: String, models: List<ModelItem>) {
        val digest = hashKey(apiKey)
        synchronized(lock) {
            if (digest != null) {
                catalogKeysLoaded.add(digest)
            }
        }
        if (models.isNotEmpty()) {
            registerCatalogModels(models)
        }
    }

    fun textCandidatePool(primaryModel: String): List<String> {
        val primary = normalizeId(primaryModel)
        val catalogText = synchronized(lock) {
            ModelCatalog.filterModelsForRole(discoveredModels, ModelRole.TEXT_TRANSLATION)
                .map { normalizeId(it.id) }
                .filter { it.isNotEmpty() && !isExcludedZeroQuotaModel(it) }
        }

        val catalogFlashLite = catalogText.filter { it.contains("flash-lite", ignoreCase = true) }
        val catalogFlashOrPro = catalogText.filter {
            !it.contains("flash-lite", ignoreCase = true) && !it.startsWith("gemma", ignoreCase = true)
        }
        val catalogGemma = catalogText.filter { it.startsWith("gemma", ignoreCase = true) }

        return buildList {
            if (primary.isNotEmpty() && !isExcludedZeroQuotaModel(primary)) {
                add(primary)
            }
            addAll(catalogFlashLite)
            add("gemini-3.5-flash-lite")
            add("gemini-3.1-flash-lite")
            add("gemini-2.5-flash-lite")
            addAll(catalogFlashOrPro)
            add("gemini-2.5-flash")
            addAll(catalogGemma)
            add("gemma-4-31b-it")
            add("gemma-4-26b-a4b-it")
        }.distinct()
    }

    fun ttsCandidatePool(primaryModel: String): List<String> {
        val primary = normalizeId(primaryModel)
        val catalogTts = synchronized(lock) {
            ModelCatalog.filterModelsForRole(discoveredModels, ModelRole.TTS)
                .map { normalizeId(it.id) }
                .filter { it.isNotEmpty() && !isExcludedZeroQuotaModel(it) }
        }

        return buildList {
            if (primary.isNotEmpty() && !isExcludedZeroQuotaModel(primary)) {
                add(primary)
            }
            addAll(catalogTts)
            add("gemini-3.8-flash-tts")
            add("gemini-2.5-flash-preview-tts")
        }.distinct()
    }

    fun liveAudioCandidatePool(primaryModel: String): List<String> {
        val primary = normalizeId(primaryModel)
        val catalogLive = synchronized(lock) {
            ModelCatalog.filterModelsForRole(discoveredModels, ModelRole.LIVE_NARRATION)
                .map { normalizeId(it.id) }
                .filter {
                    it.isNotEmpty() &&
                        !isExcludedZeroQuotaModel(it) &&
                        !it.contains("transcribe", ignoreCase = true)
                }
        }

        return buildList {
            if (primary.isNotEmpty() && !isExcludedZeroQuotaModel(primary)) {
                add(primary)
            }
            addAll(catalogLive)
            add("gemini-3.8-live")
            add("gemini-2.5-flash-native-audio-dialog")
            add("gemini-3-flash-live")
        }.distinct()
    }

    fun recommendedBatchBudget(candidates: List<String>): BatchBudget {
        val ready = selectNextReadyModel(candidates)
            ?: candidates.firstOrNull { !isExcludedZeroQuotaModel(it) }
            ?: DefaultModels.get(ModelRole.TEXT_TRANSLATION)
        val profile = profileFor(ready)
        return BatchBudget(
            maxChars = profile.recommendedBatchChars.coerceAtLeast(1_000),
            maxItems = profile.recommendedBatchItems.coerceAtLeast(1),
            targetModel = normalizeId(ready)
        )
    }

    fun selectNextReadyModel(candidates: List<String>, estimatedTokens: Int = 500): String? {
        synchronized(lock) {
            val now = clockMs()
            for (candidate in candidates) {
                val id = normalizeId(candidate)
                if (id.isEmpty()) continue
                if (isModelAvailableLocked(id, now, estimatedTokens)) {
                    return id
                }
            }
            return null
        }
    }

    fun shortestCooldownMs(candidates: List<String>): Long? {
        synchronized(lock) {
            val now = clockMs()
            var shortest: Long? = null
            for (candidate in candidates) {
                val id = normalizeId(candidate)
                if (id.isEmpty()) continue
                val profile = profileFor(id)
                if (profile.rpmLimit <= 0) continue

                val state = stateForLocked(id, now)
                pruneWindowsLocked(state, now)
                if (state.permanentlyUnavailable || state.exhaustedForDay) continue
                if (profile.rpdLimit < Int.MAX_VALUE && state.rpdUsedToday >= profile.rpdLimit) continue

                val remaining = computeCooldownRemainingLocked(state, profile, now)
                if (remaining > 0L) {
                    shortest = if (shortest == null) remaining else minOf(shortest, remaining)
                }
            }
            return shortest
        }
    }

    fun recordRequestStarted(modelId: String, estimatedTokens: Int = 500) {
        val id = normalizeId(modelId)
        if (id.isEmpty()) return
        synchronized(lock) {
            val now = clockMs()
            val state = stateForLocked(id, now)
            pruneWindowsLocked(state, now)
            state.minuteRequests.add(now)
            if (estimatedTokens > 0) {
                state.minuteTokens.add(TokenUsageEntry(now, estimatedTokens))
            }
            state.rpdUsedToday += 1
            publishSnapshotsLocked(now)
        }
    }

    fun recordSuccess(modelId: String, actualOrEstimatedTokens: Int = 0) {
        val id = normalizeId(modelId)
        if (id.isEmpty()) return
        synchronized(lock) {
            val now = clockMs()
            val state = stateForLocked(id, now)
            pruneWindowsLocked(state, now)
            state.cooldownUntilMs = 0L
            state.quotaNote = null
            if (actualOrEstimatedTokens > 0) {
                val last = state.minuteTokens.lastOrNull()
                if (last != null) {
                    last.tokens = actualOrEstimatedTokens
                } else {
                    state.minuteTokens.add(TokenUsageEntry(now, actualOrEstimatedTokens))
                }
            }
            publishSnapshotsLocked(now)
        }
    }

    fun recordHttpError(
        modelId: String,
        httpCode: Int,
        responseBody: String,
        retryAfterHeader: String? = null
    ) {
        val id = normalizeId(modelId)
        if (id.isEmpty()) return
        synchronized(lock) {
            val now = clockMs()
            val state = stateForLocked(id, now)
            pruneWindowsLocked(state, now)
            val bodyLower = responseBody.lowercase()

            when {
                httpCode == 404 ||
                    (httpCode == 400 && isUnsupportedModelMessage(bodyLower)) -> {
                    state.permanentlyUnavailable = true
                    state.quotaNote = "HTTP $httpCode: unavailable"
                }
                httpCode == 429 -> {
                    if (isZeroLimitQuota(bodyLower)) {
                        state.permanentlyUnavailable = true
                        state.quotaNote = "Free-tier quota is 0 for $id"
                    } else if (isDailyQuotaExhausted(bodyLower)) {
                        state.exhaustedForDay = true
                        state.quotaNote = "Daily quota (RPD) exhausted"
                    } else {
                        val retryMs = parseRetryDelayMs(responseBody, retryAfterHeader)
                            ?: DEFAULT_429_COOLDOWN_MS
                        state.cooldownUntilMs = maxOf(state.cooldownUntilMs, now + retryMs)
                        val secs = ceil(retryMs / 1000.0).toLong().coerceAtLeast(1L)
                        state.quotaNote = "Rate limited (429), retry in ${secs}s"
                    }
                }
                httpCode == 503 || httpCode == 500 -> {
                    state.cooldownUntilMs = maxOf(state.cooldownUntilMs, now + DEFAULT_503_COOLDOWN_MS)
                    state.quotaNote = "Service busy (HTTP $httpCode)"
                }
                else -> {
                    state.quotaNote = "HTTP $httpCode"
                }
            }

            publishSnapshotsLocked(now)
        }
    }

    fun isModelAvailable(modelId: String): Boolean {
        val id = normalizeId(modelId)
        if (id.isEmpty()) return false
        synchronized(lock) {
            return isModelAvailableLocked(id, clockMs(), estimatedTokens = 0)
        }
    }

    fun isTtsQuotaLikelyExhausted(): Boolean {
        val ttsPool = ttsCandidatePool(DefaultModels.get(ModelRole.TTS))
        return selectNextReadyModel(ttsPool, estimatedTokens = 100) == null
    }

    fun resetCooldowns() {
        synchronized(lock) {
            val now = clockMs()
            for (state in modelStates.values) {
                state.cooldownUntilMs = 0L
                state.exhaustedForDay = false
                if (!state.permanentlyUnavailable) {
                    state.quotaNote = null
                }
                pruneWindowsLocked(state, now)
            }
            publishSnapshotsLocked(now)
        }
    }

    fun formatConciseError(
        failedModels: List<String>,
        shortestRetryMs: Long?,
        lastBody: String = ""
    ): String {
        val uniqueModels = failedModels.map { normalizeId(it) }.filter { it.isNotEmpty() }.distinct()
        val modelLabel = if (uniqueModels.isEmpty()) {
            "selected Gemini models"
        } else {
            uniqueModels.joinToString(", ")
        }

        val bodyLower = lastBody.lowercase()
        return when {
            shortestRetryMs != null && shortestRetryMs > 0L -> {
                val secs = ceil(shortestRetryMs / 1000.0).toLong().coerceAtLeast(1L)
                "Free-tier rate limit reached for $modelLabel. Try again in ~${secs}s or switch models in Settings."
            }
            isZeroLimitQuota(bodyLower) || isDailyQuotaExhausted(bodyLower) -> {
                "Daily or free-tier quota exhausted for $modelLabel. Switch models or check AI Studio quota."
            }
            else -> {
                "Quota or model error for $modelLabel. Check your API key or try another model in Settings."
            }
        }
    }

    private fun isModelAvailableLocked(id: String, now: Long, estimatedTokens: Int): Boolean {
        val profile = profileFor(id)
        if (profile.rpmLimit <= 0) return false
        val state = stateForLocked(id, now)
        pruneWindowsLocked(state, now)

        if (state.permanentlyUnavailable || state.exhaustedForDay) return false
        if (state.cooldownUntilMs > now) return false
        if (state.minuteRequests.size >= profile.rpmLimit) return false
        if (profile.rpdLimit < Int.MAX_VALUE && state.rpdUsedToday >= profile.rpdLimit) return false

        val tpmUsed = state.minuteTokens.sumOf { it.tokens }
        if (profile.tpmLimit < Int.MAX_VALUE && tpmUsed > 0 && tpmUsed + estimatedTokens > profile.tpmLimit) {
            return false
        }
        return true
    }

    private fun computeCooldownRemainingLocked(
        state: ModelState,
        profile: ModelQuotaProfile,
        now: Long
    ): Long {
        var remaining = (state.cooldownUntilMs - now).coerceAtLeast(0L)
        if (state.minuteRequests.size >= profile.rpmLimit && state.minuteRequests.isNotEmpty()) {
            val oldest = state.minuteRequests.first()
            remaining = maxOf(remaining, (oldest + ONE_MINUTE_MS - now).coerceAtLeast(1L))
        }
        val tpmUsed = state.minuteTokens.sumOf { it.tokens }
        if (profile.tpmLimit < Int.MAX_VALUE && tpmUsed >= profile.tpmLimit && state.minuteTokens.isNotEmpty()) {
            val oldestTok = state.minuteTokens.first().timestampMs
            remaining = maxOf(remaining, (oldestTok + ONE_MINUTE_MS - now).coerceAtLeast(1L))
        }
        return remaining
    }

    private fun stateForLocked(id: String, now: Long): ModelState {
        return modelStates.getOrPut(id) {
            ModelState(id).apply {
                dayBucket = now / ONE_DAY_MS
            }
        }
    }

    private fun pruneWindowsLocked(state: ModelState, now: Long) {
        val cutoff = now - ONE_MINUTE_MS
        state.minuteRequests.removeAll { it <= cutoff }
        state.minuteTokens.removeAll { it.timestampMs <= cutoff }
        val currentBucket = now / ONE_DAY_MS
        if (state.dayBucket != currentBucket) {
            state.dayBucket = currentBucket
            state.rpdUsedToday = 0
            state.exhaustedForDay = false
        }
        if (state.cooldownUntilMs in 1..now) {
            state.cooldownUntilMs = 0L
            if (!state.permanentlyUnavailable && !state.exhaustedForDay &&
                state.quotaNote?.startsWith("Rate limited") == true
            ) {
                state.quotaNote = null
            }
        }
    }

    private fun publishSnapshotsLocked(now: Long) {
        val map = LinkedHashMap<String, ModelQuotaSnapshot>(modelStates.size)
        for ((id, state) in modelStates) {
            pruneWindowsLocked(state, now)
            val profile = profileFor(id)
            val cooldown = computeCooldownRemainingLocked(state, profile, now)
            map[id] = ModelQuotaSnapshot(
                modelId = id,
                rpmUsedLastMinute = state.minuteRequests.size,
                rpmLimit = profile.rpmLimit,
                tpmUsedLastMinute = state.minuteTokens.sumOf { it.tokens },
                tpmLimit = profile.tpmLimit,
                rpdUsedToday = state.rpdUsedToday,
                rpdLimit = profile.rpdLimit,
                cooldownRemainingMs = cooldown,
                permanentlyUnavailable = state.permanentlyUnavailable,
                exhaustedForDay = state.exhaustedForDay,
                quotaNote = state.quotaNote
            )
        }
        _quotaSnapshots.value = map
    }

    private fun normalizeId(modelId: String): String =
        modelId.trim().removePrefix("models/")

    private fun isExcludedZeroQuotaModel(modelId: String): Boolean =
        normalizeId(modelId).lowercase().startsWith("gemini-2.0-flash")

    private fun isUnsupportedModelMessage(bodyLower: String): Boolean =
        bodyLower.contains("not found") ||
            bodyLower.contains("not supported") ||
            bodyLower.contains("unsupported") ||
            bodyLower.contains("is not enabled") ||
            bodyLower.contains("does not support")

    private fun isZeroLimitQuota(bodyLower: String): Boolean =
        ZERO_LIMIT_REGEX.containsMatchIn(bodyLower)

    private fun isDailyQuotaExhausted(bodyLower: String): Boolean =
        DAILY_QUOTA_REGEX.containsMatchIn(bodyLower)

    private fun parseRetryDelayMs(responseBody: String, retryAfterHeader: String?): Long? {
        retryAfterHeader?.trim()?.toDoubleOrNull()?.let { secs ->
            if (secs > 0.0) return (secs * 1000.0).roundToLong()
        }
        PLEASE_RETRY_REGEX.find(responseBody)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { secs ->
            if (secs > 0.0) return (secs * 1000.0).roundToLong()
        }
        JSON_RETRY_DELAY_REGEX.find(responseBody)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { secs ->
            if (secs > 0.0) return (secs * 1000.0).roundToLong()
        }
        return null
    }

    private fun hashKey(apiKey: String): String? {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(trimmed.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ONE_MINUTE_MS = 60_000L
        const val ONE_DAY_MS = 86_400_000L
        const val DEFAULT_429_COOLDOWN_MS = 15_000L
        const val DEFAULT_503_COOLDOWN_MS = 10_000L

        val ZERO_LIMIT_REGEX = Regex("""(?i)\blimit\s*[:=]\s*0\b|"limit"\s*:\s*0\b""")
        val DAILY_QUOTA_REGEX = Regex("""(?i)per_day|perday|requestsperday|\brpd\b|generaterequestsperday""")
        val PLEASE_RETRY_REGEX = Regex("""(?i)please\s+retry\s+in\s+(\d+(?:\.\d+)?)\s*s""")
        val JSON_RETRY_DELAY_REGEX = Regex("""(?i)"retryDelay"\s*:\s*"(\d+(?:\.\d+)?)s"""")
    }
}
