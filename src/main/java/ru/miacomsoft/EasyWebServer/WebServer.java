package ru.miacomsoft.EasyWebServer;

import ru.miacomsoft.EasyWebServer.util.callbackType.CallbackPage;
import ru.miacomsoft.EasyWebServer.util.callbackType.CallbackProcedure;
import ru.miacomsoft.EasyWebServer.util.structObject.JavaInnerClassObject;
import ru.miacomsoft.EasyWebServer.util.structObject.JavaTerminalClassObject;

import javax.net.ssl.SSLServerSocketFactory;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static ru.miacomsoft.EasyWebServer.PacketManager.getPageJar;
import static ru.miacomsoft.EasyWebServer.PacketManager.getWebPage;

public class WebServer implements Runnable {
    public static CallbackProcedure callbackProcedure = null;
    private static final Logger LOGGER = Logger.getLogger(WebServer.class.getName());
    private static boolean isRunServer = false;
    private static WebServer server;

    private static ConferenceServer conferenceServer;      // mesh, WSS, порт CONFERENCE_PORT
    private static WebRTCSignalingServer webrtcSignalingServer; // pub/sub, WSS, порт VIDEOCHAT_PORT
    private static TurnServer turnServer;                  // TURN, порт TURN_PORT
    private static StunServer stunServer;                  // STUN, порт STUN_PORT
    private static ServerSocket httpRedirectSocket;

    public WebServer() {}

    public WebServer(Class<?> mainClass) {
        System.out.println("Список классов страниц: " + getWebPage(mainClass));
    }

    public static void start() {
        server = new WebServer();
        Thread thread = new Thread(server);
        thread.start();
        Runtime.getRuntime().addShutdownHook(new ShutDown());
        try { thread.join(); } catch (Exception e) { e.printStackTrace(); }
    }

    public static void stop() {
        isRunServer = false;
        if (conferenceServer != null) conferenceServer.stop();
        if (webrtcSignalingServer != null) webrtcSignalingServer.stop();
        if (turnServer != null) turnServer.stop();
        if (stunServer != null) stunServer.stop();
        if (httpRedirectSocket != null && !httpRedirectSocket.isClosed()) {
            try { httpRedirectSocket.close(); } catch (Exception ignored) {}
        }
    }

    public void onPage(String path, StringBuffer contentText) {
        ServerResource.pagesListContent.put(path, contentText);
    }
    public void onPage(String path, StringBuffer contentText, String mime) {
        ServerResource.pagesListContent.put(path, contentText);
    }
    public void onPage(CallbackProcedure callbackProcedure) { this.callbackProcedure = callbackProcedure; }
    public void onPage(String path, CallbackPage callbackPage) { ServerResource.pagesList.put(path, callbackPage); }
    public void onPage(String path, File file) { ServerResource.pagesListFile.put(path, file); }

    public void initConfig(String args) {
        ServerConstant.config = (args.length() == 0) ? new ServerConstant("config.ini") : new ServerConstant(args);
    }

    public Boolean config(String name, String value) {
        if (value.contains(";")) return handleListProperty(name, value);
        if (name.startsWith("DATABASES.")) return handleDatabaseProperty(name, value);
        return ServerConstant.config.setProp(name, value);
    }

    private Boolean handleDatabaseProperty(String name, String value) {
        try {
            String[] parts = name.split("\\.", 2);
            if (parts.length != 2) return false;
            DatabaseConfig cfg = DatabaseConfig.parse(value);
            if (cfg != null) { ServerConstant.config.DATABASES.put(parts[1], cfg); return true; }
        } catch (Exception e) { System.err.println("Error: " + e.getMessage()); }
        return false;
    }

