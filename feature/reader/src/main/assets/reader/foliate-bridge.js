/**
 * Foliate-js & Polyglot Books JavaScript bridge communication layer.
 */
(function () {
  const CHAPTERS_URL = "https://appassets.androidplatform.net/book/chapters.json";
  const PDF_PAGE_BASE_URL = "https://appassets.androidplatform.net/book/pdf-page/";
  const BLOCK_SELECTOR = "p, h1, h2, h3, h4, h5, h6, li, blockquote";

  let chaptersData = null;
  let currentBookUri = "";
  let currentFormat = "";
  let currentChapterIndex = 0;
  let currentPdfPage = 0;
  let totalPdfPages = 0;
  let currentCfi = "";
  let currentFraction = 0.0;
  let isBookReady = false;
  let hasDirtyPosition = false;
  let suppressScrollUntil = 0;
  let scrollReportTimer = null;
  let activeHighlightCfi = null;
  let lastTranslationMap = {};

  function getContainer() {
    return document.getElementById("reader-container");
  }

  function isPdfFormat() {
    return currentFormat === "PDF";
  }

  function escapeHtml(str) {
    return String(str)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function notifyBookReady() {
    if (window.AndroidReaderBridge && typeof window.AndroidReaderBridge.onBookReady === "function") {
      window.AndroidReaderBridge.onBookReady();
    }
  }

  function notifyError(message) {
    console.error("FoliateBridge error:", message);
    if (window.AndroidReaderBridge && typeof window.AndroidReaderBridge.onReaderError === "function") {
      window.AndroidReaderBridge.onReaderError(String(message || "Unknown reader error"));
    }
  }

  function notifyPageTurn(cfi, fraction, chapterTitle) {
    if (!cfi && !(fraction > 0)) return;
    if (window.AndroidReaderBridge && typeof window.AndroidReaderBridge.onPageTurn === "function") {
      window.AndroidReaderBridge.onPageTurn(cfi, Number(fraction) || 0, chapterTitle || "");
    }
  }

  function notifyParagraphs(chapterIndex, paragraphs) {
    if (window.AndroidReaderBridge && typeof window.AndroidReaderBridge.postParagraphs === "function") {
      window.AndroidReaderBridge.postParagraphs(chapterIndex, JSON.stringify(paragraphs));
    }
  }

  function notifyChaptersExtracted(rawJson) {
    if (window.AndroidReaderBridge && typeof window.AndroidReaderBridge.onChaptersExtracted === "function") {
      window.AndroidReaderBridge.onChaptersExtracted(rawJson);
    }
  }

  function buildEpubCfi(chapterIndex, paragraphIndex) {
    return "epubcfi(/6/" + ((chapterIndex + 1) * 2) + "!/4/2/" + (paragraphIndex + 1) + ":0)";
  }

  function parseChapterIndexFromCfi(cfi) {
    if (!cfi) return null;
    const epubMatch = /epubcfi\(\s*\/6\/(\d+)/i.exec(cfi);
    if (epubMatch) {
      const spineStep = parseInt(epubMatch[1], 10);
      if (!isNaN(spineStep) && spineStep >= 2) {
        return Math.max(0, Math.floor(spineStep / 2) - 1);
      }
    }
    if (chaptersData && Array.isArray(chaptersData.chapters)) {
      const idx = chaptersData.chapters.findIndex(function (ch) {
        return ch.href === cfi;
      });
      if (idx >= 0) return idx;
    }
    return null;
  }

  function parsePdfPageFromCfi(cfi) {
    if (!cfi) return null;
    const match = /pdf:page:(\d+)/i.exec(cfi);
    if (match) {
      const page = parseInt(match[1], 10);
      if (!isNaN(page) && page >= 0) return page;
    }
    return null;
  }

  function chapterIndexForPdfPage(pageIndex) {
    if (!chaptersData || !Array.isArray(chaptersData.chapters) || chaptersData.chapters.length === 0) {
      return 0;
    }
    if (chaptersData.chapters.length >= totalPdfPages) {
      return Math.min(pageIndex, chaptersData.chapters.length - 1);
    }
    return Math.min(Math.floor(pageIndex / 10), chaptersData.chapters.length - 1);
  }

  function findElementByCfi(cfi) {
    if (!cfi) return null;
    const elements = document.querySelectorAll("[data-cfi]");
    for (let i = 0; i < elements.length; i++) {
      if (elements[i].getAttribute("data-cfi") === cfi) {
        return elements[i];
      }
    }
    return null;
  }

  function estimateFractionForElement(el) {
    if (!el) return currentFraction;
    const paragraphs = Array.from(document.querySelectorAll("[data-cfi]"));
    const idx = paragraphs.indexOf(el);
    const totalChapters = chaptersData && Array.isArray(chaptersData.chapters) ? chaptersData.chapters.length : 1;
    if (idx >= 0 && paragraphs.length > 0) {
      const withinChapter = paragraphs.length === 1 ? 0.0 : idx / (paragraphs.length - 1);
      if (totalChapters <= 1) return withinChapter;
      return Math.min(1.0, Math.max(0.0, (currentChapterIndex + withinChapter) / totalChapters));
    }
    return currentFraction;
  }

  function attachScrollTracking() {
    if (window.__polyglotScrollBound) return;
    window.__polyglotScrollBound = true;

    window.addEventListener("scroll", function () {
      const container = getContainer();
      if (!container || !isBookReady || isPdfFormat() || Date.now() < suppressScrollUntil) return;
      const paragraphs = Array.from(container.querySelectorAll("[data-cfi]"));
      if (paragraphs.length === 0) return;

      let topVisible = null;
      for (let i = 0; i < paragraphs.length; i++) {
        const rect = paragraphs[i].getBoundingClientRect();
        if (rect.bottom > 4) {
          topVisible = paragraphs[i];
          break;
        }
      }
      if (!topVisible) {
        topVisible = paragraphs[paragraphs.length - 1];
      }
      const cfi = topVisible.getAttribute("data-cfi") || "";
      if (!cfi) return;
      currentCfi = cfi;
      currentFraction = estimateFractionForElement(topVisible);
      hasDirtyPosition = true;

      if (scrollReportTimer) clearTimeout(scrollReportTimer);
      scrollReportTimer = setTimeout(function () {
        if (hasDirtyPosition) {
          hasDirtyPosition = false;
          const title = chaptersData?.chapters?.[currentChapterIndex]?.title || "";
          notifyPageTurn(currentCfi, currentFraction, title);
        }
      }, 300);
    }, { passive: true });
  }

  function attachSelectionTracking() {
    if (document.__polyglotSelectionBound) return;
    document.__polyglotSelectionBound = true;
    document.addEventListener("selectionchange", function () {
      if (!isBookReady) return;
      const sel = window.getSelection();
      if (!sel || sel.isCollapsed) return;
      const text = sel.toString().trim();
      if (!text) return;
      let node = sel.anchorNode;
      while (node && node.nodeType !== 1) node = node.parentNode;
      const cfiEl = node && node.closest ? node.closest("[data-cfi]") : null;
      const cfiRange = cfiEl ? cfiEl.getAttribute("data-cfi") || currentCfi : currentCfi;
      if (window.AndroidReaderBridge && typeof window.AndroidReaderBridge.onSelection === "function") {
        window.AndroidReaderBridge.onSelection(cfiRange || "", text);
      }
    });
  }

  function applyBilingualTranslationsToDom() {
    const elements = document.querySelectorAll("[data-cfi]");
    elements.forEach(function (el) {
      const cfi = el.getAttribute("data-cfi");
      const translation = cfi ? lastTranslationMap[cfi] : null;
      const next = el.nextElementSibling;
      const hasExisting = next && next.classList.contains("bilingual-translation");
      if (translation) {
        if (!hasExisting) {
          const transEl = document.createElement("div");
          transEl.className = "bilingual-translation";
          transEl.textContent = translation;
          el.parentNode.insertBefore(transEl, el.nextSibling);
        } else {
          next.textContent = translation;
        }
      } else if (hasExisting) {
        next.remove();
      }
    });
  }

  function applyHighlightToDom() {
    document.querySelectorAll(".highlight-active").forEach(function (el) {
      el.classList.remove("highlight-active");
    });
    if (!activeHighlightCfi) return;
    const el = findElementByCfi(activeHighlightCfi);
    if (el) {
      el.classList.add("highlight-active");
    }
  }

  function renderPdfPage(pageIndex, notifyAndroid) {
    const container = getContainer();
    if (!container) return;

    const clampedPage = Math.max(0, Math.min(pageIndex, Math.max(0, totalPdfPages - 1)));
    currentPdfPage = clampedPage;
    currentChapterIndex = chapterIndexForPdfPage(clampedPage);

    const chapter = chaptersData?.chapters?.[currentChapterIndex];
    const paragraphs = Array.isArray(chapter?.paragraphs) ? chapter.paragraphs : [];
    const imgUrl = PDF_PAGE_BASE_URL + clampedPage + ".png";

    container.innerHTML = "";
    const viewer = document.createElement("div");
    viewer.className = "pdf-viewer";

    const toolbar = document.createElement("div");
    toolbar.className = "pdf-toolbar";

    const prevBtn = document.createElement("button");
    prevBtn.className = "pdf-nav-btn";
    prevBtn.textContent = "Prev Page";
    prevBtn.disabled = clampedPage <= 0;
    prevBtn.onclick = function () {
      window.FoliateBridge.prevPage();
    };

    const pageLabel = document.createElement("span");
    pageLabel.textContent = "Page " + (clampedPage + 1) + " of " + totalPdfPages;

    const nextBtn = document.createElement("button");
    nextBtn.className = "pdf-nav-btn";
    nextBtn.textContent = "Next Page";
    nextBtn.disabled = clampedPage >= totalPdfPages - 1;
    nextBtn.onclick = function () {
      window.FoliateBridge.nextPage();
    };

    toolbar.appendChild(prevBtn);
    toolbar.appendChild(pageLabel);
    toolbar.appendChild(nextBtn);
    viewer.appendChild(toolbar);

    const frame = document.createElement("div");
    frame.className = "pdf-page-frame";
    const img = document.createElement("img");
    img.className = "pdf-page-img";
    img.src = imgUrl;
    img.alt = "Page " + (clampedPage + 1);
    img.onerror = function () {
      frame.innerHTML = "<div class=\"error-state\">Unable to render PDF page " + (clampedPage + 1) + "</div>";
    };
    frame.appendChild(img);
    viewer.appendChild(frame);

    const details = document.createElement("details");
    details.className = "pdf-text-section";
    details.open = true;

    const summary = document.createElement("summary");
    summary.textContent = "Page Text (" + paragraphs.length + " paragraphs)";
    details.appendChild(summary);

    const extractedPayload = [];
    if (paragraphs.length > 0) {
      paragraphs.forEach(function (text, pIdx) {
        const p = document.createElement("p");
        const cfi = "pdf:page:" + clampedPage + ":p:" + pIdx;
        p.setAttribute("data-cfi", cfi);
        p.setAttribute("data-paragraph-index", String(pIdx));
        p.setAttribute("data-id", "pdf_" + clampedPage + "_" + pIdx);
        p.textContent = text;
        details.appendChild(p);
        extractedPayload.push({
          id: "pdf_" + clampedPage + "_" + pIdx,
          text: text,
          cfiRange: cfi
        });
      });
    } else {
      const emptyNote = document.createElement("div");
      emptyNote.textContent = "No embedded text extracted on this page.";
      details.appendChild(emptyNote);
    }

    viewer.appendChild(details);
    container.appendChild(viewer);

    suppressScrollUntil = Date.now() + 250;
    window.scrollTo(0, 0);
    document.documentElement.scrollTop = 0;
    void container.offsetHeight;

    currentCfi = "pdf:page:" + clampedPage;
    currentFraction = totalPdfPages <= 1 ? 0.0 : clampedPage / (totalPdfPages - 1);
    hasDirtyPosition = false;

    applyBilingualTranslationsToDom();
    applyHighlightToDom();
    notifyParagraphs(currentChapterIndex, extractedPayload);

    if (notifyAndroid !== false) {
      notifyPageTurn(currentCfi, currentFraction, "Page " + (clampedPage + 1));
    }
  }

  function renderReflowableChapter(chapterIndex, notifyAndroid) {
    const container = getContainer();
    if (!container || !chaptersData || !Array.isArray(chaptersData.chapters) || chaptersData.chapters.length === 0) {
      return;
    }

    const clampedIndex = Math.max(0, Math.min(chapterIndex, chaptersData.chapters.length - 1));
    currentChapterIndex = clampedIndex;
    const chapter = chaptersData.chapters[clampedIndex];

    container.innerHTML = "";
    if (chapter.title) {
      const header = document.createElement("div");
      header.className = "chapter-header";
      header.setAttribute("data-synthetic-header", "true");
      header.textContent = chapter.title;
      container.appendChild(header);
    }

    const bodyWrapper = document.createElement("div");
    bodyWrapper.className = "chapter-content";
    if (chapter.html && chapter.html.trim().length > 0) {
      bodyWrapper.innerHTML = chapter.html;
    } else if (Array.isArray(chapter.paragraphs)) {
      bodyWrapper.innerHTML = chapter.paragraphs.map(function (t) {
        return "<p>" + escapeHtml(t) + "</p>";
      }).join("\n");
    }
    container.appendChild(bodyWrapper);

    // Tag leaf block elements so data-cfi & data-paragraph-index align with extracted paragraphs.
    const candidates = Array.from(bodyWrapper.querySelectorAll(BLOCK_SELECTOR));
    let leafBlocks = candidates.filter(function (el) {
      if (el.hasAttribute("data-synthetic-header")) return false;
      const hasNestedBlock = el.querySelector(BLOCK_SELECTOR) !== null;
      if (hasNestedBlock) return false;
      return (el.textContent || "").replace(/\s+/g, " ").trim().length > 0;
    });

    if (leafBlocks.length === 0 && Array.isArray(chapter.paragraphs) && chapter.paragraphs.length > 0) {
      bodyWrapper.innerHTML = chapter.paragraphs.map(function (t) {
        return "<p>" + escapeHtml(t) + "</p>";
      }).join("\n");
      leafBlocks = Array.from(bodyWrapper.querySelectorAll("p"));
    }

    const extractedPayload = [];
    leafBlocks.forEach(function (el, pIndex) {
      const cfi = buildEpubCfi(clampedIndex, pIndex);
      const id = "p_" + clampedIndex + "_" + pIndex;
      const text = (el.textContent || "").replace(/\s+/g, " ").trim();
      el.setAttribute("data-cfi", cfi);
      el.setAttribute("data-paragraph-index", String(pIndex));
      el.setAttribute("data-id", id);
      extractedPayload.push({
        id: id,
        text: text,
        cfiRange: cfi
      });
    });

    suppressScrollUntil = Date.now() + 250;
    window.scrollTo(0, 0);
    document.documentElement.scrollTop = 0;
    void container.offsetHeight;

    if (extractedPayload.length > 0) {
      currentCfi = extractedPayload[0].cfiRange;
    } else {
      currentCfi = buildEpubCfi(clampedIndex, 0);
    }
    const totalChapters = chaptersData.chapters.length;
    currentFraction = totalChapters <= 1 ? 0.0 : clampedIndex / (totalChapters - 1);
    hasDirtyPosition = false;

    applyBilingualTranslationsToDom();
    applyHighlightToDom();
    notifyParagraphs(clampedIndex, extractedPayload);

    if (notifyAndroid !== false) {
      notifyPageTurn(currentCfi, currentFraction, chapter.title || "");
    }
  }

  window.FoliateBridge = {
    openBook: async function (bookUrl, formatHint, initialCfi) {
      let resolvedFormatHint = formatHint;
      let resolvedInitialCfi = initialCfi;
      if (arguments.length === 2 && typeof formatHint === "string" &&
          (formatHint.startsWith("epubcfi(") || formatHint.startsWith("pdf:page:"))) {
        resolvedInitialCfi = formatHint;
        resolvedFormatHint = "";
      }

      currentBookUri = bookUrl || "";
      isBookReady = false;
      hasDirtyPosition = false;

      const container = getContainer();
      if (container) {
        container.innerHTML = "<div class=\"loading-state\">Loading book…</div>";
      }

      attachScrollTracking();
      attachSelectionTracking();

      try {
        const response = await fetch(CHAPTERS_URL, { cache: "no-store" });
        if (!response.ok) {
          throw new Error("Failed to load chapters.json (HTTP " + response.status + ")");
        }
        const rawJson = await response.text();
        chaptersData = JSON.parse(rawJson);
        notifyChaptersExtracted(rawJson);

        currentFormat = String(resolvedFormatHint || chaptersData.format || "").trim().toUpperCase();
        if (isPdfFormat()) {
          totalPdfPages = Math.max(
            Number(chaptersData.pageCount) || 0,
            Array.isArray(chaptersData.chapters) ? chaptersData.chapters.length : 0,
            1
          );
          const initialPage = parsePdfPageFromCfi(resolvedInitialCfi) ?? 0;
          isBookReady = true;
          renderPdfPage(initialPage, false);
        } else {
          const chapters = Array.isArray(chaptersData.chapters) ? chaptersData.chapters : [];
          if (chapters.length === 0) {
            if (container) {
              container.innerHTML = "<div class=\"error-state\">No readable content found in this book.</div>";
            }
          } else {
            const initialChapter = parseChapterIndexFromCfi(resolvedInitialCfi) ?? 0;
            const clamped = Math.max(0, Math.min(initialChapter, chapters.length - 1));
            isBookReady = true;
            renderReflowableChapter(clamped, false);
            if (resolvedInitialCfi && String(resolvedInitialCfi).trim().length > 0) {
              this.goToCfi(resolvedInitialCfi, false);
            }
          }
        }
        isBookReady = true;
        notifyBookReady();
      } catch (err) {
        const msg = err && err.message ? err.message : String(err);
        if (container) {
          container.innerHTML = "<div class=\"error-state\">Could not open book: " + escapeHtml(msg) + "</div>";
        }
        notifyError(msg);
      }
    },

    loadChapter: function (chapterIndex, notifyAndroid) {
      if (!chaptersData || !Array.isArray(chaptersData.chapters) || chaptersData.chapters.length === 0) {
        return;
      }
      const idx = Math.max(0, Math.min(Number(chapterIndex) || 0, chaptersData.chapters.length - 1));
      if (isPdfFormat()) {
        const ch = chaptersData.chapters[idx];
        const pageFromHref = parsePdfPageFromCfi(ch?.href) ?? idx;
        renderPdfPage(pageFromHref, notifyAndroid);
      } else {
        renderReflowableChapter(idx, notifyAndroid);
      }
    },

    goToCfi: function (cfi, notifyAndroid) {
      if (!cfi) return;
      if (isPdfFormat()) {
        const targetPage = parsePdfPageFromCfi(cfi);
        if (targetPage !== null && targetPage !== currentPdfPage) {
          renderPdfPage(targetPage, false);
        }
        currentCfi = cfi;
        const el = findElementByCfi(cfi);
        if (el) {
          suppressScrollUntil = Date.now() + 250;
          el.scrollIntoView({ behavior: notifyAndroid === false ? "auto" : "smooth", block: "center" });
        }
        if (notifyAndroid === false) {
          hasDirtyPosition = false;
          return;
        }
        currentFraction = totalPdfPages <= 1 ? 0.0 : currentPdfPage / (totalPdfPages - 1);
        hasDirtyPosition = false;
        notifyPageTurn(cfi, currentFraction, "Page " + (currentPdfPage + 1));
        return;
      }

      const targetChapter = parseChapterIndexFromCfi(cfi);
      if (
        targetChapter !== null &&
        chaptersData &&
        Array.isArray(chaptersData.chapters) &&
        targetChapter < chaptersData.chapters.length &&
        targetChapter !== currentChapterIndex
      ) {
        renderReflowableChapter(targetChapter, false);
      }

      currentCfi = cfi;
      const el = findElementByCfi(cfi);
      if (el) {
        suppressScrollUntil = Date.now() + 250;
        el.scrollIntoView({ behavior: notifyAndroid === false ? "auto" : "smooth", block: "center" });
      }
      if (notifyAndroid === false) {
        hasDirtyPosition = false;
        return;
      }
      const fraction = el ? estimateFractionForElement(el) : currentFraction;
      currentFraction = fraction;
      hasDirtyPosition = false;
      const title = chaptersData?.chapters?.[currentChapterIndex]?.title || "";
      notifyPageTurn(cfi, fraction, title);
    },

    flushPosition: function () {
      if (scrollReportTimer) {
        clearTimeout(scrollReportTimer);
        scrollReportTimer = null;
      }
      if (hasDirtyPosition && currentCfi) {
        hasDirtyPosition = false;
        const title = isPdfFormat()
          ? "Page " + (currentPdfPage + 1)
          : (chaptersData?.chapters?.[currentChapterIndex]?.title || "");
        notifyPageTurn(currentCfi, currentFraction, title);
      }
    },

    nextPage: function () {
      if (isPdfFormat()) {
        if (currentPdfPage < totalPdfPages - 1) {
          renderPdfPage(currentPdfPage + 1, true);
        }
        return;
      }
      const scrollTop = window.scrollY || document.documentElement.scrollTop || 0;
      const viewportHeight = window.innerHeight || document.documentElement.clientHeight || 600;
      const docHeight = document.documentElement.scrollHeight || 0;
      const atBottom = scrollTop + viewportHeight >= docHeight - 12;
      if (!atBottom && docHeight > viewportHeight) {
        const step = Math.max(120, Math.floor(viewportHeight * 0.85));
        window.scrollBy({ top: step, behavior: "smooth" });
        return;
      }
      const chapters = chaptersData?.chapters || [];
      if (currentChapterIndex < chapters.length - 1) {
        renderReflowableChapter(currentChapterIndex + 1, true);
      }
    },

    prevPage: function () {
      if (isPdfFormat()) {
        if (currentPdfPage > 0) {
          renderPdfPage(currentPdfPage - 1, true);
        }
        return;
      }
      const scrollTop = window.scrollY || document.documentElement.scrollTop || 0;
      const viewportHeight = window.innerHeight || document.documentElement.clientHeight || 600;
      const atTop = scrollTop <= 12;
      if (!atTop) {
        const step = Math.max(120, Math.floor(viewportHeight * 0.85));
        window.scrollBy({ top: -step, behavior: "smooth" });
        return;
      }
      if (currentChapterIndex > 0) {
        renderReflowableChapter(currentChapterIndex - 1, true);
      }
    },

    highlightCfi: function (cfiRange, followScroll) {
      activeHighlightCfi = cfiRange || null;
      if (!cfiRange) {
        this.clearHighlights();
        return;
      }
      if (isPdfFormat()) {
        const targetPage = parsePdfPageFromCfi(cfiRange);
        if (targetPage !== null && targetPage !== currentPdfPage) {
          renderPdfPage(targetPage, false);
        }
      } else {
        const targetChapter = parseChapterIndexFromCfi(cfiRange);
        if (
          targetChapter !== null &&
          chaptersData?.chapters &&
          targetChapter < chaptersData.chapters.length &&
          targetChapter !== currentChapterIndex
        ) {
          renderReflowableChapter(targetChapter, false);
        }
      }
      applyHighlightToDom();
      if (followScroll !== false) {
        const el = findElementByCfi(cfiRange);
        if (el) {
          const rect = el.getBoundingClientRect();
          const vh = window.innerHeight || document.documentElement.clientHeight || 600;
          const comfortTop = vh * 0.18;
          const comfortBottom = vh * 0.72;
          const outOfComfortZone = rect.top < comfortTop || rect.bottom > comfortBottom;
          if (outOfComfortZone) {
            suppressScrollUntil = Date.now() + 350;
            el.scrollIntoView({ behavior: "smooth", block: "center" });
          }
        }
      }
    },

    jumpToHighlight: function () {
      if (!activeHighlightCfi) return;
      if (!isPdfFormat()) {
        const targetChapter = parseChapterIndexFromCfi(activeHighlightCfi);
        if (
          targetChapter !== null &&
          chaptersData?.chapters &&
          targetChapter < chaptersData.chapters.length &&
          targetChapter !== currentChapterIndex
        ) {
          renderReflowableChapter(targetChapter, false);
          applyHighlightToDom();
        }
      }
      const el = findElementByCfi(activeHighlightCfi);
      if (el) {
        suppressScrollUntil = Date.now() + 350;
        el.scrollIntoView({ behavior: "smooth", block: "center" });
      }
    },

    clearHighlights: function () {
      activeHighlightCfi = null;
      document.querySelectorAll(".highlight-active").forEach(function (el) {
        el.classList.remove("highlight-active");
      });
    },

    setTheme: function (themeName, fontSizeSp, fontFamily) {
      const normalized = String(themeName || "light").toLowerCase();
      const themeClass = "theme-" + normalized;
      document.documentElement.className = themeClass;
      document.body.className = themeClass;
      document.documentElement.style.colorScheme = normalized === "dark" ? "dark" : "light";

      if (fontSizeSp) {
        const px = Number(fontSizeSp) + "px";
        document.body.style.fontSize = px;
        const container = getContainer();
        if (container) {
          container.style.fontSize = px;
        }
      }

      if (fontFamily) {
        const lower = String(fontFamily).toLowerCase();
        let cssFont = fontFamily;
        if (lower === "serif") {
          cssFont = "Georgia, \"Times New Roman\", serif";
        } else if (lower === "monospace" || lower === "mono") {
          cssFont = "\"Courier New\", Courier, monospace";
        } else if (lower === "sans-serif" || lower === "sans") {
          cssFont = "-apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, sans-serif";
        }
        document.body.style.fontFamily = cssFont;
        const container = getContainer();
        if (container) {
          container.style.fontFamily = cssFont;
        }
      }
    },

    searchInBook: function (query) {
      const q = String(query || "").trim().toLowerCase();
      const results = [];
      if (q.length > 0 && chaptersData && Array.isArray(chaptersData.chapters)) {
        const chapters = chaptersData.chapters;
        for (let cIdx = 0; cIdx < chapters.length; cIdx++) {
          const ch = chapters[cIdx];
          const paragraphs = Array.isArray(ch.paragraphs) ? ch.paragraphs : [];
          for (let pIdx = 0; pIdx < paragraphs.length; pIdx++) {
            const text = String(paragraphs[pIdx] || "");
            const matchIdx = text.toLowerCase().indexOf(q);
            if (matchIdx >= 0) {
              let cfi;
              if (isPdfFormat()) {
                const basePage = parsePdfPageFromCfi(ch.href) ?? cIdx;
                cfi = "pdf:page:" + basePage + ":p:" + pIdx;
              } else {
                cfi = buildEpubCfi(cIdx, pIdx);
              }
              const start = Math.max(0, matchIdx - 30);
              const end = Math.min(text.length, start + 120);
              const excerpt = (start > 0 ? "…" : "") + text.substring(start, end) + (end < text.length ? "…" : "");
              results.push({ cfi: cfi, excerpt: excerpt });
              if (results.length >= 100) break;
            }
          }
          if (results.length >= 100) break;
        }
      } else if (q.length > 0) {
        document.querySelectorAll("[data-cfi]").forEach(function (el) {
          const text = el.textContent || "";
          if (text.toLowerCase().includes(q)) {
            results.push({
              cfi: el.getAttribute("data-cfi") || "",
              excerpt: text.substring(0, 120)
            });
          }
        });
      }

      if (window.AndroidReaderBridge && typeof window.AndroidReaderBridge.postSearchResults === "function") {
        window.AndroidReaderBridge.postSearchResults(JSON.stringify(results));
      }
    },

    setBilingualTranslations: function (translationsJson) {
      let map = {};
      if (typeof translationsJson === "string") {
        try {
          map = JSON.parse(translationsJson) || {};
        } catch (_e) {
          map = {};
        }
      } else if (translationsJson && typeof translationsJson === "object") {
        map = translationsJson;
      }
      lastTranslationMap = map;
      applyBilingualTranslationsToDom();
    },

    extractParagraphs: function (chapterIndex) {
      const targetChapter = typeof chapterIndex === "number" ? chapterIndex : currentChapterIndex;
      const paragraphs = [];
      document.querySelectorAll("[data-cfi]").forEach(function (el, idx) {
        paragraphs.push({
          id: el.getAttribute("data-id") || ("p_" + idx),
          text: (el.textContent || "").replace(/\s+/g, " ").trim(),
          cfiRange: el.getAttribute("data-cfi") || buildEpubCfi(targetChapter, idx)
        });
      });
      notifyParagraphs(targetChapter, paragraphs);
    }
  };

  console.log("FoliateBridge fully initialized.");
})();
