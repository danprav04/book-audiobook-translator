# Privacy Policy

**Effective Date:** November 12, 2026  
**Application:** Polyglot Books (`io.github.danprav04.polyglotbooks`)

Polyglot Books is committed to maximum user privacy and full transparency. This policy describes how data is handled when you use the app.

---

## 1. Zero Tracking & No Analytics
- Polyglot Books contains **zero analytics SDKs**, **zero advertising libraries**, and **zero telemetry or crash-reporting services** (no Firebase, no Google Play Services dependencies, no Mixpanel, no Sentry).
- The application does not collect your identity, name, email, IP address, device identifier (IMEI/Android ID), location, or reading habits.
- There is **no app backend or intermediary server**. All communication occurs strictly peer-to-peer between your device and the cloud AI APIs you configure.

---

## 2. Bring-Your-Own-Key (BYOK) Architecture
- To use cloud AI features (such as live audiobook translation or multi-voice ebook narration), you provide your own **Google Gemini API Key** obtained from [Google AI Studio](https://aistudio.google.com/).
- **On-Device Encryption:** Your API key is encrypted on your device using Google Tink AEAD and the hardware-backed Android Keystore. The key never leaves your device except in TLS 1.3 encrypted HTTPS / WSS requests sent directly to Google's official API host:
  `generativelanguage.googleapis.com`
- You can test, change, or permanently delete your key at any time from the in-app **Settings** screen.

---

## 3. Important AI Data Handling Disclosure: Google Free vs. Paid Tier

Because Polyglot Books communicates directly with Google's Gemini API using your personal API key, Google's Terms of Service and data retention policies apply to requests made by the app. Please be aware of the difference between Gemini API account tiers:

### Free of Charge Tier (Default for New Google AI Studio Keys)
- According to the [Google Gemini API Additional Terms of Service](https://ai.google.dev/gemini-api/terms):
  > *"When you use the Unpaid Services, Google may use your content (prompts, audio, and responses) to train and improve Google products and services."*
- Human reviewers may access and annotate your submitted audio chunks and book excerpts.
- **Notice:** If you use a Free Tier API key, do not translate or narrate sensitive, confidential, or proprietary reading material.

### Paid Tier (Google Cloud Project with Linked Billing)
- When you enable billing in your Google Cloud Project or Google AI Studio:
  > Google commits that your input prompts, uploaded audio, and generated outputs **are not used to train Google models**. Data is processed solely to fulfill your API requests.
- **Recommendation:** If you desire confidential reading and strict enterprise privacy, configure a Paid Tier API key in Google AI Studio.

---

## 4. Local Data & Storage
- **Books and Audiobooks:** All imported files remain in your local device storage. Polyglot Books accesses them via Android's Storage Access Framework (SAF) only with your explicit permission.
- **Translated Audio Cache:** To prevent redundant network calls and save API costs, live-translated audio segments are cached locally in your device's private cache directory.
- **Cache Management:** You can set a maximum cache size quota (e.g., 500 MB to 10 GB) or clear the cache completely at any time from Settings.
- **Cloud Backup:** Android Auto-Backup is configured to explicitly **exclude** your encrypted API key and translation cache.

---

## 5. Offline Fallback
- Ebook reading via the embedded foliate-js reader works completely offline.
- Offline narration is supported using Android's built-in **System TTS** engine without requiring any internet access or API keys.

---

## 6. Permissions Requested
- `android.permission.INTERNET`: Required to communicate with `generativelanguage.googleapis.com` for translation and narration.
- `android.permission.FOREGROUND_SERVICE` & `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK`: Required to keep audiobook playback and live translation running smoothly in the background and through notifications.
- `android.permission.POST_NOTIFICATIONS`: Required on Android 13+ to display media player controls in the notification drawer.

---

## 7. Contact & Issue Reporting
For questions regarding this policy or to report concerns, please visit the open source repository:  
[https://github.com/danprav04/book-audiobook-translator](https://github.com/danprav04/book-audiobook-translator)  
Or open an issue using the [AI Output Report Template](https://github.com/danprav04/book-audiobook-translator/issues/new?template=ai_output_report.yml).
