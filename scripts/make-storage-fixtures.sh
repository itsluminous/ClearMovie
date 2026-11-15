#!/usr/bin/env bash
# Makes the synthetic media fixtures for the storage tests (cm-storage).
# Nothing here comes from real media: every picture and sound is generated.
#
#   frame-original.mp4   1280x720 (HD, BT.709, like the real frame), 2 s: an orange border, a #00D922 window at the
#                        generic preset's contentRect, a blue strip, a 440 Hz tone.
#   frame-reencoded.mp4  the same file passed through a lossy re-encode that shifts
#                        its colours, as a chat app does; the window's green comes
#                        out about Delta E 9 from #00D922.
#   tone-997hz-minus20dbfs.m4a  3 s of a 997 Hz sine at -20 dBFS in both channels,
#                        which measures -20 LUFS (ITU-R BS.1770).
#   frame-*-{10,50,90}.png  frames at 10, 50 and 90 % of each frame video, for the
#                        JVM tests, which cannot decode video.
#
# Usage: scripts/make-storage-fixtures.sh   (needs ffmpeg with libx264)
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
device="$root/app/src/androidTest/resources/fixtures/media"
jvm="$root/app/src/test/resources/fixtures/media"
mkdir -p "$device" "$jvm"

ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }
tags=(-colorspace bt709 -color_primaries bt709 -color_trc bt709 -color_range tv)
# The RGB to YUV steps name BT.709 explicitly: plain format=yuv420p would use
# BT.601 while the tag says BT.709, and a phone would see another green.
clean=(-map_metadata -1 -fflags +bitexact -flags:a +bitexact -movflags +faststart)

# The window: contentRect {x 0.0094, y 0.0222, w 0.9813, h 0.7741} of 1280x720.
# HD on purpose: a decoder that is not told the matrix picks BT.709 for HD and
# BT.601 for SD, and the real frame is HD BT.709.
ff -f lavfi -i "color=c=0xE0401A:s=1280x720:r=30:d=2" \
   -f lavfi -i "color=c=0x00D922:s=1256x557:r=30:d=2" \
   -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=2" \
   -filter_complex "[0:v][1:v]overlay=x=12:y=16,drawbox=x=0:y=600:w=1280:h=120:color=0x1E50C8@1:t=fill,scale=out_color_matrix=bt709:out_range=tv,format=yuv420p[v]" \
   -map "[v]" -map 2:a -c:v libx264 -preset slow -crf 16 "${tags[@]}" \
   -c:a aac -b:a 64k -ac 1 "${clean[@]}" "$device/frame-original.mp4"

# The re-encode: a colour shift plus heavy compression.
ff -i "$device/frame-original.mp4" \
   -vf "scale=in_color_matrix=bt709:in_range=tv,format=rgb24,lutrgb=r='clipval+8':g='clipval-12':b='clipval+8',scale=out_color_matrix=bt709:out_range=tv,format=yuv420p" \
   -c:v libx264 -preset veryfast -crf 30 "${tags[@]}" -c:a copy "${clean[@]}" "$device/frame-reencoded.mp4"

# Peak 0.1 (-20 dBFS) in each channel.
ff -f lavfi -i "aevalsrc=0.1*sin(2*PI*997*t)|0.1*sin(2*PI*997*t):s=48000:d=3" \
   -c:a aac -b:a 128k "${clean[@]}" "$device/tone-997hz-minus20dbfs.m4a"

for name in original reencoded; do
  for pct in 10 50 90; do
    t=$(awk "BEGIN { print 2 * $pct / 100 }")
    ff -ss "$t" -i "$device/frame-$name.mp4" -vf "scale=in_color_matrix=bt709:in_range=tv,format=rgb24" -frames:v 1 -map_metadata -1 "$jvm/frame-$name-$pct.png"
  done
done

ls -l "$device" "$jvm"
