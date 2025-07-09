package com.example.socketchatapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.textfield.TextInputLayout;
import java.io.IOException;
import android.os.Build;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

public class MainActivity extends AppCompatActivity {
    private EditText serverIpInput;
    private EditText usernameInput;
    private EditText roomIdInput; // removed portInput
    private SharedPreferences prefs;
    private static final int DEFAULT_PORT = 12345;
    private static final int CLIENT_1_PORT = 1234;
    private static final int CLIENT_2_PORT = 12340;
    private Button connectBtn;
    private boolean isConnected = false;
    private int clientId = 1; // Default client ID
    private SocketService socketService;
    private boolean isServiceBound = false;
    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // تهيئة العناصر
        TextInputLayout serverIpLayout = findViewById(R.id.serverIpLayout);
        TextInputLayout usernameLayout = findViewById(R.id.usernameLayout);
        TextInputLayout roomIdLayout = findViewById(R.id.roomIdLayout);

        serverIpInput = serverIpLayout.getEditText();
        usernameInput = usernameLayout.getEditText();
        roomIdInput = roomIdLayout.getEditText();

        Button settingsBtn = findViewById(R.id.settingsBtn);
        connectBtn = findViewById(R.id.connectBtn);

        // تحميل الإعدادات
        prefs = getSharedPreferences("app_settings", MODE_PRIVATE);
        serverIpInput.setText(prefs.getString("server_ip", "192.168.197.126"));
        usernameInput.setText(prefs.getString("username", ""));
        roomIdInput.setText(prefs.getString("room_id", "general"));

        // إعداد الأزرار
        settingsBtn.setOnClickListener(v -> {
            Toast.makeText(this, "فتح الإعدادات", Toast.LENGTH_SHORT).show();
        });
        connectBtn.setOnClickListener(v -> connectToServer());
    }

    private void startChat() {
        if (!isConnected) {
            Toast.makeText(this, "يرجى الاتصال بالخادم أولاً", Toast.LENGTH_SHORT).show();
            return;
        }
        if (validateInputs()) {
            Intent intent = new Intent(this, ChatActivity.class);
            intent.putExtra("SERVER_IP", serverIpInput.getText().toString().trim());
            intent.putExtra("SERVER_PORT", DEFAULT_PORT); // always use default port
            intent.putExtra("USERNAME", usernameInput.getText().toString().trim());
            intent.putExtra("ROOM_ID", roomIdInput.getText().toString().trim());
            intent.putExtra("CLIENT_ID", clientId);
            saveSettings();
            startActivity(intent);
        }
    }

    private boolean validateInputs() {
        if (serverIpInput.getText().toString().trim().isEmpty() ||
                usernameInput.getText().toString().trim().isEmpty()) {
            Toast.makeText(this, "الرجاء إدخال جميع البيانات المطلوبة", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void saveSettings() {
        prefs.edit()
                .putString("server_ip", serverIpInput.getText().toString().trim())
                .putString("username", usernameInput.getText().toString().trim())
                .putString("room_id", roomIdInput.getText().toString().trim())
                .putInt("server_port", DEFAULT_PORT)
                .apply();
    }


    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "onServiceConnected: SocketService bound");
            SocketService.LocalBinder binder = (SocketService.LocalBinder) service;
            socketService = binder.getService();
            isServiceBound = true;
            // Connect and handshake
            new Thread(() -> {
                try {
                    socketService.connectChat(serverIpInput.getText().toString().trim(),DEFAULT_PORT, clientId, roomIdInput.getText().toString().trim(), usernameInput.getText().toString().trim());
                    runOnUiThread(() -> {
                        isConnected = socketService.isChatConnected();
                        Toast.makeText(MainActivity.this, "تم الاتصال بالخادم بنجاح", Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "Connection and handshake successful");
                        // Open ChatActivity immediately after successful connection
                        startChat();
                    });
                } catch (IOException e) {
                    Log.e(TAG, "connectChat failed: " + e.getMessage());
                    runOnUiThread(() -> {
                        isConnected = false;
                        Toast.makeText(MainActivity.this, "فشل الاتصال بالخادم: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                }
            }).start();
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "onServiceDisconnected: SocketService unbound");
            isServiceBound = false;
            socketService = null;
        }
    };

    private void connectToServer() {
        Log.d(TAG, "connectToServer called");
        String ip = serverIpInput.getText().toString().trim();
        String username = usernameInput.getText().toString().trim();
        String roomId = roomIdInput.getText().toString().trim();
        int port = DEFAULT_PORT;
        Log.d(TAG, "User input: ip=" + ip + ", port=" + port + ", username=" + username + ", roomId=" + roomId);
        if (ip.isEmpty() || username.isEmpty() || roomId.isEmpty()) {
            Log.e(TAG, "Missing required input");
            Toast.makeText(this, "يرجى إدخال جميع البيانات المطلوبة", Toast.LENGTH_SHORT).show();
            return;
        }
        Log.d(TAG, "Starting SocketService");
        Intent serviceIntent = new Intent(this, SocketService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Log.d(TAG, "Binding to SocketService");
        Intent bindIntent = new Intent(this, SocketService.class);
        bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isServiceBound) {
            unbindService(serviceConnection);
            isServiceBound = false;
        }
        // If SocketService is bound, its onDestroy will handle cleanup.
    }
}