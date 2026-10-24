#!/bin/bash
# 把 beta 包挂到 GitHub Release，方便网页直接下载
# 用法: ./release.sh <tag> <apk路径> [说明文件]
#   不传说明文件时，用 tag 的注释正文作为 Release 说明。

TAG="$1"
APK="$2"
BODY_FILE="$3"
REPO="selenaflora927-dot/jimeng-ime"
TOKEN=$(sed -n 's|https://\([^:]*\):\([^@]*\)@github.com|\2|p' /root/.git-credentials | head -1)

if [ -z "$TAG" ] || [ -z "$APK" ]; then
    echo "用法: $0 <tag> <apk路径> [说明文件]"
    exit 1
fi

if [ ! -f "$APK" ]; then
    echo "APK 不存在: $APK"
    exit 1
fi

NAME=$(basename "$APK")
SIZE=$(stat -c %s "$APK")

echo "=== 1. 创建 Release: $TAG ==="
# 说明来源：优先用第三个参数指定的 Markdown 文件，否则取 tag 注释正文。
# 注意两点：
#   1. 用 for-each-ref 取正文，git tag -l -n99 会把首行并进 tag 名那一行，
#      再 tail -n +2 会把正文第一行（通常是标题）直接截掉。
#   2. JSON 转义交给 python 的 json.dumps，别用 sed 手拼 —— 换行、引号、
#      反斜杠凑在一起很容易出错。
if [ -n "$BODY_FILE" ] && [ -f "$BODY_FILE" ]; then
    RAW_BODY=$(cat "$BODY_FILE")
else
    RAW_BODY=$(cd /root/jm3 && git for-each-ref --format='%(contents)' "refs/tags/$TAG")
fi
if [ -z "$RAW_BODY" ]; then
    echo "说明为空：tag 没有注释，也没传说明文件"
    exit 1
fi

PAYLOAD=$(TAG="$TAG" REPO="$REPO" python3 - "$RAW_BODY" <<'PY'
import json, os, sys
body = sys.argv[1]
print(json.dumps({
    "tag_name": os.environ["TAG"],
    "name": os.environ["TAG"],
    "body": body,
    "draft": False,
    "prerelease": True,
}, ensure_ascii=False))
PY
)
RESP=$(curl -s -X POST \
    -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -H "Content-Type: application/json" \
    "https://api.github.com/repos/$REPO/releases" \
    --data-binary "$PAYLOAD")

UPLOAD_URL=$(echo "$RESP" | grep -o '"upload_url": *"[^"]*"' | sed 's/.*"\(https[^"]*\)".*/\1/' | sed 's/{.*//')
if [ -z "$UPLOAD_URL" ]; then
    echo "创建失败:"
    echo "$RESP" | head -20
    exit 1
fi
echo "  ok"

echo "=== 2. 上传 APK ($NAME, $SIZE bytes) ==="
UP=$(curl -s -X POST \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary @"$APK" \
    "${UPLOAD_URL}?name=${NAME}")
DL=$(echo "$UP" | grep -o '"browser_download_url": *"[^"]*"' | sed 's/.*"\(https[^"]*\)".*/\1/')
if [ -z "$DL" ]; then
    echo "上传失败:"
    echo "$UP" | head -10
    exit 1
fi

echo
echo "=== 完成 ==="
echo "下载地址: $DL"
