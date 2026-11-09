#!/usr/bin/env bash
# Runs inside the emulator job: converts real documents with the installed APK
# and checks the PDF, the save to Downloads and the widget registration.
set -uo pipefail

PKG=io.github.fishy.hwptopdf
OUT=e2e-output
mkdir -p "$OUT"

adb install -r hwptopdf.apk || exit 1

# Starts a conversion and waits for it to end. Extra arguments go to `am start`.
run_app() {
  local sample=$1 label=$2
  shift 2
  adb shell am force-stop "$PKG"
  adb shell run-as "$PKG" rm -f cache/e2e.done cache/e2e.pdf
  adb shell "run-as $PKG sh -c 'cat > cache/e2e.hwp'" < "$sample"
  adb logcat -c
  adb shell am start -n "$PKG/.MainActivity" --es e2e_path e2e.hwp "$@"

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
  adb logcat -d > "$OUT/$label.log"
  grep -E "HwpToPdf|AndroidRuntime|FATAL|Fatal signal" "$OUT/$label.log" | tail -30 | cut -c1-220
}

convert_one() {
  local sample=$1 label=$2
  run_app "$sample" "$label"
  adb exec-out run-as "$PKG" cat cache/e2e.pdf > "$OUT/$label.pdf"
  pdfinfo "$OUT/$label.pdf" | grep -E "Pages|File size" || return 1
  qpdf --check "$OUT/$label.pdf" | tail -1
}

status=0
convert_one samples/exam_math.hwp hwp || status=1
convert_one samples/sample.hwpx hwpx || status=1

run_app samples/exam_math.hwp downloads --ez e2e_save true
saved=$(adb shell content query --uri content://media/external/downloads \
  --projection _display_name:relative_path:is_pending:_size --where "_display_name=\'e2e.pdf\'")
echo "Downloads entry: $saved"
case "$saved" in
  *"_display_name=e2e.pdf, relative_path=Download/HWP2PDF/, is_pending=0"*) ;;
  *) echo "The PDF did not reach Downloads"; status=1 ;;
esac

widget=$(adb shell dumpsys appwidget | grep -c "$PKG/.ConvertWidget")
echo "Widget provider entries: $widget"
[ "$widget" -gt 0 ] || status=1

exit "$status"
