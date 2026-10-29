@echo off
setlocal
REM Stops V50Cam on the phone and removes the USB forward.
REM If adb is not on PATH, set this to the full path of adb.exe.
set ADB=

set "ADB_CMD="
if not "%ADB%"=="" goto use_var
where adb >nul 2>&1
if not errorlevel 1 goto use_path
if exist "%~dp0scrcpy\adb.exe" goto use_next
if exist "%~dp0..\scrcpy\adb.exe" goto use_parent
echo Could not find adb.
echo Edit the ADB= line at the top of this file, or install platform-tools.
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
"%ADB_CMD%" get-state >nul 2>&1
if errorlevel 1 goto no_phone
"%ADB_CMD%" shell am force-stop com.nick.v50cam
echo V50Cam has been stopped on the phone.
goto remove_forward

:no_phone
echo No phone found. Removing the USB forward anyway.

:remove_forward
"%ADB_CMD%" forward --remove tcp:27183
echo USB forward for port 27183 has been removed.
echo.
pause
exit /b 0
