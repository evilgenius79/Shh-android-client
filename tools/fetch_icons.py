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
add arrow_back arrow_upward bolt cable check close code content_copy content_paste
create_new_folder delete description dns download edit error expand_more fingerprint folder
history home info key keyboard keyboard_arrow_down keyboard_arrow_left keyboard_arrow_right
keyboard_arrow_up keyboard_hide link lock more_vert open_in_new palette power_settings_new
refresh save search settings share swap_horiz terminal text_fields tune upload visibility
visibility_off warning drive_file_rename_outline restart_alt select_all travel_explore
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
