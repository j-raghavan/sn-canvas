#!/usr/bin/env python3
"""Draws docs/images/features.png: the Canvas screen with every control
labelled, in the hand-drawn style of the in-app hints (Virgil, the Excalidraw
font, and the same blue).

The screen is mostly empty canvas, so it is shown as two bands, the top of the
screen and the bottom of it, with the drawing area between them left out. That
keeps every label beside the thing it names.

Take the screenshot with the style panel open, the hints off and nothing
selected, so every control is on screen and the action bar reads as the greyed
row it is before a selection:

    adb shell screencap -p /sdcard/f.png && adb pull /sdcard/f.png shot.png
    python3 scripts/draw_features.py shot.png [--debug]

--debug marks every arrow target, to check them against the screenshot.
Requires Pillow.
"""
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "images" / "features.png"
FONT = ROOT / "scripts" / "fonts" / "Virgil-Regular.ttf"

# The screenshot these coordinates were measured against. Anything else is refused
# rather than silently mislabelled, since every arrow points at a fixed pixel.
SHOT = (1920, 2560)

CANVAS = (2100, 1660)

# The two bands of the screenshot: (top, bottom) in screenshot pixels, where the
# band's top-left corner lands in the finished image, and what it is scaled by.
# The bars are shown larger than the top of the screen, as the detail they are,
# which also keeps their labels beside them rather than a page away.
TOP_BAND = (0, 900)
TOP_AT = (300, 140)
TOP_SCALE = 0.55
BOTTOM_BAND = (2290, 2530)
BOTTOM_AT = (186, 1010)
BOTTOM_SCALE = 0.9

INK = "#1971c2"
TITLE_INK = "#1e1e1e"
FRAME = "#adb5bd"
STROKE = 3

# Where each control sits in the screenshot, measured once.
HEADER_Y = 51
ACTIONS_Y = 2369
TOOLS_Y = 2449
HEADER = {"export": 1711, "save": 1782, "close": 1855}
STYLE_TOGGLE = (1833, 163)
COLOURS_AT = (1836, 352)
OPACITY_AT = (1852, 548)
FILL_AT = (1836, 646)
OUTLINE_AT = (1836, 728)
SIZE_AT = (1836, 810)
ACTIONS = [662, 736, 810, 884, 960, 1034, 1109, 1183, 1258]
TOOLS = [495, 576, 659, 742, 823, 906, 992, 1073, 1153, 1235, 1318, 1425]


def place(x, y):
    """A point on the screenshot, in the finished image; None when it is in neither band."""
    for (top, bottom), (at_x, at_y), scale in (
        (TOP_BAND, TOP_AT, TOP_SCALE),
        (BOTTOM_BAND, BOTTOM_AT, BOTTOM_SCALE),
    ):
        if top <= y <= bottom:
            return (at_x + x * scale, at_y + (y - top) * scale)
    return None


def curve(draw, start, end, bend):
    (x0, y0), (x2, y2) = start, end
    mx, my = (x0 + x2) / 2, (y0 + y2) / 2
    dx, dy = x2 - x0, y2 - y0
    cx, cy = mx - dy * bend, my + dx * bend
    points = [
        (
            (1 - t) ** 2 * x0 + 2 * (1 - t) * t * cx + t * t * x2,
            (1 - t) ** 2 * y0 + 2 * (1 - t) * t * cy + t * t * y2,
        )
        for t in (i / 40 for i in range(41))
    ]
    draw.line(points, fill=INK, width=STROKE, joint="curve")
    return points[-2], points[-1]


def arrowhead(draw, before, tip, size=15):
    angle = math.atan2(tip[1] - before[1], tip[0] - before[0])
    for spread in (2.6, -2.6):
        draw.line(
            [tip, (tip[0] + size * math.cos(angle + spread), tip[1] + size * math.sin(angle + spread))],
            fill=INK,
            width=STROKE,
        )


def label(draw, font, text, at, target, align="left", bend=0.12, gap=16):
    """Writes [text] with its [align] edge at [at], and curves an arrow to [target]."""
    lines = text.split("\n")
    widths = [font.getbbox(line)[2] - font.getbbox(line)[0] for line in lines]
    line_h = max(font.getbbox(line)[3] - font.getbbox(line)[1] for line in lines) + 14
    x, y = at
    for i, line in enumerate(lines):
        draw.text((x if align == "left" else x - widths[i], y + i * line_h), line, font=font, fill=INK)
    left = x if align == "left" else x - max(widths)
    right = left + max(widths)
    # The arrow leaves whichever side of the text faces the target, and its vertical
    # end when the target is well above or below rather than beside it.
    mid_y = y + line_h * len(lines) / 2
    if target[1] > y + line_h * len(lines) + 40:
        start = ((left + right) / 2, y + line_h * len(lines) + 6)
    elif target[1] < y - 40:
        start = ((left + right) / 2, y - 6)
    else:
        start = (right + gap, mid_y) if target[0] > right else (left - gap, mid_y)
    arrowhead(draw, *curve(draw, start, target, bend))


def band(image, draw, shot, span, at, scale):
    crop = shot.crop((0, span[0], shot.width, span[1]))
    scaled = crop.resize((int(crop.width * scale), int(crop.height * scale)), Image.LANCZOS)
    image.paste(scaled, (int(at[0]), int(at[1])))
    draw.rectangle([at[0], at[1], at[0] + scaled.width, at[1] + scaled.height], outline=FRAME, width=2)
    return scaled.height


