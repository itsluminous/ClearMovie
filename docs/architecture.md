# ClearMovie architecture

This page describes the code that exists today. It grows as features land.

## Modules and packages

One Gradle module, `:app`, as in ClearSMS. Layers are packages under
`com.itsluminous.clearmovie`, and `PackageLayeringConventionTest` enforces
their imports.

| Package | Owns | May not import |
| --- | --- | --- |
| `domain.model` | The project and preset model (below), `ThemeMode`, `AppSettings`: pure Kotlin | `android.*`, `androidx.*`, or any other app layer |
| `render.plan` | `RenderPlanBuilder` and the `RenderPlan` it makes, and the preview's `PreviewSchedule`: pure Kotlin | `android.*`, `androidx.*`, Media3, or any other app layer but `domain` |
| `render.media3` | The Media3 mapping: `CompositionFactory`, the GL effects, the gain processor, `RenderExporter` | |
| `render.preview` | The layered editor preview (`LayeredPreviewEngine`, `StillLoader`) and the exact draft (`ExactDraft`) | |
| `data.settings` | `SettingsRepository` and its DataStore implementation | `ui` |
| `data.db` | The Room database `ClearMovieDatabase` (projects, session, preset bindings and definitions, persisted link grants) | `ui` |
| `data.project` | `ProjectRepository` (autosave, named save, recovery), `AutosaveGuard`, `NewVideos` (new drafts), `ProjectMedia` (a project's files) | `ui` |
| `data.preset` | `PresetStore`, the key-colour check, `LoudnessMeter`, `MediaProbe` | `ui` |
| `data.media` | `MediaAccess`, `MediaResolver`, `MediaCopier`, `CopyBudget`, `MediaPermissions`, persisted grants, `newMainItems` (picked items to clips) | `ui` |
| `data.files` | `AppFiles` (where files live), `SafeFileName`, `StorageUsage` | `ui` |
| `di` | Hilt modules (the settings DataStore, the database, the storage classes, the app scope) | |
| `ui.theme` | `ClearMovieTheme`, the branded fallback colour schemes, `MediaColors` | |
| `ui.navigation` | `ClearMovieApp` (the navigation graph), `NewVideoFlow` (which screen follows which), `Routes` | |
| `ui.layout` | `WindowClass`: Material window size classes from the space the screen has | |
| `ui.home`, `ui.preset`, `ui.pick`, `ui.editor`, `ui.settings` | The screens and their view models; `AppLanguage` (the in-app language picker) | |
| `ui.media`, `ui.components` | The photo-access explainer, the copy dialogs and `MediaIntake`, the missing-clip tile; progress and play-a-file dialogs | |

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
  music, the closing message) hold only the clip the preset put there, which
  moves only in time on its own row, so the user's items always get rows of
  their own. A row emptied by a delete or move is removed in the same step,
  except MAIN and the preset frame row; deleting the preset frame switches
  its row off and keeps the frame.
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
  derived values again (spans and fits, flagged as `repaired`), never moving,
  adding or dropping an item, and refuses files that are too large, too
  deeply nested, from a newer app or otherwise invalid. `ProjectMigrations` upgrades older
  versions one step at a time (schema 1 is the first, so there are no steps
  yet). Golden files under `app/src/test/resources/fixtures/project/` must
  round-trip byte for byte.

## Rendering

Rendering has two layers, both under `render`:

- **`render.plan`** (pure Kotlin, JVM-tested). `RenderPlanBuilder` turns a
  `Project` (and its `Preset`) into a `RenderPlan`: visual layers in
  `stacking` order, bottom first, and audio layers. Every layer covers the
  whole video, with transparent `Fill`s (or `Silence`) in its gaps, so an
  empty stretch of a row never hides the rows below it. MAIN becomes two
  lanes, A and B (B just above A), when it has a transition: the lane switches
  at each transition so the two clips overlap, and the clip in lane B carries
  an opacity ramp (a crossfade; other shapes come with the effects
  milestone). Photos and videos go into the frame's window while the preset
  frame row is on; picture in picture goes in its corner of the window. The
  frame repeats and its last copy is cut. A spoken ending shows the last item's
  last frame. Sound is planned per row with a `GainEnvelope` in each item's own
  time: volume, loudness normalisation, fades, an equal-power crossfade, and
  ducking computed from the timeline (music lowered under clip sound and under
  voice, everything under the closing message, clip sound and voice never
  lowered by music). `RenderPlan.slice` cuts a plan for a short exact draft.
