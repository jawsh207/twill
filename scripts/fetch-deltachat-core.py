#!/usr/bin/env python3
"""Downloads the Delta Chat core (chatmail core) JSON-RPC server for Android into
deltachat/jniLibs/<abi>/libdeltachat_rpc_server.so, where Gradle packages it.

Twill runs it as a child process and talks JSON-RPC over stdin/stdout. Android only lets
apps execute files from their native library directory, which is why it is packaged as a
"library". The binary comes from the official PyPI wheel; its SHA-256 is pinned below, so
changing the version means updating the hash too (copy it from the PyPI release page).

Usage: scripts/fetch-deltachat-core.py   (no-op when the right file is already there)
"""
import hashlib
import io
import json
import os
import sys
import urllib.request
import zipfile

VERSION = "2.63.0"
WHEELS = {
    # ABI -> (wheel filename, sha256 of the wheel)
    "arm64-v8a": (
        f"deltachat_rpc_server-{VERSION}-py3-none-android_21_arm64_v8a.whl",
        "507419e84288a64e37798e46f7fa181b40fcb54daff6f7845a914a6a5ff41722",
    ),
}
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "deltachat", "jniLibs")
LIB_NAME = "libdeltachat_rpc_server.so"


def wheel_url(filename: str) -> str:
    with urllib.request.urlopen(f"https://pypi.org/pypi/deltachat-rpc-server/{VERSION}/json") as r:
        release = json.load(r)
    for entry in release["urls"]:
        if entry["filename"] == filename:
            return entry["url"]
    sys.exit(f"{filename} is not on PyPI")


def main() -> None:
    for abi, (filename, sha256) in WHEELS.items():
        target = os.path.join(OUT_DIR, abi, LIB_NAME)
        # Outside jniLibs, so it isn't packaged into the APK.
        stamp = os.path.join(ROOT, "deltachat", f"core-{abi}.sha256")
        if os.path.exists(target) and os.path.exists(stamp) and open(stamp).read().strip() == sha256:
            print(f"{abi}: Delta Chat core {VERSION} already present")
            continue
        url = wheel_url(filename)
        print(f"{abi}: downloading {url}")
        with urllib.request.urlopen(url) as r:
            data = r.read()
        actual = hashlib.sha256(data).hexdigest()
        if actual != sha256:
            sys.exit(f"{filename}: SHA-256 mismatch, expected {sha256}, got {actual}")
        with zipfile.ZipFile(io.BytesIO(data)) as wheel:
            members = [n for n in wheel.namelist() if n.endswith("/deltachat-rpc-server")]
            if len(members) != 1:
                sys.exit(f"{filename}: expected one deltachat-rpc-server binary, found {members}")
            binary = wheel.read(members[0])
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "wb") as f:
            f.write(binary)
        os.chmod(target, 0o755)
        with open(stamp, "w") as f:
            f.write(sha256)
        print(f"{abi}: wrote {target} ({len(binary)} bytes)")


if __name__ == "__main__":
    main()
