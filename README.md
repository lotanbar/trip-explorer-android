# Trip Explorer – phone app (Android)

Writes the `trips/` folders on the phone: GPS recordings as GPX, and POIs as folders with text files,
photos and audio notes. Reads the plans the PC app saves in `trips/plans/` and drives them stop by stop with Waze. No map, no Google services, no Hilt. The PC app
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

Google Drive sync needs `google_oauth.json` at the repo root (the Desktop OAuth client shared with the PC app, kept
out of git): `{"client_id": "...", "client_secret": "..."}`. Without it the build works and Drive sync says so.

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
| `sync/SyncPlan.kt`, `sync/SyncEngine.kt` | Drive sync: what to do per path (same rules as the PC app), and the loop that does it |
| `sync/DriveApi.kt` | Drive REST calls and the browser sign-in (loopback + PKCE), plain HttpURLConnection |
| `sync/Sync.kt`, `ui/sync/DriveScreen.kt` | The Sync switch, the foreground service, sign-in and the Drive folder picker |
| `ui/poi/MediaPreviewScreen.kt` | Zoomable photos and audio player (copied from the reference app) |

Copied from the reference app (mapping-solution): theme, storage permission screen, media preview, and the
recording service basics. Everything else is new.

## Behaviour notes

- Drive sync (switch on the main screen): while on, `trips/` syncs live with the picked Drive folder on any network,
  also in the background; while off, nothing is sent. Drive is the truth, the newest change wins, a removal on one
  side is mirrored (on Drive to the trash). Live test on the PC: `LIVE=1 ./gradlew testDebugUnitTest --tests '*SyncLiveTest*'`.
- Nothing is ever deleted by hand in the app. Clearing a POI's group renames `group-x.txt` to `.group-x.txt` (hidden; both apps read it as "No group").
- A recording killed without Stop stays `… - recording.gpx`; on the next launch the app offers Resume / Finish / Later. If the OS restarts the service, it resumes into the same file by itself.
- Media taken before a POI has a name is kept in the app cache and moved into `media/` on save; the Add POI screen survives the camera killing the app.
