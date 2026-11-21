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
| `domain.record` | `RecordingSync`: lining a voice recording up with the picture: pure Kotlin | `android.*`, `androidx.*`, or any other app layer |
| `domain.export` | The export's rules: the three sizes (`ExportQuality`), the free-space rule, the gallery file name, the time left: pure Kotlin | `android.*`, `androidx.*`, or any other app layer |
| `render.media3` | The Media3 mapping: `CompositionFactory`, the GL effects, the gain processor, `RenderExporter`, `VideoExport` (hardware first, then software) | |
| `render.preview` | The layered editor preview (`LayeredPreviewEngine`, `StillLoader`) and the exact draft (`ExactDraft`) | |
| `data.settings` | `SettingsRepository` and its DataStore implementation | `ui` |
| `data.db` | The Room database `ClearMovieDatabase` (projects, session, preset bindings and definitions, persisted link grants) | `ui` |
| `data.project` | `ProjectRepository` (autosave, named save, recovery), `AutosaveGuard`, `NewVideos` (new drafts), `ShareDrafts` (a share's pending draft), `ProjectMedia` (a project's files) | `ui` |
| `data.preset` | `PresetStore`, the key-colour check, `LoudnessMeter`, `MediaProbe` | `ui` |
| `data.media` | `MediaAccess`, `MediaResolver`, `MediaCopier`, `CopyBudget`, `MediaPermissions`, persisted grants, `newMainItems` (picked items to clips); for shares `ShareLinks`, `ShareTypes`, `CaptureOrder` and `CaptureDates`, `PendingShare` | `ui` |
| `data.record` | `VoiceRecorder` (microphone to AAC in M4A), `MicSource` and `AudioRecordMic`, `VoiceRecordings` (the files, and removing unused ones) | `ui` |
| `data.files` | `AppFiles` (where files live), `SafeFileName`, `StorageUsage` | `ui` |
| `data.export` | `ExportStore`: the export's hidden-then-shown entry in `Movies/ClearMovie` | `ui` |
| `work` | `ExportWorker` (the foreground worker), `ExportPipeline` (what one export does), its notifications and Stop receiver | |
| `di` | Hilt modules (the settings DataStore, the database, the storage classes, the app scope) | |
| `ui.theme` | `ClearMovieTheme`, the branded fallback colour schemes, `MediaColors` | |
| `ui.navigation` | `ClearMovieApp` (the navigation graph), `NewVideoFlow` (which screen follows which), `Routes` | |
| `ui.layout` | `WindowClass`: Material window size classes from the space the screen has | |
| `ui.home`, `ui.preset`, `ui.pick`, `ui.share`, `ui.editor`, `ui.export`, `ui.settings` | The screens and their view models; `AppLanguage` (the in-app language picker) | |
| `ui.editor.timeline` | The editor timeline (`TimelineView`) and the pure pieces it runs on: `TimelineZoom`, `TimelineGeometry`, `TimelineGestureArbiter`, `PreviewLayoutReducer`, `ClipActions`, `TrimMath`, `ThumbPlan` | |
| `ui.media`, `ui.components` | The photo-access explainer, the copy dialogs and `MediaIntake`, the missing-clip tile; progress and play-a-file dialogs | |

`ClearMovieApplication` (`@HiltAndroidApp`), `MainActivity` and the share entry `ShareActivity` sit at the root.

## Start-up

