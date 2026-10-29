# V50Cam

Turn an Android phone into a **low-latency, constant-delay camera for OBS** over USB (adb). Built for drum streaming, where a camera that drifts out of sync with the audio is useless.

Most phone-webcam apps showed 50–750 ms of *variable* lag. V50Cam keeps only the newest frame: if the PC falls behind, frames are dropped instead of queued, so the delay stays flat for hours.

## Results (LG V50 ThinQ, Android 12, 1080p30, glass-to-glass)

| Pipeline | Delay |
|---|---|
| H.264 into an OBS Media Source | ~565 ms |
| MJPEG (q70) with lookahead mux | **~165 ms** (145–200 ms) |

About 76 ms of that is the phone's own camera pipeline.

### Why H.264 was slow

OBS's media playback code (`shared/media-playback/decode.c`) sets the decoder's `thread_count` to 0 (auto), so FFmpeg frame-threads H.264 decoding. On a 12-thread CPU that holds roughly a dozen frames (~400 ms) before anything is shown, and no OBS setting changes it. FFmpeg's MJPEG decoder doesn't frame-thread. The `lookahead` mux also shifts each JPEG's start marker so FFmpeg emits a frame as soon as it arrives, instead of waiting for the next one.

## How it works

Camera2 → JPEG (or MediaCodec H.264) on the phone → one-slot TCP server on phone port 27183 → `adb forward tcp:27183 tcp:27183` → OBS Media Source reading `tcp://127.0.0.1:27183`. Video never leaves the computer.

## Requirements

- Android 8.0+ phone (minSdk 26, tested on Android 12) with USB debugging enabled
- [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) (`adb`) on the PC
- OBS Studio (scripts are Windows `.bat`; the adb/OBS steps work anywhere)
- To build: JDK 17 and the Android SDK

## Build

```
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

## Install

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the app once and allow Camera (and notifications, for the Stop button).

## Run

Run `scripts/start-v50cam.bat`. It finds `adb` (PATH, a `scrcpy` folder next to it, or the `ADB=` variable at the top), sets up the forward, and launches the app with the settings at the top of the file. `scripts/stop-v50cam.bat` stops it. `scripts/play-v50cam.bat` previews the stream in ffplay without OBS. More detail is in [`scripts/README.md`](scripts/README.md).

Manual launch:

```
adb forward tcp:27183 tcp:27183
adb shell am start -n com.nick.v50cam/.MainActivity --ei res 1080 --ei fps 30 --es codec mjpeg --ei quality 70 --es mux lookahead --ez autostart true
```

## OBS Media Source settings

Sources → + → Media Source, uncheck **Local File**:

| Setting | Value |
|---|---|
| Input | `tcp://127.0.0.1:27183` |
| Input Format | `mjpeg` (`h264` if using H.264, `mpjpeg` for that mux) |
| Network Buffering | 0 MB |
| Use hardware decoding | Off |
| Reconnect | On, delay 1 s |
| FFmpeg Options | `fflags=nobuffer probesize=32 analyzeduration=0 framerate=32` |

The stream has no timestamps, so set `framerate` to the phone's fps + 2 (32 for 30 fps, 62 for 60 fps). Otherwise FFmpeg assumes 25 fps and the delay grows.

## Options (intent extras / script variables)

| Extra | Values | Default |
|---|---|---|
| `codec` | `mjpeg`, `h264` | `mjpeg` |
| `mux` (MJPEG only) | `lookahead`, `raw`, `mpjpeg` | `lookahead` |
| `quality` | JPEG quality 1–100 | 80 in app, 70 in start script |
| `res` | `1080`, `720` | `1080` |
| `fps` | `30`, `60` | `30` |
| `bitrate` | H.264 Mbps (8, 10, 12) | `10` |
| `camera` | camera id shown in the app | main back camera |

`raw` is plain concatenated JPEGs (FFmpeg holds each until the next arrives). `mpjpeg` gets a fixed 25 fps timestamp in OBS, so it isn't recommended. Advanced MJPEG A/B extras: `template record|preview|zsl`, `edge` / `nr` `off|fast|hq|default`, `maxexp N` (µs, 0 = auto exposure), `jpeg sw|hw`.

## Measuring latency

1. Open `latency/clock.html` in a browser on the PC; it shows a millisecond counter.
2. Point the phone at the screen and put the OBS preview next to the clock.
3. Take screenshots (`latency/shot.ps1 -n 5` on Windows saves them to `latency/shots/`). Delay = live clock − clock shown in the preview. Average several shots; each one can be off by up to one frame.

Then set the matching **Sync Offset** on your audio sources (Advanced Audio Properties), and a Render Delay filter on any other cameras, so everything lines up.

## Notes

- Android 12 shows a green camera dot while streaming; that's expected.
- Built with Grok Build (grok-4.7).

## License

MIT, see [LICENSE](LICENSE).
