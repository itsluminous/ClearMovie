# ClearMovie

[![Android CI](https://github.com/itsluminous/ClearMovie/actions/workflows/android.yml/badge.svg)](https://github.com/itsluminous/ClearMovie/actions/workflows/android.yml)
[![Latest release](https://img.shields.io/github/v/release/itsluminous/ClearMovie?sort=semver&include_prereleases)](https://github.com/itsluminous/ClearMovie/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/itsluminous/ClearMovie/total?logo=github&label=downloads&color=success)](https://github.com/itsluminous/ClearMovie/releases)
[![Min Android](https://img.shields.io/badge/Android-10%2B-3ddc84?logo=android&logoColor=white)](docs/building.md)
[![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7f52ff?logo=kotlin&logoColor=white)](docs/building.md)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![Stars](https://img.shields.io/github/stars/itsluminous/ClearMovie?style=flat&color=gold)](https://github.com/itsluminous/ClearMovie/stargazers)
[![Forks](https://img.shields.io/github/forks/itsluminous/ClearMovie?style=flat&color=blue)](https://github.com/itsluminous/ClearMovie/forks)

**ClearMovie** is a simple, offline video maker for Android, built for people who
just want to turn the photos and videos from an event into a finished video. Pick a
preset (an intro, a green-screen frame overlay that runs across the whole video, and
an ending), add your photos and videos, drop in a few captions, and export. Encoding
will use your phone's hardware, and everything stays on your device.

> **Status: in development.** There is no release yet. The feature list below is the
> plan, and items get ticked off as they ship.

<a href="https://github.com/itsluminous/ClearMovie/releases/latest">
  <img alt="Get it on GitHub" height="80"
       src="docs/badges/get-it-on-github.png" />
</a>
<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.itsluminous.ClearMovie%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fitsluminous%2FClearMovie%22%2C%22author%22%3A%22itsluminous%22%2C%22name%22%3A%22ClearMovie%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22arm64-v8a%5C%22%7D%22%7D">
  <img alt="Get it on Obtainium" height="80"
       src="docs/badges/get-it-on-obtainium.png" />
</a>
<!-- F-Droid badge goes here once the app is listed:
<a href="https://f-droid.org/packages/com.itsluminous.ClearMovie/">
  <img alt="Get it on F-Droid" height="80" src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" />
</a>
-->

Website: [itsluminous.github.io/ClearMovie](https://itsluminous.github.io/ClearMovie/)

### Which APK to download

Each release has one APK per processor type, plus a universal one:

| File | Use it for |
| --- | --- |
| `ClearMovie-<version>-arm64-v8a.apk` | **Almost every phone from the last several years. Choose this if you're unsure.** |
| `ClearMovie-<version>-armeabi-v7a.apk` | Older 32-bit phones |
| `ClearMovie-<version>-x86_64.apk`, `-x86.apk` | Emulators and a few tablets |
| `ClearMovie-<version>-universal.apk` | Any of the above (a larger download) |

Obtainium (the button above) picks the `arm64-v8a` APK automatically. ClearMovie needs Android 10 or newer.

## Screenshots

Screenshots arrive with the first release.

| Pick a preset | Editor (portrait) | Editor (landscape) | Captions | Export |
| :---: | :---: | :---: | :---: | :---: |
| _coming soon_ | _coming soon_ | _coming soon_ | _coming soon_ | _coming soon_ |

## Features

Planned for the first release:

- **Presets** - one tap will set up the intro, the frame overlay (with its green window
  keyed out so your photos and videos show through) and the ending, which can be a
  video or a closing voice clip. You can replace any part for a single video, or skip it.
- **Share from your gallery** - select photos and videos in the gallery, tap Share, and
  choose ClearMovie. They open on the timeline in the order they were taken.
- **A timeline that stays simple** - photos and videos on one row you reorder by dragging,
  plus as many text, music, picture-in-picture and frame rows as you want. Each row has an
  icon, rows can be rearranged, and you choose which one shows on top. Pinch to zoom, or
  use the zoom buttons. A "+" before and after each item adds something right there.
- **Big, labelled buttons** - tap any item for a toolbar of icons with names under them
  (trim, split, speed, loop, photo duration, volume, text, transition, animation, replace,
  delete). It scrolls sideways when there are more.
- **Captions** - start from the preset's position, font and size, then change any of
  them. Hindi (Devanagari) and English fonts are included.
- **Transitions and animations** - crossfades, fades, wipes, slides and zooms between
  items, and gentle zooms or fade-ins on single items, each with a live preview.
- **Background music and ducking** - the frame's music plays under the whole video and
  is lowered automatically while people talk in your videos, and everything is lowered
  for the closing voice.
- **Light and dark, with your colours** - follows your phone's dark mode, takes its
  colours from your wallpaper on Android 12+, and has a themed icon.
- **Portrait and landscape** - in landscape, the preview sits on the left and the timeline on the right.
  This also works on tablets, foldables and split-screen.
- **Autosave** - work is saved as you go and recovered after a crash. Name a video to keep it.
- **Hardware-accelerated export** - Best (1080p), Good (720p) or Small (480p), saved
  to your gallery and ready to play or share.

## Feature checklist

What's planned, and what has shipped:

**Making a video**
- [x] Preset picker, with "Start without a preset"
- [x] One-time preset setup (choose the intro, frame and ending files on your phone) with a friendly repair screen if a file goes missing
- [x] Preset review: replace or skip the intro, frame or ending for this video
- [x] Add photos and videos with the system photo picker
- [x] Share photos and videos from the gallery straight into a new video

**Editing**
- [ ] Drag-to-reorder main row, with Move left and Move right buttons as well
- [ ] Several rows of each kind (text, music, picture in picture, frame), with type icons, rearranging, and "Show on top" / "Send backward"
- [ ] "+" before and after each item to insert photos, videos, text cards or music
- [ ] Pinch to zoom the timeline, plus zoom buttons and Fit all
- [ ] Transitions between items and animations on items
- [ ] Trim, split, join, speed (0.25x-4x), loop
- [ ] Photo duration
- [ ] Fit: fill the frame window (default), fit with a blurred background, or fit with bars
- [ ] Audio overlay with volume, fade and automatic ducking
- [ ] Captions with preset defaults, drag-to-place, fonts, size, colours, outline and background
- [ ] Undo and redo for every change
- [ ] Preview that shrinks, or floats as a small window, when all rows need room

**Saving and export**
- [ ] Autosave and crash recovery
- [ ] Named projects in "My videos"
- [x] Export in Best, Good or Small quality with hardware encoding; keeps running if you rotate or leave the app
- [x] Saved to Movies/ClearMovie, then Play and Share buttons

**Everywhere**
- [ ] Portrait and landscape on every screen; tablets and foldables
- [x] Light and dark themes, wallpaper colours (Android 12+), themed icon
- [x] English, Hindi and Hinglish, with an in-app language picker

## Privacy Principles

- **Offline by design.** The app is planned with no INTERNET permission: no servers, no
  telemetry, no analytics, no ads.
- **Your media stays put.** Photos and videos are read from your gallery where they are.
  The only files copied into the app are your preset files, and items the gallery can't
  share permanently.
- **No proprietary dependencies.** It is built on AndroidX and Jetpack Media3, with no Firebase or Play Services.
- **No watermarks.**

## Building

Requirements, build and test commands, and how to run tests on an emulator are in
[docs/building.md](docs/building.md). How the code is laid out is in
[docs/architecture.md](docs/architecture.md), and release signing in
[docs/release-signing.md](docs/release-signing.md).

## Translations

ClearMovie launches in three languages. Untranslated strings fall back to English.

| Language | Code | Status |
| --- | --- | --- |
| English | `values` (default) | complete |
| Hindi (हिन्दी) | `hi` | complete |
| Hinglish | `b+hi+Latn` | complete |

How to add or improve a translation is in [docs/translating.md](docs/translating.md).

## License

[Apache License 2.0](LICENSE)
