package com.example.socketchatapp;
import java.util.Locale;
import android.Manifest;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.example.socketchatapp.databinding.ActivityAudioCallBinding;
import java.io.*;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.media.AudioManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.content.Intent;

public class AudioCallActivity extends AppCompatActivity {
    private ActivityAudioCallBinding binding;
    private ExecutorService executor = Executors.newFixedThreadPool(2);
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private boolean isCalling = false;
    private boolean isMuted = false;
    private boolean isSpeakerOn = false;
    private Socket audioSocket;
    private String serverIp, roomId, username;
    private int serverPort, clientId;
    private CountDownTimer callTimer;
    private DataOutputStream dos;
    private DataInputStream dis;
    private SocketService socketService;
    private boolean isServiceBound = false;
    private boolean shouldStartCall = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAudioCallBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // استقبال البيانات من Intent
        serverIp = getIntent().getStringExtra("SERVER_IP");
        serverPort = getIntent().getIntExtra("SERVER_PORT", 12346);
        roomId = getIntent().getStringExtra("ROOM_ID");
        username = getIntent().getStringExtra("USERNAME");
        clientId = getIntent().getIntExtra("CLIENT_ID", 1);

        binding.toolbar.setTitle("مكالمة صوتية - غرفة: " + roomId + " | عميل: " + clientId);
        binding.toolbar.setSubtitle(username);
        setSupportActionBar(binding.toolbar);

        if (checkPermissions()) {
            shouldStartCall = true;
            // setupCall() will be called in onServiceConnected
        } else {
            requestPermissions();
        }

        binding.btnMute.setOnClickListener(v -> toggleMute());
        binding.btnEndCall.setOnClickListener(v -> endCall());
        binding.btnSpeaker.setOnClickListener(v -> toggleSpeaker());
        updateSpeakerButton();

        // Bind to SocketService
        Intent serviceIntent = new Intent(this, SocketService.class);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            SocketService.LocalBinder binder = (SocketService.LocalBinder) service;
            socketService = binder.getService();
            isServiceBound = true;
            // Only proceed if the audio service is already connected
            executor.execute(() -> {
                try {
                    if (!socketService.isAudioConnected()) {
                        socketService.connectAudio(serverIp, serverPort, clientId, roomId, username);
                    }
                    runOnUiThread(() -> {
                        if (shouldStartCall) {
                            setupCall();
                            shouldStartCall = false;
                        }
                    });
                } catch (IOException e) {
                    runOnUiThread(() -> {
                        Toast.makeText(AudioCallActivity.this, "فشل الاتصال بالسيرفر: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.MODIFY_AUDIO_SETTINGS) ==
                        PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this,
                new String[]{
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.MODIFY_AUDIO_SETTINGS
                }, 101);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101 && grantResults.length > 1 &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED &&
                grantResults[1] == PackageManager.PERMISSION_GRANTED) {
            shouldStartCall = true;
            // setupCall() will be called in onServiceConnected
        } else {
            Toast.makeText(this, "يجب منح جميع الصلاحيات المطلوبة", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void setupCall() {
        isCalling = true;
        startCallTimer();
        executor.execute(() -> {
            try {
                dis = socketService.getAudioInputStream();
                dos = socketService.getAudioOutputStream();
                if (dis == null || dos == null) throw new IOException("لم يتم الاتصال بالخادم");
                startAudioSender();
                startAudioReceiver();
                runOnUiThread(() ->
                        Toast.makeText(this, "تم الاتصال بالمكالمة", Toast.LENGTH_SHORT).show()
                );
            } catch (IOException e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "فشل الاتصال بالسيرفر: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        });
    }

    private void startCallTimer() {
        callTimer = new CountDownTimer(Long.MAX_VALUE, 1000) {
            public void onTick(long millisUntilFinished) {
                binding.callDuration.setText(getFormattedTime(millisUntilFinished));
            }
            public void onFinish() {}
        }.start();
    }

    private String getFormattedTime(long millis) {
        int seconds = (int) (System.currentTimeMillis() - millis) / 1000;
        int minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    private void startAudioSender() {
        executor.execute(() -> {
            try {
                int sampleRate = 16000; // 16 kHz
                int channelConfig = AudioFormat.CHANNEL_IN_MONO;
                int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
                int bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);

                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    // TODO: Consider calling
                    //    ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                    //                                          int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    return;
                }
                audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                );

                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IOException("تعذر تهيئة AudioRecord");
                }

                audioRecord.startRecording();
                OutputStream os = dos; // Use dos for sending
                byte[] buffer = new byte[bufferSize];

                while (isCalling && socketService.isAudioConnected()) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0 && !isMuted) {
                        os.write(buffer, 0, read);
                        os.flush();
                    }
                }

            } catch (IOException e) {
                if (isCalling) {
                    runOnUiThread(() ->
                            Toast.makeText(this, "خطأ في إرسال الصوت", Toast.LENGTH_SHORT).show()
                    );
                }
            } finally {
                if (audioRecord != null) {
                    audioRecord.stop();
                    audioRecord.release();
                }
            }
        });
    }

    private void startAudioReceiver() {
        executor.execute(() -> {
            try {
                int sampleRate = 16000; // 16 kHz
                int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
                int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
                int bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);

                audioTrack = new AudioTrack(
                        AudioManager.STREAM_VOICE_CALL,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize,
                        AudioTrack.MODE_STREAM
                );

                if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                    throw new IOException("تعذر تهيئة AudioTrack");
                }

                audioTrack.play();
                InputStream is = dis; // Use dis for receiving
                byte[] buffer = new byte[bufferSize];

                while (isCalling && socketService.isAudioConnected()) {
                    int read = is.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        audioTrack.write(buffer, 0, read);
                    }
                }

            } catch (IOException e) {
                if (isCalling) {
                    runOnUiThread(() ->
                            Toast.makeText(this, "خطأ في استقبال الصوت", Toast.LENGTH_SHORT).show()
                    );
                }
            } finally {
                if (audioTrack != null) {
                    audioTrack.stop();
                    audioTrack.release();
                }
            }
        });
    }

    private void toggleMute() {
        isMuted = !isMuted;
        runOnUiThread(() -> {
            binding.btnMute.setIconResource(isMuted ?
                    R.drawable.ic_mic_off : R.drawable.ic_mic_on);
            binding.btnMute.setText(isMuted ? "إلغاء الكتم" : "كتم الصوت");
        });
    }

    private void toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn;
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(isSpeakerOn);
        }
        updateSpeakerButton();
    }

    private void updateSpeakerButton() {
        runOnUiThread(() -> {
            binding.btnSpeaker.setIconResource(isSpeakerOn ? R.drawable.ic_speaker_on : R.drawable.ic_speaker_off);
            binding.btnSpeaker.setText(isSpeakerOn ? "إيقاف السماعة" : "تشغيل السماعة");
        });
    }

    private void endCall() {
        if (!isCalling) return;

        isCalling = false;

        if (callTimer != null) {
            callTimer.cancel();
        }

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
            socketService.disconnectAudio();
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        if (isServiceBound && socketService != null) {
            socketService.disconnectAudio();
            unbindService(serviceConnection);
            isServiceBound = false;
        }
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setSpeakerphoneOn(false);
            audioManager.setMode(AudioManager.MODE_NORMAL);
        }
        endCall();
        executor.shutdownNow();
        super.onDestroy();
    }
}