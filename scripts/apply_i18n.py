"""
Replace inline android:text="..." / android:hint="..." in layout XMLs with
@string/key references, using values/strings.xml as the lookup table.

Run from repo root:
    python scripts/apply_i18n.py

Idempotent: skips strings that look like @string/, @+id/, single chars, or
purely numeric/punctuation values.
"""
import re
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
RES = REPO / "app" / "src" / "main" / "res"
STRINGS_XML = RES / "values" / "strings.xml"
LAYOUTS = [
    RES / "layout" / "activity_main.xml",
    RES / "layout" / "activity_bouncer.xml",
    RES / "layout" / "profile_overlay.xml",
    RES / "layout" / "bubble_menu.xml",
]

# Skip these — symbols, numbers, single chars, already-referenced.
SKIP_VALUES = {"", "›", "✕", "✓", "▶", "▸", "▼", "↓", "→", "+", "-", "—", "·",
               "!", "?", "@", "#", "%", "&", "*"}


def load_string_map():
    tree = ET.parse(STRINGS_XML)
    root = tree.getroot()
    by_value = {}
    for s in root.findall("string"):
        key = s.attrib.get("name")
        # ET parses entity refs (&amp; → &, etc.). Normalize back for matching.
        val = s.text or ""
        # Reverse the entity decoding so we can match the raw layout text.
        raw = val.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        # Layouts use different escape style for ' than strings.xml — strings.xml
        # uses \' but layouts use straight '. Normalize.
        layout_form = raw.replace("\\'", "'").replace("\\n", "&#10;")
        by_value.setdefault(layout_form, key)
        # Also try the un-escaped form (for direct ' in layout source)
        by_value.setdefault(raw, key)
    return by_value


def should_skip(value):
    if not value or value in SKIP_VALUES:
        return True
    if value.startswith("@"):
        return True
    if re.fullmatch(r"[\d\s\.\,\-:]+\s*(ms|%)?", value):
        return True
    if len(value) == 1:
        return True
    return False


PATTERNS = [
    re.compile(r'(android:text)="([^"@][^"]*)"'),
    re.compile(r'(android:hint)="([^"@][^"]*)"'),
    re.compile(r'(android:contentDescription)="([^"@][^"]*)"'),
]


def process_layout(path, by_value):
    text = path.read_text(encoding="utf-8")
    original = text
    replaced = 0
    skipped_unmapped = []

    def replacer(match):
        nonlocal replaced
        attr, value = match.group(1), match.group(2)
        if should_skip(value):
            return match.group(0)
        key = by_value.get(value)
        if not key:
            skipped_unmapped.append(value)
            return match.group(0)
        replaced += 1
        return f'{attr}="@string/{key}"'

    for pat in PATTERNS:
        text = pat.sub(replacer, text)

    if text != original:
        path.write_text(text, encoding="utf-8")
    return replaced, skipped_unmapped


def main():
    by_value = load_string_map()
    print(f"Loaded {len(by_value)} value-to-key mappings from strings.xml\n")

    for layout in LAYOUTS:
        if not layout.exists():
            print(f"SKIP (missing): {layout.name}")
            continue
        replaced, unmapped = process_layout(layout, by_value)
        print(f"{layout.name}: {replaced} replaced")
        if unmapped:
            uniq = sorted(set(unmapped))
            print(f"  {len(uniq)} unique unmapped values:")
            for v in uniq[:30]:
                print(f"    - {v!r}")
            if len(uniq) > 30:
                print(f"    ... and {len(uniq) - 30} more")
        print()


if __name__ == "__main__":
    main()
