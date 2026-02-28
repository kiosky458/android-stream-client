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
    
    public interface FrameCallback {
        void onFrameAvailable(byte[] jpegData);
        void onError(String error);
    }
    
    public CameraStreamManager(Context context) {
        this.context = context;
    }
    
    public void setFrameCallback(FrameCallback callback) {
        this.frameCallback = callback;
    }
    
    public void startCamera() {
        startBackgroundThread();
        
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = manager.getCameraIdList()[0]; // 後鏡頭
            
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Size[] sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(ImageFormat.YUV_420_888);
            
            // 選擇接近 768x1024 的解析度
            Size selectedSize = sizes[0];
            for (Size size : sizes) {
                if (size.getWidth() <= 1024 && size.getHeight() <= 1024) {
                    selectedSize = size;
                    break;
                }
            }
            
            imageReader = ImageReader.newInstance(
                selectedSize.getWidth(),
                selectedSize.getHeight(),
                ImageFormat.YUV_420_888,
                2
            );
            
            imageReader.setOnImageAvailableListener(reader -> {
                if (!isStreaming) return;
                
                Image image = reader.acquireLatestImage();
                if (image != null) {
                    byte[] jpegData = convertYUVtoJPEG(image);
                    image.close();
                    
                    if (jpegData != null && frameCallback != null) {
                        frameCallback.onFrameAvailable(jpegData);
                    }
                }
            }, backgroundHandler);
            
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    createCaptureSession();
                }
                
                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }
                
                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    if (frameCallback != null) {
                        frameCallback.onError("相機錯誤: " + error);
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
        try {
            cameraDevice.createCaptureSession(
                java.util.Arrays.asList(imageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        captureSession = session;
                        startStreaming();
                    }
                    
                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        if (frameCallback != null) {
                            frameCallback.onError("相機設定失敗");
                        }
                    }
                },
                backgroundHandler
            );
        } catch (CameraAccessException e) {
            if (frameCallback != null) {
                frameCallback.onError("建立相機 session 失敗");
            }
        }
    }
    
    public void startStreaming() {
        if (captureSession == null || cameraDevice == null) return;
        
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(imageReader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            
            captureSession.setRepeatingRequest(builder.build(), null, backgroundHandler);
            isStreaming = true;
            
        } catch (CameraAccessException e) {
            if (frameCallback != null) {
                frameCallback.onError("啟動串流失敗");
            }
        }
    }
    
    public void stopStreaming() {
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
            yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 70, out);
            
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
