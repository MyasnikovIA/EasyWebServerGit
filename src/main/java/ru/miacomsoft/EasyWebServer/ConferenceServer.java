package ru.miacomsoft.EasyWebServer;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 信令服务器：用于多人 Mesh 视频会议。
 * 负责交换参与者的 SDP 和 ICE 候选者。
 */
public class ConferenceServer {
    private final int port;
    private final SSLServerSocketFactory sslFactory;
    private volatile boolean running = false;
    private ServerSocket server;

    // 房间名 -> (PeerId -> Peer 对象)
    private static final ConcurrentHashMap<String, Map<String, Peer>> ROOMS = new ConcurrentHashMap<>();

    public ConferenceServer(int port, SSLServerSocketFactory sslFactory) {
        this.port = port;
        this.sslFactory = sslFactory;
    }

    public void start() {
        running = true;
        new Thread(() -> {
            try {
                if (sslFactory != null) {
                    SSLServerSocket ss = (SSLServerSocket) sslFactory.createServerSocket(port);
                    ss.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
                    server = ss;
                    System.out.println("[Conference] Signaling server (WSS) started on port " + port);
                } else {
                    server = new ServerSocket(port);
                    System.out.println("[Conference] Signaling server (WS) started on port " + port);
                }
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
            if (socket instanceof SSLSocket) {
                try { ((SSLSocket) socket).startHandshake(); } catch (Exception e) { return; }
            }

            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 3) return;

            Map<String, String> headers = new ConcurrentHashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) headers.put(line.substring(0, idx).trim().toLowerCase(), line.substring(idx + 1).trim());
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

            String key = headers.get("sec-websocket-key");
            String accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes()));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes());
            out.flush();

            String roomName = "main", userName = "User";
            for (String p : query.split("&")) {
                String[] kv = p.split("=", 2);
                if (kv.length == 2) {
                    if ("room".equals(kv[0])) roomName = java.net.URLDecoder.decode(kv[1], "UTF-8");
                    if ("user".equals(kv[0])) userName = java.net.URLDecoder.decode(kv[1], "UTF-8");
                }
            }

            String peerId = UUID.randomUUID().toString().substring(0, 8);
            Peer peer = new Peer(peerId, userName, new WsConnection(socket, in, out));
            Map<String, Peer> room = ROOMS.computeIfAbsent(roomName, k -> new ConcurrentHashMap<>());

            // 1. 发送已有成员列表给新加入者
            JSONObject welcome = new JSONObject();
            welcome.put("type", "welcome");
            welcome.put("peerId", peerId);
            welcome.put("userName", userName);
            JSONArray peersArr = new JSONArray();
            for (Peer p : room.values()) {
                peersArr.put(new JSONObject().put("peerId", p.peerId).put("userName", p.userName));
            }
            welcome.put("peers", peersArr);
            peer.ws.sendText(welcome.toString());

            // 2. 通知其他人有新成员加入
            JSONObject joined = new JSONObject();
            joined.put("type", "peerJoined");
            joined.put("peerId", peerId);
            joined.put("userName", userName);
            for (Peer p : room.values()) p.ws.sendText(joined.toString());

            room.put(peerId, peer);
            System.out.println("[Conference] " + userName + " (" + peerId + ") joined " + roomName);

            // 3. 转发 SDP / ICE
            while (!peer.ws.isClosed()) {
                WsFrame frame = peer.ws.readFrame();
                if (frame == null) break;
                if (frame.opcode == 0x1) {
                    try {
                        JSONObject msg = new JSONObject(new String(frame.payload, java.nio.charset.StandardCharsets.UTF_8));
                        String target = msg.optString("target", "");
                        if (!target.isEmpty()) {
                            Peer targetPeer = room.get(target);
                            if (targetPeer != null) {
                                msg.put("from", peerId);
                                targetPeer.ws.sendText(msg.toString());
                            }
                        }
                    } catch (Exception ignored) {}
                } else if (frame.opcode == 0x8) break;
            }

            // 4. 断开清理
            room.remove(peerId);
            JSONObject left = new JSONObject();
            left.put("type", "peerLeft");
            left.put("peerId", peerId);
            for (Peer p : room.values()) p.ws.sendText(left.toString());
            peer.ws.close(1000, "bye");
            System.out.println("[Conference] " + userName + " left " + roomName);

        } catch (Exception ignored) {}
    }

    private String readLine(InputStream in) throws IOException {
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
    }

    static class Peer {
        String peerId, userName;
        WsConnection ws;
        Peer(String id, String name, WsConnection ws) { this.peerId = id; this.userName = name; this.ws = ws; }
    }

    static class WsFrame { int opcode; byte[] payload; }

    static class WsConnection {
        Socket socket; BufferedInputStream in; OutputStream out; volatile boolean closed = false;
        WsConnection(Socket s, BufferedInputStream i, OutputStream o) { socket = s; in = i; out = o; }
        boolean isClosed() { return closed || socket.isClosed(); }
        WsFrame readFrame() {
            try {
                int b0 = in.read(); if (b0 == -1) return null;
                int b1 = in.read(); if (b1 == -1) return null;
                int opcode = b0 & 0x0F; boolean masked = (b1 & 0x80) != 0; long len = b1 & 0x7F;
                if (len == 126) len = ((long) in.read() << 8) | in.read();
                else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | in.read(); }
                byte[] mask = null;
                if (masked) { mask = new byte[4]; for (int i = 0; i < 4; i++) mask[i] = (byte) in.read(); }
                byte[] payload = new byte[(int) len];
                int read = 0;
                while (read < len) { int r = in.read(payload, read, (int) len - read); if (r == -1) break; read += r; }
                if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                WsFrame f = new WsFrame(); f.opcode = opcode; f.payload = payload; return f;
            } catch (IOException e) { return null; }
        }
        synchronized void sendText(String text) {
            try {
                if (closed) return;
                byte[] p = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.write(0x81);
                if (p.length < 126) out.write(p.length);
                else if (p.length < 65536) { out.write(126); out.write((p.length >> 8) & 0xFF); out.write(p.length & 0xFF); }
                else { out.write(127); for (int i = 7; i >= 0; i--) out.write((int)(((long)p.length >> (8*i)) & 0xFF)); }
                out.write(p); out.flush();
            } catch (IOException e) { closed = true; }
        }
        void close(int code, String reason) {
            if (closed) return;
            synchronized (this) {
                try { out.write(0x88); out.write(2 + reason.getBytes().length); out.write((code >> 8) & 0xFF); out.write(code & 0xFF); out.write(reason.getBytes()); out.flush(); } catch (Exception ignored) {}
                closed = true; try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }
}