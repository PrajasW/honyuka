# Honyuka v0.1.0

**📦 Download the latest build here: [Honyuka.apk](./Honyuka.apk)**

`Honyuka` is a standalone Android translator companion for the existing manga reader storage layout.

## Translation Comparison

Here is a visual comparison of the translation quality:

| Original | ML Kit Translation | Gemini Translation |
| :---: | :---: | :---: |
| <!-- ![Original Image](path_to_original.png) --> <br> *Original* | <!-- ![ML Kit Image](path_to_mlkit.png) --> <br> *ML Kit* | <!-- ![Gemini Image](path_to_gemini.png) --> <br> *Gemini* |

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
