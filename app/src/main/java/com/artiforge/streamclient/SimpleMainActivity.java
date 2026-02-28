package com.artiforge.streamclient;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class SimpleMainActivity extends AppCompatActivity {

    private EditText serverUrlInput;
    private Button connectBtn;
    private TextView statusText;
    private TextView logText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            setContentView(R.layout.activity_simple);
            
            // 初始化 UI
            serverUrlInput = findViewById(R.id.serverUrlInput);
            connectBtn = findViewById(R.id.connectBtn);
            statusText = findViewById(R.id.statusText);
            logText = findViewById(R.id.logText);
            
            // 測試按鈕
            connectBtn.setOnClickListener(v -> {
                String url = serverUrlInput.getText().toString();
                Toast.makeText(this, "伺服器: " + url, Toast.LENGTH_SHORT).show();
                logText.setText("測試成功！\n伺服器: " + url);
            });
            
            logText.setText("✅ App 啟動成功！\n版本: 1.0 (Simple)\n請輸入伺服器網址並測試連接");
            
        } catch (Exception e) {
            // 顯示錯誤
            Toast.makeText(this, "錯誤: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }
}
