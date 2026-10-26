#!/usr/bin/env bash
# Сборка PSYHOZ VPN (Android) на Fedora. Запуск:  ./build-apk.sh
# Результат: PsyhozVPN-<версия>.apk в этой папке. Нужен интернет (всё скачивается автоматически).
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$PWD"
TOOLS="$HOME/.psyhoz-android-build"          # кэш SDK, Gradle и ключа подписи
SDK="$TOOLS/sdk"
GRADLE_VER="8.7"
mkdir -p "$TOOLS"

say() { printf '\n\033[1;36m→ %s\033[0m\n' "$*"; }

# ---------- 1. Системные пакеты ----------
say "Устанавливаю JDK и утилиты (потребуется пароль sudo)"
sudo dnf install -y unzip curl
JDK_OK=0
for pkg in java-21-openjdk-devel java-17-openjdk-devel; do
  if sudo dnf install -y "$pkg"; then JDK_OK=1; break; fi
done
[ "$JDK_OK" = 1 ] || { echo "Не удалось установить JDK 21/17. Установи вручную: sudo dnf install java-21-openjdk-devel"; exit 1; }
# Gradle 8.7 работает с JDK 17–21 (более новая Java ему не подходит)
JAVA_HOME=""
for d in /usr/lib/jvm/java-21-openjdk* /usr/lib/jvm/java-17-openjdk*; do
  if [ -x "$d/bin/javac" ]; then JAVA_HOME="$d"; break; fi
done
[ -n "$JAVA_HOME" ] || { echo "Не найден JDK с javac в /usr/lib/jvm"; exit 1; }
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
echo "JAVA_HOME=$JAVA_HOME"
java -version

# ---------- 2. Android SDK ----------
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  say "Скачиваю Android command-line tools"
  curl -fL -o "$TOOLS/cmdline.zip" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  rm -rf "$TOOLS/cmdtmp" && mkdir -p "$TOOLS/cmdtmp" "$SDK/cmdline-tools"
  unzip -q "$TOOLS/cmdline.zip" -d "$TOOLS/cmdtmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$TOOLS/cmdtmp/cmdline-tools" "$SDK/cmdline-tools/latest"
fi
SDKM="$SDK/cmdline-tools/latest/bin/sdkmanager"
say "Принимаю лицензии и ставлю компоненты SDK (первый раз ~1–2 ГБ)"
set +o pipefail; yes | "$SDKM" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true; set -o pipefail
"$SDKM" --sdk_root="$SDK" "platform-tools" "platforms;android-34" "build-tools;34.0.0"
echo "sdk.dir=$SDK" > "$ROOT/local.properties"

# ---------- 3. Gradle ----------
GRADLE_HOME="$TOOLS/gradle-$GRADLE_VER"
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  say "Скачиваю Gradle $GRADLE_VER"
  curl -fL -o "$TOOLS/gradle.zip" "https://services.gradle.org/distributions/gradle-$GRADLE_VER-bin.zip"
  unzip -q "$TOOLS/gradle.zip" -d "$TOOLS"
fi

# ---------- 4. Xray и tun2socks (готовые сборки из релизов) ----------
say "Скачиваю Xray-core для Android"
for pair in "arm64-v8a:Xray-android-arm64-v8a" "x86_64:Xray-android-amd64"; do
  abi="${pair%%:*}"; asset="${pair##*:}"
  curl -fL -o "$TOOLS/$asset.zip" "https://github.com/XTLS/Xray-core/releases/latest/download/$asset.zip"
  rm -rf "$TOOLS/$asset" && mkdir -p "$TOOLS/$asset" && unzip -q -o "$TOOLS/$asset.zip" -d "$TOOLS/$asset"
  mkdir -p "$ROOT/app/src/main/jniLibs/$abi"
  # приложение запускает xray как исполняемый файл из папки нативных библиотек, поэтому имя libxray.so
  cp "$TOOLS/$asset/xray" "$ROOT/app/src/main/jniLibs/$abi/libxray.so"
done
say "Скачиваю tun2socks (hev-socks5-tunnel)"
mkdir -p "$ROOT/app/libs"
curl -fL -o "$ROOT/app/libs/hev-socks5-tunnel.aar" https://github.com/heiher/hev-socks5-tunnel/releases/latest/download/hev-socks5-tunnel.aar

# ---------- 5. Ключ подписи (создаётся один раз и хранится у тебя) ----------
KS="$TOOLS/psyhoz.keystore"; KSPASS_FILE="$TOOLS/keystore.pass"
if [ ! -f "$KS" ]; then
  say "Создаю ключ подписи APK"
  head -c 18 /dev/urandom | base64 | tr -d '/+=' > "$KSPASS_FILE"; chmod 600 "$KSPASS_FILE"
  keytool -genkeypair -keystore "$KS" -alias psyhoz -keyalg RSA -keysize 2048 -validity 36500 \
    -storepass "$(cat "$KSPASS_FILE")" -keypass "$(cat "$KSPASS_FILE")" \
    -dname "CN=PSYHOZ VPN, O=PSYHOZ, C=RU"
  echo "ВАЖНО: сохрани копию папки $TOOLS/ (файлы psyhoz.keystore и keystore.pass): только с этим ключом можно обновлять приложение у пользователей."
fi
export PSYHOZ_KS_FILE="$KS" PSYHOZ_KS_PASS="$(cat "$KSPASS_FILE")"

# ---------- 6. Сборка ----------
say "Собираю APK (первый раз 5–10 минут: Gradle качает зависимости)"
"$GRADLE_HOME/bin/gradle" --no-daemon --console=plain assembleRelease

VER="$(grep -o 'versionName = "[^"]*"' app/build.gradle.kts | cut -d'"' -f2)"
OUT="$ROOT/PsyhozVPN-$VER.apk"
cp app/build/outputs/apk/release/app-release.apk "$OUT"
say "Готово: $OUT"
ls -lh "$OUT"
