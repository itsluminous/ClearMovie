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
| `domain.voice` | The voice choice's rules (cm-voice-choice): `Phrases` and `PhraseFit` (a recording's words as timed phrases, and fitting each voiced phrase before the next), `Phonemizer` (English through the pack's dictionary, unknown words by rule, numbers as words; Hindi through `HindiPhonemes`), `VoicePackManifest`: pure Kotlin | `android.*`, `androidx.*`, or any other app layer |
| `domain.record` | `RecordingSync`: lining a voice recording up with the picture: pure Kotlin | `android.*`, `androidx.*`, or any other app layer |
| `domain.export` | The export's rules: the three sizes (`ExportQuality`), the free-space rule, the gallery file name, the time left: pure Kotlin | `android.*`, `androidx.*`, or any other app layer |
| `render.media3` | The Media3 mapping: `CompositionFactory`, the GL effects, the gain processor, `RenderExporter`, `VideoExport` (hardware first, then software) | |
| `render.preview` | The layered editor preview (`LayeredPreviewEngine`, `StillLoader`) | |
| `data.settings` | `SettingsRepository` and its DataStore implementation | `ui` |
| `data.db` | The Room database `ClearMovieDatabase` (projects, session, preset bindings and definitions, persisted link grants) | `ui` |
| `data.project` | `ProjectRepository` (autosave, named save, recovery), `AutosaveGuard`, `NewVideos` (new drafts), `ShareDrafts` (a share's pending draft), `ProjectMedia` (a project's files) | `ui` |
| `data.preset` | `PresetStore`, the key-colour check, `LoudnessMeter`, `MediaProbe` | `ui` |
| `data.media` | `MediaAccess`, `MediaResolver`, `MediaCopier`, `CopyBudget`, `MediaPermissions`, persisted grants, `newMainItems` (picked items to clips); for shares `ShareLinks`, `ShareTypes`, `CaptureOrder` and `CaptureDates`, `PendingShare` | `ui` |
| `data.voice` | `VoicePackStore` (the voice pack on the phone, and taking in downloaded files), `VoiceEngine` (Vosk and Kokoro from the pack), `VoiceTools` / `PackVoiceTools` (a recording's words, and words to a new voice file); the pack download (cm-voice-pack-download): `PackDownloader` (the only code that uses the network), `PackDownloadJob`, `PackDownloads` | `ui` |
| `data.record` | `VoiceRecorder` (microphone to AAC in M4A), `MicSource` and `AudioRecordMic`, `VoiceRecordings` (the files, and removing unused ones) | `ui` |
| `data.files` | `AppFiles` (where files live), `SafeFileName`, `StorageUsage` | `ui` |
| `data.export` | `ExportStore`: the export's hidden-then-shown entry in `Movies/ClearMovie` | `ui` |
| `work` | `ExportWorker` (the foreground worker), `ExportPipeline` (what one export does), its notifications and Stop receiver; `VoicePackDownloadWorker` and `WorkPackDownloads` (the voice pack download), its notifications and Cancel receiver | |
| `di` | Hilt modules (the settings DataStore, the database, the storage classes, the app scope) | |
| `ui.theme` | `ClearMovieTheme`, the branded fallback colour schemes, `MediaColors` | |
| `ui.navigation` | `ClearMovieApp` (the navigation graph), `NewVideoFlow` (which screen follows which), `Routes` | |
| `ui.layout` | `WindowClass`: Material window size classes from the space the screen has | |
| `ui.home`, `ui.preset`, `ui.pick`, `ui.share`, `ui.editor`, `ui.export`, `ui.settings` | The screens and their view models; `AppLanguage` (the in-app language picker) | |
| `ui.editor.timeline` | The editor timeline (`TimelineView`) and the pure pieces it runs on: `TimelineZoom`, `TimelineGeometry`, `TimelineGestureArbiter`, `PreviewLayoutReducer`, `ClipActions`, `TrimMath`, `ThumbPlan`, `TapSeek` | |
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
  MAIN (once a new frame takes over part of the preset frame, `addFrameFrom`,
  the preset frame keeps fixed times and the new frame follows the video's
  end from the cut). Inserting on MAIN shifts later clips; on any other row the clip goes at
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
  (`CaptionAttachment.detachedStartOffsetMs` / `detachedEndOffsetMs`, its
  distance from that edge of the item): it keeps that distance wherever the
  item goes or however it is trimmed, while the other side follows the item,
  and follows it again when let go exactly on the item's edge. Both detached,
  the caption is free and keeps its times. While
  one side is detached, Match item gives it both sides back.
- **Handles** (`Resize.kt`). Every item off MAIN can be resized by side:
  `resizeRange` gives each handle's limits (neighbours on its row, the
  video's start and end, 0.5 s or 0.3 s for video and sound, and its file
  for media that does not repeat) and `resizeClip` applies one. The preset's
  frame, its music and the closing message keep following the video on the
  side that was not dragged, and the dragged side keeps its distance from
  that edge of the span (`detachedStartOffsetMs` / `detachedEndOffsetMs` on
  the clip, next to its `anchor`).
- **Lower for talk.** Sound is lowered on one ladder (`SoundRank`, on
  `Track`): frame sound is lowest (a frame row's own sound, and the preset
  frame's, its frame music row), then music rows, then the sound of
  photo-and-video rows (MAIN and picture in picture), then voice rows. While
  a row's sound plays, every row below it with Lower for talk on
  (`lowerWhenTalking`) goes down to its `lowerToDb`, or without one to the
  project's `DuckingSpec` levels (-20 dB under a voice, -12 dB under a
  video or music). The closing message lowers every other row: to
  `speechDuckDb` (-20 dB), or to the row's own `lowerToDb` when Lower for
  talk is on with one, as a music row's level always did. Music, frame and
  photo-and-video rows have the setting (photo-and-video rows since schema
  7), and a frame its own `volumeDb` (schema 6). A
  style stores only what differs from the preset's
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
  either; schema 5 keeps a detached side as a distance from its item's edge,
  and its step renames the schema 4 keys and turns their times into
  distances; schema 6 added a frame's `volumeDb`, and its step rewrites
  nothing, so an older frame reads at the frame music's -8 dB; schema 7
  gave photo-and-video rows Lower for talk, and its step rewrites nothing:
  every older row already carries `lowerWhenTalking`, so a row the user
  turned off stays off and the rest take the ladder; schema 8 added a
  voice item's `voiceOver`, and its step rewrites nothing, so an older
  voice item plays its recording). Golden files under `app/src/test/resources/fixtures/project/` must
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
  ducking computed from the timeline on the Lower for talk ladder (frame
  sound under music, both under videos' own sound, all three under voices,
  everything under the closing message; no row lowered by one below it),
  with the project's 300 ms ramps, straight in dB. A video whose file has no
  sound track (the intro and a video ending too) lowers nothing, nor does a
  music or voice item turned down to -40 dB or lower, nor a muted row. A video keeps the answer
  (`VideoClip.hasSound`), read when it is added; for older videos and the
  preset's parts the editor and the export read it with one function
  (`readSoundTracks`, `METADATA_KEY_HAS_AUDIO` through `UriGuard`), only
  when a music or frame row would be lowered, and the editor keeps it on
  the videos. A file that can't be read lowers music as before. A frame on a frame row of the user's plays its video's
  own sound as a music layer of its row (cm-frame-audio): at the frame's
  `volumeDb` (the frame music's -8 dB to start) after the music's loudness
  normalisation, each copy of a repeating frame from its file's start with
  its picture and a dip at each join, and lowered by its row's Lower for
  talk. Only a frame with a measured loudness plays, so one without sound
  gets no sound layer. The editor offers a frame's Volume and Lower for talk
  as soon as a quick look finds a sound track in its file, measures its
  loudness in the background with the export's own meter (`SoundLoudness`)
  and keeps it on the frame (`OverlayClip.soundLufs`, no undo step), so a
  frame is measured once and the preview and the export play it at one
  level; an export of a frame not measured yet measures it itself. The
  preset's frame is left out there: its sound is the frame music row
  already. `RenderPlan.slice` cuts a plan to a part of the video
  (render tests use it).
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
in a plain player with the other rows drawn over it.

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
- **Sound rows** (the frame's music, the closing message, and a user frame
  row's own sound) each play in an audio-only player; the frame's keyed
  picture player stays muted. Every player's volume follows the plan's gain envelopes,
  so every row ducks as it will in the export: while playing, the volumes are
  set every 20 ms (`VOLUME_TICK_MS`) between the 100 ms syncs, so a 300 ms
  ramp plays in steps of about 1.3 dB (the export ramps sample by sample).
- **Captions** are the export's own images: `CaptionBitmapRenderer` draws
  each at its box's size on the 1080-line canvas and the preview scales it
  to its box, so wrapping, size, outline and band match the export. A
  schedule that differs only in its captions is taken without reloading any
  player (`LayeredPreviewEngine.updateCaptions`), so typing and styling show
  at once. While the caption editor is open, or a caption is selected and
  shows at the paused playhead, its box is outlined on the preview with
  handles (`ui/components/ResizableRect.kt`, shared with the preset's
  caption style editor): the box moves inside the frame window's safe area
  (`CaptionPlaces`), snapping to the preset's place, and a handle resizes it.
  A resize keeps the text size: the box is never made shorter than its
  words need at the new width (`CaptionBitmapRenderer.boxHeightFor`, the
  export's own measurement), so the preview and every export size wrap it
  alike. A preset's caption style also sets `effects.textStart`, the start
  animation new captions get (`withEffectDefaultsFor`). The preset editor's
  Animation and transition card sets the other two effect defaults
  (cm-preset-motion-defaults): `effects.photoDuring`, the "all through"
  animation new photos get (None or a gentle zoom; videos have none), and
  `effects.mainTransition`, the transition at every junction a new item makes
  (None or any kind, keeping the preset's length, which the row shows). Both
  choices show the editor's own tiles (`AnimationChoices`,
  `TransitionChoices`) on sample pictures. New preset opens this editor right
  after Create, so they are chosen there, as the caption style is. Its
  Default voice card (cm-preset-voice-default) sets `Preset.voice`, the voice
  a new voice recording starts in, from the Record voice sheet's own list
  (`VoiceChoiceField`); `preset.json` keeps it as `defaultVoice`, a
  `VoiceChoice` id written only when it is not No change, and a file without
  one, or with an id this version does not know, reads as No change. A project
  copies the preset's defaults when it is made (the voice as
  `Project.defaultVoice`, left out of the JSON while null, so older projects
  keep their bytes and schema), so a later change to the preset reaches only
  videos made after it.

`LayeredPreviewEngine` keeps the followers within 200 ms of MAIN. In the last
1.5 s before one of its parts (the frame after the intro, the outgoing video of
a transition, a sound row's next sound) a follower waits, paused, at that
part's start and starts when MAIN reaches it, its picture already decoded:
crossing from silence into the file mid-play, it started late and was then
moved back in step again and again. Further away it plays its silence and
holds no decoder, which an export running beside the editor may need. A
paused seek within a gap leaves a keyed frame player where it is in its
silence: one moved within its silence and then to its file stayed buffering,
and the paused preview showed no frame. Every preview player has Media3's
dynamic scheduling off: with it on, a player that plays only sound (MAIN over
a photo, a card or the held last frame) updated its position about four times
a second, so the motion drawn from MAIN's clock moved in steps and the
followers looked out of step. The preview picks MAIN's item by the clock, not
by the player's last report (the next item's first picture can be on screen a
moment before the player says it moved on), and draws MAIN's surface hidden
from a photo, card or gap on until the next video's first picture, as the
surface still holds the last picture of the video before. It takes its
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

The editor's Export button (the upload arrow at the end of the playback strip, beside Play) opens the export sheet (`ui.export`): three sizes,
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

**Keeping the screen on** (cm-keep-screen-on). `MainActivity` sets
`FLAG_KEEP_SCREEN_ON` on its own window (no wake lock, no permission) through
`ScreenAwake` while it is started and either an export of any video is
waiting or saving (the unique `export` work, unfinished) or a screen holds it:
`KeepScreenOnWhile` in the editor while the preview plays or a voice is
recorded, and in `PlayFileDialog` while its player plays (the preset review,
an editor part, the exported video). `KeepScreenOn` holds the pure rules.
The flag follows the activity's lifecycle directly, because Compose's
recomposer is paused once the activity stops, so it goes the moment the app
leaves the screen; pausing, an ending, a failed file, a finished, failed or
stopped export, or leaving the screen clears it as well. The export itself
runs on in the background as before.

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
(below; Export in its playback strip opens the export sheet).
`NewVideoFlow` decides the order: with `EntryMode.MEDIA_CHOSEN` (shares, from
cm-share) the preset pick goes straight to the editor.

Layouts follow `WindowClass`: two panes for medium or expanded width, or
compact height. State survives rotation and process death: the back stack,
the review's choices, the picked links until they are on MAIN, whether the
picker was already opened (so it never opens again by itself), and the
editor's playhead. On a fresh start, `ProjectRepository.launchDecision`
reopens the editor when the app stopped with a video open in it, except
right after a crash (Recovery, below).

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

`EditorScreen` has the top bar, Material's small top app bar (Back, the title,
Undo and Redo side by side, and More options; the bar is always 64 dp
tall, 56 dp on a phone held sideways, so the title shrinks to stay on one line, down to 14 sp (less at the
largest text sizes, so two lines still fit), before it takes a second line). A tap on the title, or More options > Rename video, opens the
rename dialog (`RenameDialog`); the new name is an edit like any other, so Undo
takes it back. More options also holds Video shape, Settings, which opens
the settings screen over the editor (Back returns to it as it was), and Delete
video (cm-delete-project). Delete asks first (`DeleteVideoDialog`), and says to
wait instead while an export of the video is waiting or running
(`ExportsInProgress`); on Delete the view model stops any intake, deletes the
project through `ProjectRepository.delete` and goes Home. It is not an undo
step, and no edit, undo or autosave can write the video back afterwards. Below the
bar come the preview with Play, the time and Export, the timeline, and the
contextual bar (with Zoom out / Zoom in / Fit all) or, while one is open, the panel of the
action in use. Its on-screen state (`EditorScreenState`: selection, open panel,
zoom and scroll, "Show all rows", the floating preview's corner) is saved with
the screen; the playhead is saved by `EditorViewModel`.

- **Layout.** `PreviewLayoutReducer` places the preview from the window, the
  bars' measured heights and the rows' heights (which grow with the font
  size). Every row is always on the timeline, which scrolls up and down when
  they don't fit. Two panes are Docked, with no "Show all rows"; stacked, the
  preview is Full, Shrunk (down to 180 dp, or 28% of the window when that is
  more and the natural preview is taller), Partial (the minimum preview, the
  rows scrolling under it, and "Show all rows (N more)" for the rows below
  those that fit), or Floating: when all rows are asked for and don't fit,
  the preview is a small window over them that drags and snaps to a corner,
  with Move and Close buttons. The contextual bar is 56 dp with one-line
  labels, as tall as the Play bar. In a window under 480 dp tall (a phone
  held sideways) the rows are slimmer (`RowMetrics.compact`: MAIN 64 dp, the
  others 40 dp, 4 dp apart), so four rows show on a 411 dp tall phone. The
  selected row, or MAIN with nothing selected, is scrolled into view when the
  selection or the rows' room changes.
- **Timeline.** A ruler, the playhead and the rows in screen order, each with
  its type icon and name. MAIN shows each item with a thumbnail strip
  (re-planned per zoom step by `ThumbPlan`, the old pictures drawn until the
  new ones load), its type and length, and trim handles on the selected item.
  Every row has its own "+" floating over its right edge, its 48 dp touch
  area flush with the timeline's edge, the same place on every row; items
  run on under it, and held or dragged there they are still the item. It
  adds to the right of the cursor (`RowEndPlus`): at the cursor where the new
  item fits, else right after the item under it (on rows other than MAIN,
  past any items that leave no room; on MAIN later items move later), never
  starting later than the item's length before the video's end. The time is
  fixed when the "+" is tapped (`OpenPanel.atMs`, shown in the chooser's
  title), and opening a chooser pauses the preview. A frame row's "+" adds a
  new frame from the cursor: the frame under it ends there and the new one
  takes over its time (`addFrameFrom`). A row that never takes what it would
  add (the preset's music, a row switched off) adds at the cursor on another
  row of its kind and says so. On the row of the selected item it gives way
  to that item's own "+" on each side, which `TimelineGeometry` and
  `TimedRowGeometry` place clear of its trim handles.
- **Gestures.** One pointer handler feeds `TimelineGestureArbiter`: a second
  finger before a long-press is a pinch (zoom around the fingers, clamped
  between Fit all and 1 s per 240 dp, `TimelineZoom`, but never so close that
  the whole video is wider than `MAX_SPAN_DP`, 30,000 dp: a MAIN item is laid
  out at its full width, and Compose cannot measure a box wider than 262,143
  px; an item on another row, which can run past the video's end, lays out
  only its part on screen), movement is a scroll
  (one way per gesture: up and down the rows when it starts more upright than
  sideways and the rows scroll, otherwise along the time), a trim from a
  handle (unless clearly upright) or a scrub from the ruler, a hold then a drag moves an
  item, a hold and release opens its menu, and a quick release is a tap. Each
  gesture has a button: the zoom buttons, the bar's Move left and Move right,
  the Trim panel, TalkBack actions on the items and the time.
- **A tap on an item** selects it and (`TapSeek`) moves the cursor to the time
  tapped, kept inside the item: while playing on any row, and play goes on
  from there; while paused only on the rows the preview shows (MAIN, text,
  frames, picture in picture, switched on), so the preview shows that item
  there. A tap on a sound while paused only selects it. A tap on a "+" or a
  transition marker keeps its own meaning; a tap on a trim handle keeps the
  item selected and is a tap on the item (on a narrow item the handles cover
  it). TalkBack's click only selects (it has no point to move to). While
  playing the view follows the playhead until the view is moved along the
  time by hand (a swipe, a pinch) or an item is moved or held (a drag, a
  trim, a long-press); moving the cursor (a tap on an item, a tap or a scrub
  on the ruler) starts the following again. A selection made while the view
  follows gets its "+" brought into view when playing stops. A seek with the
  video stopped at its end leaves it paused there (`LayeredPreviewEngine`).
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
- **Voice choice** (cm-voice-choice). A voice row's sheet has "Voice in
  the video" (an exposed dropdown: No change, then British, Indian and
  American voices, male and female; `VoiceChoice` in `domain.model`, ids
  a preset can store) and, with a voice chosen, "Spoken in" (English or
  Hindi; only the Indian voices say Hindi) and "Type the words". A
  recording made with a voice chosen has its words found on the phone and
  its "Change voice" panel opens to check them (speech recognition
  mishears, the panel says so); Make voice says them in the voice. Typed
  words become a new item of their own length. `AudioClip.media` stays the
  recording: `AudioClip.voiceOver` holds the voice, language, the words as
  phrases timed in the recording, and `generated`, a new
  `voice-<token>.m4a` as long as the recording, so trims, splits and
  handles stay right; the plan, the loudness and the missing-file check
  play `playedMedia`, and the sweep keeps both files. No change keeps the
  words and plays the recording. Each phrase is voiced at its first word,
  up to 1.3x faster if it would run into the next, and ends where the next
  starts. `VoiceChoiceController` (owned by the view model) runs one job
  at a time off the main thread and applies it as one undo step only if
  the item is unchanged. When the editor opens a video with no choice kept
  with the screen, the sheet starts at the video's `defaultVoice`
  (`preselect`), without asking for the pack: a recording made without it
  stays as recorded, and the pack dialog shows when its words are to be
  found, right after it; once the pack is added, the item's Find the words
  and Make voice use that voice. Closing that dialog without the pack sets
  the choice back to No change, so later recordings are not asked about a
  voice the user never picked.
  The engine is Vosk (speech to words, with word times) and Kokoro
  through sherpa-onnx (words to voice; it reads phonemes, which the app
  makes itself, since it silently skips words its dictionary lacks), all
  Apache-2.0 or MIT, inside the app's own process; nothing they do uses
  the network. They are about 150 MB, so they are not in the APK: they
  are the voice pack, the `voice-packs/v1/` folder of the repo (zips under
  GitHub's 100 MB limit, and `manifest.json`), outside the app build.
  The app carries the same manifest (`assets/voice-pack/manifest-v1.json`,
  checked equal by a unit test). Choosing a voice without the pack shows
  its size and Download (cm-voice-pack-download, below). "Download in the
  browser instead" keeps the older way: the folder's page, the files, and
  "Add the downloaded files": each chosen file is copied privately while its
  SHA-256 is worked out. Either way, only a listed file for this phone's
  processor is unzipped, through a staging folder, entry by entry inside
  its part's folder and within the listed count and size, into
  `filesDir/voice-pack/v1/`, then made read-only. The engine's native
  libraries load from there by path (`System.load`; JNA finds `libvosk`),
  so the APK keeps only JNA's dispatcher, and the vendored sherpa-onnx
  Kotlin API (`src/vendor/`) has no `loadLibrary`. Engines are loaded per
  job and released (Kokoro alone grows to about 800 MB). On a phone whose
  processor the pack has no engine for, only No change is offered.
  Checked by hand with `VoiceChoiceDeviceTest` (`-e cm.voicepack true`).
- **Hindi HD** (cm-hindi-voices). An add-on pack in `voice-packs/hindi-hd-v1/`
  with its own manifest (`assets/voice-pack/manifest-hindi-hd-v1.json`,
  checked equal by a unit test), merged into the v1 manifest at start-up
  with each file marked by its folder, so its files unpack into the same
  `filesDir/voice-pack/v1/` and one Remove takes everything. Its parts:
  `voices-hi-hd` (a voices.bin with Kokoro hf_beta and hm_psi beside the
  six of v1, for "Indian male voice 2" and "Indian female voice 2"),
  `speech-hi-hd` (Vosk `vosk-model-hi-0.22` without rescoring: am, conf,
  graph, ivector) and `speech-hi-hd-rescore` (its rescore and rnnlm
  folders), both into the one model folder `asr/hi-hd`. A zip over 100 MB
  is listed with `pieces` (`<file>.001` and on, 95,000,000 bytes each, each
  with its SHA-256): the download checks each piece as it ends and fetches a
  bad one once more; `addDownloaded` re-checks every piece and unzips them
  read in order. `HindiModel` comes from the phone's total memory: under
  3.4 GB the small recognizer only, from 3.4 GB the light model, from
  7 GB (phones sold with 8 GB) also the rescoring files, which a phone below
  that never takes in. Hindi asks for the best model the phone runs, unless
  a Hindi recognizer is already there; Settings > Storage offers the rest.
  The phonemizer reads numbers as Hindi words (years 1100-1999 in
  hundreds; a leading zero or more than nine digits digit by digit) and an
  anusvara after a short vowel before a stop as that stop's nasal.
- **Voice pack download** (cm-voice-pack-download). The internet is used
  for this alone. Download in the pack dialog starts the unique
  `VoicePackDownloadWorker` (WorkManager, a `dataSync` foreground worker
  that needs a connection, with a notification and Cancel), so it goes on
  while the user edits or leaves the editor. It fetches the files this phone
  needs: `voices.zip`, the engine for its processor type, and the words
  file of the language in use ("Spoken in", English unless Hindi was
  chosen); the other language's file comes later, the same way, if its
  words are to be found. `PackDownloader`, the only code allowed to use the
  network, opens only `https://raw.githubusercontent.com/itsluminous/ClearMovie/<commit>/voice-packs/<folder>/<file>`
  at the commit pinned in `VoicePackSource` (so a later push cannot change
  the bytes), follows no redirect, takes no more than the listed size, and
  resumes a part on disk with a Range request. A dropped connection is
  tried again after 2, 5 and 10 s (a try that got bytes starts the count
  again), a lost connection pauses the worker until WorkManager runs it
  again, and Cancel stops it before the next file. Each finished file goes to
  `VoicePackStore.addDownloaded`, which works out its SHA-256 and unzips it
  only if it is the file the app's own manifest lists (a manifest is never
  fetched), then deletes the zip; a mismatch is downloaded once more from
  the start, and a match without room to unpack it is kept for Try again. Before downloading, the dialog checks free space (everything
  unpacked, the largest zip and 50 MB), and warns on mobile data or without
  a connection. Parts live in `filesDir/voice-pack/v1/.download/`; Cancel
  and Settings' Remove delete them, once the worker has stopped
  (`VoicePackDownloadWorker.active`), so a stopping worker cannot add a
  file back into a removed pack. The network security config
  (`res/xml/network_security_config.xml`) allows no plain HTTP and trusts
  only `raw.githubusercontent.com`. `NetworkUseConventionTest` fails the
  build if any other source uses networking APIs or a networking library
  is added; `scripts/verify-release-apks.sh` checks the release APK's exact
  permissions, its network security config, that every network call in
  its code is the downloader's or a known library method the app never
  reaches, and (`scripts/check-native-network.py`) that no native library
  in the APK or the pack's engines imports socket, name lookup or TLS
  functions, since native code is not bound by the network security config.
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
launch, a session left behind by a kill reopens the editor at that project
with no question; "We brought back your last video" shows only when it has
edits newer than that time. A session naming an unreadable project starts at
Home. After a crash the launch starts at Home too (cm-crash-after-media-pick):
the crash handler keeps the crash in `CrashLog` (`noBackupFilesDir/crash`,
the app and phone, the time and the trace, its start and end when it is
long) with a mark, and the next process clears the session in
`Application.onCreate`, before a screen or a share asks which video is open,
taking the mark only once that worked (`launchDecision` opens Home while it
is there). So a video whose editor crashes on opening is not reopened at
every launch; its edits were written and it stays in My videos. Any uncaught
crash does this, not only one in the editor. Settings > About then shows
Share crash details (only for a crash kept by this version, by version code), which sends the text
through the share sheet: the app has no internet permission, so nothing
leaves the phone unless the user shares it. The timeline lays out
with the stored zoom fitted to the current length (`TimelineZoom.forLayout`):
the zoom kept while the editor waits for its project is fit all of an empty
video, and 0.0.5 drew a long video's rows at that scale in the frame the
project arrived, too wide to lay out.

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
(preset files), `cacheDir/thumbs/`. Deleting a project removes its row, its own
folder and thumbnail (`AppFiles.deleteProjectFiles`, which never follows a link)
and the link permissions no other project uses; gallery items, exported videos
and preset files are never in that folder, so they stay. Leftover `.part` files and unreferenced
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
