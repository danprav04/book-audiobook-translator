# Security Policy

## Supported Versions

Only the latest release of Polyglot Books receives active security patches.

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |
| < 1.0.0 | :x:                |

---

## Architecture Security Model

Polyglot Books follows a strict **Bring-Your-Own-Key (BYOK)**, serverless, and privacy-preserving client architecture.

### 1. Zero Backend & No Intermediaries
- Polyglot Books has **no centralized server, proxy, or relay**.
- Network traffic is transmitted directly between the user's Android device and Google's official Gemini API endpoints (`generativelanguage.googleapis.com`) using TLS 1.3 / WSS.
- The repository and application include no third-party tracking, crash analytics, or telemetry SDKs.

### 2. Android Keystore & Cryptographic Storage
- User API keys are stored strictly on-device using **Google Tink AEAD** (`com.google.crypto.tink:tink-android`) backed by the hardware-backed **Android Keystore** (`AndroidKeysetManager`).
- Master keys are bound to the hardware security module (HSM) / StrongBox Keymaster where supported.
- Stored key ciphertext resides in an isolated private app DataStore (`io.github.danprav04.polyglotbooks_secure_prefs`), excluded from Android cloud backups (`android:fullBackupContent` and `dataExtractionRules`).
- Plaintext API keys exist only in process memory for the brief duration of network request signing or WebSocket connection headers and are never persisted in logs or unencrypted caches.

### 3. Local Cache Isolation
- Translated audio segments and cached TTS outputs are stored in app-private external/internal cache directories (`context.cacheDir` / `context.noBackupFilesDir`).
- Other applications cannot access these cache files without root privileges.

---

## Reporting a Vulnerability

We take the security and privacy of our users seriously. If you discover a security vulnerability in Polyglot Books, please report it responsibly:

1. **Do NOT open a public GitHub issue.**
2. Send an email to the maintainers or report via **GitHub Private Vulnerability Reporting** on the repository page:
   - Security Contact: `danprav04@users.noreply.github.com`
   - Repository: [https://github.com/danprav04/book-audiobook-translator/security/advisories/new](https://github.com/danprav04/book-audiobook-translator/security/advisories/new)
3. Please provide:
   - A detailed description of the vulnerability.
   - Exact steps or proof-of-concept (PoC) code to reproduce the issue.
   - Affected app version and Android OS version / device model.
   - Any suggested mitigations.

### Response Timeline
- **Initial Acknowledgement**: Within 48 hours.
- **Triage & Status Assessment**: Within 5 business days.
- **Fix & Public Disclosure**: Coordinated release following patch validation, typically within 30 days.
