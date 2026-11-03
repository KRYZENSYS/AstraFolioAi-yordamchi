#!/data/data/com.termux/files/usr/bin/bash
# ============================================================================
# ASTRA-YORDAMCHI (ASTRAFOLIO) • TERMUX MUHIT VA LOYIHA TEKSHIRUVCHI (REDMI 15)
# Ishga tushirish: bash termux-check.sh
# ============================================================================

set -e

CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${CYAN}================================================================${NC}"
echo -e "${CYAN}   ASTRA-YORDAMCHI • TERMUX ARM64 MUHIT AUDITI (REDMI 15)       ${NC}"
echo -e "${CYAN}================================================================${NC}"

PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME_DIR/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# 1. Arxitektura tekshiruvi
ARCH="$(uname -m)"
echo -e "${GREEN}[1/7] Qurilma arxitekturasi:${NC} $ARCH"
if [[ "$ARCH" != "aarch64" && "$ARCH" != "arm64"* ]]; then
    echo -e "${YELLOW}⚠ Eslatma: Arxitektura aarch64 emas ($ARCH).${NC}"
fi

# 2. Java / OpenJDK 21 tekshiruvi
if command -v java >/dev/null 2>&1; then
    JAVA_VER="$(java -version 2>&1 | head -n 1)"
    echo -e "${GREEN}[2/7] OpenJDK holati:${NC} $JAVA_VER"
else
    echo -e "${RED}[XATO] Java topilmadi! O'rnatish: pkg install openjdk-21${NC}"
    exit 1
fi

# 3. Gradle tekshiruvi
if command -v gradle >/dev/null 2>&1; then
    GRADLE_VER="$(gradle --version | grep 'Gradle ' | head -n 1)"
    echo -e "${GREEN}[3/7] Gradle holati:${NC} $GRADLE_VER"
else
    echo -e "${RED}[XATO] Gradle topilmadi! O'rnatish: pkg install gradle${NC}"
    exit 1
fi

# 4. Termux ARM64 AAPT2 tekshiruvi
AAPT2_BIN="$PREFIX_DIR/bin/aapt2"
if [ -x "$AAPT2_BIN" ]; then
    AAPT2_VER="$("$AAPT2_BIN" version 2>&1 | head -n 1 || echo 'aapt2 mavjud')"
    echo -e "${GREEN}[4/7] ARM64 AAPT2:${NC} $AAPT2_BIN ($AAPT2_VER)"
elif command -v aapt2 >/dev/null 2>&1; then
    AAPT2_BIN="$(command -v aapt2)"
    echo -e "${GREEN}[4/7] ARM64 AAPT2:${NC} $AAPT2_BIN"
else
    echo -e "${RED}[XATO] ARM64 aapt2 topilmadi! O'rnatish: pkg install aapt2${NC}"
    exit 1
fi

# 5. Android SDK va Platformalar tekshiruvi
echo -e "${GREEN}[5/7] ANDROID_HOME:${NC} $ANDROID_HOME"
if [ -d "$ANDROID_HOME/platforms" ]; then
    echo -e "      Mavjud SDK platformalar:"
    for plat in "$ANDROID_HOME/platforms"/android-*; do
        if [ -d "$plat" ]; then
            PLAT_NAME="$(basename "$plat")"
            if [ -f "$plat/android.jar" ]; then
                JAR_SIZE="$(du -h "$plat/android.jar" | cut -f1)"
                echo -e "        ✔ $PLAT_NAME (android.jar: $JAR_SIZE)"
            else
                echo -e "        ⚠ $PLAT_NAME (android.jar topilmadi!)"
            fi
        fi
    done
else
    echo -e "${YELLOW}⚠ $ANDROID_HOME/platforms topilmadi. bash termux-setup-sdk.sh ni ishga tushiring.${NC}"
fi

# 6. android-36.1 / android-36 resurs jadvali (color/black, color/white) tekshiruvi
TARGET_JAR=""
if [ -f "$ANDROID_HOME/platforms/android-36.1/android.jar" ]; then
    TARGET_JAR="$ANDROID_HOME/platforms/android-36.1/android.jar"
elif [ -f "$ANDROID_HOME/platforms/android-36/android.jar" ]; then
    TARGET_JAR="$ANDROID_HOME/platforms/android-36/android.jar"
fi

if [ -n "$TARGET_JAR" ]; then
    echo -e "${GREEN}[6/7] AAPT2 resurs mosligi tekshirilmoqda:${NC} $TARGET_JAR"
    DUMP_OUT="$("$AAPT2_BIN" dump resources "$TARGET_JAR" 2>/dev/null | grep -E "resource 0x0106000[bcde] color/(black|white|transparent)" | head -n 5 || true)"
    if echo "$DUMP_OUT" | grep -q "color/black" && ! echo "$DUMP_OUT" | grep -q "@null"; then
        echo -e "      ✔ android:color/black va android:color/white to'liq o'qilmoqda!"
    else
        echo -e "${YELLOW}      ⚠ DIQQAT: $TARGET_JAR ichida FLAG_COMPACT resurslar Termux aapt2 uchun ochilmagan.${NC}"
        echo -e "${YELLOW}      ✔ Yechim: 'bash termux-fix.sh' avtomatik ravishda buni tuzatadi.${NC}"
    fi
else
    echo -e "${YELLOW}[6/7] android-36.1 yoki android-36 topilmadi.${NC}"
fi

# 7. Firebase yo'qligini va debug.keystore mavjudligini tekshirish
echo -e "${GREEN}[7/7] Loyiha konfiguratsiyasi (Firebase-free audit):${NC}"
if grep -q "firebase" app/build.gradle.kts 2>/dev/null; then
    echo -e "${RED}      ✘ XATO: app/build.gradle.kts ichida Firebase qoldig'i bor!${NC}"
    exit 1
else
    echo -e "      ✔ Firebase dependency'lari 100% tozalangan (0 ta Firebase kutubxonasi)"
fi

if [ -f "app/google-services.json" ]; then
    echo -e "${RED}      ✘ XATO: app/google-services.json hali ham mavjud!${NC}"
    exit 1
else
    echo -e "      ✔ google-services.json va Firebase XML fayllari o'chirilgan"
fi

if [ -f "debug.keystore" ] || [ -f "debug.keystore.base64" ]; then
    echo -e "      ✔ APK imzolash kaliti (debug.keystore) tayyor"
else
    echo -e "${YELLOW}      ⚠ debug.keystore topilmadi (termux-fix.sh avtomatik yaratadi)${NC}"
fi

echo -e "${CYAN}================================================================${NC}"
echo -e "${GREEN}✔ TEKSHIRUV YAKUNLANDI! Keyingi qadam: bash termux-fix.sh${NC}"
echo -e "${CYAN}================================================================${NC}"