`MainActivity` is the only activity with a screen (`AppCompatActivity`, `singleTask`, so there is only ever one, in ClearMovie's own task). It
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
- **Captions** (`Attachments.kt`). "Add caption" on a photo or video makes
  a caption attached to it (`TextClip.attachedTo`): normalising places it
  where its item plays, between the middles of the transitions into and out
  of it, so it moves when MAIN is reordered or a picture-in-picture item is
  moved, stays inside the item when it is trimmed or sped up, and a caption
  that lands on another goes to a free text row. Deleting the item (or its
  row) deletes its captions in the same step; split copies a whole-item
  caption onto both parts, join merges them, duplicate copies them. Placed
  by hand inside its item, a caption keeps an offset; placed outside, it is
  free. Captions made on a text row ("+", "Add here") are free and keep
  their times. Dragging a whole caption's handle detaches only that side
  (`CaptionAttachment.detachedStartMs` / `detachedEndMs`): it keeps its own
  time while the other side follows the item, and follows it again when let
  go exactly on the item's edge. Both detached, the caption is free. While
  one side is detached, Match item gives it both sides back.
- **Handles** (`Resize.kt`). Every item off MAIN can be resized by side:
  `resizeRange` gives each handle's limits (neighbours on its row, the
  video's start and end, 0.5 s or 0.3 s for video and sound, and its file
  for media that does not repeat) and `resizeClip` applies one. The preset's
  frame, its music and the closing message keep following the video on the
  side that was not dragged (`detachedStartMs` / `detachedEndMs` on the clip,
  next to its `anchor`).
- **Lower for talk.** A music row's `lowerToDb` is the level its music goes
  down to under voices, videos' own sound and the closing message; without
  one the project's `DuckingSpec` levels apply (-20 dB under a voice or the
  closing message, -12 dB under a video). A style stores only what differs from the preset's
  (`CaptionStyleOverride.between`), so Reset to preset clears it.
- **Undo.** `ProjectEditor` applies one edit per undo step (50 kept), and
  accepts a result only if `validate()` finds no broken invariant.
- **Presets.** `PresetParser` reads `preset.json` (schema 1) field by field,
  with size, nesting and range limits, because a user's own preset file is
  untrusted; `ProjectFactory.fromPreset` builds the default rows (Text, Frame,
  MAIN, Music, Closing message). The only preset in the repo is the generic
  `assets/presets/event-frame/preset.json`, named through the
  `preset_event_frame_name` string. It is not offered on its own: it is the
  template a new preset starts from (see Presets under Storage).
- **Storage format.** `ProjectCodec` writes canonical JSON with a
  `schemaVersion` and every value (defaults included, so a later change to a
  default never changes a saved project). On reading it works out stale
  derived values again (spans and fits, flagged as `repaired`), never moving,
  adding or dropping an item, and refuses files that are too large, too
  deeply nested, from a newer app or otherwise invalid. `ProjectMigrations` upgrades older
  versions one step at a time (schema 2 added `attachedTo`; the 1 to 2
  step rewrites nothing, so a schema 1 caption reads as a free one; schema
  4 added detached sides and `lowerToDb`, and its step rewrites nothing
  either). Golden files under `app/src/test/resources/fixtures/project/` must
  round-trip byte for byte.

## Rendering

Rendering has two layers, both under `render`:

