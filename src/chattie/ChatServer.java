package chattie;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChatServer {
    private static final int TEXT_PORT = 12345;
    private static final int AUDIO_PORT = 12346;
    private static final int VIDEO_PORT = 12347;
    private static Map<String, List<Socket>> textRooms = new HashMap<>();
    private static Map<String, List<Socket>> audioRooms = new HashMap<>();
    private static Map<String, List<Socket>> videoRooms = new HashMap<>();

    public static void main(String[] args) {
        printServerIPs();
        new Thread(() -> startTextServer()).start();
        new Thread(() -> startAudioServer()).start();
        new Thread(() -> startVideoServer()).start();
    }

    private static void printServerIPs() {
        System.out.println("Server IP addresses (HomeWork):");
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr.isSiteLocalAddress()) {
                        System.out.println("  " + addr.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Could not determine server IP addresses: " + e.getMessage());
        }
    }

    private static void startTextServer() {
        startServer(TEXT_PORT, "TEXT", textRooms);
    }

    private static void startAudioServer() {
        startServer(AUDIO_PORT, "AUDIO", audioRooms);
    }

    private static void startVideoServer() {
        startServer(VIDEO_PORT, "VIDEO", videoRooms);
    }

    private static void startServer(int port, String type, Map<String, List<Socket>> rooms) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println(type + " Server running on port " + port);
            while (true) {
                Socket socket = serverSocket.accept();
                new Thread(() -> handleClient(socket, type, rooms)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void handleClient(Socket socket, String type, Map<String, List<Socket>> rooms) {
        try (DataInputStream dis = new DataInputStream(socket.getInputStream())) {
            String clientInfo = dis.readUTF();
            String[] parts = clientInfo.split(":");
            String username = parts[0];
            String roomId = parts[1];

            synchronized (rooms) {
                rooms.computeIfAbsent(roomId, k -> new ArrayList<>()).add(socket);
            }
            System.out.println(username + " joined " + roomId + " (" + type + ")");

            if (type.equals("TEXT")) {
                handleTextClient(socket, dis, roomId, rooms);
            } else if (type.equals("AUDIO")) {
                handleAudioClient(socket, dis, roomId, rooms);
            } else if (type.equals("VIDEO")) {
                handleVideoClient(socket, dis, roomId, rooms);
            }
        } catch (IOException e) {
            System.out.println("Client connection error: " + e.getMessage());
        } finally {
            synchronized (rooms) {
                rooms.values().forEach(room -> room.remove(socket));
            }
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private static void handleTextClient(Socket socket, DataInputStream dis, 
                                      String roomId, Map<String, List<Socket>> rooms) throws IOException {
        while (true) {
            String type = dis.readUTF();
            if (type.equals("TEXT")) {
                String clientId = dis.readUTF();
                String message = dis.readUTF();
                System.out.println("[" + clientId + "] " + message);
                broadcastChat(roomId, socket, type, clientId, message, null, null, 0, rooms);
            } else if (type.equals("FILE") || type.equals("IMAGE") || type.equals("VIDEO") || type.equals("VOICE")) {
                String clientId = dis.readUTF();
                String fileName = dis.readUTF();
                long fileLength = dis.readLong();
                byte[] fileBytes = new byte[(int) fileLength];
                dis.readFully(fileBytes);
                System.out.println("[" + clientId + "] sent " + type + ": " + fileName + " (" + fileLength + " bytes)");
                broadcastChat(roomId, socket, type, clientId, null, fileName, fileBytes, fileLength, rooms);
            } else {
                System.out.println("Unknown chat message type: " + type);
            }
        }
    }

    private static void handleAudioClient(Socket socket, DataInputStream dis,
                                        String roomId, Map<String, List<Socket>> rooms) throws IOException {
        byte[] buffer = new byte[4096];
        while (true) {
            int bytesRead = dis.read(buffer);
            if (bytesRead == -1) break;
            
            broadcastMedia(roomId, socket, buffer, bytesRead, rooms, "AUDIO");
        }
    }

    private static void handleVideoClient(Socket socket, DataInputStream dis,
                                       String roomId, Map<String, List<Socket>> rooms) throws IOException {
        // قراءة حجم الإطار أولاً
        while (true) {
            int frameSize = dis.readInt();
            if (frameSize == -1) break;
            
            byte[] frameData = new byte[frameSize];
            dis.readFully(frameData);
            
            broadcastMedia(roomId, socket, frameData, frameSize, rooms, "VIDEO");
        }
    }
private static void broadcastMedia(String roomId, Socket sender, 
                                     byte[] data, int dataLength,
                                     Map<String, List<Socket>> rooms, String type) {
        List<Socket> roomClients;
        synchronized (rooms) {
            roomClients = new ArrayList<>(rooms.getOrDefault(roomId, Collections.emptyList()));
        }
        
        for (Socket clientSocket : roomClients) {
            if (clientSocket != sender && !clientSocket.isClosed()) {
                try {
                    if (type.equals("VIDEO")) {
                        DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream());
                        dos.writeInt(dataLength);
                        dos.write(data, 0, dataLength);
                    } else {
                        OutputStream out = clientSocket.getOutputStream();
                        out.write(data, 0, dataLength);
                    }
                } catch (IOException e) {
                    System.out.println("Error sending " + type + " to client: " + e.getMessage());
                    try { clientSocket.close(); } catch (IOException ignored) {}
                }
            }
        }
    }

    private static void broadcastChat(String roomId, Socket sender, String type, String clientId, String message, String fileName, byte[] fileBytes, long fileLength, Map<String, List<Socket>> rooms) {
        List<Socket> roomClients;
        synchronized (rooms) {
            roomClients = new ArrayList<>(rooms.getOrDefault(roomId, Collections.emptyList()));
        }
        for (Socket socket : roomClients) {
            if (socket != sender && !socket.isClosed()) {
                try {
                    DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                    dos.writeUTF(type);
                    dos.writeUTF(clientId);
                    if (type.equals("TEXT")) {
                        dos.writeUTF(message);
                    } else if (type.equals("FILE") || type.equals("IMAGE") || type.equals("VIDEO") || type.equals("VOICE")) {
                        dos.writeUTF(fileName);
                        dos.writeLong(fileLength);
                        dos.write(fileBytes);
                    }
                    dos.flush();
                } catch (IOException e) {
                    System.out.println("Error sending chat message to client: " + e.getMessage());
                    try { socket.close(); } catch (IOException ignored) {}
                }
            }
        }
    }
}