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


def corner_brackets(draw, box, reach=22):
    """Four L-shaped corner marks around [box]: what a selection's frame looks like."""
    left, top, right, bottom = box
    polyline(draw, [(left + reach, top), (left, top), (left, top + reach)])
    polyline(draw, [(right - reach, top), (right, top), (right, top + reach)])
    polyline(draw, [(left, bottom - reach), (left, bottom), (left + reach, bottom)])
    polyline(draw, [(right, bottom - reach), (right, bottom), (right - reach, bottom)])


def action_group():
    """Two squares inside one set of corner marks: taken as one (FR7)."""
    image, draw = new_canvas()
    draw.rectangle(scaled((30, 30, 66, 66)), outline="black", width=STROKE * SCALE)
    draw.rectangle(scaled((62, 62, 98, 98)), outline="black", width=STROKE * SCALE)
    corner_brackets(draw, (12, 12, 116, 116))
    save(image, "action-group")


def action_ungroup():
    """The same two squares, one of them outside the marks: on their own again (FR7)."""
    image, draw = new_canvas()
    draw.rectangle(scaled((22, 22, 58, 58)), outline="black", width=STROKE * SCALE)
    draw.rectangle(scaled((74, 74, 110, 110)), outline="black", width=STROKE * SCALE)
    corner_brackets(draw, (8, 8, 72, 72), reach=16)
    save(image, "action-ungroup")


def action_more():
    """Three stacked dots: more actions (FR18)."""
    image, draw = new_canvas()
    for y in (30, 64, 98):
        draw.ellipse(scaled((56, y - 8, 72, y + 8)), fill="black")
    save(image, "action-more")


SQUARE = (26, 26, 102, 102)


def fill_icon(name, interior_alpha=0, hatched=False, gradient=False):
    """A square in each fill style (FR19): outline only, a light interior, a solid one, hatched, or
    fading down the square (#59)."""
    image, draw = new_canvas()
    if gradient:
        # A column of rows, each a little fainter than the last, kept inside the square.
        ramp = Image.new("RGBA", image.size, (0, 0, 0, 0))
        ramp_draw = ImageDraw.Draw(ramp)
        top, bottom = SQUARE[1], SQUARE[3]
        for y in range(top, bottom):
            fade = 1 - (y - top) / (bottom - top)
            ramp_draw.line(scaled((SQUARE[0], y, SQUARE[2], y)), fill=(0, 0, 0, round(215 * fade)), width=SCALE)
        mask = Image.new("L", image.size, 0)
        ImageDraw.Draw(mask).rounded_rectangle(scaled(SQUARE), radius=10 * SCALE, fill=255)
        # The mask is the rounded rect alone. Passing the ramp through composite would hand paste an
        # alpha band that is already the ramp's, and paste multiplies, so the fade came out squared.
        image.paste(ramp, (0, 0), mask)
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


def tool_help():
    """A bold question mark, no ring (its round button is the ring): toggles the onboarding hints."""
    import math

    image, draw = new_canvas()
    # The hook, swept clockwise from 9 o'clock over the top to 5 o'clock (sampled as dash_icon's hand-drawn circle is),
    # then in to the middle and down the stem.
    points = []
    for degrees in range(180, 425, 5):
        angle = math.radians(degrees)
        points.append((64 + 24 * math.cos(angle), 40 + 24 * math.sin(angle)))
    polyline(draw, points + [(64, 74), (64, 84)], width=13)
    draw.ellipse(scaled((56, 96, 72, 112)), fill="black")
    save(image, "tool-help")


def tool_draw():
    """A pencil: freehand drawing (FR5)."""
    image, draw = new_canvas()
    polyline(draw, [(24, 104), (24, 82), (82, 24), (104, 46), (46, 104), (24, 104)])
    polyline(draw, [(70, 36), (92, 58)])
    save(image, "tool-draw")


def tool_eraser():
    """An eraser block on its base line (FR20)."""
    image, draw = new_canvas()
    polyline(draw, [(44, 104), (22, 82), (76, 28), (106, 58), (60, 104), (44, 104)])
    polyline(draw, [(49, 55), (79, 85)])
    polyline(draw, [(60, 104), (106, 104)])
    save(image, "tool-eraser")


def tool_text():
    """A T: text boxes (FR6)."""
    image, draw = new_canvas()
    polyline(draw, [(30, 28), (98, 28)])
    polyline(draw, [(64, 28), (64, 104)])
    save(image, "tool-text")


