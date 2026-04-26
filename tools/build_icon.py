"""WiM launcher icon generator.

Draws the icon procedurally — no source photo. Layered structure (outside in):
  maroon flood (bg) -> thin maroon ring -> thin LIGHT dish ring ->
  wide DARK dish ring -> cast shadow -> raised knob (light gunmetal face
  with bevel) -> Limelight WM cream with drop shadow.

Three greys: knob face is the LIGHTEST gunmetal, inner dish is the DARKEST,
outer dish ring is a thin LIGHTER band catching light just inside the maroon
stroke. Dark band is ~3x the width of the light band.

Run:  python tools/build_icon.py
"""

import math
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter

REPO = Path(__file__).resolve().parents[1]
FONT = REPO / "app/src/main/assets/limelight.ttf"
OUT_RES = REPO / "app/src/main/res"

DENSITIES = {
    "mipmap-mdpi": 108,
    "mipmap-hdpi": 162,
    "mipmap-xhdpi": 216,
    "mipmap-xxhdpi": 324,
    "mipmap-xxxhdpi": 432,
}

MAROON = (181, 80, 80)
MAROON_RING = (155, 45, 45)   # deeper saturated maroon stroke (not pink)
HAIRLINE = (24, 24, 28)       # tiny dark separator between light dish and maroon ring
KNOB_TOP = (91, 93, 101)         # 5% darker
KNOB_BOTTOM = (74, 76, 84)       # 5% darker
DISH_DARK_INNER = (35, 36, 42)   # 10% lighter
DISH_DARK_OUTER = (48, 51, 57)   # 10% lighter
DISH_LIGHT_INNER = (96, 100, 108)
DISH_LIGHT_OUTER = (118, 122, 130)
BEVEL_DARK = (20, 20, 24)
BEVEL_LIGHT = (140, 144, 154)
CREAM = (210, 185, 124)


