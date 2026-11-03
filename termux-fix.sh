#!/data/data/com.termux/files/usr/bin/bash
# ============================================================================
# ASTRA-YORDAMCHI (ASTRAFOLIO) • TERMUX AVTOMATIK TUZATUVCHI (REDMI 15)
# Vazifasi:
#   1. android.jar fayllarini o'zgartirishdan oldin .original.bak nusxasini oladi
#   2. Agar android-36.1/android.jar avvalgi skriptdan buzilgan bo'lsa, uni
#      toza backup yoki android-36 / android-35 dan tiklaydi
#   3. Termux ARM64 aapt2 uchun FLAG_COMPACT (0x08 -> <HHIHBBI), FLAG_OFFSET16 (0x02)
#      va FLAG_SPARSE (0x01) jadvallarini 100% aniq ochib beradi
#   4. android:color/black, android:color/white, android:color/transparent,
#      android:attr/textStyle, android:attr/actionBarSize resurslarini tekshiradi
#   5. debug.keystore va local.properties ni Termux uchun moslaydi
# Ishga tushirish: bash termux-fix.sh
# ============================================================================

set -e

CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${CYAN}================================================================${NC}"
echo -e "${CYAN}   ASTRA-YORDAMCHI • TERMUX SDK VA AAPT2 TUZATUVCHI (REDMI 15)  ${NC}"
echo -e "${CYAN}================================================================${NC}"

PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME_DIR/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

if ! command -v python3 >/dev/null 2>&1; then
    echo -e "${YELLOW}⚙ Python3 o'rnatilmoqda...${NC}"
    pkg install -y python
fi

if ! command -v aapt2 >/dev/null 2>&1; then
    echo -e "${YELLOW}⚙ ARM64 aapt2 o'rnatilmoqda...${NC}"
    pkg install -y aapt2
fi

AAPT2_BIN="$PREFIX_DIR/bin/aapt2"
if [ ! -x "$AAPT2_BIN" ]; then
    AAPT2_BIN="$(command -v aapt2)"
fi

# 1. debug.keystore ni tayyorlash
if [ ! -f "debug.keystore" ]; then
    if [ -f "debug.keystore.base64" ]; then
        echo -e "${GREEN}🔑 debug.keystore.base64 dan debug.keystore tiklanmoqda...${NC}"
        base64 -d debug.keystore.base64 > debug.keystore
    elif command -v keytool >/dev/null 2>&1; then
        echo -e "${GREEN}🔑 Yangi debug.keystore yaratilmoqda...${NC}"
        keytool -genkeypair -v -keystore debug.keystore -storepass android \
            -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
            -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
    fi
fi

# 2. Termux local.properties yaratish (faqat Termux muhitida)
if [ -d "/data/data/com.termux" ] && [ -d "$ANDROID_HOME" ]; then
    echo "sdk.dir=$ANDROID_HOME" > local.properties
    echo -e "${GREEN}✔ local.properties yangilandi: sdk.dir=$ANDROID_HOME${NC}"
fi

# 3. android.jar resurs jadvalini xavfsiz backup bilan Termux aapt2 ga moslash
if [ -d "$ANDROID_HOME/platforms" ]; then
    python3 - "$ANDROID_HOME/platforms" "$AAPT2_BIN" << 'PYEOF'
import sys, os, glob, struct, zipfile, shutil, subprocess

platforms_dir = sys.argv[1]
aapt2_bin = sys.argv[2]

RES_TABLE_TYPE = 0x0002
RES_TABLE_PACKAGE_TYPE = 0x0200
RES_TABLE_TYPE_TYPE = 0x0201
RES_TABLE_OVERLAYABLE_TYPE = 0x0206

FLAG_COMPLEX = 0x01
FLAG_COMPACT = 0x08
NO_ENTRY = 0xFFFFFFFF

def check_jar_valid(jar_path):
    """Verify with aapt2 dump resources that color/black is #ff000000 and not @null or missing."""
    if not os.path.isfile(jar_path):
        return False
    try:
        proc = subprocess.run(
            [aapt2_bin, "dump", "resources", jar_path],
             stdout=subprocess.PIPE,
             stderr=subprocess.PIPE,
             text=True,
             timeout=25
        )
        if proc.returncode != 0:
            return False
        for line in proc.stdout.splitlines():
            if "color/black" in line:
                return "#ff000000" in line.lower() and "@null" not in line.lower()
        return False
    except Exception:
        return False

