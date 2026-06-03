package ru.miacomsoft.EasyWebServer.component.Action;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import ru.miacomsoft.EasyWebServer.DatabaseConfig;
import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.ServerConstant;
import ru.miacomsoft.EasyWebServer.component.Base;
import ru.miacomsoft.EasyWebServer.component.cmpAction;

import java.math.BigDecimal;
import java.sql.*;
import java.sql.Date;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PostgreActionHandler {

    public static final PostgreActionHandler INSTANCE = new PostgreActionHandler();

    // Кэши PostgreSQL
    public final Map<String, HashMap<String, Object>> procedureList = new ConcurrentHashMap<>();
    private final Map<String, Boolean> functionExistsCache = new HashMap<>();
    private final Map<String, Boolean> schemaExistsCache = new HashMap<>();
    private final Map<String, Boolean> databaseExistsCache = new HashMap<>();
    private final Map<String, String> functionContentHashCache = new ConcurrentHashMap<>();
    private final Map<String, Long> databaseCheckTimestamp = new HashMap<>();
    private static final long CACHE_TTL = 60000;

    private boolean debugMode = false;
    private String currentContentHash;
    private DatabaseConfig currentDbConfig;

    private PostgreActionHandler() {}

    // ========== ИНИЦИАЛИЗАЦИЯ ==========
    public void handlePostgreAction(Document doc, Element element, cmpAction.ActionConfig config, Base base) {
        System.out.println("=== handlePostgreAction called for: " + config.name);
        if (config.dbConfig == null) {
            System.err.println("Database config not found for: " + config.dbName);
            element.empty();
            element.append("Database configuration not found for: " + config.dbName);
            element.removeAttr("style");
            return;
        }
        this.debugMode = (doc != null && doc.hasAttr("debug_mode") && Boolean.parseBoolean(doc.attr("debug_mode")));
        this.currentDbConfig = config.dbConfig;

        initializeDatabase(config);
        initializeSchema(config);

        String sqlContent = element.hasText() ? element.text().trim() : "";
        String contentHash = getShortHash(sqlContent);
        this.currentContentHash = contentHash;

        String functionName = generateFunctionName(config, contentHash);
        String fullFunctionName = config.schema + "." + functionName;

        List<PostgreVar> variables = parseVariables(element);
        setComponentAttributes(config, functionName, variables);

        HashMap<String, Object> param = null;
        if (needsRecreation(fullFunctionName, contentHash, config)) {
            param = createPostgresProcedure(fullFunctionName, config, functionName, sqlContent, element, doc, variables, contentHash);
            updateFunctionHashCache(fullFunctionName, contentHash);
        } else {
            param = loadExistingProcedureToCache(fullFunctionName, config.schema, element);
        }

        // Сохраняем под исходным именем действия (config.name) для поиска
        if (param != null) {
            procedureList.put(config.name, param);
            System.out.println("Saved action under original name: " + config.name);
        }

        finalizeElement(element, config, base);
    }

    // ========== ВЫПОЛНЕНИЕ ==========
    public void executePostgresAction(HttpExchange query, JSONObject result, String actionName,
                                      JSONObject vars, Map<String, Object> session, boolean debugMode) {
        System.out.println("=== executePostgresAction: " + actionName + " ===");
        if (!procedureList.containsKey(actionName)) {
            result.put("ERROR", "Procedure not found: " + actionName);
            return;
        }

        HashMap<String, Object> param = procedureList.get(actionName);
        Map<String, String> varTypes = (Map<String, String>) param.get("varTypes");
        Map<String, String> varDirections = (Map<String, String>) param.get("varDirections");
        DatabaseConfig dbConfig = (DatabaseConfig) param.get("dbConfig");
        if (dbConfig == null) dbConfig = ServerConstant.config.getDatabaseConfig("default");
        if (dbConfig == null) {
            result.put("ERROR", "Database configuration not found");
            return;
        }

        String schema = extractSchema(actionName);
        String jdbcUrl = buildJdbcUrlWithSchema(dbConfig, schema);
        Properties props = new Properties();
        props.setProperty("user", dbConfig.getUsername());
        props.setProperty("password", dbConfig.getPassword());
        props.setProperty("currentSchema", schema);

        List<String> varsArr = (List<String>) param.get("vars");
        String prepareCall = (String) param.get("prepareCall");

        try (Connection conn = DriverManager.getConnection(jdbcUrl, props);
             CallableStatement cs = conn.prepareCall(prepareCall)) {
            conn.setAutoCommit(false);
            setSearchPath(conn, schema);

            int idx = 1;
            for (String vname : varsArr) {
                String dir = varDirections != null ? varDirections.getOrDefault(vname, "IN") : "IN";
                if ("OUT".equals(dir) || "INOUT".equals(dir))
                    registerPostgresOutParameter(cs, idx, varTypes.getOrDefault(vname, "string"));
                idx++;
            }
            idx = 1;
            for (String vname : varsArr) {
                String dir = varDirections != null ? varDirections.getOrDefault(vname, "IN") : "IN";
                if ("IN".equals(dir) || "INOUT".equals(dir)) {
                    String value = getValueFromVars(vars, session, vname);
                    setPostgresParameter(cs, idx, value, varTypes.getOrDefault(vname, "string"), conn);
                }
                idx++;
            }
            cs.execute();
            idx = 1;
            for (String vname : varsArr) {
                String dir = varDirections != null ? varDirections.getOrDefault(vname, "IN") : "IN";
                if ("OUT".equals(dir) || "INOUT".equals(dir)) {
                    String outValue = getPostgresOutParameter(cs, idx, varTypes.getOrDefault(vname, "string"));
                    updateVars(vars, session, vname, outValue);
                }
                idx++;
            }
            conn.commit();
            result.put("vars", vars);
            if (debugMode && param.containsKey("SQL"))
                result.put("SQL", ((String) param.get("SQL")).split("\n"));
        } catch (SQLException e) {
            result.put("ERROR", "SQL Error: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            result.put("ERROR", "Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ========== МЕТОДЫ РАБОТЫ С POSTGRESQL ==========
    public DatabaseConfig createDefaultPostgresConfig() {
        String url = ServerConstant.config.DATABASE_NAME;
        String user = ServerConstant.config.DATABASE_USER_NAME;
        String pass = ServerConstant.config.DATABASE_USER_PASS;
        if (url == null || url.isEmpty() || user == null || user.isEmpty()) return null;
        try {
            DatabaseConfig cfg = new DatabaseConfig();
            cfg.setType("jdbc");
            cfg.setDriver("org.postgresql.Driver");
            String withoutProtocol = url.substring(url.indexOf("://") + 3);
            String[] parts = withoutProtocol.split("/", 2);
            String hostPort = parts[0];
            String database = parts.length > 1 ? parts[1] : "postgres";
            String[] hp = hostPort.split(":");
            cfg.setHost(hp[0]);
            cfg.setPort(hp.length > 1 ? hp[1] : "5432");
            cfg.setDatabase(database);
            cfg.setUsername(user);
            cfg.setPassword(pass);
            cfg.setSchema("public");
            return cfg;
        } catch (Exception e) { return null; }
    }

    private void initializeDatabase(cmpAction.ActionConfig config) {
        String targetDbName = config.dbConfig.getDatabase();
        boolean dbExists = checkDatabaseExistsCached(targetDbName, config.dbConfig);
        if (!dbExists && !debugMode) {
            System.out.println("=== Creating database: " + targetDbName + " ===");
            if (createDatabaseIfNotExists(targetDbName, config.dbConfig)) sleepQuietly(2000);
        }
    }

    private void initializeSchema(cmpAction.ActionConfig config) {
        boolean schemaExists = checkSchemaExistsCached(config.schema, config.dbName, config.dbConfig);
        if (!schemaExists && !debugMode) {
            System.out.println("=== Creating schema: " + config.schema + " ===");
            if (createSchemaIfNotExists(config.schema, config.dbName, config.dbConfig)) sleepQuietly(1000);
        }
    }

    private HashMap<String, Object> createPostgresProcedure(String fullFunctionName, cmpAction.ActionConfig config, String procName,
                                                            String sqlContent, Element element, Document doc,
                                                            List<PostgreVar> variables, String contentHash) {
        Connection conn = connectToPostgres(config.dbConfig);
        if (conn == null) return null;
        String cleanName = sanitizeProcedureName(procName);
        String signature = buildProcedureSignature(config.schema, cleanName, variables);
        String body = buildProcedureBody(sqlContent, contentHash, element, doc);
        String fullSQL = signature + "\n" + body;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP PROCEDURE IF EXISTS " + config.schema + "." + cleanName + " CASCADE");
            stmt.execute(fullSQL);
            return saveProcedureToCache(fullFunctionName, config.schema, cleanName, variables, sqlContent, contentHash, conn, config.name);
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        } finally {
            closeConnectionQuietly(conn);
        }
    }

    private HashMap<String, Object> saveProcedureToCache(String fullName, String schema, String procName,
                                                         List<PostgreVar> variables, String sqlContent,
                                                         String contentHash, Connection conn, String actionName) {
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
        param.put("prepareCall", "CALL " + schema + "." + procName + "(" + placeholders.toString() + ")");
        procedureList.put(fullName, param);
        procedureList.put(procName, param);
        procedureList.put(actionName, param); // сохраняем под исходным именем действия
        return param;
    }

    private HashMap<String, Object> loadExistingProcedureToCache(String fullName, String schema, Element element) {
        List<PostgreVar> variables = parseVariables(element);
        HashMap<String, Object> param = createBaseParam(variables, null);
        param.put("SQL", element.hasText() ? element.text().trim() : "");
        param.put("dbConfig", currentDbConfig);
        String procName = fullName.contains(".") ? fullName.substring(fullName.lastIndexOf('.') + 1) : fullName;
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < variables.size(); i++) {
            if (i > 0) placeholders.append(",");
            placeholders.append("?");
        }
        param.put("prepareCall", "CALL " + schema + "." + procName + "(" + placeholders.toString() + ")");
        procedureList.put(fullName, param);
        procedureList.put(procName, param);
        // здесь actionName не передаётся, но позже в handlePostgreAction добавим под именем config.name
        return param;
    }

    // ========== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ (парсинг, кэширование, утилиты) ==========
    private List<PostgreVar> parseVariables(Element element) {
        List<PostgreVar> vars = new ArrayList<>();
        for (Element child : element.children()) {
            String tag = child.tag().toString().toLowerCase();
            if (tag.contains("var") || tag.contains("cmpactionvar"))
                vars.add(parseActionVar(child));
        }
        return vars;
    }

    private PostgreVar parseActionVar(Element element) {
        Attributes attrs = element.attributes();
        PostgreVar var = new PostgreVar();
        var.name = attrs.get("name");
        var.src = baseRemoveArrKeyRtrn(attrs, "src", var.name);
        var.srctype = baseRemoveArrKeyRtrn(attrs, "srctype", "var");
        var.len = baseRemoveArrKeyRtrn(attrs, "len", "");
        var.defaultVal = baseRemoveArrKeyRtrn(attrs, "default", "");
        var.type = baseRemoveArrKeyRtrn(attrs, "type", "string");
        String put = attrs.hasKey("put") ? attrs.get("put") : null;
        String get = attrs.hasKey("get") ? attrs.get("get") : null;
        var.direction = (put != null && get != null) ? "INOUT" : (put != null ? "OUT" : "IN");
        return var;
    }

    private HashMap<String, Object> createBaseParam(List<PostgreVar> variables, cmpAction.ActionConfig config) {
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

    private void setComponentAttributes(cmpAction.ActionConfig config, String functionName, List<PostgreVar> variables) {
        // Не используется, атрибуты устанавливаются через element в handlePostgreAction
    }

    private void finalizeElement(Element element, cmpAction.ActionConfig config, Base base) {
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

    private String generateFunctionName(cmpAction.ActionConfig config, String contentHash) {
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
        return docPath.substring(rootPath.length(), docPath.length() - 5).replaceAll("[/\\\\]", "_").replaceAll("[^a-zA-Z0-9_]", "");
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
        } catch (Exception e) { return String.valueOf(input.hashCode()); }
    }

    private boolean needsRecreation(String functionName, String currentHash, cmpAction.ActionConfig config) {
        if (debugMode) return true;
        String cachedHash = functionContentHashCache.get(functionName);
        if (cachedHash == null) return true;
        if (!cachedHash.equals(currentHash)) return true;
        if (!checkFunctionExistsInDB(functionName, config.schema, config.dbConfig)) return true;
        return false;
    }

    private void updateFunctionHashCache(String name, String hash) {
        functionContentHashCache.put(name, hash);
    }

    private boolean checkDatabaseExistsCached(String dbName, DatabaseConfig dbConfig) {
        String key = "db:" + dbName;
        if (databaseExistsCache.containsKey(key)) {
            Long ts = databaseCheckTimestamp.get(key);
            if (ts != null && System.currentTimeMillis() - ts < CACHE_TTL)
                return databaseExistsCache.get(key);
        }
        boolean exists = checkDatabaseExistsDirect(dbName, dbConfig);
        databaseExistsCache.put(key, exists);
        databaseCheckTimestamp.put(key, System.currentTimeMillis());
        return exists;
    }

    private boolean checkDatabaseExistsDirect(String dbName, DatabaseConfig dbConfig) {
        String adminUrl = "jdbc:postgresql://" + dbConfig.getHost() + ":" + dbConfig.getPort() + "/postgres";
        try (Connection conn = createSimpleConnection(adminUrl, dbConfig);
             PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
            ps.setString(1, dbName);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) { return false; }
    }

    private boolean createDatabaseIfNotExists(String dbName, DatabaseConfig dbConfig) {
        String adminUrl = "jdbc:postgresql://" + dbConfig.getHost() + ":" + dbConfig.getPort() + "/postgres";
        try (Connection conn = createSimpleConnection(adminUrl, dbConfig)) {
            conn.setAutoCommit(true);
            String sql = String.format("CREATE DATABASE \"%s\" WITH OWNER = \"%s\" ENCODING = 'UTF8'",
                    dbName.replace("\"", "\"\""), dbConfig.getUsername().replace("\"", "\"\""));
            try (Statement stmt = conn.createStatement()) { stmt.executeUpdate(sql); return true; }
        } catch (SQLException e) { return e.getMessage().contains("already exists"); }
    }

    private boolean checkSchemaExistsCached(String schemaName, String dbName, DatabaseConfig dbConfig) {
        String key = dbName + ":schema:" + schemaName;
        if (schemaExistsCache.containsKey(key)) return schemaExistsCache.get(key);
        boolean exists = checkSchemaExistsDirect(schemaName, dbConfig);
        schemaExistsCache.put(key, exists);
        return exists;
    }

    private boolean checkSchemaExistsDirect(String schemaName, DatabaseConfig dbConfig) {
        try (Connection conn = createSimpleConnection(dbConfig.getJdbcUrl(), dbConfig);
             PreparedStatement ps = conn.prepareStatement("SELECT EXISTS(SELECT 1 FROM information_schema.schemata WHERE schema_name = ?)")) {
            ps.setString(1, schemaName);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getBoolean(1); }
        } catch (SQLException e) { return false; }
    }

    private boolean createSchemaIfNotExists(String schemaName, String dbName, DatabaseConfig dbConfig) {
        try (Connection conn = createSimpleConnection(dbConfig.getJdbcUrl(), dbConfig)) {
            conn.setAutoCommit(false);
            String sql = String.format("CREATE SCHEMA IF NOT EXISTS \"%s\" AUTHORIZATION \"%s\"",
                    schemaName.replace("\"", "\"\""), dbConfig.getUsername().replace("\"", "\"\""));
            try (Statement stmt = conn.createStatement()) { stmt.executeUpdate(sql); conn.commit(); return true; }
        } catch (SQLException e) { return false; }
    }

    private boolean checkFunctionExistsInDB(String functionName, String schema, DatabaseConfig dbConfig) {
        String cleanName = functionName.contains(".") ? functionName.substring(functionName.lastIndexOf('.')+1) : functionName;
        try (Connection conn = createSimpleConnection(dbConfig.getJdbcUrl(), dbConfig);
             PreparedStatement ps = conn.prepareStatement("SELECT EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON p.pronamespace = n.oid WHERE n.nspname = ? AND p.proname = ?)")) {
            ps.setString(1, schema);
            ps.setString(2, cleanName);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && rs.getBoolean(1); }
        } catch (SQLException e) { return false; }
    }

    private Connection createSimpleConnection(String url, DatabaseConfig dbConfig) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", dbConfig.getUsername());
        props.setProperty("password", dbConfig.getPassword());
        props.setProperty("connectTimeout", "10");
        return DriverManager.getConnection(url, props);
    }

    private Connection connectToPostgres(DatabaseConfig dbConfig) {
        try {
            Class.forName("org.postgresql.Driver");
            Properties props = new Properties();
            props.setProperty("user", dbConfig.getUsername());
            props.setProperty("password", dbConfig.getPassword());
            return DriverManager.getConnection(dbConfig.getJdbcUrl(), props);
        } catch (Exception e) { return null; }
    }

    private void closeConnectionQuietly(Connection conn) {
        try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
    }

    private void sleepQuietly(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private String sanitizeProcedureName(String name) {
        String clean = name.replaceAll("[^a-zA-Z0-9_]", "");
        if (clean.length() > 0 && Character.isDigit(clean.charAt(0))) clean = "f_" + clean;
        return clean;
    }

    private String buildProcedureSignature(String schema, String procName, List<PostgreVar> variables) {
        StringBuilder params = new StringBuilder();
        for (PostgreVar var : variables) {
            String sqlType = mapJavaTypeToSqlType(var.type, var.len);
            params.append(var.name).append(" ").append(var.direction).append(" ").append(sqlType).append(",");
        }
        String paramsStr = params.length() > 0 ? params.substring(0, params.length()-1) : "";
        return "CREATE OR REPLACE PROCEDURE " + schema + "." + procName + "(" + paramsStr + ")\nLANGUAGE plpgsql\nAS $$";
    }

    private String buildProcedureBody(String sqlContent, String contentHash, Element element, Document doc) {
        StringBuilder body = new StringBuilder();
        body.append("BEGIN\n");
        body.append("-- fileName: ").append(getFileName(doc)).append("\n");
        body.append("-- contentHash: ").append(contentHash).append("\n");
        body.append(sqlContent);
        if (!sqlContent.endsWith(";") && !sqlContent.endsWith("\n")) body.append(";\n");
        body.append("\nEND;\n$$");
        return body.toString();
    }

    private String mapJavaTypeToSqlType(String type, String len) {
        switch (type.toLowerCase()) {
            case "int": case "integer": return "INTEGER";
            case "long": case "bigint": return "BIGINT";
            case "decimal": case "numeric": return "NUMERIC";
            case "bool": case "boolean": return "BOOLEAN";
            case "date": return "DATE";
            case "timestamp": return "TIMESTAMP";
            case "json": case "jsonb": return "JSONB";
            case "array": return "TEXT[]";
            default:
                if (!len.isEmpty() && !len.equals("-1")) return "VARCHAR(" + len + ")";
                return "TEXT";
        }
    }

    private String getFileName(Document doc) {
        if (doc == null) return "unknown";
        String path = doc.attr("doc_path");
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private String escapeJson(String s) { return s.replace("'", "\\\\'"); }
    private String baseRemoveArrKeyRtrn(Attributes arr, String key, String defaultValue) {
        if (arr.hasKey(key)) { String val = arr.get(key); arr.remove(key); return val; }
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

    private void updateVars(JSONObject vars, Map<String, Object> session, String name, String value) {
        if (vars.has(name) && vars.get(name) instanceof JSONObject) {
            JSONObject obj = vars.getJSONObject(name);
            if ("session".equals(obj.optString("srctype")))
                session.put(name, value);
            else
                obj.put("value", value);
        } else {
            JSONObject wrapper = new JSONObject();
            wrapper.put("value", value);
            wrapper.put("src", name);
            wrapper.put("srctype", "var");
            vars.put(name, wrapper);
        }
    }

    private String extractSchema(String fullActionName) {
        if (fullActionName.contains(".")) return fullActionName.substring(0, fullActionName.lastIndexOf('.'));
        return "public";
    }

    private String buildJdbcUrlWithSchema(DatabaseConfig dbConfig, String schema) {
        String jdbcUrl = dbConfig.getJdbcUrl();
        if (!jdbcUrl.contains("currentSchema")) {
            jdbcUrl += (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        }
        return jdbcUrl;
    }

    private void setSearchPath(Connection conn, String schema) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET search_path TO " + schema + ", public");
        }
    }

    private void setPostgresParameter(CallableStatement cs, int idx, String value, String type, Connection conn) throws SQLException {
        if (value == null || value.isEmpty()) { cs.setNull(idx, Types.VARCHAR); return; }
        switch (type.toLowerCase()) {
            case "int": case "integer": cs.setInt(idx, Integer.parseInt(value)); break;
            case "long": case "bigint": cs.setLong(idx, Long.parseLong(value)); break;
            case "decimal": case "numeric": cs.setBigDecimal(idx, new BigDecimal(value)); break;
            case "bool": case "boolean": cs.setBoolean(idx, Boolean.parseBoolean(value)); break;
            case "date": cs.setDate(idx, Date.valueOf(value)); break;
            case "timestamp": cs.setTimestamp(idx, Timestamp.valueOf(value.replace("T", " "))); break;
            case "json": case "jsonb": cs.setObject(idx, value, Types.OTHER); break;
            case "array":
                try {
                    JSONArray arr = new JSONArray(value);
                    String[] strs = new String[arr.length()];
                    for (int i = 0; i < arr.length(); i++) strs[i] = arr.getString(i);
                    cs.setArray(idx, conn.createArrayOf("text", strs));
                } catch (Exception e) { cs.setString(idx, value); }
                break;
            default: cs.setString(idx, value);
        }
    }

    private void registerPostgresOutParameter(CallableStatement cs, int idx, String type) throws SQLException {
        switch (type.toLowerCase()) {
            case "int": case "integer": cs.registerOutParameter(idx, Types.INTEGER); break;
            case "long": case "bigint": cs.registerOutParameter(idx, Types.BIGINT); break;
            case "decimal": case "numeric": cs.registerOutParameter(idx, Types.NUMERIC); break;
            case "bool": case "boolean": cs.registerOutParameter(idx, Types.BOOLEAN); break;
            case "date": cs.registerOutParameter(idx, Types.DATE); break;
            case "timestamp": cs.registerOutParameter(idx, Types.TIMESTAMP); break;
            case "json": case "jsonb": cs.registerOutParameter(idx, Types.OTHER); break;
            case "array": cs.registerOutParameter(idx, Types.ARRAY); break;
            default: cs.registerOutParameter(idx, Types.VARCHAR);
        }
    }

    private String getPostgresOutParameter(CallableStatement cs, int idx, String type) throws SQLException {
        switch (type.toLowerCase()) {
            case "int": case "integer": return cs.wasNull() ? "" : String.valueOf(cs.getInt(idx));
            case "long": case "bigint": return cs.wasNull() ? "" : String.valueOf(cs.getLong(idx));
            case "decimal": case "numeric":
                BigDecimal bd = cs.getBigDecimal(idx);
                return bd == null ? "" : bd.toString();
            case "bool": case "boolean": return cs.wasNull() ? "" : String.valueOf(cs.getBoolean(idx));
            case "date": Date d = cs.getDate(idx); return d == null ? "" : d.toString();
            case "timestamp": Timestamp ts = cs.getTimestamp(idx); return ts == null ? "" : ts.toString();
            case "json": case "jsonb":
                Object o = cs.getObject(idx);
                return o == null ? "" : o.toString();
            case "array":
                Array a = cs.getArray(idx);
                if (a == null) return "";
                Object[] arr = (Object[]) a.getArray();
                JSONArray ja = new JSONArray();
                for (Object item : arr) ja.put(item != null ? item.toString() : null);
                return ja.toString();
            default:
                String s = cs.getString(idx);
                return s == null ? "" : s;
        }
    }

    private static class PostgreVar {
        String name, src, srctype, len, defaultVal, type, direction;
    }
}