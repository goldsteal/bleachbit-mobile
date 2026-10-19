# BleachBit Mobile

BleachBit Mobile is an Android port of [BleachBit](https://www.bleachbit.org/). It frees storage and clears privacy traces the user chooses. It works without root. Root unlocks app caches and system locations that Android's sandbox keeps closed.

Desktop BleachBit targets Windows and Linux. This project targets Android. What carries over is the cleaner model: a cleaner declares what it may remove, the app lists the matches, and nothing is deleted until the user confirms.

The build order, cleaner catalog, and safety rules are in [docs/plan.md](docs/plan.md).

## What it cleans

Without root:

- Items already in the system media trash
- Partial downloads
- The current clipboard contents
- A per-app report of cache and data size, with a link to the system screen that clears one app's cache
- Large files and duplicate files, deleted only when the user marks them

With root:

- Other apps' cache, without clearing their data, logins, or settings
- Leftover data directories of apps that are no longer installed
- System crash logs and the log buffer
- Browser cache, cookies, and history, each as its own expert-mode option

[Shizuku](https://shizuku.rikka.app/) sits between the two. It runs the app with the ADB shell user and no full root. It can ask Android to trim caches, and on some versions it can see external app-cache folders.

## How a clean works

1. The user chooses cleaners. The app selects none of them on its own.
2. A scan lists every file or action, with sizes.
3. The user removes individual rows if they want.
4. The user confirms.
5. The app removes what was confirmed and reports bytes freed, files skipped, and errors.

## Rules

- Cache and app data are different operations. A bulk clean never wipes logins, databases, or settings.
- The app does not invent junk, inflate sizes, or scan on first launch.
- File shredding and free-space wiping are out of scope. Flash storage, wear leveling, and file-based encryption make overwriting an unreliable wipe, and it wears the device.
- The app does not use an Accessibility Service to drive the Settings app.
- There is no network access. Cleaner definitions ship inside the app. Nothing is uploaded.

## Status

Phase 1 is in the tree. The `engine` module decides which deletes are allowed, builds a plan, and runs that plan only after a second check. The app opens on a home screen that shows the privilege as Standard. Scanning and cleaning are later phases.

## Build

Install a JDK that includes `javac`, and Android SDK platform 37. From this directory:

```
./gradlew :engine:test :app:assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. The application id is `dev.goldsteal.bleachbit`.

## License

Code will be released under GPL-3.0-or-later, the same license family as BleachBit.

This is an independent port, not an official BleachBit release. The name "BleachBit Mobile" is a working title. The application id will not be under `org.bleachbit` unless the BleachBit project agrees.