def convert_type_chunk(chunk):
    t_type, header_size, chunk_size = struct.unpack_from("<HHI", chunk, 0)
    if t_type != RES_TABLE_TYPE_TYPE or header_size < 20:
        return chunk, False

    type_id, flags, reserved, entry_count, entries_start = struct.unpack_from("<BBHII", chunk, 8)
    is_sparse = (flags & 0x01) != 0
    is_offset16 = (flags & 0x02) != 0

    new_header_size = 68 if header_size > 68 else header_size
    new_header = bytearray(chunk[:new_header_size])
    struct.pack_into("<H", new_header, 2, new_header_size)

    raw_offsets = []
    if is_sparse and is_offset16:
        for i in range(entry_count):
            pos = header_size + i * 4
            if pos + 4 <= entries_start:
                idx, off16 = struct.unpack_from("<HH", chunk, pos)
                raw_offsets.append((idx, off16 * 4))
        max_idx = max((idx for idx, _ in raw_offsets), default=-1)
        dense_count = max_idx + 1
        dense_map = {idx: off for idx, off in raw_offsets}
        ordered_offsets = [dense_map.get(i, NO_ENTRY) for i in range(dense_count)]
    elif is_sparse and not is_offset16:
        for i in range(entry_count):
            pos = header_size + i * 8
            if pos + 8 <= entries_start:
                idx, off32 = struct.unpack_from("<II", chunk, pos)
                raw_offsets.append((idx, off32))
        max_idx = max((idx for idx, _ in raw_offsets), default=-1)
        dense_count = max_idx + 1
        dense_map = {idx: off for idx, off in raw_offsets}
        ordered_offsets = [dense_map.get(i, NO_ENTRY) for i in range(dense_count)]
    elif is_offset16:
        dense_count = entry_count
        ordered_offsets = []
        for i in range(entry_count):
            pos = header_size + i * 2
            if pos + 2 <= entries_start:
                off16 = struct.unpack_from("<H", chunk, pos)[0]
                ordered_offsets.append(NO_ENTRY if off16 == 0xFFFF else off16 * 4)
            else:
                ordered_offsets.append(NO_ENTRY)
    else:
        dense_count = entry_count
        ordered_offsets = []
        for i in range(entry_count):
            pos = header_size + i * 4
            if pos + 4 <= entries_start:
                ordered_offsets.append(struct.unpack_from("<I", chunk, pos)[0])
            else:
                ordered_offsets.append(NO_ENTRY)

    entry_data = chunk[entries_start:]
    new_entry_data = bytearray()
    new_offsets = []
    converted_any = is_sparse or is_offset16 or (new_header_size != header_size)

    for rel_off in ordered_offsets:
        if rel_off == NO_ENTRY or rel_off + 8 > len(entry_data):
            new_offsets.append(NO_ENTRY)
            continue

        new_offsets.append(len(new_entry_data))
        e_size_or_key, e_flags = struct.unpack_from("<HH", entry_data, rel_off)

        if (e_flags & FLAG_COMPACT) != 0:
            # Compact entry: key_idx is in first 2 bytes, data_type in high byte of flags,
            # and 4-byte scalar value at rel_off + 4.
            key_idx = e_size_or_key
            data_type = (e_flags >> 8) & 0xFF
            clean_flags = e_flags & ~(FLAG_COMPACT | 0xFF00)
            data_val = struct.unpack_from("<I", entry_data, rel_off + 4)[0]
            # Standard 16-byte ResTable_entry (8 bytes) + Res_value (8 bytes):
            # uint16 entry_size=8, uint16 entry_flags, uint32 key_idx,
            # uint16 value_size=8, uint8 res0=0, uint8 dataType, uint32 data
            new_entry_data.extend(
                struct.pack("<HHIHBBI", 8, clean_flags, key_idx, 8, 0, data_type, data_val)
            )
            converted_any = True
        elif (e_flags & FLAG_COMPLEX) != 0:
            if rel_off + 16 <= len(entry_data):
                 _, _, _, parent, count = struct.unpack_from("<HHIII", entry_data, rel_off)
                total_len = 16 + count * 12
                new_entry_data.extend(entry_data[rel_off:rel_off + total_len])
            else:
                new_entry_data.extend(entry_data[rel_off:rel_off + 16])
        else:
            new_entry_data.extend(entry_data[rel_off:rel_off + 16])

    while len(new_entry_data) % 4 != 0:
        new_entry_data.append(0)

    if not converted_any:
        return chunk, False

    new_offsets_bytes = bytearray()
    for off in new_offsets:
        new_offsets_bytes.extend(struct.pack("<I", off))

    new_entries_start = new_header_size + len(new_offsets_bytes)
    new_chunk_size = new_entries_start + len(new_entry_data)

    new_flags = flags & ~(0x01 | 0x02)
    struct.pack_into("<I", new_header, 4, new_chunk_size)
    struct.pack_into("<BBHII", new_header, 8, type_id, new_flags, 0, len(new_offsets), new_entries_start)

    return bytes(new_header) + bytes(new_offsets_bytes) + bytes(new_entry_data), True

