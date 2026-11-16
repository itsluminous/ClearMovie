#!/usr/bin/env bash
# Generates the small synthetic media fixtures the render tests use
# (TESTING-PROTOCOL section 1). Everything is drawn by ffmpeg from scratch:
# nothing comes from anyone's real media. Re-running produces equivalent
# files; each must stay under 300 KB.
#
# Usage: scripts/make-fixtures.sh [output-dir]
# Default output: app/src/androidTest/assets/fixtures
set -euo pipefail

out="${1:-app/src/androidTest/assets/fixtures}"
mkdir -p "$out"
ff=(ffmpeg -hide_banner -loglevel error -y)
# The bitstream filter writes the full BT.709 colour description into the
# SPS: the encoder flags alone left primaries and transfer "unspecified",
# which Android's MediaMetadataRetriever then reads with the BT.601 matrix.
x264=(-c:v libx264 -preset veryslow -crf 30 -pix_fmt yuv420p -profile:v main
  -color_primaries bt709 -color_trc bt709 -colorspace bt709 -g 25 -bf 0
  -bsf:v h264_metadata=colour_primaries=1:transfer_characteristics=1:matrix_coefficients=1)
aac=(-c:a aac -b:a 48k -ar 44100)

# Tones are drawn with aevalsrc at an exact amplitude of 0.1 (-20 dBFS) on
# both channels, so the ducking test can check absolute levels.
tone() { echo "aevalsrc=0.1*sin(2*PI*$1*t)|0.1*sin(2*PI*$1*t):s=44100:d=$2"; }

# frame.mp4: a 12 s 1920x1080 green-screen frame with the reference geometry
# (ARCHITECTURE section 3): a red border, a #00D922 window at x 18-1901,
# y 24-859, a blue strip below with a white band, and a white "logo" box
# that overlaps the window's bottom-left corner (x 32-271, y 802-1059). A
# white block moves along the strip so the frame is a real moving video
# (overlay, because drawbox's "t" is its thickness, not the time).
# Its soundtrack (the background music) is a 1 kHz tone at -20 dBFS. It is
# long enough that the ducking test never meets a repeat join.
"${ff[@]}" \
  -f lavfi -i "color=c=0xC81414:s=1920x1080:r=30:d=12" \
  -f lavfi -i "$(tone 1000 12)" \
  -f lavfi -i "color=c=0xFFFFFF:s=120x100:r=30:d=12" \
  -filter_complex "[0:v]drawbox=x=18:y=24:w=1884:h=836:color=0x00D922:t=fill,\
drawbox=x=0:y=860:w=1920:h=180:color=0x1440B4:t=fill,\
drawbox=x=0:y=1040:w=1920:h=40:color=0xF0F0F0:t=fill,\
drawbox=x=32:y=802:w=240:h=258:color=0xFFFFFF:t=fill,\
drawbox=x=72:y=842:w=160:h=120:color=0x1440B4:t=fill[bg];\
[bg][2:v]overlay=x='400+mod(t*300\,1200)':y=900[v];\
[1:a]anull[a]" \
  -map "[v]" -map "[a]" "${x264[@]}" "${aac[@]}" -t 12 "$out/frame.mp4"

# frame_neargreen.mp4: 4 s, the same window geometry as frame.mp4, with a
# strip of five near-green swatches below the window, none of which may be
# keyed. As encoded, each is 0.287-0.315 from the nearer form of the key in
# BT.709 Cb/Cr (the stored key and its BT.601-decoded form) whichever
# matrix decodes it, so at least 0.027 outside tolerance + softness (0.26).
# Encoded near-lossless (crf 12) so the swatches keep those colours. All
# five stay inside the RGB range when a GPU decodes them with the BT.601
# matrix, so the BT.709 correction restores them exactly; more saturated
# greens (#98FC00) clip there, a limit recorded in docs/architecture.md.
"${ff[@]}" \
  -f lavfi -i "color=c=0xC81414:s=1920x1080:r=30:d=4" \
  -filter_complex "[0:v]drawbox=x=18:y=24:w=1884:h=836:color=0x00D922:t=fill,\
drawbox=x=0:y=860:w=1920:h=220:color=0x1440B4:t=fill,\
drawbox=x=60:y=880:w=320:h=180:color=0xA4E420:t=fill,\
drawbox=x=420:y=880:w=320:h=180:color=0xB0E880:t=fill,\
drawbox=x=780:y=880:w=320:h=180:color=0x80C484:t=fill,\
drawbox=x=1140:y=880:w=320:h=180:color=0x589444:t=fill,\
drawbox=x=1500:y=880:w=320:h=180:color=0x1B5E20:t=fill[v]" \
  -map "[v]" "${x264[@]}" -crf 12 -t 4 "$out/frame_neargreen.mp4"

