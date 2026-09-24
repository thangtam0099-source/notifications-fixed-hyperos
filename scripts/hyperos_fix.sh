#!/system/bin/sh
# HyperOS Notification Fixer - Quick Execution Script
echo "============================================================"
echo "      🚀 HYPEROS NOTIFICATION FIXER - ADB SHELL ENGINE      "
echo "============================================================"

# 1. GMS Protection
GMS_PKG="com.google.android.gms"
cmd appops set $GMS_PKG RUN_IN_BACKGROUND allow 2>/dev/null || true
dumpsys deviceidle whitelist +$GMS_PKG >/dev/null 2>&1 || true

# 2. Millet Anti-freeze
CURRENT_MILLET=$(settings get system MILLET_NO_RESTRICT_APP 2>/dev/null || echo "")
if ! echo "$CURRENT_MILLET" | grep -q "$GMS_PKG"; then
    settings put system MILLET_NO_RESTRICT_APP "${CURRENT_MILLET:+$CURRENT_MILLET,}$GMS_PKG"
fi

# 3. Disable Greezer
dumpsys greezer IM GMS disable >/dev/null 2>&1 || true

# 4. FCM Reconnect
am broadcast -a com.google.android.intent.action.GCM_RECONNECT -p $GMS_PKG >/dev/null 2>&1 || true

# 5. Autostart OP 10008 & Doze Whitelist for apps
TARGET_APPS="com.facebook.orca org.telegram.messenger com.zing.zalo com.whatsapp com.discord com.google.android.gm"
for PKG in $TARGET_APPS; do
    if pm list packages | grep -q "^package:${PKG}$"; then
        cmd appops set "$PKG" 10008 allow 2>/dev/null || cmd appops set "$PKG" AUTOSTART allow 2>/dev/null || true
        dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1 || true
        cmd package set-stopped-state "$PKG" false 2>/dev/null || true
        dumpsys aurogon whitelist add "$PKG" >/dev/null 2>&1 || true
        echo "  ✔️ Đã bảo vệ: $PKG"
    fi
done
echo "✨ HOÀN TẤT VÁ LỖI THÔNG BÁO CHO HYPEROS!"