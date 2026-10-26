#!/usr/bin/env python3
"""Generates the Frost-flix icon and TV banner.

One set of shapes is written out twice: as Android vector drawables (the
adaptive icon layers and the leanback banner) and as SVG files in
docs/brand/src/ (used to render the PNGs). Run from the repository root:

    python3 tools/brand_icons.py

Needs fontTools (pip install fonttools) to turn "Frost-flix" into paths,
using the Inter SemiBold font the app already ships (SIL OFL).
"""
import math
import os

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
SRC = os.path.join(ROOT, "docs", "brand", "src")
FONT = os.path.join(RES, "font", "inter_semibold.ttf")

NAME = "Frost-flix"

# Colours as (RRGGBB, alpha 0..255).
NAVY_TOP = ("10304F", 255)
NAVY_BOTTOM = ("061220", 255)
GLOW = ("6CC4F5", 100)
GLOW_MID = ("6CC4F5", 34)
GLOW_END = ("6CC4F5", 0)
PLATE_TOP = ("E8F6FF", 112)
PLATE_BOTTOM = ("8CC6EE", 34)
RIM_TOP = ("FFFFFF", 210)
RIM_BOTTOM = ("FFFFFF", 40)
SHEEN_TOP = ("FFFFFF", 56)
SHEEN_BOTTOM = ("FFFFFF", 0)
PLAY_TOP = ("FFFFFF", 255)
PLAY_BOTTOM = ("CBEBFF", 255)
FLAKE = ("E4F5FF", 235)
WHITE = ("FFFFFF", 255)


class T:
    """Uniform scale then offset, applied to every coordinate."""

    def __init__(self, s=1.0, x=0.0, y=0.0):
        self.s, self.x, self.y = s, x, y

    def p(self, x, y):
        return (x * self.s + self.x, y * self.s + self.y)

    def d(self, v):
        return v * self.s


def f(v):
    return ("%.2f" % v).rstrip("0").rstrip(".")


def pt(p):
    return f(p[0]) + "," + f(p[1])


# --- Shapes, in the 108 x 108 adaptive-icon space --------------------------

PLATE = (27.0, 27.0, 81.0, 81.0, 14.0)  # left, top, right, bottom, radius
PLAY = [(45.5, 39.0), (45.5, 69.0), (71.5, 54.0)]
PLAY_ROUND = 4.5
FLAKE_C, FLAKE_R, FLAKE_W = (70.0, 38.0), 5.5, 1.3


def rounded_rect(t, l, tp, r, b, rad):
    L, Tp = t.p(l, tp)
    R, B = t.p(r, b)
    q = t.d(rad)
    return (
        "M%s,%s H%s A%s,%s 0 0 1 %s,%s V%s A%s,%s 0 0 1 %s,%s H%s A%s,%s 0 0 1 %s,%s V%s A%s,%s 0 0 1 %s,%sZ"
        % (f(L + q), f(Tp), f(R - q), f(q), f(q), f(R), f(Tp + q), f(B - q), f(q), f(q), f(R - q), f(B),
           f(L + q), f(q), f(q), f(L), f(B - q), f(Tp + q), f(q), f(q), f(L + q), f(Tp))
    )


def plate(t):
    return rounded_rect(t, *PLATE)


def sheen(t):
    # Upper part of the plate, with a soft diagonal lower edge.
    l, tp, r, b, rad = PLATE
    q = t.d(rad)
    L, Tp = t.p(l, tp)
    R, _ = t.p(r, tp)
    return "M%s V%s A%s,%s 0 0 1 %s,%s H%s A%s,%s 0 0 1 %s,%s V%s C%s %s %sZ" % (
        pt(t.p(l, 58)), f(Tp + q), f(q), f(q), f(L + q), f(Tp), f(R - q), f(q), f(q), f(R), f(Tp + q),
        f(t.p(r, 44)[1]), pt(t.p(66, 47)), pt(t.p(44, 52)), pt(t.p(l, 58)))


def play(t):
    pts = PLAY
    out = []
    for i, v in enumerate(pts):
        prev, nxt = pts[i - 1], pts[(i + 1) % len(pts)]
        a = _toward(v, prev, PLAY_ROUND)
        b = _toward(v, nxt, PLAY_ROUND)
        out.append(("L" if out else "M") + pt(t.p(*a)))
        out.append("Q" + pt(t.p(*v)) + " " + pt(t.p(*b)))
    return " ".join(out) + "Z"


def _toward(a, b, d):
    dx, dy = b[0] - a[0], b[1] - a[1]
    n = math.hypot(dx, dy)
    return (a[0] + dx / n * d, a[1] + dy / n * d)


