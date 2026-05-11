# Build and Deploy

## Prerequisites

- Android Studio (any recent version — Hedgehog or later recommended).
- Android SDK 34 installed (compile SDK).
- A physical or virtual Android device running Android 8.0+ (API 26).
- For OsmAnd plugin testing: OsmAnd installed on the same device.
- For Garmin export testing: garmiand installed on the same device.

## Build

Open `g:\code\MapsCreator\` in Android Studio. Gradle sync runs automatically.

### Debug APK (`:app`)
`Build → Build Bundle(s) / APK(s) → Build APK(s)`
or via terminal:
```powershell
cd g:\code\MapsCreator
.\gradlew :app:assembleDebug
```
Output: `app\build\outputs\apk\debug\app-debug.apk`

### Debug APK (`:plugin`)
```powershell
.\gradlew :plugin:assembleDebug
```
Output: `plugin\build\outputs\apk\debug\plugin-debug.apk`

### Release build
```powershell
.\gradlew :app:assembleRelease
.\gradlew :plugin:assembleRelease
```
Requires a signing keystore configured in `app/build.gradle.kts`.

## Install and Run

### Standalone app
```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
```
Or use Android Studio's **Run** button.

### OsmAnd plugin
Install the plugin APK separately:
```powershell
& $adb install -r plugin\build\outputs\apk\debug\plugin-debug.apk
```
Then in OsmAnd: `Menu → Plugins` → enable **MapsCreator for OsmAnd**.

## End-to-end Smoke Tests

### Test 1 — Download OSM tiles for a route
1. Install app.
2. Open MapsCreator → **+** → **GPX** → pick any `.gpx` file.
3. Select OSM source only, zoom 14–14.
4. Confirm → verify notification appears with progress.
5. After finish: area card shows tile count > 0.

### Test 2 — Cache hit (no re-download)
1. Tap **Update** on the area from Test 1 → dialog says "Скачать новых: 0".
2. Tap **Только новые** → `DownloadService` starts and finishes immediately (all tiles cached).
3. Check logcat: `TileDownloader` should show 0 downloaded, all skipped.

### Test 3 — OsmAnd plugin Intent
1. Install both `:app` and `:plugin`.
2. In OsmAnd: plan a route → plugin action → MapsCreator opens with route pre-filled.

### Test 4 — GMND export
1. Download ≤20 OSM tiles for a small area (zoom 14).
2. Tap **Export to Garmin**.
3. Check that a `.gmnd` file appears in `<externalFilesDir>/exports/`.
4. Open in hex editor: first 4 bytes = `47 4D 4E 44` ("GMND"), byte 4 = `01` (version).

## Logcat Filters

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb logcat -v time TileDownloader:I DownloadService:I MBTilesStore:I GmndExporter:I *:S
```

## Notes

- `MBTilesStore` writes to `getExternalFilesDir(null)` — no `WRITE_EXTERNAL_STORAGE` permission
  needed on API 29+. Files are in `Android/data/com.mapscreator/files/` on the device.
- The Gradle wrapper (`gradlew` / `gradlew.bat`) is included in the repo. No need to install
  Gradle manually. If the scripts are missing, regenerate them with a local Gradle installation:
  `gradle wrapper --gradle-version 8.6` (the version declared in `gradle-wrapper.properties`).
- `local.properties` is **not** committed to the repo. Create it manually pointing to the local SDK:
  ```
  sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
  ```
- `gradle.properties` must exist and contain `android.useAndroidX=true` (AndroidX dependencies
  will not resolve without it). A minimal working file:
  ```properties
  android.useAndroidX=true
  org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
  kotlin.code.style=official
  ```
- OSM tile server has rate limits. For bulk test downloads, prefer ArcGIS or a local tile cache.
