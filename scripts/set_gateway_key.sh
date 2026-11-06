#!/usr/bin/env bash
# 마인드로직 API 게이트웨이 키를 앱 Secrets.kt 에 넣고, gemini-3.8-live 를 쓸 수 있는지 확인한다.
# 키는 화면에 보이지 않게 입력받고, 명령줄 인자나 로그에 남기지 않는다.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$ROOT/frontend/samples/CameraAccessAndroid/app/src/main/java/com/meta/wearable/dat/externalsampleapps/cameraaccess"
SECRETS="$DIR/Secrets.kt"

[ -f "$SECRETS" ] || cp "$DIR/Secrets.kt.example" "$SECRETS"

printf '마인드로직 게이트웨이 키를 붙여 넣고 Enter (입력은 보이지 않음): '
IFS= read -rs KEY; echo
KEY="$(printf '%s' "$KEY" | tr -d '[:space:]')"
[ -n "$KEY" ] || { echo "키가 비어 있어 멈춥니다."; exit 1; }

GATEWAY_KEY="$KEY" python3 -I - "$SECRETS" <<'PY'
import os, re, sys, json, urllib.request, urllib.error
path, key = sys.argv[1], os.environ["GATEWAY_KEY"]
text = open(path, encoding="utf-8").read()
new, n = re.subn(r'const val geminiAPIKey = ".*?"', lambda m: f'const val geminiAPIKey = {json.dumps(key)}', text)
if n != 1:
    sys.exit("Secrets.kt 에서 geminiAPIKey 줄을 찾지 못했습니다.")
open(path, "w", encoding="utf-8").write(new)
os.chmod(path, 0o600)
print(f"Secrets.kt 에 저장했습니다 (끝 4자리 …{key[-4:]}).")

req = urllib.request.Request("https://factchat-cloud.mindlogic.ai/v1/gateway/models/?type=realtime",
                             headers={"x-api-key": key, "Accept": "application/json"})
try:
    body = urllib.request.urlopen(req, timeout=15).read().decode()
except urllib.error.HTTPError as e:
    sys.exit(f"키 확인 실패: 게이트웨이 응답 {e.code}. 키를 다시 확인해 주세요.")
if "gemini-3.8-live" in body:
    print("확인: 이 키로 gemini-3.8-live 를 쓸 수 있습니다.")
else:
    print("주의: 키는 맞지만 실시간 모델 목록에 gemini-3.8-live 가 없습니다. 조직 설정에서 켜야 합니다.")
PY
unset KEY
echo "앱을 다시 빌드해 설치하면 적용됩니다. 이미 설치된 앱은 설정 > 고급 설정 > Gemini 키 (마인드로직)에 넣어도 됩니다."