    private Boolean handleListProperty(String name, String value) {
        try {
            if (!isListProperty(name)) return ServerConstant.config.setProp(name, value);
            List<String> vals = new ArrayList<>();
            for (String v : value.split(";")) { String t = v.trim(); if (!t.isEmpty()) vals.add(t); }
            switch (name) {
                case "WEBAPP_DIR":
                    ServerConstant.config.WEBAPP_DIRS.clear();
                    for (String d : vals) {
                        String full = (!d.contains("/") && !d.contains("\\"))
                                ? ServerConstant.config.SERVER_HOM + File.separator + d : d;
                        ServerConstant.config.WEBAPP_DIRS.add(full);
                    }
                    if (!ServerConstant.config.WEBAPP_DIRS.isEmpty())
                        ServerConstant.config.WEBAPP_DIR = ServerConstant.config.WEBAPP_DIRS.get(0);
                    return true;
                case "LIB_CSS": ServerConstant.config.LIB_CSS.clear(); ServerConstant.config.LIB_CSS.addAll(vals); return true;
                case "LIB_JS": ServerConstant.config.LIB_JS.clear(); ServerConstant.config.LIB_JS.addAll(vals); return true;
                case "LIB_JAR": ServerConstant.config.LIB_JAR.clear(); ServerConstant.config.LIB_JAR.addAll(vals); return true;
                default: return ServerConstant.config.setProp(name, value);
            }
        } catch (Exception e) { System.err.println("Error list: " + e.getMessage()); return false; }
    }

    private boolean isListProperty(String n) {
        return n.endsWith("_LIST") || n.endsWith("_DIRS") || n.equals("WEBAPP_DIR")
                || n.equals("LIB_CSS") || n.equals("LIB_JS") || n.equals("LIB_JAR") || n.equals("MIME_MAP");
    }

    public Boolean config(String name, Boolean value) { return ServerConstant.config.setProp(name, value); }
    public void addPageJar(String pathJarFile) { System.out.println("Jar pages: " + getPageJar(pathJarFile)); }

    @Override
    public void run() {
        int port = Integer.parseInt(ServerConstant.config.DEFAULT_PORT);
        try {
            isRunServer = true;
            viewWebResource(port);

            boolean hasKeystore = ServerConstant.config.HTTPS_KEYSTORE != null
                    && !ServerConstant.config.HTTPS_KEYSTORE.isEmpty();
            boolean hasPem = ServerConstant.config.HTTPS_CERT != null && !ServerConstant.config.HTTPS_CERT.isEmpty()
                    && ServerConstant.config.HTTPS_KEY != null && !ServerConstant.config.HTTPS_KEY.isEmpty();

            ServerSocket ss;
            if (ServerConstant.config.HTTPS_ENABLED && (hasKeystore || hasPem)) {
                System.out.println("[WebServer] HTTPS mode, port " + port);
                try {
                    ss = SSLManager.createSSLServerSocket(port,
                            ServerConstant.config.HTTPS_KEYSTORE,
                            ServerConstant.config.HTTPS_KEYSTORE_PASSWORD,
                            ServerConstant.config.HTTPS_KEY_PASSWORD,
                            ServerConstant.config.HTTPS_CERT,
                            ServerConstant.config.HTTPS_KEY);
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "SSL fail", e);
                    return;
                }
            } else {
                System.out.println("[WebServer] HTTP mode, port " + port);
                ss = new ServerSocket(port);
            }

            // WSS factory (общая для mesh и pub/sub)
            SSLServerSocketFactory wssFactory = null;
            if (ServerConstant.config.HTTPS_ENABLED && (hasKeystore || hasPem)) {
                try {
                    wssFactory = SSLManager.createSSLServerSocketFactory(
                            ServerConstant.config.HTTPS_KEYSTORE,
                            ServerConstant.config.HTTPS_KEYSTORE_PASSWORD,
                            ServerConstant.config.HTTPS_KEY_PASSWORD,
                            ServerConstant.config.HTTPS_CERT,
                            ServerConstant.config.HTTPS_KEY);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "WSS factory fail", e);
                }
            }

            // ConferenceServer (mesh) — порт CONFERENCE_PORT (9093)
            if (ServerConstant.config.CONFERENCE_PORT > 0) {
                conferenceServer = new ConferenceServer(ServerConstant.config.CONFERENCE_PORT, wssFactory);
                conferenceServer.start();
            }

            // WebRTCSignalingServer (pub/sub) — порт VIDEOCHAT_PORT (9095)
            if (ServerConstant.config.VIDEOCHAT_PORT > 0) {
                webrtcSignalingServer = new WebRTCSignalingServer(ServerConstant.config.VIDEOCHAT_PORT, wssFactory);
                webrtcSignalingServer.start();
            }

            // TURN (9094)
            if (ServerConstant.config.TURN_PORT > 0) {
                turnServer = new TurnServer(
                        ServerConstant.config.TURN_PORT,
                        ServerConstant.config.TURN_RELAY_PORT_START,
                        ServerConstant.config.TURN_RELAY_PORT_END,
                        ServerConstant.config.TURN_PUBLIC_IP,
                        ServerConstant.config.TURN_PUBLIC_HOST);
                turnServer.start();
            }

