# 📱 ASTRA-YORDAMCHI (ASTRAFOLIO) • REDMI 15 TERMUX ARM64 PRODUCTION APK QO'LLANMASI

Ushbu loyiha **100% Production darajasiga o'tkazilgan** va **Firebase'dan to'liq tozalangan** bo'lib, barcha ma'lumotlar **Room SQLite v2** lokal bazasida, **SharedPreferences** xotirasida, **Lokal HTTP Server (Port 8080)** va **To'g'ridan-to'g'ri Gemini REST / Avtonom Jarvis Dvigateli** orqali mustaqil ishlaydi.

---

## 🚀 1. Redmi 15 (Termux) da Production Release APK Yig'ish

Loyiha ildizida Redmi 15 (`aarch64` / ARM64, OpenJDK 21, Gradle 9.6.0) uchun maxsus 3 ta avtomatlashtirilgan skript mavjud:

1. **`termux-check.sh`** — Termux muhiti, OpenJDK 21, Gradle, `$ANDROID_HOME`, ARM64 `aapt2`, `android.jar` resurslari va Firebase yo'qligini tekshiradi.
2. **`termux-fix.sh`** — `android.jar` fayllarining `.original.bak` zaxira nusxasini oladi, `android-36.1` / `android-36` ichidagi `FLAG_COMPACT` (`0x08`) resurs jadvalini Termux ARM64 `aapt2` uchun xavfsiz ochib beradi va keystore mavjudligini ta'minlaydi.
3. **`termux-build-apk.sh`** — Avtomatik `termux-fix.sh` ni ishga tushiradi, `gradle assembleRelease assembleDebug` orqali **Production Release APK (`app/build/outputs/apk/release/app-release.apk`)** yig'adi, imzosini tekshiradi va tayyor APK'ni `/sdcard/Download/Astra-yordamchi.apk` manziliga nusxalaydi.

### Termux uchun ketma-ket buyruqlar:

```bash
cd ~/Astra-yordamchi- || cd ~/astrafolio
git pull origin main
chmod +x termux-check.sh termux-fix.sh termux-build-apk.sh
bash termux-check.sh
bash termux-fix.sh
bash termux-build-apk.sh
```

Yoki to'g'ridan-to'g'ri Gradle orqali:

```bash
gradle assembleRelease -Pandroid.aapt2FromMavenOverride=$PREFIX/bin/aapt2 --no-daemon --no-configuration-cache
cp app/build/outputs/apk/release/app-release.apk /sdcard/Download/Astra-yordamchi.apk
```

Tayyor Release APK telefonning **Download** papkasiga tushadi:
- `/sdcard/Download/Astra-yordamchi.apk`
- `/sdcard/Download/Astrafolio-Jarvis.apk`

---

## 🔐 2. Shaxsiy Release Keystore Yaratish (Maxfiy parollarni Git'ga qo'shmasdan)

Agar o'zingizning shaxsiy `.jks` kalitingiz bilan imzolamoqchi bo'lsangiz, Termux'da quyidagi buyruq orqali `.gitignore` ga kiritilgan shaxsiy keystore yarating:

```bash
keytool -genkeypair -v \
  -keystore ~/astra-release-key.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias astra_alias
```

> **Xavfsizlik qoidasi:** `*.jks` va `*.keystore` fayllari hech qachon Git repozitoriyga yuklanmasligi kerak (`~/astra-release-key.jks` telefonning uy katalogida xavfsiz saqlanadi).
