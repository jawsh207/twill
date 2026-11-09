#!/usr/bin/env python3
"""Nostr helpers for the emulator test, so it can receive a real NIP-17 DM.

  nostr_dm.py account <keyfile>       make the emulator account's key; prints its nsec
  nostr_dm.py send <keyfile> <text>   send <text> to that account from a test sender
                                      (named "Robin Test"), gift-wrapped (NIP-17/44/59)
  nostr_dm.py wallet <keyfile> <linkfile> <seconds>
                                      run a test Nostr Wallet Connect service (NIP-47) for
                                      that long; writes its connection link to <linkfile>.
                                      It "pays" every invoice and reports 21,000 sats.

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


def nip44_decrypt(payload, priv, pub_hex):
    raw = base64.b64decode(payload)
    assert raw[0] == 2
    nonce, ciphertext, mac = raw[1:33], raw[33:-32], raw[-32:]
    keys = hkdf_expand(conversation_key(priv, pub_hex), nonce, 76)
    chacha_key, chacha_nonce, hmac_key = keys[:32], keys[32:44], keys[44:]
    expected = hmac.new(hmac_key, nonce + ciphertext, hashlib.sha256).digest()
    assert hmac.compare_digest(mac, expected)
    cipher = Cipher(algorithms.ChaCha20(chacha_key, b"\0\0\0\0" + chacha_nonce), mode=None)
    padded = cipher.decryptor().update(ciphertext)
    length = int.from_bytes(padded[:2], "big")
    return padded[2:2 + length].decode()


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
    expiry_words = words_of(expiry, 3)  # 3600 needs three 5-bit words
    words = (words_of(int(time.time()), 7)
             + [13] + words_of(len(memo_words), 2) + memo_words
             + [6] + words_of(len(expiry_words), 2) + expiry_words
             + [secrets.randbelow(32) for _ in range(104)])
    return bech32_words("lnbc" + amount, words)


WALLET_RELAYS = ["wss://nos.lol", "wss://relay.primal.net", "wss://relay.damus.io",
                 "wss://relay.nostr.band"]


async def run_wallet(wallet, client_secret, seconds, linkfile):
    """Serves NIP-47 on every relay that lets it in; the link lists them all."""
    import websockets

    me = xonly(wallet).hex()
    client = xonly(client_secret).hex()
    info = signed(wallet, 13194, "pay_invoice get_balance get_info",
                  [["encryption", "nip44_v2"]])
    sockets = {}
    for relay in WALLET_RELAYS:
        try:
            ws = await websockets.connect(relay, open_timeout=10)
            await ws.send(json.dumps(["EVENT", info]))
            reply = await asyncio.wait_for(ws.recv(), 10)
            print("wallet info on", relay, reply[:120], flush=True)
            if '"OK"' in reply and "true" in reply:
                sockets[relay] = ws
            else:
                await ws.close()
        except Exception as exc:  # noqa: BLE001
            print("wallet relay", relay, "failed:", exc, flush=True)
    if not sockets:
        return
    relays = "".join("&relay=" + r for r in sockets)
    with open(linkfile, "w") as f:
        f.write("nostr+walletconnect://" + me + "?" + relays.lstrip("&")
                + "&secret=" + client_secret.to_bytes(32, "big").hex())
    answered = set()

    async def serve(relay, ws):
        await ws.send(json.dumps(["REQ", "nwc", {"kinds": [23194], "#p": [me],
                                                  "since": int(time.time()) - 60}]))
        deadline = time.time() + seconds
        while time.time() < deadline:
            try:
                message = json.loads(await asyncio.wait_for(ws.recv(), 5))
            except asyncio.TimeoutError:
                continue
            except Exception as exc:  # noqa: BLE001
                print("wallet relay", relay, "closed:", exc, flush=True)
                return
            print("wallet relay", relay, str(message)[:160], flush=True)
            if message[0] != "EVENT" or message[2].get("pubkey") != client:
                continue
            request = message[2]
            if request["id"] in answered:
                continue
            answered.add(request["id"])
            body = json.loads(nip44_decrypt(request["content"], wallet, client))
            method = body.get("method")
            print("wallet request:", method, flush=True)
            if method == "get_info":
                reply = {"result_type": method, "result": {
                    "alias": "Twill Test Wallet", "methods": ["pay_invoice", "get_balance"]}}
            elif method == "get_balance":
                reply = {"result_type": method, "result": {"balance": 21_000_000}}
            elif method == "pay_invoice":
                reply = {"result_type": method, "result": {"preimage": "00" * 32}}
            else:
                reply = {"result_type": method, "error": {"code": "NOT_IMPLEMENTED",
                                                          "message": "Not here"}}
            response = signed(wallet, 23195, nip44_encrypt(json.dumps(reply), wallet, client),
                              [["e", request["id"]], ["p", client]])
            for other in sockets.values():
                try:
                    await other.send(json.dumps(["EVENT", response]))
                except Exception:  # noqa: BLE001
                    pass

    await asyncio.gather(*(serve(r, ws) for r, ws in sockets.items()))


def main():
    command, keyfile = sys.argv[1], sys.argv[2]
    if command == "account":
        account = secrets.randbelow(N - 1) + 1
        sender = secrets.randbelow(N - 1) + 1
        with open(keyfile, "w") as f:
            json.dump({"account": hex(account), "sender": hex(sender)}, f)
        print(bech32("nsec", account.to_bytes(32, "big")))
    elif command == "wallet":
        wallet = secrets.randbelow(N - 1) + 1
        client = secrets.randbelow(N - 1) + 1
        asyncio.run(run_wallet(wallet, client, int(sys.argv[4]), sys.argv[3]))
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
