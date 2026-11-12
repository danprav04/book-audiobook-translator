package io.github.danprav04.polyglotbooks.feature.reader

import android.webkit.JavascriptInterface

/**
 * Interface contract between Foliate-js running inside a sandboxed WebView and Kotlin.
 */
interface ReaderBridgeListener {
    fun onParagraphsExtracted(chapterIndex: Int, paragraphsJson: String)
    fun onPageTurned(cfi: String)
    fun onSelectionChanged(cfiRange: String, selectedText: String)
}

/**
 * JavaScriptInterface exposed to WebView foliate host.
 */
class ReaderBridge(
    private val listener: ReaderBridgeListener? = null
) {
    @JavascriptInterface
    fun postParagraphs(chapterIndex: Int, paragraphsJson: String) {
        listener?.onParagraphsExtracted(chapterIndex, paragraphsJson)
    }

    @JavascriptInterface
    fun onPageTurn(cfi: String) {
        listener?.onPageTurned(cfi)
    }

    @JavascriptInterface
    fun onSelection(cfiRange: String, text: String) {
        listener?.onSelectionChanged(cfiRange, text)
    }
}
