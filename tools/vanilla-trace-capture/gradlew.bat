@echo off
setlocal EnableExtensions

rem Phase 5 project-local Gradle launcher.
rem Downloads the pinned Gradle distribution only when it is not already cached.
set "GRADLE_VERSION=8.14.3"
set "SCRIPT_DIR=%~dp0"
set "DIST_DIR=%SCRIPT_DIR%.gradle-dist\gradle-%GRADLE_VERSION%"
set "ZIP_FILE=%SCRIPT_DIR%.gradle-dist\gradle-%GRADLE_VERSION%-bin.zip"
set "DIST_URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip"

if not exist "%DIST_DIR%\bin\gradle.bat" (
  if not exist "%SCRIPT_DIR%.gradle-dist" mkdir "%SCRIPT_DIR%.gradle-dist"
  echo Gradle %GRADLE_VERSION% is not installed for this project. Downloading from:
  echo %DIST_URL%
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest -UseBasicParsing -Uri '%DIST_URL%' -OutFile '%ZIP_FILE%'"
  if errorlevel 1 (
    echo Failed to download Gradle %GRADLE_VERSION%.
    exit /b 1
  )
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Expand-Archive -LiteralPath '%ZIP_FILE%' -DestinationPath '%SCRIPT_DIR%.gradle-dist' -Force"
  if errorlevel 1 (
    echo Failed to extract Gradle %GRADLE_VERSION%.
    exit /b 1
  )
  del /q "%ZIP_FILE%"
)

call "%DIST_DIR%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
