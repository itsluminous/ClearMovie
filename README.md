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
uses your phone's hardware, and everything stays on your device.

> **Status: early releases.** What the newest version does and what to know is in the
> notes of the [latest release](https://github.com/itsluminous/ClearMovie/releases/latest).
> The checklist below shows what is done on `main` and what is still to come.

<a href="https://github.com/itsluminous/ClearMovie/releases/latest">
  <img alt="Get it on GitHub" height="80"
       src="docs/badges/get-it-on-github.png" />
</a>
<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.itsluminous.ClearMovie%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fitsluminous%2FClearMovie%22%2C%22author%22%3A%22itsluminous%22%2C%22name%22%3A%22ClearMovie%22%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22ClearMovie-%5B0-9.%5D%2B%28-arm64-v8a%29%2A%5C%5C%5C%5C.apk%24%5C%22%7D%22%7D">
  <img alt="Get it on Obtainium" height="80"
       src="docs/badges/get-it-on-obtainium.png" />
</a>
<!-- F-Droid badge goes here once the app is listed:
<a href="https://f-droid.org/packages/com.itsluminous.ClearMovie/">
  <img alt="Get it on F-Droid" height="80" src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" />
</a>
-->

Website: [itsluminous.github.io/ClearMovie](https://itsluminous.github.io/ClearMovie/)

### Download

[Download the latest release](https://github.com/itsluminous/ClearMovie/releases/latest)
and open the APK on your phone to install. Each release is a single
`ClearMovie-<version>.apk` that works on every phone; the first release alone had
one file per processor type.
Obtainium (the button above) installs it and keeps it up to date. If your Obtainium
entry for ClearMovie has the APK filter `arm64-v8a` (what the button used to set),
edit it in Obtainium and clear the filter, because the single APK will not match it.
ClearMovie needs Android 10 or newer.

## Screenshots

ClearMovie from `main` on an Android 16 emulator, making a short video from three
clips with a preset called Spasht Dhaara and its caption.

| Pick a preset | Check the preset | Add photos and videos | Arrange the timeline |
| :---: | :---: | :---: | :---: |
| ![Home screen: the Spasht Dhaara preset with an Edit button, Start without a preset, New preset, and the video under My videos](site/screenshots/home.webp) | ![What this preset adds: the intro and frame (the ending is further down), each with Play, Replace and Skip](site/screenshots/preset-review.webp) | ![The Android photo picker with three videos selected](site/screenshots/media-pick.webp) | ![The editor: back, title, undo and redo and More options at the top, the caption on the frame's blue strip, Play and Export under the preview, and every row of the timeline with a + at its right end](site/screenshots/editor.webp) |

| Add a caption | Choose a transition | Choose a size | Share it |
| :---: | :---: | :---: | :---: |
| ![Caption editor, Font tab with English and Hindi letters, and handles on the caption's box](site/screenshots/caption.webp) | ![Transition picker with previews, Wipe Left chosen](site/screenshots/transitions.webp) | ![Save your video: Best, Good or Small](site/screenshots/export.webp) | ![Your video is ready, with Share and Play](site/screenshots/export-done.webp) |

| Make a preset | Set up the frame | Place the picture | Edit a preset |
| :---: | :---: | :---: | :---: |
| ![New preset, scrolled down: the kind of ending, the video shape, Keep a copy of the files, and Create](site/screenshots/preset-create.webp) | ![Set up this frame, Screen colour tab: the green screen tapped and removed, with Colour chosen](site/screenshots/frame-setup.webp) | ![Set up this frame, Picture area tab: a box with handles shows where photos and videos go](site/screenshots/frame-area.webp) | ![Edit preset, scrolled down: a preview of its caption, its files and Save](site/screenshots/preset-edit.webp) |

| Set its caption style | The caption, selected | Trim on the timeline | Lower for talk |
| :---: | :---: | :---: | :---: |
| ![Change caption style, Animation tab: None, Fade in, Zoom in, Pop, Bounce or Slide in, with Fade in chosen](site/screenshots/preset-caption-style.webp) | ![The caption selected in the editor, with handles on its box and Edit text, Animation, Earlier, Later, Other row and Delete below](site/screenshots/editor-caption.webp) | ![A video selected on the timeline with a handle at each end to trim it and a + before and after it](site/screenshots/item-handles.webp) | ![Lower for talk: lower the music when people talk, to 10%](site/screenshots/talk-level.webp) |

| Record voice | Choose a shape | More options | Settings |
| :---: | :---: | :---: | :---: |
| ![Add to a voice row: Music or sound, or Record voice while the video plays](site/screenshots/record-voice.webp) | ![What shape should your video be? Landscape, Portrait or Square](site/screenshots/shape-first.webp) | ![The editor's More options menu: Rename video, Video shape and Settings](site/screenshots/editor-menu.webp) | ![Settings, scrolled to the end: Colours from wallpaper, Language, Storage, and the version with Open ClearMovie on GitHub](site/screenshots/settings.webp) |

<table>
  <tr><th>Dark theme</th><th>In Hindi</th><th colspan="2">Sideways too</th></tr>
  <tr>
    <td width="25%"><img src="site/screenshots/editor-dark.webp" alt="The editor in the dark theme"></td>
    <td width="25%"><img src="site/screenshots/editor-hindi.webp" alt="The editor in Hindi"></td>
    <td colspan="2" width="50%"><img src="site/screenshots/editor-landscape.webp" alt="The editor held sideways: preview on the left, timeline on the right"></td>
  </tr>
</table>

## Features

What ClearMovie on `main` does:

- **Presets** - one tap sets up the intro, the frame overlay (with its green window
  keyed out so your photos and videos show through) and the ending, which can be a
  video or a closing voice clip. You can replace any part for a single video, or skip it.
- **Your own presets** - make a new preset from Home, and edit or delete any preset there.
  A new install starts with none: Home shows your presets, then Start without a preset and
  New preset. An install that had the preset older versions came with keeps it like any other. A new
  preset can use your files where they are, or keep a copy of them. Each
  preset keeps a caption style (words, place, size, font, colour, outline, band and the
  animation it comes in with) that new captions start from, with a live preview.
- **Set up the frame** - tap the frame's screen colour (like a green screen) to remove it, and
  drag the picture area to where your photos and videos should show. A frame item on the
  timeline has Colour and Area buttons to change them for one video.
- **Video shapes** - landscape, portrait or square. A preset has its shape; without a preset,
  ClearMovie asks once, and More options > Video shape changes it later.
- **Share from your gallery** - select photos and videos in the gallery, tap Share, and
  choose ClearMovie. They open on the timeline in the order they were taken.
- **A timeline that stays simple** - photos and videos on one row you reorder by dragging,
  plus as many text, music, voice, picture-in-picture and frame rows as you want. Each row has an
  icon, rows can be rearranged, and you choose which one shows on top. Pinch to zoom, or
  use the zoom buttons. Each row, the frame row too, has a "+" at its right end to add to it.
  A selected photo or video has a "+" before and after it to add something right there, and
  a selected item has a handle at each end to trim it.
- **Big, labelled buttons** - tap any item for a toolbar of icons with names under them
  (trim, split, speed, loop, photo duration, volume, text, transition, animation, replace,
  delete). It scrolls sideways when there are more.
- **Captions** - start from the preset's position, font and size, then change any of
  them. Drag a caption's box to move it, or a handle on its edge to make it wider or
  narrower; the words wrap again at their size. Hindi (Devanagari) and English fonts are
  included.
- **Transitions and animations** - crossfades, fades, wipes, slides and zooms between
  items, and gentle zooms or fade-ins on single items, each with a live preview.
- **Background music and ducking** - the frame's music plays under the whole video and
  is lowered automatically while your videos play with their own sound, and everything
  is lowered for the closing voice. Lower for talk sets how low the music goes.
- **Record voice** - add a voice row, tap its "+" and choose Record voice to speak while the
  video plays, lined up with the picture.
- **Light and dark, with your colours** - follows your phone's dark mode, takes its
  colours from your wallpaper on Android 12+, and has a themed icon.
- **Portrait and landscape** - in the editor, landscape puts the preview on the left and the
  timeline on the right. The home and preset screens use the extra width too. A wide window
  on a tablet, foldable or in split-screen gets the same layout.
- **Autosave** - work is saved as you go and recovered after a crash. Your videos are
  listed under "My videos"; tap a video's title in the editor to give it a name.
- **Hardware-accelerated export** - tap Export in the play bar under the preview, then choose Best
  (1080p), Good (720p) or Small (480p). The video is saved to your gallery, ready to play
  or share.

## Feature checklist

What has shipped, and what is still to come:

**Making a video**
- [x] Preset picker, with "Start without a preset"
- [x] One-time preset setup (choose the intro, frame and ending files on your phone) with a friendly repair screen if a file goes missing
- [x] Preset review: replace or skip the intro, frame or ending for this video
- [x] Make your own presets, edit and delete any preset, each with its own caption style
- [x] Set up a frame: tap its screen colour to remove it and place the picture area
- [x] Video shapes: landscape, portrait or square, per preset or asked once without one
- [x] Presets can use your files where they are, or keep a copy
- [x] Add photos and videos with the system photo picker
- [x] Share photos and videos from the gallery straight into a new video

**Editing**
- [x] Drag-to-reorder main row, with Move left and Move right buttons as well
- [x] Several rows of each kind (text, music, picture in picture, frame), with type icons, rearranging, and "Show on top" / "Send backward"
- [x] "+" at the end of each row, and before and after a selected photo or video, to insert photos, videos, text cards or music
- [x] Pinch to zoom the timeline, plus zoom buttons and Fit all
- [x] Transitions between items and animations on items
- [x] Trim, split, join, speed (0.25x-4x), loop
- [x] Photo duration
- [x] Fit: fill the frame window (default), fit with a blurred background, or fit with bars
- [x] Audio overlay with volume, fade and automatic ducking
- [x] Lower for talk: choose how low the music goes while people talk
- [x] Trim handles at both ends of a selected item
- [x] Record voice: speak over the video as it plays, lined up with the picture
- [x] Captions with preset defaults, drag-to-place, drag-to-resize, fonts, size, colours, outline and background
- [x] Undo and redo for every change
- [x] Preview that shrinks, or floats as a small window, when all rows need room

**Saving and export**
- [x] Autosave and crash recovery
- [x] Named projects in "My videos": tap the title in the editor, or More options > Rename video
- [x] Export at Best, Good or Small size with hardware encoding; keeps running if you rotate or leave the app
- [x] Saved to Movies/ClearMovie, then Play and Share buttons

**Everywhere**
- [ ] Portrait and landscape on every screen; tablets and foldables
- [x] Light and dark themes, wallpaper colours (Android 12+), themed icon
- [x] English, Hindi and Hinglish, with an in-app language picker

## Privacy Principles

- **Offline by design.** The app has no INTERNET permission: no servers, no
  telemetry, no analytics, no ads.
- **Your media stays put.** Photos and videos are read from your gallery where they are.
  The only files copied into the app are preset files you chose to keep a copy of, and
  items the gallery can't share permanently.
- **The microphone only when you record.** It is asked for the first time you tap
  Record voice, used only while the recording panel shows, and recordings stay
  inside the app.
- **No proprietary dependencies.** It is built on AndroidX and Jetpack Media3, with no Firebase or Play Services.
- **No watermarks.**

## Building

Requirements, build and test commands, and how to run tests on an emulator are in
[docs/building.md](docs/building.md). How the code is laid out is in
[docs/architecture.md](docs/architecture.md), and release signing in
[docs/release-signing.md](docs/release-signing.md).

## Fonts

Captions use four bundled font pairs, so English, Hindi and Hinglish look of a
piece and Hindi conjuncts and matras are drawn properly in the preview and in
the video. All are from the [Noto project](https://notofonts.github.io/) and
licensed under the [SIL Open Font License 1.1](https://openfontlicense.org/);
the licence texts ship in the app (`app/src/main/assets/licenses/`).

| In the app | Latin | Devanagari |
| --- | --- | --- |
| Plain | Noto Sans Regular | Noto Sans Devanagari Regular |
| Bold | Noto Sans Bold | Noto Sans Devanagari Bold |
| Classic | Noto Serif Regular | Noto Serif Devanagari Regular |
| Classic bold | Noto Serif Bold | Noto Serif Devanagari Bold |

The files are the unhinted static TTFs from the notofonts releases
[NotoSans-v2.015 and NotoSerif-v2.015](https://github.com/notofonts/latin-greek-cyrillic/releases),
[NotoSansDevanagari-v2.007](https://github.com/notofonts/devanagari/releases/tag/NotoSansDevanagari-v2.007) and
[NotoSerifDevanagari-v2.006](https://github.com/notofonts/devanagari/releases/tag/NotoSerifDevanagari-v2.006),
renamed to Android resource names in `app/src/main/res/font/`.
Copyright 2022 The Noto Project Authors.

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
