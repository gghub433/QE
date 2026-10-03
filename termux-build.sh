#!/data/data/com.termux/files/usr/bin/bash
# Сборка EQ прямо на телефоне в Termux (без Gradle и Android Studio).
# Запуск:  bash termux-build.sh
set -e
cd "$(dirname "$0")"

# Версия — та же, что в app/build.gradle (поднимать в обоих местах)
VERSION_CODE=40
VERSION_NAME=8.1

SDK="$HOME/android-sdk"
JAR="$SDK/android-36.jar"
KS="$HOME/.budseq-debug.keystore"
B="build-termux"
OUT="EQ.apk"

echo "=== 1/7 Пакеты ==="
missing=""
for t in aapt2 apksigner dx zip unzip javac keytool; do
    command -v "$t" >/dev/null 2>&1 || missing="$missing $t"
done
if [ -n "$missing" ]; then
    echo "Не хватает:$missing — устанавливаю"
    pkg upgrade -y
    pkg install -y openjdk-17 aapt aapt2 apksigner dx zip unzip curl
else
    echo "Всё уже установлено"
fi
pkg install -y zipalign >/dev/null 2>&1 || true   # необязательно

echo "=== 2/7 android.jar API 36 (Android 16) (один раз, ~26 МБ) ==="
if [ ! -f "$JAR" ]; then
    command -v git >/dev/null 2>&1 || pkg install -y git
    rm -rf "$SDK/ap" && mkdir -p "$SDK"
    git clone --depth 1 --filter=blob:none --sparse https://github.com/Sable/android-platforms.git "$SDK/ap"
    (cd "$SDK/ap" && git sparse-checkout set android-36)
    cp "$SDK/ap/android-36/android.jar" "$JAR"
    rm -rf "$SDK/ap"
fi

echo "=== 3/7 Ключ подписи (один раз) ==="
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias debug -keyalg RSA -keysize 2048 \
        -validity 10000 -storepass android -keypass android \
        -dname "CN=EQ, O=Debug, C=LV"
fi

rm -rf "$B" && mkdir -p "$B/classes" "$B/gen"

echo "=== 4/7 Ресурсы (aapt2) ==="
sed -e 's|<manifest xmlns|<manifest package="lv.budseq" xmlns|' \
    app/src/main/AndroidManifest.xml > "$B/AndroidManifest.xml"
aapt2 compile --dir app/src/main/res -o "$B/res.zip"
aapt2 link -o "$B/base.apk" -I "$JAR" --manifest "$B/AndroidManifest.xml" \
    --min-sdk-version 28 --target-sdk-version 36 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" --java "$B/gen" "$B/res.zip"

echo "=== 5/7 Компиляция Java ==="
javac -source 8 -target 8 -nowarn -Xlint:-options -encoding UTF-8 -bootclasspath "$JAR" \
    -d "$B/classes" $(find app/src/main/java "$B/gen" -name '*.java')

echo "=== 6/7 DEX ==="
dx --dex --min-sdk-version=26 --output="$B/classes.dex" "$B/classes"

echo "=== 7/7 Упаковка и подпись ==="
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B" && zip -q unsigned.apk classes.dex)
if command -v zipalign >/dev/null 2>&1; then
    zipalign -f 4 "$B/unsigned.apk" "$B/aligned.apk"
else
    cp "$B/unsigned.apk" "$B/aligned.apk"
fi
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT" "$B/aligned.apk"

echo ""
echo "Готово: $(pwd)/$OUT"
if [ -d "$HOME/storage/downloads" ]; then
    cp "$OUT" "$HOME/storage/downloads/$OUT"
    echo "Скопировано в Загрузки (Download/$OUT) — открой его в файловом менеджере и установи."
else
    echo "Чтобы скопировать в Загрузки: termux-setup-storage, потом снова запусти скрипт."
fi
