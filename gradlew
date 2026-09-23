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

# --- Locate an already-installed Gradle binary ---
# The install directory merely *existing* is not proof of a usable install:
# the Gradle cache restored in CI can hand back wrapper/dists/<dist>/ empty or
# half-extracted. Look for the binary itself, and reinstall when it is absent.
find_gradle() {
  find "$INSTALL_DIR" -type f -name gradle 2>/dev/null | head -n 1
}

GRADLE_BIN="$(find_gradle)"

if [ -z "$GRADLE_BIN" ]; then
  echo "Downloading $distributionUrl ..."
  rm -rf "$INSTALL_DIR"
  mkdir -p "$INSTALL_DIR"

  if command -v curl >/dev/null 2>&1; then
    curl -fL -o "$ZIP_PATH" "$distributionUrl"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP_PATH" "$distributionUrl"
  else
    echo "ERROR: Neither curl nor wget is installed."
    exit 1
  fi

  echo "Extracting $ZIP_NAME ..."
  unzip -q "$ZIP_PATH" -d "$INSTALL_DIR"

  # Move inner folder up if needed. Non-fatal: the lookup below is recursive,
  # so an unflattened layout still works and reports a clear error if not.
  INNER_DIR=$(find "$INSTALL_DIR" -mindepth 1 -maxdepth 1 -type d | head -n 1)
  if [ -n "$INNER_DIR" ]; then
    mv "$INNER_DIR"/* "$INSTALL_DIR/" 2>/dev/null || true
    rmdir "$INNER_DIR" 2>/dev/null || true
  fi

  GRADLE_BIN="$(find_gradle)"
fi

if [ -z "$GRADLE_BIN" ]; then
  echo "ERROR: no 'gradle' binary found under $INSTALL_DIR" >&2
  echo "Delete that directory and re-run to force a fresh download." >&2
  exit 1
fi

chmod +x "$GRADLE_BIN"

# --- Execute Gradle ---
exec "$GRADLE_BIN" "$@"
