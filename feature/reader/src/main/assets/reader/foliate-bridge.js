/**
 * Foliate-js JavaScript bridge communication layer for Polyglot Books.
 */
(function() {
  window.FoliateBridge = {
    openBook: function(bookUri) {
      console.log("FoliateBridge: openBook", bookUri);
    },
    goToCfi: function(cfi) {
      console.log("FoliateBridge: goToCfi", cfi);
      if (window.AndroidReaderBridge) {
        window.AndroidReaderBridge.onPageTurn(cfi);
      }
    },
    highlightLine: function(cfiRange) {
      console.log("FoliateBridge: highlightLine", cfiRange);
    },
    extractParagraphs: function(chapterIndex) {
      console.log("FoliateBridge: extractParagraphs for chapter", chapterIndex);
      var paragraphs = [];
      if (window.AndroidReaderBridge) {
        window.AndroidReaderBridge.postParagraphs(chapterIndex, JSON.stringify(paragraphs));
      }
    }
  };
  console.log("FoliateBridge initialized.");
})();
