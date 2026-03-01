package com.artiforge.streamclient;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;

/**
 * v1.2.8: 透明 Activity（解決相機背景限制）
 * 
 * 原理：
 * - 創建 1x1 透明視窗
 * - 保持在前景
 * - 欺騙系統認為應用在前景
 * - 允許相機在背景運行
 */
public class TransparentActivity extends Activity {
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 設置為 1x1 透明視窗（左下角）
        // v1.3.0.2: 改為左下角，更不容易被觸碰
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.width = 1;
        params.height = 1;
        params.gravity = Gravity.BOTTOM | Gravity.START;
        params.x = 0;
        params.y = 0;
        getWindow().setAttributes(params);
        
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
    
    @Override
    public void onBackPressed() {
        // 阻止返回鍵關閉（保持前景）
        // 不調用 super.onBackPressed()
    }
}
