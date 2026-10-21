package ru.miacomsoft.EasyWebServer;

import ru.miacomsoft.EasyWebServer.util.callbackType.CallbackPage;
import ru.miacomsoft.EasyWebServer.util.callbackType.CallbackProcedure;
import ru.miacomsoft.EasyWebServer.util.structObject.JavaInnerClassObject;
import ru.miacomsoft.EasyWebServer.util.structObject.JavaTerminalClassObject;

import javax.net.ssl.SSLServerSocketFactory;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static ru.miacomsoft.EasyWebServer.PacketManager.getPageJar;
import static ru.miacomsoft.EasyWebServer.PacketManager.getWebPage;

public class WebServer implements Runnable {
    public static CallbackProcedure callbackProcedure = null;
    private static final Logger LOGGER = Logger.getLogger(WebServer.class.getName());
    private static boolean isRunServer = false;
    private static WebServer server;
    private static WebRTCSignalingServer webrtcServer;
    private static ServerSocket httpRedirectSocket;
    // STUN-сервер (UDP) для RTCPeerConnection (ICE srflx-кандидаты)
    private static StunServer stunServer;

    public WebServer() {
    }

    public WebServer(Class<?> mainClass) {
        System.out.println("Список классов страниц: " + getWebPage(mainClass));
    }

