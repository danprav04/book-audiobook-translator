package io.github.danprav04.polyglotbooks.feature.reader

import android.webkit.JavascriptInterface

/**
 * Interface contract between Foliate-js running inside a sandboxed WebView and Kotlin.
 */
interface ReaderBridgeListener {
    fun onParagraphsExtracted(chapterIndex: Int, paragraphsJson: String)
    fun onPageTurned(cfi: String, fraction: Float, chapterTitle: String)
    fun onSelectionChanged(cfiRange: String, selectedText: String)
    fun onSearchResults(resultsJson: String)
}

/**
 * JavaScriptInterface exposed to WebView foliate host (`AndroidReaderBridge`).
 */
class ReaderBridge(
    private val listener: ReaderBridgeListener? = null
) {
    @JavascriptInterface
    fun postParagraphs(chapterIndex: Int, paragraphsJson: String) {
        listener?.onParagraphsExtracted(chapterIndex, paragraphsJson)
    }

    @JavascriptInterface
    fun onPageTurn(cfi: String, fraction: Float, chapterTitle: String) {
        listener?.onPageTurned(cfi, fraction, chapterTitle)
    }

    @JavascriptInterface
    fun onSelection(cfiRange: String, text: String) {
        listener?.onSelectionChanged(cfiRange, text)
    }

    @JavascriptInterface
    fun postSearchResults(resultsJson: String) {
        listener?.onSearchResults(resultsJson)
    }
}
