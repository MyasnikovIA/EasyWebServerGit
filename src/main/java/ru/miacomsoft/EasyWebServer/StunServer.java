package ru.miacomsoft.EasyWebServer;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/**
 * Минимальный STUN-сервер (RFC 5389, только Binding Request → Binding Response).
 *
 * Позволяет браузеру в `new RTCPeerConnection({ iceServers: [{ urls: 'stun:host:port' }] })`
 * узнать свой публичный IP:порт (srflx-кандидат) для ICE.
 *
 * Только UDP. TCP-транспорт для STUN в WebRTC не используется.
 *
 * ВАЖНО: если сервер за NAT — на роутере должно быть
 * UDP <STUN_PORT> → <server-ip>:<STUN_PORT>.
 */
public class StunServer {

    private static final int MAGIC_COOKIE = 0x2112A442;
    private static final int MSG_BINDING_REQUEST  = 0x0001;
    private static final int MSG_BINDING_RESPONSE = 0x0101;

    private final int port;
    private volatile boolean running = false;
    private DatagramSocket socket;

    public StunServer(int port) {
        this.port = port;
    }

    public void start() {
        running = true;
        new Thread(() -> {
            try {
                socket = new DatagramSocket(port);
                System.out.println("[STUN] UDP server started on port " + port);
                byte[] buf = new byte[2048];
                while (running) {
                    try {
                        DatagramPacket req = new DatagramPacket(buf, buf.length);
                        socket.receive(req);
                        handle(req);
                    } catch (Exception e) {
                        if (!running) break;
                    }
                }
            } catch (Exception e) {
                if (running) {
                    System.err.println("[STUN] Failed to start: " + e.getMessage());
                }
            }
        }, "STUN-Server").start();
    }

    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        System.out.println("[STUN] stopped");
    }

    // ============================================================
    private void handle(DatagramPacket req) {
        byte[] data = req.getData();
        int len = req.getLength();
        if (len < 20) return;

        int magic = ((data[4] & 0xFF) << 24)
                | ((data[5] & 0xFF) << 16)
                | ((data[6] & 0xFF) << 8)
                |  (data[7] & 0xFF);
        if (magic != MAGIC_COOKIE) return;

        int msgType = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        if (msgType != MSG_BINDING_REQUEST) return;

        byte[] txId = new byte[12];
        System.arraycopy(data, 8, txId, 0, 12);

        InetAddress clientAddr = req.getAddress();
        int clientPort = req.getPort();
        byte[] ip = clientAddr.getAddress();
        if (ip.length != 4) return;

        int xPort = clientPort ^ (MAGIC_COOKIE >>> 16);
        int ipInt = ((ip[0] & 0xFF) << 24) | ((ip[1] & 0xFF) << 16)
                | ((ip[2] & 0xFF) << 8)  |  (ip[3] & 0xFF);
        int xIp = ipInt ^ MAGIC_COOKIE;

        byte[] attr = new byte[12];
        attr[0] = 0x00; attr[1] = 0x20;
        attr[2] = 0x00; attr[3] = 0x08;
        attr[4] = 0x00;
        attr[5] = 0x01;
        attr[6] = (byte) ((xPort >> 8) & 0xFF);
        attr[7] = (byte) (xPort & 0xFF);
        attr[8]  = (byte) ((xIp >> 24) & 0xFF);
        attr[9]  = (byte) ((xIp >> 16) & 0xFF);
        attr[10] = (byte) ((xIp >> 8)  & 0xFF);
        attr[11] = (byte) (xIp & 0xFF);

        byte[] resp = new byte[20 + attr.length];
        resp[0] = 0x01; resp[1] = 0x01;
        resp[2] = (byte) ((attr.length >> 8) & 0xFF);
        resp[3] = (byte) (attr.length & 0xFF);
        resp[4] = 0x21; resp[5] = 0x12; resp[6] = (byte) 0xA4; resp[7] = 0x42;
        System.arraycopy(txId, 0, resp, 8, 12);
        System.arraycopy(attr, 0, resp, 20, attr.length);

        try {
            DatagramPacket out = new DatagramPacket(resp, resp.length, clientAddr, clientPort);
            socket.send(out);
        } catch (Exception ignored) {}
    }
}