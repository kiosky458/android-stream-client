package com.artiforge.streamclient;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;

/**
 * v1.2.8: 透明 Activity（解決相機背景限制）
 * v1.3.2: 2×2 綠點/紅點指示器
 * 
 * 原理：
 * - 創建 2x2 視窗（左下角）
 * - 綠色 = 閒置，紅色 = 串流中
 * - 保持在前景，允許相機在背景運行
 */
public class TransparentActivity extends Activity {
    
    private static TransparentActivity instance;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        
        // v1.3.2: 設置為 2x2 視窗（左下角）
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.width = 2;
        params.height = 2;
        params.gravity = Gravity.BOTTOM | Gravity.START;
        params.x = 0;
        params.y = 0;
        getWindow().setAttributes(params);
        
        // v1.3.2: 默認綠色背景
        getWindow().getDecorView().setBackgroundColor(Color.parseColor("#28a745"));
        
        // 保持螢幕常亮（防止鎖屏）
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        // 允許鎖屏時顯示
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        
        // v1.3.0.2: 防止搶焦點（避免被帶到前景）
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }
    
    /**
     * v1.3.2: 更新指示器顏色
     * @param streaming true = 紅色（串流中），false = 綠色（閒置）
     */
    public static void updateIndicator(boolean streaming) {
        if (instance != null) {
            instance.runOnUiThread(() -> {
                if (streaming) {
                    // 串流中 → 紅色
                    instance.getWindow().getDecorView().setBackgroundColor(Color.parseColor("#dc3545"));
                } else {
                    // 閒置 → 綠色
                    instance.getWindow().getDecorView().setBackgroundColor(Color.parseColor("#28a745"));
                }
            });
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        instance = null;
    }
    
    @Override
    public void onBackPressed() {
        // 阻止返回鍵關閉（保持前景）
        // 不調用 super.onBackPressed()
    }
}
