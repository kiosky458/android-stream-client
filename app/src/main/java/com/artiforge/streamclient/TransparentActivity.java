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
        
        android.util.Log.d("TransparentActivity", "onCreate called");
        
        // v1.3.2.2: 先設置所有 FLAG
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        
        // 設置為 2x2 視窗（左下角）
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.width = 2;
        params.height = 2;
        params.gravity = Gravity.BOTTOM | Gravity.START;  // 左下角
        params.x = 0;
        params.y = 0;
        getWindow().setAttributes(params);
        
        android.util.Log.d("TransparentActivity", "Window params set: width=" + params.width + ", height=" + params.height + ", gravity=" + params.gravity);
        
        // v1.3.2: 默認綠色背景
        getWindow().getDecorView().setBackgroundColor(Color.parseColor("#28a745"));
        android.util.Log.d("TransparentActivity", "Background color set to GREEN");
    }
    
    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        // v1.3.2.3: singleInstance 模式下，重新啟動會調用這裡
        android.util.Log.d("TransparentActivity", "onNewIntent called - Activity already running");
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        android.util.Log.d("TransparentActivity", "onResume called - Activity in foreground");
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        android.util.Log.d("TransparentActivity", "onPause called - Activity paused");
    }
    
    @Override
    protected void onStop() {
        super.onStop();
        android.util.Log.d("TransparentActivity", "onStop called - Activity stopped");
    }
    
    /**
     * v1.3.2: 更新指示器顏色
     * @param streaming true = 紅色（串流中），false = 綠色（閒置）
     */
    public static void updateIndicator(boolean streaming) {
        android.util.Log.d("TransparentActivity", "updateIndicator called: streaming=" + streaming + ", instance=" + (instance != null));
        if (instance != null) {
            instance.runOnUiThread(() -> {
                if (streaming) {
                    // 串流中 → 紅色
                    android.util.Log.d("TransparentActivity", "Setting RED color");
                    instance.getWindow().getDecorView().setBackgroundColor(Color.parseColor("#dc3545"));
                } else {
                    // 閒置 → 綠色
                    android.util.Log.d("TransparentActivity", "Setting GREEN color");
                    instance.getWindow().getDecorView().setBackgroundColor(Color.parseColor("#28a745"));
                }
            });
        } else {
            android.util.Log.e("TransparentActivity", "instance is NULL!");
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
