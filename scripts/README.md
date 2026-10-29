# V50Cam

This streams the LG V50 camera over the USB cable into OBS. The video stays on this computer. Nothing is sent to the internet.

The phone sends each frame as soon as it exists. If the laptop falls behind, the phone throws that frame away and sends a fresh full picture, instead of letting the delay pile up. You may see a tiny glitch. The delay does not grow.

OBS has to be told the frame rate. The video has no timestamps. OBS otherwise assumes 25 fps and the delay grows for the whole session. Use 62 when the phone is at 60 fps, and 32 when it is at 30. The OBS section below has the exact line.

Build the APK (see the main README) and copy it into this folder as `v50cam.apk`. You need `v50cam.apk` and the `.bat` files side by side.

## Once per phone

1. On the phone: Settings, About phone, tap Build number seven times. That unlocks Developer options.
2. Settings, Developer options, turn on USB debugging.
3. Plug the phone into the laptop with the USB cable that came with the phone. Unlock the phone and tap Allow on the "Allow USB debugging?" prompt.
4. On the laptop, install [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) (that gives you `adb`) and make sure `adb` works in a Command Prompt. If you already use scrcpy, its folder contains `adb.exe`. You can leave that folder named `scrcpy` next to these bat files, or set the `ADB=` line at the top of `start-v50cam.bat` to the full path of `adb.exe`.

## Each session

### a. Install the app

In this folder, in a Command Prompt:

```
adb install -r v50cam.apk
```

`-r` means "replace the copy that is already there", so the same command is safe to run again later.

### b. Allow the camera

Open V50Cam on the phone once. Allow Camera, and allow notifications if Android asks. Notifications are only so the Stop button can sit in the shade. The stream still runs if you say no to notifications.

You can also let `start-v50cam.bat` open the app and answer the questions then.

### c. Start the stream

Double-click `start-v50cam.bat`.

It checks that the phone is plugged in, opens a private tunnel from the laptop's port 27183 to the phone, and starts the camera. The defaults at the top of the file are 1080p, 60 fps, and 10 Mbps. Change `RES`, `FPS`, `BITRATE`, `CODEC`, `MUX`, or `QUALITY` there if you want 720p, 30 fps, 8 / 12 Mbps, or `h264`. `CODEC` defaults to `mjpeg`. `MUX` defaults to `lookahead` (OBS Input Format `mjpeg`). `raw` is the same format without the SOI shift. `mpjpeg` is not recommended: OBS timestamps it at a fixed 25 fps, so lag grows. `QUALITY` defaults to 70. Leave `CAMERA` blank for the main back camera. The app lists every lens with its id, which way it faces, and its focal length. Put that id in `CAMERA` if you want a different lens.

If the phone cannot lock 60 fps, it does not guess. It tells you on screen and uses 30 fps. High-speed camera mode is not used.

Leave V50Cam open on the phone. The screen stays on while a stream is running and that page is in front. There is no picture on the phone. Frame the shot in OBS. That is deliberate: a preview on the phone is one more thing that can stall the stream.

On Android 12 the status bar shows a green dot while the camera is on. That dot is the system privacy indicator. It stays up for the whole stream. That is expected. It does not mean another app is recording.

To stop, double-click `stop-v50cam.bat`, or tap Stop in the app, or tap Stop on the notification.

### d. OBS

Use OBS Studio 30 or newer.

1. Under Sources, click + and choose **Media Source**. On newer OBS the same item may just be named **Media**.
2. Uncheck **Local File**. The network fields appear when that box is off.
3. Set the fields like this:

| Field | Value |
| --- | --- |
| Input | `tcp://127.0.0.1:27183` |
| Input Format | `mjpeg` (`mpjpeg` only if `MUX=mpjpeg`, `h264` if `CODEC=h264`) |
| Network Buffering | `0 MB` (the slider goes from 0 to 16; the default is 2, so move it to 0) |
| Reconnect Delay | `1` (seconds; the control may show `1 S`. The lowest it allows is 1. Default is 10.) |
| Use hardware decoding when available | unchecked for mjpeg (checked is fine for h264) |
| FFmpeg Options | `fflags=nobuffer probesize=32 analyzeduration=0 framerate=62` |