# intro.mp4: 2 s of solid orange with a moving dark block, and a 660 Hz tone.
"${ff[@]}" \
  -f lavfi -i "color=c=0xFF8000:s=1920x1080:r=30:d=2" \
  -f lavfi -i "$(tone 660 2)" \
  -f lavfi -i "color=c=0x402000:s=200x200:r=30:d=2" \
  -filter_complex "[0:v][2:v]overlay=x='100+t*600':y=100[v];\
[1:a]anull[a]" \
  -map "[v]" -map "[a]" "${x264[@]}" "${aac[@]}" -t 2 "$out/intro.mp4"

# clip_dark.mp4 / clip_light.mp4: 3 s, 1280x720 (not the canvas size, so
# scaling is exercised), flat grey 60 and 200 for the crossfade luminance
# check, with 440 Hz and 550 Hz tones as clip audio at -20 dBFS.
for spec in "dark 0x3C3C3C 440" "light 0xC8C8C8 550"; do
  set -- $spec
  "${ff[@]}" \
    -f lavfi -i "color=c=$2:s=1280x720:r=30:d=3" \
    -f lavfi -i "$(tone "$3" 3)" \
    -f lavfi -i "color=c=0x808080:s=60x40:r=30:d=3" \
    -filter_complex "[0:v][2:v]overlay=x='mod(t*200\,1100)':y=20[v];\
[1:a]anull[a]" \
    -map "[v]" -map "[a]" "${x264[@]}" "${aac[@]}" -t 3 "$out/clip_$1.mp4"
done

# speech.m4a: 3 s mono "speech-like" sound, a 300 Hz tone with a 4 Hz
# syllable-rate tremolo, peaking at -14 dBFS.
"${ff[@]}" \
  -f lavfi -i "aevalsrc=0.2*sin(2*PI*300*t):s=44100:d=3" \
  -af "tremolo=f=4:d=0.6" -c:a aac -b:a 48k -ac 1 "$out/speech.m4a"

# photo_blue.png: a flat blue 800x450 picture (the picture-in-picture item).
"${ff[@]}" -f lavfi -i "color=c=0x2040E0:s=800x450:d=1" -frames:v 1 "$out/photo_blue.png"

# photo_quadrants.png: 640x360 with a red top-left, green top-right, blue
# bottom-left and white bottom-right quadrant, to check orientation and
# placement.
"${ff[@]}" -f lavfi -i "color=c=0xFFFFFF:s=640x360:d=1" \
  -vf "drawbox=x=0:y=0:w=320:h=180:color=0xFF0000:t=fill,\
drawbox=x=320:y=0:w=320:h=180:color=0x00FF00:t=fill,\
drawbox=x=0:y=180:w=320:h=180:color=0x0000FF:t=fill" \
  -frames:v 1 "$out/photo_quadrants.png"

# tag_sd709.mp4 / tag_hd601.mp4: one grey frame each whose colour tag is
# only in the stream (SPS VUI), with no colr box, and disagrees with what
# Android's size rule would assume: 640x480 tagged BT.709, 1280x720 tagged
# SMPTE 170M (BT.601). The probe must follow the stream's tag.
tagged() {
  "${ff[@]}" -f lavfi -i "color=c=gray:s=$1:r=1:d=1" -vf format=yuv420p \
    -c:v libx264 -preset veryslow -crf 30 -profile:v main -bf 0 \
    -bsf:v "h264_metadata=video_full_range_flag=0:colour_primaries=$2:transfer_characteristics=$2:matrix_coefficients=$2" \
    -frames:v 1 -an "$out/$3"
}
tagged 640x480 1 tag_sd709.mp4
tagged 1280x720 6 tag_hd601.mp4

for f in "$out"/*; do
  size=$(wc -c < "$f")
  if [ "$size" -ge 307200 ]; then
    echo "error: $f is $size bytes (over 300 KB)" >&2
    exit 1
  fi
  printf '%8d  %s\n' "$size" "$f"
done
