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

/**
 * Обработчик PostgreSQL-датасетов.
 * Создаёт/обновляет функции, возвращающие JSON, и выполняет их.
 */
public class PostgreDatasetHandler {

    public static final PostgreDatasetHandler INSTANCE = new PostgreDatasetHandler();

    // Кэши (локальные для PostgreSQL)
    public final Map<String, HashMap<String, Object>> procedureList = new ConcurrentHashMap<>();
    private final Map<String, Boolean> functionExistsCache = new HashMap<>();
    private final Map<String, Boolean> schemaExistsCache = new HashMap<>();
    private final Map<String, Boolean> databaseExistsCache = new HashMap<>();
    private final Map<String, String> functionContentHashCache = new ConcurrentHashMap<>();
    private final Map<String, Long> databaseCheckTimestamp = new HashMap<>();
    private static final long CACHE_TTL = 60000;

    private boolean debugMode = false;
    private DatabaseConfig currentDbConfig;

    private PostgreDatasetHandler() {}

    // ================================ ОБЩЕСТВЕННЫЕ МЕТОДЫ ================================

    public void handlePostgreDataset(Document doc, Element element, cmpDataset.DatasetConfig config, Base base) {
        System.out.println("=== PostgreSQL dataset mode: " + config.name + " ===");
        if (config.dbConfig == null) {
            System.err.println("ERROR: dbConfig is NULL for dataset: " + config.name);
            base.attr("error", "Database config not found for: " + config.dbName);
            return;
        }

        this.debugMode = (doc != null && doc.hasAttr("debug_mode") && Boolean.parseBoolean(doc.attr("debug_mode")));
        this.currentDbConfig = config.dbConfig;

        ensureDatabaseExists(config.dbConfig);
        ensureSchemaExists(config.schema, config.dbName, config.dbConfig);

        String sqlContent = element.hasText() ? element.text().trim() : "";
        sqlContent = sqlContent.replaceAll(";+$", "");
        String contentHash = getShortHash(sqlContent);
        String functionName = generateFunctionName(config, contentHash);
        String fullFunctionName = config.schema + "." + functionName;

        List<PostgreVar> variables = parseVariables(element);
        HashMap<String, Object> param = null;

        if (needsRecreation(fullFunctionName, contentHash, config)) {
            System.out.println("Creating new function: " + fullFunctionName);
            param = createOrReplaceFunction(fullFunctionName, config.schema, functionName, sqlContent, element, doc, variables, contentHash);
            if (param != null) {
                updateFunctionHashCache(fullFunctionName, contentHash);
            }
        } else {
            System.out.println("Loading existing function: " + fullFunctionName);
            param = loadExistingFunctionToCache(fullFunctionName, config.schema, element);
        }

        if (param != null) {
            procedureList.put(config.name, param);
            procedureList.put(fullFunctionName, param);
            procedureList.put(functionName, param);
            // ========== КЛЮЧЕВОЕ ДОБАВЛЕНИЕ ==========
            procedureList.put(config.schema + "." + config.name, param);
            System.out.println("Dataset saved in cache under names: " + config.name + ", " + fullFunctionName + ", " + functionName + ", " + config.schema + "." + config.name);
        } else {
            System.err.println("Failed to create/load function for dataset: " + config.name);
        }

        setPostgreDatasetAttributes(element, config, variables, functionName);
        finalizeElement(element, config, base);
    }