FFmpeg Options is the box OBS added in version 30. Its own hint says the text is `option=value` pairs separated by spaces, with no dashes and no commas. Example from OBS: `rtsp_transport=tcp rtsp_flags=prefer_tcp`. Paste the line above the same way.

`framerate` is the setting that keeps the delay from growing. The phone sends `lookahead` MJPEG by default (OBS Input Format `mjpeg`), the same format when `MUX=raw`, multipart when `MUX=mpjpeg`, or raw H.264 if `CODEC=h264`. Do not use `mpjpeg` in OBS: that demuxer has no framerate option and stamps every frame at 1/25 s, so a 30 fps stream falls behind. Those bytes contain no timestamps. FFmpeg invents one timestamp per frame from `framerate`, and that option defaults to 25. OBS then shows each frame when its timestamp says it is due. At 25, OBS spaces frames 40 ms apart. A 60 fps phone sends one every 16.7 ms, and a 30 fps phone every 33 ms. Frames arrive faster than OBS plays them, the queue grows, and over a 1–3 hour set the picture falls further behind the drums.

Set `framerate` a little faster than the phone, not equal to it:

- Phone at 60 fps (the default in `start-v50cam.bat` and in the app): `framerate=62`
- Phone at 30 fps: `framerate=32`

OBS then wants the next frame slightly before the phone sends it, so it shows each frame when it arrives and does not build a queue. An exact match (60 or 30) still drifts when the phone runs a hair slow, and that drift adds up over a long set. Two frames per second is enough margin. It does not drop frames by itself. If you change `FPS` at the top of `start-v50cam.bat`, change this number to that FPS plus 2. The start bat prints the line that matches its `FPS`.

If the phone's encoder writes a frame rate into the stream, FFmpeg uses that rate instead of this option. A correct in-band rate does not grow the delay either. Set `framerate` anyway. The stream that has no in-band rate is the one that creeps, and FFmpeg's default for that case is 25.

`fflags`, `probesize`, and `analyzeduration` are read by the part of OBS that receives the stream, so they work on OBS 30. `probesize` and `analyzeduration` only limit how long OBS studies the stream before the first picture. They are not a playback buffer.

`flags=low_delay` is not in the line. It is a decoder flag. OBS passes this box only to the receiver (`avformat_open_input`), and it opens the decoder with no options from the box, so `flags=low_delay` is ignored. Do not add it back expecting it to cut delay. When Network Buffering is 0, OBS itself turns on nobuffer (`AVFMT_FLAG_NOBUFFER`). `fflags=nobuffer` in the box is the same switch. Leaving it in does not hurt.

4. Leave **Restart playback when source becomes active** checked. That is the default. When the source is on screen, OBS connects again and gets a fresh picture.
5. Leave **Close file when inactive** unchecked. That is the default. Checking it drops the connection whenever the source is hidden, and OBS warns that bringing it back has a startup delay. Leave it off so the connection stays up while you perform.
6. If you see **Seekable**, leave it unchecked. This is a live stream, not a file. Seeking makes OBS buffer.
7. Leave **YUV Color Range** on Auto.
8. Click OK.

If the picture is on its side, right-click the source, choose Transform, and rotate it 90 degrees. The phone sends the sensor's own orientation. Rotating on the phone would mean extra work on every frame, and the V50 gets hot enough already.

