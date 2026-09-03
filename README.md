# Coin Gallery for NGC and PCGS slabs

An independent Android coin-collection app for scanning certified NGC and PCGS slabs, keeping a local gallery, and opening the corresponding certification pages.

> **Unofficial project:** This project is not affiliated with, endorsed by, or sponsored by Numismatic Guaranty Company (NGC), Professional Coin Grading Service (PCGS), or their respective parent companies. NGC, PCGS, TrueView, and other third-party names, marks, website content, and images belong to their respective owners.

## Current features

- Live CameraX + ML Kit barcode scanning with repeated-read confirmation.
- ZXing/multi-pass scanning for imported photos.
- NGC barcode parsing and PCGS certificate detection.
- Manual full-barcode, PCGS certificate, and NGC certificate + grade entry.
- NGC/PCGS certification-page links.
- Runtime retrieval of available certification-site images and metadata.
- PCGS combined images can be split into front/back views while retaining the original image.
- Manual photos can be attached; site-downloaded images are protected from manual deletion.
- OBV/REV gallery switching, Mine flag, trash/restore, persistent gallery, and image zoom/pan.
- Newly added coins scroll into view and are temporarily highlighted.

## Privacy / local data

This repository does **not** include a user's coin database, downloaded NGC/PCGS coin images, Android signing keys, API tokens, or built APKs. Runtime coin data and downloaded images are stored locally by the app.

## Third-party website access

The app currently accesses public NGC/PCGS web resources at runtime. Website behavior, anti-automation controls, URLs, and terms may change. Anyone distributing a build is responsible for reviewing and complying with applicable NGC/PCGS terms, trademark rules, image/content rights, and app-store policies.

## Build

Requirements: JDK 17, Android SDK 34, Android API 24+.

```bash
./gradlew clean
./gradlew assembleDebug
```

Install on an authorized USB-connected device:

```bash
adb -d install -r app/build/outputs/apk/debug/app-debug.apk
```

## Repository safety

Do not commit signing keys, `local.properties`, credentials/API tokens, downloaded certification images, local databases, APKs, or AABs. The included `.gitignore` excludes these common files.

## License

No open-source license has been granted yet. Making source visible on GitHub does not itself grant broad permission to copy, redistribute, or create derivative works beyond rights provided by GitHub's terms and applicable law.

If you want this project to be open source, add an explicit license such as MIT, Apache-2.0, or GPL after deciding what permissions you want to grant.
