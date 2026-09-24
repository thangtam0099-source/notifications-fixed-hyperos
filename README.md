# HyperOS Notification Fixer (Xiaomi China ROM)

Ứng dụng Android Native chuyên sâu khắc phục triệt để tình trạng chậm hoặc mất thông báo trên Xiaomi/Redmi/POCO chạy HyperOS China ROM thông qua Shizuku & ADB shell privilege.

## Các module chính:
- **MilletProtectionManager**: Quản lý an toàn `MILLET_NO_RESTRICT_APP`
- **GmsProtectionManager**: Khắc phục đóng băng Google Play Services & Greezer
- **XiaomiAppOpsManager**: Quản lý quyền Xiaomi Autostart OP 10008 & Doze
- **FcmConnectionManager**: Giữ socket kết nối FCM và Reconnect tự động
- **OneClickFixEngine**: Quy trình 15 bước tự động kiểm tra, sửa và verify
- **AntiRevertEngine**: WorkManager chạy định kỳ ngăn chặn HyperOS tự revert policy
- **SafeModeManager**: Snapshot trạng thái ban đầu và khôi phục an toàn (Undo Fix)

## Hướng dẫn xuất và cài đặt APK:
1. Xem chi tiết trong file `scripts/HUONG_DAN_CAI_DAT_APK.txt`
2. Hoặc tải lên GitHub để GitHub Actions tự động build ra file APK thông qua `.github/workflows/build-apk.yml`
3. Hoặc chạy script nhanh không cần APK: `scripts/hyperos_fix.sh`