`play-v50cam.bat` is an optional check that does not use OBS. It needs [FFmpeg](https://www.gyan.dev/ffmpeg/builds/) installed, including `ffplay`. Run the start bat first, then this one. The flags in that file are real ffplay options (`-fflags nobuffer`, `-flags low_delay`, `-probesize 32`, `-analyzeduration 0`, `-framedrop`, `-sync ext`, `-f mjpeg` for `lookahead` and `raw` (or `-f mpjpeg` when `MUX=mpjpeg`, or `-f h264` when `CODEC=h264`), `-framerate`). `-framerate` has to come before `-i`. It is 62 by default, for a 60 fps phone. If `start-v50cam.bat` has `FPS=30`, set `FRAMERATE=32` at the top of the play bat. Same reason as OBS: without it, ffplay assumes 25 fps and plays slower than the phone sends. `-flags low_delay` works here because ffplay gives it to the decoder. The OBS box does not.

### e. Line the picture up with the drums

Do this once.

Put a stopwatch on the laptop screen and point the phone at it, or tap a drum while OBS is recording. Compare the moment you hear with the moment you see.

In the Audio Mixer, right-click your microphone and choose **Advanced Audio Properties**. **Sync Offset** is in milliseconds. A positive number delays that audio. If the picture is 80 milliseconds late, set the mic's Sync Offset to 80 so the hit lines up with the picture.

The offset stays put. You should not have to chase it during a set. If it slowly gets worse, that is a stall, not normal behavior. See below.

### f. When something is wrong

**Camera access is turned off in Quick Settings**

Android 12 has a Camera access switch in Quick Settings. Swipe down from the top of the screen twice. If that switch is off, the phone blocks the camera. V50Cam may open the camera and get a black picture, or the camera disconnects and never sends a frame. The app then says "Camera access is turned off in Quick Settings". Turn Camera access on, then run `start-v50cam.bat` again.

The green dot is a different thing. The green dot means the camera is in use. The Quick Settings switch is what blocks the camera.

**The app says camera permission was denied**

Tap Start and allow Camera. If the message says Android will not ask again, open Settings, Apps, V50Cam, Permissions, and allow Camera. Saying no to notifications is fine. The stream still runs. The Stop button in the shade may be missing.

**Black picture**

- If the phone says "Camera access is turned off in Quick Settings", use the section above.
- The phone app should say Client: connected once OBS is listening. If it says not connected, OBS is not on the port yet.
- Input must be `tcp://127.0.0.1:27183` and Input Format must be `mjpeg` (or `mpjpeg` when `MUX=mpjpeg`, or `h264` when `CODEC=h264`). Local File must be unchecked.
- Start the bat before you click OK in OBS. If OBS connected too early, right-click the source and toggle the eye off and on, or open properties and click OK again. Reconnect Delay will also try again after a second.
- Run `start-v50cam.bat` again. That rebuilds the USB tunnel.

**"No device" or the bat says the phone is not ready**

- Use the cable that came with the phone. A charge-only cable will not work. Try another USB port on the laptop itself, not a hub.
- Unlock the phone and tap Allow on the debugging prompt.
- Unplug any second phone.
- If the bat says it cannot find adb, edit the `ADB=` line at the top.

**The picture freezes**

- Run `start-v50cam.bat` again.
- In OBS, toggle the Media Source off and on.
- The phone drops a late frame and waits for a fresh full picture (an IDR) instead of showing a broken frame. That is a short glitch, then motion again. The Dropped number on the phone counts those. A freeze that does not recover is OBS holding a dead connection. Toggle the source.

**The delay slowly gets longer**

- It should not. The phone keeps one frame in hand and no more. A slow laptop loses frames; it does not build a queue.
- The usual cause is OBS assuming 25 fps. FFmpeg Options must include `framerate=62` for a 60 fps phone, or `framerate=32` for 30. The table has the full line. Toggle the source after changing it so OBS reconnects clean.
- Network Buffering must be 0. That is what makes OBS turn its own buffer off.
- For MJPEG leave **Use hardware decoding when available** off. For H.264 it can be on; if the picture stutters only when it is checked, turn it off.

**The phone gets hot**

- The V50 will throttle if it cooks, and then the frame rate falls. Set `RES=720` and `FPS=30` at the top of the start bat and run it again.
- Take the case off. Keep the phone plugged in so it does not dim itself to save battery, but do not leave it in the sun on a fast charger.
- Do not run another camera app at the same time.

**USB**

- Cable that came with the phone, plugged straight into the laptop.
- If it drops every few minutes, try another port. A loose cable looks like a frozen stream.

**Firewall and antivirus**

- Windows Defender and the firewall are not in this path. OBS connects to `127.0.0.1`, which is this computer, through the USB tunnel adb already opened. Do not open a port. Do not add an inbound firewall rule.

**Focus and exposure hunting**

- On the phone, turn on AE lock and AF lock once the shot looks right. AE lock holds exposure. AF lock focuses once, then holds that distance so the lens does not hunt while you play.

**The app says 60 fps is not offered**

- That camera has no fixed `[60,60]` exposure range. The app will not pretend. Use 30 fps. The message on screen lists the ranges the camera actually has.
