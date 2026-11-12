# Polyglot Books Architecture & Technical Specification

Polyglot Books is an Android application designed to combine offline-first media playback, modern ebook rendering, and real-time generative AI streaming. It provides live audiobook translation into 70+ languages and multi-voice character-cast ebook narration.

---

## 1. System Architecture & 12-Module Decomposition

Polyglot Books strictly separates concerns into **12 Gradle modules**:

```
book-audiobook-translator/
├── app/                  # Application container, navigation, Hilt setup, Manifest
├── core/
│   ├── model/            # Pure Kotlin domain entities & value objects (zero Android deps)
│   ├── data/             # Room DB, SecureKeyStore (Tink AEAD), SettingsRepository (DataStore)
│   ├── gemini/           # WebSockets Live API client, REST catalog, cost estimator
│   ├── audio/            # 16kHz PCM chunker, decoders, resamplers, AAC segment cache IO
│   └── playback/         # Media3 ExoPlayer wrapper, MediaSessionService, dual audio ducking
├── feature/
│   ├── library/          # Book import, SAF picker, FormatDetector (magic bytes + ext)
│   ├── audiobook/        # Compose player UI, scrubbers, dual subtitles, language switch
│   ├── translate/        # TranslationPipeline, Pacer look-ahead buffer flow controller
│   ├── reader/           # Compose WebView host, foliate-js bridge, sentence highlighter
│   ├── narration/        # Character CastingService, prompt JSON schemas, TTS engines
│   └── settings/         # API key test/save/delete UI, model roles, cache/ducking sliders
└── third_party/
    └── foliate-js/       # Upstream git submodule for EPUB/MOBI/AZW3/PDF rendering
```

### Module Responsibilities

| Module | Gradle Type | Key Responsibilities |
| :--- | :--- | :--- |
| `:app` | `com.android.application` | Shell hosting `MainActivity`, `PolyglotApplication`, `AppNavHost`, and app-level AndroidManifest permissions. |
| `:core:model` | `org.jetbrains.kotlin.jvm` | Domain entities: `Book`, `Audiobook`, `Chapter`, `Character`, `NarrationLine`, `LanguageCode` (70+ languages), `ModelRole`, `DefaultModels`, `PcmChunk`. Zero Android dependencies. |
| `:core:data` | `com.android.library` | Local persistence: Room database (`PolyglotDatabase`), DAOs, `SecureKeyStore` (Google Tink AEAD + Android Keystore), and `SettingsRepository` (DataStore). |
| `:core:gemini` | `com.android.library` | Gemini API communication: bidirectional WebSocket `LiveSession` messaging, `ModelCatalog` (`models.list` querying), and cost estimators. |
| `:core:audio` | `com.android.library` | Audio processing: `Chunker` (16 kHz mono 16-bit LE PCM, 100 ms = 3200 bytes), `SourceDecoder`, `Resampler`, and `AacSegmentWriter/Reader`. |
| `:core:playback` | `com.android.library` | Media playback: `PolyglotPlaybackService` (`MediaSessionService`), Android Auto/Bluetooth controls, `TranslatedPlayer`, and `NarrationPlayer`. |
| `:feature:library` | `com.android.library` | Library view: SAF file picker, chapter indexing, metadata extraction, and `FormatDetector` (magic bytes & file extension detection). |
| `:feature:audiobook`| `com.android.library` | UI: Player controls, look-ahead buffer indicator, dual subtitles panel (source + translated), and volume ducking controls. |
| `:feature:translate`| `com.android.library` | Orchestration: `TranslationPipeline` connecting audio decoding to Gemini WebSockets, and `Pacer` for look-ahead buffer pacing. |
| `:feature:reader` | `com.android.library` | UI & Bridge: Sandboxed `WebView` hosting `third_party/foliate-js`, bidirectional JavaScript bridge, sentence highlighting. |
| `:feature:narration`| `com.android.library` | Narration logic: `CastingService` (schema + span validation), `VoiceCaster`, and engine stubs (Gemini TTS, Gemini Live, System TTS). |
| `:feature:settings` | `com.android.library` | Interactive Settings UI: API key test/save/delete, per-role model dropdowns, cache size limit, ducking volume, and privacy notices. |

