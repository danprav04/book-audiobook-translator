/**
 * Foliate-js JavaScript bridge communication layer for Polyglot Books.
 */
(function() {
  let currentBookUri = "";
  let currentCfi = "";
  let currentFraction = 0.0;
  let isBookReady = false;
  let hasDirtyPosition = false;
  let suppressScrollUntil = 0;
  let scrollReportTimer = null;

  function findElementByCfi(cfi) {
    if (!cfi) return null;
    const elements = document.querySelectorAll('[data-cfi]');
    for (let i = 0; i < elements.length; i++) {
      if (elements[i].getAttribute('data-cfi') === cfi) {
        return elements[i];
      }
    }
    return null;
  }

  function estimateFractionForElement(el) {
    if (!el) return currentFraction;
    const paragraphs = Array.from(document.querySelectorAll('p[data-cfi]'));
    const idx = paragraphs.indexOf(el);
    if (idx >= 0 && paragraphs.length > 0) {
      return paragraphs.length === 1 ? 0.0 : idx / (paragraphs.length - 1);
    }
    return currentFraction;
  }

  function notifyPageTurn(cfi, fraction, chapterTitle) {
    if (!cfi && !(fraction > 0)) return;
    if (window.AndroidReaderBridge && typeof window.AndroidReaderBridge.onPageTurn === 'function') {
      window.AndroidReaderBridge.onPageTurn(cfi, fraction, chapterTitle || "");
    }
  }

  function attachScrollTracking() {
    const container = document.getElementById('reader-container');
    if (!container || container.__polyglotScrollBound) return;
    container.__polyglotScrollBound = true;
    container.addEventListener('scroll', function() {
      if (!isBookReady || Date.now() < suppressScrollUntil) return;
      const containerRect = container.getBoundingClientRect();
      const paragraphs = Array.from(container.querySelectorAll('p[data-cfi]'));
      if (paragraphs.length === 0) return;

      let topVisible = null;
      for (let i = 0; i < paragraphs.length; i++) {
        const rect = paragraphs[i].getBoundingClientRect();
        if (rect.bottom > containerRect.top + 4) {
          topVisible = paragraphs[i];
          break;
        }
      }
      if (!topVisible) {
        topVisible = paragraphs[paragraphs.length - 1];
      }
      const cfi = topVisible.getAttribute('data-cfi') || "";
      if (!cfi) return;
      currentCfi = cfi;
      currentFraction = estimateFractionForElement(topVisible);
      hasDirtyPosition = true;

      if (scrollReportTimer) clearTimeout(scrollReportTimer);
      scrollReportTimer = setTimeout(function() {
        if (hasDirtyPosition) {
          hasDirtyPosition = false;
          notifyPageTurn(currentCfi, currentFraction, "");
        }
      }, 300);
    }, { passive: true });
  }

  window.FoliateBridge = {
    openBook: function(bookUri, initialCfi) {
      console.log("FoliateBridge: openBook", bookUri, initialCfi);
      currentBookUri = bookUri || "";
      isBookReady = true;
      hasDirtyPosition = false;
      attachScrollTracking();
      if (initialCfi && String(initialCfi).trim().length > 0) {
        this.goToCfi(initialCfi, false);
      }
      if (window.AndroidReaderBridge && typeof window.AndroidReaderBridge.onBookReady === 'function') {
        window.AndroidReaderBridge.onBookReady();
      }
    },
    goToCfi: function(cfi, notifyAndroid) {
      console.log("FoliateBridge: goToCfi", cfi, notifyAndroid);
      if (!cfi) return;
      currentCfi = cfi;
      const el = findElementByCfi(cfi);
      if (el) {
        suppressScrollUntil = Date.now() + 250;
        el.scrollIntoView({ behavior: notifyAndroid === false ? 'auto' : 'smooth', block: 'center' });
      }
      if (notifyAndroid === false) {
        hasDirtyPosition = false;
        return;
      }
      const fraction = el ? estimateFractionForElement(el) : currentFraction;
      currentFraction = fraction;
      hasDirtyPosition = false;
      notifyPageTurn(cfi, fraction, "");
    },
    flushPosition: function() {
      if (scrollReportTimer) {
        clearTimeout(scrollReportTimer);
        scrollReportTimer = null;
      }
      if (hasDirtyPosition && currentCfi) {
        hasDirtyPosition = false;
        notifyPageTurn(currentCfi, currentFraction, "");
      }
    },
    nextPage: function() {
      console.log("FoliateBridge: nextPage");
      const paragraphs = Array.from(document.querySelectorAll('p[data-cfi]'));
      if (paragraphs.length === 0) return;
      const curIdx = currentCfi ? paragraphs.findIndex(p => p.getAttribute('data-cfi') === currentCfi) : -1;
      const nextIdx = Math.min(paragraphs.length - 1, curIdx + 1);
      const nextCfi = paragraphs[nextIdx].getAttribute('data-cfi') || "";
      if (nextCfi) {
        this.goToCfi(nextCfi, true);
      }
    },
    prevPage: function() {
      console.log("FoliateBridge: prevPage");
      const paragraphs = Array.from(document.querySelectorAll('p[data-cfi]'));
      if (paragraphs.length === 0) return;
      const curIdx = currentCfi ? paragraphs.findIndex(p => p.getAttribute('data-cfi') === currentCfi) : 0;
      const prevIdx = Math.max(0, curIdx - 1);
      const prevCfi = paragraphs[prevIdx].getAttribute('data-cfi') || "";
      if (prevCfi) {
        this.goToCfi(prevCfi, true);
      }
    },
    highlightCfi: function(cfiRange) {
      console.log("FoliateBridge: highlightCfi", cfiRange);
      // Remove previous highlights
      document.querySelectorAll('.highlight-active').forEach(el => {
        el.classList.remove('highlight-active');
      });
      // In a full foliate-js implementation, cfiRange locates the element.
      // For fallback/mock DOM inspection:
      const el = findElementByCfi(cfiRange) || document.querySelector('p');
      if (el) {
        suppressScrollUntil = Date.now() + 250;
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
