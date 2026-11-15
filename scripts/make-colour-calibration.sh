#!/usr/bin/env bash
# Generates the colour calibration clip the renderer measures each device
# with (render.media3.ColourCalibration): 1 s of flat #E42CE4 at 320x192,
# 10 fps, H.264 main profile, BT.709 limited range with the full colour
# description in the SPS. Its YUV decodes to (228,44,228) with BT.709 and to
# about (214,12,222) with BT.601, and both forms re-encode 20 levels apart,
# so one export tells which matrix the GPU and the encoder use.
#
# Usage: scripts/make-colour-calibration.sh [output-file]
# Default output: app/src/main/assets/render/colour-calibration.mp4
set -euo pipefail
out="${1:-app/src/main/assets/render/colour-calibration.mp4}"
mkdir -p "$(dirname "$out")"
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "color=c=0xE42CE4:s=320x192:r=10:d=1" \
  -vf "scale=out_color_matrix=bt709:out_range=tv,format=yuv420p" \
  -c:v libx264 -preset veryslow -crf 4 -profile:v main -g 10 -bf 0 \
  -color_primaries bt709 -color_trc bt709 -colorspace bt709 -color_range tv \
  -bsf:v h264_metadata=colour_primaries=1:transfer_characteristics=1:matrix_coefficients=1 \
  -an -movflags +faststart "$out"
wc -c < "$out"
