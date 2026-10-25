#!/bin/bash
# 把 APK 挂到 GitHub Release，方便网页直接下载
#
# 用法:
#   ./release.sh <tag> <apk路径> [说明文件]
#
#   不传说明文件时，用 tag 的注释正文作为 Release 说明。
#
# 正式版 / beta 的判定：
#   tag 名里含 "beta" 就当 beta 发（prerelease=true），否则当正式版发
#   （prerelease=false）。想强制指定就传环境变量，例如：
#       PRERELEASE=false ./release.sh v1.0.1-beta1 app-debug.apk
#
# 仓库路径：
#   默认用脚本所在目录的 git 仓库（不写死 /root/jm3）。
#   tag 正文也从同一个仓库取。

set -u

TAG="${1:-}"
APK="${2:-}"
BODY_FILE="${3:-}"
REPO="selenaflora927-dot/jimeng-ime"

# 脚本所在的 git 仓库根目录。tag 正文、remote 地址都从这里取。
REPO_DIR=$(cd "$(dirname "$0")" && pwd)

usage() {
    echo "用法: $0 <tag> <apk路径> [说明文件]"
    echo "示例: $0 jimeng-ime app/build/outputs/apk/debug/jimeng-1.0.0-debug.apk"
}

if [ -z "$TAG" ] || [ -z "$APK" ]; then
    usage
    exit 1
fi

if [ ! -f "$APK" ]; then
    echo "APK 不存在: $APK"
    exit 1
fi

# ── 取 token ──────────────────────────────────────────────────────────
# 以前是从 /root/.git-credentials 读的，但那个文件根本不存在，导致 token
# 一直是空字符串、请求必然 401。改成从 remote URL 里拆，因为 remote 里
# 本来就内嵌着 PAT。两种写法都要认：
#     https://<user>:<token>@github.com/...   （带用户名）
#     https://<token>@github.com/...          （只有 token）
# 也支持用环境变量 GITHUB_TOKEN 覆盖（CI 里更方便）。
TOKEN="${GITHUB_TOKEN:-}"
REMOTE_URL=$(cd "$REPO_DIR" && git remote get-url origin 2>/dev/null)
if [ -z "$TOKEN" ] && [ -n "$REMOTE_URL" ]; then
    AUTH=$(echo "$REMOTE_URL" \
        | sed -n 's|^https://\([^@]*\)@github\.com/.*|\1|p')
    case "$AUTH" in
        "")   ;;                 # remote 里没带凭据（比如走 ssh 或 credential helper）
        *:*)  TOKEN="${AUTH#*:}" ;;   # user:token → 取冒号后面
        *)    TOKEN="$AUTH" ;;        # 只有 token
    esac
fi
if [ -z "$TOKEN" ]; then
    echo "取不到 GitHub token。"
    echo "  remote origin 形如 https://<user>:<token>@github.com/... 才能自动取到；"
    echo "  或者设置环境变量 GITHUB_TOKEN=<token>"
    exit 1
fi

# ── 判定是不是 beta ───────────────────────────────────────────────────
if [ -n "${PRERELEASE:-}" ]; then
    case "$(echo "$PRERELEASE" | tr 'A-Z' 'a-z')" in
        1|true|yes) IS_PRE=True ;;
        *)          IS_PRE=False ;;
    esac
else
    case "$TAG" in
        *beta*|*Beta*|*BETA*) IS_PRE=True ;;
        *)                    IS_PRE=False ;;
    esac
fi

NAME=$(basename "$APK")
SIZE=$(stat -c %s "$APK")

echo "=== 1. 创建 Release ==="
echo "  仓库: $REPO"
echo "  tag : $TAG"
echo "  包   : $NAME ($SIZE bytes)"
echo "  类型: $([ "$IS_PRE" = "True" ] && echo 'beta（prerelease）' || echo '正式版')"

# 说明来源：优先用第三个参数指定的 Markdown 文件，否则取 tag 注释正文。
# 注意两点：
#   1. 用 for-each-ref 取正文，git tag -l -n99 会把首行并进 tag 名那一行，
#      再 tail -n +2 会把正文第一行（通常是标题）直接截掉。
#   2. JSON 转义交给 python 的 json.dumps，别用 sed 手拼 —— 换行、引号、
#      反斜杠凑在一起很容易出错。
if [ -n "$BODY_FILE" ] && [ -f "$BODY_FILE" ]; then
    RAW_BODY=$(cat "$BODY_FILE")
else
    RAW_BODY=$(cd "$REPO_DIR" && git for-each-ref --format='%(contents)' "refs/tags/$TAG")
fi
if [ -z "$RAW_BODY" ]; then
    echo "说明为空：tag 没有注释，也没传说明文件"
    exit 1
fi

# 顺手检查 tag 有没有推到远端 —— 没推的话 GitHub 建 Release 会失败。
if ! (cd "$REPO_DIR" && git ls-remote --tags --exit-code origin "refs/tags/$TAG" >/dev/null 2>&1); then
    echo "警告：远端还没有 tag $TAG"
    echo "      先执行： git push origin $TAG"
fi

PAYLOAD=$(TAG="$TAG" REPO="$REPO" IS_PRE="$IS_PRE" python3 - "$RAW_BODY" <<'PY'
import json, os, sys
body = sys.argv[1]
print(json.dumps({
    "tag_name": os.environ["TAG"],
    # Release 标题直接用 tag 名；要改标题请传说明文件并在里面写，或事后在网页改。
    "name": os.environ["TAG"],
    "body": body,
    "draft": False,
    "prerelease": os.environ["IS_PRE"] == "True",
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
    # 只回显前 20 行，避免把整份响应糊满屏
    echo "$RESP" | head -20
    exit 1
fi
echo "  ok"

echo "=== 2. 上传 APK ==="
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
