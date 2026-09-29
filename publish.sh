#!/data/data/com.termux/files/usr/bin/bash
# Выложить новую версию EQ в GitHub Releases — телефоны получат автообновление.
#
#   bash publish.sh          собрать APK в Termux (termux-build.sh) и выложить его в релиз vX.Y
#   bash publish.sh --tag    только поставить тег vX.Y и отправить его: APK соберёт GitHub Actions
#                            (нужны секреты с ключом подписи, см. README)
#
# Версия берётся из app/build.gradle (versionName). Перед публикацией подними её.
# Токен GitHub: переменная GITHUB_TOKEN или файл ~/.eq-github-token
# (github.com → Settings → Developer settings → Fine-grained tokens, доступ к репозиторию: Contents — Read and write).
set -e
cd "$(dirname "$0")"

REPO="${EQ_REPO:-gghub433/QE}"
VER=$(sed -n "s/.*versionName *'\([^']*\)'.*/\1/p" app/build.gradle | head -1)
TAG="v$VER"
[ -n "$VER" ] || { echo "Не нашёл versionName в app/build.gradle"; exit 1; }
echo "=== EQ $VER → $REPO ($TAG) ==="

if [ "$1" = "--tag" ]; then
    git tag -a "$TAG" -m "EQ $VER"
    git push origin "$TAG"
    echo "Тег $TAG отправлен — сборка и релиз появятся во вкладке Actions через 3–5 минут."
    exit 0
fi

TOKEN="${GITHUB_TOKEN:-$(cat "$HOME/.eq-github-token" 2>/dev/null || true)}"
if [ -z "$TOKEN" ]; then
    echo "Нет токена. Создай его на github.com и сохрани:  echo ТОКЕН > ~/.eq-github-token"
    exit 1
fi
command -v curl >/dev/null 2>&1 || pkg install -y curl

if [ "$1" != "--no-build" ]; then
    bash termux-build.sh
fi
[ -f EQ.apk ] || { echo "Нет EQ.apk — сборка не удалась"; exit 1; }

API="https://api.github.com/repos/$REPO"
AUTH="Authorization: Bearer $TOKEN"
ACCEPT="Accept: application/vnd.github+json"

# Описание релиза — раздел «### X.Y» из README.md
NOTES=$(awk -v v="### $VER" '$0==v{f=1;next} f&&/^##/{exit} f' README.md)
[ -n "$NOTES" ] || NOTES="EQ $VER"
json_escape() {
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/\t/\\t/g' | awk 'NR>1{printf "\\n"} {printf "%s", $0}'
}

# id релиза — первый ключ "id" в ответе (до "author"); ответ может прийти одной строкой
json_id() {
    printf '%s' "$1" | grep -o '"id": *[0-9]\+' | head -1 | grep -o '[0-9]\+$' || true
}

echo "=== Релиз $TAG ==="
RESP=$(curl -sS -H "$AUTH" -H "$ACCEPT" "$API/releases/tags/$TAG")
ID=$(json_id "$RESP")
if [ -z "$ID" ]; then
    BODY="{\"tag_name\":\"$TAG\",\"name\":\"EQ $VER\",\"body\":\"$(json_escape "$NOTES")\"}"
    RESP=$(curl -sS -X POST -H "$AUTH" -H "$ACCEPT" -d "$BODY" "$API/releases")
    ID=$(json_id "$RESP")
fi
if [ -z "$ID" ]; then
    echo "Не удалось создать релиз. Ответ GitHub:"
    printf '%s\n' "$RESP" | head -20
    exit 1
fi

echo "=== Загрузка EQ-$VER.apk ==="
OUT_JSON="${TMPDIR:-/tmp}/eq-upload.json"
CODE=$(curl -sS -o "$OUT_JSON" -w '%{http_code}' -X POST -H "$AUTH" -H "$ACCEPT" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary @EQ.apk "https://uploads.github.com/repos/$REPO/releases/$ID/assets?name=EQ-$VER.apk")
case "$CODE" in
    201) echo "Готово: https://github.com/$REPO/releases/tag/$TAG" ;;
    422) echo "В релизе уже есть EQ-$VER.apk. Подними versionName в app/build.gradle и termux-build.sh или удали файл в релизе." ;;
    *)   echo "Ошибка загрузки (HTTP $CODE):"; head -20 "$OUT_JSON"; exit 1 ;;
esac
