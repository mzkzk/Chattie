package com.example.socketchatapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import android.util.Log;

public class SocketService extends Service {
    public static final String CHANNEL_ID = "SocketServiceChannel";
    private final IBinder binder = new LocalBinder();
    // Chat socket/streams
    private Socket chatSocket;
    private DataInputStream chatDis;
    private DataOutputStream chatDos;
    private boolean isChatConnected = false;
    private String chatRoomId;
    private String chatUsername;
    private int chatClientId;
    // Audio socket/streams
    private Socket audioSocket;
    private DataInputStream audioDis;
    private DataOutputStream audioDos;
    private boolean isAudioConnected = false;
    // Video socket/streams
    private Socket videoSocket;
    private DataInputStream videoDis;
    private DataOutputStream videoDos;
    private boolean isVideoConnected = false;
    private static final String TAG = "SocketService";
    private ConnectionListener connectionListener;

    public interface ConnectionListener {
        void onConnected();
        void onDisconnected();
        void onError(String error);
    }

    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    public class LocalBinder extends Binder {
        public SocketService getService() {
            return SocketService.this;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Chattie Connected")
                .setContentText("المحادثة متصلة بالخادم")
                .setSmallIcon(R.drawable.ic_chat)
                .setContentIntent(pendingIntent)
                .build();
        startForeground(1, notification);
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Chattie Socket Service",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    // --- Chat ---
    public void connectChat(String ip, int port, int clientId, String roomId, String username) throws IOException {
        disconnectChat();
        chatSocket = new Socket(ip, port);
        chatDis = new DataInputStream(chatSocket.getInputStream());
        chatDos = new DataOutputStream(chatSocket.getOutputStream());
        chatClientId = clientId;
        chatRoomId = roomId;
        chatUsername = username;
        // Handshake: send username:roomId as a single UTF string
        chatDos.writeUTF(username + ":" + roomId);
        chatDos.flush();
        isChatConnected = true;
    }
    public void disconnectChat() {
        try {
            if (chatDis != null) chatDis.close();
            if (chatDos != null) chatDos.close();
            if (chatSocket != null) chatSocket.close();
        } catch (IOException ignored) {}
        chatDis = null;
        chatDos = null;
        chatSocket = null;
        isChatConnected = false;
    }
    public boolean isChatConnected() {
        return isChatConnected && chatSocket != null && chatSocket.isConnected() && !chatSocket.isClosed();
    }
    public DataInputStream getChatInputStream() { return chatDis; }
    public DataOutputStream getChatOutputStream() { return chatDos; }

    // --- Audio ---
    public void connectAudio(String ip, int port, int clientId, String roomId, String username) throws IOException {
        disconnectAudio();
        audioSocket = new Socket(ip, port);
        audioDis = new DataInputStream(audioSocket.getInputStream());
        audioDos = new DataOutputStream(audioSocket.getOutputStream());
        // Handshake: send username:roomId as a single UTF string
        audioDos.writeUTF(username + ":" + roomId);
        audioDos.flush();
        isAudioConnected = true;
    }
    public void disconnectAudio() {
        try {
            if (audioDis != null) audioDis.close();
            if (audioDos != null) audioDos.close();
            if (audioSocket != null) audioSocket.close();
        } catch (IOException ignored) {}
        audioDis = null;
        audioDos = null;
        audioSocket = null;
        isAudioConnected = false;
    }
    public boolean isAudioConnected() {
        return isAudioConnected && audioSocket != null && audioSocket.isConnected() && !audioSocket.isClosed();
    }
    public DataInputStream getAudioInputStream() { return audioDis; }
    public DataOutputStream getAudioOutputStream() { return audioDos; }

    // --- Video ---
    public void connectVideo(String ip, int port, int clientId, String roomId, String username) throws IOException {
        disconnectVideo();
        videoSocket = new Socket(ip, port);
        videoDis = new DataInputStream(videoSocket.getInputStream());
        videoDos = new DataOutputStream(videoSocket.getOutputStream());
        // Handshake: send username:roomId as a single UTF string
        videoDos.writeUTF(username + ":" + roomId);
        videoDos.flush();
        isVideoConnected = true;
    }
    public void disconnectVideo() {
        try {
            if (videoDis != null) videoDis.close();
            if (videoDos != null) videoDos.close();
            if (videoSocket != null) videoSocket.close();
        } catch (IOException ignored) {}
        videoDis = null;
        videoDos = null;
        videoSocket = null;
        isVideoConnected = false;
    }
    public boolean isVideoConnected() {
        return isVideoConnected && videoSocket != null && videoSocket.isConnected() && !videoSocket.isClosed();
    }
    public DataInputStream getVideoInputStream() { return videoDis; }
    public DataOutputStream getVideoOutputStream() { return videoDos; }

    @Override
    public void onDestroy() {
        disconnectChat();
        disconnectAudio();
        disconnectVideo();
        super.onDestroy();
    }
} 