def flake(t):
    cx, cy = FLAKE_C
    r = FLAKE_R
    parts = []
    for k in range(6):
        a = math.radians(90 + 60 * k)
        ex, ey = cx + r * math.cos(a), cy - r * math.sin(a)
        parts.append("M%s L%s" % (pt(t.p(cx, cy)), pt(t.p(ex, ey))))
        # Two small branches on each arm.
        bx, by = cx + 0.58 * r * math.cos(a), cy - 0.58 * r * math.sin(a)
        for side in (-1, 1):
            b = a + side * math.radians(45)
            parts.append("M%s L%s" % (pt(t.p(bx, by)), pt(t.p(bx + 0.34 * r * math.cos(b), by - 0.34 * r * math.sin(b)))))
    return " ".join(parts)


# --- Output helpers --------------------------------------------------------

class Grad:
    def __init__(self, kind, coords, stops):
        self.kind, self.coords, self.stops = kind, coords, stops


def lin(t, x1, y1, x2, y2, *stops):
    a, b = t.p(x1, y1), t.p(x2, y2)
    return Grad("linear", (a[0], a[1], b[0], b[1]), stops)


def rad(t, cx, cy, r, *stops):
    c = t.p(cx, cy)
    return Grad("radial", (c[0], c[1], t.d(r)), stops)


def argb(c):
    return "#%02X%s" % (c[1], c[0])


def vd_paint(attr, g):
    if not isinstance(g, Grad):
        return ' android:%s="%s"' % (attr, argb(g)), ""
    if g.kind == "linear":
        geo = 'android:type="linear" android:startX="%s" android:startY="%s" android:endX="%s" android:endY="%s"' % tuple(map(f, g.coords))
    else:
        geo = 'android:type="radial" android:centerX="%s" android:centerY="%s" android:gradientRadius="%s"' % tuple(map(f, g.coords))
    items = "".join('\n                <item android:offset="%s" android:color="%s"/>' % (f(o), argb(c)) for o, c in g.stops)
    body = ('\n        <aapt:attr name="android:%s">\n            <gradient %s>%s\n            </gradient>\n        </aapt:attr>'
            % (attr, geo, items))
    return "", body


def vd_path(d, fill=None, stroke=None, width=None, cap=None):
    attrs, bodies = "", ""
    if fill is not None:
        a, b = vd_paint("fillColor", fill)
        attrs, bodies = attrs + a, bodies + b
    if stroke is not None:
        a, b = vd_paint("strokeColor", stroke)
        attrs, bodies = attrs + a, bodies + b
        attrs += ' android:strokeWidth="%s"' % f(width)
        if cap:
            attrs += ' android:strokeLineCap="%s" android:strokeLineJoin="round"' % cap
    if bodies:
        return '    <path%s\n        android:pathData="%s">%s\n    </path>\n' % (attrs, d, bodies)
    return '    <path%s\n        android:pathData="%s"/>\n' % (attrs, d)


def vd(w, h, paths):
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Generated by tools/brand_icons.py. Edit that script, not this file. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            '    android:width="%sdp" android:height="%sdp"\n'
            '    android:viewportWidth="%s" android:viewportHeight="%s">\n%s</vector>\n'
            % (w, h, w, h, "".join(paths)))


class Svg:
    def __init__(self):
        self.defs, self.body, self.n = [], [], 0

    def paint(self, g):
        if not isinstance(g, Grad):
            return "#" + g[0], g[1] / 255.0
        self.n += 1
        gid = "g%d" % self.n
        stops = "".join('<stop offset="%s" stop-color="#%s" stop-opacity="%s"/>' % (f(o), c[0], f(c[1] / 255.0)) for o, c in g.stops)
        if g.kind == "linear":
            self.defs.append('<linearGradient id="%s" gradientUnits="userSpaceOnUse" x1="%s" y1="%s" x2="%s" y2="%s">%s</linearGradient>'
                             % ((gid,) + tuple(map(f, g.coords)) + (stops,)))
        else:
            self.defs.append('<radialGradient id="%s" gradientUnits="userSpaceOnUse" cx="%s" cy="%s" r="%s">%s</radialGradient>'
                             % ((gid,) + tuple(map(f, g.coords)) + (stops,)))
        return "url(#%s)" % gid, 1.0

    def path(self, d, fill=None, stroke=None, width=None, cap=None):
        a = ""
        if fill is not None:
            c, o = self.paint(fill)
            a += ' fill="%s"' % c + (' fill-opacity="%s"' % f(o) if o < 1 else "")
        else:
            a += ' fill="none"'
        if stroke is not None:
            c, o = self.paint(stroke)
            a += ' stroke="%s" stroke-width="%s"' % (c, f(width)) + (' stroke-opacity="%s"' % f(o) if o < 1 else "")
            if cap:
                a += ' stroke-linecap="%s" stroke-linejoin="round"' % cap
        self.body.append('<path%s d="%s"/>' % (a, d))

    def out(self, w, h):
        return ('<svg xmlns="http://www.w3.org/2000/svg" width="%s" height="%s" viewBox="0 0 %s %s">\n'
                '<!-- Generated by tools/brand_icons.py. -->\n<defs>%s</defs>\n%s\n</svg>\n'
                % (w, h, w, h, "\n".join(self.defs), "\n".join(self.body)))


