#!/usr/bin/env python3
"""Draws the tour pages (src/ui/HelpTour.tsx, #71): one PNG a page, in Virgil,
Excalidraw's hand-drawn font, to match the arrows in scripts/draw_hints.py.

The rows use the app's own icons from assets/icons rather than drawings of
them, so a page cannot come to show a button that no longer looks like that.
What has no icon, because it is a line in the menu rather than a button, is
written as the words the menu itself shows.

The tour says what things do; the arrows say which control is which. A page
that only named the buttons would be the arrows again with more taps.

Usage: python3 scripts/draw_tour.py             (requires Pillow)
"""
import json
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
FONT = ROOT / "scripts" / "fonts" / "Virgil-Regular.ttf"
ICONS = ROOT / "assets" / "icons"
OUT = ROOT / "assets" / "tour"

PX_PER_DP = 2
SS = 4                  # drawn larger, then downsampled, as the hints are
PAGE_W_DP = 520
PAD_DP = 24
TITLE_DP = 28
BODY_DP = 19
ROW_DP = 46             # a row's height: icon and its line of text
ICON_DP = 28
GAP_DP = 12             # between the icon and its text
LINE_DP = 25            # between wrapped body lines
RULE_DP = 1.2
# The arrow that runs from a page to the control it is about, drawn the way the hints' are so the
# two read as one hand. There is no SVG on this side, so it is a PNG positioned by its tip, and
# tour.json carries where that tip sits inside it.
ARROW_STROKE_DP = 1.8
ARROW_HEAD_DP = 12
ARROW_HEAD_ANGLE = math.radians(26)
ARROW_WOBBLE_DP = 1.0

# Which control each page is about, as (the way the arrow runs, its reach across, its reach along).
# Every page has something on screen to point at, the three dots page included: what is behind the
# button is not visible, but the button is, and that is the half an arrow can answer.
ARROWS = {
    1: ("down-right", 150, 300),   # the toolbar, and the tool it names
    2: ("up-right", 120, 210),     # the style button, top right
    3: ("down-right", 120, 190),   # the action bar above the tools
    4: ("down-right", 200, 190),   # the three dots at the end of that bar
    5: ("down-left", 140, 190),    # the link buttons on the same bar
    6: ("up-right", 150, 240),     # the header, along the top
}

class MENU(list):
    """A page's rows drawn as a picture of the menu they live in, rather than as a list of them."""


class PANEL(list):
    """Rows drawn as a picture of the style panel: its grid of colours, then its rows of options."""


class DOCK(list):
    """Rows drawn as a picture of a bar of buttons: the icons in a row, then what each one is.

    Every page draws the thing it is about. None of it is on screen while the tour is being read:
    the menu and the style panel are behind buttons, and the bar of actions is there but greyed and
    inert until something is selected, which is the state nobody is in while reading this.
    """


