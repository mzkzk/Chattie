package com.example.socketchatapp;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import com.example.socketchatapp.databinding.ActivityVideoCallBinding;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.content.Intent;

public class VideoCallActivity extends AppCompatActivity {
    private ActivityVideoCallBinding binding;
    private static final String TAG = "VideoCallActivity";
    private TextureView textureView;
    private ImageView remoteVideoView;
    private ExecutorService executor = Executors.newFixedThreadPool(4);
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder captureRequestBuilder;
    private Size previewSize;
    private String cameraId;
    private boolean isFrontCamera = true;
    private boolean isCalling = false;
    private boolean isMuted = false;
    private boolean isSpeakerphoneOn = false;
    private SocketService socketService;
    private boolean isServiceBound = false;
    private DataInputStream dis;
    private DataOutputStream dos;
    private String serverIp, roomId, username;
    private Handler handler = new Handler();
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private int serverPort,clientId;
    private boolean shouldStartCall = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_call);

        binding = ActivityVideoCallBinding.inflate(getLayoutInflater());
        textureView = findViewById(R.id.textureView);
        remoteVideoView = findViewById(R.id.remoteVideoView);

        setupCamera();

        serverIp = getSharedPreferences("app_settings", MODE_PRIVATE).getString("server_ip", "");
        roomId = getSharedPreferences("app_settings", MODE_PRIVATE).getString("room_id", "general");
        username = getSharedPreferences("app_settings", MODE_PRIVATE).getString("username", "User");
        serverPort = getIntent().getIntExtra("SERVER_PORT", 12347); // Use correct default for video
        clientId = getIntent().getIntExtra("CLIENT_ID", 1);

        // Bind to SocketService
        Intent serviceIntent = new Intent(this, SocketService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        if (checkPermissions()) {
            shouldStartCall = true;
            // setupCall() will be called in onServiceConnected
        } else {
            requestPermissions();
        }

        findViewById(R.id.btnMute).setOnClickListener(v -> toggleMute());
        findViewById(R.id.btnSwitchCamera).setOnClickListener(v -> switchCamera());
        findViewById(R.id.btnEndCall).setOnClickListener(v -> endCall());
        findViewById(R.id.btnSpeaker).setOnClickListener(v -> {
            toggleSpeakerphone();
            updateSpeakerButton();
        });
        updateSpeakerButton();
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            SocketService.LocalBinder binder = (SocketService.LocalBinder) service;
            socketService = binder.getService();
            isServiceBound = true;
            // Only proceed if the video and audio services are already connected
            executor.execute(() -> {
                try {
                    if (!socketService.isVideoConnected()) {
                        socketService.connectVideo(serverIp, serverPort, clientId, roomId, username);
                    }
                    if (!socketService.isAudioConnected()) {
                        socketService.connectAudio(serverIp, 12346, clientId, roomId, username); // Connect audio socket for video call
                    }
                    runOnUiThread(() -> {
                        if (shouldStartCall) {
                            setupCall();
                            shouldStartCall = false;
                        }
                    });
                } catch (IOException e) {
                    runOnUiThread(() -> {
                        Toast.makeText(VideoCallActivity.this, "فشل الاتصال بالسيرفر: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        finish();
                    });
                }
            });
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            isServiceBound = false;
            socketService = null;
        }
    };

    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) ==
                        PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.MODIFY_AUDIO_SETTINGS) ==
                        PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this,
                new String[]{
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.INTERNET,
                        Manifest.permission.MODIFY_AUDIO_SETTINGS
                }, 102);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 102 && grantResults.length > 3 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                grantResults[1] == PackageManager.PERMISSION_GRANTED &&
                grantResults[2] == PackageManager.PERMISSION_GRANTED &&
                grantResults[3] == PackageManager.PERMISSION_GRANTED) {
            shouldStartCall = true;
            // setupCall() will be called in onServiceConnected
        } else {
            Toast.makeText(this, "يجب منح جميع الصلاحيات", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void setupCall() {
        isCalling = true;
        startCallTimer();
        executor.execute(() -> {
            try {
                dis = socketService.getVideoInputStream();
                dos = socketService.getVideoOutputStream();
                if (dis == null || dos == null) throw new IOException("لم يتم الاتصال بالخادم");
                startVideoStreaming();
                startAudioStreaming();
                runOnUiThread(() ->
                        Toast.makeText(this, "تم الاتصال بنجاح", Toast.LENGTH_SHORT).show());
            } catch (IOException e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "فشل الاتصال بالسيرفر", Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        });
    }

    private void setupAudioMode() {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(isSpeakerphoneOn);
            audioManager.setMicrophoneMute(false);

            // ضبط مستوى الصوت لأعلى قيمة
            audioManager.setStreamVolume(
                    AudioManager.STREAM_VOICE_CALL,
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                    0
            );
        }
    }

    private void startCallTimer() {
        handler.postDelayed(new Runnable() {
            long startTime = System.currentTimeMillis();

            @Override
            public void run() {
                if (isCalling) {
                    long elapsedMillis = System.currentTimeMillis() - startTime;
                    updateCallDuration(elapsedMillis);
                    handler.postDelayed(this, 1000);
                }
            }
        }, 1000);
    }

    private void updateCallDuration(long millis) {
        int seconds = (int) (millis / 1000);
        int minutes = seconds / 60;
        seconds = seconds % 60;
        String time = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    private void startNetworkMonitoring() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isCalling) {
                    checkNetworkConnection();
                    handler.postDelayed(this, 5000);
                }
            }
        }, 5000);
    }

    private void checkNetworkConnection() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        if (activeNetwork == null || !activeNetwork.isConnected()) {
            runOnUiThread(() -> {
                Toast.makeText(this, "فقدان الاتصال بالإنترنت", Toast.LENGTH_SHORT).show();
                endCall();
            });
        }
    }

    private void connectToServers() {
        executor.execute(() -> {
            try {
                dis = socketService.getVideoInputStream();
                dos = socketService.getVideoOutputStream();
                if (dis == null || dos == null) throw new IOException("لم يتم الاتصال بالخادم");
                // Send handshake as required by server
                dos.writeUTF(username + ":" + roomId);
                dos.flush();
                startVideoStreaming();
                startAudioStreaming();
                runOnUiThread(() ->
                        Toast.makeText(this, "تم الاتصال بنجاح", Toast.LENGTH_SHORT).show());
            } catch (IOException e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "فشل الاتصال بالسيرفر", Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        });
    }

    private void startVideoStreaming() {
        executor.execute(this::sendVideoFrames);
        executor.execute(this::receiveVideoFrames);
    }

    private void startAudioStreaming() {
        executor.execute(this::sendAudioData);
        executor.execute(this::receiveAudioData);
    }

    private void sendVideoFrames() {
        try {
            DataOutputStream dos = new DataOutputStream(socketService.getVideoOutputStream());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            while (isCalling && socketService.isVideoConnected()) {
                Log.d(TAG, "Attempting to capture frame...");
                Bitmap frame = textureView.getBitmap();
                if (frame != null) {
                    Log.d(TAG, "Captured frame, size: " + frame.getWidth() + "x" + frame.getHeight());
                    baos.reset();
                    boolean compressed = frame.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                    if (!compressed) {
                        Log.e(TAG, "Failed to compress frame!");
                    } else {
                    byte[] frameData = baos.toByteArray();
                        Log.d(TAG, "Compressed frame size: " + frameData.length);
                    dos.writeInt(frameData.length);
                    dos.write(frameData);
                    dos.flush();
                        Log.d(TAG, "Sent frame of size: " + frameData.length);
                    }
                } else {
                    Log.e(TAG, "Frame is null!");
                }
                Thread.sleep(33);
            }
        } catch (java.net.SocketException e) {
            if (isCalling) {
                Log.e(TAG, "Video send error", e);
            }
            // else: ignore, shutting down
        } catch (Exception e) {
            Log.e(TAG, "Video send error", e);
        }
    }

    private void receiveVideoFrames() {
        try {
            DataInputStream dis = new DataInputStream(socketService.getVideoInputStream());
            while (isCalling && socketService.isVideoConnected()) {
                int frameSize = dis.readInt();
                byte[] frameData = new byte[frameSize];
                dis.readFully(frameData);

                Bitmap frame = BitmapFactory.decodeByteArray(frameData, 0, frameSize);
                runOnUiThread(() -> remoteVideoView.setImageBitmap(frame));
            }
        } catch (java.net.SocketException e) {
            if (isCalling) {
                Log.e(TAG, "Video receive error", e);
            }
            // else: ignore, shutting down
        } catch (Exception e) {
            Log.e(TAG, "Video receive error", e);
        }
    }

    private void sendAudioData() {
        try {
            int sampleRate = 44100;
            int bufferSize = AudioRecord.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
            );

            audioRecord.startRecording();
            byte[] buffer = new byte[bufferSize];
            OutputStream os = socketService.getAudioOutputStream(); // <-- Use audio stream

            while (isCalling && socketService.isAudioConnected()) { // <-- Use audio connection check
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    os.write(buffer, 0, read);
                    os.flush();
                }
            }
        } catch (java.net.SocketException e) {
            if (isCalling) {
                Log.e(TAG, "Audio send error", e);
            }
            // else: ignore, shutting down
        } catch (Exception e) {
            Log.e(TAG, "Audio send error", e);
        }
    }

    private void receiveAudioData() {
        try {
            int sampleRate = 44100;
            int bufferSize = AudioRecord.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            audioTrack = new AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
            );
            audioTrack.play();
            InputStream is = socketService.getAudioInputStream(); // <-- Use audio stream
            byte[] buffer = new byte[bufferSize];
            int read;
            while (isCalling && socketService.isAudioConnected() && (read = is.read(buffer)) != -1) { // <-- Use audio connection check
                audioTrack.write(buffer, 0, read);
                }
        } catch (java.net.SocketException e) {
            if (isCalling) {
                Log.e(TAG, "Audio receive error", e);
            }
            // else: ignore, shutting down
        } catch (Exception e) {
            Log.e(TAG, "Audio receive error", e);
        }
    }

    private void setupCamera() {
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                openCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {}

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            cameraId = getCameraId(manager);
            previewSize = new Size(640, 480);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED) {
                return;
            }

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    createPreviewSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    cleanup();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    cleanup();
                }
            }, null);
        } catch (Exception e) {
            runOnUiThread(() -> {
                Toast.makeText(this, "فشل فتح الكاميرا", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Failed to open camera", e);
            });
            finish();
        }
    }

    private String getCameraId(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (isFrontCamera && facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT)
                return id;
            else if (!isFrontCamera && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK)
                return id;
        }
        return manager.getCameraIdList()[0];
    }

    private void createPreviewSession() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(texture);

            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);

            cameraDevice.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            captureSession = session;
                            try {
                                captureSession.setRepeatingRequest(
                                        captureRequestBuilder.build(), null, null);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Failed to start camera preview", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            runOnUiThread(() ->
                                    Toast.makeText(VideoCallActivity.this, "فشل تهيئة الكاميرا", Toast.LENGTH_SHORT).show());
                        }
                    }, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to create camera session", e);
        }
    }

    private void toggleMute() {
        isMuted = !isMuted;
    }

    private void toggleSpeakerphone() {
        isSpeakerphoneOn = !isSpeakerphoneOn;
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setSpeakerphoneOn(isSpeakerphoneOn);
        }
    }

    private void switchCamera() {
        isFrontCamera = !isFrontCamera;
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        openCamera();
    }

    private void endCall() {
        isCalling = false; // Stop threads before closing socket
        handler.removeCallbacksAndMessages(null);
        // Send disconnect message before shutting down executor
        if (dos != null) {
            executor.execute(() -> {
                try {
                    dos.writeUTF("DISCONNECT:" + clientId);
                    dos.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }
        if (isServiceBound && socketService != null) {
            socketService.disconnectVideo();
        }
        cleanup(); // This will call executor.shutdownNow()
        finish();
    }

    private void cleanup() {
        isCalling = false; // Stop threads before closing socket
        try {
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (socketService != null && !socketService.isVideoConnected()) {
                socketService.disconnectVideo();
            }
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            }
            if (audioTrack != null) {
                audioTrack.stop();
                audioTrack.release();
                audioTrack = null;
            }

            AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audioManager != null) {
                audioManager.setMode(AudioManager.MODE_NORMAL);
            }
        } catch (Exception e) {
            Log.e(TAG, "Cleanup error", e);
        }
        executor.shutdownNow();
    }

    private void updateSpeakerButton() {
        runOnUiThread(() -> {
            if (binding != null && binding.getRoot() != null) {
                android.widget.Button btn = findViewById(R.id.btnSpeaker);
                if (btn instanceof com.google.android.material.button.MaterialButton) {
                    com.google.android.material.button.MaterialButton mb = (com.google.android.material.button.MaterialButton) btn;
                    mb.setIconResource(isSpeakerphoneOn ? R.drawable.ic_speaker_on : R.drawable.ic_speaker_off);
                    mb.setText(isSpeakerphoneOn ? "إيقاف السماعة" : "تشغيل السماعة");
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (isServiceBound && socketService != null) {
            socketService.disconnectVideo();
            unbindService(serviceConnection);
            isServiceBound = false;
        }
        super.onDestroy();
        // Don't call endCall() here as it tries to use executor after shutdown
        // Just call cleanup() directly
        cleanup();
    }
}