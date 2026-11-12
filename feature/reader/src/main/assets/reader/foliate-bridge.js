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
        window.AndroidReaderBridge.onPageTurn(cfi, 0.0, "");
      }
    },
    nextPage: function() {
      console.log("FoliateBridge: nextPage");
    },
    prevPage: function() {
      console.log("FoliateBridge: prevPage");
    },
    highlightCfi: function(cfiRange) {
      console.log("FoliateBridge: highlightCfi", cfiRange);
      // Remove previous highlights
      document.querySelectorAll('.highlight-active').forEach(el => {
        el.classList.remove('highlight-active');
      });
      // In a full foliate-js implementation, cfiRange locates the element.
      // For fallback/mock DOM inspection:
      const el = document.querySelector(`[data-cfi="${cfiRange}"]`) || document.querySelector('p');
      if (el) {
        el.classList.add('highlight-active');
        el.scrollIntoView({ behavior: 'smooth', block: 'center' });
      }
    },
    clearHighlights: function() {
      console.log("FoliateBridge: clearHighlights");
      document.querySelectorAll('.highlight-active').forEach(el => {
        el.classList.remove('highlight-active');
      });
    },
    setTheme: function(themeName, fontSizeSp, fontFamily) {
      console.log("FoliateBridge: setTheme", themeName, fontSizeSp, fontFamily);
      document.body.className = "theme-" + themeName.toLowerCase();
      document.body.style.fontSize = fontSizeSp + "sp";
      if (fontFamily) {
        document.body.style.fontFamily = fontFamily;
      }
    },
    searchInBook: function(query) {
      console.log("FoliateBridge: searchInBook", query);
      const results = [];
      const paragraphs = document.querySelectorAll('p');
      paragraphs.forEach((p, index) => {
        const text = p.textContent || "";
        if (text.toLowerCase().includes(query.toLowerCase())) {
          results.push({
            cfi: p.getAttribute('data-cfi') || `epubcfi(/6/1!/4/2/1:${index})`,
            excerpt: text.substring(0, 120)
          });
        }
      });
      if (window.AndroidReaderBridge) {
        window.AndroidReaderBridge.postSearchResults(JSON.stringify(results));
      }
    },
    setBilingualTranslations: function(translationMap) {
      console.log("FoliateBridge: setBilingualTranslations", translationMap);
      const paragraphs = document.querySelectorAll('p[data-cfi]');
      paragraphs.forEach(p => {
        const cfi = p.getAttribute('data-cfi');
        const translation = translationMap[cfi];
        let transEl = p.nextElementSibling;
        if (translation) {
          if (!transEl || !transEl.classList.contains('bilingual-translation')) {
            transEl = document.createElement('div');
            transEl.className = 'bilingual-translation';
            transEl.style.fontSize = '0.9em';
            transEl.style.color = '#666666';
            transEl.style.marginTop = '4px';
            transEl.style.fontStyle = 'italic';
            p.parentNode.insertBefore(transEl, p.nextSibling);
          }
          transEl.textContent = translation;
        } else if (transEl && transEl.classList.contains('bilingual-translation')) {
          transEl.remove();
        }
      });
    },
    extractParagraphs: function(chapterIndex) {
      console.log("FoliateBridge: extractParagraphs for chapter", chapterIndex);
      var paragraphs = [];
      document.querySelectorAll('p').forEach((p, idx) => {
        paragraphs.push({
          id: p.getAttribute('data-id') || "p_" + idx,
          text: p.textContent || "",
          cfiRange: p.getAttribute('data-cfi') || `epubcfi(/6/1!/4/2/1:${idx})`
        });
      });
      if (window.AndroidReaderBridge) {
        window.AndroidReaderBridge.postParagraphs(chapterIndex, JSON.stringify(paragraphs));
      }
    }
  };
  console.log("FoliateBridge fully initialized.");
})();
