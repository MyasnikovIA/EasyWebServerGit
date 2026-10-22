package ru.miacomsoft.EasyWebServer;

import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TurnServer {
    private static final int MAGIC_COOKIE = 0x2112A442;
    private static final String REALM = "smwrap.ru";
    private static final String PASSWORD = "pass";

    private final int port;
    private final int relayStart;
    private final int relayEnd;
    private final String publicIpStr;
    private final String publicHostStr;
    private volatile boolean running = false;
    private DatagramSocket socket;
    private ServerSocket tcpServer;
    private final Map<String, RelayAllocation> allocations = new ConcurrentHashMap<>();
    private final Set<Integer> usedRelayPorts = ConcurrentHashMap.newKeySet();

    public TurnServer(int port, int relayStart, int relayEnd, String publicIp, String publicHost) {
        this.port = port;
        this.relayStart = relayStart;
        this.relayEnd = relayEnd;
        this.publicIpStr = publicIp;
        this.publicHostStr = publicHost;
    }

    public void start() {
        running = true;
        new Thread(this::udpLoop, "TURN-UDP").start();
        new Thread(this::tcpLoop, "TURN-TCP").start();
        System.out.println("[TURN] Server started on port " + port
                + " (UDP & TCP), relay range " + relayStart + "-" + relayEnd);
    }

    public void stop() {
        running = false;
        if (socket != null) socket.close();
        try { if (tcpServer != null) tcpServer.close(); } catch (Exception ignored) {}
        allocations.values().forEach(a -> a.relaySocket.close());
        allocations.clear();
        usedRelayPorts.clear();
    }

    private void udpLoop() {
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            byte[] buf = new byte[4096];
            while (running) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                final DatagramPacket pp = new DatagramPacket(
                        Arrays.copyOf(p.getData(), p.getLength()),
                        p.getLength(), p.getAddress(), p.getPort());
                new Thread(() -> processPacket(pp, null)).start();
            }
        } catch (BindException e) {
            System.err.println("[TURN] UDP port " + port + " already in use!");
        } catch (IOException e) {
            if (running) System.err.println("[TURN] UDP loop: " + e.getMessage());
        }
    }

    private void tcpLoop() {
        try {
            tcpServer = new ServerSocket();
            tcpServer.setReuseAddress(true);
            tcpServer.bind(new InetSocketAddress(port));
            while (running) {
                Socket s = tcpServer.accept();
                new Thread(() -> handleTcp(s)).start();
            }
        } catch (BindException e) {
            System.err.println("[TURN] TCP port " + port + " already in use!");
        } catch (IOException e) {
            if (running) System.err.println("[TURN] TCP loop: " + e.getMessage());
        }
    }

    private void handleTcp(Socket s) {
        try (s) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            byte[] lenBuf = new byte[2];
            while (in.read(lenBuf) == 2) {
                int len = ((lenBuf[0] & 0xFF) << 8) | (lenBuf[1] & 0xFF);
                byte[] msg = new byte[len];
                int read = 0;
                while (read < len) {
                    int r = in.read(msg, read, len - read);
                    if (r == -1) break;
                    read += r;
                }
                if (read != len) break;
                processPacket(new DatagramPacket(msg, len, s.getInetAddress(), s.getPort()), out);
            }
        } catch (Exception ignored) {}
    }

    private void processPacket(DatagramPacket p, OutputStream tcpOut) {
        byte[] data = p.getData();
        int len = p.getLength();
        if (len < 20) return;

        int type = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        int magic = ((data[4] & 0xFF) << 24) | ((data[5] & 0xFF) << 16)
                | ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        if (magic != MAGIC_COOKIE) return;

        String clientKey = p.getAddress().getHostAddress() + ":" + p.getPort();

        try {
            if (type == 0x0003) handleAllocate(p, tcpOut, clientKey);
            else if (type == 0x0008) sendSuccess(p, 0x0008, tcpOut);
            else if (type == 0x0009) sendSuccess(p, 0x0009, tcpOut);
            else if (type == 0x0016) handleSend(p, clientKey);
        } catch (Exception ignored) {}
    }

    private void handleAllocate(DatagramPacket p, OutputStream tcpOut, String clientKey) throws Exception {
        byte[] data = p.getData();
        int attrOffset = 20;
        byte[] nonce = null;

        while (attrOffset < p.getLength()) {
            int type = ((data[attrOffset] & 0xFF) << 8) | (data[attrOffset + 1] & 0xFF);
            int len = ((data[attrOffset + 2] & 0xFF) << 8) | (data[attrOffset + 3] & 0xFF);
            if (type == 0x0015) nonce = Arrays.copyOfRange(data, attrOffset + 4, attrOffset + 4 + len);
            attrOffset += 4 + len + ((4 - len % 4) % 4);
        }

        if (nonce == null) {
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            writeHeader(resp, 0x0113, 0);
            writeAttr(resp, 0x0015, "nonce1234567890".getBytes());
            writeAttr(resp, 0x0014, REALM.getBytes());
            sendPacket(p, resp.toByteArray(), tcpOut);
            return;
        }

        int relayPort = allocateRelayPort();
        if (relayPort < 0) {
            System.err.println("[TURN] No free relay ports!");
            return;
        }

        DatagramSocket relaySocket;
        try {
            relaySocket = new DatagramSocket(relayPort);
        } catch (Exception e) {
            usedRelayPorts.remove(relayPort);
            System.err.println("[TURN] Cannot bind relay port " + relayPort);
            return;
        }

        RelayAllocation alloc = new RelayAllocation(p.getAddress(), p.getPort(), relaySocket, tcpOut, relayPort);
        allocations.put(clientKey, alloc);
        new Thread(() -> relayLoop(alloc)).start();

        byte[] publicIpBytes = resolvePublicIp();

        ByteArrayOutputStream resp = new ByteArrayOutputStream();
        writeHeader(resp, 0x0103, 0);
        writeXorAddress(resp, 0x0016, publicIpBytes, relayPort);   // XOR-RELAYED-ADDRESS
        writeXorAddress(resp, 0x0020, p.getAddress().getAddress(), p.getPort()); // XOR-MAPPED-ADDRESS
        writeAttr(resp, 0x000D, new byte[]{0x00, 0x00, 0x0E, 0x10});
        sendPacket(p, resp.toByteArray(), tcpOut);
        System.out.println("[TURN] Allocate " + clientKey + " -> relay " + relayPort);
    }

    private synchronized int allocateRelayPort() {
        for (int i = relayStart; i <= relayEnd; i++) {
            if (!usedRelayPorts.contains(i)) {
                try {
                    DatagramSocket test = new DatagramSocket(i);
                    test.close();
                    usedRelayPorts.add(i);
                    return i;
                } catch (Exception ignored) {}
            }
        }
        return -1;
    }

    private void handleSend(DatagramPacket p, String clientKey) throws Exception {
        RelayAllocation alloc = allocations.get(clientKey);
        if (alloc == null) return;

        byte[] data = p.getData();
        int attrOffset = 20;
        byte[] peerIp = null;
        int peerPort = 0;
        byte[] payload = null;

        while (attrOffset < p.getLength()) {
            int type = ((data[attrOffset] & 0xFF) << 8) | (data[attrOffset + 1] & 0xFF);
            int len = ((data[attrOffset + 2] & 0xFF) << 8) | (data[attrOffset + 3] & 0xFF);
            if (type == 0x0012) {
                peerPort = (((data[attrOffset+6] & 0xFF) << 8) | (data[attrOffset+7] & 0xFF)) ^ (MAGIC_COOKIE >> 16);
                int ipInt = ((data[attrOffset+8] & 0xFF) << 24) | ((data[attrOffset+9] & 0xFF) << 16)
                        | ((data[attrOffset+10] & 0xFF) << 8) | (data[attrOffset+11] & 0xFF);
                int realIp = ipInt ^ MAGIC_COOKIE;
                peerIp = new byte[]{(byte)(realIp >> 24), (byte)(realIp >> 16), (byte)(realIp >> 8), (byte)realIp};
            } else if (type == 0x0013) {
                payload = Arrays.copyOfRange(data, attrOffset + 4, attrOffset + 4 + len);
            }
            attrOffset += 4 + len + ((4 - len % 4) % 4);
        }

        if (peerIp != null && payload != null) {
            DatagramPacket out = new DatagramPacket(payload, payload.length,
                    InetAddress.getByAddress(peerIp), peerPort);
            alloc.relaySocket.send(out);
        }
    }

    private void relayLoop(RelayAllocation alloc) {
        byte[] buf = new byte[4096];
        try {
            while (!alloc.relaySocket.isClosed()) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                alloc.relaySocket.receive(p);
                ByteArrayOutputStream resp = new ByteArrayOutputStream();
                writeHeader(resp, 0x0017, 0);
                writeXorAddress(resp, 0x0012, p.getAddress().getAddress(), p.getPort());
                writeAttr(resp, 0x0013, Arrays.copyOfRange(p.getData(), p.getOffset(),
                        p.getOffset() + p.getLength()));
                byte[] data = resp.toByteArray();

                if (alloc.tcpOut != null) {
                    synchronized (alloc.tcpOut) {
                        alloc.tcpOut.write((data.length >> 8) & 0xFF);
                        alloc.tcpOut.write(data.length & 0xFF);
                        alloc.tcpOut.write(data);
                        alloc.tcpOut.flush();
                    }
                } else if (socket != null && !socket.isClosed()) {
                    socket.send(new DatagramPacket(data, data.length, alloc.clientIp, alloc.clientPort));
                }
            }
        } catch (Exception ignored) {}
    }

    private byte[] resolvePublicIp() {
        if (publicIpStr != null && !publicIpStr.isEmpty()) {
            try {
                InetAddress a = InetAddress.getByName(publicIpStr);
                if (a.getAddress().length == 4) return a.getAddress();
            } catch (Exception ignored) {}
        }
        if (publicHostStr != null && !publicHostStr.isEmpty()) {
            try {
                InetAddress[] all = InetAddress.getAllByName(publicHostStr);
                for (InetAddress a : all) if (a.getAddress().length == 4) return a.getAddress();
            } catch (Exception ignored) {}
        }
        try { return InetAddress.getLocalHost().getAddress(); }
        catch (Exception e) { return new byte[]{127,0,0,1}; }
    }

    private void writeHeader(ByteArrayOutputStream out, int type, int length) {
        out.write((type >> 8) & 0xFF); out.write(type & 0xFF);
        out.write((length >> 8) & 0xFF); out.write(length & 0xFF);
        out.write(0x21); out.write(0x12); out.write(0xA4); out.write(0x42);
        out.write(new byte[12], 0, 12);
    }

    private void writeXorAddress(ByteArrayOutputStream out, int type, byte[] ip, int port) {
        out.write((type >> 8) & 0xFF); out.write(type & 0xFF);
        out.write(0x00); out.write(0x08);
        out.write(0x00); out.write(0x01);
        int xPort = port ^ (MAGIC_COOKIE >> 16);
        out.write((xPort >> 8) & 0xFF); out.write(xPort & 0xFF);
        int ipInt = ((ip[0] & 0xFF) << 24) | ((ip[1] & 0xFF) << 16)
                | ((ip[2] & 0xFF) << 8) | (ip[3] & 0xFF);
        int xIp = ipInt ^ MAGIC_COOKIE;
        out.write((xIp >> 24) & 0xFF); out.write((xIp >> 16) & 0xFF);
        out.write((xIp >> 8) & 0xFF); out.write(xIp & 0xFF);
    }

    private void writeAttr(ByteArrayOutputStream out, int type, byte[] val) {
        out.write((type >> 8) & 0xFF); out.write(type & 0xFF);
        out.write((val.length >> 8) & 0xFF); out.write(val.length & 0xFF);
        out.write(val, 0, val.length);
        int pad = (4 - val.length % 4) % 4;
        for (int i = 0; i < pad; i++) out.write(0);
    }

    private void sendSuccess(DatagramPacket req, int type, OutputStream tcpOut) throws Exception {
        ByteArrayOutputStream resp = new ByteArrayOutputStream();
        writeHeader(resp, type | 0x0100, 0);
        sendPacket(req, resp.toByteArray(), tcpOut);
    }

    private void sendPacket(DatagramPacket req, byte[] data, OutputStream tcpOut) throws Exception {
        if (tcpOut != null) {
            tcpOut.write((data.length >> 8) & 0xFF);
            tcpOut.write(data.length & 0xFF);
            tcpOut.write(data);
            tcpOut.flush();
        } else if (socket != null && !socket.isClosed()) {
            socket.send(new DatagramPacket(data, data.length, req.getAddress(), req.getPort()));
        }
    }

    static class RelayAllocation {
        final InetAddress clientIp;
        final int clientPort;
        final DatagramSocket relaySocket;
        final OutputStream tcpOut;
        final int relayPort;
        RelayAllocation(InetAddress ip, int port, DatagramSocket sock, OutputStream out, int rport) {
            this.clientIp = ip; this.clientPort = port; this.relaySocket = sock;
            this.tcpOut = out; this.relayPort = rport;
        }
    }
}