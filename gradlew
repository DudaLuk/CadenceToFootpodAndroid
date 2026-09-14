#!/usr/bin/env sh
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GRADLE_VERSION=8.9
BOOT_DIR="$APP_HOME/.gradle-bootstrap"
GRADLE_HOME="$BOOT_DIR/gradle-$GRADLE_VERSION"
ZIP_FILE="$BOOT_DIR/gradle-$GRADLE_VERSION-bin.zip"

if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  mkdir -p "$BOOT_DIR"
  echo "[bootstrap] Pobieranie Gradle $GRADLE_VERSION..."
  if command -v curl >/dev/null 2>&1; then
    curl -L "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP_FILE"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP_FILE" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  else
    echo "Brak curl/wget. Zainstaluj Gradle $GRADLE_VERSION lub otwórz projekt w Android Studio."
    exit 1
  fi
  unzip -oq "$ZIP_FILE" -d "$BOOT_DIR"
fi

exec "$GRADLE_HOME/bin/gradle" "$@"