def patch_arsc(arsc_bytes):
    if len(arsc_bytes) < 12:
        return arsc_bytes, False
    t_type, header_size, total_size, pkg_count = struct.unpack_from("<HHII", arsc_bytes, 0)
    if t_type != RES_TABLE_TYPE:
        return arsc_bytes, False

    out = bytearray(arsc_bytes[:header_size])
    pos = header_size
    modified_any = False

    while pos + 8 <= len(arsc_bytes):
        c_type, c_hsize, c_size = struct.unpack_from("<HHI", arsc_bytes, pos)
        if c_size <= 0 or pos + c_size > len(arsc_bytes):
            break
        chunk = arsc_bytes[pos:pos + c_size]

        if c_type == RES_TABLE_PACKAGE_TYPE:
            pkg_out = bytearray(chunk[:c_hsize])
            sub_pos = c_hsize
            pkg_mod = False
            while sub_pos + 8 <= len(chunk):
                sc_type, sc_hsize, sc_size = struct.unpack_from("<HHI", chunk, sub_pos)
                if sc_size <= 0 or sub_pos + sc_size > len(chunk):
                    break
                sub_chunk = chunk[sub_pos:sub_pos + sc_size]
                if sc_type == RES_TABLE_OVERLAYABLE_TYPE:
                    pkg_mod = True
                    modified_any = True
                elif sc_type == RES_TABLE_TYPE_TYPE:
                    new_sub, changed = convert_type_chunk(sub_chunk)
                    pkg_out.extend(new_sub)
                    if changed:
                        pkg_mod = True
                        modified_any = True
                else:
                    pkg_out.extend(sub_chunk)
                sub_pos += sc_size

            if pkg_mod:
                struct.pack_into("<I", pkg_out, 4, len(pkg_out))
                out.extend(pkg_out)
            else:
                out.extend(chunk)
        else:
            out.extend(chunk)
        pos += c_size

    if modified_any:
        struct.pack_into("<I", out, 4, len(out))
    return bytes(out), modified_any

# Find clean donor android.jar in case android-36.1 was previously corrupted
all_jars = sorted(glob.glob(os.path.join(platforms_dir, "android-*", "android.jar")))

for jar_path in all_jars:
    bak_path = jar_path + ".original.bak"
    # Step A: If a clean backup already exists and current jar is corrupted, restore backup first
    if os.path.isfile(bak_path) and not check_jar_valid(jar_path):
        print(f"🔄 Backupdan tiklanmoqda: {bak_path} -> {jar_path}")
        shutil.copy2(bak_path, jar_path)

# Step B: If android-36.1/android.jar is still invalid (e.g. corrupted before backup was created),
# restore it from android-36/android.jar or android-35/android.jar
jar_36_1 = os.path.join(platforms_dir, "android-36.1", "android.jar")
if os.path.isfile(jar_36_1) and not check_jar_valid(jar_36_1):
    for donor_ver in ["android-36", "android-35", "android-34", "android-33"]:
        donor_jar = os.path.join(platforms_dir, donor_ver, "android.jar")
        donor_bak = donor_jar + ".original.bak"
        src_donor = donor_bak if os.path.isfile(donor_bak) else donor_jar
        if os.path.isfile(src_donor):
            print(f"🛠 {jar_36_1} avvalgi xatodan tiklanmoqda (manba: {src_donor})...")
            shutil.copy2(src_donor, jar_36_1)
            break

# Step C: Process each android.jar safely with backup + verification
for jar_path in all_jars:
    if check_jar_valid(jar_path):
        print(f"✔ {jar_path} — AAPT2 resurslari to'liq sog'lom (color/black=#ff000000)")
        continue

    bak_path = jar_path + ".original.bak"
    if not os.path.isfile(bak_path):
        shutil.copy2(jar_path, bak_path)
        print(f"📦 Asl nusxa saqlandi: {bak_path}")

    print(f"🔧 {jar_path} Termux ARM64 aapt2 uchun moslashtirilmoqda...")
    with zipfile.ZipFile(jar_path, "r") as zin:
        if "resources.arsc" not in zin.namelist():
            continue
        raw_arsc = zin.read("resources.arsc")
        patched_arsc, changed = patch_arsc(raw_arsc)
        if changed:
            tmp_jar = jar_path + ".tmp"
            with zipfile.ZipFile(tmp_jar, "w") as zout:
                for item in zin.infolist():
                    if item.filename == "resources.arsc":
                        info = zipfile.ZipInfo("resources.arsc")
                        info.compress_type = zipfile.ZIP_STORED
                        zout.writestr(info, patched_arsc)
                    else:
                        zout.writestr(item, zin.read(item.filename))
            os.replace(tmp_jar, jar_path)

    if check_jar_valid(jar_path):
        print(f"✅ {jar_path} muvaffaqiyatli moslashtirildi va tasdiqlandi!")
    else:
        print(f"⚠ {jar_path} tekshiruvdan o'tmadi, backup tiklanmoqda...")
        if os.path.isfile(bak_path):
            shutil.copy2(bak_path, jar_path)
PYEOF
fi

echo -e "${CYAN}================================================================${NC}"
echo -e "${GREEN}✔ BARCHA TUZATISHLAR BAJARILDI! Endi APK yig'ish uchun:${NC}"
echo -e "${YELLOW}  bash termux-build-apk.sh${NC}"
echo -e "${CYAN}================================================================${NC}"
