@echo off
setlocal
REM V50Cam starter. Double-click this after the phone is plugged in.
REM
REM RES is 1080 or 720. FPS is 60 or 30. BITRATE is 8, 10, or 12 (megabits).
REM If the phone cannot do 60 fps it falls back to 30 and says so on screen.
REM A hot phone is happier at RES=720 and FPS=30.
set RES=1080
set FPS=30
set BITRATE=10
REM CODEC is mjpeg (default) or h264. MJPEG is the low-delay path.
set CODEC=mjpeg
REM MUX is lookahead (default), raw, or mpjpeg. Ignored for h264.
REM lookahead and raw use OBS Input Format mjpeg. mpjpeg plays at 25 fps there.
set MUX=lookahead
REM JPEG quality, 1 to 100.
set QUALITY=70
REM This stream has no timestamps. FFmpeg assumes 25 fps unless told otherwise,
REM and the delay then grows. OBS and ffplay need the phone fps plus 2.
set /a FRAMERATE=FPS+2
REM Leave CAMERA blank for the main back camera, or set an id the app shows (for example 0).
set CAMERA=
REM If adb is not on PATH, set this to the full path of adb.exe and save the file.
set ADB=

set "ADB_CMD="
if not "%ADB%"=="" goto use_var
where adb >nul 2>&1
if not errorlevel 1 goto use_path
if exist "%~dp0scrcpy\adb.exe" goto use_next
if exist "%~dp0..\scrcpy\adb.exe" goto use_parent
echo Could not find adb.
echo.
echo Install Android platform-tools and add them to PATH,
echo or put a scrcpy folder next to this file so scrcpy\adb.exe exists,
echo or edit the ADB= line at the top of this file.
echo.
pause
exit /b 1

:use_var
set "ADB_CMD=%ADB%"
goto found

:use_path
set "ADB_CMD=adb"
goto found

:use_next
set "ADB_CMD=%~dp0scrcpy\adb.exe"
goto found

:use_parent
set "ADB_CMD=%~dp0..\scrcpy\adb.exe"
goto found

:found
echo Using adb: %ADB_CMD%
set "STATE="
for /f "delims=" %%s in ('"%ADB_CMD%" get-state 2^>^&1') do set "STATE=%%s"
if /i "%STATE%"=="device" goto device_ok
echo Phone is not ready. adb says: %STATE%
echo.
echo Unlock the phone, use the USB cable that came with it, and tap Allow
echo on the USB debugging prompt. If two phones are plugged in, unplug the extra one.
echo.
pause
exit /b 1

:device_ok
echo Phone is connected.
"%ADB_CMD%" forward --remove tcp:27183
"%ADB_CMD%" forward tcp:27183 tcp:27183
if errorlevel 1 goto forward_fail
echo USB forward is up: this PC's port 27183 goes to the phone's port 27183.

set "INPUT_FORMAT=h264"
if /i not "%CODEC%"=="h264" set "INPUT_FORMAT=mjpeg"
if /i not "%CODEC%"=="h264" if /i "%MUX%"=="mpjpeg" set "INPUT_FORMAT=mpjpeg"
set "MUX_ARG="
if /i "%CODEC%"=="mjpeg" set "MUX_ARG=--es mux %MUX%"

if "%CAMERA%"=="" goto start_default
echo Starting V50Cam: %RES%p, %FPS% fps, %BITRATE% Mbps, %CODEC% mux=%MUX% q=%QUALITY%, camera %CAMERA%
"%ADB_CMD%" shell am start -n com.nick.v50cam/.MainActivity --ei res %RES% --ei fps %FPS% --ei bitrate %BITRATE% --es codec %CODEC% --ei quality %QUALITY% %MUX_ARG% --es camera "%CAMERA%" --ez autostart true
goto started

:start_default
echo Starting V50Cam: %RES%p, %FPS% fps, %BITRATE% Mbps, %CODEC% mux=%MUX% q=%QUALITY%, main back camera
"%ADB_CMD%" shell am start -n com.nick.v50cam/.MainActivity --ei res %RES% --ei fps %FPS% --ei bitrate %BITRATE% --es codec %CODEC% --ei quality %QUALITY% %MUX_ARG% --ez autostart true

:started
if errorlevel 1 goto start_fail
echo.
echo Next steps:
echo   1. On the phone, allow Camera and notifications if it asks.
echo      Leave V50Cam open. The screen stays on while it is streaming.
echo   2. In OBS: Sources, +, Media Source (newer OBS may just say Media).
echo      Uncheck Local File.
echo      Input:            tcp://127.0.0.1:27183
echo      Input Format:     %INPUT_FORMAT%
echo      Network Buffering: 0 MB
echo      Reconnect Delay:  1
echo      Hardware decoding: off for mjpeg (on is fine for h264).
echo      FFmpeg Options:   fflags=nobuffer probesize=32 analyzeduration=0 framerate=%FRAMERATE%
echo   3. If the picture is black, wait one second, or toggle the source eye off and on.
echo.
echo Windows Firewall is not involved. The video never leaves this computer.
echo.
pause
exit /b 0

:forward_fail
echo adb forward failed. Unplug the phone, plug it back in, and run this again.
echo.
pause
exit /b 1

:start_fail
echo The phone did not open V50Cam. Install the app first:
echo   adb install -r v50cam.apk
echo Then run this file again.
echo.
pause
exit /b 1
