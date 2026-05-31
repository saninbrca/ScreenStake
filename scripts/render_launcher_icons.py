"""Render legacy launcher .webp fallbacks for the Shield+Checkmark icon.

The adaptive XML icons (mipmap-anydpi) cover all API 26+ devices; these raster
files are only legacy fallbacks. Geometry mirrors drawable/ic_launcher_*.xml in
the 108x108 viewport. Run: python scripts/render_launcher_icons.py
"""
import os
from PIL import Image, ImageDraw

VIEW = 108.0
SS = 8  # supersample factor for antialiasing
GREEN = (0, 200, 83, 255)   # #00C853
WHITE = (255, 255, 255, 255)

RES_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
DENSITIES = {
    "mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192,
}


def cubic(p0, p1, p2, p3, steps=40):
    pts = []
    for i in range(steps + 1):
        t = i / steps
        mt = 1 - t
        x = mt**3*p0[0] + 3*mt*mt*t*p1[0] + 3*mt*t*t*p2[0] + t**3*p3[0]
        y = mt**3*p0[1] + 3*mt*mt*t*p1[1] + 3*mt*t*t*p2[1] + t**3*p3[1]
        pts.append((x, y))
    return pts


def shield_polygon():
    # M54,30 L74,38 L74,58 C74,72 54,82 54,82 C54,82 34,72 34,58 L34,38 Z
    pts = [(54, 30), (74, 38), (74, 58)]
    pts += cubic((74, 58), (74, 72), (54, 82), (54, 82))
    pts += cubic((54, 82), (54, 82), (34, 72), (34, 58))
    pts += [(34, 38)]
    return pts


def draw_check(draw, s):
    # M45,55 L52,62 L65,46, round caps/joins, strokeWidth 5
    pts = [(45, 55), (52, 62), (65, 46)]
    sp = [(x * s, y * s) for x, y in pts]
    w = int(5 * s)
    draw.line(sp, fill=GREEN, width=w, joint="curve")
    r = w / 2
    for x, y in sp:  # round caps
        draw.ellipse([x - r, y - r, x + r, y + r], fill=GREEN)


def render_base():
    big = int(VIEW * SS)
    img = Image.new("RGBA", (big, big), GREEN)
    draw = ImageDraw.Draw(img)
    draw.polygon([(x * SS, y * SS) for x, y in shield_polygon()], fill=WHITE)
    draw_check(draw, SS)
    return img


def circle_mask(size):
    big = size * SS
    m = Image.new("L", (big, big), 0)
    ImageDraw.Draw(m).ellipse([0, 0, big - 1, big - 1], fill=255)
    return m.resize((size, size), Image.LANCZOS)


def main():
    base = render_base()
    for dens, size in DENSITIES.items():
        out_dir = os.path.join(RES_DIR, f"mipmap-{dens}")
        os.makedirs(out_dir, exist_ok=True)
        sq = base.resize((size, size), Image.LANCZOS)
        sq.save(os.path.join(out_dir, "ic_launcher.webp"), "WEBP", lossless=True)
        rnd = sq.copy()
        rnd.putalpha(circle_mask(size))
        rnd.save(os.path.join(out_dir, "ic_launcher_round.webp"), "WEBP", lossless=True)
        print(f"  {dens}: {size}x{size} square + round")
    print("done")


if __name__ == "__main__":
    main()
