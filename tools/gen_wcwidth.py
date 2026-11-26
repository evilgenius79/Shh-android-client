#!/usr/bin/env python3
"""Generates WcWidthTables.kt from Python's bundled Unicode Character Database.

Usage: python3 tools/gen_wcwidth.py > terminal-core/src/main/kotlin/io/github/evilgenius79/shh/terminal/WcWidthTables.kt

Rules (mirroring what glibc's wcwidth does for a UTF-8 locale):
  * width 0: nonspacing marks (Mn), enclosing marks (Me), format characters (Cf) except
    SOFT HYPHEN, and the conjoining Hangul Jamo medial vowels / final consonants.
  * width 2: East Asian Width "W" (wide) or "F" (fullwidth), which since Unicode 9 also covers
    every character with default emoji presentation. Planes 2 and 3 are wide in their entirety.
  * width 1: everything else.
"""
import sys
import unicodedata


def is_zero(cp: int) -> bool:
    if cp == 0x00AD:
        return False
    if 0x1160 <= cp <= 0x11FF or 0xD7B0 <= cp <= 0xD7FF:
        return True
    return unicodedata.category(chr(cp)) in ("Mn", "Me", "Cf")


def is_wide(cp: int) -> bool:
    if 0x20000 <= cp <= 0x2FFFD or 0x30000 <= cp <= 0x3FFFD:
        return True
    return unicodedata.east_asian_width(chr(cp)) in ("W", "F")


def ranges(pred):
    out, start = [], None
    for cp in range(0x110000):
        if 0xD800 <= cp <= 0xDFFF:
            hit = False
        else:
            hit = pred(cp)
        if hit and start is None:
            start = cp
        elif not hit and start is not None:
            out.append((start, cp - 1))
            start = None
    if start is not None:
        out.append((start, 0x10FFFF))
    return out


def emit(name, rs):
    print(f"    /** {len(rs)} inclusive ranges, flattened as start, end, start, end, ... */")
    print(f"    @JvmField")
    print(f"    val {name}: IntArray = intArrayOf(")
    line = "       "
    for a, b in rs:
        item = f" 0x{a:04X}, 0x{b:04X},"
        if len(line) + len(item) > 100:
            print(line)
            line = "       "
        line += item
    print(line)
    print("    )")


zero = ranges(is_zero)
wide = ranges(lambda cp: is_wide(cp) and not is_zero(cp))
print("// GENERATED FILE - do not edit by hand. Regenerate with tools/gen_wcwidth.py")
print(f"// Source: Unicode Character Database {unicodedata.unidata_version} (via Python {sys.version.split()[0]})")
print("package io.github.evilgenius79.shh.terminal")
print()
print("internal object WcWidthTables {")
print(f'    const val UNICODE_VERSION: String = "{unicodedata.unidata_version}"')
print()
emit("ZERO_WIDTH", zero)
print()
emit("WIDE", wide)
print("}")