def main(shot_path, debug=False):
    shot = Image.open(shot_path).convert("RGB")
    if shot.size != SHOT:
        raise SystemExit(f"expected a {SHOT[0]}x{SHOT[1]} screenshot, got {shot.size[0]}x{shot.size[1]}")

    image = Image.new("RGB", CANVAS, "white")
    draw = ImageDraw.Draw(image)
    title_font = ImageFont.truetype(str(FONT), 54)
    note_font = ImageFont.truetype(str(FONT), 26)
    font = ImageFont.truetype(str(FONT), 30)

    draw.text((70, 50), "Canvas: what every button does", font=title_font, fill=TITLE_INK)

    top_h = band(image, draw, shot, TOP_BAND, TOP_AT, TOP_SCALE)
    band(image, draw, shot, BOTTOM_BAND, BOTTOM_AT, BOTTOM_SCALE)
    # What was left out between the two bands.
    gap_y = TOP_AT[1] + top_h + 34
    caption = "the canvas itself, left out here; the bars below are shown larger"
    caption_w = note_font.getbbox(caption)[2] - note_font.getbbox(caption)[0]
    caption_x = (CANVAS[0] - caption_w) / 2
    draw.text((caption_x, gap_y - 16), caption, font=note_font, fill=FRAME)
    # A rule either side of the caption, never through it.
    for left, right in ((caption_x - 210, caption_x - 50), (caption_x + caption_w + 50, caption_x + caption_w + 210)):
        draw.line([(left, gap_y), (right, gap_y)], fill=FRAME, width=2)

    right = 2060
    for text, target, y in (
        ("Export to PDF", place(HEADER["export"], HEADER_Y), 145),
        ("Save to note", place(HEADER["save"], HEADER_Y), 199),
        ("Close", place(HEADER["close"], HEADER_Y), 253),
        ("Colours & styles: tap to open or close", place(*STYLE_TOGGLE), 307),
        ("12 colours, named below", place(*COLOURS_AT), 361),
        ("Opacity", place(*OPACITY_AT), 415),
        ("Fill: none, semi, solid, hatched", place(*FILL_AT), 469),
        ("Outline: hand-drawn, dashed, dotted, solid", place(*OUTLINE_AT), 523),
        ("Size: S, M, L, XL", place(*SIZE_AT), 577),
    ):
        label(draw, font, text, (right, y), target, align="right", bend=-0.08)

    # The action bar, labelled in the space above it.
    for text, index, at, align in (
        ("Undo", 0, (60, 700), "left"),
        ("Redo", 1, (60, 754), "left"),
        ("Delete", 2, (60, 808), "left"),
        ("Duplicate", 3, (60, 862), "left"),
        ("Group", 4, (60, 916), "left"),
        ("More: arrange, zoom, tables,\ncanvases in this note, new canvas", 8, (2060, 700), "right"),
        ("Take the link off", 7, (2060, 790), "right"),
        ("Link this to a note", 6, (2060, 844), "right"),
        ("Ungroup", 5, (2060, 898), "right"),
    ):
        label(draw, font, text, at, place(ACTIONS[index], ACTIONS_Y), align=align, bend=0.06)

    # The toolbar, labelled below, the first half down the left and the rest down the right.
    for row, (text, index) in enumerate(
        (("Select, move & pan", 0), ("Rectangle", 1), ("Ellipse", 2), ("Line", 3), ("Arrow (sticks to shapes)", 4), ("Pencil", 5))
    ):
        label(draw, font, text, (60, 1300 + row * 60), place(TOOLS[index], TOOLS_Y), align="left", bend=0.1)
    for row, (text, index) in enumerate(
        (
            ("Show or hide the hints", 11),
            ("Insert an image", 10),
            ("Table", 9),
            ("Sticky note", 8),
            ("Text", 7),
            ("Eraser (tap again to clear)", 6),
        )
    ):
        label(draw, font, text, (2060, 1300 + row * 60), place(TOOLS[index], TOOLS_Y), align="right", bend=-0.1)

    if debug:
        for x in ACTIONS:
            spot = place(x, ACTIONS_Y)
            draw.ellipse([spot[0] - 5, spot[1] - 5, spot[0] + 5, spot[1] + 5], fill="red")
        for x in TOOLS:
            spot = place(x, TOOLS_Y)
            draw.ellipse([spot[0] - 5, spot[1] - 5, spot[0] + 5, spot[1] + 5], fill="red")
        for spot in (
            place(HEADER["export"], HEADER_Y),
            place(HEADER["save"], HEADER_Y),
            place(HEADER["close"], HEADER_Y),
            place(*STYLE_TOGGLE),
            place(*COLOURS_AT),
            place(*OPACITY_AT),
            place(*FILL_AT),
            place(*OUTLINE_AT),
            place(*SIZE_AT),
        ):
            draw.ellipse([spot[0] - 5, spot[1] - 5, spot[0] + 5, spot[1] + 5], fill="red")

    image.save(OUT)
    print(f"wrote {OUT} ({image.width}x{image.height})")


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) != 1:
        raise SystemExit(__doc__)
    main(args[0], debug="--debug" in sys.argv)