    public void executePostgresQuery(HttpExchange query, JSONObject result, String fullDatasetName,
                                     JSONObject vars, boolean debugMode) {
        System.out.println("=== executePostgresDataset: " + fullDatasetName + " ===");
        Connection conn = null;
        CallableStatement cs = null;
        ResultSet rs = null;

        try {
            HashMap<String, Object> param = procedureList.get(fullDatasetName);
            if (param == null) {
                result.put("ERROR", "Procedure not found: " + fullDatasetName);
                return;
            }

            Map<String, String> varTypes = (Map<String, String>) param.get("varTypes");
            DatabaseConfig dbConfig = (DatabaseConfig) param.get("dbConfig");
            if (dbConfig == null) {
                dbConfig = (currentDbConfig != null) ? currentDbConfig : ServerConstant.config.getDatabaseConfig("default");
            }
            if (dbConfig == null) {
                result.put("ERROR", "Database configuration not found");
                return;
            }

            String schema = fullDatasetName.contains(".") ? fullDatasetName.substring(0, fullDatasetName.lastIndexOf('.')) : "public";
            String jdbcUrl = buildJdbcUrlWithSchema(dbConfig, schema);
            Properties props = new Properties();
            props.setProperty("user", dbConfig.getUsername());
            props.setProperty("password", dbConfig.getPassword());
            props.setProperty("currentSchema", schema);
            conn = DriverManager.getConnection(jdbcUrl, props);
            conn.setAutoCommit(false);
            setSearchPath(conn, schema);

            String prepareCall = (String) param.get("prepareCall");
            cs = conn.prepareCall(prepareCall);

            List<String> varsArr = (List<String>) param.get("vars");
            int idx = 1;
            for (String vname : varsArr) {
                String value = getValueFromVars(vars, query.session, vname);
                String targetType = (varTypes != null) ? varTypes.getOrDefault(vname, "string") : "string";
                setParameter(cs, idx, value, targetType, conn);
                idx++;
            }

            boolean hasResults = cs.execute();
            JSONArray dataArray = new JSONArray();

            if (hasResults) {
                rs = cs.getResultSet();
                if (rs != null) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int cols = meta.getColumnCount();
                    // Если только одна колонка и её значение похоже на JSON-массив или объект
                    while (rs.next()) {
                        if (cols == 1) {
                            String value = rs.getString(1);
                            if (value != null && (value.trim().startsWith("[") || value.trim().startsWith("{"))) {
                                try {
                                    JSONArray arr = new JSONArray(value);
                                    for (int i = 0; i < arr.length(); i++) {
                                        dataArray.put(arr.get(i));
                                    }
                                } catch (Exception e1) {
                                    // Если не массив, может быть объект
                                    try {
                                        JSONObject obj = new JSONObject(value);
                                        dataArray.put(obj);
                                    } catch (Exception e2) {
                                        // Иначе добавляем как есть
                                        JSONObject row = new JSONObject();
                                        row.put(meta.getColumnLabel(1), value);
                                        dataArray.put(row);
                                    }
                                }
                            } else {
                                JSONObject row = new JSONObject();
                                row.put(meta.getColumnLabel(1), value);
                                dataArray.put(row);
                            }
                        } else {
                            // Несколько колонок – строим обычную строку
                            JSONObject row = new JSONObject();
                            for (int i = 1; i <= cols; i++) {
                                row.put(meta.getColumnLabel(i), rs.getObject(i));
                            }
                            dataArray.put(row);
                        }
                    }
                }
            } else {
                // Функция возвращает JSON через выходной параметр
                try {
                    String jsonStr = cs.getString(1);
                    if (jsonStr != null && !jsonStr.isEmpty()) {
                        if (jsonStr.trim().startsWith("[")) {
                            JSONArray arr = new JSONArray(jsonStr);
                            for (int i = 0; i < arr.length(); i++) {
                                dataArray.put(arr.get(i));
                            }
                        } else if (jsonStr.trim().startsWith("{")) {
                            JSONObject obj = new JSONObject(jsonStr);
                            dataArray.put(obj);
                        } else {
                            dataArray.put(jsonStr);
                        }
                    }
                } catch (SQLException e) {
                    // Нет параметра – игнорируем
                }
            }

            result.put("data", dataArray);
            if (debugMode && param.containsKey("SQL")) {
                result.put("SQL", param.get("SQL"));
            }
        } catch (Exception e) {
            result.put("ERROR", "SQL Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignore) {}
            try { if (cs != null) cs.close(); } catch (Exception ignore) {}
            try { if (conn != null) conn.close(); } catch (Exception ignore) {}
        }
    }

    // ================================ РАБОТА С БАЗОЙ ДАННЫХ ================================

