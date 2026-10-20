package ru.miacomsoft.EasyWebServer;

import org.json.JSONObject;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class WebRTCSignalingServer {

    private final int port;
    private volatile boolean running = false;
    private ServerSocket server;

    // Комнаты: roomName -> Room
    public static final ConcurrentHashMap<String, Room> ROOMS = new ConcurrentHashMap<>();

    public WebRTCSignalingServer(int port) {
        this.port = port;
    }

    public void start() {
        running = true;
        new Thread(() -> {
            try {
                server = new ServerSocket(port);
                System.out.println("[WebRTC] Signaling server started on port " + port);
                while (running) {
                    Socket socket = server.accept();
                    new Thread(() -> handleConnection(socket)).start();
                }
            } catch (IOException e) {
                if (running) e.printStackTrace();
            }
        }).start();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
    }

    private void handleConnection(Socket socket) {
        try (socket) {
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 3) return;

            Map<String, String> headers = new ConcurrentHashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    headers.put(line.substring(0, idx).trim().toLowerCase(), line.substring(idx + 1).trim());
                }
            }

            String path = parts[1];
            String query = "";
            int q = path.indexOf('?');
            if (q >= 0) {
                query = path.substring(q + 1);
                path = path.substring(0, q);
            }

            if (!"websocket".equalsIgnoreCase(headers.getOrDefault("upgrade", ""))) {
                out.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
                return;
            }

            // Рукопожатие WebSocket
            String key = headers.get("sec-websocket-key");
            String accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes())
            );
            String resp = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            out.write(resp.getBytes());
            out.flush();

            String roomName = "main";
            String pass = "";
            for (String p : query.split("&")) {
                String[] kv = p.split("=");
                if (kv.length == 2) {
                    if ("room".equals(kv[0])) roomName = java.net.URLDecoder.decode(kv[1], "UTF-8");
                    if ("pass".equals(kv[0])) pass = java.net.URLDecoder.decode(kv[1], "UTF-8");
                }
            }

            WsConnection ws = new WsConnection(socket, in, out);

            if (path.equals("/ws/publish")) {
                handlePublisher(ws, roomName, pass);
            } else if (path.equals("/ws/subscribe")) {
                handleSubscriber(ws, roomName, pass);
            } else {
                ws.close(1008, "Unknown endpoint");
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private void handlePublisher(WsConnection ws, String roomName, String pass) {
        Room room = ROOMS.computeIfAbsent(roomName, Room::new);

        synchronized (room) {
            if (room.password != null && !room.password.equals(pass)) {
                ws.close(1008, "Invalid password");
                return;
            }
            if (room.password == null && !pass.isEmpty()) {
                room.password = pass;
            }

            if (room.publisherWs != null) {
                room.publisherWs.close(1000, "replaced");
            }
            room.publisherWs = ws;
            for (WsConnection v : room.viewers.values()) {
                v.close(1000, "publisher restarted");
            }
            room.viewers.clear();
        }
        System.out.println("[WebRTC] Publisher connected to room: " + roomName);

        while (!ws.isClosed()) {
            WsFrame frame = ws.readFrame();
            if (frame == null) break;

            if (frame.opcode == 0x1) { // Text
                String text = new String(frame.payload, java.nio.charset.StandardCharsets.UTF_8);
                try {
                    JSONObject msg = new JSONObject(text);
                    String target = msg.optString("target");
                    synchronized (room) {
                        WsConnection v = room.viewers.get(target);
                        if (v != null) v.sendText(text);
                    }
                } catch (Exception ignored) {}
            } else if (frame.opcode == 0x8) { // Close
                break;
            }
        }

        synchronized (room) {
            if (room.publisherWs == ws) room.publisherWs = null;
        }
        ws.close(1000, "bye");
        System.out.println("[WebRTC] Publisher disconnected from room: " + roomName);
    }

    private void handleSubscriber(WsConnection ws, String roomName, String pass) {
        Room room = ROOMS.computeIfAbsent(roomName, Room::new);
        String viewerId = UUID.randomUUID().toString();

        synchronized (room) {
            if (room.password != null && !room.password.equals(pass)) {
                ws.close(1008, "Invalid password");
                return;
            }
            room.viewers.put(viewerId, ws);
            ws.sendText(new JSONObject().put("viewerId", viewerId).toString());
            if (room.publisherWs != null) {
                room.publisherWs.sendText(new JSONObject().put("viewerJoined", viewerId).toString());
            }
        }
        System.out.println("[WebRTC] Viewer joined room: " + roomName + " (" + viewerId + ")");

        while (!ws.isClosed()) {
            WsFrame frame = ws.readFrame();
            if (frame == null) break;

            if (frame.opcode == 0x1) {
                String text = new String(frame.payload, java.nio.charset.StandardCharsets.UTF_8);
                try {
                    JSONObject msg = new JSONObject(text);
                    if ("publisher".equals(msg.optString("target"))) {
                        msg.put("viewerId", viewerId);
                        synchronized (room) {
                            if (room.publisherWs != null) {
                                room.publisherWs.sendText(msg.toString());
                            }
                        }
                    }
                } catch (Exception ignored) {}
            } else if (frame.opcode == 0x8) {
                break;
            }
        }

        synchronized (room) {
            room.viewers.remove(viewerId);
            if (room.publisherWs != null) {
                try { room.publisherWs.sendText(new JSONObject().put("viewerLeft", viewerId).toString()); } catch (Exception ignored) {}
            }
        }
        ws.close(1000, "bye");
        System.out.println("[WebRTC] Viewer left room: " + roomName);
    }

    // Чтение строки HTTP-заголовка с внутренней обработкой исключений
    private String readLine(InputStream in) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') {
                    byte[] arr = baos.toByteArray();
                    int len = arr.length;
                    if (len > 0 && arr[len - 1] == '\r') len--;
                    return new String(arr, 0, len, "ISO-8859-1");
                }
                baos.write(b);
            }
            return baos.size() > 0 ? baos.toString("ISO-8859-1") : null;
        } catch (IOException e) {
            return null;
        }
    }

    static class Room {
        String name;
        String password = null;
        volatile WsConnection publisherWs;
        final Map<String, WsConnection> viewers = new ConcurrentHashMap<>();
        Room(String name) { this.name = name; }
    }

    static class WsFrame {
        int opcode;
        byte[] payload;
    }

    static class WsConnection {
        Socket socket;
        BufferedInputStream in;
        OutputStream out;
        volatile boolean closed = false;

        WsConnection(Socket socket, BufferedInputStream in, OutputStream out) {
            this.socket = socket;
            this.in = in;
            this.out = out;
        }

        boolean isClosed() { return closed || socket.isClosed(); }

        // Чтение кадра WebSocket с внутренней обработкой исключений
        WsFrame readFrame() {
            try {
                int b0 = in.read();
                if (b0 == -1) return null;

                int b1 = in.read();
                if (b1 == -1) return null;

                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;

                if (len == 126) {
                    len = (in.read() << 8) | in.read();
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
                }

                if (len > 1024 * 1024 * 16) return null; // Frame too big

                byte[] mask = null;
                if (masked) {
                    mask = new byte[4];
                    for (int i = 0; i < 4; i++) mask[i] = (byte) in.read();
                }

                byte[] payload = new byte[(int) len];
                int read = 0;
                while (read < len) {
                    int r = in.read(payload, read, (int) len - read);
                    if (r == -1) break;
                    read += r;
                }
                if (mask != null) {
                    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                }

                WsFrame f = new WsFrame();
                f.opcode = opcode;
                f.payload = payload;
                return f;
            } catch (IOException e) {
                return null;
            }
        }

        // Отправка текста с внутренней обработкой исключений
        void sendText(String text) {
            try {
                byte[] payload = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                synchronized (this) {
                    if (closed) return;
                    out.write(0x81);
                    if (payload.length < 126) {
                        out.write(payload.length);
                    } else if (payload.length < 65536) {
                        out.write(126);
                        out.write((payload.length >> 8) & 0xFF);
                        out.write(payload.length & 0xFF);
                    } else {
                        out.write(127);
                        for (int i = 7; i >= 0; i--) {
                            out.write((int) (((long) payload.length >> (8 * i)) & 0xFF));
                        }
                    }
                    out.write(payload);
                    out.flush();
                }
            } catch (IOException e) {
                closed = true;
            }
        }

        void close(int code, String reason) {
            if (closed) return;
            synchronized (this) {
                try {
                    out.write(0x88);
                    out.write(2 + reason.getBytes().length);
                    out.write((code >> 8) & 0xFF);
                    out.write(code & 0xFF);
                    out.write(reason.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignored) {}
                closed = true;
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }
}