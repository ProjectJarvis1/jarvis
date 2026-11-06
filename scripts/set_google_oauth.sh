#!/usr/bin/env bash
# 구글 캘린더 연결용 OAuth 클라이언트 ID·비밀번호를 운영 서버(맥미니) .env 에 넣고 서버를 다시 켠다.
# 값은 화면에 보이지 않게 입력받아 ssh 표준입력으로만 보낸다(명령줄 인자·로그에 남지 않음).
set -euo pipefail

SERVER_DIR='~/jarvis/JarvisMemoryServer/jarvis-server'
REDIRECT='https://api.projectjarvis.biz/integrations/google/callback'

printf '구글 클라이언트 ID: '
IFS= read -r CID
printf '구글 클라이언트 보안 비밀번호 (입력은 보이지 않음): '
IFS= read -rs SECRET; echo
CID="$(printf '%s' "$CID" | tr -d '[:space:]')"
SECRET="$(printf '%s' "$SECRET" | tr -d '[:space:]')"
[ -n "$CID" ] && [ -n "$SECRET" ] || { echo "값이 비어 있어 멈춥니다."; exit 1; }
case "$CID" in *.apps.googleusercontent.com) ;; *) echo "클라이언트 ID 모양이 아닙니다 (…apps.googleusercontent.com)."; exit 1;; esac

printf '%s\n%s\n%s\n' "$CID" "$SECRET" "$REDIRECT" | ssh macmini "cd $SERVER_DIR && /usr/bin/python3 -c '
import os, sys, time, shutil
cid, secret, redirect = (sys.stdin.readline().strip() for _ in range(3))
ts = time.strftime(\"%Y%m%d-%H%M%S\")
shutil.copy2(\".env\", \".env.bak.\" + ts); os.chmod(\".env.bak.\" + ts, 0o600)
want = {\"GOOGLE_CLIENT_ID\": cid, \"GOOGLE_CLIENT_SECRET\": secret, \"GOOGLE_REDIRECT_URI\": redirect}
lines = [l for l in open(\".env\").read().splitlines() if l.split(\"=\", 1)[0] not in want]
lines += [k + \"=\" + v for k, v in want.items()]
open(\".env\", \"w\").write(\"\\n\".join(lines) + \"\\n\"); os.chmod(\".env\", 0o600)
print(\"서버 .env 에 넣었습니다 (백업 .env.bak.\" + ts + \").\")
' && launchctl kickstart -k gui/\$(id -u)/com.jarvis.api && for i in \$(seq 1 45); do curl -s -m 2 127.0.0.1:8000/health >/dev/null && break; sleep 2; done && K=\$(grep ^API_KEY= .env | cut -d= -f2-) && curl -s -H \"X-API-Key: \$K\" 127.0.0.1:8000/integrations/status | /usr/bin/python3 -c 'import sys,json; g=json.load(sys.stdin)[\"services\"][\"google\"]; print(\"확인: 앱의 구글 캘린더 연결 버튼이 켜집니다.\" if g[\"ready\"] else \"주의: 서버가 아직 준비되지 않았다고 합니다.\")'"
unset CID SECRET
