"""Convert the checked-in Ghost Cloak SVG originals to theme-tinted Android vectors.

Run from any directory: python Android/scripts/import-icons.py
Only this pack's path, circle and rounded-rectangle primitives are supported.
"""
from pathlib import Path
import xml.etree.ElementTree as ET

ANDROID = Path(__file__).resolve().parents[1]
NS = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", NS)


def number(value):
    return format(value, "g")


def path_data(element):
    kind = element.tag.rsplit("}", 1)[-1]
    if kind == "path":
        return element.attrib["d"]
    if kind == "circle":
        x, y, r = (float(element.attrib[k]) for k in ("cx", "cy", "r"))
        return f"M{number(x-r)},{number(y)}a{number(r)},{number(r)} 0 1,0 {number(2*r)},0a{number(r)},{number(r)} 0 1,0 {number(-2*r)},0Z"
    if kind == "rect":
        x, y, w, h = (float(element.attrib[k]) for k in ("x", "y", "width", "height"))
        r = min(float(element.get("rx", "0")), w / 2, h / 2)
        n = number
        arc = f"A{n(r)},{n(r)} 0 0,1 "
        return (f"M{n(x+r)},{n(y)}H{n(x+w-r)}{arc}{n(x+w)},{n(y+r)}"
                f"V{n(y+h-r)}{arc}{n(x+w-r)},{n(y+h)}"
                f"H{n(x+r)}{arc}{n(x)},{n(y+h-r)}"
                f"V{n(y+r)}{arc}{n(x+r)},{n(y)}Z")
    raise ValueError(f"Unsupported icon primitive: {kind}")


destination = ANDROID / "app/src/main/res/drawable"
destination.mkdir(parents=True, exist_ok=True)
sources = sorted((ANDROID / "design/icons/monochrome").glob("*.svg"))
for source in sources:
    svg = ET.parse(source).getroot()
    if svg.get("viewBox") != "0 0 64 64":
        raise ValueError("Unexpected icon viewport")
    vector = ET.Element("vector", {f"{{{NS}}}{k}": v for k, v in {
        "width": "24dp", "height": "24dp", "viewportWidth": "64", "viewportHeight": "64"
    }.items()})
    if source.stem in ("back", "logout"):
        vector.set(f"{{{NS}}}autoMirrored", "true")
    group = svg.find("{http://www.w3.org/2000/svg}g")
    if group is None or group.get("stroke") != "currentColor":
        raise ValueError("Expected monochrome currentColor icon group")
    for primitive in group:
        ET.SubElement(vector, "path", {f"{{{NS}}}{k}": v for k, v in {
            "fillColor": "#00000000", "strokeColor": "#FF000000",
            "strokeWidth": group.attrib["stroke-width"], "strokeLineCap": "round",
            "strokeLineJoin": "round", "pathData": path_data(primitive)
        }.items()})
    ET.indent(vector, space="    ")
    target = destination / f"gc_{source.stem.replace('-', '_')}.xml"
    target.write_text('<?xml version="1.0" encoding="utf-8"?>\n'
                      '<!-- Generated from Android/design/icons/monochrome; use scripts/import-icons.py. -->\n'
                      + ET.tostring(vector, encoding="unicode") + "\n", encoding="utf-8")
print(f"Imported {len(sources)} Ghost Cloak vector icons.")
