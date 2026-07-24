#!/usr/bin/env bash
# Build a Windows distributable on WSL/Linux (no Windows host required).
# Output: dist/EvidenceAuto/  +  dist/EvidenceAuto-windows.zip
#
# Layout:
#   EvidenceAuto/
#     EvidenceAuto.exe   (or EvidenceAuto.bat if mingw is missing)
#     EvidenceAuto.bat
#     app/evidence-auto.jar
#     runtime/           (Windows x64 JRE 21, downloaded from Adoptium)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BACKEND="$ROOT/backend"
DIST="$ROOT/dist/EvidenceAuto"
CACHE="$ROOT/.cache"
JRE_ZIP="$CACHE/temurin-21-jre-windows-x64.zip"
LAUNCHER_SRC="$ROOT/scripts/windows-launcher/EvidenceAuto.c"
ADOPTIUM_URL='https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse?project=jdk'

# Prefer JDK 21 for Gradle 8.11 (system default may be JDK 25 on WSL).
if [[ -d /usr/lib/jvm/java-21-openjdk-amd64 ]]; then
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  export PATH="$JAVA_HOME/bin:$PATH"
fi

echo "==> [1/5] Building bootJar (frontend + Spring Boot)..."
cd "$BACKEND"
GRADLE_JAVA_ARGS=()
if [[ -n "${JAVA_HOME:-}" ]]; then
  GRADLE_JAVA_ARGS+=("-Dorg.gradle.java.home=$JAVA_HOME")
fi
./gradlew "${GRADLE_JAVA_ARGS[@]}" clean bootJar

JAR="$(ls -1 "$BACKEND"/build/libs/evidence-auto-backend-*.jar | head -n1)"
if [[ ! -f "$JAR" ]]; then
  echo "bootJar not found under build/libs" >&2
  exit 1
fi
echo "    JAR: $JAR"

echo "==> [2/5] Fetching Windows JRE 21 (cached if present)..."
mkdir -p "$CACHE"
if [[ ! -f "$JRE_ZIP" ]]; then
  curl -fL --retry 3 --retry-delay 2 "$ADOPTIUM_URL" -o "$JRE_ZIP"
else
  echo "    Using cache: $JRE_ZIP"
fi

extract_zip() {
  local zip_file="$1"
  local dest_dir="$2"
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$zip_file" -d "$dest_dir"
  else
    python3 - "$zip_file" "$dest_dir" <<'PY'
import sys, zipfile
from pathlib import Path
zf, dest = sys.argv[1], Path(sys.argv[2])
with zipfile.ZipFile(zf) as z:
    z.extractall(dest)
PY
  fi
}

make_zip() {
  local source_dir="$1"
  local zip_file="$2"
  if command -v zip >/dev/null 2>&1; then
    (cd "$(dirname "$source_dir")" && zip -qr "$(basename "$zip_file")" "$(basename "$source_dir")")
    # move if zip created next to source parent with basename only
    local created
    created="$(dirname "$source_dir")/$(basename "$zip_file")"
    if [[ "$created" != "$zip_file" && -f "$created" ]]; then
      mv -f "$created" "$zip_file"
    fi
  else
    python3 - "$source_dir" "$zip_file" <<'PY'
import sys, zipfile
from pathlib import Path
src = Path(sys.argv[1])
out = Path(sys.argv[2])
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for path in src.rglob('*'):
        if path.is_file():
            z.write(path, path.relative_to(src.parent).as_posix())
PY
  fi
}

echo "==> [3/5] Assembling dist/EvidenceAuto ..."
rm -rf "$DIST"
mkdir -p "$DIST/app" "$DIST/runtime"

TMP_JRE="$(mktemp -d)"
trap 'rm -rf "$TMP_JRE"' EXIT
extract_zip "$JRE_ZIP" "$TMP_JRE"
JRE_ROOT="$(find "$TMP_JRE" -maxdepth 1 -mindepth 1 -type d | head -n1)"
if [[ -z "$JRE_ROOT" || ! -f "$JRE_ROOT/bin/java.exe" ]]; then
  echo "Windows JRE layout unexpected (java.exe missing)." >&2
  exit 1
fi
cp -a "$JRE_ROOT"/. "$DIST/runtime/"
cp "$JAR" "$DIST/app/evidence-auto.jar"

cat > "$DIST/EvidenceAuto.bat" <<'EOF'
@echo off
setlocal
cd /d "%~dp0"

echo ==================================================
echo  Evidence Auto starting...
echo  Working dir: %CD%
echo ==================================================
echo.

