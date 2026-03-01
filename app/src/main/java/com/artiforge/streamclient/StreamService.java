package com.artiforge.streamclient;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import io.socket.client.IO;
import io.socket.client.Socket;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class StreamService extends Service {

    private static final String TAG = "StreamService";
    private static final String CHANNEL_ID = "StreamServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    
    // 串流設定
    private static final int STREAM_WIDTH = 480;
    private static final int STREAM_HEIGHT = 640;
    private static final int STREAM_FPS = 10;
    private static final int FRAME_INTERVAL_MS = 1000 / STREAM_FPS; // 100ms
    
    private final IBinder binder = new LocalBinder();
    
    // WebSocket
    private Socket socket;
    private String serverUrl;
    private boolean isConnected = false;
    
    // Camera2
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Handler backgroundHandler;
    private HandlerThread backgroundThread;
    private TextureView cameraPreview;
    
    // 串流狀態
    private boolean isStreaming = false;
    private boolean streamingLock = false; // 防止重複指令
    private long lastFrameTime = 0; // FPS 節流
    private OkHttpClient httpClient;
    
    // 日誌回調
    private LogCallback logCallback;
    
    public class LocalBinder extends Binder {
        StreamService getService() {
            return StreamService.class.cast(StreamService.this);
        }
    }
    
    public interface LogCallback {
        void onLog(String message);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        
        startBackgroundThread();
        createNotificationChannel();
        log("✅ StreamService 已建立");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = createNotification("等待連接...");
        startForeground(NOTIFICATION_ID, notification);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // ========================================================================
    // WebSocket 連接
    // ========================================================================

    public void connect(String url) {
        this.serverUrl = url;
        
        try {
            IO.Options options = new IO.Options();
            options.forceNew = true;
            options.reconnection = true;
            
            socket = IO.socket(url, options);
            
            socket.on(Socket.EVENT_CONNECT, args -> {
                isConnected = true;
                log("✅ WebSocket 已連接");
                updateNotification("已連接");
                
                // 註冊 Android 裝置
                try {
                    JSONObject data = new JSONObject();
                    data.put("device_id", android.os.Build.MODEL);
                    socket.emit("android_register", data);
                } catch (JSONException e) {
                    log("❌ 註冊失敗: " + e.getMessage());
                }
                
                // 啟動心跳
                startHeartbeat();
            });
            
            socket.on(Socket.EVENT_DISCONNECT, args -> {
                isConnected = false;
                log("❌ WebSocket 已斷線");
                updateNotification("已斷線");
            });
            
            socket.on(Socket.EVENT_CONNECT_ERROR, args -> {
                log("❌ 連接錯誤: " + (args.length > 0 ? args[0].toString() : "unknown"));
            });
            
            // 監聽伺服器指令
            socket.on("cmd_start_stream", args -> {
                log("📹 收到串流指令");
                startStreaming();
            });
            
            socket.on("cmd_stop_stream", args -> {
                log("⏹️ 收到停止指令");
                stopStreaming();
            });
            
            socket.on("cmd_vibrate", args -> {
                try {
                    JSONObject data = (JSONObject) args[0];
                    int duration = data.optInt("duration", 500);
                    vibrateDevice(duration);
                    log("📳 震動 " + duration + "ms");
                } catch (Exception e) {
                    log("❌ 震動失敗: " + e.getMessage());
                }
            });
            
            socket.connect();
            log("🔌 正在連接到 " + url);
            
        } catch (URISyntaxException e) {
            log("❌ 網址錯誤: " + e.getMessage());
        }
    }

    public void disconnect() {
        if (socket != null) {
            socket.disconnect();
            socket.close();
            socket = null;
        }
        isConnected = false;
        stopStreaming();
        log("🔌 已斷線");
    }

    private void startHeartbeat() {
        backgroundHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isConnected && socket != null && socket.connected()) {
                    try {
                        JSONObject data = new JSONObject();
                        data.put("device_id", android.os.Build.MODEL);
                        socket.emit("android_heartbeat", data);
                    } catch (JSONException e) {
                        // Ignore
                    }
                    backgroundHandler.postDelayed(this, 10000); // 每 10 秒
                }
            }
        }, 10000);
    }

    // ========================================================================
    // Camera2 控制
    // ========================================================================

    public void setCameraPreview(TextureView textureView) {
        this.cameraPreview = textureView;
        
        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    openCamera();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    closeCamera();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
            });
        }
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        
        try {
            String cameraId = manager.getCameraIdList()[0]; // 使用後置相機
            
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            
            // 建立 ImageReader（用於擷取 JPEG 影格）
            imageReader = ImageReader.newInstance(
                    STREAM_WIDTH, 
                    STREAM_HEIGHT,
                    ImageFormat.JPEG,
                    2
            );
            
            imageReader.setOnImageAvailableListener(reader -> {
                if (isStreaming) {
                    // FPS 節流：確保每秒最多 10 張
                    long now = System.currentTimeMillis();
                    if (now - lastFrameTime < FRAME_INTERVAL_MS) {
                        return; // 跳過此影格
                    }
                    lastFrameTime = now;
                    
                    Image image = reader.acquireLatestImage();
                    if (image != null) {
                        uploadFrame(imageToByteArray(image));
                        image.close();
                    }
                }
            }, backgroundHandler);
            
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    createCaptureSession();
                    log("📷 相機已開啟");
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    log("📷 相機已斷線");
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    log("❌ 相機錯誤: " + error);
                }
            }, backgroundHandler);
            
        } catch (CameraAccessException | SecurityException e) {
            log("❌ 無法開啟相機: " + e.getMessage());
        }
    }

    private void createCaptureSession() {
        if (cameraDevice == null || cameraPreview == null) return;
        
        try {
            SurfaceTexture texture = cameraPreview.getSurfaceTexture();
            texture.setDefaultBufferSize(STREAM_WIDTH, STREAM_HEIGHT);
            
            Surface previewSurface = new Surface(texture);
            Surface imageSurface = imageReader.getSurface();
            
            cameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface, imageSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            startPreview();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            log("❌ 建立 Capture Session 失敗");
                        }
                    },
                    backgroundHandler
            );
        } catch (CameraAccessException e) {
            log("❌ 建立 Capture Session 錯誤: " + e.getMessage());
        }
    }

    private void startPreview() {
        if (cameraDevice == null || captureSession == null) return;
        
        try {
            SurfaceTexture texture = cameraPreview.getSurfaceTexture();
            Surface previewSurface = new Surface(texture);
            
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);
            
            if (isStreaming) {
                builder.addTarget(imageReader.getSurface());
            }
            
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            
            captureSession.setRepeatingRequest(builder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                              @NonNull CaptureRequest request,
                                              @NonNull TotalCaptureResult result) {
                    // 預覽完成
                }
            }, backgroundHandler);
            
        } catch (CameraAccessException e) {
            log("❌ 啟動預覽失敗: " + e.getMessage());
        }
    }

    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    // ========================================================================
    // 串流控制
    // ========================================================================

    private void startStreaming() {
        // 防止重複指令
        if (isStreaming || streamingLock) {
            log("⚠️ 串流已在執行中，忽略重複指令");
            return;
        }
        
        streamingLock = true;
        isStreaming = true;
        lastFrameTime = 0; // 重置節流計時器
        updateNotification("串流中...");
        startPreview(); // 重新啟動預覽（加入 ImageReader）
        log("📹 開始串流");
        
        // 500ms 後解鎖（防止誤觸）
        backgroundHandler.postDelayed(() -> streamingLock = false, 500);
    }

    private void stopStreaming() {
        // 防止重複指令
        if (!isStreaming) {
            log("⚠️ 串流未啟動，忽略停止指令");
            return;
        }
        
        isStreaming = false;
        streamingLock = false;
        updateNotification("已連接");
        startPreview(); // 重新啟動預覽（移除 ImageReader）
        log("⏹️ 停止串流");
    }

    private byte[] imageToByteArray(Image image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private void uploadFrame(byte[] jpegData) {
        if (serverUrl == null || jpegData == null) return;
        
        RequestBody body = RequestBody.create(jpegData, MediaType.parse("image/jpeg"));
        Request request = new Request.Builder()
                .url(serverUrl + "/upload_frame")
                .post(body)
                .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                // 忽略上傳失敗（避免日誌爆炸）
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                response.close();
            }
        });
    }

    // ========================================================================
    // 震動功能（透過通知）
    // ========================================================================

    private void vibrateDevice(int durationMs) {
        // Android 16 建議：透過通知觸發震動
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 建立震動通知頻道
            NotificationChannel channel = new NotificationChannel(
                    "vibrate_channel",
                    "呼叫通知",
                    NotificationManager.IMPORTANCE_HIGH
            );
            
            // 設定震動模式
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, durationMs});
            
            // 設定通知聲音（貓咪喵喵聲 - 使用系統預設）
            channel.setSound(
                    android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                    null
            );
            
            manager.createNotificationChannel(channel);
            
            // 發送通知
            Notification notification = new NotificationCompat.Builder(this, "vibrate_channel")
                    .setContentTitle("📳 呼叫通知")
                    .setContentText("遠端裝置正在呼叫...")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build();
            
            manager.notify(999, notification);
            
        } else {
            // Android 7 及以下：直接呼叫震動
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(durationMs);
            }
        }
    }

    // ========================================================================
    // 通知與日誌
    // ========================================================================

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "串流服務",
                    NotificationManager.IMPORTANCE_LOW
            );
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification(String content) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Android Stream Client")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }

    private void updateNotification(String content) {
        Notification notification = createNotification(content);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification);
    }

    private void log(String message) {
        Log.d(TAG, message);
        if (logCallback != null) {
            logCallback.onLog(message);
        }
    }

    public void setLogCallback(LogCallback callback) {
        this.logCallback = callback;
    }

    public boolean isConnected() {
        return isConnected;
    }

    // ========================================================================
    // 背景執行緒
    // ========================================================================

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disconnect();
        closeCamera();
        stopBackgroundThread();
        log("🛑 StreamService 已停止");
    }
}