    private void ensureDatabaseExists(DatabaseConfig dbConfig) {
        String dbName = dbConfig.getDatabase();
        if (!checkDatabaseExists(dbName, dbConfig) && !debugMode) {
            System.out.println("=== Creating database: " + dbName + " ===");
            createDatabase(dbName, dbConfig);
            sleepQuietly(2000);
        }
    }

    private void ensureSchemaExists(String schema, String dbName, DatabaseConfig dbConfig) {
        if (!checkSchemaExists(schema, dbName, dbConfig) && !debugMode) {
            System.out.println("=== Creating schema: " + schema + " ===");
            createSchema(schema, dbName, dbConfig);
            sleepQuietly(1000);
        }
    }

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

    private boolean checkSchemaExists(String schema, String dbName, DatabaseConfig dbConfig) {
        String key = dbName + ":schema:" + schema;
        if (schemaExistsCache.containsKey(key)) {
            return schemaExistsCache.get(key);
        }
        try (Connection conn = getConnectionFromConfig(dbConfig);
             PreparedStatement ps = conn.prepareStatement("SELECT EXISTS(SELECT 1 FROM information_schema.schemata WHERE schema_name = ?)")) {
            ps.setString(1, schema);
            boolean exists = ps.executeQuery().next();
            schemaExistsCache.put(key, exists);
            return exists;
        } catch (SQLException e) {
            System.err.println("Error checking schema existence: " + e.getMessage());
            return false;
        }
    }

    private boolean createSchema(String schema, String dbName, DatabaseConfig dbConfig) {
        try (Connection conn = getConnectionFromConfig(dbConfig);
             Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            String sql = String.format("CREATE SCHEMA IF NOT EXISTS \"%s\" AUTHORIZATION \"%s\"",
                    schema.replace("\"", "\"\""), dbConfig.getUsername().replace("\"", "\"\""));
            stmt.executeUpdate(sql);
            conn.commit();
            schemaExistsCache.put(dbName + ":schema:" + schema, true);
            return true;
        } catch (SQLException e) {
            System.err.println("Error creating schema: " + e.getMessage());
            return false;
        }
    }

