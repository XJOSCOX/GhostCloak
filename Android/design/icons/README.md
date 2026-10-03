# Ghost Cloak UI icons

`monochrome/` contains the 32 SVG originals supplied in `GhostCloak-Icons`.
They are source artwork, not runtime downloads. Each uses a 64 × 64 viewport,
3.5-unit rounded strokes and a transparent background.

Run `python Android/scripts/import-icons.py` to regenerate
`app/src/main/res/drawable/gc_*.xml`. The converter preserves path geometry,
circles and rounded rectangles. Back/logout vectors mirror in RTL layouts.

`ui/components/AppIcon.kt` owns the resource mapping. Compose supplies the tint
from the current theme/content color; the vector's black stroke is a tintable
template, not an application palette color. Light, Dark and Automatic appearance
share the same artwork. Header and navigation sizing remain in the theme files.

The gradient SVG variants are not used for controls, preserving semantic theme
colors and disabled/error states. Having an icon in the pack does not enable
unsupported features such as calls, video or microphone recording.
