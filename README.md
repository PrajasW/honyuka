# Honyuka v0.1.0

**📦 Download the latest build here: [Honyuka.apk](./Honyuka.apk)**

Honyuka (ほにゅか, mispronunciation of Honyakuya  = translator) is a standalone Android app that translates downloaded manga chapters and makes the translated output readable in Mihon (and its fork TachiyomiAT).

## Translation Comparison

Here is a visual comparison of the translation quality:

| Original | ML Kit Translation | Gemini Translation |
| :---: | :---: | :---: |
| <!-- ![Original Image](path_to_original.png) --> <br> *Original* | <!-- ![ML Kit Image](path_to_mlkit.png) --> <br> *ML Kit* | <!-- ![Gemini Image](path_to_gemini.png) --> <br> *Gemini* |

## How to Use

1. **Download Manga via Mihon:** First, use the [Mihon](https://mihon.app/) app to download the chapters of the manga you want to translate.
2. **Set the Storage Directory:** Open Honyuka and select the exact same base directory where Mihon saves your downloaded manga.
3. **Translate:** In Honyuka, browse the list to find the manga you downloaded, open it, and simply click the **Translate** button for the chapter.
4. **Read Translated Manga:** Once the translation process finishes, go back to Mihon and navigate to the **Local source** tab. You will find a new discoverable entry containing your fully translated manga chapter ready to be read!

## Settings

Honyuka offers several configuration options to customize your translation experience:

- **Storage Root Folder:** Choose the root directory where your Mihon manga downloads are located.
- **Source Language:** The original language of the manga (e.g., Japanese, Korean, Chinese).
- **Target Language:** The language you want to translate the manga into (e.g., English).
- **Translation Engine:** Choose between different translation providers:
  - **Gemini:** Cloud-based translation using Google's Gemini models for highly contextual and accurate translations. Requires an API Key.
  - **ML Kit:** On-device, offline translation provided by Google. Fast and private, but may have lower contextual quality.
  - **DeepL:** High-quality neural machine translation. Requires an API Key.
  - **On-device (Gemma):** Free, offline and private. Download **Gemma 4 E4B** (3.7 GB, closest to Gemini Flash; ~8 GB RAM phones) or **Gemma 4 E2B** (2.6 GB, faster; 6 GB RAM) in Settings → On-device model. Runs through Google's LiteRT-LM on GPU (falls back to CPU). Expect a few minutes per chapter; no API quota.
- **Gemini Model:** Defaults to `gemini-flash-lite-latest` (cheapest, largest free quota). Tap **Refresh models** to list every model your key can use, or type any model name.
- **API Keys:** Secure fields to enter your respective Gemini and DeepL API keys.
- **Theme:** Auto, ☀️ Washi (light) or 🌙 Yoru (dark).

This folder is now its own Android project. You can open `honyuka/` directly in Android Studio or build it from inside this folder without using the parent repo's Gradle setup.

## Expected Storage Root

Pick the same root folder the reader uses, with this structure:

```text
<root>/
  downloads/
    <source>/
      <manga>/
        <chapter-folder-or-cbz>
  translations/
```

`Honyuka` scans chapters from `downloads/` and writes reader-compatible sidecar JSON files into:

```text
translations/<source>/<manga>/<chapter>.json
```

It renders the translated pages into Mihon's Local Source:

```text
local/<manga name> (Honyuka)/
  ComicInfo.xml        # Series = "<manga name> (Honyuka)"
  cover.jpg            # first translated page, written once
  <chapter>.cbz        # rendered pages + ComicInfo.xml (title/number kept, Translator credited)
```

In Mihon, open **Browse → Local source** (pull to refresh) to read it. Original downloads are never modified, and in-progress downloads (`*_tmp`) are ignored.

### Keeping API usage low

- Only untranslated lines are sent; translating a chapter again sends nothing.
- Repeated lines are sent once, and lines seen in earlier chapters come from an on-device translation memory.
- Progress is saved after every request, so if the quota runs out you only resend what is left.
- Most chapters take a single Gemini request; model "thinking" is turned down to the minimum, since thinking tokens are billed.

## Current Feature Set

- Modern standalone Android app module: `:honyuka`
- Storage Access Framework root picker
- Scans downloaded manga chapter folders and `.cbz` archives
- OCR with ML Kit for Chinese, Japanese, Korean, and English
- On-device ML Kit translation output
- Smart text-block merge before translation
- Sidecar JSON output compatible with the existing reader overlay format

## Build

```powershell
cd honyuka
.\gradlew.bat assembleRelease ; .\gradlew.bat installRelease
```
