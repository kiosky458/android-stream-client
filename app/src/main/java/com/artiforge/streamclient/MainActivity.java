package com.artiforge.streamclient;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.view.TextureView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String[] REQUIRED_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.INTERNET,
            Manifest.permission.VIBRATE
    };

    private TextureView cameraPreview;
    private EditText serverUrlInput;
    private Button connectBtn;
    private TextView statusText;
    private TextView logText;

    private StreamService streamService;
    private boolean serviceBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            StreamService.LocalBinder binder = (StreamService.LocalBinder) service;
            streamService = binder.getService();
            serviceBound = true;
            streamService.setCameraPreview(cameraPreview);
            streamService.setLogCallback(MainActivity.this::appendLog);
            appendLog("✅ 服務已連接");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            streamService = null;
            appendLog("❌ 服務已斷線");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        checkPermissions();
    }

    private void initViews() {
        cameraPreview = findViewById(R.id.cameraPreview);
        serverUrlInput = findViewById(R.id.serverUrlInput);
        connectBtn = findViewById(R.id.connectBtn);
        statusText = findViewById(R.id.statusText);
        logText = findViewById(R.id.logText);

        connectBtn.setOnClickListener(v -> {
            if (serviceBound && streamService.isConnected()) {
                disconnect();
            } else {
                connect();
            }
        });
    }

    private void checkPermissions() {
        boolean allGranted = true;
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) 
                    != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
        } else {
            startStreamService();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                          @NonNull int[] grantResults) {
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
                startStreamService();
            } else {
                Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void startStreamService() {
        Intent serviceIntent = new Intent(this, StreamService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        appendLog("🚀 啟動服務");
    }

    private void connect() {
        String serverUrl = serverUrlInput.getText().toString().trim();
        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "請輸入伺服器網址", Toast.LENGTH_SHORT).show();
            return;
        }

        if (serviceBound && streamService != null) {
            streamService.connect(serverUrl);
            updateUI(true);
        }
    }

    private void disconnect() {
        if (serviceBound && streamService != null) {
            streamService.disconnect();
            updateUI(false);
        }
    }

    private void updateUI(boolean connected) {
        runOnUiThread(() -> {
            if (connected) {
                statusText.setText(R.string.status_connected);
                statusText.setBackgroundColor(0xCC00FF00);
                connectBtn.setText(R.string.disconnect);
            } else {
                statusText.setText(R.string.status_disconnected);
                statusText.setBackgroundColor(0xCCFF0000);
                connectBtn.setText(R.string.connect);
            }
        });
    }

    private void appendLog(String message) {
        runOnUiThread(() -> {
            String currentText = logText.getText().toString();
            String newText = message + "\n" + currentText;
            
            // 保留最多 500 個字元
            if (newText.length() > 500) {
                newText = newText.substring(0, 500);
            }
            
            logText.setText(newText);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }
}
