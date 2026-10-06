#!/usr/bin/env bash
# cron/launchd에서 호출하는 실행 스크립트.
# .env에 SARAMIN_ACCESS_KEY, CLAUDE_CODE_OAUTH_TOKEN, (선택) SLACK_WEBHOOK_URL을 넣어두세요.
set -euo pipefail
cd "$(dirname "$0")"

if [ -f .env ]; then
  set -a; . ./.env; set +a
fi

# cron은 PATH가 짧고 LANG이 비어 있어서 claude/java를 못 찾거나 한글이 깨지는 경우가 많다
export PATH="$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"
export LANG="${LANG:-en_US.UTF-8}"

BIN=build/install/job-hunter/bin/job-hunter
if [ ! -x "$BIN" ]; then
  ./gradlew -q installDist
fi

mkdir -p logs
"$BIN" "$@" 2>&1 | tee -a "logs/$(date +%Y-%m-%d).log"
