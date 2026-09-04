#!/usr/bin/env sh
set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
GRADLE_VERSION=8.9
DIST_ROOT="$APP_HOME/.gradle-dist"
GRADLE_BIN="$DIST_ROOT/gradle-$GRADLE_VERSION/bin/gradle"
ZIP_PATH="$DIST_ROOT/gradle-$GRADLE_VERSION-bin.zip"

if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$DIST_ROOT"
  if [ ! -f "$ZIP_PATH" ]; then
    echo "Downloading Gradle $GRADLE_VERSION..."
    curl -L --fail --progress-bar \
      "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" \
      -o "$ZIP_PATH"
  fi
  unzip -q -o "$ZIP_PATH" -d "$DIST_ROOT"
fi

exec "$GRADLE_BIN" "$@"