---

## 2. Live Audiobook Translation Pipeline

### Data Flow Diagram

```
[Local Audio File] (MP3, M4B, AAC, FLAC, OGG)
        │
        ▼
[SourceDecoder] (MediaExtractor + MediaCodec)
        │ ➔ Arbitrary PCM (e.g. 44.1kHz stereo)
        ▼
[Resampler]
        │ ➔ 16 kHz Mono 16-bit LE PCM
        ▼
[Chunker]
        │ ➔ Exact 100 ms chunks (3200 bytes)
        ▼
[Pacer Flow Controller] ─── (Monitors look-ahead buffer: 10s–30s)
        │ ➔ Controls WebSocket send rate (1.0x – 1.5x speed)
        ▼
[LiveTranslateClient] (OkHttp WebSocket)
        │ ➔ WSS generativelanguage.googleapis.com
        │ ➔ JSON: {"realtimeInput": {"mediaChunks": [{"data": "base64..."}]}}
        │
        ▼ (Google Gemini 3.5 Live Translate)
        │
        ▲ (Incoming ServerContent: 24 kHz PCM audio + text transcripts)
        │
        ├─────────────────────────────┐
        ▼                             ▼
[TranslatedCacheIndex]        [AudiobookPlayerScreen]
(Writes AAC segments to disk) (Live dual subtitles: source + translated)
        │
        ▼
[TranslatedPlayer (Media3)]
(Plays 24kHz translated audio + optional ducked original at 15% volume)
```

### Flow Control & Pacing Logic (`Pacer`)
To prevent network buffering interruptions, the `Pacer` maintains a look-ahead buffer of translated audio:
- **Target Buffer**: 10 to 30 seconds ahead of playback.
- **Dynamic Speed**:
  - Buffer < 10s: streams audio at **1.5x real-time speed** (`delay = 100ms / 1.5 = 66.6ms`).
  - Buffer 10s–30s: smoothly scales transmission speed from 1.5x down to 1.0x.
  - Buffer >= 30s: pauses transmission until playback consumes the buffer below target threshold.
- **Zero-Token Seeking**: When the user rewinds or replays a section, `TranslatedCacheIndex` serves the audio directly from local AAC cache files without consuming API quota.

---

## 3. Character-Cast Ebook Reader Architecture

### foliate-js Sandboxed WebView Bridge
The reader leverages the open-source **foliate-js** rendering engine running inside an isolated Android `WebView`:
- **File Ingestion**: Direct loading of EPUB 2/3, MOBI, AZW3/KF8, FB2, and PDF documents.
- **JavaScript Interface Bridge (`ReaderBridge`)**:
  - `extractParagraphs(chapterIndex)`: Extracts raw paragraph text and CFI (Canonical Fragment Identifier) ranges.
  - `highlightLine(cfiRange)`: Highlights the sentence or paragraph currently being spoken by the narration engine.
  - `onPageTurned(cfi)`: Notifies Kotlin when the user manually navigates pages.

### Automated Character Casting (`CastingService`)
Before narration begins, chapter text is analyzed by a text model (`gemini-3.8-flash`) using structured JSON output:
1. **Extraction**: Identifies characters, aliases, inferred gender, age, and voice traits.
2. **Span Attribution**: Breaks paragraphs into line segments with speaker assignments (`speakerId` or `"narrator"`).
3. **Validation & Normalization**:
   - Ensures line start/end offsets are non-overlapping and bounded within paragraph text length.
   - Automatically fills unassigned text gaps with default `"narrator"` lines so 100% of book content is spoken.

