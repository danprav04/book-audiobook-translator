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
    fun onBookReady() {}
    fun onChaptersExtracted(chaptersJson: String) {}
    fun onReaderError(message: String) {}
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
    fun postParagraphs(paragraphsJson: String) {
        listener?.onParagraphsExtracted(-1, paragraphsJson)
    }

    @JavascriptInterface
    fun onPageTurn(cfi: String, fraction: Float, chapterTitle: String) {
        listener?.onPageTurned(cfi, fraction, chapterTitle)
    }

    @JavascriptInterface
    fun onPageTurn(cfi: String) {
        listener?.onPageTurned(cfi, 0f, "")
    }

    @JavascriptInterface
    fun onSelection(cfiRange: String, text: String) {
        listener?.onSelectionChanged(cfiRange, text)
    }

    @JavascriptInterface
    fun postSearchResults(resultsJson: String) {
        listener?.onSearchResults(resultsJson)
    }

    @JavascriptInterface
    fun onBookReady() {
        listener?.onBookReady()
    }

    @JavascriptInterface
    fun onChaptersExtracted(chaptersJson: String) {
        listener?.onChaptersExtracted(chaptersJson)
    }

    @JavascriptInterface
    fun onReaderError(message: String) {
        listener?.onReaderError(message)
    }
}
