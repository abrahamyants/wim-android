"""Generate the 1024 x 500 Play Store feature graphic for WiM.

Brand-locked: every hex below is from brand.local.md canonical dark-mode tokens.
Approved set used:
    bg #1A1A1E, surface #222226, sunken #18181C,
    cream #C8B48A, copper #C49A6C, maroon #D06060, orange #EE5A24,
    text #D4D0C8, text_bright #F0ECE4.

Output: play_store/feature_graphic_1024x500.png
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import math

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "play_store" / "feature_graphic_1024x500.png"
FONT_LIMELIGHT = ROOT / "app" / "src" / "main" / "assets" / "limelight.ttf"

W, H = 1024, 500

# Canonical dark-mode palette (brand.local.md)
BG = (26, 26, 30)              # #1A1A1E
SURFACE = (34, 34, 38)         # #222226
SUNKEN = (24, 24, 28)          # #18181C
CREAM = (200, 180, 138)        # #C8B48A
COPPER = (196, 154, 108)       # #C49A6C
MAROON = (208, 96, 96)         # #D06060
ORANGE = (238, 90, 36)         # #EE5A24
TEXT = (212, 208, 200)         # #D4D0C8
TEXT_BRIGHT = (240, 236, 228)  # #F0ECE4

img = Image.new("RGB", (W, H), BG)
draw = ImageDraw.Draw(img, "RGBA")

# Vertical fade: BG at top, drift toward SUNKEN at the bottom
for y in range(H):
    t = y / H
    r = int(BG[0] * (1 - 0.4 * t) + SUNKEN[0] * 0.4 * t)
    g = int(BG[1] * (1 - 0.4 * t) + SUNKEN[1] * 0.4 * t)
    b = int(BG[2] * (1 - 0.4 * t) + SUNKEN[2] * 0.4 * t)
    draw.line([(0, y), (W, y)], fill=(r, g, b))

# Subtle hex grid (uses surface alpha as overlay)
hex_radius = 24
hex_h_step = hex_radius * math.sqrt(3)
hex_line_color = (SURFACE[0], SURFACE[1], SURFACE[2], 110)
for row in range(-1, int(H / hex_h_step) + 2):
    for col in range(-1, int(W / (1.5 * hex_radius)) + 2):
        cx = col * 1.5 * hex_radius
        cy = row * hex_h_step + (hex_h_step / 2 if col % 2 else 0)
        pts = [
            (cx + hex_radius * math.cos(math.pi / 3 * i),
             cy + hex_radius * math.sin(math.pi / 3 * i))
            for i in range(6)
        ]
        draw.polygon(pts, outline=hex_line_color)

# Right-side bubble badge — stylized, brand-canonical only.
# Disk: cream fill with maroon stroke. Single WM monogram in maroon.
bubble_cx, bubble_cy = 818, 250
disk_r = 138
stroke_w = 6

# Drop shadow first
shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
sdraw = ImageDraw.Draw(shadow)
sdraw.ellipse(
    [bubble_cx - disk_r - 4, bubble_cy - disk_r + 16,
     bubble_cx + disk_r + 4, bubble_cy + disk_r + 22],
    fill=(0, 0, 0, 170)
)
shadow = shadow.filter(ImageFilter.GaussianBlur(radius=20))
img.paste(shadow, (0, 0), shadow)
draw = ImageDraw.Draw(img, "RGBA")

# Disk fill — cream
draw.ellipse(
    [bubble_cx - disk_r, bubble_cy - disk_r,
     bubble_cx + disk_r, bubble_cy + disk_r],
    fill=CREAM
)

# Disk maroon stroke
draw.ellipse(
    [bubble_cx - disk_r, bubble_cy - disk_r,
     bubble_cx + disk_r, bubble_cy + disk_r],
    outline=MAROON, width=stroke_w
)

# Inner copper ring — gives the disk depth without a new hex
inner_r = disk_r - 28
draw.ellipse(
    [bubble_cx - inner_r, bubble_cy - inner_r,
     bubble_cx + inner_r, bubble_cy + inner_r],
    outline=COPPER, width=2
)

# WM letters on the disk — Limelight, maroon
try:
    wm_font = ImageFont.truetype(str(FONT_LIMELIGHT), 110)
except Exception:
    wm_font = ImageFont.load_default()
wm_text = "WM"
bbox = draw.textbbox((0, 0), wm_text, font=wm_font)
tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
draw.text(
    (bubble_cx - tw / 2 - bbox[0], bubble_cy - th / 2 - bbox[1] - 6),
    wm_text, font=wm_font, fill=MAROON
)

# Orange tick at 12 o'clock — recalls the real bubble's tick marker
tick_w = 10
tick_h = 18
tick_top = bubble_cy - disk_r + 14
draw.rounded_rectangle(
    [bubble_cx - tick_w / 2, tick_top,
     bubble_cx + tick_w / 2, tick_top + tick_h],
    radius=4, fill=ORANGE
)

# Left side — wordmark + tagline
try:
    title_font = ImageFont.truetype(str(FONT_LIMELIGHT), 168)
except Exception:
    title_font = ImageFont.load_default()
try:
    sub_font = ImageFont.truetype(str(FONT_LIMELIGHT), 36)
except Exception:
    sub_font = ImageFont.load_default()
try:
    body_font = ImageFont.truetype("arial.ttf", 28)
except Exception:
    body_font = ImageFont.load_default()
try:
    small_font = ImageFont.truetype("arial.ttf", 20)
except Exception:
    small_font = ImageFont.load_default()

# "WiM"
draw.text((58, 78), "WiM", font=title_font, fill=CREAM)

# "What I Meant"
draw.text((68, 256), "What I Meant", font=sub_font, fill=COPPER)

# Tagline
draw.text((68, 326), "Voice typing built for", font=body_font, fill=TEXT_BRIGHT)
draw.text((68, 362), "speech disfluencies.", font=body_font, fill=TEXT_BRIGHT)

# Footer line
draw.text(
    (68, 432),
    "Offline-first  -  Bubble across every app  -  Local by default",
    font=small_font, fill=COPPER
)

OUT.parent.mkdir(parents=True, exist_ok=True)
img.save(OUT, "PNG", optimize=True)
print(f"WROTE {OUT} {OUT.stat().st_size} bytes")