- **`render.plan`** (pure Kotlin, JVM-tested). `RenderPlanBuilder` turns a
  `Project` (and its `Preset`) into a `RenderPlan`: visual layers in
  `stacking` order, bottom first, and audio layers. Every layer covers the
  whole video, with transparent `Fill`s (or `Silence`) in its gaps, so an
  empty stretch of a row never hides the rows below it. MAIN becomes two
  lanes, A and B (B just above A), when it has a transition: the lane switches
  at each transition so the two clips overlap. Each picture carries a
  `PictureMotion`: its start, end and during animations and its part in the
  transitions into and out of it (lane B, drawn over A, carries the fade or the
  mask; both lanes move). `MotionMath` holds the curves (crossfade, fade
  through black or white, wipes, slides, circle, zoom; fade, slide and zoom
  in and out, gentle zoom (Ken Burns) on photos, pop, bounce and pulse on
  text) and is the one source the export shader, the preview and the
  picker's moving tiles draw. Photos and videos go into the frame's window while the preset
  frame row is on; picture in picture goes in its corner of the window. The
  frame repeats and its last copy is cut. A spoken ending shows the last item's
  last frame. Sound is planned per row with a `GainEnvelope` in each item's own
  time: volume, loudness normalisation, fades, an equal-power crossfade (a
  dip to silence for a fade through black or white), and
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
  frame's green; `MotionEffect` draws each frame's `MotionMath` result
  (scale, move, fade, tint, wipe or circle mask; GLSL ES 1.0, so it runs on
  SwiftShader as on a phone). Every effect leaves texture unit 0 active after
  it draws (`SinglePassShaderProgram`): all layers share one GL context, and
  while placements left unit 1 active, exports on the emulator's SwiftShader
  GL had single frames whose frame layer showed a clip's picture
  (`ActiveTextureRenderTest`, `LayerSwitchRenderTest`);
  `GainEnvelopeAudioProcessor` applies the sound envelope. Captions and cards
  are drawn as bitmaps by `CaptionBitmapRenderer` and kept in
  `noBackupFilesDir/render/` by a hash of their content (and the renderer's
  `VERSION`), trimmed to 64 MB. It lays text out with `StaticLayout` in the
  bundled Noto fonts (`CaptionTypefaces`: Noto Sans or Serif for Latin with
  Noto Sans or Serif Devanagari as the first fallback, then the system
  fonts), so the platform's HarfBuzz shaping forms Devanagari conjuncts and
  matras, and shrinks text that does not fit its box. Font ids map to the
  app's own `res/font` files through a fixed table; an unknown id draws in
  the default font.
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
to them answers alike at all three sizes. Low drafts (below 854x480) and SD
BT.709-tagged sources on a decoder that ignores the tag are outside that
argument; they reuse the answer unmeasured. A source's matrix comes, in the
decoder's order, from an H.264 stream's own matrix coefficients (SPS VUI; the
matrix, not the primaries, decides the decode), else from what Media3
configures the decoder with: the SPS primaries, else the `colr` box's
primaries (Media3 reads no matrix there, for any codec), else from the same
size rule (`RetrieverProbe.classify`). HEVC, VP9 and AV1 stream tags are not
read, and a `colr` box before `avcC` (rare) is used although Media3 drops it. On a GPU that misdecodes, every
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
  Where a transition overlaps two items, the end of the outgoing one (the
  schedule's tail) plays in a second player, picture and sound, under or
  over the incoming one as the export's lanes are, and the UI draws both
  with the same `MotionMath` curves every frame.
- **Frame rows** each play in a muted ExoPlayer with the export's
  `ChromaKeyEffect` (plus `PremultiplyEffect`, since a TextureView blends
  premultiplied colour) on a non-opaque TextureView over MAIN, in stacking order.
- **Sound rows** (the frame's music, the closing message) each play in an
  audio-only player. Every player's volume follows the plan's gain envelopes,
  so the music ducks under clip sound as it will in the export.
- **Captions** are the export's own images: `CaptionBitmapRenderer` draws
  each at its box's size on the 1080-line canvas and the preview scales it
  to its box, so wrapping, size, outline and band match the export. A
  schedule that differs only in its captions is taken without reloading any
  player (`LayeredPreviewEngine.updateCaptions`), so typing and styling show
  at once. While the caption editor is open, its box is outlined on the
  preview and can be dragged inside the frame window's safe area
  (`CaptionPlaces`), snapping to the preset's place.

`LayeredPreviewEngine` keeps the followers within 200 ms of MAIN. It takes its
players from a `PreviewPlayerFactory` (`ExoPreviewPlayers` in the app), so
`LayeredPreviewEngineTest` checks its playlists, seeks, speeds, drift
correction, volumes and error handling with fake players. It is owned
by `EditorViewModel`, so a rotation keeps playing and only the surfaces are
attached again; the playhead and play state go into the saved state for a
process death. Two device facts shape it: a player with video effects shows
nothing after a seek until it plays, so a paused seek plays the frame player
until its first frame lands; and a TextureView that is never drawn stops taking
pictures and stalls its player, so a hidden layer is drawn almost transparent
rather than not at all.

## Export

The editor's Export button opens the export sheet (`ui.export`): three sizes,
**Best** (1080p30, about 12 Mb/s), **Good** (720p30, about 6 Mb/s, suggested)
and **Small** (480p30, about 2.5 Mb/s), each with its estimated size and, when
the phone is too full for it, how much to free. Save starts `ExportWorker`
through WorkManager as unique work named `export`, so one export runs at a
time on the phone (a second video asks before replacing it). The sheet only
watches that work; hiding it, rotating, leaving the app or closing the editor
never stops the export.

`ExportWorker` is a Hilt `CoroutineWorker` promoted to a foreground service
(type `mediaProcessing` on Android 15+, `dataSync` before) with a quiet
progress notification whose Stop button reaches the app's own unexported
`ExportStopReceiver` through an immutable PendingIntent. If the process is
killed, WorkManager runs it again from the start and the sheet says so.
`ExportPipeline` does the work:

1. removes what a killed export left (this app's hidden gallery entries in
   `Movies/ClearMovie`, its temporary files);
2. reads the project as it is (`ProjectRepository.read`, without opening it),
   and refuses with a plain reason when a preset file it plays changed or a
   photo or video is missing;
3. measures the loudness of any sound that was not measured when it was bound
   (a replaced spoken ending), so it is evened out like the preset's own;
4. builds the plan and composition at the chosen height, with the colour
   calibration measured again when this process only had an unclear answer
   (at most three times per process);
5. applies the free-space rule: 2.2 times the estimate plus 200 MB for a
   temporary file that is then copied into the gallery, else 1.2 times for
   writing straight into the hidden gallery entry (Media3's MP4 muxer on its
   file descriptor), else it says how much to free;
6. encodes with `VideoExport`: hardware encoders first; when an encoder fails,
   the output is emptied and the export runs once more with software encoders
   only. Decoders fall back to the next one when the first will not start
   (an Android 10 phone out of hardware decoder instances). The log names the
   encoders and the most decoders the plan keeps open at once next to the
   phone's H.264 instance limits;
7. shows the entry in the gallery only when it is complete. A Stop or a
   failure removes the entry and the temporary file.

The gallery name comes from the project's name (`ExportFileName`): path and
reserved characters become spaces, invisible and direction-changing
characters are dropped, a leading dot cannot hide it, letters of every script
stay, and the extension is always `.mp4`. An unnamed draft uses the editor's
title. The result offers Play (the app's own player) and Share (the phone's
share sheet, with read access to that one video), and the notification does
the same.

## Screens and navigation

Home (the presets, Start without a preset, New preset, My videos; with no
presets yet, New preset first, then Start without a preset; every card one
height) leads to preset setup or
repair when a file is not ready (a missing file never crashes: it opens the
repair heading), then preset review (Play, Replace or Skip for this video for
the intro, frame and ending; the choices rebuild the new draft from the
track template), then the photo step (the one-time access explainer, Try
again after a refusal, the system Photo Picker, the intake), then the editor
(below; Export at the top right opens the export sheet).
`NewVideoFlow` decides the order: with `EntryMode.MEDIA_CHOSEN` (shares, from
cm-share) the preset pick goes straight to the editor.

Layouts follow `WindowClass`: two panes for medium or expanded width, or
compact height. State survives rotation and process death: the back stack,
the review's choices, the picked links until they are on MAIN, whether the
picker was already opened (so it never opens again by itself), and the
editor's playhead. On a fresh start, `ProjectRepository.launchDecision`
reopens the editor when the app stopped with a video open in it.

## Share from the gallery

`ShareActivity` holds the `SEND` and `SEND_MULTIPLE` filters (`image/*` and
`video/*` only). Android starts a share target inside the sharing app's
task, so it shows nothing: it reads the intent as untrusted
(`ShareLinks`: `content://` links only, each once, at most 500; `file://`
and malformed entries are counted, never read), hands only those links on
to `MainActivity` in ClearMovie's own task (in the ClipData, with a read
grant), and finishes. A share while the app is open, in front or in the
background, so reaches the one `MainActivity` through `onNewIntent`.
`MainActivity` takes shares only in that handed-on form. Questions to the
sharing app's provider (the type, a video's length) have a 5 s limit on a
few threads of their own, so a provider that hangs costs only its items.

