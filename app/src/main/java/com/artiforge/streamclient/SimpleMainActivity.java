package com.artiforge.streamclient;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
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
    
    private static final String FOREGROUND_CHANNEL_ID = "stream_service";
    private static final int FOREGROUND_NOTIFICATION_ID = 1001;
    
    private Socket socket;
    private boolean isConnected = false;
    private Handler mainHandler;
    private Vibrator vibrator;
    private CameraStreamManager cameraManager;
    private okhttp3.OkHttpClient httpClient = null;
    private Runnable autoStopRunnable = null;
    private NotificationManager notificationManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // v1.2.4: 保持屏幕常亮（防止锁屏后相机停止）
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        try {
            setContentView(R.layout.activity_simple);
            
            mainHandler = new Handler(Looper.getMainLooper());
            
            // 初始化通知管理器
            notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            createNotificationChannel();
            
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
            // Socket.IO 配置（適應 HTTPS）
            IO.Options options = new IO.Options();
            options.transports = new String[] {"websocket", "polling"};
            options.reconnection = true;
            options.reconnectionDelay = 1000;
            options.reconnectionAttempts = 5;
            options.timeout = 20000;
            options.forceNew = true;
            options.secure = serverUrl.startsWith("https");
            
            socket = IO.socket(serverUrl, options);
            
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
                    
                    // 啟動前景服務（保持相機權限）
                    startForegroundService();
                    appendLog("🔒 已啟動前景服務（防止系統停用相機）");
                    
                    // v1.2.4: 請求電池優化豁免（後台執行）
                    requestBatteryOptimizationExemption();
                    
                    // 立即初始化相機（提前發現問題）
                    if (checkPermissions()) {
                        appendLog("📸 開始初始化相機系統...");
                        initializeCamera();
                    } else {
                        appendLog("⚠️ 缺少相機權限，請授予權限後重新連接");
                    }
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
                    if (args.length > 0) {
                        Object errorObj = args[0];
                        String errorDetail = "";
                        
                        // 詳細錯誤訊息
                        if (errorObj instanceof Exception) {
                            Exception e = (Exception) errorObj;
                            errorDetail = e.getClass().getSimpleName() + ": " + e.getMessage();
                            
                            // 更詳細的堆疊追蹤
                            if (e.getCause() != null) {
                                errorDetail += "\n原因: " + e.getCause().getMessage();
                            }
                        } else {
                            errorDetail = errorObj.toString();
                        }
                        
                        appendLog("❌ 連接錯誤: " + errorDetail);
                    } else {
                        appendLog("❌ 連接錯誤: 未知錯誤");
                    }
                    
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
        
        // 停止前景服務
        stopForegroundService();
        appendLog("🔓 已停止前景服務");
        
        // 清理相機
        if (cameraManager != null) {
            appendLog("📸 關閉相機...");
            cameraManager.stopCamera();
            cameraManager = null;
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
        // v1.2.4: 日志过滤 - 只显示重要信息（减少轰炸）
        boolean shouldLog = message.contains("✅") || message.contains("❌") || 
                           message.contains("⚠️") || message.contains("📤") || 
                           message.contains("📊") || message.contains("🔒") ||
                           message.contains("⚡") || message.contains("📸") ||
                           message.contains("📹") || message.contains("⏹") ||
                           message.contains("🎬") || message.contains("🔓") ||
                           message.contains("启动") || message.contains("停止") || 
                           message.contains("初始化") || message.contains("成功") ||
                           message.contains("失败") || message.contains("错误") ||
                           message.contains("啟動") || message.contains("錯誤") ||
                           message.contains("失敗");
        
        if (!shouldLog) {
            return; // 跳过不重要的日志
        }
        
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
            appendLog("📳 發送呼叫通知...");
            
            // 使用通知聲音（手機震動模式下會自動震動）
            android.app.NotificationManager notificationManager = 
                (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            
            if (notificationManager == null) {
                appendLog("❌ 無法取得 NotificationManager");
                return;
            }
            
            String channelId = "call_notification";
            
            // Android 8.0+ 需要建立通知頻道
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    channelId,
                    "呼叫通知",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                );
                
                // 設定通知聲音（使用系統預設）
                channel.setSound(
                    android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                    null
                );
                
                // 啟用震動（手機震動模式下會震動）
                channel.enableVibration(true);
                channel.setVibrationPattern(new long[]{0, 500, 200, 500});
                
                notificationManager.createNotificationChannel(channel);
            }
            
            // 建立通知
            androidx.core.app.NotificationCompat.Builder builder = 
                new androidx.core.app.NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("📞 遠端呼叫")
                    .setContentText("控制台正在呼叫您")
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL);
            
            // 發送通知
            notificationManager.notify(999, builder.build());
            appendLog("✅ 通知已發送（手機震動模式下會震動）");
            
        } catch (Exception e) {
            appendLog("❌ 通知失敗: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void initializeCamera() {
        try {
            if (cameraManager == null) {
                cameraManager = new CameraStreamManager(this);
                cameraManager.setFrameCallback(new CameraStreamManager.FrameCallback() {
                    @Override
                    public void onFrameAvailable(byte[] jpegData) {
                        uploadFrame(jpegData);
                    }
                    
                    @Override
                    public void onError(String error) {
                        mainHandler.post(() -> appendLog("❌ 相機錯誤: " + error));
                    }
                    
                    @Override
                    public void onInfo(String message) {
                        mainHandler.post(() -> appendLog(message));
                    }
                });
            }
            
            appendLog("📸 正在初始化相機...");
            cameraManager.startCamera();
            
        } catch (Exception e) {
            appendLog("❌ 相機初始化失敗: " + e.getMessage());
        }
    }
    
    private void startCameraStream() {
        try {
            // 取消之前的自動停止
            if (autoStopRunnable != null) {
                mainHandler.removeCallbacks(autoStopRunnable);
            }
            
            // 重置計數器
            uploadCount = 0;
            uploadSuccess = 0;
            uploadFail = 0;
            
            if (cameraManager == null) {
                appendLog("⚠️ 相機未初始化，嘗試重新初始化...");
                initializeCamera();
                // 等待初始化完成後再啟動串流
                mainHandler.postDelayed(() -> {
                    if (cameraManager != null) {
                        cameraManager.startStreaming();
                    }
                }, 2000);
                return;
            }
            
            appendLog("📹 啟動串流上傳（10 秒）...");
            cameraManager.startStreaming();
            
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
            appendLog("❌ 啟動串流失敗: " + e.getMessage());
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
            appendLog("📊 統計: 總計 " + uploadCount + " 影格，成功 " + uploadSuccess + "，失敗 " + uploadFail);
        } catch (Exception e) {
            appendLog("❌ 停止失敗: " + e.getMessage());
        }
    }
    
    private volatile int uploadCount = 0;
    private volatile int uploadSuccess = 0;
    private volatile int uploadFail = 0;
    
    private void uploadFrame(byte[] jpegData) {
        if (!isConnected || socket == null) {
            appendLog("⚠️ 未連接，無法上傳");
            return;
        }
        
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
            
            uploadCount++;
            final int frameNum = uploadCount;
            
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
                    
                    long startTime = System.currentTimeMillis();
                    okhttp3.Response response = httpClient.newCall(request).execute();
                    long elapsed = System.currentTimeMillis() - startTime;
                    
                    if (response.isSuccessful()) {
                        uploadSuccess++;
                        if (frameNum == 1 || frameNum % 10 == 0) {
                            mainHandler.post(() -> appendLog("📤 上傳成功 #" + frameNum + ": " + jpegData.length + " bytes (" + elapsed + "ms)"));
                        }
                    } else {
                        uploadFail++;
                        mainHandler.post(() -> appendLog("❌ 上傳失敗 #" + frameNum + ": HTTP " + response.code()));
                    }
                    response.close();
                    
                } catch (java.net.SocketTimeoutException e) {
                    uploadFail++;
                    mainHandler.post(() -> appendLog("❌ 上傳超時 #" + frameNum));
                } catch (Exception e) {
                    uploadFail++;
                    mainHandler.post(() -> appendLog("❌ 上傳錯誤 #" + frameNum + ": " + e.getMessage()));
                }
            }).start();
            
        } catch (Exception e) {
            appendLog("❌ 上傳異常: " + e.getMessage());
        }
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "串流服務",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("保持相機連接（防止系統停用）");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    private void startForegroundService() {
        Intent notificationIntent = new Intent(this, SimpleMainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 
            0, 
            notificationIntent, 
            PendingIntent.FLAG_IMMUTABLE
        );
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("📹 串流服務運行中")
            .setContentText("相機已就緒，等待串流指令")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent);
        
        notificationManager.notify(FOREGROUND_NOTIFICATION_ID, builder.build());
    }
    
    private void stopForegroundService() {
        if (notificationManager != null) {
            notificationManager.cancel(FOREGROUND_NOTIFICATION_ID);
        }
    }
    
    /**
     * v1.2.4: 請求電池優化豁免（允許後台執行）
     */
    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            String packageName = getPackageName();
            
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + packageName));
                    startActivity(intent);
                    appendLog("⚡ 請允許「不受電池優化限制」以實現後台串流");
                } catch (Exception e) {
                    appendLog("⚠️ 無法請求電池優化豁免: " + e.getMessage());
                }
            } else {
                appendLog("✅ 電池優化已豁免（可後台執行）");
            }
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopForegroundService();
        if (cameraManager != null) {
            cameraManager.stopCamera();
        }
        disconnect();
    }
}
