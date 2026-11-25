#!/usr/bin/env bash
# macOS üçün scrcpy fayllarını yükləyib src-tauri/resources/scrcpy/macos qovluğuna qoyur.
# İstifadə: scripts/fetch-scrcpy-macos.sh [aarch64|x86_64]   (standart: hazırkı Mac-in arxitekturası)
set -euo pipefail

VERSION="5.0.1"
ARCH="${1:-$(uname -m)}"
case "$ARCH" in
  arm64|aarch64) ARCH="aarch64" ;;
  x86_64|amd64)  ARCH="x86_64" ;;
  *) echo "Naməlum arxitektura: $ARCH" >&2; exit 1 ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/src-tauri/resources/scrcpy/macos"
NAME="scrcpy-macos-$ARCH-v$VERSION"
URL="https://github.com/Genymobile/scrcpy/releases/download/v$VERSION/$NAME.tar.gz"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Yüklənir: $URL"
curl -fsSL "$URL" -o "$TMP/scrcpy.tar.gz"
tar -xzf "$TMP/scrcpy.tar.gz" -C "$TMP"

rm -rf "$DEST"
mkdir -p "$DEST"
for f in scrcpy adb scrcpy-server scrcpy.png disconnected.png LICENSE; do
  cp "$TMP/$NAME/$f" "$DEST/"
done
chmod +x "$DEST/scrcpy" "$DEST/adb"
echo "Hazırdır: $DEST ($ARCH)"
ls -la "$DEST"
