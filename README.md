# Trip Explorer – phone app (Android)

Writes the `trips/` folders on the phone: GPS recordings as GPX, and POIs as folders with text files,
photos and audio notes. No map, no Google services, no Hilt. The PC app
([trip-explorer-pc](https://github.com/lotanbar/trip-explorer-pc)) shows the folders on a map; the spec
lives there in `docs/Trip_Explorer_Spec.docx`.

## Build and install

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17 and the Android SDK (compileSdk 37, minSdk 31). The APK is debug-signed; the app is not published.

First launch asks for "All files access" (the app writes `/sdcard/trips`). Recording asks for precise
location and notifications, audio notes for the microphone. The camera is the phone's own camera app.

## Layout

| Path | Purpose |
| --- | --- |
| `data/Names.kt` | Name rules (Windows-safe folder names, `recordings` reserved, duplicates) |
| `data/Trips.kt` | The `trips/` root, trip folders, media scanner, remembered current trip |
| `data/Gpx.kt` | GPX writer: always-valid file, segment per pause, resume of unfinished files, file names |
| `data/Poi.kt` | POI folders: coordinates / datetime / description / group file / numbered media |
| `data/Groups.kt` | The fixed group list (icons copied from the PC app as vector drawables) |
| `service/RecordingService.kt` | Foreground service: GPS provider only, 1 fix/s, 10 s batching, saved every 10 s |
| `ui/main/MainScreen.kt` | Trip picker, Start/Pause/Stop, Add POI, recordings list, incomplete-recording dialog |
| `ui/poi/AddPoiScreen.kt` | GPS fix → camera → "Another photo / Done" → form with audio notes |
| `ui/poi/PoiListScreen.kt`, `PoiScreen.kt` | All POIs by trip; edit name / description / group, add media, Show in map |
| `ui/poi/MediaPreviewScreen.kt` | Zoomable photos and audio player (copied from the reference app) |

Copied from the reference app (mapping-solution): theme, storage permission screen, media preview, and the
recording service basics. Everything else is new.

## Behaviour notes

- Nothing is ever deleted. Clearing a POI's group renames `group-x.txt` to `.group-x.txt` (hidden; both apps read it as "No group").
- A recording killed without Stop stays `… - recording.gpx`; on the next launch the app offers Resume / Finish / Later. If the OS restarts the service, it resumes into the same file by itself.
- Media taken before a POI has a name is kept in the app cache and moved into `media/` on save; the Add POI screen survives the camera killing the app.
