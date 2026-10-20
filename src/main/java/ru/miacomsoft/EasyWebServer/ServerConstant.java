package ru.miacomsoft.EasyWebServer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class ServerConstant {
    public static ServerConstant config = new ServerConstant("");

    public String DATABASE_NAME = "jdbc:postgresql://your_host:your_port/your_database";
    public String DATABASE_USER_NAME = "postgres";
    public String DATABASE_USER_PASS = "******";

    public int ORACLE_POOL_SIZE = 10;
    public int POSTGRES_MIN_POOL_SIZE = 2;
    public int POSTGRES_MAX_POOL_SIZE = 20;

    // === HTTPS (PEM, как в FileServer): основная схема ===
    // Если HTTPS_ENABLED = true и заданы HTTPS_CERT / HTTPS_KEY —
    // основной DEFAULT_PORT работает как HTTPS. Порт 443 не используется.
    public boolean HTTPS_ENABLED = false;
    public String HTTPS_CERT = "";   // путь к fullchain.pem / .crt
    public String HTTPS_KEY = "";    // путь к privkey.pem / .key (PKCS#8)

    // === WebRTC signaling ===
    public int VIDEOCHAT_PORT = 0;   // 0 = выключено

    public Map<String, DatabaseConfig> DATABASES = new HashMap<>();

    public String LIB_DIR = "D:\\JavaProject\\HttpServer-JAVA\\lib";
    public String APP_NAME = "webpage";

    public boolean DEBUG = false;
    public boolean GZIPPABLE = false;
    public boolean CAHEBLE = true;

    public String LOGIN_PAGE = "";
    public String PAGE_404 = "";
    public String FORWARD_SINGLE_SLASH = "/";
    public String FORWARD_DOUBLE_SLASH = "//";
    public String COMPONENT_PATH = "";
    public String WEBAPP_DIR = "www";
    public List<String> WEBAPP_DIRS = new ArrayList<>();
    public String WEBAPP_SYSTEM_DIR = "www";

    public String DEFAULT_HOST = "0.0.0.0";
    public String DEFAULT_PORT = "9092";
    public int LENGTH_CAHE = 10_485_760;

    public String SERVER_HOM = "/data/data/com.termux/files/home2";
    public String APPLICATION_OCTET_STREAM = "application/octet-stream";
    public String TEXT_PLAIN = "text/plain; charset=utf-8";
    public String TEXT_HTML = "text/html; charset=utf-8";

    public String INDEX_PAGE = "index.html";
    public String CONTENT_TYPE = "Content-Type";
    public String CONTENT_LENGTH = "Content-Length";
    public String CONTENT_ENCODING = "Content-Encoding";
    public String ENCODING_GZIP = "gzip";
    public String ENCODING_UTF8 = "UTF-8";
    public String GIT_URL = "";
    public String GIT_MASTER = "";
    public int GIT_INTERVAL = 120;
    public String LOG_FILE = "";
    public final int MAX_HEADER_SIZE = 4728;

    public final List<String> LIB_CSS = new ArrayList<>();
    public final List<String> LIB_JS = new ArrayList<>();
    public final List<String> LIB_JAR = new ArrayList<>();
    public final Map<String, String> MIME_MAP = new HashMap<>();

    static {
        Map<String, String> defaultMimeTypes = Map.ofEntries(
                Map.entry("appcache", "text/cache-manifest"),
                Map.entry("css", "text/css"),
                Map.entry("asc", "text/plain"),
                Map.entry("gif", "image/gif"),
                Map.entry("htm", "text/html"),
                Map.entry("html", "text/html"),
                Map.entry("java", "text/x-java-source"),
                Map.entry("js", "application/javascript"),
                Map.entry("json", "application/json"),
                Map.entry("jpg", "image/jpeg"),
                Map.entry("jpeg", "image/jpeg"),
                Map.entry("mp3", "audio/mpeg"),
                Map.entry("mp4", "video/mp4"),
                Map.entry("m3u", "audio/mpeg-url"),
                Map.entry("ogv", "video/ogg"),
                Map.entry("flv", "video/x-flv"),
                Map.entry("mov", "video/quicktime"),
                Map.entry("swf", "application/x-shockwave-flash"),
                Map.entry("pdf", "application/pdf"),
                Map.entry("doc", "application/msword"),
                Map.entry("ogg", "application/x-ogg"),
                Map.entry("png", "image/png"),
                Map.entry("svg", "image/svg+xml"),
                Map.entry("xlsm", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                Map.entry("xml", "application/xml"),
                Map.entry("zip", "application/zip"),
                Map.entry("m3u8", "application/vnd.apple.mpegurl"),
                Map.entry("md", "text/plain"),
                Map.entry("txt", "text/plain"),
                Map.entry("php", "text/plain"),
                Map.entry("ts", "video/mp2t")
        );
        config.MIME_MAP.putAll(defaultMimeTypes);
    }

    public ServerConstant(String pathIniConfig) {
        if (pathIniConfig == null || pathIniConfig.isEmpty()) {
            return;
        }
        System.out.println("pathIniConfig: " + pathIniConfig);
        File configFile = new File(pathIniConfig);
        if (configFile.exists()) {
            String configContent = readConfigFile(configFile);
            System.out.println("configContent: " + configContent);
            if (!configContent.isEmpty()) {
                parseConfig(new JSONObject(configContent));
            }
        } else {
            LIB_CSS.clear();
            LIB_JS.clear();
        }
    }

    private String readConfigFile(File configFile) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int commentIndex = line.indexOf('#');
                if (commentIndex != -1) {
                    line = line.substring(0, commentIndex);
                }
                sb.append(line.trim());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return sb.toString();
    }

    private void parseConfig(JSONObject jsonConfig) {
        setIfPresent(jsonConfig, "DATABASE_NAME", val -> DATABASE_NAME = val);
        setIfPresent(jsonConfig, "DATABASE_USER_NAME", val -> DATABASE_USER_NAME = val);
        setIfPresent(jsonConfig, "DATABASE_USER_PASS", val -> DATABASE_USER_PASS = val);

        if (jsonConfig.has("DATABASES")) {
            JSONObject dbs = jsonConfig.getJSONObject("DATABASES");
            dbs.keys().forEachRemaining(key -> {
                String connStr = dbs.getString(key);
                DatabaseConfig dbConfig = DatabaseConfig.parse(connStr);
                if (dbConfig != null) {
                    DATABASES.put(key, dbConfig);
                    System.out.println("Loaded database config: " + key + " -> " + dbConfig.getType());
                }
            });
        }

        setIfPresent(jsonConfig, "APP_NAME", val -> APP_NAME = val);

        setBooleanIfPresent(jsonConfig, "DEBUG", val -> DEBUG = val);
        setBooleanIfPresent(jsonConfig, "GZIPPABLE", val -> GZIPPABLE = val);
        setBooleanIfPresent(jsonConfig, "CAHEBLE", val -> CAHEBLE = val);

        setIfPresent(jsonConfig, "LOGIN_PAGE", val -> LOGIN_PAGE = val);
        setIfPresent(jsonConfig, "PAGE_404", val -> PAGE_404 = val);
        setIfPresent(jsonConfig, "FORWARD_SINGLE_SLASH", val -> FORWARD_SINGLE_SLASH = val);
        setIfPresent(jsonConfig, "FORWARD_DOUBLE_SLASH", val -> FORWARD_DOUBLE_SLASH = val);
        setIfPresent(jsonConfig, "COMPONENT_PATH", val -> COMPONENT_PATH = val);
        setIfPresent(jsonConfig, "WEBAPP_DIR", val -> {
            if (val.contains(";")) {
                String[] dirs = val.split(";");
                WEBAPP_DIRS.clear();
                for (String dir : dirs) {
                    String trimmedDir = dir.trim();
                    if (!trimmedDir.isEmpty()) {
                        if (!trimmedDir.contains("/") && !trimmedDir.contains("\\")) {
                            trimmedDir = SERVER_HOM + File.separator + trimmedDir;
                        }
                        WEBAPP_DIRS.add(trimmedDir);
                    }
                }
                if (!WEBAPP_DIRS.isEmpty()) {
                    WEBAPP_DIR = WEBAPP_DIRS.get(0);
                }
            } else {
                WEBAPP_DIRS.clear();
                String trimmedVal = val.trim();
                if (!trimmedVal.contains("/") && !trimmedVal.contains("\\")) {
                    trimmedVal = SERVER_HOM + File.separator + trimmedVal;
                }
                WEBAPP_DIRS.add(trimmedVal);
                WEBAPP_DIR = trimmedVal;
            }
        });

        setIfPresent(jsonConfig, "WEBAPP_DIR", val -> WEBAPP_DIR = val);
        setIfPresent(jsonConfig, "WEBAPP_SYSTEM_DIR", val -> WEBAPP_SYSTEM_DIR = val);
        setIfPresent(jsonConfig, "DEFAULT_HOST", val -> DEFAULT_HOST = val);
        setIfPresent(jsonConfig, "DEFAULT_PORT", val -> DEFAULT_PORT = val);
        setIfPresent(jsonConfig, "SERVER_HOM", val -> SERVER_HOM = val);
        setIfPresent(jsonConfig, "APPLICATION_OCTET_STREAM", val -> APPLICATION_OCTET_STREAM = val);
        setIfPresent(jsonConfig, "TEXT_PLAIN", val -> TEXT_PLAIN = val);
        setIfPresent(jsonConfig, "TEXT_HTML", val -> TEXT_HTML = val);
        setIfPresent(jsonConfig, "INDEX_PAGE", val -> INDEX_PAGE = val);
        setIfPresent(jsonConfig, "CONTENT_TYPE", val -> CONTENT_TYPE = val);
        setIfPresent(jsonConfig, "CONTENT_LENGTH", val -> CONTENT_LENGTH = val);
        setIfPresent(jsonConfig, "CONTENT_ENCODING", val -> CONTENT_ENCODING = val);
        setIfPresent(jsonConfig, "ENCODING_GZIP", val -> ENCODING_GZIP = val);
        setIfPresent(jsonConfig, "ENCODING_UTF8", val -> ENCODING_UTF8 = val);
        setIfPresent(jsonConfig, "GIT_URL", val -> GIT_URL = val);
        setIfPresent(jsonConfig, "GIT_MASTER", val -> GIT_MASTER = val);
        setIfPresent(jsonConfig, "LOG_FILE", val -> LOG_FILE = val);

        setIntIfPresent(jsonConfig, "LENGTH_CAHE", val -> LENGTH_CAHE = val);
        setIntIfPresent(jsonConfig, "GIT_INTERVAL", val -> GIT_INTERVAL = val);

        setListIfPresent(jsonConfig, "LIB_CSS", LIB_CSS);
        setListIfPresent(jsonConfig, "LIB_JS", LIB_JS);
        setListIfPresent(jsonConfig, "LIB_JAR", LIB_JAR);

        if (jsonConfig.has("MIME_MAP")) {
            JSONObject mimeMap = jsonConfig.getJSONObject("MIME_MAP");
            mimeMap.keys().forEachRemaining(key ->
                    MIME_MAP.put(key, mimeMap.getString(key))
            );
        }

        if (!WEBAPP_DIR.contains("/")) {
            WEBAPP_DIR = SERVER_HOM + '/' + WEBAPP_DIR;
        }

        if (!GIT_URL.isEmpty()) {
            setupGitSync();
        }

        setIntIfPresent(jsonConfig, "ORACLE_POOL_SIZE", val -> ORACLE_POOL_SIZE = val);
        setIntIfPresent(jsonConfig, "POSTGRES_MIN_POOL_SIZE", val -> POSTGRES_MIN_POOL_SIZE = val);
        setIntIfPresent(jsonConfig, "POSTGRES_MAX_POOL_SIZE", val -> POSTGRES_MAX_POOL_SIZE = val);

        // === HTTPS (PEM) ===
        // Основной формат — вложенный блок "https": {...}, как в FileServer
        if (jsonConfig.has("https")) {
            JSONObject httpsBlock = jsonConfig.getJSONObject("https");
            HTTPS_ENABLED = httpsBlock.optBoolean("enabled", false);
            HTTPS_CERT = httpsBlock.optString("cert", "");
            HTTPS_KEY = httpsBlock.optString("key", "");
        }
        // Опционально: плоские ключи в корне (для web.config(...))
        setBooleanIfPresent(jsonConfig, "HTTPS_ENABLED", val -> HTTPS_ENABLED = val);
        setIfPresent(jsonConfig, "HTTPS_CERT", val -> HTTPS_CERT = val);
        setIfPresent(jsonConfig, "HTTPS_KEY", val -> HTTPS_KEY = val);

        // === WebRTC signaling ===
        setIntIfPresent(jsonConfig, "VIDEOCHAT_PORT", val -> VIDEOCHAT_PORT = val);
    }

    private void setIfPresent(JSONObject json, String key, StringConsumer setter) {
        if (json.has(key)) {
            setter.accept(json.getString(key));
        }
    }

    private void setBooleanIfPresent(JSONObject json, String key, BooleanConsumer setter) {
        if (json.has(key)) {
            Object v = json.get(key);
            if (v instanceof Boolean) {
                setter.accept((Boolean) v);
            } else {
                setter.accept(String.valueOf(v).equals("true"));
            }
        }
    }

    private void setIntIfPresent(JSONObject json, String key, IntConsumer setter) {
        if (json.has(key)) {
            setter.accept(json.getInt(key));
        }
    }

    private void setListIfPresent(JSONObject json, String key, List<String> targetList) {
        if (json.has(key)) {
            targetList.clear();
            JSONArray array = json.getJSONArray(key);
            for (int i = 0; i < array.length(); i++) {
                targetList.add(array.getString(i));
            }
        }
    }

    private void setupGitSync() {
        gitSync();
        Thread gitSyncThread = new Thread(() -> {
            while (true) {
                try {
                    TimeUnit.MINUTES.sleep(GIT_INTERVAL);
                    gitSync();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "GitSyncThread");
        gitSyncThread.setDaemon(true);
        gitSyncThread.start();
    }

    private void gitSync() {
        System.out.println("====================================");
        if (new File(WEBAPP_DIR + "/.git").exists()) {
            RunProcess.exec(new File(WEBAPP_DIR), true, "git pull --progress \"origin\" ");
        } else {
            String dirPath = WEBAPP_DIR.substring(0, WEBAPP_DIR.lastIndexOf("/"));
            String dirName = WEBAPP_DIR.substring(WEBAPP_DIR.lastIndexOf("/") + 1);
            RunProcess.exec(new File(dirPath), true, "git clone " + GIT_URL + " " + dirName);
        }
        System.out.println("====================================");
        System.out.println();
    }

    @FunctionalInterface
    private interface StringConsumer { void accept(String value); }

    @FunctionalInterface
    private interface BooleanConsumer { void accept(boolean value); }

    @FunctionalInterface
    private interface IntConsumer { void accept(int value); }

    public static boolean setProp(String propertyName, String propertyValue) {
        try {
            Field field = config.getClass().getDeclaredField(propertyName);
            field.setAccessible(true);
            Class<?> type = field.getType();
            if (type == String.class) {
                field.set(config, propertyValue);
            } else if (type == boolean.class || type == Boolean.class) {
                field.set(config, "true".equals(propertyValue));
            } else if (type == int.class || type == Integer.class) {
                field.set(config, Integer.parseInt(propertyValue));
            } else {
                return false;
            }
            return true;
        } catch (Exception e) {
            System.err.println("Error setting property: " + e.getMessage());
            return false;
        }
    }

    public static boolean setProp(String propertyName, boolean propertyValue) {
        try {
            Field field = config.getClass().getDeclaredField(propertyName);
            field.setAccessible(true);
            if (field.getType() == boolean.class || field.getType() == Boolean.class) {
                field.setBoolean(config, propertyValue);
                return true;
            }
            return false;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            System.err.println("Error setting boolean property: " + e.getMessage());
            return false;
        }
    }

    public void addPathCss(String path) { LIB_CSS.add(path); }
    public void addPathJs(String path) { LIB_JS.add(path); }
    public void addPathJar(String path) { LIB_JAR.add(path); }
    public void addMime(String fileExtension, String mimeType) { MIME_MAP.put(fileExtension, mimeType); }

    public DatabaseConfig getDatabaseConfig(String dbName) {
        if (dbName == null || dbName.isEmpty() || "default".equals(dbName)) {
            return DATABASES.get("default");
        }
        return DATABASES.get(dbName);
    }

    private DatabaseConfig.ConnectionInfo parseJdbcUrl(String url) {
        DatabaseConfig.ConnectionInfo info = new DatabaseConfig.ConnectionInfo();
        if (url == null || url.isEmpty()) return info;
        try {
            if (url.contains("://")) {
                String withoutProtocol = url.substring(url.indexOf("://") + 3);
                String[] hostPortDb = withoutProtocol.split("/", 2);
                if (hostPortDb.length > 0) {
                    String[] hostPort = hostPortDb[0].split(":");
                    info.host = hostPort[0];
                    if (hostPort.length > 1) info.port = hostPort[1];
                }
                if (hostPortDb.length > 1) info.database = hostPortDb[1];
            }
        } catch (Exception e) {
            System.err.println("Error parsing JDBC URL: " + e.getMessage());
        }
        return info;
    }
}