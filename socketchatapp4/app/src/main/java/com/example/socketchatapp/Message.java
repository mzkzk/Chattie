package com.example.socketchatapp;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Message {
    private String text;
    private String filePath;
    private String time;
    private boolean sent;
    private String senderId;
    private MessageType type;

    public enum MessageType {
        TEXT, IMAGE, FILE
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

    // جميع التوابع getter و setter هنا...
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