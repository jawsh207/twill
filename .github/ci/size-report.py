#!/usr/bin/env python3
"""Experiment only: summarises an APK's size and its dex code by package."""
import collections
import subprocess
import sys
import zipfile

apk, analyzer, out = sys.argv[1], sys.argv[2], sys.argv[3]
lines = []
z = zipfile.ZipFile(apk)
import os
lines.append(f"APK bytes: {os.path.getsize(apk)}")
dex = sum(i.file_size for i in z.infolist() if i.filename.endswith(".dex"))
lines.append(f"dex bytes: {dex}")
for i in z.infolist():
    if i.filename.startswith("lib/") or i.filename == "resources.arsc":
        lines.append(f"{i.filename}: {i.file_size}")
pk = subprocess.run([analyzer, "dex", "packages", "--defined-only", apk],
                    capture_output=True, text=True).stdout
sizes = collections.Counter()
for row in pk.splitlines():
    parts = row.split()
    if len(parts) < 5 or parts[0] != "C":
        continue
    name = parts[-1]
    size = int(parts[4])
    segs = name.split(".")
    for depth in (2, 3, 4):
        sizes[(depth, ".".join(segs[:depth]))] += size
for depth in (2, 3, 4):
    lines.append(f"\n## Top packages, depth {depth} (defined class bytes)")
    top = sorted(((s, n) for (d, n), s in sizes.items() if d == depth), reverse=True)[:40]
    lines += [f"{s:>10} {n}" for s, n in top]
open(out, "w").write("\n".join(lines) + "\n")
print("\n".join(lines[:12]))
