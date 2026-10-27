#!/usr/bin/env python3
"""Checks that every Java method the Delta Chat library implements natively still exists,
with its original class and method name, in an R8-optimized APK.

  check-jni.py <apk> <apkanalyzer>
"""
import subprocess
import sys
import zipfile

apk, analyzer = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk) as z:
    libs = [n for n in z.namelist() if n.endswith("libtwill_deltachat.so")]
    if not libs:
        print("No Delta Chat library in this APK; nothing to check.")
        sys.exit(0)
    data = z.read(libs[0])
open("/tmp/twill_deltachat.so", "wb").write(data)
symbols = subprocess.run(["readelf", "-Ws", "--dyn-syms", "/tmp/twill_deltachat.so"],
                         capture_output=True, text=True).stdout
exports = sorted({w for line in symbols.splitlines() for w in line.split() if w.startswith("Java_")})
dex = subprocess.run([analyzer, "dex", "packages", "--defined-only", apk],
                     capture_output=True, text=True).stdout
methods = set()
for line in dex.splitlines():
    parts = line.split(None, 5)
    if len(parts) == 6 and parts[0] == "M":
        cls, _, sig = parts[5].partition(" ")
        name = sig.split("(")[0].split()[-1]
        methods.add((cls, name))


def decode(symbol):
    body = symbol[len("Java_"):]
    out, i = [], 0
    while i < len(body):
        if body[i] == "_" and i + 1 < len(body) and body[i + 1] == "1":
            out.append("_")
            i += 2
        elif body[i] == "_":
            out.append(".")
            i += 1
        else:
            out.append(body[i])
            i += 1
    full = "".join(out)
    cls, _, method = full.rpartition(".")
    return cls, method


missing = [s for s in exports if decode(s) not in methods]
print(f"{len(exports)} native methods, {len(missing)} missing")
for s in missing:
    print("::error::Native method missing after R8:", decode(s))
sys.exit(1 if missing or not exports else 0)
