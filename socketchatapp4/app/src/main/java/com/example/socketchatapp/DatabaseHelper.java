package com.example.socketchatapp;
import com.example.socketchatapp.ChatActivity.Message;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "chat.db";
    private static final int DATABASE_VERSION = 2;

    private static final String TABLE_MESSAGES = "messages";
    private static final String COLUMN_ID = "id";
    private static final String COLUMN_TEXT = "text";
    private static final String COLUMN_FILE_PATH = "file_path";
    private static final String COLUMN_SENDER_ID = "sender_id";
    private static final String COLUMN_ROOM_ID = "room_id";
    private static final String COLUMN_TIMESTAMP = "timestamp";
    private static final String COLUMN_IS_SENT = "is_sent";
    private static final String COLUMN_MESSAGE_TYPE = "message_type";

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String CREATE_MESSAGES_TABLE = "CREATE TABLE " + TABLE_MESSAGES + "("
                + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + COLUMN_TEXT + " TEXT,"
                + COLUMN_FILE_PATH + " TEXT,"
                + COLUMN_SENDER_ID + " TEXT,"
                + COLUMN_ROOM_ID + " TEXT,"
                + COLUMN_TIMESTAMP + " TEXT,"
                + COLUMN_IS_SENT + " INTEGER,"
                + COLUMN_MESSAGE_TYPE + " TEXT)";
        db.execSQL(CREATE_MESSAGES_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_MESSAGES);
        onCreate(db);
    }

    public void addMessage(Message message, String roomId) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues values = new ContentValues();

        if(message.getType() == Message.MessageType.TEXT) {
            values.put(COLUMN_TEXT, message.getText());
        } else {
            values.put(COLUMN_FILE_PATH, message.getFilePath());
        }

        values.put(COLUMN_SENDER_ID, message.getSenderId());
        values.put(COLUMN_ROOM_ID, roomId);
        values.put(COLUMN_TIMESTAMP, message.getTime());
        values.put(COLUMN_IS_SENT, message.isSent() ? 1 : 0);
        values.put(COLUMN_MESSAGE_TYPE, message.getType().name());

        db.insert(TABLE_MESSAGES, null, values);
        db.close();
    }

    public List<Message> getAllMessages(String roomId) {
        List<Message> messages = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(TABLE_MESSAGES,
                new String[]{COLUMN_TEXT, COLUMN_FILE_PATH, COLUMN_SENDER_ID,
                        COLUMN_TIMESTAMP, COLUMN_IS_SENT, COLUMN_MESSAGE_TYPE},
                COLUMN_ROOM_ID + " = ?",
                new String[]{roomId},
                null, null, COLUMN_TIMESTAMP + " ASC");

        if (cursor.moveToFirst()) {
            do {
                boolean isSent = cursor.getInt(4) == 1;
                Message.MessageType type = Message.MessageType.valueOf(cursor.getString(5));

                Message message;
                if(type == Message.MessageType.TEXT) {
                    message = new Message(
                            cursor.getString(0),
                            isSent,
                            cursor.getString(2));
                } else {
                    message = new Message(
                            cursor.getString(1),
                            isSent,
                            cursor.getString(2),
                            type);
                }

                message.setTime(cursor.getString(3));
                messages.add(message);
            } while (cursor.moveToNext());
        }
        cursor.close();
        db.close();
        return messages;
    }
}