    private boolean checkFunctionExistsInDB(String fullFunctionName, String schema) {
        String cleanName = fullFunctionName.contains(".") ? fullFunctionName.substring(fullFunctionName.lastIndexOf('.') + 1) : fullFunctionName;
        try (Connection conn = getConnectionFromConfig(currentDbConfig);
             PreparedStatement ps = conn.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON p.pronamespace = n.oid WHERE n.nspname = ? AND p.proname = ?)")) {
            ps.setString(1, schema);
            ps.setString(2, cleanName);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean needsRecreation(String functionName, String currentHash, cmpDataset.DatasetConfig config) {
        if (debugMode) {
            System.out.println("Debug mode: forcing recreation of " + functionName);
            return true;
        }
        String cachedHash = functionContentHashCache.get(functionName);
        if (cachedHash == null) {
            System.out.println("No cached hash for " + functionName);
            return true;
        }
        if (!cachedHash.equals(currentHash)) {
            System.out.println("Hash changed for " + functionName);
            return true;
        }
        if (!checkFunctionExistsInDB(functionName, config.schema)) {
            System.out.println("Function not found in DB: " + functionName);
            return true;
        }
        System.out.println("Function " + functionName + " is up to date");
        return false;
    }

    private void updateFunctionHashCache(String name, String hash) {
        functionContentHashCache.put(name, hash);
        System.out.println("Updated cache for " + name + " -> " + hash);
    }

    // ================================ СОЗДАНИЕ / ЗАГРУЗКА ФУНКЦИЙ ================================

    private HashMap<String, Object> createOrReplaceFunction(String fullFunctionName, String schema, String procName,
                                                            String sqlContent, Element element, Document doc,
                                                            List<PostgreVar> variables, String contentHash) {
        Connection conn = getConnectionFromConfig(currentDbConfig);
        if (conn == null) {
            System.err.println("Cannot connect to database for function creation");
            return null;
        }

        String cleanName = sanitizeName(procName);
        String signature = buildFunctionSignature(schema, cleanName, variables);
        String body = buildFunctionBody(sqlContent, contentHash, element, doc);
        String fullSQL = signature + "\n" + body;

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP FUNCTION IF EXISTS " + schema + "." + cleanName + " CASCADE");
            stmt.execute(fullSQL);
            conn.commit();
            System.out.println("Function created successfully: " + schema + "." + cleanName);
            return saveFunctionToCache(fullFunctionName, schema, cleanName, variables, sqlContent, contentHash, conn);
        } catch (SQLException e) {
            String errorMsg = "SQL error while creating function: " + e.getMessage() + "\nSQL:\n" + fullSQL;
            System.err.println(errorMsg);
            // Записываем ошибку в элемент, чтобы отобразить на странице
            element.empty();
            element.append("<div style='color:red; padding:10px; border:1px solid red; background:#ffeeee;'>");
            element.append("<b>Ошибка создания функции PostgreSQL:</b><br/>");
            element.append("<pre>" + escapeHtml(e.getMessage()) + "</pre>");
            element.append("<pre>" + escapeHtml(fullSQL) + "</pre>");
            element.append("</div>");
            element.removeAttr("style");
            try { conn.rollback(); } catch (SQLException ex) {}
            return null;
        } finally {
            try { conn.close(); } catch (SQLException e) {}
        }
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
    private HashMap<String, Object> saveFunctionToCache(String fullName, String schema, String funcName,
                                                        List<PostgreVar> variables, String sqlContent,
                                                        String contentHash, Connection conn) {
        HashMap<String, Object> param = createBaseParam(variables, null);
        param.put("SQL", sqlContent);
        param.put("dbConfig", currentDbConfig);
        param.put("contentHash", contentHash);
        param.put("connect", conn);

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < variables.size(); i++) {
            if (i > 0) placeholders.append(",");
            placeholders.append("?");
        }
        String prepareCall = "SELECT " + schema + "." + funcName + "(" + placeholders.toString() + ")";
        param.put("prepareCall", prepareCall);
        return param;
    }