`ShareViewModel` (one per activity) runs the share:

- **Routing.** Shares are handled one at a time, in the order they came. A
  video open in the editor (or an earlier share still on its preset pick)
  is asked about first: "Add to this video", "Start a new video" (the open
  one is closed, saved in My videos) or Cancel.
- **Intake, at once.** Share grants last only as long as the activity, so
  the items are taken in straight away with `MediaIntake` (cm-storage):
  MediaStore links are kept as links, other apps' files are matched to
  MediaStore by name and size or copied into the project within the copy
  budget. Items whose type is clearly not a photo or video (a PDF, text)
  are skipped and counted; links that can't be opened are counted. More
  than 100 items show a length notice (Continue or Cancel) first.
- **Order.** `CaptureOrder`: by capture time (MediaStore `DATE_TAKEN`, else
  EXIF `DateTimeOriginal`, else the video's creation time), oldest first;
  an item with no time keeps its shared place, and ties keep the shared
  order.
- **Pending draft.** A new video's items go onto the MAIN row of a blank
  draft (`ShareDrafts`), written before the share's preset pick shows and
  left out of the session. The pick (`HomeScreen` with a share header)
  then rebuilds the draft from the chosen preset, keeping its id, copies and
  grants, and opens the editor; a preset file that is not ready opens setup
  or repair first, whose Done does the same. Cancel or Back drops the share
  and its draft.
- **Process death.** Where the flow is, the draft id and the notes are in
  the saved state; the links are in a small file (`PendingShare`), so a
  share half taken in is taken in again (its grants belong to the activity
  record, which Android keeps), and one on its preset pick comes back as it
  was. A share replayed from Recents (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`)
  is ignored.

## The editor

`EditorScreen` has the top bar (Back, the title, Undo stacked above Redo, and
Export at the top right; the bar is always 64 dp tall, so the title shrinks to
stay on one line, down to 14 sp (less at the largest text sizes, so two
lines still fit), before it takes a second line),
the preview with Play, the time and the Zoom out / Zoom in / Fit all buttons,
the timeline, and the contextual bar or, while one is open, the panel of the
action in use. Its on-screen state (`EditorScreenState`: selection, open panel,
zoom and scroll, "Show all rows", the floating preview's corner) is saved with
the screen; the playhead is saved by `EditorViewModel`.

- **Layout.** `PreviewLayoutReducer` places the preview from the window, the
  bars' measured heights and the rows' heights (which grow with the font
  size): two panes are Docked; stacked, the preview is Full, Shrunk (down to
  180 dp, or 28% of the window when that is more and the natural preview is
  taller), Partial (only the rows that fit, MAIN and the selected row first,
  with "Show all rows (N more)"), or Floating: when all rows are asked for and
  don't fit, the preview is a small window over them that drags and snaps to
  a corner, with Move and Close buttons.
- **Timeline.** A ruler, the playhead and the rows in screen order, each with
  its type icon and name. MAIN shows each item with a thumbnail strip
  (re-planned per zoom step by `ThumbPlan`, the old pictures drawn until the
  new ones load), its type and length, trim handles on the selected item and
  the "+" insert points that `TimelineGeometry` places: one per boundary, all
  of them once items are 64 dp wide, otherwise around the selected item and
  under the playhead, moved clear of the trim handles and never overlapping.
  The other rows are drawn read-only until cm-tracks.
- **Gestures.** One pointer handler feeds `TimelineGestureArbiter`: a second
  finger before a long-press is a pinch (zoom around the fingers, clamped
  between Fit all and 1 s per 240 dp, `TimelineZoom`), movement is a scroll,
  a trim from a handle or a scrub from the ruler, a hold then a drag moves an
  item, a hold and release opens its menu, and a quick release is a tap. Each
  gesture has a button: the zoom buttons, the bar's Move left and Move right,
  the Trim panel, TalkBack actions on the items and the time.
- **Edits.** The bar's actions and the panels change the model through
  `EditSession` (one undo step each, 50 kept; a change from elsewhere is a
  step too). "+" opens a chooser: photos and videos from the gallery, a text
  card or a colour card, put exactly before or after the item. Gallery picks
  for "+", Replace and Add are taken in by the view model with `MediaIntake`
  and `newMainItems`, and the links are kept in the saved state until they are
  on MAIN.
- **Record voice.** A voice row's chooser ("+" or Add here) also offers
  Record voice. The microphone permission is asked for only then, after a
  plain-language explanation; refused for good, a dialog says where in
  Settings to allow it. `VoiceRecordingController` (owned by the view model,
  so a rotation keeps recording) opens the microphone with a 3 s countdown on
  screen, then plays the preview from the playhead with its sound off
  (`LayeredPreviewEngine.monitorGain`, so nothing goes back into the
  microphone; the export is unchanged). It reads the preview's position every
  8 ms until the picture has moved, and `RecordingSync` works out the moment
  the first picture showed (the median over a dozen readings); the
  microphone's capture timestamps (`AudioRecord.getTimestamp`, or the arrival
  times when there are none) give the frame captured then, and
  `VoiceRecorder` drops every frame before it, plus the AAC encoder's 2048
  frames of priming (46 ms at 44.1 kHz), which players decode as silence at
  the start. The recording ends at Stop, Back, the end of the video, a
  preview that is moved or held up for more than 3 s, a phone call taking the
  microphone, or the app going to the background, and keeps what was said
  up to then. While it runs the panel shows the time, a large Stop and
  Cancel, and the rest of the editor takes no touches. The clip goes at the
  playhead, never moved in time, on the voice row it was asked from when it
  is free, else the first free voice row, else a new one (`addRecording`),
  as one undo step. The file is `voice-<token>.m4a` (mono AAC-LC, 96 kbit/s)
  in the project's own media folder, written as `.part` and renamed when
  complete; `VoiceRecordings.sweepUnused` removes recordings the video no
  longer plays when the editor opens or leaves it (no undo can bring them
  back then), never while an export runs. The sync is checked by
  `VoiceRecorderSyncTest` (a click 1 s after the picture is 1 s into the
  file) and by hand with `RecordVoiceRealCheck` (`-e cm.recordsync true`),
  which records over real clips through the editor and exports.
- **Fit with blur.** The export blurs the background with a separable
  Gaussian at an eighth of the window (`BlurPasses`, two passes before
  `PlacementEffect`'s own); the preview draws the picture shrunk and stretched
  behind the fitted one.

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

**Presets.** `PresetStore` offers only stored definitions: the presets the
user made, and on an install made before cm-home-presets the Event frame,
which `seedOnce` stores once (the app was updated rather than newly
installed, or the preset has files or videos) as an ordinary preset the user
can edit or delete; `filesDir/presets-seeded` records that this was decided,
so a deleted preset never comes back. A new install starts with none. A video
made from a preset the app ships, on an install with no copy of it, reads
the app's own definition, which is never put back on the list. The user's
own `preset.json` may only replace a preset with the id of one the app
ships; it is size-limited, strict UTF-8, parsed by `PresetParser` and
re-checked every time it is read. Each slot's file (intro, frame, ending) is copied into
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
one APK for every processor type with `versionCode = base × 10`, and the CI
workflow `.github/workflows/android.yml` with `check`, `instrumented`
(API 29 and 36 emulators) and `release` jobs, plus a tag-only `publish` job.
