package com.artiforge.streamclient;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
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
        // v1.3.1: 相機狀態變化回調
        void onCameraStatusChanged(boolean available, String reason);
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
            
            // v1.2.6: 改用前鏡頭（LENS_FACING_FRONT）
            String cameraId = null;
            for (String id : cameraIds) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    cameraId = id;
                    break;
                }
            }
            
            // 如果沒有前鏡頭，使用第一個相機
            if (cameraId == null) {
                cameraId = cameraIds[0];
            }
            
            if (frameCallback != null) {
                frameCallback.onInfo("🎯 使用相機 ID: " + cameraId + " (前鏡頭)");
            }
            
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            
            // 檢查相機支援的格式
            int[] formats = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputFormats();
            if (frameCallback != null) {
                frameCallback.onInfo("📋 支援格式數: " + formats.length);
            }
            
            // 使用 YUV_420_888（PREVIEW 模板的正確格式）
            Size[] sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(ImageFormat.YUV_420_888);
            
            if (frameCallback != null) {
                frameCallback.onInfo("📐 可用解析度數: " + sizes.length);
                
                // 列出前 5 個常用解析度
                StringBuilder resolutions = new StringBuilder("   常用解析度: ");
                int count = 0;
                for (Size size : sizes) {
                    if (count >= 5) break;
                    if (size.getWidth() <= 1920 && size.getHeight() <= 1920) {
                        if (count > 0) resolutions.append(", ");
                        resolutions.append(size.getWidth()).append("x").append(size.getHeight());
                        count++;
                    }
                }
                frameCallback.onInfo(resolutions.toString());
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
                frameCallback.onInfo("✅ 選擇解析度: " + selectedSize.getWidth() + "x" + selectedSize.getHeight() + " (最接近 480x640)");
            }
            
            if (frameCallback != null) {
                frameCallback.onInfo("🔧 建立 ImageReader...");
            }
            
            imageReader = ImageReader.newInstance(
                selectedSize.getWidth(),
                selectedSize.getHeight(),
                ImageFormat.YUV_420_888,
                2
            );
            
            if (frameCallback != null) {
                frameCallback.onInfo("✅ ImageReader 建立成功");
            }
            
            imageReader.setOnImageAvailableListener(reader -> {
                if (!isStreaming) {
                    // 靜默丟棄（預覽模式）
                    Image img = reader.acquireLatestImage();
                    if (img != null) img.close();
                    return;
                }
                
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
                    // YUV 格式：轉換為 JPEG（靜默模式）
                    byte[] jpegData = convertYUVtoJPEG(image);
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
                        // v1.3.1: 通知相機可用
                        frameCallback.onCameraStatusChanged(true, "ready");
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
                    captureSession = null;
                    
                    String errorMsg = "相機錯誤 " + error + ": ";
                    boolean canRetry = false;
                    
                    switch (error) {
                        case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                            errorMsg += "相機正被其他應用使用\n解決: 關閉其他相機 App";
                            break;
                        case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                            errorMsg += "已達相機使用上限\n解決: 關閉其他使用相機的 App";
                            break;
                        case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                            errorMsg += "相機被系統停用（格式/模板不相容）\n⚠️ 嘗試自動恢復...";
                            canRetry = true;  // 錯誤 3 可以嘗試恢復
                            break;
                        case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                            errorMsg += "相機硬體錯誤\n⚠️ 嘗試自動恢復...";
                            canRetry = true;  // 錯誤 4 可以嘗試恢復
                            break;
                        case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                            errorMsg += "相機服務錯誤\n解決: 重啟手機";
                            break;
                        default:
                            errorMsg += "未知錯誤";
                    }
                    
                    if (frameCallback != null) {
                        frameCallback.onError(errorMsg);
                        // v1.3.1: 通知相機不可用（鎖定或錯誤）
                        frameCallback.onCameraStatusChanged(false, "locked");
                    }
                    
                    // 自動恢復（3 秒後重試）
                    if (canRetry && backgroundHandler != null) {
                        if (frameCallback != null) {
                            frameCallback.onInfo("🔄 3 秒後自動重新初始化相機...");
                        }
                        backgroundHandler.postDelayed(() -> {
                            if (frameCallback != null) {
                                frameCallback.onInfo("🔄 開始自動恢復...");
                            }
                            startCamera();
                        }, 3000);
                    }
                }
            }, backgroundHandler);
            
        } catch (CameraAccessException e) {
            if (frameCallback != null) {
                frameCallback.onError("相機存取失敗: " + e.getMessage());
                // v1.3.1: 通知相機不可用
                frameCallback.onCameraStatusChanged(false, "access_error");
            }
        } catch (SecurityException e) {
            if (frameCallback != null) {
                frameCallback.onError("缺少相機權限");
                // v1.3.1: 通知相機不可用（權限問題）
                frameCallback.onCameraStatusChanged(false, "permission_denied");
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
            // 改用 PREVIEW 模板（適合長時間預覽，不會被系統停用）
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(imageReader.getSurface());
            
            // 自動對焦（連續視訊對焦）
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            
            // 自動曝光
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            
            // 自動白平衡
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
            
            // 影像穩定（如果支援）
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, 
                       CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
            
            if (frameCallback != null) {
                frameCallback.onInfo("📤 發送預覽請求（PREVIEW 模板 + YUV 格式）...");
            }
            
            captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);
            
            if (frameCallback != null) {
                frameCallback.onInfo("✅ 相機預覽已啟動（PREVIEW + YUV，系統相容）");
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
                frameCallback.onError("❌ 相機未就緒");
                frameCallback.onInfo("💡 提示: 相機可能正在恢復中，請稍後再試");
            }
            return;
        }
        
        // 檢查相機設備是否仍然有效
        try {
            String deviceId = cameraDevice.getId();
            if (frameCallback != null) {
                frameCallback.onInfo("✅ 相機設備正常（ID: " + deviceId + "）");
            }
        } catch (Exception e) {
            if (frameCallback != null) {
                frameCallback.onError("❌ 相機設備已失效: " + e.getMessage());
            }
            return;
        }
        
        isStreaming = true;
        
        if (frameCallback != null) {
            frameCallback.onInfo("✅ 串流已啟動 (YUV→JPEG, 10 FPS)");
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
    
    /**
     * v1.2.7: YUV 轉 JPEG + 逆時針旋轉 90 度（直立格式 480x640）
     */
    private byte[] convertYUVtoJPEG(Image image) {
        try {
            // 1. YUV → JPEG（橫向 640x480）
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
            ByteArrayOutputStream tempOut = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 85, tempOut);
            byte[] jpegData = tempOut.toByteArray();
            
            // 2. JPEG → Bitmap
            Bitmap bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
            if (bitmap == null) {
                throw new Exception("無法解碼 JPEG 為 Bitmap");
            }
            
            // 3. 逆時針旋轉 90 度（橫向 640x480 → 直立 480x640）
            Matrix matrix = new Matrix();
            matrix.postRotate(-90); // 逆時針 90 度
            
            Bitmap rotatedBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, 
                bitmap.getWidth(), bitmap.getHeight(), 
                matrix, true
            );
            bitmap.recycle(); // 釋放原始 Bitmap
            
            // 4. Bitmap → JPEG
            ByteArrayOutputStream finalOut = new ByteArrayOutputStream();
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, finalOut);
            rotatedBitmap.recycle(); // 釋放旋轉後的 Bitmap
            
            return finalOut.toByteArray();
            
        } catch (Exception e) {
            if (frameCallback != null) {
                frameCallback.onError("❌ YUV→JPEG 錯誤: " + e.getMessage());
            }
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
