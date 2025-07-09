package com.example.socketchatapp;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.example.socketchatapp.databinding.ActivityChatBinding;
import com.example.socketchatapp.databinding.ItemMessageBinding;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import androidx.core.content.FileProvider;
import androidx.core.content.ContextCompat;

public class ChatActivity extends AppCompatActivity {
    private static final int SOCKET_TIMEOUT = 30000; // 30 ثانية
    private static final int HEARTBEAT_INTERVAL = 15000; // 15 ثانية
    private ActivityChatBinding binding;
    private MessageAdapter adapter;
    private final List<Message> messages = new ArrayList<>();
    private ExecutorService executor = Executors.newFixedThreadPool(2);
    private Handler handler = new Handler(Looper.getMainLooper());
    private DataInputStream dis;
    private DataOutputStream dos;
    private String username, roomId, serverIp;
    private int serverPort, clientId;
    private DatabaseHelper dbHelper;
    private SocketService socketService;
    private boolean isServiceBound = false;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private MediaRecorder mediaRecorder;
    private String tempVoiceFilePath;
    private boolean isRecording = false;
    private Uri tempCameraPhotoUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        dbHelper = new DatabaseHelper(this);

        username = getIntent().getStringExtra("USERNAME");
        roomId = getIntent().getStringExtra("ROOM_ID");
        serverIp = getIntent().getStringExtra("SERVER_IP");
        serverPort = getIntent().getIntExtra("SERVER_PORT", 12345);
        clientId = getIntent().getIntExtra("CLIENT_ID", 1);

        messages.addAll(dbHelper.getAllMessages(roomId));

        binding.toolbar.setTitle("غرفة: " + roomId + " | عميل: " + clientId);
        binding.toolbar.setSubtitle(username);
        setSupportActionBar(binding.toolbar);

        adapter = new MessageAdapter(messages);
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerView.setAdapter(adapter);

