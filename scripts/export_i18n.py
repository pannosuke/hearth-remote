#!/usr/bin/env python3
"""Write shared/i18n/en.json and ja.json from the Android string resources.

The JSON files are the catalog the iPhone web remote mirrors. Run this after
editing values/strings.xml or values-ja/strings.xml.
"""

import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    "en": ROOT / "app/src/main/res/values/strings.xml",
    "ja": ROOT / "app/src/main/res/values-ja/strings.xml",
}
OUT = ROOT / "shared/i18n"


def android_unescape(value: str) -> str:
    out = []
    i = 0
    while i < len(value):
        if value[i] == "\\" and i + 1 < len(value):
            nxt = value[i + 1]
            out.append(
                {
                    "'": "'",
                    '"': '"',
                    "n": "\n",
                    "t": "\t",
                    "\\": "\\",
                    "@": "@",
                    "?": "?",
                }.get(nxt, nxt)
            )
            i += 2
        else:
            out.append(value[i])
            i += 1
    return "".join(out)


def read_strings(path: Path) -> dict:
    tree = ET.parse(path)
    strings = {}
    for node in tree.getroot().findall("string"):
        name = node.attrib["name"]
        text = "".join(node.itertext())
        strings[name] = android_unescape(text)
    return strings


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    catalogs = {lang: read_strings(path) for lang, path in SOURCES.items()}
    keys = [set(catalog) for catalog in catalogs.values()]
    if keys[0] != keys[1]:
        missing_ja = sorted(keys[0] - keys[1])
        missing_en = sorted(keys[1] - keys[0])
        print("string keys differ", file=sys.stderr)
        if missing_ja:
            print("missing from ja:", ", ".join(missing_ja), file=sys.stderr)
        if missing_en:
            print("missing from en:", ", ".join(missing_en), file=sys.stderr)
        return 1
    for lang, catalog in catalogs.items():
        target = OUT / f"{lang}.json"
        payload = dict(sorted(catalog.items()))
        target.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"wrote {target.relative_to(ROOT)} ({len(payload)} strings)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
