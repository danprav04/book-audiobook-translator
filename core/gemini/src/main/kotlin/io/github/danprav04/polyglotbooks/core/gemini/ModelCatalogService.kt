package io.github.danprav04.polyglotbooks.core.gemini

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Service to interact with the Gemini Model Catalog REST API.
 */
interface ModelCatalogService {
    /**
     * Lists available models from Google AI Studio.
     * Calls `GET https://generativelanguage.googleapis.com/v1beta/models?key={apiKey}`.
     * Throws an exception or returns parsed [ModelListResponse].
     */
    suspend fun listModels(apiKey: String): ModelListResponse
}

/**
 * Concrete implementation of [ModelCatalogService] using OkHttp.
 */
class OkHttpModelCatalogService(
    private val okHttpClient: OkHttpClient = OkHttpClient()
) : ModelCatalogService {

    override suspend fun listModels(apiKey: String): ModelListResponse = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        require(trimmedKey.isNotEmpty()) { "API key must not be blank" }

        val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$trimmedKey"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                throw IOException("Gemini API error (HTTP ${response.code}): $errorBody")
            }
            val bodyString = response.body?.string()
                ?: throw IOException("Empty response body from models.list")
            ModelCatalog.parseModelsJson(bodyString)
        }
    }
}