def build(size: int) -> Image.Image:
    img = Image.new("RGBA", (size, size), (*MAROON, 255))
    draw = ImageDraw.Draw(img)
    cx = cy = size // 2

    bubble_r = int(size * 0.475)
    knob_r = int(bubble_r * 0.48)

    # Layer widths: maroon stroke, then thin light dish, then wide dark dish.
    maroon_w = max(2, int(size * 0.008))   # thinner maroon stroke
    hairline_w = max(1, size // 260)
    dish_total_w = bubble_r - knob_r - maroon_w - hairline_w
    light_w = max(2, dish_total_w // 4)   # 1 unit
    dark_w = dish_total_w - light_w        # 3 units

    ring_outer_r = bubble_r
    ring_inner_r = ring_outer_r - maroon_w
    hairline_outer_r = ring_inner_r
    hairline_inner_r = hairline_outer_r - hairline_w
    light_outer_r = hairline_inner_r
    light_inner_r = light_outer_r - light_w
    dark_outer_r = light_inner_r
    dark_inner_r = knob_r

    # 1) Concentric discs from outside in.
    draw.ellipse((cx - ring_outer_r, cy - ring_outer_r, cx + ring_outer_r, cy + ring_outer_r),
                 fill=(*MAROON_RING, 255))
    draw.ellipse((cx - hairline_outer_r, cy - hairline_outer_r, cx + hairline_outer_r, cy + hairline_outer_r),
                 fill=(*HAIRLINE, 255))
    draw.ellipse((cx - light_outer_r, cy - light_outer_r, cx + light_outer_r, cy + light_outer_r),
                 fill=(*DISH_LIGHT_OUTER, 255))
    draw.ellipse((cx - dark_outer_r, cy - dark_outer_r, cx + dark_outer_r, cy + dark_outer_r),
                 fill=(*DISH_DARK_OUTER, 255))

    # 2) Dish: radial gradient + DIRECTIONAL top sheen (light from upper-left)
    #    so the dark band reads as a recessed metal surface, not a paint fill.
    px = img.load()
    light_dir_x, light_dir_y = -0.6, -0.8  # unit vector pointing toward light
    for y in range(size):
        for x in range(size):
            dx, dy = x - cx, y - cy
            d2 = dx * dx + dy * dy
            if d2 > bubble_r * bubble_r:
                continue
            d = d2 ** 0.5
            # surface normal at radius d on the dish (radial outward)
            if d > 1:
                nx, ny = dx / d, dy / d
            else:
                nx = ny = 0
            # how much this pixel faces the light (1 = facing, -1 = away)
            facing = nx * light_dir_x + ny * light_dir_y

            if dark_inner_r <= d < dark_outer_r:
                t = (d - dark_inner_r) / max(1, (dark_outer_r - dark_inner_r))
                t = max(0.0, min(1.0, t))
                r = int(DISH_DARK_INNER[0] + (DISH_DARK_OUTER[0] - DISH_DARK_INNER[0]) * t)
                g = int(DISH_DARK_INNER[1] + (DISH_DARK_OUTER[1] - DISH_DARK_INNER[1]) * t)
                b = int(DISH_DARK_INNER[2] + (DISH_DARK_OUTER[2] - DISH_DARK_INNER[2]) * t)
                # top sheen: brighten ~+24 on upper-left rim, darken ~-10 on lower-right
                bump = int(facing * 28)
                # extra rim sheen near the outer edge of the dark band (at the seam with light ring)
                if d > dark_outer_r - max(2, size // 90):
                    bump += int(max(0, facing) * 30)
                j = ((x * 13 + y * 29) % 5) - 2
                px[x, y] = (
                    max(0, min(255, r + bump + j)),
                    max(0, min(255, g + bump + j)),
                    max(0, min(255, b + bump + j)),
                    255,
                )
            elif light_inner_r <= d < light_outer_r:
                t = (d - light_inner_r) / max(1, (light_outer_r - light_inner_r))
                t = max(0.0, min(1.0, t))
                r = int(DISH_LIGHT_INNER[0] + (DISH_LIGHT_OUTER[0] - DISH_LIGHT_INNER[0]) * t)
                g = int(DISH_LIGHT_INNER[1] + (DISH_LIGHT_OUTER[1] - DISH_LIGHT_INNER[1]) * t)
                b = int(DISH_LIGHT_INNER[2] + (DISH_LIGHT_OUTER[2] - DISH_LIGHT_INNER[2]) * t)
                # the light ring catches strong sheen on the upper-left, drops to dark on the lower-right
                bump = int(facing * 50)
                j = ((x * 17 + y * 11) % 5) - 2
                px[x, y] = (
                    max(0, min(255, r + bump + j)),
                    max(0, min(255, g + bump + j)),
                    max(0, min(255, b + bump + j)),
                    255,
                )

    # 3) Cast shadow under the knob to sell the ~1in raise.
    shadow_layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow_layer)
    s_off_x = max(1, int(size * 0.012))
    s_off_y = max(2, int(size * 0.030))
    s_grow = max(2, int(size * 0.022))
    sd.ellipse(
        (
            cx - knob_r - s_grow + s_off_x,
            cy - knob_r - s_grow + s_off_y,
            cx + knob_r + s_grow + s_off_x,
            cy + knob_r + s_grow + s_off_y,
        ),
        fill=(0, 0, 0, 175),
    )
    shadow_layer = shadow_layer.filter(ImageFilter.GaussianBlur(radius=max(2.5, size * 0.030)))
    img = Image.alpha_composite(img, shadow_layer)
    px = img.load()

    # 4) Knob face: ASYMMETRIC bevel (chamfer lit on upper-left, shadowed on
    #    lower-right) + offset radial sheen on the face for a brushed-metal pop.
    bevel_outer = knob_r
    bevel_mid = knob_r - max(3, size // 130)
    bevel_inner = knob_r - max(5, size // 80)
    sheen_cx = cx - knob_r * 0.35
    sheen_cy = cy - knob_r * 0.35
    sheen_r = knob_r * 1.15
    for y in range(cy - knob_r - 2, cy + knob_r + 2):
        for x in range(cx - knob_r - 2, cx + knob_r + 2):
            dx, dy = x - cx, y - cy
            d2 = dx * dx + dy * dy
            if d2 > bevel_outer * bevel_outer:
                continue
            d = d2 ** 0.5
            if d > 0.5:
                nx, ny = dx / d, dy / d
            else:
                nx = ny = 0
            facing = nx * light_dir_x + ny * light_dir_y  # 1 = upper-left, -1 = lower-right
            if d > bevel_mid:
                # outer chamfer: dark by default; brighten on upper-left, deepen on lower-right
                base = list(BEVEL_DARK)
                bump = int(facing * 70)
                px[x, y] = (
                    max(0, min(255, base[0] + bump)),
                    max(0, min(255, base[1] + bump)),
                    max(0, min(255, base[2] + bump)),
                    255,
                )
            elif d > bevel_inner:
                # thin highlight ring: very bright upper-left, very dark lower-right
                base = list(BEVEL_LIGHT)
                bump = int(facing * 60) - 15  # slight bias toward darker so it's a credible chamfer
                px[x, y] = (
                    max(0, min(255, base[0] + bump)),
                    max(0, min(255, base[1] + bump)),
                    max(0, min(255, base[2] + bump)),
                    255,
                )
            else:
                # Flat-ish face base, then offset radial sheen on the face.
                t = (y - (cy - bevel_inner)) / (2 * bevel_inner)
                t = max(0.0, min(1.0, t))
                r = int(KNOB_TOP[0] + (KNOB_BOTTOM[0] - KNOB_TOP[0]) * t)
                g = int(KNOB_TOP[1] + (KNOB_BOTTOM[1] - KNOB_TOP[1]) * t)
                b = int(KNOB_TOP[2] + (KNOB_BOTTOM[2] - KNOB_TOP[2]) * t)
                # offset radial sheen — falls off from upper-left
                sdx = x - sheen_cx
                sdy = y - sheen_cy
                sd = (sdx * sdx + sdy * sdy) ** 0.5
                sheen_t = max(0.0, min(1.0, sd / sheen_r))
                # smooth falloff: bright at center of sheen origin, fading to slight darkening on far side
                sheen_bump = int((1.0 - sheen_t) ** 1.6 * 32) - int(sheen_t * 12)
                # subtle ring darkening at the very edge for ambient occlusion
                edge_t = max(0.0, (d - bevel_inner * 0.85) / max(1.0, bevel_inner * 0.15))
                edge_bump = int(edge_t * -10)
                bump = sheen_bump + edge_bump
                j = ((x * 31 + y * 17) % 7) - 3
                px[x, y] = (
                    max(0, min(255, r + bump + j)),
                    max(0, min(255, g + bump + j)),
                    max(0, min(255, b + bump + j)),
                    255,
                )

    # 5) Limelight WM with drop shadow on the knob face.
    target_w = int(knob_r * 1.55)
    fnt_size = 10
    fnt = ImageFont.truetype(str(FONT), fnt_size)
    while True:
        bb = fnt.getbbox("WM")
        if (bb[2] - bb[0]) >= target_w or fnt_size > size:
            break
        fnt_size += 2
        fnt = ImageFont.truetype(str(FONT), fnt_size)

    bb = fnt.getbbox("WM")
    tw, th = bb[2] - bb[0], bb[3] - bb[1]
    tx = cx - tw // 2 - bb[0]
    ty = cy - th // 2 - bb[1]

    text_shadow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ts = ImageDraw.Draw(text_shadow)
    ts.text((tx + max(1, size // 400), ty + max(2, size // 200)), "WM", font=fnt, fill=(0, 0, 0, 210))
    text_shadow = text_shadow.filter(ImageFilter.GaussianBlur(radius=max(1.2, size * 0.006)))
    img = Image.alpha_composite(img, text_shadow)

    d = ImageDraw.Draw(img)
    d.text((tx, ty), "WM", font=fnt, fill=(*MAROON, 255))

    return img


def main():
    for density, sz in DENSITIES.items():
        out = OUT_RES / density / "ic_launcher_foreground.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        img = build(sz)
        img.save(out)
        print(f"  wrote {out.relative_to(REPO)}  ({sz}x{sz})")
    preview = REPO / "tools/icon_preview.png"
    build(432).save(preview)
    print(f"  preview {preview.relative_to(REPO)}")


if __name__ == "__main__":
    main()
