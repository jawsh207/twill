#!/usr/bin/env python3
"""Nostr helpers for the emulator test, so it can receive a real NIP-17 DM.

  nostr_dm.py account <keyfile>       make the emulator account's key; prints its nsec
  nostr_dm.py send <keyfile> <text>   send <text> to that account from a test sender
                                      (named "Robin Test"), gift-wrapped (NIP-17/44/59)

Needs `pip install cryptography websockets`. Keys are throwaway, made fresh for each run.
"""

import asyncio
import base64
import hashlib
import hmac
import json
import os
import secrets
import sys
import time

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms

P = 2**256 - 2**32 - 977
N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
G = (0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,
     0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8)
RELAYS = ["wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net"]
SENDER_NAME = "Robin Test"


def add(a, b):
    if a is None:
        return b
    if b is None:
        return a
    if a[0] == b[0] and (a[1] + b[1]) % P == 0:
        return None
    if a == b:
        m = 3 * a[0] * a[0] * pow(2 * a[1], P - 2, P)
    else:
        m = (b[1] - a[1]) * pow(b[0] - a[0], P - 2, P)
    x = (m * m - a[0] - b[0]) % P
    return x, (m * (a[0] - x) - a[1]) % P


def mul(k, point):
    result = None
    while k:
        if k & 1:
            result = add(result, point)
        point = add(point, point)
        k >>= 1
    return result


