# ClearMovie architecture

This page describes the code that exists today. It grows as features land.

## Modules and packages

One Gradle module, `:app`, as in ClearSMS. Layers are packages under
`com.itsluminous.clearmovie`, and `PackageLayeringConventionTest` enforces
their imports.

| Package | Owns | May not import |
| --- | --- | --- |
| `domain.model` | The project and preset model (below), `ThemeMode`, `AppSettings`: pure Kotlin | `android.*`, `androidx.*`, or any other app layer |
| `data.settings` | `SettingsRepository` and its DataStore implementation | `ui` |
| `di` | Hilt modules (the settings DataStore, repository bindings) | |
| `ui.theme` | `ClearMovieTheme`, the branded fallback colour schemes, `MediaColors` | |
| `ui.navigation` | `ClearMovieApp`, the navigation graph | |
| `ui.home`, `ui.settings` | The screens; `AppLanguage` (the in-app language picker) | |

`ClearMovieApplication` (`@HiltAndroidApp`) and `MainActivity` sit at the root.

## Start-up

`MainActivity` is the only activity (`AppCompatActivity`, `singleTop`). It
installs the `core-splashscreen` splash (`Theme.ClearMovie.Starting`) and keeps
it on screen until `MainViewModel` has read the settings, so the content never
appears in the wrong theme first. It then draws edge to edge, with system
bar icons that follow the app's theme rather than only the phone's.

## Theme

The Theme override is applied app-wide through
`AppCompatDelegate.setDefaultNightMode` (in `ClearMovieApplication` at start
and on every change), so the window, `values-night` resources and dialogs
follow it too; only the Android 12+ cold-start splash, drawn before the app
runs, follows the phone's own setting.

`ClearMovieTheme(themeMode, dynamicColor)` uses Material You colours from the
wallpaper on Android 12+ when "Colours from wallpaper" is on, and the amber
fallback schemes otherwise (always on Android 10 and 11). `ThemeMode` is
System, Light or Dark. Both settings live in DataStore Preferences
(`settings.preferences_pb`). `MediaColors` holds fixed colours for media
surfaces that do not follow the palette; `MediaColorsContrastTest` checks
4.5:1 contrast for them and for the fallback schemes.

The launcher icon is an adaptive icon (`mipmap-anydpi/ic_launcher.xml`) with
background, foreground and monochrome layers, so Android 13+ themed icons work.

## Languages

Strings live in `values/` (English), `values-hi/` (Hindi) and
`values-b+hi+Latn/` (Hinglish); see [translating.md](translating.md).
`generateLocaleConfig` builds the system's per-app language list from those
folders. The in-app picker (`AppLanguage`) calls
`AppCompatDelegate.setApplicationLocales`: on Android 13+ the system's
LocaleManager stores the choice, and on Android 10-12 AppCompat stores it
(`AppLocalesMetadataHolderService` with `autoStoreLocales`). The picker's list
comes from `supported_language_tags`, each language named in its own script.

Lint makes `HardcodedText`, `MissingTranslation` and `ExtraTranslation` errors
(`app/lint.xml`). `UserVisibleLabelConventionTest` rejects literal text in
Compose code, and `StringKeyParityTest` checks that the three languages have
the same keys and placeholders, with no Devanagari in Hinglish.

## Project model

`domain.model` holds the video being edited as immutable data, with no
Android types, so all of it is tested on the JVM.

- **Rows.** A `Project` has a list of `Track`s in **screen order** and a
  separate `stacking` list, the **drawing order** of the picture rows (bottom
  first). Moving a row on screen never changes the video; Show on top, Bring
  forward, Send backward and Show at bottom change only `stacking`. Row types
  are MAIN (exactly one: the photos and videos that set the video's length,
  with the intro first and the ending last), VISUAL (picture in picture),
  TEXT, OVERLAY (frames) and AUDIO (music, voice or effect), as many of each
  as wanted. A new row goes on screen directly above the rows of its type, and
  into `stacking` by type: text on top, frames under the lowest text row,
  picture in picture just above MAIN.
- **Clips.** MAIN clips (`PhotoClip`, `VideoClip`, `CardClip`, `IntroClip`,
  `EndingClip`) have no start time: `Timeline` derives it from their order. Every
  other clip has a start time, and clips on one row never overlap.
  `MediaRef` points at a `content://` item, a file copied into the project's
  own folder (a checked relative path), or a preset slot.
- **Transitions** are stored on the leading MAIN clip and overlap the next, so
  each one shortens the video by its length; `Timeline.fit` keeps it between
  0.3 s and 2 s and at most half the shorter clip. **Apply to all** keeps every
  junction, new ones included, on one transition. **Animations** are stored per
  clip, by slot (start, end, during).
- **Edits** (`Edits.kt`) are pure functions such as `insertAt`, `addText`,
  `moveClip`, `splitClip`, `setTransition` and `bringForward`. Each returns a
  normalised project (`normalized()`): transitions and animations refitted,
  and the preset frame, its music and the closing message re-spanned to follow
  MAIN. Inserting on MAIN shifts later clips; on any other row the clip goes at
  the item's edge, or onto the highest free row of that type, or a new row,
  when it would overlap. The rows a preset manages (its frame, the frame's
  music, the closing message) hold only the preset's own media, so the
  user's items always get rows of their own. A row emptied by a delete or
  move is removed in the same step, except MAIN and the preset frame row;
  deleting the preset frame switches its row off and keeps the frame.
- **Undo.** `ProjectEditor` applies one edit per undo step (50 kept), and
  accepts a result only if `validate()` finds no broken invariant.
- **Presets.** `PresetParser` reads `preset.json` (schema 1) field by field,
  with size, nesting and range limits, because a user's own preset file is
  untrusted; `ProjectFactory.fromPreset` builds the default rows (Text, Frame,
  MAIN, Music, Closing message). The only preset in the repo is the generic
  `assets/presets/event-frame/preset.json`, named through the
  `preset_event_frame_name` string.
- **Storage format.** `ProjectCodec` writes canonical JSON with a
  `schemaVersion` and every value (defaults included, so a later change to a
  default never changes a saved project). On reading it works out stale
  derived values again (spans and fits, flagged as `repaired`) and refuses
  files that are too large, too deeply nested, from a newer app or otherwise
  invalid. `ProjectMigrations` upgrades older
  versions one step at a time (schema 1 is the first, so there are no steps
  yet). Golden files under `app/src/test/resources/fixtures/project/` must
  round-trip byte for byte.

## Persistence

DataStore Preferences holds the settings. Room is on the classpath (with KSP)
for the project database, which arrives with the storage milestone and will
store each project as `ProjectCodec` JSON.

## Build and release

See [building.md](building.md) and [release-signing.md](release-signing.md):
per-ABI APK splits with `versionCode = base × 10 + abiCode`, and the CI
workflow `.github/workflows/android.yml` with `check`, `instrumented`
(API 29 and 36 emulators) and `release` jobs, plus a tag-only `publish` job.
