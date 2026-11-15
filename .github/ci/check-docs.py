#!/usr/bin/env python3
"""Fails when Twill's own Kotlin code has a class, object, interface or function without a
plain-English description (a /** ... */ comment right above it, after any annotations).

Twill's own code: the twill, nostr and deltachat packages, their screens under ui/, and any
other file whose name starts with Twill. Upstream GrapheneOS code keeps its own style.
See CONTRIBUTING.md.
"""
import glob
import os
import re
import sys

ROOT = "src/com/android/messaging"
OWN_DIRS = ["twill", "nostr", "deltachat", "ui/twill", "ui/nostr", "ui/deltachat"]
DECLARATION = re.compile(
    r"^(?:(?:internal|private|public|protected)\s+)?"
    r"(?:(?:data|enum|sealed|abstract|open|inline|value|annotation|fun)\s+)*"
    r"(?:class|object|interface|fun)\s"
)


def own_files():
    files = set()
    for d in OWN_DIRS:
        files.update(glob.glob(os.path.join(ROOT, d, "**", "*.kt"), recursive=True))
    files.update(glob.glob(os.path.join(ROOT, "**", "Twill*.kt"), recursive=True))
    return sorted(files)


def undocumented(path):
    lines = open(path, encoding="utf-8").read().split("\n")
    for i, line in enumerate(lines):
        # Top-level declarations only: nested members are covered by their class's comment.
        if not DECLARATION.match(line):
            continue
        j = i - 1
        while j >= 0 and lines[j].lstrip().startswith("@"):
            j -= 1  # Annotations sit between the description and what it describes.
        if j < 0 or not lines[j].rstrip().endswith("*/"):
            yield i + 1, line.strip()


def main():
    missing = [
        f"{path}:{number}: {text[:90]}"
        for path in own_files()
        for number, text in undocumented(path)
    ]
    if missing:
        print("These need a short /** ... */ description of what they are and why:")
        print("\n".join(missing))
        print(f"\n{len(missing)} undocumented. See CONTRIBUTING.md.")
        return 1
    print(f"All {len(own_files())} files of Twill's own code are described.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
