package ru.miacomsoft.EasyWebServer.component.Dataset;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import ru.miacomsoft.EasyWebServer.DatabaseConfig;
import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.ServerConstant;
import ru.miacomsoft.EasyWebServer.component.Base;
import ru.miacomsoft.EasyWebServer.component.cmpDataset;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PostgreDatasetHandler {

    public static final PostgreDatasetHandler INSTANCE = new PostgreDatasetHandler();

    // Кэш для PostgreSQL-датасетов (имя -> параметры) - сохраняется при инициализации страницы
    public final Map<String, HashMap<String, Object>> procedureList = new ConcurrentHashMap<>();

    // Кэш для проверки существования функций в БД
    private final Map<String, Boolean> functionExistsCache = new HashMap<>();
    private final Map<String, Boolean> schemaExistsCache = new HashMap<>();
    private final Map<String, Boolean> databaseExistsCache = new HashMap<>();
    private final Map<String, String> functionContentHashCache = new ConcurrentHashMap<>();
    private final Map<String, Long> databaseCheckTimestamp = new HashMap<>();
    private static final long CACHE_TTL = 60000;

    private boolean debugMode = false;
    private DatabaseConfig currentDbConfig;

    private PostgreDatasetHandler() {}

    // ========== ИНИЦИАЛИЗАЦИЯ (ТОЛЬКО СОХРАНЕНИЕ В КЭШ) ==========
    public void handlePostgreDataset(Document doc, Element element, cmpDataset.DatasetConfig config, Base base) {
        System.out.println("=== PostgreSQL dataset mode (cache only): " + config.name + " ===");

        if (config.dbConfig == null) {
            System.err.println("ERROR: dbConfig is NULL for dataset: " + config.name);
            if (base != null) {
                base.attr("error", "Database config not found for: " + config.dbName);
            }
            return;
        }

        this.debugMode = (doc != null && doc.hasAttr("debug_mode") && Boolean.parseBoolean(doc.attr("debug_mode")));
        this.currentDbConfig = config.dbConfig;

        String sqlContent = element.hasText() ? element.text().trim() : "";
        sqlContent = sqlContent.replaceAll(";+$", "");

        // Сохраняем конфигурацию в кэш - НЕ СОЗДАЁМ ФУНКЦИЮ В БД
        saveToCache(config.name, config, sqlContent);

        setPostgreDatasetAttributes(element, config);
        finalizeElement(element, config, base);
    }

    /**
     * Сохранение конфигурации датасета в кэш без создания функции в БД
     */
    private void saveToCache(String name, cmpDataset.DatasetConfig config, String sqlContent) {
        HashMap<String, Object> param = new HashMap<>();
        param.put("SQL", sqlContent);
        param.put("SQL_RAW", sqlContent);
        param.put("dbConfig", config.dbConfig);
        param.put("schema", config.schema);
        param.put("dbName", config.dbName);
        param.put("dbType", config.dbType);
        param.put("query_type", "sql");
        param.put("isOracle", false);
        param.put("contentHash", getShortHash(sqlContent));

        procedureList.put(name, param);
        procedureList.put(config.schema + "." + name, param);

        System.out.println("PostgreSQL dataset cached (not created yet): " + name);
    }

    // ========== ВЫПОЛНЕНИЕ (С ЛЕНИВЫМ СОЗДАНИЕМ ФУНКЦИИ) ==========
    public void executePostgresQuery(HttpExchange query, JSONObject result, String fullDatasetName,
                                     JSONObject vars, boolean debugMode) {
        System.out.println("=== executePostgresDataset: " + fullDatasetName + " ===");

        // Определяем схему и имя датасета
        String schema = fullDatasetName.contains(".") ?
                fullDatasetName.substring(0, fullDatasetName.lastIndexOf('.')) : "public";
        String datasetName = fullDatasetName.contains(".") ?
                fullDatasetName.substring(fullDatasetName.lastIndexOf('.') + 1) : fullDatasetName;

        // Получаем конфигурацию из кэша
        HashMap<String, Object> param = procedureList.get(fullDatasetName);
        if (param == null) {
            param = procedureList.get(datasetName);
        }
        if (param == null) {
            result.put("ERROR", "Dataset not found: " + fullDatasetName);
            return;
        }

        String sqlContent = (String) param.get("SQL_RAW");
        String contentHash = (String) param.get("contentHash");
        DatabaseConfig dbConfig = (DatabaseConfig) param.get("dbConfig");

        if (dbConfig == null) {
            result.put("ERROR", "Database configuration not found");
            return;
        }

        // Убеждаемся, что БД и схема существуют
        ensureDatabaseExists(dbConfig);
        ensureSchemaExists(schema, dbConfig);

        // Генерируем имя функции на основе датасета и хэша
        String functionName = generateFunctionName(datasetName, contentHash);
        String fullFunctionName = schema + "." + functionName;

        // Проверяем, существует ли функция в БД
        boolean functionExists = checkFunctionExistsInDB(dbConfig, schema, functionName);
        boolean needsCreation = debugMode || !functionExists || needsRecreationByHash(fullFunctionName, contentHash);

        if (needsCreation) {
            // Ленивое создание функции при первом вызове
            System.out.println("Creating PostgreSQL function on first call: " + fullFunctionName);

            if (!createFunction(dbConfig, schema, functionName, sqlContent, contentHash)) {
                result.put("ERROR", "Failed to create function: " + fullFunctionName);
                result.put("SQL", sqlContent);
                return;
            }

            // Обновляем кэш хэша
            updateFunctionHashCache(fullFunctionName, contentHash);
        }

        // Выполняем функцию
        executeFunction(query, result, fullFunctionName, dbConfig, schema, vars, debugMode);
    }

    /**
     * Обеспечение существования БД
     */
    private void ensureDatabaseExists(DatabaseConfig dbConfig) {
        String dbName = dbConfig.getDatabase();
        if (!checkDatabaseExists(dbName, dbConfig) && !debugMode) {
            System.out.println("=== Creating database: " + dbName + " ===");
            createDatabase(dbName, dbConfig);
            sleepQuietly(2000);
        }
    }

    /**
     * Обеспечение существования схемы
     */
    private void ensureSchemaExists(String schema, DatabaseConfig dbConfig) {
        if (!checkSchemaExists(schema, dbConfig) && !debugMode) {
            System.out.println("=== Creating schema: " + schema + " ===");
            createSchema(schema, dbConfig);
            sleepQuietly(1000);
        }
    }

    /**
     * Проверка существования БД (с кэшированием)
     */
    private boolean checkDatabaseExists(String dbName, DatabaseConfig dbConfig) {
        String key = "db:" + dbName;
        if (databaseExistsCache.containsKey(key)) {
            Long ts = databaseCheckTimestamp.get(key);
            if (ts != null && System.currentTimeMillis() - ts < CACHE_TTL) {
                return databaseExistsCache.get(key);
            }
        }

        String adminUrl = "jdbc:postgresql://" + dbConfig.getHost() + ":" + dbConfig.getPort() + "/postgres";
        try (Connection conn = createSimpleConnection(adminUrl, dbConfig);
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
            ps.setString(1, dbName);
            boolean exists = ps.executeQuery().next();
            databaseExistsCache.put(key, exists);
            databaseCheckTimestamp.put(key, System.currentTimeMillis());
            return exists;
        } catch (SQLException e) {
            System.err.println("Error checking database existence: " + e.getMessage());
            return false;
        }
    }

    /**
     * Создание БД
     */
    private boolean createDatabase(String dbName, DatabaseConfig dbConfig) {
        String adminUrl = "jdbc:postgresql://" + dbConfig.getHost() + ":" + dbConfig.getPort() + "/postgres";
        try (Connection conn = createSimpleConnection(adminUrl, dbConfig);
             Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            String sql = String.format("CREATE DATABASE \"%s\" WITH OWNER = \"%s\" ENCODING = 'UTF8'",
                    dbName.replace("\"", "\"\""), dbConfig.getUsername().replace("\"", "\"\""));
            stmt.executeUpdate(sql);
            databaseExistsCache.put("db:" + dbName, true);
            return true;
        } catch (SQLException e) {
            if (e.getMessage().contains("already exists")) {
                databaseExistsCache.put("db:" + dbName, true);
                return true;
            }
            System.err.println("Error creating database: " + e.getMessage());
            return false;
        }
    }

    /**
     * Проверка существования схемы (с кэшированием)
     */
    private boolean checkSchemaExists(String schema, DatabaseConfig dbConfig) {
        String key = "schema:" + schema;
        if (schemaExistsCache.containsKey(key)) {
            return schemaExistsCache.get(key);
        }

        try (Connection conn = getConnectionFromConfig(dbConfig);
             PreparedStatement ps = conn.prepareStatement("SELECT EXISTS(SELECT 1 FROM information_schema.schemata WHERE schema_name = ?)")) {
            ps.setString(1, schema);
            ResultSet rs = ps.executeQuery();
            boolean exists = rs.next() && rs.getBoolean(1);
            schemaExistsCache.put(key, exists);
            return exists;
        } catch (SQLException e) {
            System.err.println("Error checking schema existence: " + e.getMessage());
            return false;
        }
    }

    /**
     * Создание схемы
     */
    private boolean createSchema(String schema, DatabaseConfig dbConfig) {
        try (Connection conn = getConnectionFromConfig(dbConfig);
             Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            String sql = String.format("CREATE SCHEMA IF NOT EXISTS \"%s\" AUTHORIZATION \"%s\"",
                    schema.replace("\"", "\"\""), dbConfig.getUsername().replace("\"", "\"\""));
            stmt.executeUpdate(sql);
            conn.commit();
            schemaExistsCache.put("schema:" + schema, true);
            return true;
        } catch (SQLException e) {
            System.err.println("Error creating schema: " + e.getMessage());
            return false;
        }
    }

    /**
     * Проверка существования функции в БД
     */
    private boolean checkFunctionExistsInDB(DatabaseConfig dbConfig, String schema, String functionName) {
        String cacheKey = schema + "." + functionName;

        if (functionExistsCache.containsKey(cacheKey)) {
            return functionExistsCache.get(cacheKey);
        }

        Connection conn = null;
        try {
            conn = getConnectionFromConfig(dbConfig);
            if (conn == null) return false;

            String sql = "SELECT EXISTS(" +
                    "SELECT 1 FROM pg_proc p " +
                    "JOIN pg_namespace n ON p.pronamespace = n.oid " +
                    "WHERE n.nspname = ? AND p.proname = ?" +
                    ")";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, schema);
                ps.setString(2, functionName);
                ResultSet rs = ps.executeQuery();
                boolean exists = rs.next() && rs.getBoolean(1);
                functionExistsCache.put(cacheKey, exists);
                return exists;
            }
        } catch (SQLException e) {
            System.err.println("Error checking function existence: " + e.getMessage());
            return false;
        } finally {
            closeConnectionQuietly(conn);
        }
    }

    /**
     * Проверка, нужно ли обновить функцию по хэшу
     */
    private boolean needsRecreationByHash(String fullName, String currentHash) {
        if (currentHash == null) return true;
        String cachedHash = functionContentHashCache.get(fullName);
        return cachedHash == null || !cachedHash.equals(currentHash);
    }

    /**
     * Обновление кэша хэша функции
     */
    private void updateFunctionHashCache(String name, String hash) {
        functionContentHashCache.put(name, hash);
    }

    /**
     * Создание функции в PostgreSQL
     */
    private boolean createFunction(DatabaseConfig dbConfig, String schema, String functionName,
                                   String sqlContent, String contentHash) {
        Connection conn = null;
        try {
            conn = getConnectionFromConfig(dbConfig);
            if (conn == null) return false;

            setSearchPath(conn, schema);

            // Очищаем SQL от завершающих точек с запятой
            String cleanedSql = sqlContent.replaceAll(";+$", "").trim();

            String fullSQL = "CREATE OR REPLACE FUNCTION " + schema + "." + functionName +
                    "()\nRETURNS JSON AS $$\n" +
                    "BEGIN\n" +
                    "-- contentHash: " + contentHash + "\n" +
                    "RETURN (\n" +
                    "SELECT COALESCE(json_agg(row_to_json(tempTab)), '[]'::json) FROM (\n" +
                    cleanedSql + "\n" +
                    ") tempTab\n" +
                    ");\n" +
                    "END;\n$$ LANGUAGE plpgsql;";

            try (Statement stmt = conn.createStatement()) {
                stmt.execute(fullSQL);
                conn.commit();
                System.out.println("PostgreSQL function created: " + schema + "." + functionName);
                return true;
            }
        } catch (SQLException e) {
            System.err.println("Error creating PostgreSQL function: " + e.getMessage());
            rollbackQuietly(conn);
            return false;
        } finally {
            closeConnectionQuietly(conn);
        }
    }

    /**
     * Выполнение функции PostgreSQL
     */
    private void executeFunction(HttpExchange query, JSONObject result, String fullFunctionName,
                                 DatabaseConfig dbConfig, String schema, JSONObject vars, boolean debugMode) {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            String jdbcUrl = buildJdbcUrlWithSchema(dbConfig, schema);
            Properties props = new Properties();
            props.setProperty("user", dbConfig.getUsername());
            props.setProperty("password", dbConfig.getPassword());
            props.setProperty("currentSchema", schema);
            conn = DriverManager.getConnection(jdbcUrl, props);
            conn.setAutoCommit(false);

            setSearchPath(conn, schema);

            String functionName = fullFunctionName.contains(".") ?
                    fullFunctionName.substring(fullFunctionName.lastIndexOf('.') + 1) : fullFunctionName;
            String callSql = "SELECT * FROM " + fullFunctionName + "()";
            ps = conn.prepareStatement(callSql);
            rs = ps.executeQuery();

            JSONArray dataArray = new JSONArray();
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();

            if (columnCount == 1) {
                // Предполагаем, что результат уже в JSON формате
                while (rs.next()) {
                    String value = rs.getString(1);
                    if (value != null && !value.isEmpty()) {
                        try {
                            JSONArray arr = new JSONArray(value);
                            for (int i = 0; i < arr.length(); i++) {
                                dataArray.put(arr.get(i));
                            }
                        } catch (Exception e) {
                            try {
                                JSONObject obj = new JSONObject(value);
                                dataArray.put(obj);
                            } catch (Exception e2) {
                                JSONObject row = new JSONObject();
                                row.put("value", value);
                                dataArray.put(row);
                            }
                        }
                    }
                }
            } else {
                // Несколько колонок
                while (rs.next()) {
                    JSONObject row = new JSONObject();
                    for (int i = 1; i <= columnCount; i++) {
                        String columnName = meta.getColumnLabel(i);
                        Object value = rs.getObject(i);
                        row.put(columnName, value != null ? value : JSONObject.NULL);
                    }
                    dataArray.put(row);
                }
            }

            result.put("data", dataArray);
            if (debugMode) {
                result.put("function", fullFunctionName);
                result.put("call_sql", callSql);
            }

        } catch (SQLException e) {
            result.put("ERROR", "SQL Error: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            result.put("ERROR", "Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignore) {}
            try { if (ps != null) ps.close(); } catch (Exception ignore) {}
            closeConnectionQuietly(conn);
        }
    }

    // ========== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ==========

    private void setPostgreDatasetAttributes(Element element, cmpDataset.DatasetConfig config) {
        element.attr("style", "display:none");
        element.attr("dataset_name", config.name);
        element.attr("name", config.name);
        element.attr("query_type", "sql");
        element.attr("db", config.dbName);
        element.attr("pg_schema", config.schema);
        element.attr("db_type", config.dbType);
    }

    private void finalizeElement(Element element, cmpDataset.DatasetConfig config, Base base) {
        element.empty();
        if (base != null) {
            base.attr("query_type", config.queryType);
            base.attr("db_type", config.dbType);
            base.attr("pg_schema", config.schema);
            base.attr("db", config.dbName);
            base.attr("name", config.name);
        }
    }

    private String generateFunctionName(String datasetName, String contentHash) {
        String baseName = "ds_" + datasetName + "_" + contentHash;
        baseName = baseName.replaceAll("[^a-zA-Z0-9_]", "_");
        if (baseName.length() > 60) baseName = baseName.substring(0, 60);
        if (baseName.length() > 0 && Character.isDigit(baseName.charAt(0))) baseName = "f_" + baseName;
        return baseName.toLowerCase();
    }

    private String getShortHash(String input) {
        String hash = getMd5Hash(input);
        return hash.length() > 12 ? hash.substring(0, 12) : hash;
    }

    private String getMd5Hash(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    private Connection getConnectionFromConfig(DatabaseConfig dbConfig) {
        try {
            Class.forName("org.postgresql.Driver");
            Properties props = new Properties();
            props.setProperty("user", dbConfig.getUsername());
            props.setProperty("password", dbConfig.getPassword());
            props.setProperty("connectTimeout", "10");
            Connection conn = DriverManager.getConnection(dbConfig.getJdbcUrl(), props);
            conn.setAutoCommit(false);
            return conn;
        } catch (Exception e) {
            System.err.println("Connection error: " + e.getMessage());
            return null;
        }
    }

    private Connection createSimpleConnection(String url, DatabaseConfig dbConfig) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", dbConfig.getUsername());
        props.setProperty("password", dbConfig.getPassword());
        return DriverManager.getConnection(url, props);
    }

    private void closeConnectionQuietly(Connection conn) {
        try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
    }

    private void rollbackQuietly(Connection conn) {
        if (conn != null) {
            try { conn.rollback(); } catch (SQLException ignored) {}
        }
    }

    private void sleepQuietly(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void setSearchPath(Connection conn, String schema) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET search_path TO " + schema + ", public");
        }
    }

    private String buildJdbcUrlWithSchema(DatabaseConfig dbConfig, String schema) {
        String url = dbConfig.getJdbcUrl();
        if (!url.contains("currentSchema")) {
            url += (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        }
        return url;
    }
}