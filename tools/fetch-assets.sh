#!/usr/bin/env bash
# Puts the conversion engine, fonts and their licences into the app's assets.
# Versions are pinned so every build uses exactly the same files.
set -euo pipefail

RHWP_VERSION=0.9.0
RHWP_COMMIT=c9095c5f67632e6721e800ccc7fb43ea49d26682
RAW="https://raw.githubusercontent.com/edwardkim/rhwp/${RHWP_COMMIT}/assets/fonts"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$ASSETS/fonts" "$ASSETS/licenses"

(cd "$WORK" && npm pack "@rhwp/core@${RHWP_VERSION}" --silent >/dev/null && tar -xzf rhwp-core-*.tgz)
cp "$WORK/package/rhwp.js" "$WORK/package/rhwp_bg.wasm" "$ASSETS/"
cp "$WORK/package/LICENSE" "$ASSETS/licenses/rhwp-MIT.txt"
cp "$WORK/package/notices/THIRD_PARTY_LICENSES.md" "$ASSETS/licenses/rhwp-third-party.md"

for font in NotoSerifKR-Regular NotoSerifKR-Bold NotoSansKR-Regular NotoSansKR-Bold \
            Pretendard-Regular Pretendard-Bold LatinModernMath-Regular \
            RHWPOldHangul-Regular RHWPPublicBatang-Regular; do
  curl -fsSL --retry 3 -o "$ASSETS/fonts/$font.woff2" "$RAW/$font.woff2"
done

for licence in NotoCJKSerif-OFL.txt NotoSansKR-OFL.txt Pretendard-OFL.txt LatinModernMath-GUST-FONT-LICENSE.txt \
               SourceHanSerifK-OFL.txt RHWPPublicBatang-OFL.txt; do
  curl -fsSL --retry 3 -o "$ASSETS/licenses/$licence" "$RAW/licenses/$licence"
done

ls -la "$ASSETS" "$ASSETS/fonts"
