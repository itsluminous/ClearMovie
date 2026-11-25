#!/usr/bin/env python3
"""Fails when native code ClearMovie runs could open a network connection itself.

    scripts/check-native-network.py [--manifest <manifest.json>] <zip-or-apk> [...]

ClearMovie uses the internet only to download the voice pack
(cm-voice-pack-download). The Java and Kotlin side is checked by
NetworkUseConventionTest and by verify-release-apks.sh's dex check, but native
code loaded into the app's process (the APK's own .so files, and the voice
pack's engines, loaded with System.load) bypasses the network security config.
So every lib/<abi>/*.so in each given zip (an APK, or a voice pack engine zip)
is read here, and the check fails if one of them imports a socket, name lookup
or TLS function. It reads each ELF file's dynamic symbol table directly, so it
needs only Python 3, on CI and on a Mac alike.

Imports that could reach the network without naming a socket function
(dlopen, dlsym, syscall) fail too, except where INDIRECT_ALLOWED lists them
for that library: JNA loads libraries by name (that is what it is for),
onnxruntime and sherpa-onnx load optional execution providers, and Vosk and
onnxruntime use syscall for futex and thread calls. A new library, or a new
use, fails until it is looked at and listed. A library whose dynamic symbols
cannot be read fails as well, so a stripped or odd file never passes unread.

With --manifest, each given zip named in that voice pack manifest must have
the manifest's size and SHA-256, and every engine the manifest lists must be
among the files given: the bytes checked are the bytes phones accept.
"""
import hashlib
import json
import os
import re
import struct
import sys
import zipfile

# Imported functions that reach the network.
NETWORK = re.compile(
    r"^(socket|socketpair|connect|bind|listen|accept4?|send(to|msg|mmsg)?|recv(from|msg|mmsg)?|"
    r"__(send|recv)\w*_chk|getaddrinfo|getnameinfo|gethostbyname2?(_r)?|gethostbyaddr(_r)?|res_n?query|res_n?search|"
    r"android_getaddrinfofornetwork|android_res_n.*|android_setsocknetwork|inet_(pton|aton|addr)|SSL_.*|TLS_.*|curl_.*)$"
)

# Imports that could reach the network indirectly.
INDIRECT = re.compile(r"^(dlopen|dlsym|dlvsym|android_dlopen_ext|syscall)$")

# Library file name -> the indirect imports it is known to have, and why (see above).
INDIRECT_ALLOWED = {
    "libjnidispatch.so": {"dlopen", "dlsym"},
    "libonnxruntime.so": {"dlopen", "dlsym", "syscall"},
    "libsherpa-onnx-jni.so": {"dlopen", "syscall"},
    "libvosk.so": {"syscall"},
}

SHT_DYNSYM = 11


def undefined_symbols(data: bytes) -> list:
    """The undefined (imported) dynamic symbols of an ELF shared library."""
    if data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    bits = {1: 32, 2: 64}[data[4]]
    end = {1: "<", 2: ">"}[data[5]]
    if bits == 64:
        shoff, = struct.unpack_from(end + "Q", data, 0x28)
        shentsize, shnum = struct.unpack_from(end + "HH", data, 0x3A)
    else:
        shoff, = struct.unpack_from(end + "I", data, 0x20)
        shentsize, shnum = struct.unpack_from(end + "HH", data, 0x2E)

    def section(i):
        o = shoff + i * shentsize
        if bits == 64:
            _, typ, _, _, off, size, link, _, _, entsize = struct.unpack_from(end + "IIQQQQIIQQ", data, o)
        else:
            _, typ, _, _, off, size, link, _, _, entsize = struct.unpack_from(end + "IIIIIIIIII", data, o)
        return typ, off, size, link, entsize

    if shoff == 0 or shnum == 0 or shentsize == 0 or shoff + shnum * shentsize > len(data):
        raise ValueError("no section headers to read the dynamic symbols from")
    names = []
    dynsyms = 0
    for i in range(shnum):
        typ, off, size, link, entsize = section(i)
        if typ != SHT_DYNSYM:
            continue
        if entsize == 0 or link >= shnum or off + size > len(data):
            raise ValueError("a .dynsym section that cannot be read")
        dynsyms += 1
        _, stroff, strsize, _, _ = section(link)
        if stroff + strsize > len(data):
            raise ValueError("a dynamic string table past the end of the file")
        for k in range(size // entsize):
            o = off + k * entsize
            if bits == 64:
                name, _, _, shndx = struct.unpack_from(end + "IBBH", data, o)
            else:
                name, _, _, _, _, shndx = struct.unpack_from(end + "IIIBBH", data, o)
            if shndx != 0 or name == 0:
                continue
            if name >= strsize:
                raise ValueError("a symbol name outside its string table")
            s = data[stroff + name: data.index(b"\0", stroff + name)]
            names.append(s.decode("ascii", "replace"))
    if dynsyms == 0:
        raise ValueError("no .dynsym section")
    return names


def check_manifest(manifest_path, paths, bad):
    """Each given zip the manifest lists has its bytes; every listed engine is given."""
    with open(manifest_path) as f:
        files = {e["file"]: e for e in json.load(f)["files"]}
    # Only the zips in the manifest's own pack folder (voice-packs/v<n>/, by its version) are its files.
    with open(manifest_path) as f:
        version = json.load(f)["version"]
    given = {os.path.basename(p): p for p in paths if os.path.basename(os.path.dirname(os.path.abspath(p))) == f"v{version}"}
    for name, entry in files.items():
        if entry.get("part") == "engine" and name not in given:
            bad.append(f"{manifest_path}: the engine {name} was not checked")
    for name, path in given.items():
        entry = files.get(name)
        if entry is None:
            continue
        with open(path, "rb") as f:
            data = f.read()
        if len(data) != entry["bytes"] or hashlib.sha256(data).hexdigest() != entry["sha256"].lower():
            bad.append(f"{path}: not the bytes {manifest_path} lists")


def main(args) -> int:
    manifest = None
    if args[:1] == ["--manifest"]:
        if len(args) < 3 or not args[1].endswith(".json"):
            print("--manifest needs a manifest .json and then the files to check", file=sys.stderr)
            return 2
        manifest, args = args[1], args[2:]
    paths = args
    if not paths:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    bad = []
    count = 0
    if manifest:
        check_manifest(manifest, paths, bad)
    for path in paths:
        with zipfile.ZipFile(path) as z:
            libs = [n for n in z.namelist() if re.match(r"^lib/[^/]+/[^/]+\.so$", n)]
            if not libs:
                bad.append(f"{path}: no lib/<abi>/*.so to check")
            for name in libs:
                count += 1
                try:
                    symbols = undefined_symbols(z.read(name))
                except (ValueError, KeyError, IndexError, struct.error) as e:
                    bad.append(f"{path}!{name}: could not read it ({e})")
                    continue
                found = sorted({s for s in symbols if NETWORK.match(s)})
                allowed = INDIRECT_ALLOWED.get(os.path.basename(name), set())
                found += sorted({s for s in symbols if INDIRECT.match(s) and s not in allowed})
                if found:
                    bad.append(f"{path}!{name}: imports {', '.join(found)}")
    for b in bad:
        print("FAIL: " + b, file=sys.stderr)
    if bad:
        return 1
    print(f"No network functions imported by {count} native libraries in {len(paths)} files.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