        binding.btnSend.setOnClickListener(v -> sendMessage());
        binding.btnAttach.setOnClickListener(v -> showAttachmentOptions());
        binding.recordButton.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startVoiceRecording();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    stopVoiceRecordingAndSend();
                    return true;
            }
            return false;
        });
        binding.mediaPickerButton.setOnClickListener(v -> showMediaPickerDialog());

        // Handle audio and video call buttons
        findViewById(R.id.audioCallBtn).setOnClickListener(v -> startCall(AudioCallActivity.class));
        findViewById(R.id.videoCallBtn).setOnClickListener(v -> startCall(VideoCallActivity.class));

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
            // Only proceed if the service is already connected
            if (socketService.isChatConnected()) {
                connectToServer();
            } else {
                Toast.makeText(ChatActivity.this, "Not connected to server. Please connect first.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            isServiceBound = false;
            socketService = null;
        }
    };

    private void connectToServer() {
        executor.execute(() -> {
            try {
                // Actually reconnect the socket, not just get existing streams
                if (!socketService.isChatConnected()) {
                    socketService.connectChat(serverIp, serverPort, clientId, roomId, username);
                }
                dis = socketService.getChatInputStream();
                dos = socketService.getChatOutputStream();
                if (dis == null || dos == null) throw new IOException("لم يتم الاتصال بالخادم");
                reconnectAttempts = 0; // Reset on successful connection
                startMessageReceiver();
            } catch (IOException e) {
                handler.post(() -> {
                    Toast.makeText(this, "خطأ في الاتصال: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    reconnectToServer();
                });
            }
        });
    }

    private void startMessageReceiver() {
        executor.execute(() -> {
            try {
                while (socketService.isChatConnected()) {
                    String typeStr = dis.readUTF();
                    Message.MessageType type = Message.MessageType.valueOf(typeStr);
                    String senderId = dis.readUTF();

                    if (type == Message.MessageType.TEXT) {
                        String message = dis.readUTF();
                        handler.post(() -> {
                            boolean isSent = senderId.equals(String.valueOf(clientId));
                            Message newMessage = new Message(message, isSent, senderId);
                            addMessage(newMessage);
                            dbHelper.addMessage(newMessage, roomId);
                        });
                    } else {
                        String fileName = dis.readUTF();
                        long fileSize = dis.readLong();
                        byte[] fileData = new byte[(int) fileSize];
                        dis.readFully(fileData);

                        // Set file extension for VOICE/VIDEO
                        String saveName = fileName;
                        if (type == Message.MessageType.VOICE && !fileName.endsWith(".aac") && !fileName.endsWith(".m4a")) {
                            saveName = fileName + ".aac";
                        } else if (type == Message.MessageType.VIDEO && !fileName.endsWith(".mp4")) {
                            saveName = fileName + ".mp4";
                        }
                        String filePath = saveFile(saveName, fileData);

                        handler.post(() -> {
                            boolean isSent = senderId.equals(String.valueOf(clientId));
                            Message newMessage = new Message(filePath, isSent, senderId, type);
                            addMessage(newMessage);
                            dbHelper.addMessage(newMessage, roomId);
                        });
                    }
                }
            } catch (IOException e) {
                handler.post(() -> {
                    if (!isFinishing()) {
                        Toast.makeText(this, "انقطع الاتصال بالخادم. إعادة الاتصال... (محاولة " + (reconnectAttempts+1) + "/" + MAX_RECONNECT_ATTEMPTS + ")", Toast.LENGTH_SHORT).show();
                        reconnectToServer();
                    }
                });
            }
        });
    }

    private String saveFile(String fileName, byte[] data) throws IOException {
        File dir = new File(getFilesDir(), "chat_files");
        if (!dir.exists()) dir.mkdirs();

        File file = new File(dir, fileName);
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(data);
        fos.close();

        return file.getAbsolutePath();
    }

    private void sendMessage() {
        String message = binding.messageInput.getText().toString().trim();
        if (!TextUtils.isEmpty(message)) {
            executor.execute(() -> {
                try {
                    dos.writeUTF("TEXT");
                    dos.writeUTF(username);
                    dos.writeUTF(message);
                    dos.flush();

                    handler.post(() -> {
                        Message newMessage = new Message(message, true, username);
                        addMessage(newMessage);
                        dbHelper.addMessage(newMessage, roomId);
                        binding.messageInput.setText("");
                    });
                } catch (IOException e) {
                    handler.post(() ->
                            Toast.makeText(this, "فشل إرسال الرسالة", Toast.LENGTH_SHORT).show()
                    );
                }
            });
        }
    }

    private void showAttachmentOptions() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        startActivityForResult(Intent.createChooser(intent, "اختر ملف"), 101);
    }

    private void showMediaPickerDialog() {
        String[] options = {"التقاط صورة بالكاميرا", "اختيار صور/فيديوهات"};
        new AlertDialog.Builder(this)
                .setTitle("إرسال وسائط")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        openCameraCapture();
                    } else {
                        openMediaPickerMulti();
                    }
                })
                .show();
    }

    private void openCameraCapture() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        File photoFile;
        try {
            File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            photoFile = File.createTempFile("IMG_" + System.currentTimeMillis(), ".jpg", storageDir);
            tempCameraPhotoUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", photoFile);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, tempCameraPhotoUri);
            startActivityForResult(intent, 103);
        } catch (IOException e) {
            Toast.makeText(this, "فشل فتح الكاميرا", Toast.LENGTH_SHORT).show();
        }
    }

    private void openMediaPickerMulti() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(intent, "اختر صور أو فيديوهات"), 104);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == 101 && resultCode == RESULT_OK && data != null) {
            Uri fileUri = data.getData();
            if (fileUri != null) {
                    String mimeType = getContentResolver().getType(fileUri);
                    Message.MessageType type = (mimeType != null && mimeType.startsWith("image/"))
                            ? Message.MessageType.IMAGE
                            : Message.MessageType.FILE;
                sendFile(fileUri, type);
            }
        } else if (requestCode == 102 && resultCode == RESULT_OK && data != null) {
            Uri fileUri = data.getData();
            if (fileUri != null) {
                String mimeType = getContentResolver().getType(fileUri);
                Message.MessageType type = Message.MessageType.FILE;
                if (mimeType != null) {
                    if (mimeType.startsWith("image/")) {
                        type = Message.MessageType.IMAGE;
                    } else if (mimeType.startsWith("video/")) {
                        type = Message.MessageType.VIDEO;
                    }
                }
                sendFile(fileUri, type);
            }
        } else if (requestCode == 103 && resultCode == RESULT_OK && tempCameraPhotoUri != null) {
            // Camera photo taken
            sendFile(tempCameraPhotoUri, Message.MessageType.IMAGE);
        } else if (requestCode == 104 && resultCode == RESULT_OK && data != null) {
            // Multi-choose
            if (data.getClipData() != null) {
                ClipData clipData = data.getClipData();
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    Uri uri = clipData.getItemAt(i).getUri();
                    String mimeType = getContentResolver().getType(uri);
                    Message.MessageType type = Message.MessageType.FILE;
                    if (mimeType != null) {
                        if (mimeType.startsWith("image/")) {
                            type = Message.MessageType.IMAGE;
                        } else if (mimeType.startsWith("video/")) {
                            type = Message.MessageType.VIDEO;
                        }
                    }
                    sendFile(uri, type);
                }
            } else if (data.getData() != null) {
                Uri uri = data.getData();
                String mimeType = getContentResolver().getType(uri);
                Message.MessageType type = Message.MessageType.FILE;
                if (mimeType != null) {
                    if (mimeType.startsWith("image/")) {
                        type = Message.MessageType.IMAGE;
                    } else if (mimeType.startsWith("video/")) {
                        type = Message.MessageType.VIDEO;
                    }
                }
                sendFile(uri, type);
            }
        }
    }

    private void sendFile(final Uri fileUri, final Message.MessageType type) {
        executor.execute(() -> {
            try {
                String fileName = getFileNameFromUri(fileUri);
                InputStream is = getContentResolver().openInputStream(fileUri);
                if (is == null) throw new IOException("Cannot open input stream");
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int len;
                while ((len = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
                is.close();
                byte[] fileBytes = baos.toByteArray();

                dos.writeUTF(type.name());
                dos.writeUTF(username);
                dos.writeUTF(fileName);
                dos.writeLong(fileBytes.length);
                dos.write(fileBytes);
                dos.flush();

                // Save locally for display
                String localPath = saveFile(fileName, fileBytes);
                handler.post(() -> {
                    Message newMessage = new Message(localPath, true, username, type);
                    addMessage(newMessage);
                    dbHelper.addMessage(newMessage, roomId);
                });
            } catch (IOException e) {
                handler.post(() ->
                        Toast.makeText(this, "فشل إرسال الملف", Toast.LENGTH_SHORT).show()
                );
            }
        });
    }

    private String getFileNameFromUri(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx != -1) result = cursor.getString(idx);
                cursor.close();
            }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result != null ? result : ("file_" + System.currentTimeMillis());
    }

    private void addMessage(Message message) {
        messages.add(message);
        adapter.notifyItemInserted(messages.size() - 1);
        binding.recyclerView.smoothScrollToPosition(messages.size() - 1);
    }

    private void reconnectToServer() {
        reconnectAttempts++;
        if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            handler.post(() -> {
                Toast.makeText(this, "فشل إعادة الاتصال بالخادم. سيتم الخروج.", Toast.LENGTH_LONG).show();
                finish();
            });
            return;
        }
        handler.postDelayed(() -> {
            if (!isFinishing()) {
                connectToServer();
            }
        }, 3000);
    }

    private void startCall(Class<?> activityClass) {
        Intent intent = new Intent(this, activityClass);
        intent.putExtra("SERVER_IP", serverIp);
        if (activityClass == AudioCallActivity.class) {
            intent.putExtra("SERVER_PORT", 12346); // Audio call port
        } else if (activityClass == VideoCallActivity.class) {
            intent.putExtra("SERVER_PORT", 12347); // Video call port
        } else {
            intent.putExtra("SERVER_PORT", serverPort); // fallback
        }
        intent.putExtra("USERNAME", username);
        intent.putExtra("ROOM_ID", roomId);
        intent.putExtra("CLIENT_ID", clientId);
        startActivity(intent);
    }

    private void startVoiceRecording() {
        if (isRecording) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 200);
            return;
        }
        try {
            File voiceDir = new File(getFilesDir(), "voice_temp");
            if (!voiceDir.exists()) voiceDir.mkdirs();
            tempVoiceFilePath = new File(voiceDir, "voice_" + System.currentTimeMillis() + ".aac").getAbsolutePath();
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setOutputFile(tempVoiceFilePath);
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
            Toast.makeText(this, "جارٍ التسجيل...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "فشل بدء التسجيل الصوتي", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    // For voice messages, use sendFile(Uri, MessageType) with a file:// Uri
    private void stopVoiceRecordingAndSend() {
        if (!isRecording) return;
        try {
            mediaRecorder.stop();
            mediaRecorder.release();
            mediaRecorder = null;
            isRecording = false;
            Toast.makeText(this, "تم حفظ التسجيل الصوتي", Toast.LENGTH_SHORT).show();
            Uri voiceUri = Uri.fromFile(new File(tempVoiceFilePath));
            sendFile(voiceUri, Message.MessageType.VOICE);
        } catch (Exception e) {
            Toast.makeText(this, "فشل حفظ التسجيل الصوتي", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        // Don't unbind from service - let MainActivity manage the service connection
        // if (isServiceBound) {
        //     unbindService(serviceConnection);
        //     isServiceBound = false;
        // }
        super.onDestroy();
        try {
            // لا تغلق السوكيت هنا، فقط أغلق الموارد المحلية
            executor.shutdown();
            dbHelper.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageViewHolder> {
        private final List<Message> messages;

        public MessageAdapter(List<Message> messages) {
            this.messages = messages;
        }

        @NonNull
        @Override
        public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemMessageBinding binding = ItemMessageBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);

            // تعيين معلمات التخطيط للتحكم في الموضع
            ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams)
                    binding.messageCard.getLayoutParams();
            layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT;

            return new MessageViewHolder(binding);
        }

        @Override
        public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
            holder.bind(messages.get(position));
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        class MessageViewHolder extends RecyclerView.ViewHolder {
            private final ItemMessageBinding binding;
            private MediaPlayer mediaPlayer;

            public MessageViewHolder(ItemMessageBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }

            public void bind(Message message) {
                binding.messageText.setVisibility(View.GONE);
                binding.messageImage.setVisibility(View.GONE);
                binding.fileNameText.setVisibility(View.GONE);
                binding.senderNameText.setVisibility(View.VISIBLE);
                if (message.isSent()) {
                    binding.senderNameText.setText("You");
                } else {
                    binding.senderNameText.setText(message.getSenderId());
                }
                binding.fileNameText.setOnClickListener(null);

                switch (message.getType()) {
                    case TEXT:
                        binding.messageText.setVisibility(View.VISIBLE);
                        binding.messageText.setText(message.getText());
                        break;
                    case IMAGE:
                        binding.messageImage.setVisibility(View.VISIBLE);
                        Glide.with(binding.getRoot().getContext())
                                .load(new File(message.getFilePath()))
                                .into(binding.messageImage);
                        binding.messageImage.setOnClickListener(v -> {
                            try {
                                Context context = binding.getRoot().getContext();
                                File file = new File(message.getFilePath());
                                Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", file);
                                Intent intent = new Intent(Intent.ACTION_VIEW);
                                intent.setDataAndType(uri, "image/*");
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                context.startActivity(intent);
                            } catch (Exception e) {
                                Toast.makeText(binding.getRoot().getContext(), "لا يمكن فتح الصورة في المعرض", Toast.LENGTH_SHORT).show();
                            }
                        });
                        break;
                    case FILE:
                        binding.fileNameText.setVisibility(View.VISIBLE);
                        binding.fileNameText.setText(new File(message.getFilePath()).getName());
                        binding.fileNameText.setOnClickListener(v -> {
                            try {
                                Context context = binding.getRoot().getContext();
                                File file = new File(message.getFilePath());
                                Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", file);
                                Intent intent = new Intent(Intent.ACTION_VIEW);
                                intent.setDataAndType(uri, getMimeType(file.getAbsolutePath()));
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                context.startActivity(intent);
                            } catch (Exception e) {
                                Toast.makeText(binding.getRoot().getContext(), "لا يمكن فتح الملف", Toast.LENGTH_SHORT).show();
                            }
                        });
                        break;
                    case VOICE:
                        binding.fileNameText.setVisibility(View.VISIBLE);
                        binding.fileNameText.setText("تشغيل الرسالة الصوتية ▶");
                        binding.fileNameText.setOnClickListener(v -> {
                            if (mediaPlayer != null) {
                                mediaPlayer.release();
                                mediaPlayer = null;
                            }
                            mediaPlayer = new MediaPlayer();
                            try {
                                mediaPlayer.setDataSource(message.getFilePath());
                                mediaPlayer.prepare();
                                mediaPlayer.start();
                                Toast.makeText(binding.getRoot().getContext(), "تشغيل الرسالة الصوتية...", Toast.LENGTH_SHORT).show();
                                mediaPlayer.setOnCompletionListener(mp -> {
                                    Toast.makeText(binding.getRoot().getContext(), "انتهى التشغيل", Toast.LENGTH_SHORT).show();
                                    mediaPlayer.release();
                                    mediaPlayer = null;
                                });
                            } catch (Exception e) {
                                Toast.makeText(binding.getRoot().getContext(), "فشل تشغيل الرسالة الصوتية", Toast.LENGTH_SHORT).show();
                                e.printStackTrace();
                            }
                        });
                        break;
                    case VIDEO:
                        binding.fileNameText.setVisibility(View.VISIBLE);
                        binding.fileNameText.setText("تشغيل الفيديو ▶ " + new File(message.getFilePath()).getName());
                        binding.fileNameText.setOnClickListener(v -> {
                            try {
                                Context context = binding.getRoot().getContext();
                                File file = new File(message.getFilePath());
                                Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", file);
                                Intent intent = new Intent(Intent.ACTION_VIEW);
                                intent.setDataAndType(uri, "video/*");
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                context.startActivity(intent);
                            } catch (Exception e) {
                                Toast.makeText(binding.getRoot().getContext(), "لا يمكن تشغيل الفيديو", Toast.LENGTH_SHORT).show();
                            }
                        });
                        break;
                }

                binding.messageTime.setText(message.getTime());

                // تحديد موضع الرسالة حسب كونها مرسلة أو مستقبلة
                ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams)
                        binding.messageCard.getLayoutParams();

                if (message.isSent()) {
                    // الرسائل المرسلة على اليسار
                    layoutParams.rightMargin = 100; // هامش من اليمين
                    layoutParams.leftMargin = 0;    // لا هامش من اليسار
                    binding.getRoot().setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                } else {
                    // الرسائل المستقبلة على اليمين
                    layoutParams.leftMargin = 100;  // هامش من اليسار
                    layoutParams.rightMargin = 0;   // لا هامش من اليمين
                    binding.getRoot().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                }

                binding.messageCard.setLayoutParams(layoutParams);
                binding.messageCard.setCardBackgroundColor(
                        ContextCompat.getColor(binding.getRoot().getContext(),
                                message.isSent() ? R.color.message_sent : R.color.message_received));
            }
        }
    }

    private String getMimeType(String path) {
        String type = null;
        String extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(path);
        if (extension != null) {
            type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        }
        return type != null ? type : "*/*";
    }

    static class Message {
        private String text;
        private String filePath;
        private String time;
        private boolean sent;
        private String senderId;
        private MessageType type;

        public enum MessageType {
            TEXT, IMAGE, FILE, VOICE, VIDEO
        }

        public Message(String text, boolean sent, String senderId) {
            this.text = text;
            this.sent = sent;
            this.senderId = senderId;
            this.type = MessageType.TEXT;
            this.time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        }

        public Message(String filePath, boolean sent, String senderId, MessageType type) {
            this.filePath = filePath;
            this.sent = sent;
            this.senderId = senderId;
            this.type = type;
            this.time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        }

        public String getText() {
            return text;
        }

        public String getFilePath() {
            return filePath;
        }

        public String getTime() {
            return time;
        }

        public void setTime(String time) {
            this.time = time;
        }

        public boolean isSent() {
            return sent;
        }

        public String getSenderId() {
            return senderId;
        }

        public MessageType getType() {
            return type;
        }
    }
}
