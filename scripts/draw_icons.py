#!/usr/bin/env python3
"""Draws toolbar icons in the style of assets/icons/: 128px squares, black
round-capped strokes on transparent (Tabler-like), drawn at 4x and
downsampled for anti-aliasing. The screen tints them with `tintColor`.

Usage: python3 scripts/draw_icons.py            (requires Pillow)
"""
from pathlib import Path

from PIL import Image, ImageDraw

SIZE = 128
SCALE = 4
STROKE = 9
OUT = Path(__file__).resolve().parent.parent / "assets" / "icons"


def new_canvas():
    image = Image.new("RGBA", (SIZE * SCALE, SIZE * SCALE), (0, 0, 0, 0))
    return image, ImageDraw.Draw(image)


def polyline(draw, points, width=STROKE):
    """A stroked polyline with round joins and round end caps."""
    scaled = [(x * SCALE, y * SCALE) for x, y in points]
    draw.line(scaled, fill="black", width=width * SCALE, joint="curve")
    radius = width * SCALE / 2
    for x, y in scaled:
        draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill="black")


def save(image, name):
    image.resize((SIZE, SIZE), Image.LANCZOS).save(OUT / f"{name}.png")


def action_save_to_note():
    """A page with a folded corner and a plus: add this canvas to the note (FR23)."""
    image, draw = new_canvas()
    polyline(draw, [(32, 16), (72, 16), (96, 40), (96, 112), (32, 112), (32, 16)])
    polyline(draw, [(72, 16), (72, 40), (96, 40)])
    polyline(draw, [(48, 76), (80, 76)])
    polyline(draw, [(64, 60), (64, 92)])
    save(image, "action-save-to-note")


def scaled(box):
    return tuple(v * SCALE for v in box)


def action_duplicate():
    """Two overlapping squares: duplicate (FR18)."""
    image, draw = new_canvas()
    draw.rounded_rectangle(scaled((44, 44, 108, 108)), radius=10 * SCALE, outline="black", width=STROKE * SCALE)
    polyline(draw, [(84, 32), (84, 20), (20, 20), (20, 84), (32, 84)])
    save(image, "action-duplicate")


def action_more():
    """Three stacked dots: more actions (FR18)."""
    image, draw = new_canvas()
    for y in (30, 64, 98):
        draw.ellipse(scaled((56, y - 8, 72, y + 8)), fill="black")
    save(image, "action-more")


SQUARE = (26, 26, 102, 102)


def fill_icon(name, interior_alpha=0, hatched=False):
    """A square in each fill style (FR19): outline only, a light interior, a solid one, or hatched."""
    image, draw = new_canvas()
    if interior_alpha:
        draw.rounded_rectangle(scaled(SQUARE), radius=10 * SCALE, fill=(0, 0, 0, interior_alpha))
    if hatched:
        # Diagonal lines on their own layer, kept only inside the square.
        lines = Image.new("RGBA", image.size, (0, 0, 0, 0))
        lines_draw = ImageDraw.Draw(lines)
        for offset in range(-SIZE, SIZE, 18):
            lines_draw.line(scaled((offset, SIZE, offset + SIZE, 0)), fill="black", width=5 * SCALE)
        mask = Image.new("L", image.size, 0)
        ImageDraw.Draw(mask).rounded_rectangle(scaled(SQUARE), radius=10 * SCALE, fill=255)
        image.paste(lines, (0, 0), Image.composite(lines, Image.new("RGBA", image.size), mask))
    draw.rounded_rectangle(scaled(SQUARE), radius=10 * SCALE, outline="black", width=STROKE * SCALE)
    save(image, name)


CIRCLE = (24, 24, 104, 104)


def dash_icon(name, kind):
    """A circle in each dash style (FR19): hand-drawn, dashed, dotted or solid."""
    import math

    image, draw = new_canvas()
    if kind == "solid":
        draw.ellipse(scaled(CIRCLE), outline="black", width=STROKE * SCALE)
    elif kind == "dashed":
        for i in range(8):
            draw.arc(scaled(CIRCLE), i * 45, i * 45 + 26, fill="black", width=STROKE * SCALE)
    elif kind == "dotted":
        for i in range(12):
            angle = 2 * math.pi * i / 12
            x, y = 64 + 40 * math.cos(angle), 64 + 40 * math.sin(angle)
            draw.ellipse(scaled((x - 5, y - 5, x + 5, y + 5)), fill="black")
    else:
        # Hand-drawn: nearly round, and the stroke runs a little past its start.
        points = []
        for degrees in range(0, 400, 5):
            angle = math.radians(degrees)
            radius = 38 + 1.5 * math.sin(2 * angle + 0.7) - (3 if degrees > 360 else 0)
            points.append((64 + radius * math.cos(angle), 64 + radius * math.sin(angle)))
        polyline(draw, points)
    save(image, name)


def chevron(name, up):
    """The style panel toggle's open/close hint."""
    image, draw = new_canvas()
    polyline(draw, [(34, 78), (64, 48), (94, 78)] if up else [(34, 50), (64, 80), (94, 50)], width=11)
    save(image, name)


ICONS = [
    action_save_to_note,
    action_duplicate,
    action_more,
    lambda: fill_icon("fill-none"),
    lambda: fill_icon("fill-semi", interior_alpha=80),
    lambda: fill_icon("fill-solid", interior_alpha=255),
    lambda: fill_icon("fill-pattern", hatched=True),
    lambda: dash_icon("dash-draw", "draw"),
    lambda: dash_icon("dash-dashed", "dashed"),
    lambda: dash_icon("dash-dotted", "dotted"),
    lambda: dash_icon("dash-solid", "solid"),
    lambda: chevron("chevron-up", up=True),
    lambda: chevron("chevron-down", up=False),
]

if __name__ == "__main__":
    for draw_icon in ICONS:
        draw_icon()
    print(f"drew {len(ICONS)} icons into {OUT}")
