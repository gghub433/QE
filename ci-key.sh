#!/data/data/com.termux/files/usr/bin/bash
# Обновления EQ прямо из GitHub, без Termux: один раз положить ключ подписи в секреты репозитория.
# Скрипт ничего никуда не отправляет и в репозиторий ключ не кладёт — только копирует его в буфер обмена
# (или печатает), а вставляете вы сами в настройках репозитория на GitHub.
# Ключ никому не присылайте — с ним можно выпустить «обновление» EQ от вашего имени.
set -e
KS="$HOME/.budseq-debug.keystore"
if [ ! -f "$KS" ]; then
    echo "Нет ключа $KS — сначала соберите EQ в Termux: bash termux-build.sh"
    exit 1
fi
B64=$(base64 -w0 "$KS")
echo "Откройте на GitHub: репозиторий → Settings → Secrets and variables → Actions → New repository secret"
echo "и добавьте 4 секрета:"
echo "  KEYSTORE_BASE64   — длинная строка (она уже в буфере обмена, если есть Termux:API)"
echo "  KEYSTORE_PASSWORD — android"
echo "  KEY_ALIAS         — debug"
echo "  KEY_PASSWORD      — android"
echo
if command -v termux-clipboard-set >/dev/null 2>&1; then
    printf '%s' "$B64" | termux-clipboard-set
    echo "KEYSTORE_BASE64 скопирован в буфер обмена — вставьте его в первый секрет."
else
    echo "Termux:API нет — скопируйте строку ниже целиком (одной строкой) в секрет KEYSTORE_BASE64:"
    echo
    echo "$B64"
fi
echo
echo "Готово? Дальше каждая новая версия EQ на GitHub сама станет релизом и придёт в приложение обновлением."
