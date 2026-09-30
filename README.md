# SnapMemory Lite

Lightweight, local-first Android screen recall.

## What it does

- Captures the active screen through Android AccessibilityService screenshot APIs.
- Probes each capture at 48×27 and saves only meaningful visual changes.
- Stores screenshots as WebP in app-private external storage.
- Stores capture metadata in local SQLite.
- Searches by package name and OCR text.
- Runs OCR only when manually requested.
- Supports 5/7/10/15/20 second capture intervals.
- Supports 7/30/90 day retention.
- Lets the user exclude sensitive app package names.
- No account, cloud sync, embeddings, captions, or always-on AI.

## Android requirements

- Android 11 / API 30 or newer.
- The user must explicitly enable the accessibility service in Android Settings.
- This build is intended for personal/sideloaded use unless its eventual distribution satisfies the current Android and Google Play accessibility-service requirements.

## Build

GitHub Actions builds a debug APK on every push and on manual dispatch.

The workflow uses JDK 17, Gradle 8.9, Android API 35, and Build Tools 35.0.0.

The generated APK is uploaded as the `SnapMemoryLite-debug` workflow artifact.

## Lightweight design

```
AccessibilityService
        ↓
Screenshot
        ↓
48×27 change probe
        ↓
meaningful change?
   no ──→ discard
   yes
        ↓
720px max WebP
        ↓
SQLite metadata
        ↓
optional on-demand OCR
```

The screenshot callback is delivered on a single worker executor, the HardwareBuffer is always closed, and capture processing never starts a second screenshot while one is still being processed.

## Current boundary

This is deliberately a small MVP. Semantic embeddings, AI captions, knowledge graphs, cloud sync, and continuous OCR are intentionally out of scope until the capture/search loop is proven stable on-device.