### Pluggable Narration Engines
- **Gemini Flash TTS (`gemini-3.8-flash-tts`)**: High-fidelity speech synthesis via Google's REST Interactions API with multi-voice support.
- **Gemini Live Narration (`gemini-3.8-live`)**: Low-latency conversational narration via WebSockets.
- **System TTS (`android.speech.tts.TextToSpeech`)**: Completely offline, zero-network fallback using device voices.

---

## 4. Security & Cryptographic Storage

### Keystore-Backed Tink AEAD
User Gemini API keys are protected using **Google Tink**:
- **Key Derivation**: Tink master key URI `android-keystore://polyglot_master_key` backed by the hardware-backed Android Keystore / StrongBox HSM.
- **Cipher**: AES256-GCM AEAD encryption.
- **Isolated Storage**: Encrypted ciphertext is persisted in private DataStore storage. Keys are never written to disk in plaintext.
- **Backup Exclusion**:
  ```xml
  <full-backup-content>
      <exclude domain="sharedpref" path="io.github.danprav04.polyglotbooks_secure_prefs.preferences_pb"/>
  </full-backup-content>
  ```
- **Testability**: Architecture decouples `Aead` interface from `AndroidKeysetManager`, allowing host unit tests to run with a fake in-memory AEAD without requiring Android Keystore emulators.

---

## 5. Room Database Schema

```sql
-- Books table
CREATE TABLE books (
    id TEXT PRIMARY KEY NOT NULL,
    title TEXT NOT NULL,
    author TEXT,
    format TEXT NOT NULL,
    file_path TEXT NOT NULL,
    cover_uri TEXT,
    is_audiobook INTEGER NOT NULL DEFAULT 0,
    added_date INTEGER NOT NULL,
    last_read_date INTEGER
);

-- Chapters table
CREATE TABLE chapters (
    id TEXT PRIMARY KEY NOT NULL,
    book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    chapter_index INTEGER NOT NULL,
    title TEXT NOT NULL,
    start_ms INTEGER,
    end_ms INTEGER,
    cfi_range TEXT
);

-- Characters table
CREATE TABLE characters (
    id TEXT PRIMARY KEY NOT NULL,
    book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    aliases TEXT NOT NULL, -- JSON array
    gender TEXT,
    age TEXT,
    voice_traits TEXT,
    assigned_voice_id TEXT
);

-- Translation segment cache
CREATE TABLE translation_segments (
    id TEXT PRIMARY KEY NOT NULL,
    book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
    target_language TEXT NOT NULL,
    source_start_ms INTEGER NOT NULL,
    source_end_ms INTEGER NOT NULL,
    aac_file_path TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
```

---

## 6. Live API WebSocket Protocol

Polyglot Books communicates with Gemini Live via OkHttp WebSockets:
- **Endpoint**: `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent`
- **Query Parameter**: `?key={API_KEY}`

### Message Types
1. **Setup**:
   Configures model ID, audio response modality, transcription, and translation configuration:
   ```json
   {
     "setup": {
       "model": "models/gemini-3.5-live-translate-preview",
       "generationConfig": {
         "responseModalities": ["AUDIO"],
         "inputAudioTranscription": {},
         "outputAudioTranscription": {},
         "translationConfig": {
           "targetLanguageCode": "es",
           "echoTargetLanguage": true
         }
       }
     }
   }
   ```
2. **RealtimeInput**:
   Transmits 16 kHz mono 16-bit LE PCM audio chunks formatted in base64:
   ```json
   {
     "realtimeInput": {
       "mediaChunks": [
         {
           "mimeType": "audio/pcm;rate=16000",
           "data": "..."
         }
       ]
     }
   }
   ```
3. **ServerContent**:
   Receives 24 kHz PCM audio chunks and dual transcriptions (source audio text and translated text).
4. **GoAway**:
   Graceful connection termination indicator from server with `timeLeft`.
5. **SessionResumptionUpdate**:
   Provides resumption token handles for seamless reconnection during network transitions.