    private HashMap<String, Object> loadExistingFunctionToCache(String fullName, String schema, Element element) {
        List<PostgreVar> variables = parseVariables(element);
        HashMap<String, Object> param = createBaseParam(variables, null);
        param.put("SQL", element.hasText() ? element.text().trim() : "");
        param.put("dbConfig", currentDbConfig);

        String funcName = fullName.contains(".") ? fullName.substring(fullName.lastIndexOf('.') + 1) : fullName;
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < variables.size(); i++) {
            if (i > 0) placeholders.append(",");
            placeholders.append("?");
        }
        param.put("prepareCall", "SELECT " + schema + "." + funcName + "(" + placeholders.toString() + ")");
        return param;
    }

    // ================================ ПАРСИНГ ПЕРЕМЕННЫХ ================================

    private List<PostgreVar> parseVariables(Element element) {
        List<PostgreVar> list = new ArrayList<>();
        for (Element child : element.children()) {
            String tag = child.tag().toString().toLowerCase();
            if (tag.contains("var") || tag.contains("cmpdatasetvar")) {
                list.add(parseActionVar(child));
            }
        }
        return list;
    }

    private PostgreVar parseActionVar(Element element) {
        Attributes attrs = element.attributes();
        PostgreVar var = new PostgreVar();
        var.name = attrs.get("name");
        var.src = removeAttr(attrs, "src", var.name);
        var.srctype = removeAttr(attrs, "srctype", "var");
        var.len = removeAttr(attrs, "len", "");
        var.defaultVal = removeAttr(attrs, "default", "");
        var.type = removeAttr(attrs, "type", "string");
        String put = attrs.hasKey("put") ? attrs.get("put") : null;
        String get = attrs.hasKey("get") ? attrs.get("get") : null;
        var.direction = (put != null && get != null) ? "INOUT" : (put != null ? "OUT" : "IN");
        return var;
    }

    private HashMap<String, Object> createBaseParam(List<PostgreVar> variables, cmpDataset.DatasetConfig config) {
        HashMap<String, Object> param = new HashMap<>();
        List<String> names = new ArrayList<>();
        Map<String, String> types = new HashMap<>();
        Map<String, String> dirs = new HashMap<>();
        for (PostgreVar v : variables) {
            names.add(v.name);
            types.put(v.name, v.type);
            dirs.put(v.name, v.direction);
        }
        param.put("vars", names);
        param.put("varTypes", types);
        param.put("varDirections", dirs);
        if (config != null) param.put("dbType", config.dbType);
        return param;
    }

    // ================================ АТРИБУТЫ И ФИНАЛИЗАЦИЯ ================================

    private void setPostgreDatasetAttributes(Element element, cmpDataset.DatasetConfig config,
                                             List<PostgreVar> variables, String functionName) {
        element.attr("style", "display:none");
        element.attr("dataset_name", functionName);
        element.attr("name", config.name);
        element.attr("vars", buildVarsJson(variables));
        element.attr("query_type", "sql");
        element.attr("db", config.dbName);
        element.attr("pg_schema", config.schema);
        element.attr("db_type", config.dbType);
    }

    private void finalizeElement(Element element, cmpDataset.DatasetConfig config, Base base) {
        element.empty();
        base.attr("query_type", config.queryType);
        base.attr("db_type", config.dbType);
        base.attr("pg_schema", config.schema);
        base.attr("db", config.dbName);
        base.attr("name", config.name);
    }

    private String buildVarsJson(List<PostgreVar> variables) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < variables.size(); i++) {
            PostgreVar v = variables.get(i);
            if (i > 0) json.append(",");
            json.append("'").append(v.name).append("':{");
            json.append("'src':'").append(v.src).append("',");
            json.append("'srctype':'").append(v.srctype).append("',");
            json.append("'direction':'").append(v.direction).append("'");
            if (v.defaultVal != null && !v.defaultVal.isEmpty())
                json.append(",'defaultVal':'").append(escapeJson(v.defaultVal)).append("'");
            if (v.len != null && !v.len.isEmpty())
                json.append(",'len':'").append(v.len).append("'");
            json.append("}");
        }
        json.append("}");
        return json.toString();
    }

    // ================================ ГЕНЕРАЦИЯ ИМЁН И ХЭШЕЙ ================================

    private String generateFunctionName(cmpDataset.DatasetConfig config, String contentHash) {
        String relativePath = getRelativePath(config.docPath, config.rootPath);
        String pathHash = getMd5Hash(relativePath);
        if (pathHash.length() > 8) pathHash = pathHash.substring(0, 8);
        if (pathHash.length() > 0 && Character.isDigit(pathHash.charAt(0))) pathHash = "f" + pathHash;

        String baseName = pathHash + "_" + contentHash + "_" + config.name;
        if (baseName.length() > 60) baseName = baseName.substring(0, 60);
        if (baseName.length() > 0 && Character.isDigit(baseName.charAt(0))) baseName = "f_" + baseName;
        return baseName.toLowerCase();
    }

    private String getRelativePath(String docPath, String rootPath) {
        if (docPath.length() <= rootPath.length() || docPath.length() <= 5) return "";
        String relative = docPath.substring(rootPath.length(), docPath.length() - 5);
        return relative.replaceAll("[/\\\\]", "_").replaceAll("[^a-zA-Z0-9_]", "");
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

    // ================================ ВСПОМОГАТЕЛЬНЫЕ УТИЛИТЫ ================================

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

    private void sleepQuietly(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private String sanitizeName(String name) {
        String clean = name.replaceAll("[^a-zA-Z0-9_]", "");
        if (clean.length() > 0 && Character.isDigit(clean.charAt(0))) clean = "f_" + clean;
        return clean;
    }

    private String buildFunctionSignature(String schema, String procName, List<PostgreVar> variables) {
        StringBuilder params = new StringBuilder();
        for (PostgreVar var : variables) {
            String sqlType = mapTypeToSql(var.type, var.len);
            params.append(var.name).append(" ").append(var.direction).append(" ").append(sqlType).append(",");
        }
        String paramsStr = params.length() > 0 ? params.substring(0, params.length() - 1) : "";
        return "CREATE OR REPLACE FUNCTION " + schema + "." + procName + "(" + paramsStr + ")\nRETURNS JSON AS\n$$";
    }

    private String buildFunctionBody(String sqlContent, String contentHash, Element element, Document doc) {
        // Удаляем завершающие точки с запятой из SQL запроса
        String cleanedSql = sqlContent.replaceAll(";+$", "").trim();
        StringBuilder body = new StringBuilder();
        body.append("BEGIN\n");
        body.append("-- fileName: ").append(getFileName(doc)).append("\n");
        body.append("-- contentHash: ").append(contentHash).append("\n");
        body.append("RETURN (\n");
        body.append("SELECT COALESCE(json_agg(row_to_json(tempTab)), '[]'::json) FROM (\n");
        body.append(cleanedSql);
        body.append("\n) tempTab\n");
        body.append(");\nEND;\n$$ LANGUAGE plpgsql;");
        return body.toString();
    }

    private String mapTypeToSql(String type, String len) {
        switch (type.toLowerCase()) {
            case "int":
            case "integer":
                return "INTEGER";
            case "long":
            case "bigint":
                return "BIGINT";
            case "decimal":
            case "numeric":
                return "NUMERIC";
            case "bool":
            case "boolean":
                return "BOOLEAN";
            case "date":
                return "DATE";
            case "timestamp":
                return "TIMESTAMP";
            case "json":
            case "jsonb":
                return "JSONB";
            case "array":
                return "TEXT[]";
            default:
                if (!len.isEmpty() && !len.equals("-1")) return "VARCHAR(" + len + ")";
                return "TEXT";
        }
    }

    private String getFileName(Document doc) {
        if (doc == null) return "unknown";
        String path = doc.attr("doc_path");
        int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private String escapeJson(String s) {
        return s.replace("'", "\\\\'");
    }

    private String removeAttr(Attributes attrs, String key, String defaultValue) {
        if (attrs.hasKey(key)) {
            String val = attrs.get(key);
            attrs.remove(key);
            return val;
        }
        return defaultValue;
    }

    private String getValueFromVars(JSONObject vars, Map<String, Object> session, String name) {
        if (!vars.has(name)) return "";
        Object val = vars.get(name);
        if (val instanceof JSONObject) {
            JSONObject obj = (JSONObject) val;
            if ("session".equals(obj.optString("srctype"))) {
                Object sessionVal = session.get(name);
                return sessionVal != null ? sessionVal.toString() : obj.optString("defaultVal", "");
            }
            return obj.optString("value", obj.optString("defaultVal", ""));
        }
        return val.toString();
    }

    private void setParameter(CallableStatement cs, int idx, String value, String type, Connection conn) throws SQLException {
        if (value == null || value.isEmpty()) {
            cs.setNull(idx, Types.VARCHAR);
            return;
        }
        switch (type.toLowerCase()) {
            case "int":
            case "integer":
                cs.setInt(idx, Integer.parseInt(value));
                break;
            case "long":
            case "bigint":
                cs.setLong(idx, Long.parseLong(value));
                break;
            case "decimal":
            case "numeric":
                cs.setBigDecimal(idx, new java.math.BigDecimal(value));
                break;
            case "bool":
            case "boolean":
                cs.setBoolean(idx, Boolean.parseBoolean(value));
                break;
            case "json":
            case "jsonb":
                cs.setObject(idx, value, Types.OTHER);
                break;
            case "array":
                try {
                    JSONArray arr = new JSONArray(value);
                    String[] strs = new String[arr.length()];
                    for (int i = 0; i < arr.length(); i++) strs[i] = arr.getString(i);
                    cs.setArray(idx, conn.createArrayOf("text", strs));
                } catch (Exception e) {
                    cs.setString(idx, value);
                }
                break;
            default:
                cs.setString(idx, value);
        }
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

    // ================================ ВНУТРЕННИЙ КЛАСС ================================

    private static class PostgreVar {
        String name, src, srctype, len, defaultVal, type, direction;
    }
}