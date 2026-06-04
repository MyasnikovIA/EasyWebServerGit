package ru.miacomsoft.EasyWebServer.component.Dataset;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import ru.miacomsoft.EasyWebServer.DatabaseConfig;
import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.OracleQuery;
import ru.miacomsoft.EasyWebServer.ServerConstant;
import ru.miacomsoft.EasyWebServer.component.Base;
import ru.miacomsoft.EasyWebServer.component.cmpDataset;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class OracleDatasetHandler {

    public static final OracleDatasetHandler INSTANCE = new OracleDatasetHandler();

    // Кэш для Oracle-датасетов (имя -> параметры) - сохраняется при инициализации страницы
    public final Map<String, HashMap<String, Object>> procedureList = new ConcurrentHashMap<>();

    // Кэш для проверки существования функций в БД
    private final Map<String, Boolean> functionExistsCache = new HashMap<>();
    private final Map<String, String> functionContentHashCache = new ConcurrentHashMap<>();

    private DatabaseConfig currentDbConfig;
    private String currentSchema;

    private OracleDatasetHandler() {}

    // ========== ИНИЦИАЛИЗАЦИЯ (ТОЛЬКО СОХРАНЕНИЕ В КЭШ) ==========
    public void handleOracleDataset(Document doc, Element element, cmpDataset.DatasetConfig config, Base base) {
        System.out.println("Oracle dataset mode (cache only): " + config.name);

        if (config.dbConfig == null) {
            System.err.println("Database config not found for: " + config.dbName);
            if (base != null) {
                base.attr("error", "Database configuration not found for: " + config.dbName);
            }
            return;
        }

        this.currentDbConfig = config.dbConfig;
        this.currentSchema = config.schema;

        String sqlContent = element.hasText() ? element.text().trim() : "";

        // Сохраняем конфигурацию в кэш - НЕ СОЗДАЁМ ФУНКЦИЮ В БД
        saveToCache(config.name, config, sqlContent);

        setOracleDatasetAttributes(element, config);
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
        param.put("isOracle", true);
        param.put("contentHash", getShortHash(sqlContent));

        procedureList.put(name, param);
        System.out.println("Oracle dataset cached (not created yet): " + name);
    }

    // ========== ВЫПОЛНЕНИЕ (С ЛЕНИВЫМ СОЗДАНИЕМ ФУНКЦИИ) ==========
    public void executeOracleQuery(HttpExchange query, JSONObject result, String datasetName,
                                   String dbName, JSONObject vars, boolean debugMode) {
        System.out.println("=== executeOracleDataset: " + datasetName + " ===");

        // Получаем конфигурацию из кэша
        HashMap<String, Object> param = procedureList.get(datasetName);
        if (param == null) {
            result.put("ERROR", "Dataset not found: " + datasetName);
            return;
        }

        String sqlContent = (String) param.get("SQL_RAW");
        String contentHash = (String) param.get("contentHash");
        DatabaseConfig dbConfig = (DatabaseConfig) param.get("dbConfig");
        String schema = (String) param.get("schema");

        if (dbConfig == null) {
            result.put("ERROR", "Database configuration not found");
            return;
        }

        // Генерируем имя функции на основе датасета и хэша
        String functionName = generateFunctionName(datasetName, contentHash);
        String fullFunctionName = schema + "." + functionName;

        // Проверяем, существует ли функция в БД (с кэшированием)
        boolean functionExists = checkFunctionExistsInDB(dbConfig, schema, functionName);
        boolean needsCreation = debugMode || !functionExists || needsRecreationByHash(fullFunctionName, contentHash);

        if (needsCreation) {
            // Ленивое создание функции при первом вызове
            System.out.println("Creating Oracle function on first call: " + fullFunctionName);

            if (!createFunction(dbConfig, schema, functionName, sqlContent, contentHash)) {
                result.put("ERROR", "Failed to create function: " + fullFunctionName);
                result.put("SQL", sqlContent);
                return;
            }

            // Обновляем кэш хэша
            updateFunctionHashCache(fullFunctionName, contentHash);
        }

        // Выполняем функцию
        executeFunction(query, result, fullFunctionName, dbConfig, vars, debugMode);
    }

    /**
     * Проверка существования функции в БД Oracle
     */
    private boolean checkFunctionExistsInDB(DatabaseConfig dbConfig, String schema, String functionName) {
        String cacheKey = (schema != null ? schema.toUpperCase() : "DEV") + "." + functionName.toUpperCase();

        if (functionExistsCache.containsKey(cacheKey)) {
            return functionExistsCache.get(cacheKey);
        }

        Connection conn = null;
        try {
            conn = OracleQuery.getConnect(dbConfig);
            if (conn == null) return false;

            String sql = "SELECT COUNT(*) FROM user_objects WHERE object_type = 'FUNCTION' AND object_name = ?";
            if (schema != null && !schema.isEmpty()) {
                sql = "SELECT COUNT(*) FROM all_objects WHERE owner = ? AND object_type = 'FUNCTION' AND object_name = ?";
            }

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (schema != null && !schema.isEmpty()) {
                    ps.setString(1, schema.toUpperCase());
                    ps.setString(2, functionName.toUpperCase());
                } else {
                    ps.setString(1, functionName.toUpperCase());
                }
                ResultSet rs = ps.executeQuery();
                boolean exists = rs.next() && rs.getInt(1) > 0;
                functionExistsCache.put(cacheKey, exists);
                return exists;
            }
        } catch (SQLException e) {
            System.err.println("Error checking Oracle function existence: " + e.getMessage());
            return false;
        } finally {
            OracleQuery.releaseConnection(conn);
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
     * Создание функции в Oracle
     */
    private boolean createFunction(DatabaseConfig dbConfig, String schema, String functionName,
                                   String sqlContent, String contentHash) {
        Connection conn = null;
        try {
            conn = OracleQuery.getConnect(dbConfig);
            if (conn == null) return false;

            // Создаём функцию, возвращающую SYS_REFCURSOR
            String fullSQL = "CREATE OR REPLACE FUNCTION " + schema + "." + functionName +
                    " RETURN SYS_REFCURSOR AS\n" +
                    "  l_cursor SYS_REFCURSOR;\n" +
                    "BEGIN\n" +
                    "  -- contentHash: " + contentHash + "\n" +
                    "  OPEN l_cursor FOR\n" +
                    "  " + sqlContent + ";\n" +
                    "  RETURN l_cursor;\n" +
                    "END " + functionName + ";\n";

            try (Statement stmt = conn.createStatement()) {
                stmt.execute(fullSQL);
                conn.commit();
                System.out.println("Oracle function created: " + schema + "." + functionName);
                return true;
            }
        } catch (SQLException e) {
            System.err.println("Error creating Oracle function: " + e.getMessage());
            try { if (conn != null) conn.rollback(); } catch (SQLException ignored) {}
            return false;
        } finally {
            OracleQuery.releaseConnection(conn);
        }
    }

    /**
     * Выполнение функции Oracle
     */
    private void executeFunction(HttpExchange query, JSONObject result, String fullFunctionName,
                                 DatabaseConfig dbConfig, JSONObject vars, boolean debugMode) {
        Connection conn = null;
        CallableStatement cs = null;
        ResultSet rs = null;

        try {
            conn = OracleQuery.getConnect(dbConfig);
            if (conn == null) {
                result.put("ERROR", "Oracle connection failed");
                return;
            }

            // Вызываем функцию
            String callSql = "{ ? = call " + fullFunctionName + " }";
            cs = conn.prepareCall(callSql);
            cs.registerOutParameter(1, OracleTypes.CURSOR);
            cs.execute();

            // Получаем результат
            rs = (ResultSet) cs.getObject(1);
            JSONArray dataArray = resultSetToJSONArray(rs);
            result.put("data", dataArray);

            if (debugMode) {
                result.put("function", fullFunctionName);
                result.put("call_sql", callSql);
            }

        } catch (SQLException e) {
            result.put("ERROR", "Oracle SQL Error: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            result.put("ERROR", "Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignore) {}
            try { if (cs != null) cs.close(); } catch (Exception ignore) {}
            OracleQuery.releaseConnection(conn);
        }
    }

    /**
     * Преобразование ResultSet в JSONArray
     */
    private JSONArray resultSetToJSONArray(ResultSet rs) throws SQLException {
        JSONArray result = new JSONArray();
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();

        while (rs.next()) {
            JSONObject row = new JSONObject();
            for (int i = 1; i <= columnCount; i++) {
                String columnName = meta.getColumnLabel(i);
                Object value = rs.getObject(i);
                row.put(columnName, value != null ? value : JSONObject.NULL);
            }
            result.put(row);
        }
        return result;
    }

    // ========== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ==========

    private void setOracleDatasetAttributes(Element element, cmpDataset.DatasetConfig config) {
        element.attr("style", "display:none");
        element.attr("dataset_name", config.name);
        element.attr("name", config.name);
        element.attr("query_type", config.queryType);
        element.attr("db_type", config.dbType);
        element.attr("pg_schema", config.schema);
        element.attr("db", config.dbName);
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
        if (baseName.length() > 30) baseName = baseName.substring(0, 30);
        return baseName.toUpperCase();
    }

    private String getShortHash(String input) {
        String hash = getMd5Hash(input);
        return hash.length() > 8 ? hash.substring(0, 8) : hash;
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

    // OracleTypes для SYS_REFCURSOR
    private static class OracleTypes {
        public static final int CURSOR = -10;
    }
}