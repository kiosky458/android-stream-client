#!/bin/bash
# Android Stream Client APK 自動安裝腳本

set -e

echo "📱 Android Stream Client - 自動安裝"
echo "===================================="

# 檢查 APK 檔案
if [ ! -f "app-debug.apk" ]; then
    echo "❌ 找不到 app-debug.apk"
    echo "請先從 GitHub Actions 下載 APK："
    echo "  https://github.com/kiosky458/android-stream-client/actions"
    exit 1
fi

# 檢查 ADB
if ! command -v adb &> /dev/null; then
    echo "❌ 找不到 adb，請先安裝 Android SDK Platform Tools"
    exit 1
fi

# 檢查裝置連接
echo ""
echo "🔍 檢查已連接的裝置..."
DEVICES=$(adb devices | grep -v "List" | grep "device$" | wc -l)

if [ "$DEVICES" -eq 0 ]; then
    echo "❌ 沒有裝置連接"
    echo ""
    echo "請先連接裝置："
    echo "  USB: adb devices"
    echo "  WiFi: adb connect <IP>:5555"
    exit 1
fi

echo "✅ 找到 $DEVICES 個裝置"

# 安裝 APK
echo ""
echo "📦 安裝 APK..."
adb install -r app-debug.apk

echo ""
echo "✅ 安裝完成！"

# 詢問是否啟動
echo ""
read -p "是否立即啟動 App？ (y/n): " -n 1 -r
echo ""

if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo "🚀 啟動 App..."
    adb shell am start -n com.artiforge.streamclient/.MainActivity
    
    echo ""
    echo "📋 查看 App 日誌："
    echo "  adb logcat | grep -E 'StreamService|MainActivity'"
    
    sleep 2
    echo ""
    echo "🔍 顯示最近日誌（按 Ctrl+C 停止）："
    adb logcat | grep --color=auto -E "StreamService|MainActivity"
fi
