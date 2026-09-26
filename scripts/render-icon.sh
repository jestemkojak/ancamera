#!/usr/bin/env bash
# Renders the legacy launcher PNGs (API < 26) from docs/icon/*.svg. scripts/make-icon.py runs it.
set -euo pipefail
cd "$(dirname "$0")/.."
for density in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
    name=${density%%:*}
    size=${density##*:}
    mkdir -p "app/src/main/res/mipmap-$name"
    for icon in ic_launcher ic_launcher_round; do
        magick -background none -density 1200 "docs/icon/$icon.svg" -resize "${size}x${size}" \
            "PNG32:app/src/main/res/mipmap-$name/$icon.png"
    done
done
magick -background none -density 1200 docs/icon/ic_launcher.svg -resize 512x512 PNG32:docs/icon/ic_launcher-512.png
