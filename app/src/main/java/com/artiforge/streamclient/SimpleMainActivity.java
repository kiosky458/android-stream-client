package com.artiforge.streamclient;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.net.URISyntaxException;

import io.socket.client.IO;
import io.socket.client.Socket;

public class SimpleMainActivity extends AppCompatActivity {

    private EditText serverUrlInput;
    private Button connectBtn;
    private TextView statusText;
    private TextView logText;
    
    private Socket socket;
    private boolean isConnected = false;
    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            setContentView(R.layout.activity_simple);
            
            mainHandler = new Handler(Looper.getMainLooper());
            
            // 初始化 UI
            serverUrlInput = findViewById(R.id.serverUrlInput);
            connectBtn = findViewById(R.id.connectBtn);
            statusText = findViewById(R.id.statusText);
            logText = findViewById(R.id.logText);
            
            // 連接按鈕
            connectBtn.setOnClickListener(v -> {
                if (isConnected) {
                    disconnect();
                } else {
                    connect();
                }
            });
            
            appendLog("✅ App 啟動成功！");
            appendLog("版本: 1.0.1 (WebSocket 測試版)");
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
                        deviceInfo.put("device_id", android.os.Build.MODEL);
                        deviceInfo.put("device_name", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
                        socket.emit("register_device", deviceInfo);
                        appendLog("📱 裝置已註冊");
                    } catch (Exception e) {
                        appendLog("❌ 註冊失敗: " + e.getMessage());
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
                    appendLog("❌ 連接錯誤: " + (args.length > 0 ? args[0].toString() : "未知錯誤"));
                });
            });
            
            socket.on("start_stream", args -> {
                mainHandler.post(() -> {
                    appendLog("📹 收到開始串流指令（相機功能尚未實作）");
                });
            });
            
            socket.on("stop_stream", args -> {
                mainHandler.post(() -> {
                    appendLog("🛑 收到停止串流指令");
                });
            });
            
            socket.on("vibrate", args -> {
                mainHandler.post(() -> {
                    appendLog("📳 收到震動指令");
                    // TODO: 實作震動功能
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
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
    }
}