# --- Layers ----------------------------------------------------------------

def background_layers(w, h, glow_c, glow_r):
    return [
        ("M0,0h%sv%sh-%sz" % (w, h, w), dict(fill=lin(T(), 0, 0, w * 0.35, h, (0, NAVY_TOP), (1, NAVY_BOTTOM)))),
        ("M0,0h%sv%sh-%sz" % (w, h, w), dict(fill=rad(T(), glow_c[0], glow_c[1], glow_r, (0, GLOW), (0.45, GLOW_MID), (1, GLOW_END)))),
    ]


def mark_layers(t):
    l, tp, r, b, _ = PLATE
    return [
        (plate(t), dict(fill=lin(t, l, tp, r, b, (0, PLATE_TOP), (1, PLATE_BOTTOM)))),
        (sheen(t), dict(fill=lin(t, 0, tp, 0, 58, (0, SHEEN_TOP), (1, SHEEN_BOTTOM)))),
        (plate(t), dict(stroke=lin(t, 0, tp, 0, b, (0, RIM_TOP), (1, RIM_BOTTOM)), width=t.d(1.2))),
        (play(t), dict(fill=lin(t, 45, 39, 72, 69, (0, PLAY_TOP), (1, PLAY_BOTTOM)))),
        (flake(t), dict(stroke=FLAKE, width=t.d(FLAKE_W), cap="round")),
    ]


def mono_layers():
    t = T()
    inner = (PLATE[0] + 1.2, PLATE[1] + 1.2, PLATE[2] - 1.2, PLATE[3] - 1.2, PLATE[4] - 1.2)
    return [
        (rounded_rect(t, *inner), dict(stroke=WHITE, width=2.4)),
        (play(t), dict(fill=WHITE)),
        (flake(t), dict(stroke=WHITE, width=1.6, cap="round")),
    ]


def text_path(text, x, baseline, size, max_width):
    font = TTFont(FONT)
    gs = font.getGlyphSet()
    cmap = font.getBestCmap()
    upm = font["head"].unitsPerEm
    names = [cmap[ord(ch)] for ch in text]
    adv = sum(gs[n].width for n in names)
    size = min(size, max_width * upm / adv)
    s = size / upm
    pen = SVGPathPen(gs, ntos=f)
    cursor = 0
    for n in names:
        gs[n].draw(TransformPen(pen, (s, 0, 0, -s, x + cursor * s, baseline)))
        cursor += gs[n].width
    return pen.getCommands(), adv * s


def banner_layers():
    w, h = 320, 180
    layers = background_layers(w, h, (64, 70), 230)
    # The mark, 76 px square, centred vertically on the left.
    side = 76.0
    s = side / (PLATE[2] - PLATE[0])
    ox, oy = 26.0, (h - side) / 2
    t = T(s, ox - PLATE[0] * s, oy - PLATE[1] * s)
    layers += mark_layers(t)
    tx = ox + side + 16
    d, _ = text_path(NAME, tx, 103, 38, w - tx - 22)
    layers.append((d, dict(fill=WHITE)))
    return w, h, layers


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", newline="\n") as fh:
        fh.write(text)


def emit(name, w, h, layers, svg_name=None):
    write(os.path.join(RES, "drawable", name + ".xml"), vd(w, h, [vd_path(d, **kw) for d, kw in layers]))
    if svg_name:
        sv = Svg()
        for d, kw in layers:
            sv.path(d, **kw)
        write(os.path.join(SRC, svg_name), sv.out(w, h))


def main():
    bg = background_layers(108, 108, (36, 26), 74)
    fg = mark_layers(T())
    emit("ic_launcher_background", 108, 108, bg, "icon_background.svg")
    emit("ic_launcher_foreground", 108, 108, fg, "icon_foreground.svg")
    emit("ic_launcher_monochrome", 108, 108, mono_layers(), "icon_monochrome.svg")
    sv = Svg()
    for d, kw in bg + fg:
        sv.path(d, **kw)
    write(os.path.join(SRC, "icon.svg"), sv.out(108, 108))
    w, h, bl = banner_layers()
    emit("ic_banner_frost", w, h, bl, "banner.svg")


if __name__ == "__main__":
    main()
