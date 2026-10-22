#!/usr/bin/env python3
# 找从未被调用的成员函数（保守：只报告，不自动删）
import os
import re

JAVA = 'app/src/main/java'


def read(p):
    try:
        return open(p, encoding='utf-8', errors='ignore').read()
    except Exception:
        return ''


files = []
for dp, _, fns in os.walk(JAVA):
    for f in fns:
        if f.endswith('.kt'):
            files.append(os.path.join(dp, f))
src = {f: read(f) for f in files}
all_text = '\n'.join(src.values())

# 成员函数定义：缩进 4 空格 + fun name(
fn_re = re.compile(r'^    (?:private |internal |public |protected |override |open |suspend |inline |operator )*fun\s+(?:<[^>]+>\s*)?(\w+)\s*\(', re.M)

print('=== 从未被调用的成员函数（排除 override / 常见回调） ===')
SKIP = {
    'onCreate', 'onDestroy', 'onMeasure', 'onLayout', 'onDraw', 'onSizeChanged',
    'onAttachedToWindow', 'onDetachedFromWindow', 'onTouchEvent', 'onInterceptTouchEvent',
    'onKeyDown', 'onKeyUp', 'onBindViewHolder', 'onCreateViewHolder', 'getItemCount',
    'onViewRecycled', 'onLayoutCompleted', 'onStartInput', 'onStartInputView',
    'onFinishInput', 'onFinishInputView', 'onUpdateSelection', 'onConfigurationChanged',
    'onStartInputView', 'onInitializeInterface', 'onCurrentInputMethodSubtypeChanged',
    'canScrollVertically', 'canScrollHorizontally', 'commitText', 'setText', 'setTypeface',
    'setTextSize', 'toString', 'equals', 'hashCode', 'invoke', 'onStateChanged', 'onReady',
    'onCommit', 'onCandidateUpdate', 'getMovementFlags', 'onMove', 'onSwiped',
    'onChildDraw', 'onDrawDivider', 'onDrawOver', 'getItemOffsets', 'setPage',
    'onBind', 'onCreateInputView', 'onEvaluateInputViewShown', 'onFinishInput',
    'onUpdateCursorAnchorInfo', 'onComputeInsets', 'onConfigureWindow', 'onWindowShown',
    'onWindowHidden', 'onKeyAction', 'onAction', 'onDetached', 'onAttach',
    'onPrimaryClipChanged', 'onSharedPreferenceChanged', 'setExpanded', 'apply',
}
results = []
for f in files:
    text = src[f]
    for m in fn_re.finditer(text):
        name = m.group(1)
        if name in SKIP:
            continue
        # 全工程搜索调用次数（排除定义本身）
        uses = len(re.findall(r'\b' + re.escape(name) + r'\s*\(', all_text))
        if uses <= 1:
            results.append((os.path.getsize(f), f, name))

results.sort(reverse=True)
seen = set()
for sz, f, name in results:
    key = (f, name)
    if key in seen:
        continue
    seen.add(key)
    print('%8d  %-32s  %s' % (sz, name, f.replace(JAVA + '/', '')))
print('\n合计 %d 个' % len(seen))