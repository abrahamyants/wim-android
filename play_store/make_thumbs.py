"""
For every *.png in play_store/screenshots/ that isn't a thumb, ensure a
_thumb_<name>.jpg exists at <=1024px long axis, JPEG quality 80.

Run after `adb exec-out screencap -p > foo.png` so Claude/LLM image reads
stay under the per-image byte/dim limits that cause silent API rejections.
Idempotent: skips thumbs newer than their source.
"""
import os, sys
from PIL import Image

SRC = os.path.join(os.path.dirname(__file__), 'screenshots')
MAX_SIDE = 1024
QUALITY = 80

def main():
    if not os.path.isdir(SRC):
        sys.exit(f'missing {SRC}')
    for f in sorted(os.listdir(SRC)):
        if f.startswith('_thumb_') or not f.lower().endswith('.png'):
            continue
        src = os.path.join(SRC, f)
        dst = os.path.join(SRC, '_thumb_' + os.path.splitext(f)[0] + '.jpg')
        if os.path.exists(dst) and os.path.getmtime(dst) >= os.path.getmtime(src):
            continue
        im = Image.open(src).convert('RGB')
        im.thumbnail((MAX_SIDE, MAX_SIDE), Image.LANCZOS)
        im.save(dst, 'JPEG', quality=QUALITY, optimize=True)
        print(f'{f} -> {os.path.basename(dst)} {im.size[0]}x{im.size[1]} {os.path.getsize(dst)//1024}KB')

if __name__ == '__main__':
    main()
