#!/usr/bin/env bash
# ============================================================
#  MyDungeon 一键提交 + 推送脚本
#
#  用法：  ./push.sh "本次改动说明"
#
#  说明：
#   - 凭据从 Windows 凭据管理器实时读取，不会写进仓库或 .git/config
#   - 推送后用「本地 SHA == 远程 SHA」判定成败（exit code 在
#     沙箱/代理环境下不可靠，不能作为依据）
# ============================================================
set -e
cd "$(dirname "$0")"

MSG="${1:-chore: 例行更新}"
HOST_PATH="github.com/qzrsa/MyDungeon"

git add -A
if git diff --cached --quiet; then
  echo "[push.sh] 无改动需要提交，直接推送现有提交"
else
  # 经 stdin 传给 git，避免 GBK 控制台对中文 -m 参数的转码问题
  printf '%s\n' "$MSG" | git commit -F -
fi

TOKEN=$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill | sed -n 's/^password=//p')
if [ -z "$TOKEN" ]; then
  echo "[push.sh] 未能读取 GitHub 凭据。请先在任意仓库手动执行一次 git push 完成登录。" >&2
  exit 1
fi

git -c credential.helper= \
    -c "credential.https://github.com.helper=" \
    -c "credential.https://gist.github.com.helper=" \
    push "https://x-access-token:$TOKEN@$HOST_PATH.git" main

LOCAL=$(git rev-parse HEAD)
REMOTE=$(git ls-remote "https://x-access-token:$TOKEN@$HOST_PATH.git" refs/heads/main | cut -f1)
echo "[push.sh] 本地 SHA: $LOCAL"
echo "[push.sh] 远程 SHA: $REMOTE"

if [ "$LOCAL" = "$REMOTE" ]; then
  echo "[push.sh] [OK] 推送成功"
else
  echo "[push.sh] [FAIL] 远程 SHA 与本地不一致，请重试" >&2
  exit 1
fi
