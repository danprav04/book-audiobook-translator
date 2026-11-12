# Polyglot Books

<p align="center">
  <strong>Live AI Audiobook Translation & Character-Cast Ebook Reader for Android</strong><br>
  <em>Listen and read in any language with zero tracking, open-source freedom, and Bring-Your-Own-Key AI.</em>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-blue.svg" alt="License: GPL-3.0"></a>
  <a href="https://github.com/danprav04/book-audiobook-translator/actions/workflows/ci.yml"><img src="https://github.com/danprav04/book-audiobook-translator/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <img src="https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-green.svg" alt="Android MinSdk 26">
  <img src="https://img.shields.io/badge/Architecture-Kotlin%20%2B%20Compose%20%2B%20Media3-purple.svg" alt="Kotlin + Compose + Media3">
  <img src="https://img.shields.io/badge/Privacy-100%25%20F--Droid%20Compatible-brightgreen.svg" alt="F-Droid Compatible">
</p>

---

## Overview

**Polyglot Books** is a privacy-first, fully open-source Android application designed for book lovers and language learners. It combines high-performance native media playback and an advanced hybrid ebook reader with modern generative AI capabilities via Google's Gemini Live and Flash APIs:

1. **Live Audiobook Translation**: Stream any foreign-language audiobook (M4B, MP3, AAC, FLAC, OGG, OPUS) and hear it translated in real time into your chosen language (70+ languages supported) with dual live subtitles and intelligent look-ahead buffering.
2. **Character-Cast Ebook Reader & Narrator**: Read EPUB, MOBI, AZW3, PDF, and text formats in a sandboxed, responsive viewer powered by [foliate-js](https://github.com/johnfactotum/foliate-js). Listen to chapters narrated with unique character voices detected by an LLM casting pass, or fallback to offline Android System TTS.
3. **100% Bring-Your-Own-Key (BYOK)**: No subscription fees, no proxy servers, no telemetry, and zero tracking. You provide your own Gemini API key, stored encrypted in the hardware-backed Android Keystore using Google Tink.

---

## Key Features

### 🎧 Live Audiobook Translation
- **Bidirectional Streaming**: Audio is decoded into 16 kHz mono PCM chunks (100 ms) and streamed over secure WebSockets to Gemini's Live Translation API.
- **Look-Ahead Pacing & Caching**: Buffers 10–30 seconds ahead of playback to eliminate network stutters. All translated audio segments are saved locally so replay, scrubbing, and backwards seeking cost 0 tokens.
- **Audio Ducking**: Listen to pure translated speech or duck original audiobook audio down to ~15% volume beneath the translation.
- **Dual Live Subtitles**: Real-time display of original speech transcription alongside the translated transcript.

### 📖 Character-Cast Ebook Reader
- **Hybrid foliate-js Rendering**: Full support for EPUB 2/3, MOBI, AZW3/KF8, FB2, CBZ, and PDF via a sandboxed WebKit bridge.
- **Automated Character Casting**: Analyzes chapter text to detect characters, gender, age, and emotional tone, assigning distinct AI voices to each speaker while the narrator retains a distinct voice.
- **Pluggable Narration Engines**:
  - *Gemini Flash TTS* (`gemini-3.8-flash-tts` via Interactions API)
  - *Gemini Live Narration* (`gemini-3.8-live`)
  - *Android System TTS* (100% offline fallback)
- **Karaoke Sentence Highlighting**: Bidirectional bridge synchronizes audio playback with visible sentence highlight ranges in the reader.

### 🔒 Privacy & BYOK
- **Hardware-Backed Keystore**: Gemini API keys are encrypted with Google Tink AEAD using keys generated in the Android Keystore.
- **Direct-to-Google**: App communicates directly with `generativelanguage.googleapis.com` with no middleman server.
- **F-Droid Compliant**: Zero Google Play Services, Firebase, or proprietary tracking libraries included.

---

## Architecture Overview

Polyglot Books is structured into **12 decoupled Gradle modules**:

```
                              ┌───────────────┐
                              │     :app      │
                              └───────┬───────┘
                                      │
         ┌───────────────┬────────────┴───┬───────────────┬───────────────┐
         ▼               ▼                ▼               ▼               ▼
┌─────────────────┐ ┌─────────┐   ┌───────────────┐ ┌───────────┐ ┌───────────────┐
│:feature:audiobook│ │:feature:│   │:feature:reader│ │:feature:  │ │:feature:      │
│                 │ │:library │   │               │ │:narration │ │:settings      │
└────────┬────────┘ └────┬────┘   └───────┬───────┘ └─────┬─────┘ └───────┬───────┘
         │               │                │               │               │
         ▼               │                │               │               │
┌─────────────────┐      │                │               │               │
│:feature:translate│      │                │               │               │
└────────┬────────┘      │                │               │               │
         │               ▼                │               ▼               ▼
         ├──────────────────────────────┐ │        ┌─────────────┐ ┌─────────────┐
         ▼                              ▼ ▼        │:core:gemini │ │ :core:data  │
┌─────────────────┐            ┌─────────────────┐ └──────┬──────┘ └──────┬──────┘
│  :core:audio    │            │ :core:playback  │        │               │
└────────┬────────┘            └────────┬────────┘        │               │
         │                              │                 │               │
         └──────────────────────────────┴─────────────────┴───────────────┘
                                        │
                                        ▼
                                 ┌─────────────┐
                                 │ :core:model │
                                 └─────────────┘
```

For complete technical details, audio pipelines, WebSocket specifications, and database schema, see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## Bring-Your-Own-Key (BYOK) Setup

1. Obtain a free or paid Gemini API key from [Google AI Studio](https://aistudio.google.com/).
2. Open Polyglot Books and navigate to **Settings** (`⚙`).
3. Paste your API key into the key field.
4. Tap **Test Key** to verify connectivity with Google's API catalog.
5. Tap **Save Key**. Your key is immediately encrypted into the device Keystore.

> **Privacy Note:** Google's Free Tier Gemini API allows Google to use submitted prompts and audio for model training. If you require absolute confidentiality for your books, use a Google Cloud project with billing enabled (Paid Tier). See [PRIVACY.md](PRIVACY.md) for full details.

---

## Building from Source

### Prerequisites
- JDK 17 (Eclipse Temurin or OpenJDK)
- Android SDK with API 35 (`platforms;android-35`, `build-tools;35.0.0`)
- Git

### Build Instructions
```bash
# 1. Clone repository recursively with submodules
git clone --recursive https://github.com/danprav04/book-audiobook-translator.git
cd book-audiobook-translator

# 2. Configure SDK location
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 3. Assemble debug APK
./gradlew assembleDebug

# 4. Run all unit tests
./gradlew testDebugUnitTest

# 5. Run Android Lint
./gradlew lintDebug
```
The compiled debug APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## Distribution

- **F-Droid**: Built purely from source without non-free dependencies, proprietary trackers, or Google Play Services libraries.
- **GitHub Releases**: Automated GitHub Actions CI builds release APKs and App Bundles (AAB) on tagged releases (`v*`).
- **Google Play Store**: In compliance with Google Play's AI-Generated Content Policy, users can flag inappropriate AI outputs via the in-app [AI Output Report](.github/ISSUE_TEMPLATE/ai_output_report.yml).

---

## Contributing

We welcome contributions! Please read our [CONTRIBUTING.md](CONTRIBUTING.md) guide and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) before submitting pull requests.

---

## License

Polyglot Books is licensed under the **GNU General Public License v3.0 (GPL-3.0)**.  
See the [LICENSE](LICENSE) file for the full license text.

Third-party dependencies:
- [foliate-js](https://github.com/johnfactotum/foliate-js) — MIT License