            // STUN
            if (ServerConstant.config.STUN_PORT > 0) {
                stunServer = new StunServer(ServerConstant.config.STUN_PORT);
                stunServer.start();
            }

            if (ServerConstant.config.HTTP_REDIRECT_PORT > 0) {
                startHttpRedirectServer(ServerConstant.config.HTTP_REDIRECT_PORT);
            }

            while (isRunServer) {
                Socket socket;
                try { socket = ss.accept(); }
                catch (Exception e) { if (!isRunServer) break; continue; }
                new Thread(new ServerResourceHandler(socket), "http-" + port).start();
            }
        } catch (Exception e) {
            Logger.getLogger(WebServer.class.getName()).log(Level.SEVERE, null, e);
        }
    }

    private void startHttpRedirectServer(int httpPort) {
        Thread t = new Thread(() -> {
            try {
                httpRedirectSocket = new ServerSocket(httpPort);
                while (isRunServer) {
                    try { handleHttpRedirect(httpRedirectSocket.accept()); }
                    catch (Exception ignored) {}
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Redirect fail on " + httpPort, e);
            }
        }, "http-redirect");
        t.setDaemon(true);
        t.start();
    }

    private void handleHttpRedirect(Socket client) {
        try (client) {
            client.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    client.getInputStream(), java.nio.charset.StandardCharsets.ISO_8859_1));
            String line = in.readLine();
            if (line == null || line.isEmpty()) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String path = parts[1];
            String host = null, h;
            while ((h = in.readLine()) != null && !h.isEmpty()) {
                int idx = h.indexOf(':');
                if (idx > 0 && "host".equalsIgnoreCase(h.substring(0, idx).trim()))
                    host = h.substring(idx + 1).trim();
            }
            if (host == null || host.isEmpty()) host = "localhost";
            int colon = host.indexOf(':'); if (colon > 0) host = host.substring(0, colon);
            int p = Integer.parseInt(ServerConstant.config.DEFAULT_PORT);
            String resp = "HTTP/1.1 301 Moved Permanently\r\nLocation: https://" + host + ":" + p + path
                    + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            client.getOutputStream().write(resp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            client.getOutputStream().flush();
        } catch (Exception ignored) {}
    }

    private void viewWebResource(int port) {
        System.out.println("port: " + port);
        System.out.println("-----------------------");
        for (JavaInnerClassObject page : ServerResource.pagesJavaInnerClass.values()) {
            System.out.println("http://127.0.0.1:" + port + "/" + page.url);
        }
        for (JavaTerminalClassObject page : ServerResource.pagesJavaTerminalClass.values()) {
            System.out.println("[TERM] http://127.0.0.1:" + port + "/" + page.url);
        }
    }

    protected synchronized static void WriteToFile(String msg) {
        if (ServerConstant.config.LOG_FILE.length() == 0) System.err.println(msg);
        else try { FileWriter f = new FileWriter(new File(ServerConstant.config.LOG_FILE), true); f.write(msg); f.flush(); }
        catch (Exception ignored) {}
    }

    public static String getOS() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("nix") || os.contains("nux") || os.contains("aix")) return "linux";
        if (os.contains("mac")) return "mac";
        if (os.contains("sunos") || os.contains("solaris")) return "solaris";
        if (os.contains("bsd")) return "bsd";
        return "unknown";
    }

    public static boolean isWindows() { return getOS().equals("windows"); }
    public static boolean isLinux() { return getOS().equals("linux"); }
    public static boolean isUnixLike() {
        String o = getOS();
        return o.equals("linux") || o.equals("mac") || o.equals("solaris") || o.equals("bsd") || o.equals("aix");
    }

    static void shutDown() {
        try {
            LOGGER.info("Shutting down...");
            if (conferenceServer != null) conferenceServer.stop();
            if (webrtcSignalingServer != null) webrtcSignalingServer.stop();
            if (turnServer != null) turnServer.stop();
            if (stunServer != null) stunServer.stop();
            if (httpRedirectSocket != null && !httpRedirectSocket.isClosed()) {
                try { httpRedirectSocket.close(); } catch (Exception ignored) {}
            }
            OracleQuery.shutdownPools();
            PostgreQuery.shutdownPools();
            server.stop();
        } catch (Exception e) { e.printStackTrace(); }
        synchronized (server) { server.notifyAll(); }
    }
}