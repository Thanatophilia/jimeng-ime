#!/usr/bin/env python3
# 静态扫描：找出未被引用的资源文件 + 未被引用的 Kotlin 顶层声明
import os
import re

ROOT = 'app/src/main'
RES = os.path.join(ROOT, 'res')
JAVA = os.path.join(ROOT, 'java')
MANIFEST = os.path.join(ROOT, 'AndroidManifest.xml')


def read(p):
    try:
        return open(p, encoding='utf-8', errors='ignore').read()
    except Exception:
        return ''


def walk_files(base, exts):
    out = []
    for dp, _, fns in os.walk(base):
        for f in fns:
            if f.endswith(exts):
                out.append(os.path.join(dp, f))
    return out


# ---------- 1. 资源引用 ----------
res_refs = set()          # (kind, name)
res_ref_pat = re.compile(r'R\.(drawable|mipmap|string|color|xml|layout|raw|anim|style|array|dimen)\.(\w+)')
xml_ref_pat = re.compile(r'@(drawable|mipmap|color|xml|style|string|array|dimen|raw|layout|anim)/(\w+)')

all_src = walk_files(JAVA, ('.kt',)) + walk_files(RES, ('.xml',)) + [MANIFEST]
for f in all_src:
    t = read(f)
    for m in res_ref_pat.finditer(t):
        res_refs.add((m.group(1), m.group(2)))
    for m in xml_ref_pat.finditer(t):
        res_refs.add((m.group(1), m.group(2)))

# ---------- 2. 实际资源文件 ----------
KINDS = ('drawable', 'mipmap', 'layout', 'raw', 'xml', 'anim', 'color', 'menu')
unused_res = []
for dp, _, fns in os.walk(RES):
    folder = os.path.basename(dp)
    kind = folder.split('-')[0]
    if kind not in KINDS:
        continue
    for f in fns:
        name = f.rsplit('.', 1)[0]
        # ic_launcher* 由 manifest/系统按名引用
        if name.startswith('ic_launcher'):
            continue
        if (kind, name) not in res_refs:
            unused_res.append((os.path.getsize(os.path.join(dp, f)), os.path.join(dp, f)))

unused_res.sort(reverse=True)
print('=== 未被引用的资源文件 ===')
tot = 0
for sz, p in unused_res:
    print('%8d  %s' % (sz, p))
    tot += sz
print('合计 %d 个, %d 字节 (%.1fKB)\n' % (len(unused_res), tot, tot / 1024))

# ---------- 3. Kotlin 文件是否整体未被引用 ----------
print('=== 可能整体未使用的 Kotlin 文件 ===')
files = walk_files(JAVA, ('.kt',))
# 建立：文件名 -> 其中定义的顶层类/对象/接口名
decl_pat = re.compile(r'^(?:@\w+\s+)*(?:public |internal |private |abstract |open |sealed |data |enum |annotation )*'
                      r'(?:class|object|interface)\s+(\w+)', re.M)
file_decls = {}
for f in files:
    t = read(f)
    names = decl_pat.findall(t)
    file_decls[f] = names

for f in sorted(files):
    names = file_decls[f]
    if not names:
        continue
    # 只测第一个（主）声明，避开同文件辅助类
    main = names[0]
    used = False
    for other in files:
        if other == f:
            continue
        ot = read(other)
        if re.search(r'\b' + re.escape(main) + r'\b', ot):
            used = True
            break
    if not used:
        # manifest 里注册的 Activity 也算用到
        if re.search(r'\b' + re.escape(main) + r'\b', read(MANIFEST)):
            continue
        print('%8d  %s   (%s)' % (os.path.getsize(f), f, main))