# Each page: its title, an opening line or two, then rows of (icon | None, what it is, what it does).
# A row with no icon is something the ⋮ menu writes out in words, which is the half the arrows
# cannot reach at all.
PAGES = [
    (
        "The tools",
        ["Everything you draw stays an object.", "Pick it up and change it later."],
        DOCK(
            [
                ("tool-draw", "Draw", "freehand, and a calligraphy pen"),
                ("tool-rectangle", "Shapes", "rectangle, ellipse, line, arrow"),
                ("tool-text", "Text", "and sticky notes, typed"),
                ("tool-table", "Table", "drag out rows and columns"),
                ("tool-image", "Image", "from the device"),
                ("tool-eraser", "Eraser", "tap again to clear the canvas"),
            ]
        ),
    ),
    (
        "Colour and style",
        ["The round button at the top right", "opens this. It sets what you draw", "next, and changes what is selected."],
        # Drawn as the panel, for the same reason the menu is: it is behind a button, so it is not
        # there to look at while this is being read.
        PANEL(
            [
                ("colours", "twelve, each its own grey here"),
                ("opacity", "how much of the page shows through"),
                (["fill-none", "fill-semi", "fill-solid", "fill-pattern"], "none, semi, solid, hatched, gradient"),
                (["dash-draw", "dash-dashed", "dash-dotted", "dash-solid"], "hand-drawn, dashed, dotted, solid"),
                ("size", "how thick a stroke, how big the text"),
            ]
        ),
    ),
    (
        "Once something is selected",
        ["Tap an object with the hand tool.", "The bar above the tools wakes up."],
        DOCK(
            [
                ("action-undo", "Undo", "and redo, beside it"),
                ("action-delete", "Delete", "takes it off the canvas"),
                ("action-duplicate", "Duplicate", "a copy, just beside it"),
                ("action-group", "Group", "several, moved as one"),
                ("action-ungroup", "Ungroup", "lets them go again"),
                ("action-more", "More", "the three dots, over the page"),
            ]
        ),
    ),
    (
        "Behind the three dots",
        ["Tap them and this opens. The table", "lines are only there while a table is", "selected."],
        # Drawn as the menu itself rather than listed, because the menu is not on screen to look at
        # while this is being read. A list of its lines teaches nothing to recognise later.
        # The lines as the menu lists them, and nothing else. Notes floating at the right gave it a
        # ragged edge and, worse, made it less like the menu it is a picture of.
        MENU(
            [
                ("Bring to front",),
                ("Send to back",),
                ("Add row",),
                ("Add column",),
                ("Remove this row",),
                ("Remove this column",),
                ("Zoom to fit",),
                ("Zoom to 100%",),
                ("Clear canvas",),
                ("Canvases in this note…",),
                ("New canvas",),
                ("Take the tour",),
            ]
        ),
    ),
    (
        "Linking things up",
        ["An object can lead somewhere.", "Tap it with the hand tool to follow,", "and a badge brings you back."],
        DOCK(
            [
                ("action-link", "Link to note", "opens that note, at its page"),
                ("action-link-canvas", "Link to canvas", "another of this note's"),
                ("action-unlink", "Remove link", "leaves the object be"),
            ]
        ),
    ),
    (
        "Getting a canvas out",
        ["Along the top of the screen. A canvas", "in a note is grey; the PDF keeps the", "colours. The tour is in the menu."],
        DOCK(
            [
                ("action-save", "Export to PDF", "the whole canvas, in colour"),
                ("action-save-to-note", "Save to note", "a thumbnail, on this page"),
                ("action-close", "Close", "saves first, always"),
            ]
        ),
    ),
]


def draw_menu(draw, image, rows, x, y, width, font, note_font):
    """A facsimile of the ⋮ menu: the panel it is, with its lines in it, so it can be recognised."""
    scale = PX_PER_DP * SS
    line_h = 30
    height = line_h * len(rows) + 10
    draw.rounded_rectangle(
        (x * scale, y * scale, (x + width) * scale, (y + height) * scale),
        radius=round(12 * scale),
        outline="black",
        width=round(1.4 * scale),
    )
    at = y + 5
    for (label,) in rows:
        draw.text(((x + 14) * scale, (at + 5) * scale), label, font=font, fill="black")
        at += line_h
    return height


def draw_dock(draw, image, rows, x, y, width, font, note_font):
    """A facsimile of a bar of buttons: the icons in a row inside it, then what each one is."""
    scale = PX_PER_DP * SS
    icon, slot, bar_h = 26, 46, 44
    bar_w = slot * len(rows) + 12
    # Centred in the card, as the real bar is centred on the screen. Left-aligned it read as the
    # start of a list rather than as the thing it is a picture of.
    bar_x = x + (width - bar_w) / 2
    draw.rounded_rectangle(
        (bar_x * scale, y * scale, (bar_x + bar_w) * scale, (y + bar_h) * scale),
        radius=round(12 * scale),
        outline="black",
        width=round(1.4 * scale),
    )
    for i, (icon_name, _, _) in enumerate(rows):
        bx = bar_x + 6 + i * slot + (slot - icon) / 2
        image.alpha_composite(load_icon(icon_name, round(icon * scale)), (round(bx * scale), round((y + (bar_h - icon) / 2) * scale)))
    at = y + bar_h + 22
    # The widest name sets where the second column starts, so the gap is the same on every row
    # instead of opening and closing with the length of each word.
    names = x + max(font.getlength(what) for _, what, _ in rows) / scale + 18
    for _, what, does in rows:
        # In the order the buttons are in, left to right, which is the whole of the tie between them.
        # A tick under each button pretended to line up with a list that runs the other way.
        draw.text((x * scale, at * scale), what, font=font, fill="black")
        draw.text((names * scale, (at + 2) * scale), does, font=note_font, fill="black")
        at += 27
    return bar_h + 22 + 27 * len(rows)


