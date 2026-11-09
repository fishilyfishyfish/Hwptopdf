#!/usr/bin/env bash
# Runs inside the emulator job: converts a real document with the installed
# APK, then pulls the PDF, a screenshot and the log into e2e-output/.
set -uo pipefail

PKG=io.github.fishy.hwptopdf
OUT=e2e-output
mkdir -p "$OUT"

adb install -r hwptopdf.apk || exit 1

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
    if [ "$waited" -ge 480 ]; then
      echo "$label: timed out"
      break
    fi
  done
  echo "$label: finished after ${waited}s"

  sleep 2
  adb exec-out screencap -p > "$OUT/$label.png"
  adb exec-out run-as "$PKG" cat cache/e2e.pdf > "$OUT/$label.pdf"
  adb logcat -d > "$OUT/$label.log"

  grep -E "chromium|HwpToPdf|CONSOLE" "$OUT/$label.log" | tail -20
  pdfinfo "$OUT/$label.pdf" || return 1
  qpdf --check "$OUT/$label.pdf" | tail -1
  pdftoppm -r 80 -f 2 -l 2 -png "$OUT/$label.pdf" "$OUT/$label-page"
  pdftotext -f 2 -l 2 "$OUT/$label.pdf" - | head -12
}

status=0
convert_one samples/exam_math.hwp hwp || status=1
convert_one samples/sample.hwpx hwpx || status=1
exit "$status"
