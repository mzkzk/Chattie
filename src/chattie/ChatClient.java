package chattie;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

public class ChatClient {

    private static final String SERVER_ADDRESS = "localhost";
    private static final int SERVER_PORT = 9090;

    public static void main(String[] args) {
        try (Socket socket = new Socket(SERVER_ADDRESS, SERVER_PORT);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader consoleInput = new BufferedReader(new InputStreamReader(System.in))) {

            // Send client name to the server
            System.out.print("Enter your name: ");
            String clientName = consoleInput.readLine();
            out.println(clientName);

            // Start a thread to read messages from the server
            new Thread(() -> {
                try {
                    String message;
                    while ((message = in.readLine()) != null) {
                        if (message.startsWith("TYPE:")) {
                            String[] parts = message.split(":", 3);
                            if (parts.length >= 3) {
                                String type = parts[1];
                                String sender = parts[2];
                                System.out.println(sender + " sent a " + type.toLowerCase() + " message");
                            }
                        } else {
                            System.out.println(message);
                        }
                    }
                } catch (IOException e) {
                    System.err.println("Error reading from server: " + e.getMessage());
                    e.printStackTrace();
                }
            }).start();

            // Read messages from the user and send to the server
            String userInput;
            while ((userInput = consoleInput.readLine()) != null) {
                if (userInput.startsWith("/private ")) {
                    // Handle private messages
                    String[] parts = userInput.substring(9).split(" ", 2);
                    if (parts.length == 2) {
                        out.println("TYPE:PRIVATE:" + parts[0] + ":" + parts[1]);
                        System.out.println("You (private to " + parts[0] + "): " + parts[1]);
                    }
                } else {
                    // Regular text messages
                    out.println("TYPE:TEXT:" + userInput);
                    System.out.println("You: " + userInput);
                }
            }

        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}