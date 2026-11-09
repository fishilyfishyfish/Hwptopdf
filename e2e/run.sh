#!/usr/bin/env bash
# Runs inside the emulator job: converts a real document with the installed
# APK, then pulls the PDF, a screenshot and the log into e2e-output/.
set -uo pipefail

PKG=io.github.fishy.hwptopdf
OUT=e2e-output
mkdir -p "$OUT"

adb install -r hwptopdf.apk || exit 1
python3 -m pip install -q --break-system-packages pillow

print_image() {
  python3 - "$1" "$2" <<'PY'
import base64, io, sys
from PIL import Image
image = Image.open(sys.argv[1]).convert("RGB")
image.thumbnail((int(sys.argv[2]), int(sys.argv[2])))
data = io.BytesIO()
image.save(data, "JPEG", quality=70)
print("IMAGE-BEGIN", sys.argv[1])
print(base64.b64encode(data.getvalue()).decode())
print("IMAGE-END")
PY
}

convert_one() {
  local sample=$1 label=$2
  adb shell am force-stop "$PKG"
  adb shell run-as "$PKG" rm -f cache/e2e.done cache/e2e.pdf
  adb shell "run-as $PKG sh -c 'cat > cache/e2e.hwp'" < "$sample"
  adb logcat -c
  adb shell am start -n "$PKG/.MainActivity" --es e2e_path e2e.hwp

  local waited=0
  until adb shell run-as "$PKG" ls cache/e2e.done >/dev/null 2>&1; do
    sleep 5
    waited=$((waited + 5))
    if [ -z "$(adb shell pidof "$PKG")" ]; then
      echo "$label: app process died"
      break
    fi
    if [ "$waited" -ge 300 ]; then
      echo "$label: timed out"
      break
    fi
  done
  echo "$label: stopped waiting after ${waited}s"

  sleep 2
  adb exec-out screencap -p > "$OUT/$label.png"
  adb exec-out run-as "$PKG" cat cache/e2e.pdf > "$OUT/$label.pdf"
  adb logcat -d > "$OUT/$label.log"

  grep -E "HwpToPdf|AndroidRuntime|FATAL|Fatal signal|lowmemory|has died|Force finishing|render process|Renderer process" "$OUT/$label.log" | tail -30 | cut -c1-220
  print_image "$OUT/$label.png" 700
  pdfinfo "$OUT/$label.pdf" | grep -E "Pages|File size" || return 1
  qpdf --check "$OUT/$label.pdf" | tail -1
  pdftoppm -r 90 -f 2 -l 2 -jpeg "$OUT/$label.pdf" "$OUT/$label-page"
  print_image "$(ls "$OUT/$label-page"*.jpg | head -1)" 900
}

status=0
convert_one samples/exam_math.hwp hwp || status=1
convert_one samples/sample.hwpx hwpx || status=1
exit "$status"
