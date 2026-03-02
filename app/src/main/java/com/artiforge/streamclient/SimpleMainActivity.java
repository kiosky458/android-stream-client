package com.artiforge.streamclient;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
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
import android.view.Gravity;
import android.view.View;
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
    private static final int REQUEST_OVERLAY_PERMISSION = 101; // v1.2.6
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.POST_NOTIFICATIONS
    };
    
    // v1.2.8: 固定伺服器位址
    private static final String SERVER_URL = "https://artiforge.studio";
    private static final long HEARTBEAT_INTERVAL = 3 * 60 * 1000; // 3 分鐘

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
    
    // v1.2.5: 錯誤追蹤（自動回報到 Web 端）
    private String lastLogLine = "";
    private String appVersion = "1.2.8";
    
    // v1.2.6: 懸浮窗（解決後台相機限制）
    private WindowManager overlayWindowManager;
    private View overlayView;
    
    // v1.2.7: WAKE_LOCK（保持懸浮窗運行）
    private PowerManager.WakeLock wakeLock;
    
    // v1.2.8: 心跳檢查（3 分鐘自動重連）
    private Runnable heartbeatRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // v1.2.4: 保持屏幕常亮（防止锁屏后相机停止）
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        // v1.2.7: 取得 WAKE_LOCK（保持 CPU 運行，防止懸浮窗被回收）
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "StreamClient::WakeLock"
            );
            wakeLock.acquire();
        }
        
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
                
                @Override
                public void onCameraStatusChanged(boolean available, String reason) {
                    // v1.3.1: 相機狀態變化時，發送到 Web 端
                    mainHandler.post(() -> sendCameraStatus(available, reason));
                }
            });
            
            // v1.2.8: 啟動透明 Activity（解決相機背景限制）
            startTransparentActivity();
            
            // v1.2.8: 啟動心跳檢查（3 分鐘自動重連）
            startHeartbeat();
            
            // 檢查權限
            if (!checkPermissions()) {
                requestPermissions();
            } else {
                // v1.2.8: 自動連線
                appendLog("✅ App 啟動成功！開始自動連線...");
                connect();
            }
            
            try {
                String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                appendLog("📱 版本: " + versionName);
                appendLog("🌐 伺服器: " + SERVER_URL);
            } catch (Exception e) {
                appendLog("📱 版本: 1.2.8");
                appendLog("🌐 伺服器: " + SERVER_URL);
            }
            
        } catch (Exception e) {
            Toast.makeText(this, "錯誤: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }
    
    private void connect() {
        // v1.2.8: 使用固定伺服器位址
        appendLog("🔄 正在連接: " + SERVER_URL);
        
        try {
            // Socket.IO 配置（HTTPS 固定）
            IO.Options options = new IO.Options();
            options.transports = new String[] {"websocket", "polling"};
            options.reconnection = true;
            options.reconnectionDelay = 1000;
            options.reconnectionAttempts = 5;
            options.timeout = 20000;
            options.forceNew = true;
            options.secure = true; // v1.2.8: HTTPS 固定
            
            socket = IO.socket(SERVER_URL, options);
            
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
                    
                    // v1.2.6: 請求懸浮窗權限（解決後台相機限制）
                    requestOverlayPermission();
                    
                    // v1.3.1: 初始化相機（會自動發送狀態）
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
            statusText.setText("✅ 已連接 - " + SERVER_URL);
            statusText.setTextColor(0xFF00AA00);
        } else {
            statusText.setText("❌ 未連接 - 嘗試重連中...");
            statusText.setTextColor(0xFFFF0000);
        }
    }
    
    private void appendLog(String message) {
        // v1.2.4: 日誌過濾 - 只顯示重要資訊（減少轟炸）
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
            return; // 跳過不重要的日誌
        }
        
        // v1.2.5: 檢測錯誤並自動回報到 Web 端
        if (message.contains("❌") || message.contains("錯誤") || message.contains("失敗")) {
            reportErrorToWeb(message);
        }
        
        // 儲存最後一行（用於錯誤上下文）
        lastLogLine = message;
        
        mainHandler.post(() -> {
            String current = logText.getText().toString();
            String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(new java.util.Date());
            String newLog = "[" + timestamp + "] " + message;
            
            // v1.2.5: 限制日誌為 30 條（自動清理舊日誌）
            String[] lines = current.split("\n");
            if (lines.length >= 30) {
                // 保留最新 29 條，加上新的這條 = 30 條
                StringBuilder sb = new StringBuilder();
                for (int i = Math.max(0, lines.length - 29); i < lines.length; i++) {
                    if (!lines[i].trim().isEmpty()) {
                        sb.append(lines[i]).append("\n");
                    }
                }
                current = sb.toString();
            }
            
            logText.setText(current + newLog + "\n");
            
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
                // v1.2.8: 權限授予後自動連線
                appendLog("🔄 開始自動連線...");
                connect();
                
                // v1.3.1: 權限授予後，初始化相機（會自動發送狀態）
                if (cameraManager != null) {
                    cameraManager.startCamera();
                }
            } else {
                appendLog("⚠️ 部分權限被拒絕，功能可能受限");
                appendLog("⚠️ 建議授予所有權限後重啟 App");
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
                    
                    @Override
                    public void onCameraStatusChanged(boolean available, String reason) {
                        // v1.3.1: 相機狀態變化時，發送到 Web 端
                        mainHandler.post(() -> sendCameraStatus(available, reason));
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
            
            // v1.3.2: 更新指示器 → 紅點
            TransparentActivity.updateIndicator(true);
            
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
            
            // v1.3.2: 更新指示器 → 綠點
            TransparentActivity.updateIndicator(false);
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
            // v1.2.8: 使用固定伺服器位址
            
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
                        .url(SERVER_URL + "/upload_frame")
                        .post(body)
                        .build();
                    
                    long startTime = System.currentTimeMillis();
                    okhttp3.Response response = httpClient.newCall(request).execute();
                    long elapsed = System.currentTimeMillis() - startTime;
                    
                    if (response.isSuccessful()) {
                        uploadSuccess++;
                        // v1.2.5: 移除上傳成功日誌（減少轟炸）
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
     * v1.2.5: 自動回報錯誤到 Web 端（方便診斷）
     */
    private void reportErrorToWeb(String errorMessage) {
        if (socket == null || !socket.connected()) {
            return; // 未連接，無法回報
        }
        
        try {
            JSONObject errorReport = new JSONObject();
            errorReport.put("version", appVersion);
            errorReport.put("context", lastLogLine); // 錯誤的上一行
            errorReport.put("error", errorMessage);   // 錯誤訊息
            errorReport.put("timestamp", System.currentTimeMillis());
            
            socket.emit("error_report", errorReport);
        } catch (Exception e) {
            // 靜默失敗（避免錯誤回報本身造成錯誤）
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
    
    /**
     * v1.2.6: 請求懸浮窗權限（解決後台相機限制）
     * v1.2.7: 延遲創建懸浮窗（等待前景服務完全啟動）
     */
    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                try {
                    Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                    );
                    startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
                    appendLog("🔓 請允許「顯示在其他應用程式上層」以實現後台串流");
                } catch (Exception e) {
                    appendLog("⚠️ 無法請求懸浮窗權限: " + e.getMessage());
                }
            } else {
                appendLog("✅ 懸浮窗權限已授予");
                // v1.2.7: 延遲 2 秒創建（等待前景服務完全啟動）
                mainHandler.postDelayed(() -> createOverlayWindow(), 2000);
            }
        }
    }
    
    /**
     * v1.2.6: 創建 2x2 懸浮窗（放在狀態列旁邊）
     */
    private void createOverlayWindow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !Settings.canDrawOverlays(this)) {
            return; // 無權限
        }
        
        // 避免重複創建
        if (overlayView != null) {
            return;
        }
        
        try {
            overlayWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            
            // 創建 2x2 半透明 View
            overlayView = new View(this);
            overlayView.setBackgroundColor(Color.argb(128, 0, 255, 0)); // 半透明綠色（可見但不顯眼）
            
            // 設定懸浮窗參數
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                2, 2, // 2x2 像素
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |    // 不搶焦點
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |    // 不可觸控
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,  // 可超出邊界
                PixelFormat.TRANSLUCENT
            );
            
            // 位置：螢幕右上角（狀態列旁邊）
            params.gravity = Gravity.TOP | Gravity.END;
            params.x = 10; // 距離右邊緣 10 像素
            params.y = 0;  // 頂部
            
            // 添加到 WindowManager
            overlayWindowManager.addView(overlayView, params);
            appendLog("✅ 背景模式已啟用（懸浮窗）");
            
        } catch (Exception e) {
            appendLog("❌ 懸浮窗創建失敗: " + e.getMessage());
            reportErrorToWeb("❌ 懸浮窗創建失敗: " + e.getMessage());
        }
    }
    
    /**
     * v1.2.6: 移除懸浮窗
     */
    private void removeOverlayWindow() {
        if (overlayView != null && overlayWindowManager != null) {
            try {
                overlayWindowManager.removeView(overlayView);
                overlayView = null;
                appendLog("⏹ 背景模式已停用");
            } catch (Exception e) {
                // 靜默失敗
            }
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                appendLog("✅ 懸浮窗權限已授予");
                // v1.2.7: 延遲 2 秒創建（等待前景服務完全啟動）
                mainHandler.postDelayed(() -> createOverlayWindow(), 2000);
            } else {
                appendLog("⚠️ 需要懸浮窗權限才能後台串流");
            }
        }
    }
    
    /**
     * v1.2.8: 啟動透明 Activity（解決相機背景限制）
     */
    private void startTransparentActivity() {
        try {
            Intent intent = new Intent(this, TransparentActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            appendLog("🔒 透明視窗已啟動（保持前景狀態）");
        } catch (Exception e) {
            appendLog("⚠️ 透明視窗啟動失敗: " + e.getMessage());
        }
    }
    
    /**
     * v1.2.8: 啟動心跳檢查（每 3 分鐘檢查連線）
     */
    private void startHeartbeat() {
        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                // 檢查連線狀態
                if (!isConnected || socket == null || !socket.connected()) {
                    appendLog("💔 心跳檢測：連線已斷開，嘗試重連...");
                    connect();
                } else {
                    appendLog("💚 心跳檢測：連線正常");
                }
                
                // 3 分鐘後再次檢查
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL);
            }
        };
        
        // 首次延遲 3 分鐘
        mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL);
        appendLog("💗 心跳監控已啟動（每 3 分鐘檢查）");
    }
    
    /**
     * v1.3.1: 發送相機狀態到 Web 端
     */
    private void sendCameraStatus(boolean available, String reason) {
        if (socket == null || !socket.connected()) {
            return;
        }
        
        try {
            JSONObject status = new JSONObject();
            status.put("available", available);
            status.put("reason", reason);
            socket.emit("camera_status", status);
            
            if (available) {
                appendLog("📸 相機狀態：可用");
            } else {
                appendLog("🔒 相機狀態：" + (reason.equals("locked") ? "手機鎖定，相機無權限" : "不可用"));
            }
        } catch (Exception e) {
            appendLog("❌ 發送相機狀態失敗: " + e.getMessage());
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // v1.3.0.2: 確保主界面在前景（防止透明 Activity 搶焦點）
        // 用戶切換回 App 時，總是顯示主界面
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        // v1.3.1: 用戶切換回 App（可能剛解鎖），重新初始化相機（會自動發送狀態）
        if (checkPermissions() && cameraManager != null) {
            cameraManager.startCamera();
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // v1.2.8: 停止心跳檢查
        if (heartbeatRunnable != null) {
            mainHandler.removeCallbacks(heartbeatRunnable);
        }
        
        removeOverlayWindow(); // v1.2.6: 清理懸浮窗
        
        // v1.2.7: 釋放 WAKE_LOCK
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
        
        stopForegroundService();
        if (cameraManager != null) {
            cameraManager.stopCamera();
        }
        disconnect();
    }
}
