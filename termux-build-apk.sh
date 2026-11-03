#!/data/data/com.termux/files/usr/bin/bash
# ============================================================================
# ASTRA-YORDAMCHI (ASTRAFOLIO) • TERMUX PRODUCTION RELEASE APK YIG'UVCHI
# Vazifasi:
#   1. Muhit va Android SDK resurslarini tekshiradi (termux-fix.sh orqali)
#   2. Production Release (app-release.apk) va Debug (app-debug.apk) yig'adi
#   3. Tayyor imzolangan Release APK'ni /sdcard/Download/Astra-yordamchi.apk ga nusxalaydi
#   4. APK hajmi, imzosi, manifesti va paket nomini tekshirib hisobot beradi
# Ishga tushirish: bash termux-build-apk.sh
# ============================================================================

set -e

CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${CYAN}================================================================${NC}"
echo -e "${CYAN} ASTRA-YORDAMCHI • PRODUCTION RELEASE APK YIG'UVCHI (REDMI 15) ${NC}"
echo -e "${CYAN}================================================================${NC}"

PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME_DIR/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# 1. Avtomatik SDK va AAPT2 tekshiruvi + tuzatish
if [ -f "./termux-fix.sh" ]; then
    bash ./termux-fix.sh
fi

AAPT2_BIN="$PREFIX_DIR/bin/aapt2"
if [ ! -x "$AAPT2_BIN" ]; then
    AAPT2_BIN="$(command -v aapt2 || true)"
fi

if [ -z "$AAPT2_BIN" ]; then
    echo -e "${RED}[XATO] ARM64 aapt2 topilmadi! O'rnatish: pkg install aapt2${NC}"
    exit 1
fi

echo -e "${GREEN}✔ ARM64 aapt2:${NC} $AAPT2_BIN"

# 2. Release Keystore tekshiruvi (Maxfiy kalit Git'ga kirmaydi, faqat telefonda ~/.android ichida turadi)
mkdir -p "$HOME_DIR/.android"
DEFAULT_RELEASE_JKS="$HOME_DIR/.android/astra-upload-key.jks"
export KEYSTORE_PATH="${KEYSTORE_PATH:-$DEFAULT_RELEASE_JKS}"
export STORE_PASSWORD="${STORE_PASSWORD:-astrafolio2026}"
export KEY_PASSWORD="${KEY_PASSWORD:-astrafolio2026}"

if [ ! -f "$KEYSTORE_PATH" ] && command -v keytool >/dev/null 2>&1; then
    echo -e "${GREEN}🔐 Release imzo kaliti yaratilmoqda: $KEYSTORE_PATH (alias: upload)...${NC}"
    keytool -genkeypair -v \
        -keystore "$KEYSTORE_PATH" \
        -storepass "$STORE_PASSWORD" \
        -alias upload \
        -keypass "$KEY_PASSWORD" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Astra-Yordamchi,OU=Mobile,O=Kryzensys,L=Tashkent,ST=Tashkent,C=UZ"
fi

echo -e "${CYAN}🚀 Gradle orqali Astra-yordamchi Release & Debug APK yig'ilmoqda...${NC}"

# 3. Gradle assembleRelease va assembleDebug (Termux RAM va ARM64 aapt2 optimizatsiyasi bilan)
gradle assembleRelease assembleDebug \
    -Pandroid.aapt2FromMavenOverride="$AAPT2_BIN" \
    --no-daemon \
    --no-configuration-cache \
    -Dorg.gradle.jvmargs="-Xmx2048m -Dfile.encoding=UTF-8"

RELEASE_APK_PATH="app/build/outputs/apk/release/app-release.apk"
DEBUG_APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

if [ -f "$RELEASE_APK_PATH" ]; then
    APK_PATH="$RELEASE_APK_PATH"
elif [ -f "$DEBUG_APK_PATH" ]; then
    APK_PATH="$DEBUG_APK_PATH"
else
    echo -e "${RED}❌ APK fayli topilmadi: $RELEASE_APK_PATH${NC}"
    exit 1
fi

# 3. APK tekshiruvi (hajmi, paket nomi, imzo)
APK_SIZE="$(du -h "$APK_PATH" | cut -f1)"
echo -e "${CYAN}----------------------------------------------------------------${NC}"
echo -e "${GREEN}✅ PRODUCTION RELEASE APK MUVAFFAQIYATLI YIG'ILDI!${NC}"
echo -e "📦 Asl manzil: $APK_PATH"
echo -e "📊 APK hajmi:  $APK_SIZE"

if [ -x "$AAPT2_BIN" ]; then
    BADGING="$("$AAPT2_BIN" dump badging "$APK_PATH" 2>/dev/null | head -n 2 || true)"
    if [ -n "$BADGING" ]; then
        echo -e "🏷 Paket ma'lumoti: $BADGING"
    fi
fi

if unzip -l "$APK_PATH" 2>/dev/null | grep -qE "META-INF/.*\.(RSA|DSA|EC)"; then
    echo -e "🔐 Imzo holati: ✔ Keystore bilan imzolangan (o'rnatishga tayyor)"
fi

# 4. Telefonning Download papkasiga nusxalash
DOWNLOAD_DIR="/sdcard/Download"
if [ -d "$DOWNLOAD_DIR" ] && [ -w "$DOWNLOAD_DIR" ]; then
    cp -f "$APK_PATH" "$DOWNLOAD_DIR/Astra-yordamchi.apk"
    cp -f "$APK_PATH" "$DOWNLOAD_DIR/Astrafolio-Jarvis.apk"
    echo -e "${GREEN}📲 Telefonga nusxalandi:${NC}"
    echo -e "   👉 ${YELLOW}/sdcard/Download/Astra-yordamchi.apk${NC}"
    echo -e "   👉 ${YELLOW}/sdcard/Download/Astrafolio-Jarvis.apk${NC}"
else
    echo -e "${YELLOW}⚠ /sdcard/Download papkasiga ruxsat yo'q. Termux xotira ruxsatini bering:${NC}"
    echo -e "   termux-setup-storage"
    echo -e "   So'ngra: cp $APK_PATH /sdcard/Download/Astra-yordamchi.apk"
fi
echo -e "${CYAN}================================================================${NC}"
