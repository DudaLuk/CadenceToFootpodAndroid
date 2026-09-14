@echo off
setlocal
set "APP_HOME=%~dp0"
set "GRADLE_VERSION=8.9"
set "BOOT_DIR=%APP_HOME%.gradle-bootstrap"
set "GRADLE_HOME=%BOOT_DIR%\gradle-%GRADLE_VERSION%"
set "ZIP_FILE=%BOOT_DIR%\gradle-%GRADLE_VERSION%-bin.zip"

if not exist "%GRADLE_HOME%\bin\gradle.bat" (
  if not exist "%BOOT_DIR%" mkdir "%BOOT_DIR%"
  echo [bootstrap] Pobieranie Gradle %GRADLE_VERSION%...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP_FILE%'"
  if errorlevel 1 exit /b 1
  echo [bootstrap] Rozpakowywanie Gradle...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%ZIP_FILE%' -DestinationPath '%BOOT_DIR%' -Force"
  if errorlevel 1 exit /b 1
)

call "%GRADLE_HOME%\bin\gradle.bat" %*
endlocal