- **`render.media3`** maps a plan to one Media3 `Composition` for Transformer
  (`CompositionFactory`, `RenderExporter`). Media3's compositor draws the
  first sequence on top, so layers are emitted top first, under one
  transparent full-length layer that clocks every export at the plan's frame
  rate. Every video and sound item is checked against its file's real length
  first, because a sequence item that comes out short moves every later item
  in that row. Every picture ends in `PlacementEffect`, which draws it onto a
  transparent canvas-sized frame (cover crop, fit with bars, fit over a
  blurred copy, picture in picture with a border, or a caption box) and undoes
  Android's premultiplied alpha for bitmaps; `ChromaKeyEffect` keys the
  frame's green; `TransitionEffect` applies the opacity ramp;
  `GainEnvelopeAudioProcessor` applies the sound envelope. Captions and cards
  are drawn as bitmaps by `CaptionBitmapRenderer` and kept in
  `noBackupFilesDir/render/` by a hash of their content, trimmed to 64 MB.
  `FileMediaResolver` and `UriGuard` let the renderer read only other apps'
  `content://` media and files inside the app's own folders. Building a
  composition reads files and draws bitmaps, so it runs off the main thread.
  A video's picture and sound are separate sequences, so each video clip opens
  a video and an audio decoder; trimmed sound can start up to one AAC frame
  (about 21 ms) after its picture.

Export colour does not trust the device. Media3 turns SDR video into RGB
with the GPU's `samplerExternalOES`, so the YUV-to-RGB matrix is the
driver's, and the encoder chooses its own RGB-to-YUV matrix. Both Android
emulators decode BT.709 video with the BT.601 matrix, and the Android 10
emulator's encoder writes BT.601 YUV under a BT.709 tag. `ColourCalibration`
therefore exports a 1 s, 1280x720 flat BT.709 clip once per build fingerprint
(cached in `noBackupFilesDir/render`) with a Standard export's encoder
settings: a GL readback gives the decode matrix and the output's raw YUV gives
the encoder's. The BT.601 tag is asked for only when an export opens the
encoder that was measured. High and Small exports reuse the 720p answer:
Android's size defaults for untagged video put 854x480, 1280x720 and
1920x1080 in the same BT.709 class, so an encoder or decoder that falls back
to them answers alike at all three sizes. On a GPU that misdecodes, every
BT.709 video item starts with `Bt709Correction`; on an encoder that writes
BT.601, the export asks for BT.601 so the file's tag matches its YUV. A
calibration that fails or cannot tell changes nothing. Colours whose BT.601
decode falls outside 0..255 (the most saturated ones) are clipped by the GPU
before any effect runs, so on a misdecoding GPU they stay off after the
correction.

The live editor preview does not play this composition: Media3's multi-input
`CompositionPlayer` (1.11.1) shows no frame after a seek and freezes when a
lower layer goes from a video to a photo. The editor therefore previews MAIN
in a plain player with the other rows drawn over it, and offers an exact draft
(a sliced plan, exported at a low height) on request.

### The layered preview

`PreviewSchedule.of(plan)` reads the same `RenderPlan` the export renders and
lays it out for players:

- **MAIN** becomes one ExoPlayer playlist and is the clock. A video is a
  clipped item; anything else (a photo, the held last frame of a spoken
  ending, a card) is a silent gap in the player, drawn by the UI over it.
  Where a transition overlaps two items the preview cuts to the incoming one;
  the exact draft shows the real blend.
- **Frame rows** each play in a muted ExoPlayer with the export's
  `ChromaKeyEffect` (plus `PremultiplyEffect`, since a TextureView blends
  premultiplied colour) on a non-opaque TextureView over MAIN, in stacking order.
