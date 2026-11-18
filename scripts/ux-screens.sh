#!/usr/bin/env bash
# Takes the UX review's screenshots (UxTour, an instrumented test) on one
# device, for each device configuration, and pulls them into one folder.
#
#   ANDROID_SERIAL=emulator-5584 scripts/ux-screens.sh <out-dir> [config ...]
#
# A config is name=width,orientation,theme,colours,language,font:
#   width        320, 360 or 411 (dp, a phone), or tablet (800 x 1280 dp)
#   orientation  portrait or landscape
#   theme        light or dark
#   colours      dyn (Colours from wallpaper, a green test wallpaper palette) or brand
#   language     en, hi or hi-Latn (the app's own language, as its picker sets it)
#   font         the system font scale: 1.0, 1.3 or 2.0
# With no configs it takes the standard set below.
#
# It changes only the named device's display size and density, font scale,
# rotation, dark mode and wallpaper palette, and the app's language and data
# (pm clear), and puts every one of them back when it ends. Install the debug
# app and its test APK first (./gradlew installDebug installDebugAndroidTest).
# Media: put photo1-4.jpg, video1-2.mp4, music.m4a, Intro.mp4,
# Frame-White-20s.mp4 and speech_last.mp3 in $UX_MEDIA (optional; the test
# fixtures are used otherwise). Never commit personal media. A real export
# (progress, then done) runs only for the configs in $UX_EXPORT_CONFIGS
# (default "base dark w320f20hl"); the others show the sheet's states as
# stand-ins.
set -euo pipefail

: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the device to use}"
out="${1:?usage: scripts/ux-screens.sh <out-dir> [config ...]}"
shift
adb="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
pkg=com.itsluminous.ClearMovie
runner=$pkg.test/androidx.test.runner.AndroidJUnitRunner
files=/sdcard/Android/data/$pkg/files
media="${UX_MEDIA:-}"

configs=("$@")
if [ ${#configs[@]} -eq 0 ]; then
  configs=(
    base=411,portrait,light,brand,en,1.0
    dark=411,portrait,dark,brand,en,1.0
    dyn=411,portrait,light,dyn,en,1.0
    dyndark=411,portrait,dark,dyn,en,1.0
    w360=360,portrait,light,brand,en,1.0
    hi=360,portrait,light,brand,hi,1.0
    hinglish=360,portrait,light,brand,hi-Latn,1.0
    font13=360,portrait,light,brand,en,1.3
    font13hl=360,portrait,dark,brand,hi-Latn,1.3
    w320f20=320,portrait,light,brand,en,2.0
    w320f20hi=320,portrait,light,brand,hi,2.0
    w320f20hl=320,portrait,dark,brand,hi-Latn,2.0
    land=411,landscape,light,brand,en,1.0
    landhl=411,landscape,dark,brand,hi-Latn,1.3
    tablet=tablet,portrait,light,brand,en,1.0
    tabletland=tablet,landscape,dark,dyn,hi,1.0
  )
fi

d() { "$adb" -s "$ANDROID_SERIAL" "$@"; }
sh() { d shell "$@" | tr -d '\r'; }

orig_font=$(sh settings get system font_scale)
orig_accel=$(sh settings get system accelerometer_rotation)
orig_rot=$(sh settings get system user_rotation)
orig_palette=$(sh settings get secure theme_customization_overlay_packages)
orig_night=$(sh cmd uimode night | sed 's/.*: //')

restore() {
  d shell wm size reset || true
  d shell wm density reset || true
  d shell settings put system font_scale "$orig_font" || true
  d shell settings put system accelerometer_rotation "$orig_accel" || true
  d shell settings put system user_rotation "$orig_rot" || true
  if [ "$orig_palette" = "null" ]; then
    d shell settings delete secure theme_customization_overlay_packages || true
  else
    d shell settings put secure theme_customization_overlay_packages "'$orig_palette'" || true
  fi
  d shell cmd uimode night "$orig_night" || true
  d shell cmd locale set-app-locales "$pkg" --locales "''" || true
}
trap restore EXIT

mkdir -p "$out"
for config in "${configs[@]}"; do
  name=${config%%=*}
  IFS=, read -r width orientation theme colours language font <<<"${config#*=}"
  echo "== $name: $width dp, $orientation, $theme, $colours, $language, font $font"
  if [ "$width" = tablet ]; then
    d shell wm size 1600x2560
    d shell wm density 320
  else
    d shell wm size reset
    d shell wm density $(((1080 * 160 + width / 2) / width))
  fi
  d shell settings put system accelerometer_rotation 0
  d shell settings put system user_rotation $([ "$orientation" = landscape ] && echo 1 || echo 0)
  d shell settings put system font_scale "$font"
  d shell cmd uimode night $([ "$theme" = dark ] && echo yes || echo no)
  if [ "$colours" = dyn ]; then
    d shell settings put secure theme_customization_overlay_packages \
      "'{\"android.theme.customization.system_palette\":\"FF2E7D32\",\"android.theme.customization.theme_style\":\"TONAL_SPOT\"}'"
  fi
  d shell pm clear "$pkg" >/dev/null
  d shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
  d shell cmd locale set-app-locales "$pkg" --locales "$language"
  if [ -n "$media" ]; then
    d shell mkdir -p "$files/uxmedia"
    d push "$media"/. "$files/uxmedia/" >/dev/null
  fi
  sleep 3
  dynarg=$([ "$colours" = dyn ] && echo true || echo false)
  exportarg=false
  for e in ${UX_EXPORT_CONFIGS:-base dark w320f20hl}; do [ "$e" = "$name" ] && exportarg=true; done
  for method in tour stateless; do
    d shell am instrument -w -e uxtour "$name" -e uxdyn "$dynarg" -e uxexport "$exportarg" \
      -e class "com.itsluminous.clearmovie.ui.UxTour#$method" "$runner" | tr -d '\r' | tail -3
  done
  rm -rf "${out:?}/$name"
  d pull "$files/ux/$name" "$out/$name" >/dev/null
  printf '%s\t%s\n' "config" "$config" >>"$out/$name/manifest.txt"
  if [ "$colours" = dyn ]; then
    if [ "$orig_palette" = "null" ]; then
      d shell settings delete secure theme_customization_overlay_packages
    else
      d shell settings put secure theme_customization_overlay_packages "'$orig_palette'"
    fi
  fi
done
echo "Screens are in $out"
