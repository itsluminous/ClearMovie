#!/usr/bin/env bash
# Makes the reference image CaptionRenderTest compares Hindi shaping with:
# "नमस्ते हिन्दी" (the स्त and न्द conjuncts and the ि matra) rasterised by
# HarfBuzz's hb-view from the app's own bundled font file, at 120 px, black on
# white. The test crops both images to their ink, so only the shapes matter.
#
#   scripts/make-caption-reference.sh
#
# Needs hb-view (HarfBuzz utilities, with cairo). Writes into the androidTest
# fixtures folder; the output is about 6 KB.
set -euo pipefail
cd "$(dirname "$0")/.."
font=app/src/main/res/font/noto_sans_devanagari_bold.ttf
out=app/src/androidTest/assets/fixtures/caption_ref_namaste_hindi.png
LC_ALL=en_US.UTF-8 hb-view --script=deva --font-size=120 --margin=8 \
  --foreground=000000 --background=FFFFFF -O png -o "$out" "$font" "नमस्ते हिन्दी"
echo "wrote $out"
