package io.github.danprav04.polyglotbooks.core.gemini

import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.ModelRole

/**
 * Client for structured text analysis and text translation using Gemini models.
 */
interface TextModelClient {
    suspend fun generateContent(
        apiKey: String,
        prompt: String,
        systemInstruction: String? = null,
        responseJsonSchema: String? = null,
        model: String = DefaultModels.get(ModelRole.TEXT_ANALYSIS)
    ): String
}

/**
 * Stub implementation of [TextModelClient].
 */
class StubTextModelClient : TextModelClient {
    override suspend fun generateContent(
        apiKey: String,
        prompt: String,
        systemInstruction: String?,
        responseJsonSchema: String?,
        model: String
    ): String {
        return """{"result": "stub_response"}"""
    }
}
