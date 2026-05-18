# Honyuka v0.1.0

**📦 Download the latest build here: [Honyuka.apk](./Honyuka.apk)**

`Honyuka` is a standalone Android translator companion for the existing manga reader storage layout.

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
- **Gemini Model:** Allows you to specify the exact Gemini model to use (e.g., `gemini-2.5-flash`).
- **API Keys:** Secure fields to enter your respective Gemini and DeepL API keys.

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

It also renders translated pages into a separate sibling chapter so a normal Mihon install can open the translated result directly without replacing the original chapter. The translated export is created as:

```text
downloads/<source>/<manga>/<chapter>_translated
downloads/<source>/<manga>/<chapter>_translated.cbz
```

Because regular Mihon does not create new chapter entries for arbitrary extra files under an online-source manga, Honyuka also exports a discoverable copy to the Local Source as:

```text
local/<manga name> (Honyuka)/<chapter>_translated.cbz
```

After refreshing the Local Source in Mihon, the translated chapter should appear there.

If needed, Honyuka can still keep an original backup alongside the manga's downloaded chapters under:

```text
downloads/<source>/<manga>/__honyuka_backup__/<chapter-or-cbz>
```

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
.\gradlew.bat assembleRelease
```
