# Contributing to Polyglot Books

Thank you for your interest in contributing to **Polyglot Books**! We welcome contributions from developers, designers, translators, and documentation writers.

Polyglot Books is a fully open-source, privacy-focused, Bring-Your-Own-Key Android audiobook player and ebook reader built with Kotlin, Jetpack Compose, AndroidX Media3, Room, and WebSockets.

---

## Code of Conduct

All contributors and maintainers are expected to follow our [Code of Conduct](CODE_OF_CONDUCT.md) (Contributor Covenant v2.1). Please review it to understand our community standards.

---

## Getting Started

### Prerequisites
- **JDK 17** (e.g. Eclipse Temurin 17 or OpenJDK 17).
- **Android SDK** with:
  - `platforms;android-35`
  - `build-tools;35.0.0`
  - `cmdline-tools;latest`
- **Git** with submodule support enabled.
- **Android Studio Ladybug (2024.2+)** or command-line Gradle.

### Clone the Repository
Always clone with recursive submodules to include `third_party/foliate-js`:
```bash
git clone --recursive https://github.com/danprav04/book-audiobook-translator.git
cd book-audiobook-translator
```
If you already cloned without `--recursive`:
```bash
git submodule update --init --recursive
```

### Configure Android SDK
Ensure `local.properties` specifies your Android SDK directory:
```properties
sdk.dir=/path/to/android-sdk
```

---

## Building and Testing

Polyglot Books uses Gradle with Kotlin DSL (`*.gradle.kts`) and Version Catalogs (`gradle/libs.versions.toml`).

### Compile Debug APK
```bash
./gradlew assembleDebug
```

### Run Unit Tests
```bash
./gradlew testDebugUnitTest
```

### Run Android Lint
```bash
./gradlew lintDebug
```

### Full Pre-commit Validation
Before submitting any pull request, ensure all three commands pass cleanly:
```bash
./gradlew lintDebug testDebugUnitTest assembleDebug
```

---

## Architectural Principles & Coding Standards

1. **Strict 12-Module Boundaries**:
   - `:app` is a lean shell container.
   - Domain logic lives in `:core:model` (pure JVM, zero Android imports).
   - Never introduce circular dependencies between modules.
2. **Bring-Your-Own-Key (BYOK) & Privacy**:
   - Never commit or log API keys.
   - Do not add third-party tracking, crashlytics, or analytics SDKs. The app must remain 100% F-Droid compatible.
   - Plaintext keys must never be stored in SharedPreferences or standard unencrypted Room tables. Use `SecureKeyStore` (Tink AEAD + Keystore).
3. **Audio & Streaming Pipelines**:
   - Live Translate audio chunks must follow the 16 kHz mono 16-bit LE PCM spec (`Chunker`).
   - Use `Pacer` for look-ahead buffer flow control.
4. **Jetpack Compose Guidelines**:
   - Follow standard Compose state hoisting and unidirectional data flow.
   - Ensure previews and edge-to-edge window handling function across light and dark themes.
5. **Testing Requirements**:
   - Every new domain rule, serialization model, parser, or audio chunking algorithm MUST have accompanying unit tests.
   - Aim for deterministic, isolated unit tests. Mock network endpoints and use fake AEAD cryptors for Keystore-backed tests.

---

## Pull Request Lifecycle

1. **Fork and Branch**:
   Create a descriptive branch for your work:
   ```bash
   git checkout -b feat/your-feature-name
   # or
   git checkout -b fix/issue-description
   ```
2. **Commit Conventions**:
   Write clear, concise commit messages following the Conventional Commits specification:
   - `feat(audiobook): add look-ahead buffer scrubber indicator`
   - `fix(format): correctly detect azw3 headers in byte detector`
   - `docs: update live translate pricing table`
3. **Verify Locally**:
   Ensure `./gradlew lintDebug testDebugUnitTest assembleDebug` passes.
4. **Open Pull Request**:
   - Target the default development branch.
   - Fill in the PR description detailing the problem solved, implementation notes, and testing evidence.
   - Link any relevant issues (e.g. `Fixes #42`).
5. **Continuous Integration**:
   - GitHub Actions will run automated linting, unit tests, and debug APK assembly on your PR.
   - Address any review feedback or test failures promptly.

---

## Reporting Issues & Policy Compliance

- **Bugs**: Use the [Bug Report Template](.github/ISSUE_TEMPLATE/bug.yml).
- **Features**: Use the [Feature Request Template](.github/ISSUE_TEMPLATE/feature.yml).
- **AI-Generated Content Concerns**: For unexpected, inaccurate, or offensive model outputs, submit an [AI Output Report](.github/ISSUE_TEMPLATE/ai_output_report.yml) so prompt engineering and safety filters can be updated in compliance with Google Play's AI-Generated Content Policy.
