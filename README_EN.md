# Where Did I Put It (FindIt)

![Build](https://github.com/dsglenn35-netizen/FindIt/actions/workflows/build.yml/badge.svg)
![License](https://img.shields.io/github/license/dsglenn35-netizen/FindIt)
![Release](https://img.shields.io/github/v/release/dsglenn35-netizen/FindIt)
![Stars](https://img.shields.io/github/stars/dsglenn35-netizen/FindIt)

<p align="center">
  <a href="https://github.com/dsglenn35-netizen/FindIt/releases"><strong>⬇️ Download the latest APK (GitHub Releases)</strong></a>
</p>

[中文](README.md)

An Android utility for remembering where you put small household items. Fully offline — no network required, no permissions needed, and all data stays on your phone.

<p align="center">
  <img src="app.jpg" alt="FindIt screenshot" width="320" />
</p>

## Features

- 📦 **Record location**: enter "item name + storage location" and save in one tap
- 📷 **Photo record**: snap a photo of the item; tap the thumbnail to view it fullscreen
- 🎤 **Voice input**: speak the item name/location and it fills in automatically (uses the system speech recognizer, no permission needed)
- 🔍 **Pinyin / fuzzy search**: typing `jd` or `jiandao` finds 剪刀 (scissors)
- 🕘 **Recent records**: sorted by most recently stored
- 🏠 **Group by location**: switch to the "By Location" tab to browse room by room
- 📊 **Statistics chart**: see how many items are in each place at a glance
- ✏️ **Edit / move history**: tap a record to edit its name, location, or photo; location changes are tracked automatically (where it was → where it is now)
- 🔗 **Share**: send "where the thing is" to family in one tap
- 💾 **Backup export/import**: pack records + photos into a zip, save to chat apps or cloud drives; export before switching phones or uninstalling, restore anytime
- ⚙️ **Custom quick locations**: long-press a location chip to rename/delete; tap "+ Add" to create your own
- 🌙 **Dark mode**: follows the system automatically

## Installation (Android)

1. Transfer `app-release.apk` to your phone (WeChat/QQ, USB cable, cloud drive, etc.)
2. Tap the file on your phone to install
3. If you're prompted about "unknown sources", allow installing from this source in Settings (the path varies by device brand)

Requires **Android 8.0 (API 26) or above**.

## Data & Privacy

- All data lives in the app's private directory: database `findit.db` + photos `files/photos/` — **no permissions required, no network access, no uploads**
- To upgrade, just install the new APK over the old one — your data is preserved
- ⚠️ Uninstalling the app clears all data; **export a backup zip regularly** to chat apps or cloud drives

## For Developers

- Package: `com.home.findit`
- Build environment: Gradle 8.9 + AGP 8.7.3 + Kotlin 2.0.21 + compileSdk 35 / minSdk 26 / targetSdk 35
- Dependencies: `androidx.core:core-ktx` (FileProvider), `pinyin4j` (pinyin search)

### Build from the command line

```bat
set JAVA_HOME=<your JDK 17 path>
gradlew.bat assembleRelease
```

Output: `app\build\outputs\apk\release\app-release.apk`

### Open in Android Studio

Just `Open` this directory. The SDK path lives in `local.properties` (not committed — create it yourself or let Studio generate it).

### Signing (important)

- The signing config is read from the local `keystore.properties` file; **both this file and the keystore `findit.jks` are excluded by .gitignore and never committed**
- Without `keystore.properties`, release builds automatically fall back to debug signing — it compiles and installs fine, but **cannot be installed over a properly signed release**
- To publish a release build, create `keystore.properties` in the repo root:

```properties
storeFile=../findit.jks
storePassword=your-password
keyAlias=findit
keyPassword=your-password
```

- ⚠️ Keep `findit.jks` and its password in a safe place — future updates must be signed with the same key, or users won't be able to upgrade over the old version

## Backup File Format

The zip contains `findit.json` (schema=1, with locations/items/moves) plus a `photos/` directory, viewable with any unzip tool.

## CI / Releases

- Every push to `main` triggers GitHub Actions to build the APK automatically (see the badge at the top)
- Tagging a release (e.g. `git tag v1.2 && git push origin v1.2`) auto-builds a properly signed APK and publishes it to [GitHub Releases](https://github.com/dsglenn35-netizen/FindIt/releases)

## License

MIT License — free to use, modify, and use commercially. Stars / Issues / PRs are welcome.
