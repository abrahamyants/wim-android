#!/usr/bin/env bash
# screenshot.sh — capture device screen, shrink so Anthropic image preprocessor accepts it.
# Usage: bash scripts/screenshot.sh [max_width_px]
# Default max width 1500px. Outputs JPG path on stdout for Claude to Read.

set -euo pipefail
# git-bash on Windows translates /sdcard/foo into a Windows path before adb sees it.
# Disable that for this script — adb needs the literal Android path.
export MSYS_NO_PATHCONV=1

MAX_W="${1:-1500}"
ADB="C:/Users/georg/AppData/Local/Android/Sdk/platform-tools/adb.exe"
# Use absolute Windows paths so bash and Python agree on file location.
# (Bash's /tmp maps to AppData\Local\Temp; Python on Windows resolves /tmp to C:\tmp.)
TMPDIR_WIN="C:/Users/georg/AppData/Local/Temp"
RAW="$TMPDIR_WIN/wim_screen_raw.png"
OUT="$TMPDIR_WIN/wim_screen.jpg"
DEVICE_PATH="/sdcard/wim_screen_raw.png"

# Capture on device, then pull. Avoids git-bash mangling the binary PNG
# stream that `exec-out screencap -p > file` would produce.
"$ADB" shell "screencap -p $DEVICE_PATH" 2>/dev/null
"$ADB" pull "$DEVICE_PATH" "$RAW" >/dev/null
"$ADB" shell "rm $DEVICE_PATH"

python - "$RAW" "$OUT" "$MAX_W" <<'PY'
import sys
from PIL import Image
src, dst, max_w = sys.argv[1], sys.argv[2], int(sys.argv[3])
img = Image.open(src).convert("RGB")
if img.width > max_w:
    ratio = max_w / img.width
    img = img.resize((max_w, int(img.height * ratio)), Image.LANCZOS)
img.save(dst, "JPEG", quality=85, optimize=True)
PY

rm -f "$RAW"
echo "$OUT"
