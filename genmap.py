#!/usr/bin/env python3
# 从 fcitx5-android 的 codegen 里提取键值映射表，生成等价的 Kotlin 文件
import re, os

SRC = "/root/src/fcitx5-android-master/codegen/src/main/java/org/fcitx/fcitx5/android/codegen"
DST = "/root/jm3/app/src/main/java/com/jimeng/ime/kb/core"
os.makedirs(DST, exist_ok=True)

# ---------- FcitxKeyMapping ----------
src = open(f"{SRC}/GenKeyMapping.kt").read()
pairs = []
for m in re.finditer(r'^\s*"([^"]+)" to (0x[0-9a-fA-F]+) to "([A-Z_0-9]+)",', src, re.M):
    pairs.append((m.group(1), m.group(2), m.group(3)))
print(f"FcitxKeyMapping: {len(pairs)} pairs")

consts = "\n".join(f'    const val FcitxKey_{n} = {v}' for n, v, _ in pairs)
sym_to_kc = "\n".join(f'        FcitxKey_{n} -> KeyEvent.{k}' for n, _, k in pairs)
# keyCodeToSym 排除大写字母（与原版一致）
kc_to_sym = "\n".join(
    f'        KeyEvent.{k} -> FcitxKey_{n}'
    for n, v, k in pairs if not (0x41 <= int(v, 16) <= 0x5a)
)

fcitx = f'''/*
 * 从 fcitx5-android 的 codegen/GenKeyMapping.kt 生成的 FcitxKeyMapping 等价实现。
 *
 * 原版由 KSP 在编译期生成，本工程不带 KSP，所以把生成结果直接落成源码。
 * 数据完全取自原版的 pairs 列表（{len(pairs)} 条），语义逐条一致。
 */
package com.jimeng.ime.kb.core

import android.view.KeyEvent

object FcitxKeyMapping {{

{consts}

    /** keysym -> Android keycode */
    fun symToKeyCode(sym: Int): Int? = when (sym) {{
{sym_to_kc}
        else -> null
    }}

    /**
     * Android keycode -> keysym
     *
     * 有意排除大写拉丁字母区间（0x41..0x5A）：Android 没有大小写各自独立的 keycode，
     * 而 ASCII 可打印字符的 keysym 就是字符本身，按住 Shift 时会走 unicodeChar 路径。
     */
    fun keyCodeToSym(code: Int): Int? = when (code) {{
{kc_to_sym}
        else -> null
    }}
}}
'''
open(f"{DST}/FcitxKeyMapping.kt", "w").write(fcitx)

# ---------- ScancodeMapping ----------
src2 = open(f"{SRC}/GenScancodeMapping.kt").read()
# SCANCODE_MAP
scan = []
in_scan = False
for line in src2.split("\n"):
    if "SCANCODE_MAP = listOf(" in line:
        in_scan = True
        continue
    if in_scan:
        if ")" in line and "to" not in line:
            break
        m = re.search(r'"([A-Z_0-9]+)" to (\d+) to "([A-Z_0-9]*)",', line)
        if m:
            scan.append((m.group(1), m.group(2), m.group(3)))
print(f"ScancodeMapping: {len(scan)} scancodes")

# CHAR_MAP
chars = []
in_char = False
for line in src2.split("\n"):
    if "CHAR_MAP = listOf(" in line:
        in_char = True
        continue
    if in_char:
        if line.strip() == ")":
            break
        m = re.search(r'"(\\\\.|[^"])" to "([A-Z_]+)",', line)
        if m:
            ch = m.group(1)
            chars.append((ch, m.group(2)))
print(f"charToScancode: {len(chars)} chars")

scan_consts = "\n".join(f'    const val {n} = {v}' for n, v, _ in scan)
scan_kc = "\n".join(
    f'        KeyEvent.{k} -> {n}' for n, _, k in scan if k
)


def kotlin_char(c):
    if c == "\\\\":
        return "'\\\\'"
    if c == "\\'":
        return "'\\''"
    if c == '\\"':
        return "'\"'"
    return f"'{c}'"


char_when = "\n".join(f'        {kotlin_char(c)} -> {n}' for c, n in chars)

scancode = f'''/*
 * 从 fcitx5-android 的 codegen/GenScancodeMapping.kt 生成的 ScancodeMapping 等价实现。
 *
 * scancode 用的是 Linux evdev 的 keycode（KEY_*），定义见
 * https://github.com/torvalds/linux/blob/v6.10/include/uapi/linux/input-event-codes.h
 *
 * 原版由 KSP 生成，这里直接落成源码，数据取自原版的 SCANCODE_MAP（{len(scan)} 条）
 * 与 CHAR_MAP（{len(chars)} 条），语义逐条一致。
 */
package com.jimeng.ime.kb.core

import android.view.KeyEvent

object ScancodeMapping {{

{scan_consts}

    /** 字符 -> Linux evdev scancode */
    fun charToScancode(ch: Char): Int = when (ch) {{
{char_when}
        else -> KEY_RESERVED
    }}

    /** Android keycode -> Linux evdev scancode */
    fun keyCodeToScancode(keyCode: Int): Int = when (keyCode) {{
{scan_kc}
        else -> KEY_RESERVED
    }}
}}
'''
open(f"{DST}/ScancodeMapping.kt", "w").write(scancode)
print("done")
