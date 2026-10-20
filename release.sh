#!/bin/bash
# 把 beta 包挂到 GitHub Release，方便网页直接下载
# 用法: ./release.sh <tag> <apk路径>

TAG="$1"
APK="$2"
REPO="selenaflora927-dot/jimeng-ime"
TOKEN=$(sed -n 's|https://\([^:]*\):\([^@]*\)@github.com|\2|p' /root/.git-credentials | head -1)

if [ -z "$TAG" ] || [ -z "$APK" ]; then
    echo "用法: $0 <tag> <apk路径>"
    exit 1
fi

if [ ! -f "$APK" ]; then
    echo "APK 不存在: $APK"
    exit 1
fi

NAME=$(basename "$APK")
SIZE=$(stat -c %s "$APK")

echo "=== 1. 创建 Release: $TAG ==="
# 从 tag 的注释里取说明
BODY=$(cd /root/jm3 && git tag -l -n99 "$TAG" | tail -n +2 | sed 's/^    //')
RESP=$(curl -s -X POST \
    -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/$REPO/releases" \
    -d "$(printf '{"tag_name":"%s","name":"%s","body":"%s","draft":false,"prerelease":true}' \
        "$TAG" "$TAG" "$(echo "$BODY" | sed ':a;N;$!ba;s/\n/\\n/g; s/"/\\"/g')")")

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