def tool_note():
    """A sticky note with its corner turned up (FR21)."""
    image, draw = new_canvas()
    polyline(draw, [(24, 24), (104, 24), (104, 76), (76, 104), (24, 104), (24, 24)])
    polyline(draw, [(104, 76), (76, 76), (76, 104)])
    save(image, "tool-note")


def tool_table():
    """A 3 by 3 grid: tables (FR24)."""
    image, draw = new_canvas()
    draw.rounded_rectangle(scaled((20, 24, 108, 104)), radius=8 * SCALE, outline="black", width=STROKE * SCALE)
    for y in (51, 77):
        polyline(draw, [(20, y), (108, y)], width=7)
    for x in (49, 79):
        polyline(draw, [(x, 24), (x, 104)], width=7)
    save(image, "tool-table")


def badge_back_arrow():
    """The back badge's arrow (#34): white, outlined in black, pointing left, as the firmware's return badge
    draws it and BackBadgeWindow.kt copies it. Shown untinted, over the badge's black box."""
    image, draw = new_canvas()
    corners = [(0, 0), (36, -30), (36, -12), (80, -12), (80, 12), (36, 12), (36, 30)]
    points = [((8 + x * 1.4) * SCALE, (64 + y * 1.4) * SCALE) for x, y in corners]
    draw.polygon(points, fill="white", outline="black", width=7 * SCALE)
    save(image, "badge-back-arrow")


# The lightning bolt the canvas draws in a linked element's badge (CanvasRenderer.LINK_GLYPH_BOLT),
# so the button and the mark it puts on the element are plainly the same thing.
LINK_BOLT = [(4, -17), (-10, 3), (-1, 3), (-4, 17), (10, -3), (1, -3)]


def bolt_outline(scale=2.7):
    points = [(64 + x * scale, 64 + y * scale) for x, y in LINK_BOLT]
    return [*points, points[0]]


def action_link():
    """The link badge's bolt: this element jumps somewhere (#34)."""
    image, draw = new_canvas()
    polyline(draw, bolt_outline())
    save(image, "action-link")


def action_link_canvas():
    """The canvas link's two sheets, with an arrow into the front one: this element brings up another
    canvas of the same note (#2). The arrow is what keeps it from reading as action-duplicate, which
    is two plain sheets."""
    image, draw = new_canvas()
    # The sheet behind, up and to the right; only the corner that shows past the front one.
    polyline(draw, [(56, 30), (104, 30), (104, 78)])
    draw.rounded_rectangle(scaled((24, 50, 88, 108)), radius=8 * SCALE, outline="black", width=STROKE * SCALE)
    # An arrow into it, so the icon says "go to that canvas" rather than "make another".
    polyline(draw, [(40, 79), (70, 79)])
    polyline(draw, [(60, 69), (70, 79), (60, 89)])
    save(image, "action-link-canvas")


def action_unlink():
    """The same bolt struck through: the link comes off. The stroke runs across the bolt's own
    diagonal rather than along it, or the two read as one shape at toolbar size."""
    image, draw = new_canvas()
    polyline(draw, bolt_outline(scale=2.4))
    polyline(draw, [(18, 18), (110, 110)])
    save(image, "action-unlink")


ICONS = [
    action_save_to_note,
    action_duplicate,
    action_group,
    action_ungroup,
    action_more,
    action_link,
    action_link_canvas,
    action_unlink,
    lambda: fill_icon("fill-none"),
    lambda: fill_icon("fill-semi", interior_alpha=80),
    lambda: fill_icon("fill-solid", interior_alpha=255),
    lambda: fill_icon("fill-pattern", hatched=True),
    lambda: fill_icon("fill-gradient", gradient=True),
    lambda: dash_icon("dash-draw", "draw"),
    lambda: dash_icon("dash-dashed", "dashed"),
    lambda: dash_icon("dash-dotted", "dotted"),
    lambda: dash_icon("dash-solid", "solid"),
    lambda: chevron("chevron-up", up=True),
    lambda: chevron("chevron-down", up=False),
    tool_help,
    tool_draw,
    tool_eraser,
    tool_text,
    tool_note,
    tool_table,
    badge_back_arrow,
]

if __name__ == "__main__":
    for draw_icon in ICONS:
        draw_icon()
    print(f"drew {len(ICONS)} icons into {OUT}")
