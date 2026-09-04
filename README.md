# LumaGrade

LumaGrade is a native, offline Android photo editor inspired by the fast preset workflow of professional desktop editors. It uses its own name, interface, and processing code; it is not affiliated with Adobe Lightroom.

## What works

- Import JPG, PNG, HEIC, and other formats supported by Android's image decoder
- 16 handcrafted presets for portraits, film, night, nature, cinematic color, and black-and-white
- Import complete ZIP packs, individual Lightroom/Camera Raw `.xmp` files, or LumaGrade `.json` packs
- Imported presets are stored on the phone and get live thumbnails from the current photo
- Adjustable preset strength
- Light controls: exposure, contrast, highlights, shadows, whites, and blacks
- Color controls: temperature, tint, vibrance, and saturation
- Effects: fade, vignette, deterministic film grain, and sharpening
- Press-and-hold before/after comparison
- High-quality JPEG export at 96% quality
- Completely on-device; no account, analytics, or storage permission
- Responsive preview rendering with cancellation while dragging controls

## Run it in Android Studio

1. Install a current Android Studio version with Android SDK 35 and Java 17.
2. Open this `LumaGrade` folder as a project.
3. Let Gradle sync and install any SDK packages Android Studio requests.
4. Run the `app` configuration on an Android 9+ phone or emulator.

From Windows Terminal, you can also build with:

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

If you push this folder to GitHub, the included workflow builds the APK automatically. Download it from the workflow run's **Artifacts** section.

## How presets are built

Presets are combinations of real adjustment parameters, not transparent color overlays. The pipeline applies exposure, luminance-masked highlight/shadow recovery, black/white shaping, contrast, white balance, split toning, saturation, vibrance, fade, vignette, grain, and an unsharp pass. Preset intensity blends the fully graded result with the source image.

Add or tune looks in `app/src/main/java/com/lumagrade/app/editor/PresetCatalog.kt`.

## Import downloaded presets

1. Download a preset pack to the phone. Leave it zipped, or extract its `.xmp` files.
2. In LumaGrade, tap **Import ZIP / XMP** in the Presets tab. You can also import before opening a photo.
3. Select one ZIP or up to 50 XMP/JSON files. Imported looks appear after the built-in presets and remain after restarting the app.
4. Select an imported preset and tap **Remove** if you no longer want it.

The importer translates global exposure and tone settings, master tone curves, the eight-channel color mixer, white balance, split toning/color grading, vignette, grain, clarity, and sharpening. Adobe camera profiles, LUT profiles, local masks, lens corrections, and AI settings are proprietary or engine-specific and are ignored. For that reason, a complex XMP preset can look somewhat different from Lightroom. Legacy `.lrtemplate` and DNG-based mobile presets are not supported; download the XMP version when one is offered.

For native JSON packs, use normalized values from `-1.0` to `1.0` for signed controls and `0.0` to `1.0` for effects:

```json
{
  "presets": [
    {
      "name": "My Warm Film",
      "adjustments": {
        "contrast": -0.08,
        "temperature": 0.14,
        "vibrance": 0.10,
        "fade": 0.12,
        "grain": 0.16
      }
    }
  ]
}
```

## Honest limitations

This is a strong preset editor MVP, not a full Lightroom replacement. It does not include RAW development, selective masks, healing, geometry correction, photo cataloging, or cloud sync. To keep memory use sane across normal phones, the long edge of an exported image is capped at 3072 pixels. Export is JPEG only.

## Privacy

The app uses Android's system document picker. Photos are decoded, edited, and saved locally on the device. It declares no network or broad media-library permission.

## License

MIT — see `LICENSE`.