def draw_panel(draw, image, rows, x, y, width, font, note_font):
    """A facsimile of the style panel: the colour grid, then a row of options for each line."""
    scale = PX_PER_DP * SS
    swatch, gap, line_h = 17, 7, 34
    height = line_h * len(rows) + 14
    draw.rounded_rectangle(
        (x * scale, y * scale, (x + width) * scale, (y + height) * scale),
        radius=round(12 * scale),
        outline="black",
        width=round(1.4 * scale),
    )
    at = y + 8
    for name, note in rows:
        if name == "colours":
            for i in range(4):
                cx = x + 14 + i * (swatch + gap)
                draw.ellipse(
                    ((cx) * scale, (at + 4) * scale, (cx + swatch) * scale, (at + 4 + swatch) * scale),
                    outline="black",
                    width=round(1.2 * scale),
                    fill="black" if i == 0 else None,
                )
        elif name == "opacity":
            y0 = at + 12
            draw.line([((x + 14) * scale, y0 * scale), ((x + 14 + 4 * (swatch + gap) - gap) * scale, y0 * scale)], fill="black", width=round(1.2 * scale))
            draw.ellipse(((x + 60) * scale, (y0 - 5) * scale, (x + 70) * scale, (y0 + 5) * scale), outline="black", width=round(1.2 * scale), fill="white")
        elif isinstance(name, list):
            # The panel's own icons, so what is drawn here is what is seen there.
            for i, icon_name in enumerate(name):
                bx = x + 14 + i * (swatch + gap)
                image.alpha_composite(load_icon(icon_name, round(swatch * scale)), (round(bx * scale), round((at + 4) * scale)))
        else:
            for i, letter in enumerate(("S", "M", "L", "XL")):
                bx = x + 14 + i * (swatch + gap)
                draw.text((bx * scale, (at + 4) * scale), letter, font=note_font, fill="black")
        draw.text(((x + 14 + 4 * (swatch + gap) + 10) * scale, (at + 6) * scale), note, font=note_font, fill="black")
        at += line_h
    return height


def cubic(p0, p1, p2, p3, t):
    u = 1 - t
    return tuple(u**3 * a + 3 * u * u * t * b + 3 * u * t * t * c + t**3 * d for a, b, c, d in zip(p0, p1, p2, p3))


