#!/usr/bin/env python3
"""Draws the onboarding hints (src/ui/HelpHints.tsx) the way Excalidraw draws
its own: a caption in Virgil, Excalidraw's hand-drawn font (scripts/fonts, SIL
OFL 1.1), and a hand-drawn arrow curving from it to the control it names. Black
on transparent; the screen tints them gray. One PNG per hint, plus hints.json
with each one's size and its arrow's tip, in dp, so the screen can put the tip
on its control.

Usage: python3 scripts/draw_hints.py            (requires Pillow)
"""
import json
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
FONT = ROOT / "scripts" / "fonts" / "Virgil-Regular.ttf"
OUT = ROOT / "assets" / "hints"

PX_PER_DP = 2  # the PNGs' own resolution; the device shows 1.875 px per dp
SS = 4  # drawn this much larger, then downsampled, for anti-aliasing
FONT_DP = 18
LINE_DP = 23
PAD_DP = 4
GAP_DP = 6  # between the caption and the arrow's tail
STROKE_DP = 1.6
HEAD_DP = 10
HEAD_ANGLE = math.radians(28)
WOBBLE_DP = 0.9

# name: (caption lines, the arrow's way from caption to tip, its reach across, its reach up or down)
HINTS = {
    "header": (["Export, save to note & close"], "up-right", 26, 52),
    "styles": (["Colours & styles"], "up-right", 26, 48),
    "toolbar": (["Pick a tool &", "start drawing!"], "down-right", 30, 52),
    # Reaches further down than the rest: the eraser sits under the action bar,
    # so this caption has to clear the bar and only its arrow crosses it.
    "eraser": (["Tap again to", "clear the canvas"], "down-left", 30, 82),
    "help": (["Show or hide", "these hints"], "down-left", 30, 52),
    # Points at the zoom pill in the bottom left, so its caption sits up and to the
    # right of it and only the arrow reaches down (#12).
    "zoom": (["Zoom, and the", "way back to 100%"], "down-left", 30, 52),
}


def cubic(p0, p1, p2, p3, t):
    u = 1 - t
    return tuple(u**3 * a + 3 * u * u * t * b + 3 * u * t * t * c + t**3 * d for a, b, c, d in zip(p0, p1, p2, p3))


def arrow_points(tail, tip, samples=48):
    """A curve leaving the caption sideways and reaching its tip head-on, wobbling slightly as a hand would."""
    p1 = (tail[0] + 0.75 * (tip[0] - tail[0]), tail[1])
    p2 = (tip[0], tip[1] - 0.45 * (tip[1] - tail[1]))
    points = []
    for i in range(samples + 1):
        t = i / samples
        x, y = cubic(tail, p1, p2, tip, t)
        ax, ay = cubic(tail, p1, p2, tip, max(t - 0.01, 0))
        bx, by = cubic(tail, p1, p2, tip, min(t + 0.01, 1))
        length = math.hypot(bx - ax, by - ay) or 1
        # Across the curve, and nothing at either end, so the tail and tip stay where they're put.
        wobble = WOBBLE_DP * math.sin(math.pi * t) * math.sin(2.6 * math.pi * t + 0.4)
        points.append((x - (by - ay) / length * wobble, y + (bx - ax) / length * wobble))
    return points, p2


def head_points(tip, toward):
    """The two strokes of an open arrowhead, swept back from the tip along the way the curve arrives."""
    dx, dy = tip[0] - toward[0], tip[1] - toward[1]
    length = math.hypot(dx, dy)
    bx, by = -dx / length, -dy / length
    heads = []
    for sign in (1, -1):
        a = sign * HEAD_ANGLE
        heads.append(
            (tip[0] + HEAD_DP * (bx * math.cos(a) - by * math.sin(a)), tip[1] + HEAD_DP * (bx * math.sin(a) + by * math.cos(a)))
        )
    return heads


def stroke(draw, points, scale):
    """A round-capped line through [points], given in dp."""
    scaled = [(x * scale, y * scale) for x, y in points]
    width = round(STROKE_DP * scale)
    draw.line(scaled, fill="black", width=width, joint="curve")
    radius = width / 2
    for x, y in (scaled[0], scaled[-1]):
        draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill="black")


def hint(name, lines, runs, dx, dy):
    """Draws one hint; returns its size and arrow tip in dp."""
    scale = PX_PER_DP * SS
    font = ImageFont.truetype(str(FONT), FONT_DP * scale)
    text_w = max(font.getlength(line) for line in lines) / scale
    text_h = len(lines) * LINE_DP
    head_room = HEAD_DP * math.sin(HEAD_ANGLE) + STROKE_DP
    vertical, side = runs.split("-")
    if side == "right":  # caption on the left, arrow running right
        text_x = PAD_DP
        tail_x = text_x + text_w + GAP_DP
        tip_x = tail_x + dx
        width = tip_x + head_room + PAD_DP
    else:  # caption on the right, arrow running left
        tip_x = PAD_DP + head_room
        tail_x = tip_x + dx
        text_x = tail_x + GAP_DP
        width = text_x + text_w + PAD_DP
    if vertical == "up":  # tip above the caption
        tip_y = PAD_DP + STROKE_DP
        tail_y = tip_y + dy
        text_top = tail_y - text_h / 2
        height = text_top + text_h + PAD_DP
    else:  # tip below the caption
        text_top = PAD_DP
        tail_y = text_top + text_h / 2
        tip_y = tail_y + dy
        height = tip_y + STROKE_DP + PAD_DP

    w_px, h_px = math.ceil(width * PX_PER_DP), math.ceil(height * PX_PER_DP)
    image = Image.new("RGBA", (w_px * SS, h_px * SS), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    for i, line in enumerate(lines):
        draw.text((text_x * scale, (text_top + (i + 0.5) * LINE_DP) * scale), line, font=font, fill="black", anchor="lm")
    points, toward = arrow_points((tail_x, tail_y), (tip_x, tip_y))
    stroke(draw, points, scale)
    for head in head_points((tip_x, tip_y), toward):
        stroke(draw, [(tip_x, tip_y), head], scale)
    image.resize((w_px, h_px), Image.LANCZOS).save(OUT / f"hint-{name}.png")
    return {"width": w_px / PX_PER_DP, "height": h_px / PX_PER_DP, "tipX": round(tip_x, 2), "tipY": round(tip_y, 2)}


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    layout = {name: hint(name, *spec) for name, spec in HINTS.items()}
    (OUT / "hints.json").write_text(json.dumps(layout, indent=2) + "\n")
    print(f"drew {len(layout)} hints into {OUT}")
