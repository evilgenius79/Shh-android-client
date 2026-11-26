#!/usr/bin/env python3
"""Downloads the Material Symbols (Apache-2.0) the app uses as Android vector drawables.

Usage: python3 tools/fetch_icons.py

The icons come from https://github.com/google/material-design-icons. The tint attribute is
removed because Compose tints icons itself and the attribute refers to an AppCompat theme value.
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable")
BASE = "https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android"

ICONS = """
add arrow_back arrow_upward bolt close code content_copy content_paste create_new_folder
delete description dns download drive_file_rename_outline edit expand_more fingerprint
folder history info key keyboard link lock more_vert palette power_settings_new refresh
restart_alt save search settings swap_horiz terminal text_fields upload visibility
visibility_off warning
""".split()


def main():
    os.makedirs(OUT, exist_ok=True)
    failed = []
    for name in ICONS:
        url = f"{BASE}/{name}/materialsymbolsoutlined/{name}_24px.xml"
        data = subprocess.run(["curl", "-sS", "-L", "--fail", "--max-time", "60", url], capture_output=True)
        if data.returncode != 0 or b"<vector" not in data.stdout:
            failed.append(name)
            continue
        xml = data.stdout.decode("utf-8")
        xml = re.sub(r'\s*android:tint="[^"]*"', "", xml)
        with open(os.path.join(OUT, f"ic_{name}.xml"), "w", encoding="utf-8") as f:
            f.write(xml)
    print(f"{len(ICONS) - len(failed)} icons written to {OUT}")
    if failed:
        sys.exit("failed: " + " ".join(failed))


if __name__ == "__main__":
    main()
