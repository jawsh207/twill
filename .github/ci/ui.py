#!/usr/bin/env python3
"""Small adb helpers for the screenshot workflow.

  ui.py tap <text>           tap the first view whose text or content-desc equals <text>
  ui.py tapcontains <text>   ... contains <text>
  ui.py palette <style>      set the system colour style (MONOCHROMATIC or TONAL_SPOT)
  ui.py report <dir>         sample colours of every PNG in <dir> into <dir>/report.md
"""

import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*args, text=True):
    return subprocess.run(["adb", *args], capture_output=True, text=text, check=False).stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    start = xml.find("<?xml")
    if start < 0:
        return []
    try:
        root = ET.fromstring(xml[start:])
    except ET.ParseError:
        return []
    return list(root.iter("node"))


def tap(label, contains=False):
    for _ in range(6):
        for node in nodes():
            for attr in ("text", "content-desc"):
                value = node.get(attr) or ""
                if (contains and label in value) or (not contains and value == label):
                    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
                    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                    print(f"tapped {label!r}")
                    return True
        time.sleep(2)
    print(f"NOT FOUND: {label!r}")
    return False


def palette(style):
    # Same setting the Wallpaper & style picker writes: "Other colors" with the monochrome
    # style gives neutral greys; TONAL_SPOT from the wallpaper is the default.
    if style == "MONOCHROMATIC":
        value = {
            "android.theme.customization.theme_style": "MONOCHROMATIC",
            "android.theme.customization.color_source": "preset",
            "android.theme.customization.system_palette": "808080",
            "android.theme.customization.accent_color": "808080",
        }
    else:
        value = {
            "android.theme.customization.theme_style": "TONAL_SPOT",
            "android.theme.customization.color_source": "home_wallpaper",
        }
    value["_applied_timestamp"] = int(time.time() * 1000)
    # One quoted argument, or the device shell splits the JSON apart.
    payload = json.dumps(value, separators=(",", ":"))
    adb("shell", f"settings put secure theme_customization_overlay_packages '{payload}'")
    print(adb("shell", "settings get secure theme_customization_overlay_packages").strip())


def report(directory):
    from PIL import Image

    lines = [
        "| Screenshot | Page (left margin) | Most common colours |",
        "|---|---|---|",
    ]
    for name in sorted(os.listdir(directory)):
        if not name.endswith(".png"):
            continue
        image = Image.open(os.path.join(directory, name)).convert("RGB")
        width, height = image.size
        page = image.getpixel((6, height // 2))
        body = image.crop((0, int(height * 0.06), width, int(height * 0.93)))
        colours = body.getcolors(maxcolors=width * height) or []
        colours.sort(reverse=True)
        total = sum(count for count, _ in colours) or 1
        top = ", ".join(
            "#%02X%02X%02X %d%%" % (*rgb, round(100 * count / total))
            for count, rgb in colours[:4]
        )
        lines.append("| %s | #%02X%02X%02X | %s |" % (name, *page, top))
    with open(os.path.join(directory, "report.md"), "w") as out:
        out.write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    command, argument = sys.argv[1], sys.argv[2]
    if command == "tap":
        sys.exit(0 if tap(argument) else 1)
    elif command == "tapcontains":
        sys.exit(0 if tap(argument, contains=True) else 1)
    elif command == "palette":
        palette(argument)
    elif command == "report":
        report(argument)
