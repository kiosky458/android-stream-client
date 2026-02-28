#!/usr/bin/env python3
"""建立 Android App Icons"""

from PIL import Image, ImageDraw
import os

# Icon 尺寸
SIZES = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192
}

# 顏色
BG_COLOR = (102, 126, 234)  # #667eea
FG_COLOR = (255, 255, 255)  # white

def create_icon(size, output_path):
    """建立圓形相機圖示"""
    # 建立圖片
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    
    # 繪製背景圓形
    draw.ellipse([0, 0, size-1, size-1], fill=BG_COLOR)
    
    # 繪製相機圖示（簡化版）
    center = size // 2
    
    # 相機外框
    cam_width = size * 0.6
    cam_height = size * 0.45
    cam_x1 = center - cam_width // 2
    cam_y1 = center - cam_height // 2
    cam_x2 = center + cam_width // 2
    cam_y2 = center + cam_height // 2
    draw.rectangle([cam_x1, cam_y1, cam_x2, cam_y2], outline=FG_COLOR, width=max(2, size//30))
    
    # 鏡頭圓圈
    lens_r = size * 0.15
    draw.ellipse([center-lens_r, center-lens_r, center+lens_r, center+lens_r], 
                 outline=FG_COLOR, width=max(2, size//30))
    
    # 儲存
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    img.save(output_path, 'PNG')
    print(f"✅ {output_path}")

def main():
    base_path = "app/src/main/res"
    
    print("🎨 建立 App Icons...")
    print("")
    
    for density, size in SIZES.items():
        dir_path = f"{base_path}/mipmap-{density}"
        
        # 建立普通 icon
        create_icon(size, f"{dir_path}/ic_launcher.png")
        
        # 建立圓形 icon（相同）
        create_icon(size, f"{dir_path}/ic_launcher_round.png")
    
    print("")
    print("✅ 所有 Icons 建立完成！")

if __name__ == "__main__":
    main()
