#!/usr/bin/env python3
"""Fail if a minified APK is missing BouncyCastle classes required for TLS certs.

R8 removes algorithm classes that BouncyCastle loads by name. The runtime error
is identical to a wrong provider name: "no such algorithm: SHA256WITHRSA for
provider BC". Unit tests do not catch this because they run unminified.
"""

import sys
import zipfile
from pathlib import Path

# Dex type descriptors use slashes. Reflection names inside BC use dots.
# Accept either form so the check matches what actually lands in classes.dex.
REQUIRED = (
    "org.bouncycastle.jce.provider.BouncyCastleProvider",
    "org.bouncycastle.operator.jcajce.JcaContentSignerBuilder",
    "org.bouncycastle.jcajce.provider.asymmetric.rsa.DigestSignatureSpi$SHA256",
)


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} <apk>", file=sys.stderr)
        return 2

    apk_path = Path(sys.argv[1])
    if not apk_path.is_file():
        print(f"APK not found: {apk_path}", file=sys.stderr)
        return 2

    blob = bytearray()
    with zipfile.ZipFile(apk_path) as apk:
        dex_names = [name for name in apk.namelist() if name.startswith("classes") and name.endswith(".dex")]
        if not dex_names:
            print(f"No classes*.dex in {apk_path}", file=sys.stderr)
            return 1
        for name in dex_names:
            blob.extend(apk.read(name))

    text = blob.decode("latin1")

    def present(name: str) -> bool:
        return name in text or name.replace(".", "/") in text

    missing = [name for name in REQUIRED if not present(name)]
    if missing:
        print("Minified APK is missing BouncyCastle classes (R8 stripped them):")
        for name in missing:
            print(f"  - {name}")
        return 1

    print(f"BouncyCastle signer classes present in {apk_path.name} ({len(dex_names)} dex)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
