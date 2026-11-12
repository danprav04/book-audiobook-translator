package io.github.danprav04.polyglotbooks.feature.library

/**
 * Enumeration of all supported ebook and audiobook formats.
 */
enum class BookFormat(
    val extension: String,
    val isAudiobook: Boolean,
    val displayName: String
) {
    // Ebooks (12 formats)
    EPUB("epub", false, "EPUB eBook"),
    MOBI("mobi", false, "Mobipocket eBook"),
    AZW3("azw3", false, "Kindle Format 8"),
    FB2("fb2", false, "FictionBook 2"),
    CBZ("cbz", false, "Comic Book ZIP"),
    PDF("pdf", false, "Portable Document Format"),
    TXT("txt", false, "Plain Text"),
    MD("md", false, "Markdown"),
    HTML("html", false, "Hypertext Markup Language"),
    DOCX("docx", false, "Microsoft Word"),
    ODT("odt", false, "OpenDocument Text"),
    RTF("rtf", false, "Rich Text Format"),

    // Audiobooks (7 formats)
    M4B("m4b", true, "MPEG-4 Audiobook"),
    M4A("m4a", true, "MPEG-4 Audio"),
    AAC("aac", true, "Advanced Audio Coding"),
    MP3("mp3", true, "MPEG Layer-3 Audio"),
    OGG("ogg", true, "Ogg Vorbis Audio"),
    OPUS("opus", true, "Opus Audio"),
    FLAC("flac", true, "Free Lossless Audio Codec"),
    WAV("wav", true, "Waveform Audio"),
    CUE("cue", true, "CUE Sheet"),

    // Unknown format
    UNKNOWN("", false, "Unknown Format");

    companion object {
        fun fromExtension(ext: String?): BookFormat {
            if (ext.isNullOrBlank()) return UNKNOWN
            val cleanExt = ext.lowercase().trimStart('.')
            return when (cleanExt) {
                "epub" -> EPUB
                "mobi" -> MOBI
                "azw3", "azw", "kf8" -> AZW3
                "fb2", "fbz" -> FB2
                "cbz" -> CBZ
                "pdf" -> PDF
                "txt" -> TXT
                "md", "markdown" -> MD
                "html", "htm", "xhtml" -> HTML
                "docx" -> DOCX
                "odt" -> ODT
                "rtf" -> RTF
                "m4b" -> M4B
                "m4a" -> M4A
                "aac" -> AAC
                "mp3" -> MP3
                "ogg", "oga" -> OGG
                "opus" -> OPUS
                "flac" -> FLAC
                "wav", "wave" -> WAV
                "cue" -> CUE
                else -> UNKNOWN
            }
        }
    }
}
