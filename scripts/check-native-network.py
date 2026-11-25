#!/usr/bin/env python3
"""Fails when native code ClearMovie runs could open a network connection itself.

    scripts/check-native-network.py <zip-or-apk> [...]

ClearMovie uses the internet only to download the voice pack
(cm-voice-pack-download). The Java and Kotlin side is checked by
NetworkUseConventionTest and by verify-release-apks.sh's dex check, but native
code loaded into the app's process (the APK's own .so files, and the voice
pack's engines, loaded with System.load) bypasses the network security config.
So every lib/<abi>/*.so in each given zip (an APK, or a voice pack engine zip)
is read here, and the check fails if one of them imports a socket, name lookup
or TLS function. It reads each ELF file's dynamic symbol table directly, so it
needs only Python 3, on CI and on a Mac alike.

This covers what a library links against. It cannot see a function looked up
by name at run time (dlsym), so a new native library is still worth a look.
"""
import re
import struct
import sys
import zipfile

# Imported functions that reach the network.
NETWORK = re.compile(
    r"^(socket|socketpair|connect|bind|listen|accept4?|send(to|msg|mmsg)?|recv(from|msg|mmsg)?|"
    r"getaddrinfo|getnameinfo|gethostbyname2?(_r)?|gethostbyaddr(_r)?|res_n?query|res_n?search|"
    r"android_getaddrinfofornetwork|android_res_n.*|inet_(pton|aton|addr)|SSL_.*|TLS_.*|curl_.*)$"
)

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

    names = []
    for i in range(shnum):
        typ, off, size, link, entsize = section(i)
        if typ != SHT_DYNSYM or entsize == 0:
            continue
        _, stroff, strsize, _, _ = section(link)
        for k in range(size // entsize):
            o = off + k * entsize
            if bits == 64:
                name, _, _, shndx = struct.unpack_from(end + "IBBH", data, o)
            else:
                name, _, _, _, _, shndx = struct.unpack_from(end + "IIIBBH", data, o)
            if shndx != 0 or name == 0:
                continue
            s = data[stroff + name: data.index(b"\0", stroff + name)]
            names.append(s.decode("ascii", "replace"))
    return names


def main(paths) -> int:
    if not paths:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    bad = []
    count = 0
    for path in paths:
        with zipfile.ZipFile(path) as z:
            libs = [n for n in z.namelist() if re.match(r"^lib/[^/]+/[^/]+\.so$", n)]
            if not libs:
                bad.append(f"{path}: no lib/<abi>/*.so to check")
            for name in libs:
                count += 1
                try:
                    found = sorted({s for s in undefined_symbols(z.read(name)) if NETWORK.match(s)})
                except (ValueError, KeyError, struct.error) as e:
                    bad.append(f"{path}!{name}: could not read it ({e})")
                    continue
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