def lift_x(x):
    y = pow((pow(x, 3, P) + 7) % P, (P + 1) // 4, P)
    return x, y if y % 2 == 0 else P - y


def xonly(priv):
    return mul(priv, G)[0].to_bytes(32, "big")


def tagged(tag, data):
    t = hashlib.sha256(tag.encode()).digest()
    return hashlib.sha256(t + t + data).digest()


def schnorr_sign(priv, msg):
    point = mul(priv, G)
    d = priv if point[1] % 2 == 0 else N - priv
    t = (d ^ int.from_bytes(tagged("BIP0340/aux", os.urandom(32)), "big")).to_bytes(32, "big")
    px = point[0].to_bytes(32, "big")
    k0 = int.from_bytes(tagged("BIP0340/nonce", t + px + msg), "big") % N
    r = mul(k0, G)
    k = k0 if r[1] % 2 == 0 else N - k0
    rx = r[0].to_bytes(32, "big")
    e = int.from_bytes(tagged("BIP0340/challenge", rx + px + msg), "big") % N
    return (rx + ((k + e * d) % N).to_bytes(32, "big")).hex()


# ---- NIP-44 v2 ----

def hkdf_expand(prk, info, length):
    out, block, i = b"", b"", 1
    while len(out) < length:
        block = hmac.new(prk, block + info + bytes([i]), hashlib.sha256).digest()
        out += block
        i += 1
    return out[:length]


def conversation_key(priv, pub_hex):
    shared = mul(priv, lift_x(int(pub_hex, 16)))[0].to_bytes(32, "big")
    return hmac.new(b"nip44-v2", shared, hashlib.sha256).digest()


def padded_len(n):
    if n <= 32:
        return 32
    next_power = 1 << (n - 1).bit_length()
    chunk = 32 if next_power <= 256 else next_power // 8
    return chunk * ((n - 1) // chunk + 1)


def nip44_encrypt(text, priv, pub_hex):
    key = conversation_key(priv, pub_hex)
    nonce = os.urandom(32)
    keys = hkdf_expand(key, nonce, 76)
    chacha_key, chacha_nonce, hmac_key = keys[:32], keys[32:44], keys[44:]
    raw = text.encode()
    padded = len(raw).to_bytes(2, "big") + raw + b"\0" * (padded_len(len(raw)) - len(raw))
    cipher = Cipher(algorithms.ChaCha20(chacha_key, b"\0\0\0\0" + chacha_nonce), mode=None)
    ciphertext = cipher.encryptor().update(padded)
    mac = hmac.new(hmac_key, nonce + ciphertext, hashlib.sha256).digest()
    return base64.b64encode(b"\x02" + nonce + ciphertext + mac).decode()


# ---- Events ----

def event_id(ev):
    data = [0, ev["pubkey"], ev["created_at"], ev["kind"], ev["tags"], ev["content"]]
    serialized = json.dumps(data, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(serialized.encode()).hexdigest()


def signed(priv, kind, content, tags, created_at=None):
    ev = {
        "pubkey": xonly(priv).hex(),
        "created_at": created_at or int(time.time()),
        "kind": kind,
        "tags": tags,
        "content": content,
    }
    ev["id"] = event_id(ev)
    ev["sig"] = schnorr_sign(priv, bytes.fromhex(ev["id"]))
    return ev


def gift_wrap(sender, recipient_hex, text):
    rumor = {
        "pubkey": xonly(sender).hex(),
        "created_at": int(time.time()),
        "kind": 14,
        "tags": [["p", recipient_hex]],
        "content": text,
    }
    rumor["id"] = event_id(rumor)
    past = lambda: int(time.time()) - secrets.randbelow(2 * 24 * 3600)  # noqa: E731
    seal = signed(sender, 13, nip44_encrypt(json.dumps(rumor), sender, recipient_hex), [],
                  past())
    wrapper = secrets.randbelow(N - 1) + 1
    return signed(wrapper, 1059, nip44_encrypt(json.dumps(seal), wrapper, recipient_hex),
                  [["p", recipient_hex]], past())


async def publish(events):
    import websockets

    async def one(url):
        try:
            async with websockets.connect(url, open_timeout=10) as ws:
                for ev in events:
                    await ws.send(json.dumps(["EVENT", ev]))
                    reply = await asyncio.wait_for(ws.recv(), 10)
                    print(url, reply[:120])
        except Exception as exc:  # noqa: BLE001
            print(url, "failed:", exc)

    await asyncio.gather(*(one(url) for url in RELAYS))


# ---- bech32 (nsec) ----

CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"


def bech32_words(hrp, out):
    expanded = [ord(c) >> 5 for c in hrp] + [0] + [ord(c) & 31 for c in hrp]
    mod = polymod(expanded + out + [0] * 6) ^ 1
    checksum = [(mod >> 5 * (5 - i)) & 31 for i in range(6)]
    return hrp + "1" + "".join(CHARSET[d] for d in out + checksum)


def polymod(values):
    gen = [0x3B6A57B2, 0x26508E6D, 0x1EA119FA, 0x3D4233DD, 0x2A1462B3]
    chk = 1
    for v in values:
        top = chk >> 25
        chk = (chk & 0x1FFFFFF) << 5 ^ v
        for i in range(5):
            chk ^= gen[i] if (top >> i) & 1 else 0
    return chk


def bech32(hrp, data):
    def polymod(values):
        gen = [0x3B6A57B2, 0x26508E6D, 0x1EA119FA, 0x3D4233DD, 0x2A1462B3]
        chk = 1
        for v in values:
            top = chk >> 25
            chk = (chk & 0x1FFFFFF) << 5 ^ v
            for i in range(5):
                chk ^= gen[i] if (top >> i) & 1 else 0
        return chk

    bits, acc, out = 0, 0, []
    for byte in data:
        acc = (acc << 8) | byte
        bits += 8
        while bits >= 5:
            bits -= 5
            out.append((acc >> bits) & 31)
    if bits:
        out.append((acc << (5 - bits)) & 31)
    expanded = [ord(c) >> 5 for c in hrp] + [0] + [ord(c) & 31 for c in hrp]
    mod = polymod(expanded + out + [0] * 6) ^ 1
    checksum = [(mod >> 5 * (5 - i)) & 31 for i in range(6)]
    return hrp + "1" + "".join(CHARSET[d] for d in out + checksum)


def test_invoice(memo, amount="1u", expiry=3600):
    """A well-formed BOLT-11 invoice (valid bech32, random signature) for display tests."""
    def words_of(number, count):
        return [(number >> (5 * (count - 1 - i))) & 31 for i in range(count)]

    def to_words(data):
        acc = bits = 0
        out = []
        for byte in data:
            acc = (acc << 8) | byte
            bits += 8
            while bits >= 5:
                bits -= 5
                out.append((acc >> bits) & 31)
        if bits:
            out.append((acc << (5 - bits)) & 31)
        return out

    memo_words = to_words(memo.encode())
    expiry_words = words_of(expiry, 2)
    words = (words_of(int(time.time()), 7)
             + [13] + words_of(len(memo_words), 2) + memo_words
             + [6] + words_of(len(expiry_words), 2) + expiry_words
             + [secrets.randbelow(32) for _ in range(104)])
    return bech32_words("lnbc" + amount, words)


def main():
    command, keyfile = sys.argv[1], sys.argv[2]
    if command == "account":
        account = secrets.randbelow(N - 1) + 1
        sender = secrets.randbelow(N - 1) + 1
        with open(keyfile, "w") as f:
            json.dump({"account": hex(account), "sender": hex(sender)}, f)
        print(bech32("nsec", account.to_bytes(32, "big")))
    elif command == "send":
        with open(keyfile) as f:
            keys = json.load(f)
        account, sender = int(keys["account"], 16), int(keys["sender"], 16)
        profile = signed(sender, 0, json.dumps({"name": SENDER_NAME}), [])
        text = sys.argv[3].replace("{invoice}", test_invoice("Lunch money"))
        dm = gift_wrap(sender, xonly(account).hex(), text)
        asyncio.run(publish([profile, dm]))


if __name__ == "__main__":
    main()
