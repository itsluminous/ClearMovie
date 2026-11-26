# ClearMovie voice pack, version 1

The voice pack lets ClearMovie say a voice item's words in another voice:
British, Indian or American, male or female. It is kept out of the app
because it is about 150 MB, and it works without the internet once added.

## Add it to your phone

1. In ClearMovie, choose any voice other than "No change" in a voice row's
   sheet. The app says how big the download is.
2. Tap Download. ClearMovie downloads the files your phone needs from this
   folder (and, for Hindi HD, from [hindi-hd-v1](../hindi-hd-v1/)). Tap Hide to
   keep editing while it works.

To download the files in a browser instead:

1. In the same window, tap "Download in the browser instead". This page opens,
   and the app lists the files your phone needs and how big they are.
2. Download the files the app listed. Every phone needs `voices.zip` and the
   engine for its processor (the app names it). To speak words rather than type
   them, also download `speech-english.zip` or `speech-hindi.zip`. On a phone with
   4 GB of memory or more, the app lists Hindi HD's files from the
   [hindi-hd-v1](../hindi-hd-v1/) folder instead of `speech-hindi.zip`.
3. Go back to ClearMovie, tap "Add the downloaded files" and choose them in
   your Downloads folder. You can delete the downloads afterwards.

The app checks each file against `manifest.json` (its size and SHA-256)
before it unzips anything, and refuses a file that does not match.

## Files

| File | What it is |
| --- | --- |
| `voices.zip` | The voice engine's model and its six voices |
| `speech-english.zip` | Speech recognition for English |
| `speech-hindi.zip` | Speech recognition for Hindi |
| `engine-arm64-v8a.zip`, `engine-armeabi-v7a.zip`, `engine-x86.zip`, `engine-x86_64.zip` | The engine's native libraries, one per processor type |
| `manifest.json` | Every file's size, unzipped size, number of entries and SHA-256 |

Each file is under GitHub's 100 MB limit, and none uses Git LFS. A pack that
changes gets a new folder (`v2`), so these files never change.

## Licences

All parts are open source, and each zip carries its licence and a `NOTICE.txt`:

- Kokoro-82M (hexgrad), Apache-2.0, in the int8 export from
  [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0).
- Vosk small English and Hindi models (Alpha Cephei), Apache-2.0.
- sherpa-onnx's JNI library (Apache-2.0) and ONNX Runtime (Microsoft, MIT).
- libvosk from vosk-android (Apache-2.0).

The voice item's own recording never leaves the phone, and neither do its words.
