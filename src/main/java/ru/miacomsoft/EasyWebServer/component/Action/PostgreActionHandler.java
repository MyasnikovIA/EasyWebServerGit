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

    // Кэш для действий PostgreSQL (имя -> параметры)
    public final Map<String, HashMap<String, Object>> procedureList = new ConcurrentHashMap<>();

    // Кэш для проверки существования процедур в БД
    private final Map<String, Boolean> procedureExistsCache = new HashMap<>();
    private final Map<String, Boolean> schemaExistsCache = new HashMap<>();
    private final Map<String, Boolean> databaseExistsCache = new HashMap<>();
    private final Map<String, String> procedureContentHashCache = new ConcurrentHashMap<>();
    private final Map<String, Long> databaseCheckTimestamp = new HashMap<>();
    private static final long CACHE_TTL = 60000;

    private boolean debugMode = false;
    private DatabaseConfig currentDbConfig;

    private PostgreActionHandler() {}

    // ========== ИНИЦИАЛИЗАЦИЯ (ТОЛЬКО СОХРАНЕНИЕ В КЭШ) ==========
    public void handlePostgreAction(Document doc, Element element, cmpAction.ActionConfig config, Base base) {
        System.out.println("=== handlePostgreAction (cache only) for: " + config.name);

        if (config.dbConfig == null) {
            System.err.println("Database config not found for: " + config.dbName);
            element.empty();
            element.append("Database configuration not found for: " + config.dbName);
            element.removeAttr("style");
            return;
        }

        this.debugMode = (doc != null && doc.hasAttr("debug_mode") && Boolean.parseBoolean(doc.attr("debug_mode")));
        this.currentDbConfig = config.dbConfig;

        String sqlContent = element.hasText() ? element.text().trim() : "";
        List<PostgreVar> variables = parseVariables(element);

        // Только сохраняем в кэш - НЕ СОЗДАЁМ ПРОЦЕДУРУ
        saveToCache(config.name, config, variables, sqlContent);

        setComponentAttributes(element, config, variables);
        finalizeElement(element, config, base);
    }

    /**
     * Сохранение конфигурации действия в кэш без создания процедуры в БД
     */
    private void saveToCache(String name, cmpAction.ActionConfig config,
                             List<PostgreVar> variables, String sqlContent) {
        HashMap<String, Object> param = createBaseParam(variables, config);
        param.put("SQL", sqlContent);
        param.put("SQL_RAW", sqlContent);
        param.put("dbConfig", config.dbConfig);
        param.put("schema", config.schema);
        param.put("dbName", config.dbName);
        param.put("dbType", config.dbType);
        param.put("query_type", "sql");
        param.put("isOracle", false);
        param.put("variables", variables);
        param.put("contentHash", getShortHash(sqlContent));
        param.put("procedureName", name);  // ДОБАВИТЬ - сохраняем имя процедуры

        procedureList.put(name, param);
        if (config.schema != null && !config.schema.isEmpty()) {
            procedureList.put(config.schema + "." + name, param);
        }

        System.out.println("PostgreSQL action cached (not created yet): " + name);
    }

    // ========== ВЫПОЛНЕНИЕ (С ЛЕНИВЫМ СОЗДАНИЕМ ПРОЦЕДУРЫ) ==========
    public void executePostgresAction(HttpExchange query, JSONObject result, String actionName,
                                      JSONObject vars, Map<String, Object> session, boolean debugMode) {
        System.out.println("=== executePostgresAction: " + actionName + " ===");

        HashMap<String, Object> param = findActionInCache(actionName);
        if (param == null) {
            result.put("ERROR", "Action not found: " + actionName);
            return;
        }

        String schema = (String) param.get("schema");
        String contentHash = (String) param.get("contentHash");
        List<PostgreVar> variables = (List<PostgreVar>) param.get("variables");
        DatabaseConfig dbConfig = (DatabaseConfig) param.get("dbConfig");

        // ИСПРАВЛЕНО: используем actionName как имя процедуры
        String procedureName = actionName;

        if (dbConfig == null) {
            result.put("ERROR", "Database configuration not found");
            return;
        }

        boolean procedureExists = checkProcedureExistsInDB(dbConfig, schema, procedureName);
        boolean needsRecreation = debugMode || !procedureExists || needsRecreationByHash(schema + "." + procedureName, contentHash);

        if (needsRecreation) {
            System.out.println("Creating/updating PostgreSQL procedure on first call: " + schema + "." + procedureName);

            String sqlContent = (String) param.get("SQL_RAW");

            if (!createOrReplaceProcedure(dbConfig, schema, procedureName, sqlContent, variables, contentHash)) {
                result.put("ERROR", "Failed to create procedure: " + schema + "." + procedureName);
                result.put("SQL", sqlContent);
                return;
            }

            updateProcedureHashCache(schema + "." + procedureName, contentHash);
        }

        // ИСПРАВЛЕНО: передаём procedureName в param
        param.put("procedureName", procedureName);

        executeProcedure(query, result, param, vars, session, debugMode);
    }

    /**
     * Поиск действия в кэше по имени (с поддержкой схемы)
     */
    private HashMap<String, Object> findActionInCache(String actionName) {
        // Прямой поиск
        if (procedureList.containsKey(actionName)) {
            return procedureList.get(actionName);
        }

        // Поиск с добавлением схемы public
        if (procedureList.containsKey("public." + actionName)) {
            return procedureList.get("public." + actionName);
        }

        // Поиск по суффиксу
        for (Map.Entry<String, HashMap<String, Object>> entry : procedureList.entrySet()) {
            String key = entry.getKey();
            if (key.endsWith("." + actionName)) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * Проверка существования процедуры в БД (с кэшированием)
     */
    private boolean checkProcedureExistsInDB(DatabaseConfig dbConfig, String schema, String procedureName) {
        String cacheKey = schema + "." + procedureName;

        if (procedureExistsCache.containsKey(cacheKey)) {
            return procedureExistsCache.get(cacheKey);
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
                ps.setString(2, procedureName);
                ResultSet rs = ps.executeQuery();
                boolean exists = rs.next() && rs.getBoolean(1);
                procedureExistsCache.put(cacheKey, exists);
                return exists;
            }
        } catch (SQLException e) {
            System.err.println("Error checking procedure existence: " + e.getMessage());
            return false;
        } finally {
            closeConnectionQuietly(conn);
        }
    }

    /**
     * Проверка, нужно ли обновить процедуру по хэшу содержимого
     */
    private boolean needsRecreationByHash(String fullName, String currentHash) {
        if (currentHash == null) return true;
        String cachedHash = procedureContentHashCache.get(fullName);
        return cachedHash == null || !cachedHash.equals(currentHash);
    }

    /**
     * Обновление кэша хэша процедуры
     */
    private void updateProcedureHashCache(String name, String hash) {
        procedureContentHashCache.put(name, hash);
    }

    /**
     * Создание или замена процедуры в БД
     */
    private boolean createOrReplaceProcedure(DatabaseConfig dbConfig, String schema, String procedureName,
                                             String sqlContent, List<PostgreVar> variables, String contentHash) {
        Connection conn = null;
        try {
            conn = getConnectionFromConfig(dbConfig);
            if (conn == null) return false;

            String cleanName = sanitizeName(procedureName);  // используем procedureName, а не что-то другое
            String signature = buildProcedureSignature(schema, cleanName, variables);
            String body = buildProcedureBody(sqlContent, contentHash);
            String fullSQL = signature + "\n" + body;

            System.out.println("Creating procedure with SQL:\n" + fullSQL);  // ДЛЯ ОТЛАДКИ

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DROP PROCEDURE IF EXISTS " + schema + "." + cleanName + " CASCADE");
                stmt.execute(fullSQL);
                conn.commit();
                System.out.println("PostgreSQL procedure created: " + schema + "." + cleanName);
                return true;
            }
        } catch (SQLException e) {
            System.err.println("Error creating procedure: " + e.getMessage());
            rollbackQuietly(conn);
            return false;
        } finally {
            closeConnectionQuietly(conn);
        }
    }

    /**
     * Выполнение процедуры
     */
    private void executeProcedure(HttpExchange query, JSONObject result, HashMap<String, Object> param,
                                  JSONObject vars, Map<String, Object> session, boolean debugMode) {
        String schema = (String) param.get("schema");
        String procedureName = (String) param.get("procedureName");
        if (procedureName == null) {
            procedureName = (String) param.get("dbName");
        }
        if (procedureName == null) {
            procedureName = (String) param.get("name");
        }
        if (procedureName == null) {
            procedureName = "procedure";
        }

        List<PostgreVar> variables = (List<PostgreVar>) param.get("variables");
        DatabaseConfig dbConfig = (DatabaseConfig) param.get("dbConfig");

        if (variables == null) variables = new ArrayList<>();

        Connection conn = null;
        CallableStatement cs = null;

        try {
            String jdbcUrl = buildJdbcUrlWithSchema(dbConfig, schema);
            Properties props = new Properties();
            props.setProperty("user", dbConfig.getUsername());
            props.setProperty("password", dbConfig.getPassword());
            props.setProperty("currentSchema", schema);
            conn = DriverManager.getConnection(jdbcUrl, props);
            conn.setAutoCommit(false);

            setSearchPath(conn, schema);

            // Если в имени процедуры есть точка, используем как есть, иначе добавляем схему
            String fullProcedureName;
            if (procedureName.contains(".")) {
                fullProcedureName = procedureName;
            } else {
                fullProcedureName = schema + "." + procedureName;
            }

            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < variables.size(); i++) {
                if (i > 0) placeholders.append(",");
                placeholders.append("?");
            }

            String callSql = "CALL " + fullProcedureName + "(" + placeholders.toString() + ")";
            System.out.println("Call SQL: " + callSql);

            cs = conn.prepareCall(callSql);

            // Регистрируем OUT параметры
            int idx = 1;
            for (PostgreVar var : variables) {
                if ("OUT".equals(var.direction) || "INOUT".equals(var.direction)) {
                    registerOutParameter(cs, idx, var.type);
                }
                idx++;
            }

            // Устанавливаем IN параметры
            idx = 1;
            for (PostgreVar var : variables) {
                if ("IN".equals(var.direction) || "INOUT".equals(var.direction)) {
                    String value = getValueFromVars(vars, session, var.name, var.defaultVal);
                    setParameter(cs, idx, value, var.type, conn);
                }
                idx++;
            }

            cs.execute();
            conn.commit();

            // Получаем OUT параметры
            idx = 1;
            for (PostgreVar var : variables) {
                if ("OUT".equals(var.direction) || "INOUT".equals(var.direction)) {
                    String outValue = getOutParameter(cs, idx, var.type);
                    updateVars(vars, session, var.name, outValue, var.srctype);
                }
                idx++;
            }

            result.put("vars", vars);
            if (debugMode) {
                result.put("procedure", schema + "." + procedureName);
                result.put("call_sql", callSql);
                if (param.containsKey("SQL")) {
                    result.put("SQL", param.get("SQL"));
                }
            }

        } catch (SQLException e) {
            result.put("ERROR", "SQL Error: " + e.getMessage());
            e.printStackTrace();
            rollbackQuietly(conn);
        } catch (Exception e) {
            result.put("ERROR", "Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try { if (cs != null) cs.close(); } catch (Exception ignore) {}
            closeConnectionQuietly(conn);
        }
    }

    // ========== РАБОТА С БАЗОЙ ДАННЫХ ==========

    private void ensureDatabaseExists(DatabaseConfig dbConfig) {
        String dbName = dbConfig.getDatabase();
        if (!checkDatabaseExists(dbName, dbConfig) && !debugMode) {
            System.out.println("=== Creating database: " + dbName + " ===");
            createDatabase(dbName, dbConfig);
            sleepQuietly(2000);
        }
    }

    private void ensureSchemaExists(String schema, DatabaseConfig dbConfig) {
        if (!checkSchemaExists(schema, dbConfig) && !debugMode) {
            System.out.println("=== Creating schema: " + schema + " ===");
            createSchema(schema, dbConfig);
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

    // ========== ПАРСИНГ ПЕРЕМЕННЫХ ==========

    private List<PostgreVar> parseVariables(Element element) {
        List<PostgreVar> vars = new ArrayList<>();
        for (Element child : element.children()) {
            String tag = child.tag().toString().toLowerCase();
            if (tag.contains("var") || tag.contains("cmpactionvar")) {
                vars.add(parseActionVar(child));
            }
        }
        return vars;
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
        param.put("name", config != null ? config.name : null);
        param.put("key", config != null ? config.name : null);
        return param;
    }

    private void setComponentAttributes(Element element, cmpAction.ActionConfig config, List<PostgreVar> variables) {
        element.attr("style", "display:none");
        element.attr("action_name", config.name);
        element.attr("name", config.name);
        element.attr("vars", buildVarsJson(variables));
        element.attr("query_type", config.queryType);
        element.attr("db_type", config.dbType);
        element.attr("pg_schema", config.schema);
        element.attr("db", config.dbName);
    }

    private void finalizeElement(Element element, cmpAction.ActionConfig config, Base base) {
        element.empty();
        if (base != null) {
            base.attr("query_type", config.queryType);
            base.attr("db_type", config.dbType);
            base.attr("pg_schema", config.schema);
            base.attr("db", config.dbName);
            base.attr("name", config.name);
        }
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

    // ========== ГЕНЕРАЦИЯ ИМЁН И ХЭШЕЙ ==========

    private String buildProcedureSignature(String schema, String procName, List<PostgreVar> variables) {
        StringBuilder params = new StringBuilder();
        for (PostgreVar var : variables) {
            String sqlType = mapJavaTypeToSqlType(var.type, var.len);
            String direction = "";
            if ("OUT".equals(var.direction)) {
                direction = "OUT ";
            } else if ("INOUT".equals(var.direction)) {
                direction = "INOUT ";
            }
            params.append(var.name).append(" ").append(direction).append(sqlType).append(",");
        }
        String paramsStr = params.length() > 0 ? params.substring(0, params.length() - 1) : "";
        return "CREATE OR REPLACE PROCEDURE " + schema + "." + procName + "(" + paramsStr + ")\nLANGUAGE plpgsql\nAS $$";
    }

    private String buildProcedureBody(String sqlContent, String contentHash) {
        StringBuilder body = new StringBuilder();
        body.append("BEGIN\n");
        body.append("-- contentHash: ").append(contentHash).append("\n");

        // Разбиваем SQL на отдельные statements
        String[] statements = sqlContent.split(";(?=\\s*\\w+\\s+)");
        for (String stmt : statements) {
            String trimmed = stmt.trim();
            if (!trimmed.isEmpty()) {
                body.append(trimmed);
                if (!trimmed.endsWith(";")) body.append(";");
                body.append("\n");
            }
        }

        body.append("END;\n$$");
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

    private String sanitizeName(String name) {
        String clean = name.replaceAll("[^a-zA-Z0-9_]", "");
        if (clean.length() > 0 && Character.isDigit(clean.charAt(0))) clean = "f_" + clean;
        if (clean.length() > 60) clean = clean.substring(0, 60);
        return clean;
    }

    // ========== ВСПОМОГАТЕЛЬНЫЕ УТИЛИТЫ ==========

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
        } catch (Exception e) {
            return null;
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

    private String buildJdbcUrlWithSchema(DatabaseConfig dbConfig, String schema) {
        String url = dbConfig.getJdbcUrl();
        if (!url.contains("currentSchema")) {
            url += (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        }
        return url;
    }

    private void setSearchPath(Connection conn, String schema) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET search_path TO " + schema + ", public");
        }
    }

    private void registerOutParameter(CallableStatement cs, int idx, String type) throws SQLException {
        switch (type.toLowerCase()) {
            case "int": case "integer": cs.registerOutParameter(idx, Types.INTEGER); break;
            case "long": case "bigint": cs.registerOutParameter(idx, Types.BIGINT); break;
            case "decimal": case "numeric": cs.registerOutParameter(idx, Types.NUMERIC); break;
            case "bool": case "boolean": cs.registerOutParameter(idx, Types.BOOLEAN); break;
            case "date": cs.registerOutParameter(idx, Types.DATE); break;
            case "timestamp": cs.registerOutParameter(idx, Types.TIMESTAMP); break;
            case "json": case "jsonb": cs.registerOutParameter(idx, Types.OTHER); break;
            default: cs.registerOutParameter(idx, Types.VARCHAR);
        }
    }

    private void setParameter(CallableStatement cs, int idx, String value, String type, Connection conn) throws SQLException {
        if (value == null || value.isEmpty()) {
            cs.setNull(idx, Types.VARCHAR);
            return;
        }
        switch (type.toLowerCase()) {
            case "int": case "integer": cs.setInt(idx, Integer.parseInt(value)); break;
            case "long": case "bigint": cs.setLong(idx, Long.parseLong(value)); break;
            case "decimal": case "numeric": cs.setBigDecimal(idx, new BigDecimal(value)); break;
            case "bool": case "boolean": cs.setBoolean(idx, Boolean.parseBoolean(value)); break;
            case "date": cs.setDate(idx, Date.valueOf(value)); break;
            case "timestamp": cs.setTimestamp(idx, Timestamp.valueOf(value.replace("T", " "))); break;
            case "json": case "jsonb": cs.setObject(idx, value, Types.OTHER); break;
            default: cs.setString(idx, value);
        }
    }

    private String getOutParameter(CallableStatement cs, int idx, String type) throws SQLException {
        switch (type.toLowerCase()) {
            case "int": case "integer": return cs.wasNull() ? "" : String.valueOf(cs.getInt(idx));
            case "long": case "bigint": return cs.wasNull() ? "" : String.valueOf(cs.getLong(idx));
            case "decimal": case "numeric":
                BigDecimal bd = cs.getBigDecimal(idx);
                return bd == null ? "" : bd.toString();
            case "bool": case "boolean": return cs.wasNull() ? "" : String.valueOf(cs.getBoolean(idx));
            case "date": Date d = cs.getDate(idx); return d == null ? "" : d.toString();
            case "timestamp": Timestamp ts = cs.getTimestamp(idx); return ts == null ? "" : ts.toString();
            default:
                String s = cs.getString(idx);
                return s == null ? "" : s;
        }
    }

    private String getValueFromVars(JSONObject vars, Map<String, Object> session, String name, String defaultValue) {
        if (!vars.has(name)) return defaultValue != null ? defaultValue : "";
        Object val = vars.get(name);
        if (val instanceof JSONObject) {
            JSONObject obj = (JSONObject) val;
            if ("session".equals(obj.optString("srctype"))) {
                Object sessionVal = session.get(name);
                return sessionVal != null ? sessionVal.toString() : obj.optString("defaultVal", defaultValue);
            }
            return obj.optString("value", obj.optString("defaultVal", defaultValue));
        }
        return val.toString();
    }

    private void updateVars(JSONObject vars, Map<String, Object> session, String name, String value, String srctype) {
        if (vars.has(name) && vars.get(name) instanceof JSONObject) {
            JSONObject obj = vars.getJSONObject(name);
            if ("session".equals(srctype)) {
                session.put(name, value);
            } else {
                obj.put("value", value);
            }
        } else {
            JSONObject wrapper = new JSONObject();
            wrapper.put("value", value);
            wrapper.put("src", name);
            wrapper.put("srctype", srctype != null ? srctype : "var");
            vars.put(name, wrapper);
        }
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

    // ========== ВНУТРЕННИЙ КЛАСС ==========

    private static class PostgreVar {
        String name, src, srctype, len, defaultVal, type, direction;
    }
}