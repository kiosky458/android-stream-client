package com.artiforge.streamclient;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class CameraStreamManager {
    
    private Context context;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private FrameCallback frameCallback;
    
    private boolean isStreaming = false;
    private long lastFrameTime = 0;
    private static final long FRAME_INTERVAL_MS = 100; // 最快 10 FPS
    
    public interface FrameCallback {
        void onFrameAvailable(byte[] jpegData);
        void onError(String error);
        void onInfo(String message);
    }
    
    public CameraStreamManager(Context context) {
        this.context = context;
    }
    
    public void setFrameCallback(FrameCallback callback) {
        this.frameCallback = callback;
    }
    
    public void startCamera() {
        if (frameCallback != null) {
            frameCallback.onInfo("🎬 開始初始化相機...");
        }
        
        startBackgroundThread();
        
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            // 列出所有相機
            String[] cameraIds = manager.getCameraIdList();
            if (frameCallback != null) {
                frameCallback.onInfo("📷 找到 " + cameraIds.length + " 個相機");
            }
            
            String cameraId = cameraIds[0]; // 後鏡頭
            if (frameCallback != null) {
                frameCallback.onInfo("🎯 使用相機 ID: " + cameraId);
            }
            
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            
            // 檢查相機支援的格式
            int[] formats = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputFormats();
            if (frameCallback != null) {
                frameCallback.onInfo("📋 支援格式數: " + formats.length);
            }
            
            // 改用 JPEG 格式（更穩定，相容性更好）
            Size[] sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(ImageFormat.JPEG);
            
            if (frameCallback != null) {
                frameCallback.onInfo("📐 可用解析度數: " + sizes.length);
            }
            
            // 選擇接近 480x640 的解析度
            Size selectedSize = sizes[0]; // 預設第一個
            int targetWidth = 480;
            int targetHeight = 640;
            int minDiff = Integer.MAX_VALUE;
            
            for (Size size : sizes) {
                int diff = Math.abs(size.getWidth() - targetWidth) + Math.abs(size.getHeight() - targetHeight);
                if (diff < minDiff && size.getWidth() <= 1280 && size.getHeight() <= 960) {
                    minDiff = diff;
                    selectedSize = size;
                }
            }
            
            if (frameCallback != null) {
                frameCallback.onInfo("✅ 選擇解析度: " + selectedSize.getWidth() + "x" + selectedSize.getHeight());
            }
            
            if (frameCallback != null) {
                frameCallback.onInfo("🔧 建立 ImageReader...");
            }
            
            imageReader = ImageReader.newInstance(
                selectedSize.getWidth(),
                selectedSize.getHeight(),
                ImageFormat.JPEG,
                2
            );
            
            if (frameCallback != null) {
                frameCallback.onInfo("✅ ImageReader 建立成功");
            }
            
            imageReader.setOnImageAvailableListener(reader -> {
                if (!isStreaming) return;
                
                // 節流：限制幀率
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastFrameTime < FRAME_INTERVAL_MS) {
                    Image img = reader.acquireLatestImage();
                    if (img != null) img.close(); // 丟棄此幀
                    return;
                }
                lastFrameTime = currentTime;
                
                Image image = reader.acquireLatestImage();
                if (image != null) {
                    // JPEG 格式：直接讀取 bytes
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] jpegData = new byte[buffer.remaining()];
                    buffer.get(jpegData);
                    image.close();
                    
                    if (jpegData != null && jpegData.length > 0 && frameCallback != null) {
                        frameCallback.onFrameAvailable(jpegData);
                    }
                }
            }, backgroundHandler);
            
            if (frameCallback != null) {
                frameCallback.onInfo("🔓 請求開啟相機...");
            }
            
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    if (frameCallback != null) {
                        frameCallback.onInfo("✅ 相機已開啟（ID: " + camera.getId() + "）");
                    }
                    createCaptureSession();
                }
                
                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    if (frameCallback != null) {
                        frameCallback.onInfo("⚠️ 相機已斷線");
                    }
                    camera.close();
                    cameraDevice = null;
                }
                
                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    if (frameCallback != null) {
                        frameCallback.onInfo("❌ 相機回調錯誤（onError 觸發）");
                        frameCallback.onInfo("   錯誤碼: " + error);
                        frameCallback.onInfo("   相機 ID: " + camera.getId());
                    }
                    
                    camera.close();
                    cameraDevice = null;
                    
                    String errorMsg = "相機錯誤 " + error + ": ";
                    switch (error) {
                        case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                            errorMsg += "相機正被其他應用使用\n解決: 關閉其他相機 App";
                            break;
                        case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                            errorMsg += "已達相機使用上限\n解決: 關閉其他使用相機的 App";
                            break;
                        case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                            errorMsg += "相機已被停用\n解決: 檢查裝置政策設定";
                            break;
                        case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                            errorMsg += "相機硬體錯誤\n解決: 1) 重啟 App 2) 重啟手機 3) 檢查權限";
                            break;
                        case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                            errorMsg += "相機服務錯誤\n解決: 重啟手機";
                            break;
                        default:
                            errorMsg += "未知錯誤";
                    }
                    
                    if (frameCallback != null) {
                        frameCallback.onError(errorMsg);
                    }
                }
            }, backgroundHandler);
            
        } catch (CameraAccessException e) {
            if (frameCallback != null) {
                frameCallback.onError("相機存取失敗: " + e.getMessage());
            }
        } catch (SecurityException e) {
            if (frameCallback != null) {
                frameCallback.onError("缺少相機權限");
            }
        }
    }
    
    private void createCaptureSession() {
        if (frameCallback != null) {
            frameCallback.onInfo("🔧 建立 CaptureSession...");
        }
        
        try {
            if (cameraDevice == null) {
                if (frameCallback != null) {
                    frameCallback.onError("❌ cameraDevice 為 null");
                }
                return;
            }
            
            if (imageReader == null) {
                if (frameCallback != null) {
                    frameCallback.onError("❌ imageReader 為 null");
                }
                return;
            }
            
            cameraDevice.createCaptureSession(
                java.util.Arrays.asList(imageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        captureSession = session;
                        if (frameCallback != null) {
                            frameCallback.onInfo("✅ CaptureSession 建立成功");
                        }
                        startPreview();  // 改為啟動預覽（不上傳）
                    }
                    
                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        if (frameCallback != null) {
                            frameCallback.onError("❌ CaptureSession 配置失敗");
                        }
                    }
                    
                    @Override
                    public void onClosed(@NonNull CameraCaptureSession session) {
                        if (frameCallback != null) {
                            frameCallback.onInfo("⚠️ CaptureSession 已關閉");
                        }
                    }
                },
                backgroundHandler
            );
        } catch (CameraAccessException e) {
            if (frameCallback != null) {
                frameCallback.onError("❌ 建立 CaptureSession 失敗: " + e.getMessage());
            }
        } catch (IllegalStateException e) {
            if (frameCallback != null) {
                frameCallback.onError("❌ 相機狀態錯誤: " + e.getMessage());
            }
        }
    }
    
    private void startPreview() {
        if (frameCallback != null) {
            frameCallback.onInfo("🎥 啟動相機預覽...");
        }
        
        if (captureSession == null || cameraDevice == null) {
            if (frameCallback != null) {
                frameCallback.onError("❌ 無法啟動預覽（session 或 device 為 null）");
            }
            return;
        }
        
        try {
            // 使用 STILL_CAPTURE 模板（適合 JPEG）
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(imageReader.getSurface());
            
            // 自動對焦（連續）
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            
            // 自動曝光
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            
            // 自動白平衡
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            
            // JPEG 品質
            builder.set(CaptureRequest.JPEG_QUALITY, (byte) 85);
            
            if (frameCallback != null) {
                frameCallback.onInfo("📤 發送預覽請求...");
            }
            
            captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);
            
            if (frameCallback != null) {
                frameCallback.onInfo("✅ 相機預覽已啟動（待命中）");
            }
            
        } catch (CameraAccessException e) {
            if (frameCallback != null) {
                frameCallback.onError("❌ 啟動預覽失敗: " + e.getMessage());
            }
        } catch (IllegalStateException e) {
            if (frameCallback != null) {
                frameCallback.onError("❌ 相機狀態錯誤: " + e.getMessage());
            }
        }
    }
    
    public void startStreaming() {
        if (frameCallback != null) {
            frameCallback.onInfo("🚀 開始串流上傳...");
        }
        
        if (captureSession == null || cameraDevice == null) {
            if (frameCallback != null) {
                frameCallback.onError("❌ 相機未初始化（請重新連接）");
            }
            return;
        }
        
        isStreaming = true;
        
        if (frameCallback != null) {
            frameCallback.onInfo("✅ 串流已啟動 (JPEG, 10 FPS)");
        }
    }
    
    public void stopStreaming() {
        if (frameCallback != null) {
            frameCallback.onInfo("⏹️ 停止串流上傳（保持預覽）");
        }
        isStreaming = false;
    }
    
    public void stopCamera() {
        isStreaming = false;
        
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
        
        stopBackgroundThread();
    }
    
    private byte[] convertYUVtoJPEG(Image image) {
        try {
            ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
            ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
            ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();
            
            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();
            
            byte[] nv21 = new byte[ySize + uSize + vSize];
            
            yBuffer.get(nv21, 0, ySize);
            vBuffer.get(nv21, ySize, vSize);
            uBuffer.get(nv21, ySize + vSize, uSize);
            
            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 50, out);
            
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
    
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
}
