# ClearMovie Hindi HD voice pack, version 1

Hindi HD is an add-on to the voice pack in `../v1/`. It gives more accurate
Hindi speech recognition and two more Indian voices ("Indian male voice 2" and
"Indian female voice 2"). The app downloads it from this folder when it is
needed, or from Settings > Storage > Hindi HD, and works without the internet
once it is added.

## What a phone gets

The app reads how much memory the phone has:

- **8 GB or more:** all three files, about 1.6 GB to download. Recognition
  uses the full model, with its rescoring files.
- **4 GB up to 8 GB:** `voices-hindi-hd.zip` and `speech-hindi-hd.zip`, about
  350 MB. Recognition uses the model without its rescoring files.
- **Less than 4 GB:** no Hindi HD. The phone keeps the standard Hindi
  recognition from `../v1/`, and Settings says so.

On 265 read Hindi sentences (FLEURS), the standard model got 16.9% of words
wrong, the lighter Hindi HD model 14.3%, and the full one 12.5%.

## Files

| File | What it is |
| --- | --- |
| `voices-hindi-hd.zip` | The voices file with the two extra Indian voices (Kokoro hf_beta and hm_psi) beside the six of `v1` |
| `speech-hindi-hd.zip.001` to `.004` | Hindi speech recognition: the big Vosk Hindi model without its rescoring files |
| `speech-hindi-hd-rescore.zip.001` to `.013` | The model's rescoring files, for phones with 8 GB of memory |
| `manifest.json` | Every file's size, unzipped size, number of entries and SHA-256, and every piece's |

A zip larger than GitHub's 100 MB limit is cut into pieces of 95,000,000
bytes: `<name>.zip.001`, `.002` and so on. Joined in order (`cat
speech-hindi-hd.zip.* > speech-hindi-hd.zip`), they give the zip whose
SHA-256 the manifest lists. The app checks each piece against the manifest it
carries before it keeps it, and unzips the pieces only once all of them match.
None of these files uses Git LFS. A pack that changes gets a new folder
(`hindi-hd-v2`), so these files never change.

## Licences

All parts are open source, and each zip carries its licence and a `NOTICE.txt`:

- Vosk `vosk-model-hi-0.22` (Alpha Cephei), Apache-2.0, from
  [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models)
  (upstream zip SHA-256
  `eaf9bdbd703c79e957f7ea893502e12b336f98d7cd420138bcb224b36ccf86ac`), split
  into the model and its rescoring files with nothing changed.
- Kokoro-82M voices (hexgrad), Apache-2.0, from the int8 export in
  [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0).

The recordings and words never leave the phone.
