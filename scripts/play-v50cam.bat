@echo off
setlocal
REM Optional latency check without OBS. Run start-v50cam.bat first.
REM Close the video window to stop.
REM
REM FRAMERATE is the phone fps plus 2. start-v50cam.bat defaults FPS to 30,
REM so this is 32. If you set FPS=60 there, set FRAMERATE=62 here.
REM Raw H.264 and MJPEG have no timestamps. ffplay otherwise assumes 25 fps
REM and the picture falls behind for as long as you leave it running.
REM CODEC must match start-v50cam.bat: mjpeg or h264.
REM MUX must match too: lookahead (default), raw, or mpjpeg. Ignored for h264.
set CODEC=mjpeg
set MUX=lookahead
set FRAMERATE=32
where ffplay >nul 2>&1
if errorlevel 1 goto no_ffplay
echo Playing tcp://127.0.0.1:27183 at framerate %FRAMERATE% (%CODEC% %MUX%)
echo Close the video window to stop.
if /i "%CODEC%"=="h264" goto play_h264
if /i "%MUX%"=="mpjpeg" goto play_mpjpeg
ffplay -fflags nobuffer -flags low_delay -probesize 32 -analyzeduration 0 -framedrop -sync ext -f mjpeg -framerate %FRAMERATE% -i tcp://127.0.0.1:27183
goto play_done
:play_mpjpeg
ffplay -fflags nobuffer -flags low_delay -probesize 32 -analyzeduration 0 -framedrop -sync ext -f mpjpeg -framerate %FRAMERATE% -i tcp://127.0.0.1:27183
goto play_done
:play_h264
ffplay -fflags nobuffer -flags low_delay -probesize 32 -analyzeduration 0 -framedrop -sync ext -f h264 -framerate %FRAMERATE% -i tcp://127.0.0.1:27183
:play_done
set "RC=%ERRORLEVEL%"
echo.
pause
exit /b %RC%

:no_ffplay
echo ffplay was not found.
echo Install FFmpeg (the full build that includes ffplay) and add it to PATH.
echo OBS does not need this file. Use a Media Source instead.
echo.
pause
exit /b 1