set "JAVA_EXE=%~dp0runtime\bin\java.exe"
set "APP_JAR=%~dp0app\evidence-auto.jar"
if not exist "%JAVA_EXE%" goto :missing_java
if not exist "%APP_JAR%" goto :missing_jar

"%JAVA_EXE%" -Dspring.profiles.active=desktop -Devidence.open-browser=true -jar "%APP_JAR%" %*
set "ERR=%ERRORLEVEL%"

echo.
echo ==================================================
echo  Process exited. Code: %ERR%
if not "%ERR%"=="0" echo  Startup may have failed. See logs\evidence-auto.log or %%APPDATA%%\EvidenceAuto\crash.log
echo  Press any key to close this window.
echo ==================================================
pause
exit /b %ERR%

:missing_java
echo [ERROR] Java runtime not found:
echo   %JAVA_EXE%
echo.
pause
exit /b 1

:missing_jar
echo [ERROR] Application JAR not found:
echo   %APP_JAR%
echo.
pause
exit /b 1
EOF

# Windows cmd requires CRLF; keep bat ASCII-only.
python3 - "$DIST/EvidenceAuto.bat" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
text = path.read_text(encoding='utf-8')
path.write_bytes(text.replace('\r\n', '\n').replace('\n', '\r\n').encode('ascii', errors='strict'))
print(f"    Wrote CRLF bat: {path}")
PY

# ASCII-only instructions for Windows Smart App Control users.
python3 - "$DIST/HOW-TO-RUN.txt" <<'PY'
from pathlib import Path
import sys
text = r"""Evidence Auto - How to run on Windows
====================================

If EvidenceAuto.exe / EvidenceAuto.bat are blocked by
"Smart App Control" or SmartScreen, do NOT use those files.

The bundled Java (runtime\bin\java.exe) is signed and usually allowed.
Run it from an already-open Command Prompt:

1) Press Win key, type cmd, open "Command Prompt"
2) Copy/paste commands below (fix YOUR folder path):

  cd /d "C:\path\to\EvidenceAuto"
  runtime\bin\java.exe -Dspring.profiles.active=desktop -Devidence.open-browser=true -jar app\evidence-auto.jar

3) When you see "Evidence Auto UI: http://127.0.0.1:8080/"
   open Chrome/Edge and go to that URL if the browser did not open.

Keep the Command Prompt window open while using the app.
Close it (or Ctrl+C) to stop the app.

Notes:
- Google Chrome is required for screenshots / folder picker.
- App data: %APPDATA%\EvidenceAuto\
- Logs: logs\evidence-auto.log  (inside this folder)
"""
path = Path(sys.argv[1])
path.write_bytes(text.replace('\n', '\r\n').encode('ascii'))
print(f"    Wrote {path}")
PY

# Helper that only prints the java command (also ASCII + CRLF).
python3 - "$DIST/run-from-cmd.txt" <<'PY'
from pathlib import Path
import sys
text = "runtime\\bin\\java.exe -Dspring.profiles.active=desktop -Devidence.open-browser=true -jar app\\evidence-auto.jar\r\n"
Path(sys.argv[1]).write_bytes(text.encode('ascii'))
PY

echo "==> [4/5] Building EvidenceAuto.exe (mingw cross-compile)..."
EXE_OK=0
if command -v x86_64-w64-mingw32-gcc >/dev/null 2>&1; then
  x86_64-w64-mingw32-gcc -mwindows -O2 -o "$DIST/EvidenceAuto.exe" "$LAUNCHER_SRC"
  EXE_OK=1
  echo "    EvidenceAuto.exe ready"
else
  echo "    mingw-w64 not found — shipping EvidenceAuto.bat only"
  echo "    (optional) sudo apt install -y mingw-w64"
fi

echo "==> [5/5] Creating zip..."
mkdir -p "$ROOT/dist"
ZIP_OUT="$ROOT/dist/EvidenceAuto-windows.zip"
rm -f "$ZIP_OUT"
make_zip "$DIST" "$ZIP_OUT"

echo ""
echo "Done."
echo "  Folder: $DIST"
if [[ "$EXE_OK" -eq 1 ]]; then
  echo "  Launch: $DIST/EvidenceAuto.exe"
else
  echo "  Launch: $DIST/EvidenceAuto.bat"
fi
echo "  Zip:    $ZIP_OUT"
echo ""
echo "Copy the EvidenceAuto folder (or the zip) to a Windows PC and run the launcher."
echo "Target PC still needs Google Chrome for screenshots / folder picker."
