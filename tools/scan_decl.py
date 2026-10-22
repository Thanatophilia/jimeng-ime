#!/usr/bin/env python3
# 更严格的死代码扫描：逐个顶层声明检查是否被其他文件引用
import os
import re
from collections import defaultdict

ROOT = 'app/src/main'
JAVA = os.path.join(ROOT, 'java')
MANIFEST = os.path.join(ROOT, 'AndroidManifest.xml')


def read(p):
    try:
        return open(p, encoding='utf-8', errors='ignore').read()
    except Exception:
        return ''


def walk(base, exts):
    out = []
    for dp, _, fns in os.walk(base):
        for f in fns:
            if f.endswith(exts):
                out.append(os.path.join(dp, f))
    return out


files = walk(JAVA, ('.kt',))
src = {f: read(f) for f in files}
manifest = read(MANIFEST)

# 找顶层声明（行首、无缩进）
decl_re = re.compile(
    r'^(?:@\w+\s+)*'
    r'(?:public\s+|internal\s+|private\s+|abstract\s+|open\s+|sealed\s+|data\s+|enum\s+|annotation\s+|inline\s+|value\s+)*'
    r'(class|object|interface|fun|val|var|typealias)\s+'
    r'([A-Za-z_]\w*)',
    re.M
)

decls = []  # (file, kind, name)
for f in files:
    for m in decl_re.finditer(src[f]):
        decls.append((f, m.group(1), m.group(2)))

# 建立全局引用索引
all_text = '\n'.join(src.values()) + '\n' + manifest

print('=== 跨文件无引用的顶层声明 ===')
dead = []
for f, kind, name in decls:
    # 统计除自己文件以外的引用
    hits = 0
    for other in files:
        if other == f:
            continue
        if re.search(r'\b' + re.escape(name) + r'\b', src[other]):
            hits += 1
            break
    if hits:
        continue
    if re.search(r'\b' + re.escape(name) + r'\b', manifest):
        continue
    # 同文件内部引用次数：定义处 1 次，若 >1 说明被同文件其他代码用到
    same_file_uses = len(re.findall(r'\b' + re.escape(name) + r'\b', src[f]))
    dead.append((os.path.getsize(f), f, kind, name, same_file_uses))

dead.sort(reverse=True)
for sz, f, kind, name, n in dead:
    flag = '同文件内用 %d 次' % n
    print('%8d  %-10s %-34s [%s]  %s' % (
        sz, kind, name, flag, f.replace('app/src/main/java/', '')))
print('\n合计 %d 个' % len(dead))
