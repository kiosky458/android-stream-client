package com.artiforge.streamclient;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.net.URISyntaxException;

import io.socket.client.IO;
import io.socket.client.Socket;

public class SimpleMainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.POST_NOTIFICATIONS
    };

    private EditText serverUrlInput;
    private Button connectBtn;
    private TextView statusText;
    private TextView logText;
    
    private Socket socket;
    private boolean isConnected = false;
    private Handler mainHandler;
    private Vibrator vibrator;
    private CameraStreamManager cameraManager;
    private okhttp3.OkHttpClient httpClient = null;
    private Runnable autoStopRunnable = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            setContentView(R.layout.activity_simple);
            
            mainHandler = new Handler(Looper.getMainLooper());
            
            // 初始化震動器（Android 12+ 使用新 API）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                VibratorManager vibratorManager = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = vibratorManager.getDefaultVibrator();
            } else {
                vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            }
            
            // 初始化 UI
            serverUrlInput = findViewById(R.id.serverUrlInput);
            connectBtn = findViewById(R.id.connectBtn);
            statusText = findViewById(R.id.statusText);
            logText = findViewById(R.id.logText);
            
            // 初始化相機管理器
            cameraManager = new CameraStreamManager(this);
            cameraManager.setFrameCallback(new CameraStreamManager.FrameCallback() {
                @Override
                public void onFrameAvailable(byte[] jpegData) {
                    uploadFrame(jpegData);
                }
                
                @Override
                public void onError(String error) {
                    appendLog("❌ 相機錯誤: " + error);
                }
                
                @Override
                public void onInfo(String message) {
                    appendLog(message);
                }
            });
            
            // 連接按鈕
            connectBtn.setOnClickListener(v -> {
                if (isConnected) {
                    disconnect();
                } else {
                    connect();
                }
            });
            
            // 檢查權限
            if (!checkPermissions()) {
                requestPermissions();
            }
            
            appendLog("✅ App 啟動成功！");
            try {
                String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                appendLog("版本: " + versionName + " (HTTPS + 480p @ 10fps)");
            } catch (Exception e) {
                appendLog("版本: 1.1.0 (HTTPS + 480p @ 10fps)");
            }
            appendLog("請輸入伺服器網址並點擊連接");
            
        } catch (Exception e) {
            Toast.makeText(this, "錯誤: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }
    
    private void connect() {
        String serverUrl = serverUrlInput.getText().toString().trim();
        
        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "請輸入伺服器網址", Toast.LENGTH_SHORT).show();
            return;
        }
        
        appendLog("正在連接: " + serverUrl);
        
        try {
            socket = IO.socket(serverUrl);
            
            socket.on(Socket.EVENT_CONNECT, args -> {
                mainHandler.post(() -> {
                    isConnected = true;
                    updateUI();
                    appendLog("✅ WebSocket 連接成功！");
                    
                    // 註冊裝置
                    try {
                        JSONObject deviceInfo = new JSONObject();
                        deviceInfo.put("device_id", android.os.Build.MANUFACTURER + "_" + android.os.Build.MODEL);
                        socket.emit("android_register", deviceInfo);
                        appendLog("📱 發送註冊請求: " + android.os.Build.MANUFACTURER + "_" + android.os.Build.MODEL);
                    } catch (Exception e) {
                        appendLog("❌ 註冊失敗: " + e.getMessage());
                    }
                });
            });
            
            socket.on("registered", args -> {
                mainHandler.post(() -> {
                    appendLog("✅ 裝置註冊成功！");
                });
            });
            
            socket.on(Socket.EVENT_DISCONNECT, args -> {
                mainHandler.post(() -> {
                    isConnected = false;
                    updateUI();
                    appendLog("❌ 連接已斷開");
                });
            });
            
            socket.on(Socket.EVENT_CONNECT_ERROR, args -> {
                mainHandler.post(() -> {
                    String error = args.length > 0 ? args[0].toString() : "未知錯誤";
                    appendLog("❌ 連接錯誤: " + error);
                    
                    // 停止相機串流（如果正在運行）
                    if (cameraManager != null) {
                        cameraManager.stopStreaming();
                    }
                });
            });
            
            socket.on("cmd_start_stream", args -> {
                mainHandler.post(() -> {
                    appendLog("📹 收到開始串流指令");
                    startCameraStream();
                });
            });
            
            socket.on("cmd_stop_stream", args -> {
                mainHandler.post(() -> {
                    appendLog("🛑 收到停止串流指令");
                    stopCameraStream();
                });
            });
            
            socket.on("cmd_vibrate", args -> {
                mainHandler.post(() -> {
                    appendLog("📳 收到震動指令");
                    doVibrate();
                });
            });
            
            socket.connect();
            appendLog("🔄 正在建立連接...");
            
        } catch (URISyntaxException e) {
            appendLog("❌ 網址格式錯誤: " + e.getMessage());
        }
    }
    
    private void disconnect() {
        if (socket != null) {
            socket.disconnect();
            socket.close();
            socket = null;
        }
        isConnected = false;
        updateUI();
        appendLog("🔌 已斷線");
    }
    
    private void updateUI() {
        if (isConnected) {
            statusText.setText("✅ 已連接");
            statusText.setTextColor(0xFF00AA00);
            connectBtn.setText("斷開連接");
        } else {
            statusText.setText("❌ 未連接");
            statusText.setTextColor(0xFFFF0000);
            connectBtn.setText("連接");
        }
    }
    
    private void appendLog(String message) {
        mainHandler.post(() -> {
            String current = logText.getText().toString();
            String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(new java.util.Date());
            logText.setText(current + "\n[" + timestamp + "] " + message);
            
            // 自動捲動到底部
            final android.widget.ScrollView scrollView = findViewById(R.id.logScrollView);
            if (scrollView != null) {
                scrollView.post(() -> scrollView.fullScroll(android.view.View.FOCUS_DOWN));
            }
        });
    }
    
    private boolean checkPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
    
    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (allGranted) {
                appendLog("✅ 所有權限已授予");
            } else {
                appendLog("⚠️ 部分權限被拒絕，功能可能受限");
            }
        }
    }
    
    private void doVibrate() {
        try {
            appendLog("🔍 檢查震動器...");
            appendLog("   Android 版本: " + android.os.Build.VERSION.SDK_INT);
            
            if (vibrator == null) {
                appendLog("❌ 震動器為 null");
                return;
            }
            
            appendLog("   震動器存在: " + vibrator.hasVibrator());
            
            if (vibrator.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    // Android 8.0+ 使用 VibrationEffect
                    VibrationEffect effect = VibrationEffect.createOneShot(
                        1000,  // 加長到 1 秒更明顯
                        255    // 最大強度
                    );
                    vibrator.vibrate(effect);
                    appendLog("✅ 震動執行完成 (VibrationEffect API, 1000ms)");
                } else {
                    // 舊版 API
                    vibrator.vibrate(1000);
                    appendLog("✅ 震動執行完成 (Legacy API, 1000ms)");
                }
            } else {
                appendLog("⚠️ 裝置不支援震動");
            }
        } catch (SecurityException e) {
            appendLog("❌ 震動權限被拒絕: " + e.getMessage());
        } catch (Exception e) {
            appendLog("❌ 震動失敗: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void startCameraStream() {
        try {
            // 取消之前的自動停止
            if (autoStopRunnable != null) {
                mainHandler.removeCallbacks(autoStopRunnable);
            }
            
            appendLog("📹 啟動相機串流（10 秒）...");
            cameraManager.startCamera();
            appendLog("⏳ 等待相機就緒...");
            
            // 設定 10 秒後自動停止
            autoStopRunnable = new Runnable() {
                @Override
                public void run() {
                    appendLog("⏰ 10 秒到，自動停止串流");
                    stopCameraStream();
                }
            };
            mainHandler.postDelayed(autoStopRunnable, 10000);
            
        } catch (Exception e) {
            appendLog("❌ 相機啟動失敗: " + e.getMessage());
        }
    }
    
    private void stopCameraStream() {
        try {
            // 取消自動停止（如果手動觸發）
            if (autoStopRunnable != null) {
                mainHandler.removeCallbacks(autoStopRunnable);
                autoStopRunnable = null;
            }
            
            cameraManager.stopStreaming();
            appendLog("⏹️ 相機串流已停止");
        } catch (Exception e) {
            appendLog("❌ 停止失敗: " + e.getMessage());
        }
    }
    
    private void uploadFrame(byte[] jpegData) {
        if (!isConnected || socket == null) return;
        
        try {
            String serverUrl = serverUrlInput.getText().toString().trim();
            
            // 初始化 HTTP 客戶端（複用連接）
            if (httpClient == null) {
                httpClient = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
            }
            
            // 使用 OkHttp 上傳影格
            new Thread(() -> {
                try {
                    okhttp3.RequestBody body = okhttp3.RequestBody.create(
                        jpegData,
                        okhttp3.MediaType.parse("image/jpeg")
                    );
                    
                    okhttp3.Request request = new okhttp3.Request.Builder()
                        .url(serverUrl + "/upload_frame")
                        .post(body)
                        .build();
                    
                    okhttp3.Response response = httpClient.newCall(request).execute();
                    response.close(); // 立即關閉回應
                    
                } catch (java.net.SocketTimeoutException e) {
                    // 超時靜默失敗
                } catch (Exception e) {
                    // 其他錯誤靜默失敗
                }
            }).start();
            
        } catch (Exception e) {
            // 靜默失敗
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraManager.stopCamera();
        disconnect();
    }
}
