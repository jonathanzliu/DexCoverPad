#!/usr/bin/env sh

##############################################################################
# Gradle wrapper script (fixed + simplified)
##############################################################################

set -e

APP_NAME="Gradle"
WRAPPER_DIR="$(cd "$(dirname "$0")" && pwd)"
PROPERTIES_FILE="$WRAPPER_DIR/gradle/wrapper/gradle-wrapper.properties"

# --- Validate properties file ---
if [ ! -f "$PROPERTIES_FILE" ]; then
  echo "ERROR: gradle-wrapper.properties not found!"
  exit 1
fi

# --- Extract distribution URL ---
distributionUrl=$(grep "^distributionUrl=" "$PROPERTIES_FILE" | cut -d= -f2-)

# Fix escaped colon (IMPORTANT FIX)
distributionUrl=$(echo "$distributionUrl" | sed 's/\\:/:/g')

# --- Determine cache directory ---
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
DIST_DIR="$GRADLE_USER_HOME/wrapper/dists"

mkdir -p "$DIST_DIR"

# --- Extract filename ---
ZIP_NAME=$(basename "$distributionUrl")
DIST_NAME=$(basename "$ZIP_NAME" .zip)

INSTALL_DIR="$DIST_DIR/$DIST_NAME"
ZIP_PATH="$DIST_DIR/$ZIP_NAME"

# --- Download if not already present ---
if [ ! -d "$INSTALL_DIR" ]; then
  echo "Downloading $distributionUrl ..."

  if command -v curl >/dev/null 2>&1; then
    curl -L -o "$ZIP_PATH" "$distributionUrl"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP_PATH" "$distributionUrl"
  else
    echo "ERROR: Neither curl nor wget is installed."
    exit 1
  fi

  echo "Extracting $ZIP_NAME ..."
  mkdir -p "$INSTALL_DIR"
  unzip -q "$ZIP_PATH" -d "$INSTALL_DIR"

  # Move inner folder up if needed
  INNER_DIR=$(find "$INSTALL_DIR" -mindepth 1 -maxdepth 1 -type d | head -n 1)
  if [ -n "$INNER_DIR" ]; then
    mv "$INNER_DIR"/* "$INSTALL_DIR/"
    rmdir "$INNER_DIR"
  fi
fi

# --- Locate Gradle binary ---
GRADLE_BIN=$(find "$INSTALL_DIR" -type f -name "gradle" | head -n 1)

if [ ! -x "$GRADLE_BIN" ]; then
  chmod +x "$GRADLE_BIN"
fi

# --- Execute Gradle ---
exec "$GRADLE_BIN" "$@"
