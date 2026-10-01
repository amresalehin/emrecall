# EmRecall

Lightweight, local-first Android screen recall.

## What it does

- Captures the active application through Android AccessibilityService screenshot APIs.
- Skips locked/off-screen states and known system/keyboard overlays.
- Uses block-averaged visual change detection and always captures application switches.
- Saves screenshots as 720px-max WebP in private app storage by default.
- Stores metadata and OCR state in local SQLite with WAL and schema migration.
- Uses an asynchronous RecyclerView timeline with sampled thumbnail decoding and a bounded memory cache.
- Searches package names, application labels, and OCR text through SQLite FTS4.
- Runs OCR on demand using the Latin ML Kit recognizer.
- Supports single-item deletion, clear-all, pause/resume capture, optional biometric lock, secure app windows, and 7/30/90-day retention.
- Optional SAF folder selection; a selected folder is accessible to apps/services to which the user grants access.
- No account, cloud sync, embeddings, captions, or always-on AI.

## Privacy and Android requirements

- Android 11 / API 30 or newer.
- The user must explicitly enable the accessibility service.
- Android 13+ may require **Allow restricted settings** for a sideloaded accessibility service.
- Default exclusions cover Android system UI, the keyguard, common keyboards, password managers, authenticators, payment/banking keywords, Google Play services, and Settings. Users can add more package names.
- OCR uses ML Kit's Latin recognizer and depends on Google Play services; Bengali and other non-Latin scripts are not guaranteed.
- A user-selected SAF folder is outside EmRecall's private storage boundary and may be readable by apps/services the user has granted access to.

## Build

GitHub Actions builds a debug APK on pushes to `main` and on manual dispatch. The repository includes a persistent debug signing key so debug APK updates retain the same signature; this key is for development/sideloaded debug builds, not production distribution.

The debug workflow uses JDK 17, Gradle 8.9 and Android API 35.

## Current boundary

Semantic embeddings, AI captions, knowledge graphs, cloud sync, and continuous OCR are intentionally out of scope until the capture/search loop is proven stable on-device.