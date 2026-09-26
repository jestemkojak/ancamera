# Generates the launcher icon: adaptive vectors (API 26+), docs/icon/*.svg and the legacy PNGs.
# Run: uv run --no-project python scripts/make-icon.py (needs ImageMagick with librsvg).
# The 108x108 adaptive canvas has its centre at 54,54.
import os
import subprocess

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
R = os.path.join(ROOT, "app/src/main/res")
BG, LIGHT, DARK, GLASS, RED = "#1C2B3A", "#E9EEF2", "#101B26", "#2E7BB8", "#E5484D"

def c(cx, cy, r):
    return f"M{cx - r},{cy}a{r},{r} 0 1,0 {2 * r},0a{r},{r} 0 1,0 {-2 * r},0z"

# (path, fill, alpha, stroke, strokeWidth)
FG = [
    (c(54, 54, 23), LIGHT, 1, None, 0),       # housing
    (c(54, 54, 18.5), DARK, 1, None, 0),      # lens body
    (c(54, 54, 12), GLASS, 1, None, 0),       # glass
    (c(54, 54, 5.5), DARK, 1, None, 0),       # pupil
    (c(49.5, 49.5, 2.6), "#FFFFFF", 0.85, None, 0),  # highlight
    (c(73, 35, 5), RED, 1, BG, 2.5),          # live dot
]
MONO = [c(54, 54, 23) + c(54, 54, 18.5), c(54, 54, 12) + c(54, 54, 5.5), c(73, 35, 5)]

def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "w").write(text)

NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'
def vector(paths):
    return (f'<?xml version="1.0" encoding="utf-8"?>\n<vector {NS}\n    android:width="108dp"\n    android:height="108dp"\n'
            f'    android:viewportWidth="108"\n    android:viewportHeight="108">\n' + "".join(paths) + "</vector>\n")

fg = []
for d, fill, a, stroke, sw in FG:
    s = f'    <path\n        android:fillColor="{fill}"\n'
    if a != 1: s += f'        android:fillAlpha="{a}"\n'
    if stroke: s += f'        android:strokeColor="{stroke}"\n        android:strokeWidth="{sw}"\n'
    fg.append(s + f'        android:pathData="{d}" />\n')
write(f"{R}/drawable-v26/ic_launcher_foreground.xml", vector(fg))
write(f"{R}/drawable-v26/ic_launcher_monochrome.xml", vector(
    [f'    <path\n        android:fillColor="#FFFFFFFF"\n        android:fillType="evenOdd"\n        android:pathData="{d}" />\n' for d in MONO]))
write(f"{R}/values/ic_launcher_colors.xml",
      f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">{BG}</color>\n</resources>\n')
adaptive = (f'<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon {NS}>\n'
            '    <background android:drawable="@color/ic_launcher_background" />\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n</adaptive-icon>\n')
for n in ("ic_launcher", "ic_launcher_round"):
    write(f"{R}/mipmap-anydpi-v26/{n}.xml", adaptive)

# Legacy icons (API < 26): show the 72dp visible part of the canvas on a shaped background.
def svg(shape):
    body = "".join(
        f'<path d="{d}" fill="{fill}"' + (f' fill-opacity="{a}"' if a != 1 else "")
        + (f' stroke="{st}" stroke-width="{sw}"' if st else "") + "/>" for d, fill, a, st, sw in FG)
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72">{shape}{body}</svg>\n'
out = os.path.join(ROOT, "docs/icon")
write(f"{out}/ic_launcher.svg", svg(f'<rect x="20" y="20" width="68" height="68" rx="15" fill="{BG}"/>'))
write(f"{out}/ic_launcher_round.svg", svg(f'<circle cx="54" cy="54" r="34" fill="{BG}"/>'))
subprocess.run([os.path.join(ROOT, "scripts/render-icon.sh")], check=True)