- **Sound rows** (the frame's music, the closing message) each play in an
  audio-only player. Every player's volume follows the plan's gain envelopes,
  so the music ducks under clip sound as it will in the export.
- **Captions** are drawn as text in their boxes. Picture-in-picture rows come
  with cm-tracks; until then only the exact draft shows them.

`LayeredPreviewEngine` keeps the followers within 200 ms of MAIN. It is owned
by `EditorViewModel`, so a rotation keeps playing and only the surfaces are
attached again; the playhead and play state go into the saved state for a
process death. Two device facts shape it: a player with video effects shows
nothing after a seek until it plays, so a paused seek plays the frame player
until its first frame lands; and a TextureView that is never drawn stops taking
pictures and stalls its player, so a hidden layer is drawn almost transparent
rather than not at all.

## Screens and navigation

Home (preset pick, Start without a preset, My videos) leads to preset setup or
repair when a file is not ready (a missing file never crashes: it opens the
repair heading), then preset review (Play, Replace or Skip for this video for
the intro, frame and ending; the choices rebuild the new draft from the
track template), then the photo step (the one-time access explainer, Try
again after a refusal, the system Photo Picker, the intake), then the editor
shell (Export at the top right, the layered preview, the read-only MAIN row).
`NewVideoFlow` decides the order: with `EntryMode.MEDIA_CHOSEN` (shares, from
cm-share) the preset pick goes straight to the editor.

Layouts follow `WindowClass`: two panes for medium or expanded width, or
compact height. State survives rotation and process death: the back stack,
the review's choices, the picked links until they are on MAIN, whether the
picker was already opened (so it never opens again by itself), and the
editor's playhead. On a fresh start, `ProjectRepository.launchDecision`
reopens the editor when the app stopped with a video open in it.

## Persistence

**Projects.** `ClearMovieDatabase` (Room, `clearmovie.db`) keeps each project
as one row: the whole model as `ProjectCodec` JSON plus its id, name, preset
and times. Schemas are exported to `app/schemas/`; every version step needs a
migration in `ClearMovieDatabase.MIGRATIONS` and there is no destructive
fallback. A model schema bump (a new `ProjectMigrations` step) is applied when
a project is opened and the row is saved back; a row from a newer app, or one
that cannot be read, is never overwritten.

**Autosave.** The editor changes the open project through
`ProjectRepository.update`. Writes are debounced (500 ms, at most 2 s apart
while edits continue), each in one transaction, so a kill mid-write leaves
the previous row. `AutosaveGuard` writes on `ProcessLifecycleOwner` ON_STOP
(before any low-memory kill) and from the crash handler, which waits at most
1.5 s. A failed write (a full phone) keeps the edit and sets `saveFailed`.
A draft has no name; **Save** names it in place.

**Recovery.** The session row names the project open in the editor and when
the user last saw it there (opened, or the app went to the background). At
launch, a session left behind by a crash or a kill reopens the editor at that
project with no question; "We brought back your last video" shows only when
it has edits newer than that time. A session naming an unreadable project
starts at Home.

**Presets.** `PresetStore` offers the bundled definitions plus the user's own
`preset.json`, which may only adjust a preset the app ships; it is
size-limited, strict UTF-8, parsed by `PresetParser` and re-checked every time
it is read. Each slot's file (intro, frame, ending) is copied into
`filesDir/presets/<id>/` with its SHA-256, size, length and loudness
(`LoudnessMeter`, BS.1770). Launch checks are cheap (the file exists with its
size); before an export a file modified after it was bound is hashed again.
The frame is sampled at 10, 50 and 90 % of its length with the stream's own
YUV matrix (`YuvFrameDecoder`); if the window's green is more than Delta E 6
from the preset's key colour (a chat app re-encoded it), the binding keys out
the measured green instead.

**Gallery media.** `MediaAccess.plan` resolves each picked or shared link
(`MediaResolver`): a MediaStore item stays a link; a local Photo Picker id, a
media document (`MediaStore.getMediaUri`, Android 10-11) or another app's
file with the same name and size (and capture date when several match) is
replaced by its MediaStore link when the app has full access; otherwise a
link whose read grant can be persisted is kept with that grant (Android caps
these, so the app stops 16 short and copies instead, and gives a grant back
when no project uses it); anything else is copied while the grant lasts. The
copy budget asks before an intake copies more than 1 GB or leaves under 2 GB
free. `MediaCopier` writes `<name>.part`, syncs and renames, and a cancelled
or failed copy leaves no file. Names on disk come from `SafeFileName` (a
random prefix, an ASCII stem, an extension from the MIME type) and every path
is checked to stay in its folder. `MediaAccess.check` marks clips whose media
is gone (`MISSING`) or unreadable (`NO_ACCESS`).

**Files.** `filesDir/projects/<id>/media/` (copies), `filesDir/presets/<id>/`
(preset files), `cacheDir/thumbs/`. Leftover `.part` files and unreferenced
preset files older than an hour are removed at launch. Settings -> Storage
reads `StorageUsage`.

DataStore Preferences holds the settings, and whether the app has asked for
photo and video access (`MediaPermissions` turns that and Android's answers
into `MediaAccessState`).

## Build and release

See [building.md](building.md) and [release-signing.md](release-signing.md):
per-ABI APK splits with `versionCode = base × 10 + abiCode`, and the CI
workflow `.github/workflows/android.yml` with `check`, `instrumented`
(API 29 and 36 emulators) and `release` jobs, plus a tag-only `publish` job.
