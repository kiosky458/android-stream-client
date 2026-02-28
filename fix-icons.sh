#!/bin/bash
# 建立基本的 App Icon（使用 ImageMagick）

cd /home/kiosky/.openclaw-data/workspace/android-stream-relay/android-app

echo "🎨 建立 App Icon..."

# 檢查 ImageMagick
if ! command -v convert &> /dev/null; then
    echo "❌ 需要安裝 ImageMagick"
    echo "執行: sudo apt install imagemagick"
    exit 1
fi

# 建立基本的圖示（藍色圓形背景 + 白色相機符號）
# 使用 Unicode 相機符號

# MDPI (48x48)
convert -size 48x48 xc:#667eea -fill white -font DejaVu-Sans -pointsize 32 \
    -gravity center -annotate +0+0 "📹" \
    app/src/main/res/mipmap-mdpi/ic_launcher.png 2>/dev/null || \
convert -size 48x48 xc:#667eea -fill white -draw "circle 24,24 24,4" \
    app/src/main/res/mipmap-mdpi/ic_launcher.png

cp app/src/main/res/mipmap-mdpi/ic_launcher.png \
   app/src/main/res/mipmap-mdpi/ic_launcher_round.png

# HDPI (72x72)
convert -size 72x72 xc:#667eea -fill white -font DejaVu-Sans -pointsize 48 \
    -gravity center -annotate +0+0 "📹" \
    app/src/main/res/mipmap-hdpi/ic_launcher.png 2>/dev/null || \
convert -size 72x72 xc:#667eea -fill white -draw "circle 36,36 36,6" \
    app/src/main/res/mipmap-hdpi/ic_launcher.png

cp app/src/main/res/mipmap-hdpi/ic_launcher.png \
   app/src/main/res/mipmap-hdpi/ic_launcher_round.png

# XHDPI (96x96)
convert -size 96x96 xc:#667eea -fill white -font DejaVu-Sans -pointsize 64 \
    -gravity center -annotate +0+0 "📹" \
    app/src/main/res/mipmap-xhdpi/ic_launcher.png 2>/dev/null || \
convert -size 96x96 xc:#667eea -fill white -draw "circle 48,48 48,8" \
    app/src/main/res/mipmap-xhdpi/ic_launcher.png

cp app/src/main/res/mipmap-xhdpi/ic_launcher.png \
   app/src/main/res/mipmap-xhdpi/ic_launcher_round.png

# XXHDPI (144x144)
convert -size 144x144 xc:#667eea -fill white -font DejaVu-Sans -pointsize 96 \
    -gravity center -annotate +0+0 "📹" \
    app/src/main/res/mipmap-xxhdpi/ic_launcher.png 2>/dev/null || \
convert -size 144x144 xc:#667eea -fill white -draw "circle 72,72 72,12" \
    app/src/main/res/mipmap-xxhdpi/ic_launcher.png

cp app/src/main/res/mipmap-xxhdpi/ic_launcher.png \
   app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png

# XXXHDPI (192x192)
convert -size 192x192 xc:#667eea -fill white -font DejaVu-Sans -pointsize 128 \
    -gravity center -annotate +0+0 "📹" \
    app/src/main/res/mipmap-xxxhdpi/ic_launcher.png 2>/dev/null || \
convert -size 192x192 xc:#667eea -fill white -draw "circle 96,96 96,16" \
    app/src/main/res/mipmap-xxxhdpi/ic_launcher.png

cp app/src/main/res/mipmap-xxxhdpi/ic_launcher.png \
   app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png

echo "✅ Icon 建立完成"
ls -lh app/src/main/res/mipmap-*/ic_launcher.png
