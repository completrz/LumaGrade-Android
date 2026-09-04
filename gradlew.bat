@echo off
setlocal
set "APP_HOME=%~dp0"
set "GRADLE_VERSION=8.9"
set "DIST_ROOT=%APP_HOME%.gradle-dist"
set "GRADLE_BIN=%DIST_ROOT%\gradle-%GRADLE_VERSION%\bin\gradle.bat"
set "ZIP_PATH=%DIST_ROOT%\gradle-%GRADLE_VERSION%-bin.zip"

if exist "%GRADLE_BIN%" goto run
if not exist "%DIST_ROOT%" mkdir "%DIST_ROOT%"
if exist "%ZIP_PATH%" goto unzip

echo Downloading Gradle %GRADLE_VERSION%...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP_PATH%'"
if errorlevel 1 exit /b 1

:unzip
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "Expand-Archive -Path '%ZIP_PATH%' -DestinationPath '%DIST_ROOT%' -Force"
if errorlevel 1 exit /b 1

:run
call "%GRADLE_BIN%" %*
endlocal
