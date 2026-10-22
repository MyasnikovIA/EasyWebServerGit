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

    public boolean HTTPS_ENABLED = false;
    public String HTTPS_KEYSTORE = "";
    public String HTTPS_KEYSTORE_PASSWORD = "";
    public String HTTPS_KEY_PASSWORD = "";
    public String HTTPS_CERT = "";
    public String HTTPS_KEY = "";

    public int HTTP_REDIRECT_PORT = 0;

    // Порт ConferenceServer (mesh WSS)
    public int CONFERENCE_PORT = 0;

    // Порт WebRTCSignalingServer (pub/sub WSS для webrtc_chat.html)
    public int VIDEOCHAT_PORT = 0;

    // Порт TurnServer
    public int TURN_PORT = 0;

    // Публичный IP или host для TURN (что анонсировать клиентам)
    public String TURN_PUBLIC_IP = "";
    public String TURN_PUBLIC_HOST = "";

    // Диапазон портов ретрансляции TURN (UDP)
    public int TURN_RELAY_PORT_START = 10000;
    public int TURN_RELAY_PORT_END = 10050;

    // Порт STUN (UDP)
    public int STUN_PORT = 0;

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
        if (pathIniConfig == null || pathIniConfig.isEmpty()) return;
        File configFile = new File(pathIniConfig);
        if (configFile.exists()) {
            String configContent = readConfigFile(configFile);
            if (!configContent.isEmpty()) parseConfig(new JSONObject(configContent));
        }
    }

    private String readConfigFile(File configFile) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int c = line.indexOf('#');
                if (c != -1) line = line.substring(0, c);
                sb.append(line.trim());
            }
        } catch (IOException e) { e.printStackTrace(); }
        return sb.toString();
    }

    private void parseConfig(JSONObject jsonConfig) {
        setIfPresent(jsonConfig, "DATABASE_NAME", v -> DATABASE_NAME = v);
        setIfPresent(jsonConfig, "DATABASE_USER_NAME", v -> DATABASE_USER_NAME = v);
        setIfPresent(jsonConfig, "DATABASE_USER_PASS", v -> DATABASE_USER_PASS = v);

        if (jsonConfig.has("DATABASES")) {
            JSONObject dbs = jsonConfig.getJSONObject("DATABASES");
            dbs.keys().forEachRemaining(key -> {
                DatabaseConfig dbConfig = DatabaseConfig.parse(dbs.getString(key));
                if (dbConfig != null) DATABASES.put(key, dbConfig);
            });
        }

        setIfPresent(jsonConfig, "APP_NAME", v -> APP_NAME = v);
        setBooleanIfPresent(jsonConfig, "DEBUG", v -> DEBUG = v);
        setBooleanIfPresent(jsonConfig, "GZIPPABLE", v -> GZIPPABLE = v);
        setBooleanIfPresent(jsonConfig, "CAHEBLE", v -> CAHEBLE = v);

        setIfPresent(jsonConfig, "LOGIN_PAGE", v -> LOGIN_PAGE = v);
        setIfPresent(jsonConfig, "PAGE_404", v -> PAGE_404 = v);
        setIfPresent(jsonConfig, "COMPONENT_PATH", v -> COMPONENT_PATH = v);
        setIfPresent(jsonConfig, "WEBAPP_DIR", v -> {
            if (v.contains(";")) {
                WEBAPP_DIRS.clear();
                for (String dir : v.split(";")) {
                    String t = dir.trim();
                    if (!t.isEmpty()) {
                        if (!t.contains("/") && !t.contains("\\")) t = SERVER_HOM + File.separator + t;
                        WEBAPP_DIRS.add(t);
                    }
                }
                if (!WEBAPP_DIRS.isEmpty()) WEBAPP_DIR = WEBAPP_DIRS.get(0);
            } else {
                WEBAPP_DIRS.clear();
                String t = v.trim();
                if (!t.contains("/") && !t.contains("\\")) t = SERVER_HOM + File.separator + t;
                WEBAPP_DIRS.add(t);
                WEBAPP_DIR = t;
            }
        });

        setIfPresent(jsonConfig, "WEBAPP_SYSTEM_DIR", v -> WEBAPP_SYSTEM_DIR = v);
        setIfPresent(jsonConfig, "DEFAULT_HOST", v -> DEFAULT_HOST = v);
        setIfPresent(jsonConfig, "DEFAULT_PORT", v -> DEFAULT_PORT = v);
        setIfPresent(jsonConfig, "SERVER_HOM", v -> SERVER_HOM = v);
        setIfPresent(jsonConfig, "INDEX_PAGE", v -> INDEX_PAGE = v);
        setIfPresent(jsonConfig, "LOG_FILE", v -> LOG_FILE = v);

        setIntIfPresent(jsonConfig, "LENGTH_CAHE", v -> LENGTH_CAHE = v);
        setIntIfPresent(jsonConfig, "GIT_INTERVAL", v -> GIT_INTERVAL = v);
        setIntIfPresent(jsonConfig, "ORACLE_POOL_SIZE", v -> ORACLE_POOL_SIZE = v);
        setIntIfPresent(jsonConfig, "POSTGRES_MIN_POOL_SIZE", v -> POSTGRES_MIN_POOL_SIZE = v);
        setIntIfPresent(jsonConfig, "POSTGRES_MAX_POOL_SIZE", v -> POSTGRES_MAX_POOL_SIZE = v);

        setListIfPresent(jsonConfig, "LIB_CSS", LIB_CSS);
        setListIfPresent(jsonConfig, "LIB_JS", LIB_JS);
        setListIfPresent(jsonConfig, "LIB_JAR", LIB_JAR);

        if (jsonConfig.has("MIME_MAP")) {
            JSONObject mimeMap = jsonConfig.getJSONObject("MIME_MAP");
            mimeMap.keys().forEachRemaining(k -> MIME_MAP.put(k, mimeMap.getString(k)));
        }

        if (jsonConfig.has("https")) {
            JSONObject h = jsonConfig.getJSONObject("https");
            HTTPS_ENABLED = h.optBoolean("enabled", false);
            HTTPS_KEYSTORE = h.optString("keystore", "");
            HTTPS_KEYSTORE_PASSWORD = h.optString("password", "");
            HTTPS_CERT = h.optString("cert", "");
            HTTPS_KEY = h.optString("key", "");
            HTTPS_KEY_PASSWORD = h.optString("keyPassword", "");
        }

        setBooleanIfPresent(jsonConfig, "HTTPS_ENABLED", v -> HTTPS_ENABLED = v);
        setIfPresent(jsonConfig, "HTTPS_CERT", v -> HTTPS_CERT = v);
        setIfPresent(jsonConfig, "HTTPS_KEY", v -> HTTPS_KEY = v);
        setIfPresent(jsonConfig, "HTTPS_KEYSTORE", v -> HTTPS_KEYSTORE = v);
        setIfPresent(jsonConfig, "HTTPS_KEYSTORE_PASSWORD", v -> HTTPS_KEYSTORE_PASSWORD = v);
        setIfPresent(jsonConfig, "HTTPS_KEY_PASSWORD", v -> HTTPS_KEY_PASSWORD = v);

        setIntIfPresent(jsonConfig, "HTTP_REDIRECT_PORT", v -> HTTP_REDIRECT_PORT = v);
        setIntIfPresent(jsonConfig, "CONFERENCE_PORT", v -> CONFERENCE_PORT = v);
        setIntIfPresent(jsonConfig, "VIDEOCHAT_PORT", v -> VIDEOCHAT_PORT = v);
        setIntIfPresent(jsonConfig, "TURN_PORT", v -> TURN_PORT = v);
        setIntIfPresent(jsonConfig, "STUN_PORT", v -> STUN_PORT = v);
        setIfPresent(jsonConfig, "TURN_PUBLIC_IP", v -> TURN_PUBLIC_IP = v);
        setIfPresent(jsonConfig, "TURN_PUBLIC_HOST", v -> TURN_PUBLIC_HOST = v);
        setIntIfPresent(jsonConfig, "TURN_RELAY_PORT_START", v -> TURN_RELAY_PORT_START = v);
        setIntIfPresent(jsonConfig, "TURN_RELAY_PORT_END", v -> TURN_RELAY_PORT_END = v);
    }

    private void setIfPresent(JSONObject json, String key, StringConsumer setter) {
        if (json.has(key)) setter.accept(json.getString(key));
    }

    private void setBooleanIfPresent(JSONObject json, String key, BooleanConsumer setter) {
        if (json.has(key)) {
            Object v = json.get(key);
            if (v instanceof Boolean) setter.accept((Boolean) v);
            else setter.accept(String.valueOf(v).equals("true"));
        }
    }

    private void setIntIfPresent(JSONObject json, String key, IntConsumer setter) {
        if (json.has(key)) setter.accept(json.getInt(key));
    }

    private void setListIfPresent(JSONObject json, String key, List<String> target) {
        if (json.has(key)) {
            target.clear();
            JSONArray arr = json.getJSONArray(key);
            for (int i = 0; i < arr.length(); i++) target.add(arr.getString(i));
        }
    }

    @FunctionalInterface private interface StringConsumer { void accept(String v); }
    @FunctionalInterface private interface BooleanConsumer { void accept(boolean v); }
    @FunctionalInterface private interface IntConsumer { void accept(int v); }

    public static boolean setProp(String propertyName, String propertyValue) {
        try {
            Field field = config.getClass().getDeclaredField(propertyName);
            field.setAccessible(true);
            Class<?> type = field.getType();
            if (type == String.class) field.set(config, propertyValue);
            else if (type == boolean.class || type == Boolean.class) field.set(config, "true".equals(propertyValue));
            else if (type == int.class || type == Integer.class) field.set(config, Integer.parseInt(propertyValue));
            else return false;
            return true;
        } catch (Exception e) { return false; }
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
        } catch (Exception e) { return false; }
    }

    public void addPathCss(String path) { LIB_CSS.add(path); }
    public void addPathJs(String path) { LIB_JS.add(path); }
    public void addPathJar(String path) { LIB_JAR.add(path); }
    public void addMime(String ext, String mime) { MIME_MAP.put(ext, mime); }

    public DatabaseConfig getDatabaseConfig(String dbName) {
        if (dbName == null || dbName.isEmpty() || "default".equals(dbName)) return DATABASES.get("default");
        return DATABASES.get(dbName);
    }
}