def draw_arrow(runs, dx, dy):
    """One page's arrow, on its own transparent PNG; returns it with its tip, in dp."""
    scale = PX_PER_DP * SS
    vertical, side = runs.split("-")
    head_room = ARROW_HEAD_DP + ARROW_STROKE_DP
    w = dx + head_room * 2
    h = dy + head_room * 2
    tail = (head_room if side == "right" else w - head_room, head_room if vertical == "down" else h - head_room)
    tip = (w - head_room if side == "right" else head_room, h - head_room if vertical == "down" else head_room)

    # Leaves the page sideways and arrives at the control head-on, wobbling as a hand would.
    p1 = (tail[0] + 0.15 * (tip[0] - tail[0]), tail[1] + 0.65 * (tip[1] - tail[1]))
    p2 = (tip[0] - 0.35 * (tip[0] - tail[0]), tip[1] - 0.25 * (tip[1] - tail[1]))
    points = []
    for i in range(65):
        t = i / 64
        x, y = cubic(tail, p1, p2, tip, t)
        ax, ay = cubic(tail, p1, p2, tip, max(t - 0.01, 0))
        bx, by = cubic(tail, p1, p2, tip, min(t + 0.01, 1))
        length = math.hypot(bx - ax, by - ay) or 1
        wobble = ARROW_WOBBLE_DP * math.sin(math.pi * t) * math.sin(2.4 * math.pi * t + 0.3)
        points.append((x - (by - ay) / length * wobble, y + (bx - ax) / length * wobble))

    image = Image.new("RGBA", (round(w * scale), round(h * scale)), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.line([(x * scale, y * scale) for x, y in points], fill="black", width=round(ARROW_STROKE_DP * scale), joint="curve")
    ang = math.atan2(points[-1][1] - points[-3][1], points[-1][0] - points[-3][0])
    for sign in (1, -1):
        a = ang + math.pi + sign * ARROW_HEAD_ANGLE
        draw.line(
            [(tip[0] * scale, tip[1] * scale), ((tip[0] + ARROW_HEAD_DP * math.cos(a)) * scale, (tip[1] + ARROW_HEAD_DP * math.sin(a)) * scale)],
            fill="black",
            width=round(ARROW_STROKE_DP * scale),
        )
    return image.resize((round(w * PX_PER_DP), round(h * PX_PER_DP)), Image.LANCZOS), {"width": w, "height": h, "tipX": tip[0], "tipY": tip[1]}


def check_glyphs(font):
    """Every character the pages use has to actually draw.

    Virgil has no glyph for some of what a keyboard offers, and a missing one is not an error: it
    has a width and no ink, so the text comes out with a hole in it and the build says nothing.
    U+22EE, the three dots this very tour talks about, is one of them.
    """
    used = {ch for title, intro, rows in PAGES for line in [title, *intro] for ch in line}
    for _, _, rows in PAGES:
        for row in rows:
            used |= {ch for field in row if isinstance(field, str) for ch in field}
    blank = sorted(ch for ch in used if not ch.isspace() and font.getmask(ch).getbbox() is None)
    if blank:
        raise SystemExit("Virgil cannot draw: " + ", ".join(f"U+{ord(ch):04X} {ch!r}" for ch in blank))


def load_icon(name, px):
    """One of the app's own icons, squared to [px] and tinted black on transparent."""
    icon = Image.open(ICONS / f"{name}.png").convert("RGBA").resize((px, px), Image.LANCZOS)
    black = Image.new("RGBA", icon.size, (0, 0, 0, 255))
    black.putalpha(icon.getchannel("A"))
    return black


def draw_page(title, intro, rows):
    """Draws one page; returns the image and its size in dp."""
    scale = PX_PER_DP * SS
    title_font = ImageFont.truetype(str(FONT), TITLE_DP * scale)
    body_font = ImageFont.truetype(str(FONT), BODY_DP * scale)

    menu, panel, dock = isinstance(rows, MENU), isinstance(rows, PANEL), isinstance(rows, DOCK)
    body_dp = (30 * len(rows) + 10) if menu else (34 * len(rows) + 14) if panel else (66 + 27 * len(rows)) if dock else len(rows) * ROW_DP
    height_dp = PAD_DP + TITLE_DP + 10 + len(intro) * LINE_DP + 12 + body_dp + PAD_DP
    image = Image.new("RGBA", (round(PAGE_W_DP * scale), round(height_dp * scale)), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    y = PAD_DP
    draw.text((PAD_DP * scale, y * scale), title, font=title_font, fill="black")
    y += TITLE_DP + 10
    for line in intro:
        draw.text((PAD_DP * scale, y * scale), line, font=body_font, fill="black")
        y += LINE_DP
    y += 6
    if not (menu or panel or dock):
        # A rule only where nothing is drawn below it. Where a control is, the control is the break,
        # and a line above it is a second horizontal competing with the one it already has.
        draw.line(
            [(PAD_DP * scale, y * scale), ((PAGE_W_DP - PAD_DP) * scale, y * scale)],
            fill="black",
            width=round(RULE_DP * scale),
        )
    y += 6

    if menu or panel or dock:
        small = ImageFont.truetype(str(FONT), round(BODY_DP * 0.78) * scale)
        drawer = draw_menu if menu else draw_panel if panel else draw_dock
        drawer(draw, image, rows, PAD_DP, y, PAGE_W_DP - PAD_DP * 2, body_font, small)
        return image.resize((round(PAGE_W_DP * PX_PER_DP), round(height_dp * PX_PER_DP)), Image.LANCZOS), height_dp

    for icon_name, what, does in rows:
        text_x = PAD_DP + ICON_DP + GAP_DP
        if icon_name is not None:
            icon = load_icon(icon_name, round(ICON_DP * scale))
            image.alpha_composite(icon, (round(PAD_DP * scale), round((y + (ROW_DP - ICON_DP) / 2) * scale)))
        else:
            # No button to show, so the words the menu itself uses stand in for one.
            draw.text((PAD_DP * scale, (y + 7) * scale), "•", font=body_font, fill="black")
        draw.text((text_x * scale, (y + 2) * scale), what, font=body_font, fill="black")
        draw.text((text_x * scale, (y + 2 + LINE_DP * 0.82) * scale), does, font=body_font, fill="black")
        y += ROW_DP

    return image.resize((round(PAGE_W_DP * PX_PER_DP), round(height_dp * PX_PER_DP)), Image.LANCZOS), height_dp


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    check_glyphs(ImageFont.truetype(str(FONT), BODY_DP * PX_PER_DP * SS))
    sizes = []
    for index, (title, intro, rows) in enumerate(PAGES):
        image, height_dp = draw_page(title, intro, rows)
        image.save(OUT / f"page-{index + 1}.png")
        arrow, tip = draw_arrow(*ARROWS[index + 1])
        arrow.save(OUT / f"arrow-{index + 1}.png")
        sizes.append({"width": PAGE_W_DP, "height": round(height_dp, 1), "arrow": {k: round(v, 2) for k, v in tip.items()}})
        print(f"page-{index + 1}.png  {title}")
    (OUT / "tour.json").write_text(json.dumps({"pages": sizes}, indent=2) + "\n", encoding="utf-8")
    print(f"{len(PAGES)} pages -> {OUT}")


if __name__ == "__main__":
    main()