    public static void start() {
        server = new WebServer();
        Thread thread = new Thread(server);
        thread.start();
        Runtime.getRuntime().addShutdownHook(new ShutDown());
        try {
            thread.join();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void stop() {
        isRunServer = false;
        if (webrtcServer != null) {
            webrtcServer.stop();
        }
        if (stunServer != null) {
            stunServer.stop();
        }
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

    public void onPage(CallbackProcedure callbackProcedure) {
        this.callbackProcedure = callbackProcedure;
    }

    public void onPage(String path, CallbackPage callbackPage) {
        ServerResource.pagesList.put(path, callbackPage);
    }

    public void onPage(String path, File file) {
        ServerResource.pagesListFile.put(path, file);
    }

    public void initConfig(String args) {
        if (args.length() == 0) {
            ServerConstant.config = new ServerConstant("config.ini");
        } else {
            ServerConstant.config = new ServerConstant(args);
        }
    }

    public Boolean config(String confPropName, String confPropValue) {
        if (confPropValue.contains(";")) {
            return handleListProperty(confPropName, confPropValue);
        }
        if (confPropName.startsWith("DATABASES.")) {
            return handleDatabaseProperty(confPropName, confPropValue);
        }
        return ServerConstant.config.setProp(confPropName, confPropValue);
    }

    private Boolean handleDatabaseProperty(String confPropName, String confPropValue) {
        try {
            String[] parts = confPropName.split("\\.", 2);
            if (parts.length != 2) return false;
            String dbName = parts[1];
            DatabaseConfig dbConfig = DatabaseConfig.parse(confPropValue);
            if (dbConfig != null) {
                ServerConstant.config.DATABASES.put(dbName, dbConfig);
                System.out.println("Added database config: " + dbName + " -> " + dbConfig.getType());
                return true;
            }
        } catch (Exception e) {
            System.err.println("Error handling database property '" + confPropName + "': " + e.getMessage());
        }
        return false;
    }

    private Boolean handleListProperty(String confPropName, String confPropValue) {
        try {
            if (!isListProperty(confPropName)) {
                return ServerConstant.config.setProp(confPropName, confPropValue);
            }
            String[] values = confPropValue.split(";");
            List<String> valueList = new ArrayList<>();
            for (String value : values) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) valueList.add(trimmed);
            }
            switch (confPropName) {
                case "WEBAPP_DIR":
                    ServerConstant.config.WEBAPP_DIRS.clear();
                    for (String dir : valueList) {
                        String fullPath = dir;
                        if (!dir.contains("/") && !dir.contains("\\")) {
                            fullPath = ServerConstant.config.SERVER_HOM + File.separator + dir;
                        }
                        ServerConstant.config.WEBAPP_DIRS.add(fullPath);
                    }
                    if (!ServerConstant.config.WEBAPP_DIRS.isEmpty()) {
                        ServerConstant.config.WEBAPP_DIR = ServerConstant.config.WEBAPP_DIRS.get(0);
                    }
                    return true;
                case "LIB_CSS":
                    ServerConstant.config.LIB_CSS.clear();
                    ServerConstant.config.LIB_CSS.addAll(valueList);
                    return true;
                case "LIB_JS":
                    ServerConstant.config.LIB_JS.clear();
                    ServerConstant.config.LIB_JS.addAll(valueList);
                    return true;
                case "LIB_JAR":
                    ServerConstant.config.LIB_JAR.clear();
                    ServerConstant.config.LIB_JAR.addAll(valueList);
                    return true;
                default:
                    return ServerConstant.config.setProp(confPropName, confPropValue);
            }
        } catch (Exception e) {
            System.err.println("Error handling list property '" + confPropName + "': " + e.getMessage());
            return false;
        }
    }

    private boolean isListProperty(String propertyName) {
        return propertyName.endsWith("_LIST") ||
                propertyName.endsWith("_DIRS") ||
                propertyName.equals("WEBAPP_DIR") ||
                propertyName.equals("LIB_CSS") ||
                propertyName.equals("LIB_JS") ||
                propertyName.equals("LIB_JAR") ||
                propertyName.equals("MIME_MAP");
    }

    public Boolean config(String confPropName, Boolean confPropValue) {
        return ServerConstant.config.setProp(confPropName, confPropValue);
    }

    public void addPageJar(String pathJarFile) {
        System.out.println("Список страниц из Jar файла " + getPageJar(pathJarFile));
    }

    @Override
    public void run() {
        int port = Integer.parseInt(ServerConstant.config.DEFAULT_PORT);
        try {
            isRunServer = true;
            viewWebResource(port);

            ServerSocket ss;

            // ============================================================
            // HTTPS на основном DEFAULT_PORT (без 443).
            // Приоритет: keystore.p12 → PEM.
            // ============================================================
            boolean hasKeystore = ServerConstant.config.HTTPS_KEYSTORE != null
                    && !ServerConstant.config.HTTPS_KEYSTORE.isEmpty();
            boolean hasPem = ServerConstant.config.HTTPS_CERT != null
                    && !ServerConstant.config.HTTPS_CERT.isEmpty()
                    && ServerConstant.config.HTTPS_KEY != null
                    && !ServerConstant.config.HTTPS_KEY.isEmpty();

            if (ServerConstant.config.HTTPS_ENABLED && (hasKeystore || hasPem)) {
                System.out.println("[WebServer] Starting in HTTPS mode...");
                if (hasKeystore) {
                    System.out.println("[WebServer] Keystore: " + ServerConstant.config.HTTPS_KEYSTORE);
                } else {
                    System.out.println("[WebServer] Cert: " + ServerConstant.config.HTTPS_CERT);
                    System.out.println("[WebServer] Key:  " + ServerConstant.config.HTTPS_KEY);
                }
                System.out.println("[WebServer] Listen port: " + port);

                try {
                    ss = SSLManager.createSSLServerSocket(
                            port,
                            ServerConstant.config.HTTPS_KEYSTORE,
                            ServerConstant.config.HTTPS_KEYSTORE_PASSWORD,
                            ServerConstant.config.HTTPS_KEY_PASSWORD,
                            ServerConstant.config.HTTPS_CERT,
                            ServerConstant.config.HTTPS_KEY
                    );
                } catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "Не удалось создать SSLServerSocket на порту " + port, e);
                    return;
                }
            } else {
                System.out.println("[WebServer] Starting in HTTP mode...");
                System.out.println("[WebServer] Listen port: " + port);
                ss = new ServerSocket(port);
            }

            // === ЗАПУСК WEBRTC SIGNALING (видеоконференции) ===
            // Если HTTPS включён — используем ту же SSL-фабрику,
            // чтобы signaling работал как WSS на отдельном порту.
            if (ServerConstant.config.VIDEOCHAT_PORT > 0) {
                SSLServerSocketFactory rtcSslFactory = null;
                if (ServerConstant.config.HTTPS_ENABLED && (hasKeystore || hasPem)) {
                    try {
                        rtcSslFactory = SSLManager.createSSLServerSocketFactory(
                                ServerConstant.config.HTTPS_KEYSTORE,
                                ServerConstant.config.HTTPS_KEYSTORE_PASSWORD,
                                ServerConstant.config.HTTPS_KEY_PASSWORD,
                                ServerConstant.config.HTTPS_CERT,
                                ServerConstant.config.HTTPS_KEY);
                        System.out.println("[WebServer] WebRTC signaling будет работать как WSS");
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING,
                                "WebRTC signaling: не удалось создать SSL-фабрику — " +
                                        "будет plain WS (не заработает с https-страницы)", e);
                    }
                }
                webrtcServer = new WebRTCSignalingServer(
                        ServerConstant.config.VIDEOCHAT_PORT, rtcSslFactory);
                webrtcServer.start();
            }

