# 4KCUT (Media3 + Jetpack Compose)

Features: pick video, split, delete clip, text with many fonts, 7 filter presets,
brightness/contrast/saturation/warmth grading, export 720p / 1080p / 2K / 4K.

## Build
1. Android Studio -> Open this folder -> wait for Gradle sync -> Run.
   (or push to GitHub: Actions tab -> "Build APK" -> download app-debug artifact)

## More fonts
Download .ttf files from fonts.google.com (free, open license) and copy them into
app/src/main/assets/fonts/ . They show up automatically in the Text tab.

## Notes
- Preview and export use the same effects list, so what you see is what you export.
- Text/filters apply to the whole video. Per-clip text timing is a next step.
- 4K export only works if your phone's hardware encoder supports it.

## App ka naam
app/src/main/res/values/strings.xml mein `app_name` badlo.

## Home screen
Dark + green design. Upar header mein apni photo ya video lagao (Change BG).
Video ho to sirf pehle 5 second loop hote hain.
