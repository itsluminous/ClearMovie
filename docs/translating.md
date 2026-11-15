# Translating ClearMovie

ClearMovie ships in **English**, **Hindi** and **Hinglish** (Hindi written in Latin
letters). Every user-visible string lives in plain Android resource XML under
`app/src/main/res/values/`. A translation is a copy of those files in a sibling
directory named for the language. No code changes are needed, and nothing is
downloaded at runtime: a translation ships inside the APK like everything else.

**How to submit: open a pull request.** Fork the repository, add or edit your
`values-<code>/` directory, and open a PR, so the checks below run in CI before
anything ships. GitHub does not accept `.xml` files as issue attachments.

## 1. The directories

Directory names use Android's *resource qualifier* format, not a bare language name:

| Language | Directory |
| --- | --- |
| English (default, the source of truth) | `values` |
| Hindi (shipped) | `values-hi` |
| Hinglish (shipped) | `values-b+hi+Latn` (BCP 47 form, `b+` prefix) |
| German | `values-de` |
| Brazilian Portuguese | `values-pt-rBR` (region prefixed with `r`) |
| Serbian in Latin script | `values-b+sr+Latn` |

**Hinglish** is Hindi in Latin script, written the way people type it in chats:
English UI nouns on Hindi grammar ("Video export karein"), not a respelling of
`values-hi`. A script variant has no two-letter code, so its directory uses the BCP 47
`b+<language>+<Script>` form, `values-b+hi+Latn` for the `hi-Latn` tag. The style
rules (which words stay English, fixed spellings, the polite *aap* register, no
Devanagari characters) are summarised at the top of its `strings.xml`.

The English strings are split by *whether they are translated*, not by feature. There
are three files under `app/src/main/res/values/`, and you copy two of them:

```
strings.xml              everything the user sees in the app - copy this
strings_platform.xml     text shown by Android itself (notification titles,
                         the "Share to ClearMovie" label) - copy this too
strings_notranslate.xml  the product name and URLs, every one marked
                         translatable="false" - do NOT copy this
```

Keep the same `name="..."` attributes; only the text between the tags changes. Never
rename a string or invent a new name: Android Lint's `ExtraTranslation` check fails the
build for a name that doesn't exist in English. File names inside your directory don't
matter, because Android resolves each string by its `name`.

## 2. Plurals

The English `<plurals>` declare only `one` and `other`. **Your language may need more**:
Android picks the item by your language's
[CLDR plural rules](https://www.unicode.org/cldr/charts/latest/supplemental/language_plural_rules.html).
Hindi, like English, uses `one` and `other`:

```xml
<!-- English (values/strings.xml) -->
<plurals name="share_items_ready">
    <item quantity="one">%1$d photo or video ready</item>
    <item quantity="other">%1$d photos and videos ready</item>
</plurals>

<!-- Hindi (values-hi/strings.xml) -->
<plurals name="share_items_ready">
    <item quantity="one">%1$d फ़ोटो या वीडियो तैयार</item>
    <item quantity="other">%1$d फ़ोटो और वीडियो तैयार</item>
</plurals>
```

Russian and Polish use `one`, `few`, `many` and `other`; Japanese uses only `other`.

## 3. Placeholders

Placeholders such as `%1$s` and `%2$d` are filled in by the app. **Keep every one, with
its number and its letter**, and reorder them freely to suit your grammar:

```xml
<!-- English -->
<string name="export_progress">%1$d%% done, about %2$s left</string>
<!-- Hindi: the order changes, nothing is lost -->
<string name="export_progress">लगभग %2$s बाकी, %1$d%% पूरा</string>
```

A missing, renumbered or changed placeholder crashes the app when that string is shown,
and the build checks for it. A literal percent sign is `%%`. Apostrophes and quotes
must be escaped (`\'`, `\"`).

ClearMovie never joins strings together in code, so every sentence is complete in one
string. Translate whole sentences, not fragments.

## 4. What not to translate

- Everything in `strings_notranslate.xml` (the app name and URLs).
- Brand names inside translatable strings (*ClearMovie*, *GitHub*), unless
  transliterating them is the local convention.
- The `name="..."` attributes and the placeholders.

## 5. All three shipped languages must be complete

Unlike some projects, ClearMovie's Lint configuration (`app/lint.xml`) treats a
**missing translation as an error**, so English, Hindi and Hinglish always stay complete.
A unit test also checks that every key in `values/` exists in `values-hi/` and
`values-b+hi+Latn/` with the same placeholders. When you add a string in English, add it
in all three in the same change. A **new** language is merged once it is complete. A
partial one can live on a branch until then.

## 6. Adding a new language

1. Create `app/src/main/res/values-<code>/`, using the directory format above.
2. Copy `values/strings.xml` and `values/strings_platform.xml` into it, and translate
   the text.
3. Supply the plural quantities your language needs.
4. That's all the registration there is. The build generates Android's locale list
   from the `values-<code>/` directories (`generateLocaleConfig` in
   `app/build.gradle.kts`, with English named in `app/src/main/res/resources.properties`),
   so the language appears in *Settings → Apps → ClearMovie → Language* on Android 13+.
   Add it to the in-app picker by adding its tag to the `supported_language_tags`
   array in `strings_notranslate.xml`. The picker shows each language in its own
   script, read from your translation's `language_name_native` string.
5. Run the checks exactly as CI will:
   ```bash
   ./gradlew ktlintCheck lintDebug testDebugUnitTest
   ```
6. If the script is not Latin or Devanagari, say so in the PR, and a matching Noto font
   is added for captions.

## 7. Testing a translation on a phone

Build and install the debug APK (see [building.md](building.md)), then pick the language:

- **In the app:** *Settings → Language*. This works on every supported Android
  version (10+), and is the only way to choose Hinglish on most phones, because
  Android rarely offers Hinglish as a system language.
- **Per-app language** (Android 13+): *Settings → Apps → ClearMovie → Language*.
- **Device language:** *Settings → System → Languages*.

Open every screen you translated, in portrait and landscape, and with a large font
size. Button labels may wrap onto two lines but must never be cut off, and the bottom
toolbar scrolls sideways when the labels are long.

## Writing style

- Write for someone who has never edited a video. Use short words and verbs on buttons
  ("Add text", "Export"), and no technical terms (codec, bitrate, fps, layer).
- Describe stacking in plain words: "Show on top", "Bring forward", "Send backward",
  "Show at bottom".
- Error messages say what to do next ("Tap Replace to choose it again").

## Fonts

The UI and captions bundle **Noto Sans** and **Noto Sans Devanagari** (SIL Open Font
License, with licence files next to the fonts), so Hindi renders the same on every
phone.
