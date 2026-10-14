"""Sums an unstripped libdeltachat.so's symbol sizes per Rust crate (or C library).

Usage: core-size-breakdown.py <llvm-nm> <library.so>
Prints "name<TAB>bytes" lines, largest first, then family totals.
"""

import collections
import re
import subprocess
import sys

STD = {"core", "alloc", "std", "compiler_builtins", "std_detect", "panic_abort"}
PATH_START = re.compile(r"(?:^|[<\s(&,\[*;])([a-z_][a-z0-9_]*)::")
OPENSSL = re.compile(
    r"^(EVP|OPENSSL|ossl|SSL|BN|X509|ASN1|EC|ECDSA|ECDH|RSA|CRYPTO|ERR|PEM|BIO|OBJ|DH|DSA|"
    r"CMS|PKCS|OSSL|HMAC|SHA|MD5|AES|CT|OCSP|TS|UI|RAND|ENGINE|CONF|DES|i2d|d2i|"
    r"x509|ec_|bn_|rsa_|evp_|tls|ssl|dtls|aes_|sha|md5|engine|pkey|ossl_)",
)

FAMILIES = {
    "iroh (P2P: realtime webxdc, backup transfer)": re.compile(
        r"^(iroh.*|quinn.*|netwatch|portmapper|hickory.*|n0_.*|igd_next|netdev|"
        r"swarm_discovery|surge_ping|pkarr|simple_dns|mainline|z32|"
        r"netlink.*|rtnetlink|wmi|bao_tree|iroh_blobs|derive_more|"
        r"tokio_rustls_acme|acto|self_cell|backon|reloadable_state)$"
    ),
    "shadowsocks (proxy support)": re.compile(r"^(shadowsocks.*)$"),
    "image decoding": re.compile(
        r"^(image|image_webp|png|gif|jpeg_decoder|zune_.*|fdeflate|weezl|bmp|"
        r"colorutils_rs|kamadak_exif|exif)$"
    ),
    "openssl (C)": re.compile(r"^openssl \(C\)$"),
    "sqlcipher (C)": re.compile(r"^sqlcipher \(C\)$"),
    "OpenPGP": re.compile(
        r"^(pgp|rsa|ml_kem|ml_dsa|slh_dsa|x448|ed448.*|p256|p384|p521|k256|"
        r"elliptic_curve|ecdsa|primeorder|crypto_bigint|num_bigint.*|cast5|twofish|"
        r"camellia|idea|blowfish|des|ocb3|eax|cfb_mode|sha3|keccak|ripemd|md5)$"
    ),
    "deltachat core": re.compile(r"^(deltachat|deltachat_contact_tools|format_flowed|ratelimit)$"),
    "deltachat-jsonrpc": re.compile(r"^(deltachat_jsonrpc|yerpc)$"),
    "deltachat-ffi C API (unused by Twill)": re.compile(r"^(deltachat_ffi|dc_ C API)$"),
}


def owner(name: str) -> str:
    if "::" not in name:
        if name.startswith("dc_"):
            return "dc_ C API"
        if name.startswith(("sqlite3", "sqlcipher")):
            return "sqlcipher (C)"
        if OPENSSL.match(name):
            return "openssl (C)"
        return "other C / unattributed"
    crates = PATH_START.findall(name)
    if not crates:
        return "other"
    for crate in crates:
        if crate not in STD:
            return crate
    return "std"


def main() -> None:
    nm, library = sys.argv[1], sys.argv[2]
    listing = subprocess.run(
        [nm, "-S", "--size-sort", "-C", "--defined-only", library],
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    totals: collections.Counter[str] = collections.Counter()
    for line in listing.splitlines():
        parts = line.split(" ", 3)
        if len(parts) < 4:
            continue
        totals[owner(parts[3])] += int(parts[1], 16)

    for name, size in totals.most_common(45):
        print(f"{name}\t{size}")
    print("--- families ---")
    for family, pattern in FAMILIES.items():
        print(f"{family}\t{sum(s for n, s in totals.items() if pattern.match(n))}")
    print(f"all symbols\t{sum(totals.values())}")


main()