            // === ЗАПУСК STUN (UDP) для RTCPeerConnection ===
            if (ServerConstant.config.STUN_PORT > 0) {
                stunServer = new StunServer(ServerConstant.config.STUN_PORT);
                stunServer.start();
            }

            // === ОПЦИОНАЛЬНЫЙ HTTP→HTTPS REDIRECT (для порта 80 через роутер) ===
            if (ServerConstant.config.HTTP_REDIRECT_PORT > 0) {
                startHttpRedirectServer(ServerConstant.config.HTTP_REDIRECT_PORT);
            }

            while (isRunServer == true) {
                Socket socket;
                try {
                    socket = ss.accept();
                } catch (Exception acceptEx) {
                    if (!isRunServer) break;
                    LOGGER.log(Level.WARNING, "accept() error", acceptEx);
                    continue;
                }
                new Thread(new ServerResourceHandler(socket), "http-" + port).start();
            }
        } catch (Exception ex) {
            Logger.getLogger(WebServer.class.getName()).log(Level.SEVERE, null, ex);
        } catch (Throwable ex) {
            Logger.getLogger(WebServer.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * HTTP→HTTPS redirect: слушает plain HTTP на заданном порту
     * и отвечает 301 на https://<host>:<DEFAULT_PORT>/<path>.
     * Нужен, чтобы http:// через роутер на порт 80 не висел.
     */
    private void startHttpRedirectServer(int httpPort) {
        Thread t = new Thread(() -> {
            try {
                httpRedirectSocket = new ServerSocket(httpPort);
                System.out.println("[WebServer] HTTP→HTTPS redirect server on port " + httpPort);
                while (isRunServer) {
                    try {
                        Socket client = httpRedirectSocket.accept();
                        handleHttpRedirect(client);
                    } catch (Exception ignored) { }
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE,
                        "Не удалось запустить HTTP→HTTPS redirect на порту " + httpPort, e);
            }
        }, "http-redirect");
        t.setDaemon(true);
        t.start();
    }

    private void handleHttpRedirect(Socket client) {
        try (client) {
            client.setSoTimeout(5000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(client.getInputStream(),
                            java.nio.charset.StandardCharsets.ISO_8859_1));

            String requestLine = in.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String path = parts[1];

            String host = null;
            String headerLine;
            while ((headerLine = in.readLine()) != null && !headerLine.isEmpty()) {
                int idx = headerLine.indexOf(':');
                if (idx > 0) {
                    String name = headerLine.substring(0, idx).trim();
                    if ("host".equalsIgnoreCase(name)) {
                        host = headerLine.substring(idx + 1).trim();
                    }
                }
            }
            if (host == null || host.isEmpty()) host = "localhost";

            int colon = host.indexOf(':');
            if (colon > 0) host = host.substring(0, colon);

            int httpsPort = Integer.parseInt(ServerConstant.config.DEFAULT_PORT);
            String redirectUrl = "https://" + host + ":" + httpsPort + path;

            String response = "HTTP/1.1 301 Moved Permanently\r\n" +
                    "Location: " + redirectUrl + "\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";
            client.getOutputStream().write(
                    response.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            client.getOutputStream().flush();
        } catch (Exception ignored) { }
    }

    private void viewWebResource(int port) {
        System.out.print("port: ");
        System.out.println(port);
        System.out.print("http://127.0.0.1:");
        System.out.print(port);
        System.out.println("/");
        System.out.println("-----------------------");
        String portStr = String.valueOf(port);
        for (Map.Entry<String, JavaInnerClassObject> entry : ServerResource.pagesJavaInnerClass.entrySet()) {
            String key = entry.getKey();
            JavaInnerClassObject page = entry.getValue();
            System.out.print("queryType: ");
            System.out.println(page.queryType);
            System.out.print("mime: ");
            System.out.println(page.mime);
            System.out.println(page.classNat);
            System.out.print("method: ");
            System.out.println(page.method);
            System.out.print("http://127.0.0.1:");
            System.out.print(portStr);
            System.out.println("/" + page.url);
            System.out.println("-----------------------");
        }
        if (!ServerResource.pagesJavaTerminalClass.isEmpty()) {
            System.out.println("-----------------------");
            System.out.println("------- Terminal ------");
            System.out.println("-----------------------");
            for (Map.Entry<String, JavaTerminalClassObject> entry : ServerResource.pagesJavaTerminalClass.entrySet()) {
                String key = entry.getKey();
                JavaTerminalClassObject page = entry.getValue();
                System.out.print("queryType: ");
                System.out.println(page.classNat);
                System.out.print("method: ");
                System.out.println(page.method);
                System.out.print("http://127.0.0.1:");
                System.out.print(portStr);
                System.out.println("/" + page.url);
                System.out.println("-----------------------");
            }
        }

    }

    protected synchronized static void WriteToFile(String stringMessage) {
        if (ServerConstant.config.LOG_FILE.length() == 0) {
            System.err.println(stringMessage);
        } else {
            try {
                FileWriter filelog = new FileWriter(new File(ServerConstant.config.LOG_FILE), true);
                filelog.write(stringMessage);
                filelog.flush();
            } catch (Exception error) {
                System.err.println(error);
            }
        }
    }

    public static String getOS() {
        String osName = System.getProperty("os.name").toLowerCase();

        if (osName.contains("win")) {
            return "windows";
        } else if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
            return "linux";
        } else if (osName.contains("mac")) {
            return "mac";
        } else if (osName.contains("sunos") || osName.contains("solaris")) {
            return "solaris";
        } else if (osName.contains("bsd")) {
            return "bsd";
        } else {
            return "unknown";
        }
    }

    public static boolean isWindows() {
        return getOS().equals("windows");
    }

    public static boolean isLinux() {
        return getOS().equals("linux");
    }
    
    public static boolean isUnixLike() {
        String os = getOS();
        return os.equals("linux") || os.equals("mac") ||
                os.equals("solaris") || os.equals("bsd") ||
                os.equals("aix");
    }

    static void shutDown() {
        try {
            LOGGER.info("Shutting down server...");
            if (webrtcServer != null) {
                webrtcServer.stop();
            }
            if (stunServer != null) {
                stunServer.stop();
            }
            if (httpRedirectSocket != null && !httpRedirectSocket.isClosed()) {
                try { httpRedirectSocket.close(); } catch (Exception ignored) {}
            }
            OracleQuery.shutdownPools();
            PostgreQuery.shutdownPools();
            server.stop();
        } catch (Exception e) {
            e.printStackTrace();
        }
        synchronized (server) {
            server.notifyAll();
        }
    }
}