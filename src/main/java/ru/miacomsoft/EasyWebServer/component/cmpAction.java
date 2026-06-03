package ru.miacomsoft.EasyWebServer.component;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import ru.miacomsoft.EasyWebServer.DatabaseConfig;
import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.OracleQuery;
import ru.miacomsoft.EasyWebServer.ServerConstant;
import ru.miacomsoft.EasyWebServer.ServerResourceHandler;

import java.math.BigDecimal;
import java.sql.*;
import java.sql.Date;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static ru.miacomsoft.EasyWebServer.PostgreQuery.*;

@SuppressWarnings("unchecked")
public class cmpAction extends Base {

    // ======================== СТАТИЧЕСКИЕ КЭШИ ========================
    private static final Map<String, Boolean> functionExistsCache = new HashMap<>();
    private static final Map<String, Boolean> schemaExistsCache = new HashMap<>();
    private static final Map<String, Boolean> databaseExistsCache = new HashMap<>();
    private static final Map<String, String> functionContentHashCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> databaseCheckTimestamp = new HashMap<>();
    private static final long CACHE_TTL = 60000;

    // ======================== ПОЛЯ ЭКЗЕМПЛЯРА ========================
    private boolean debugMode = false;
    private String currentContentHash;
    private String currentFunctionName;
    private DatabaseConfig currentDbConfig;

    // ======================== КОНСТРУКТОРЫ ========================
    public cmpAction(Document doc, Element element, String tag) {
        super(doc, element, tag);
        if (doc != null && doc.hasAttr("debug_mode")) {
            debugMode = Boolean.parseBoolean(doc.attr("debug_mode"));
        }
        initialize(doc, element);
    }

    public cmpAction(Document doc, Element element) {
        super(doc, element, "textarea");
        if (doc != null && doc.hasAttr("debug_mode")) {
            debugMode = Boolean.parseBoolean(doc.attr("debug_mode"));
        }
        initialize(doc, element);
    }

    // ======================== ИНИЦИАЛИЗАЦИЯ ========================
    private void initialize(Document doc, Element element) {
        ActionConfig config = parseActionConfig(doc, element);
        currentDbConfig = config.dbConfig;

        setupDefaultDatabaseConfig(config);

        if (config.isOracle) {
            handleOracleAction(doc, element, config);
        } else {
            handlePostgreAction(doc, element, config);
        }

        attachJavaScriptLibrary(doc, config.name);
    }

    // ======================== КОНФИГУРАЦИЯ ========================
    private ActionConfig parseActionConfig(Document doc, Element element) {
        ActionConfig config = new ActionConfig();
        Attributes attrs = element.attributes();

        config.name = attrs.get("name");
        config.dbName = RemoveArrKeyRtrn(attrs, "db", "default");
        config.queryType = attrs.hasKey("query_type") ? attrs.get("query_type") : "sql";
        config.schema = attrs.hasKey("schema") ? attrs.get("schema") : "public";
        config.dbType = attrs.hasKey("db_type") ? attrs.get("db_type") : "jdbc";
        config.docPath = doc != null ? doc.attr("doc_path") : "";
        config.rootPath = doc != null ? doc.attr("rootPath") : "";

        config.dbConfig = getDatabaseConfiguration(config);
        if (config.dbConfig != null) {
            config.dbType = config.dbConfig.getType().toLowerCase();
            config.isOracle = config.dbConfig.getType().equals("oci8");
            if (!attrs.hasKey("schema") && config.dbName.equals("default")) {
                config.schema = config.dbConfig.getSchema() != null ? config.dbConfig.getSchema() : "public";
            }
        }

        return config;
    }

    private DatabaseConfig getDatabaseConfiguration(ActionConfig config) {
        if (config.dbName.equals("default") || config.dbName.equals("db")) {
            DatabaseConfig dbConfig = ServerConstant.config.DATABASES.get("default");
            if (dbConfig == null) {
                dbConfig = createDefaultPostgresConfig();
            }
            return dbConfig;
        }
        return ServerConstant.config.getDatabaseConfig(config.dbName.toLowerCase());
    }

    private DatabaseConfig createDefaultPostgresConfig() {
        String url = ServerConstant.config.DATABASE_NAME;
        String user = ServerConstant.config.DATABASE_USER_NAME;
        String pass = ServerConstant.config.DATABASE_USER_PASS;

        if (url == null || url.isEmpty() || user == null || user.isEmpty()) {
            System.err.println("Cannot create default config: missing DATABASE_NAME or DATABASE_USER_NAME");
            return null;
        }

        try {
            DatabaseConfig config = new DatabaseConfig();
            config.setType("jdbc");
            config.setDriver("org.postgresql.Driver");

            String withoutProtocol = url.substring(url.indexOf("://") + 3);
            String[] parts = withoutProtocol.split("/", 2);
            String hostPort = parts[0];
            String database = parts.length > 1 ? parts[1] : "postgres";
            String[] hp = hostPort.split(":");

            config.setHost(hp[0]);
            config.setPort(hp.length > 1 ? hp[1] : "5432");
            config.setDatabase(database);
            config.setUsername(user);
            config.setPassword(pass);
            config.setSchema("public");

            System.out.println("Created default PostgreSQL config: " + config.getHost() + ":" + config.getPort() + "/" + config.getDatabase());
            return config;
        } catch (Exception e) {
            System.err.println("Failed to create default PostgreSQL config: " + e.getMessage());
            return null;
        }
    }

    private void setupDefaultDatabaseConfig(ActionConfig config) {
        if ((config.dbName.equals("default") || config.dbName.equals("db")) &&
                !ServerConstant.config.DATABASES.containsKey("default") &&
                config.dbConfig != null) {
            ServerConstant.config.DATABASES.put("default", config.dbConfig);
            System.out.println("Added default PostgreSQL config to DATABASES");
        }
    }

    // ======================== ORACLE ОБРАБОТКА ========================
    private void handleOracleAction(Document doc, Element element, ActionConfig config) {
        System.out.println("Oracle mode: " + config.name);

        List<ActionVar> variables = parseVariables(element);
        String sqlContent = element.hasText() ? element.text().trim() : "";
        ParsedSql parsed = parseNamedParameters(sqlContent);

        HashMap<String, Object> param = createBaseParam(variables, config);
        param.put("SQL", parsed.processedSql);
        param.put("SQL_RAW", sqlContent);
        param.put("SQL_PARAMS", parsed.paramNames);

        // Сохраняем с полным именем и с коротким
        String fullName = config.schema + "." + config.name;
        procedureList.put(fullName, param);
        procedureList.put(config.name, param);

        System.out.println("Oracle action saved: " + fullName + " and " + config.name);

        // Устанавливаем атрибуты для клиентской части
        setOracleComponentAttributes(element, config, variables);

        finalizeElement(element, config);
    }

    /**
     * Установка атрибутов для Oracle-действия
     */
    private void setOracleComponentAttributes(Element element, ActionConfig config, List<ActionVar> variables) {
        element.attr("style", "display:none");
        element.attr("action_name", config.name);
        element.attr("name", config.name);
        element.attr("vars", buildVarsJson(variables));
        element.attr("query_type", config.queryType);
        element.attr("db_type", config.dbType);
        element.attr("pg_schema", config.schema);
        element.attr("db", config.dbName);
    }


    // ======================== POSTGRESQL ОБРАБОТКА ========================
    private void handlePostgreAction(Document doc, Element element, ActionConfig config) {
        if (config.dbConfig == null) {
            System.err.println("Database config not found for: " + config.dbName);
            return;
        }

        initializeDatabase(config);
        initializeSchema(config);

        String sqlContent = element.hasText() ? element.text().trim() : "";
        String contentHash = getShortHash(sqlContent);
        currentContentHash = contentHash;

        String functionName = generateFunctionName(config, contentHash);
        currentFunctionName = functionName;
        String fullFunctionName = config.schema + "." + functionName;

        List<ActionVar> variables = parseVariables(element);
        setComponentAttributes(config, functionName, variables);

        if (needsRecreation(fullFunctionName, contentHash, config)) {
            createPostgresProcedure(fullFunctionName, config, functionName, sqlContent, element, doc, variables, contentHash);
            updateFunctionHashCache(fullFunctionName, contentHash);
        } else {
            loadExistingProcedureToCache(fullFunctionName, config.schema, element, config.dbConfig);
        }

        finalizeElement(element, config);
    }

    private void initializeDatabase(ActionConfig config) {
        String targetDbName = config.dbConfig.getDatabase();
        boolean dbExists = checkDatabaseExistsCached(targetDbName, config.dbConfig);

        if (!dbExists && !debugMode) {
            System.out.println("=== Creating database: " + targetDbName + " ===");
            if (createDatabaseIfNotExists(targetDbName, config.dbConfig)) {
                sleepQuietly(2000);
            }
        }
    }

    private void initializeSchema(ActionConfig config) {
        boolean schemaExists = checkSchemaExistsCached(config.schema, config.dbName, config.dbConfig);

        if (!schemaExists && !debugMode) {
            System.out.println("=== Creating schema: " + config.schema + " ===");
            if (createSchemaIfNotExists(config.schema, config.dbName, config.dbConfig)) {
                sleepQuietly(1000);
            }
        }
    }

    private void createPostgresProcedure(String fullFunctionName, ActionConfig config, String procName,
                                         String sqlContent, Element element, Document doc,
                                         List<ActionVar> variables, String contentHash) {
        Connection conn = connectToPostgres(config.dbConfig);
        if (conn == null) return;

        String cleanName = sanitizeProcedureName(procName);
        String signature = buildProcedureSignature(config.schema, cleanName, variables);
        String body = buildProcedureBody(sqlContent, contentHash, element, doc);
        String fullSQL = signature + "\n" + body;

        System.out.println("Creating procedure:\n" + fullSQL);

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP PROCEDURE IF EXISTS " + config.schema + "." + cleanName + " CASCADE");
            stmt.execute(fullSQL);
            System.out.println("Procedure created: " + config.schema + "." + cleanName);

            saveProcedureToCache(fullFunctionName, config.schema, cleanName, variables, sqlContent, contentHash, conn);
        } catch (SQLException e) {
            System.err.println("Error creating procedure: " + e.getMessage());
            e.printStackTrace();
        } finally {
            closeConnectionQuietly(conn);
        }
    }

    private String sanitizeProcedureName(String name) {
        String clean = name.replaceAll("[^a-zA-Z0-9_]", "");
        if (clean.length() > 0 && Character.isDigit(clean.charAt(0))) {
            clean = "f_" + clean;
        }
        return clean;
    }

    private String buildProcedureSignature(String schema, String procName, List<ActionVar> variables) {
        StringBuilder params = new StringBuilder();
        for (ActionVar var : variables) {
            String sqlType = mapJavaTypeToSqlType(var.type, var.len);
            params.append(var.name).append(" ").append(var.direction).append(" ").append(sqlType).append(",");
        }

        String paramsStr = params.length() > 0 ? params.substring(0, params.length() - 1) : "";
        return "CREATE OR REPLACE PROCEDURE " + schema + "." + procName + "(" + paramsStr + ")\n" +
                "LANGUAGE plpgsql\nAS $$";
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

    private void saveProcedureToCache(String fullName, String schema, String procName,
                                      List<ActionVar> variables, String sqlContent,
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
        param.put("prepareCall", "CALL " + schema + "." + procName + "(" + placeholders.toString() + ")");

        procedureList.put(fullName, param);
        procedureList.put(procName, param);
    }

    // ======================== ПАРСИНГ ПЕРЕМЕННЫХ ========================
    private List<ActionVar> parseVariables(Element element) {
        List<ActionVar> variables = new ArrayList<>();

        for (Element child : element.children()) {
            String tagName = child.tag().toString().toLowerCase();
            if (tagName.contains("var") || tagName.contains("cmpactionvar")) {
                variables.add(parseActionVar(child));
            }
        }
        return variables;
    }

    private ActionVar parseActionVar(Element element) {
        Attributes attrs = element.attributes();
        ActionVar var = new ActionVar();

        var.name = attrs.get("name");
        var.src = RemoveArrKeyRtrn(attrs, "src", var.name);
        var.srctype = RemoveArrKeyRtrn(attrs, "srctype", "var");
        var.len = RemoveArrKeyRtrn(attrs, "len", "");
        var.defaultVal = RemoveArrKeyRtrn(attrs, "default", "");
        var.type = RemoveArrKeyRtrn(attrs, "type", "string");

        String putAttr = attrs.hasKey("put") ? attrs.get("put") : null;
        String getAttr = attrs.hasKey("get") ? attrs.get("get") : null;

        if (putAttr != null && getAttr != null) {
            var.direction = "INOUT";
        } else if (putAttr != null) {
            var.direction = "OUT";
        } else {
            var.direction = "IN";
        }

        return var;
    }

    private HashMap<String, Object> createBaseParam(List<ActionVar> variables, ActionConfig config) {
        HashMap<String, Object> param = new HashMap<>();
        param.put("vars", extractVarNames(variables));
        param.put("varTypes", extractVarTypes(variables));
        param.put("varDirections", extractVarDirections(variables));

        if (config != null) {
            param.put("dbType", config.dbType);
            param.put("dbName", config.dbName);
            param.put("contentHash", currentContentHash);
        }

        return param;
    }

    private List<String> extractVarNames(List<ActionVar> variables) {
        List<String> names = new ArrayList<>();
        for (ActionVar var : variables) names.add(var.name);
        return names;
    }

    private Map<String, String> extractVarTypes(List<ActionVar> variables) {
        Map<String, String> types = new HashMap<>();
        for (ActionVar var : variables) types.put(var.name, var.type);
        return types;
    }

    private Map<String, String> extractVarDirections(List<ActionVar> variables) {
        Map<String, String> directions = new HashMap<>();
        for (ActionVar var : variables) directions.put(var.name, var.direction);
        return directions;
    }

    private String buildVarsJson(List<ActionVar> variables) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < variables.size(); i++) {
            ActionVar var = variables.get(i);
            if (i > 0) json.append(",");
            json.append("'").append(var.name).append("':{");
            json.append("'src':'").append(var.src).append("',");
            json.append("'srctype':'").append(var.srctype).append("',");
            json.append("'direction':'").append(var.direction).append("'");
            if (!var.defaultVal.isEmpty()) {
                json.append(",'defaultVal':'").append(escapeJson(var.defaultVal)).append("'");
            }
            if (!var.len.isEmpty()) {
                json.append(",'len':'").append(var.len).append("'");
            }
            json.append("}");
        }
        json.append("}");
        return json.toString();
    }

    private void setComponentAttributes(ActionConfig config, String functionName, List<ActionVar> variables) {
        this.attr("style", "display:none");
        this.attr("action_name", functionName);
        this.attr("name", config.name);
        this.attr("vars", buildVarsJson(variables));
    }

    // ======================== ГЕНЕРАЦИЯ ИМЕНИ ========================
    private String generateFunctionName(ActionConfig config, String contentHash) {
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

    // ======================== РАБОТА С КЭШЕМ ========================
    private boolean needsRecreation(String functionName, String currentHash, ActionConfig config) {
        if (debugMode) {
            System.out.println("Debug mode: forcing recreation of " + functionName);
            return true;
        }

        String cachedHash = functionContentHashCache.get(functionName);
        if (cachedHash == null) return true;
        if (!cachedHash.equals(currentHash)) return true;
        if (!checkFunctionExistsInDB(functionName, config.schema, config.dbConfig)) return true;

        System.out.println("Function up to date: " + functionName);
        return false;
    }

    private void updateFunctionHashCache(String name, String hash) {
        functionContentHashCache.put(name, hash);
        System.out.println("Updated hash cache: " + name + " -> " + hash);
    }

    private void loadExistingProcedureToCache(String fullName, String schema, Element element, DatabaseConfig dbConfig) {
        List<ActionVar> variables = parseVariables(element);
        HashMap<String, Object> param = createBaseParam(variables, null);
        param.put("SQL", element.hasText() ? element.text().trim() : "");
        param.put("dbConfig", dbConfig);

        String procName = fullName.contains(".") ? fullName.substring(fullName.lastIndexOf('.') + 1) : fullName;
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < variables.size(); i++) {
            if (i > 0) placeholders.append(",");
            placeholders.append("?");
        }
        param.put("prepareCall", "CALL " + schema + "." + procName + "(" + placeholders.toString() + ")");

        procedureList.put(fullName, param);
        procedureList.put(procName, param);
        System.out.println("Loaded existing procedure: " + fullName);
    }

    // ======================== ЗАВЕРШЕНИЕ ИНИЦИАЛИЗАЦИИ ========================
    private void finalizeElement(Element element, ActionConfig config) {
        element.empty();


        // Сохраняем атрибуты
        this.attr("query_type", config.queryType);
        this.attr("db_type", config.dbType);
        this.attr("pg_schema", config.schema);
        this.attr("db", config.dbName);
        this.attr("name", config.name);
    }

    private void attachJavaScriptLibrary(Document doc, String name) {
        if (doc == null) return;
        Elements head = doc.getElementsByTag("head");
        if (head.isEmpty()) return;

        Elements existing = head.select("script[src*='cmpAction_js']");
        if (existing.isEmpty()) {
            head.append("<script cmp=\"action-lib\" src=\"{component}/cmpAction_js\" type=\"text/javascript\"></script>");
            System.out.println("cmpAction JS attached: " + name);
        }
    }

    // ======================== РАБОТА С БД (ОБЩАЯ ЛОГИКА) ========================
    private boolean checkDatabaseExistsCached(String dbName, DatabaseConfig dbConfig) {
        String key = "db:" + dbName;
        if (databaseExistsCache.containsKey(key)) {
            Long ts = databaseCheckTimestamp.get(key);
            if (ts != null && System.currentTimeMillis() - ts < CACHE_TTL) {
                return databaseExistsCache.get(key);
            }
        }

        boolean exists = checkDatabaseExistsDirect(dbName, dbConfig);
        databaseExistsCache.put(key, exists);
        databaseCheckTimestamp.put(key, System.currentTimeMillis());
        return exists;
    }

    private boolean checkDatabaseExistsDirect(String dbName, DatabaseConfig dbConfig) {
        String adminUrl = "jdbc:postgresql://" + dbConfig.getHost() + ":" + dbConfig.getPort() + "/postgres";
        try (Connection conn = createSimpleConnection(adminUrl, dbConfig)) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
                ps.setString(1, dbName);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            System.err.println("Error checking database: " + e.getMessage());
            return false;
        }
    }

    private boolean createDatabaseIfNotExists(String dbName, DatabaseConfig dbConfig) {
        String adminUrl = "jdbc:postgresql://" + dbConfig.getHost() + ":" + dbConfig.getPort() + "/postgres";
        try (Connection conn = createSimpleConnection(adminUrl, dbConfig)) {
            conn.setAutoCommit(true);
            String sql = String.format("CREATE DATABASE \"%s\" WITH OWNER = \"%s\" ENCODING = 'UTF8'",
                    dbName.replace("\"", "\"\""),
                    dbConfig.getUsername().replace("\"", "\"\""));
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(sql);
                return true;
            }
        } catch (SQLException e) {
            if (e.getMessage().contains("already exists")) return true;
            System.err.println("Error creating database: " + e.getMessage());
            return false;
        }
    }

    private boolean checkSchemaExistsCached(String schemaName, String dbName, DatabaseConfig dbConfig) {
        String key = dbName + ":schema:" + schemaName;
        if (schemaExistsCache.containsKey(key)) return schemaExistsCache.get(key);

        boolean exists = checkSchemaExistsDirect(schemaName, dbConfig);
        schemaExistsCache.put(key, exists);
        return exists;
    }

    private boolean checkSchemaExistsDirect(String schemaName, DatabaseConfig dbConfig) {
        try (Connection conn = createSimpleConnection(dbConfig.getJdbcUrl(), dbConfig)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT EXISTS(SELECT 1 FROM information_schema.schemata WHERE schema_name = ?)")) {
                ps.setString(1, schemaName);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error checking schema: " + e.getMessage());
            return false;
        }
    }

    private boolean createSchemaIfNotExists(String schemaName, String dbName, DatabaseConfig dbConfig) {
        try (Connection conn = createSimpleConnection(dbConfig.getJdbcUrl(), dbConfig)) {
            conn.setAutoCommit(false);
            String sql = String.format("CREATE SCHEMA IF NOT EXISTS \"%s\" AUTHORIZATION \"%s\"",
                    schemaName.replace("\"", "\"\""),
                    dbConfig.getUsername().replace("\"", "\"\""));
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(sql);
                conn.commit();
                schemaExistsCache.put(dbName + ":schema:" + schemaName, true);
                return true;
            }
        } catch (SQLException e) {
            System.err.println("Error creating schema: " + e.getMessage());
            return false;
        }
    }

    private boolean checkFunctionExistsInDB(String functionName, String schema, DatabaseConfig dbConfig) {
        String cleanName = functionName.contains(".") ? functionName.substring(functionName.lastIndexOf('.') + 1) : functionName;

        try (Connection conn = createSimpleConnection(dbConfig.getJdbcUrl(), dbConfig)) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON p.pronamespace = n.oid " +
                            "WHERE n.nspname = ? AND p.proname = ?)")) {
                ps.setString(1, schema);
                ps.setString(2, cleanName);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error checking function: " + e.getMessage());
            return false;
        }
    }

    private Connection createSimpleConnection(String url, DatabaseConfig dbConfig) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", dbConfig.getUsername());
        props.setProperty("password", dbConfig.getPassword());
        props.setProperty("connectTimeout", "10");
        props.setProperty("socketTimeout", "30");
        return DriverManager.getConnection(url, props);
    }

    private Connection connectToPostgres(DatabaseConfig dbConfig) {
        try {
            Class.forName("org.postgresql.Driver");
            Properties props = new Properties();
            props.setProperty("user", dbConfig.getUsername());
            props.setProperty("password", dbConfig.getPassword());
            props.setProperty("connectTimeout", "10");
            props.setProperty("socketTimeout", "30");
            return DriverManager.getConnection(dbConfig.getJdbcUrl(), props);
        } catch (Exception e) {
            System.err.println("Postgres connection failed: " + e.getMessage());
            return null;
        }
    }

    private void closeConnectionQuietly(Connection conn) {
        try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
    }

    private void sleepQuietly(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ======================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ========================
    private String mapJavaTypeToSqlType(String type, String len) {
        switch (type.toLowerCase()) {
            case "integer": case "int": return "INTEGER";
            case "bigint": case "long": return "BIGINT";
            case "decimal": case "numeric": return "NUMERIC";
            case "boolean": case "bool": return "BOOLEAN";
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

    private String escapeJson(String s) {
        return s.replace("'", "\\\\'");
    }

    // ======================== ПАРСИНГ ИМЕНОВАННЫХ ПАРАМЕТРОВ ========================
    private ParsedSql parseNamedParameters(String sql) {
        if (sql == null || sql.isEmpty()) {
            return new ParsedSql(sql, Collections.emptyList());
        }

        StringBuilder processed = new StringBuilder(sql.length() + 16);
        List<String> paramNames = new ArrayList<>();
        StringBuilder currentParam = new StringBuilder();
        boolean inParam = false;
        boolean inQuote = false;
        char quoteChar = 0;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            if ((c == '\'' || c == '"') && (i == 0 || sql.charAt(i - 1) != '\\')) {
                if (!inQuote) {
                    inQuote = true;
                    quoteChar = c;
                } else if (quoteChar == c) {
                    inQuote = false;
                }
            }

            if (inQuote) {
                processed.append(c);
                continue;
            }

            if (c == ':' && !inParam) {
                inParam = true;
                currentParam = new StringBuilder();
            } else if (inParam && (Character.isLetterOrDigit(c) || c == '_')) {
                currentParam.append(c);
            } else if (inParam) {
                inParam = false;
                if (currentParam.length() > 0) {
                    paramNames.add(currentParam.toString());
                }
                processed.append('?').append(c);
            } else {
                processed.append(c);
            }
        }

        if (inParam && currentParam.length() > 0) {
            paramNames.add(currentParam.toString());
            processed.append('?');
        }

        return new ParsedSql(processed.toString(), paramNames);
    }

    // ======================== ОБРАБОТКА HTTP ЗАПРОСОВ (onPage) ========================
    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/json";

        RequestParams params = parseRequestParams(query);
        System.out.println("=== cmpAction onPage: " + params.actionName + " ===");
        logProcedureList();

        JSONObject vars = parseVarsFromBody(query);
        JSONObject result = new JSONObject();
        result.put("vars", vars);

        if ("java".equals(params.queryType)) {
            executeJavaAction(query, result, params.actionName, vars, query.session);
            return result.toString().getBytes();
        }

        if (!"sql".equals(params.queryType)) {
            result.put("ERROR", "Unsupported query type: " + params.queryType);
            return result.toString().getBytes();
        }

        Object actionParams = findActionInCache(params);
        if (actionParams == null) {
            result.put("ERROR", "Action not found: " + params.actionName);
            return result.toString().getBytes();
        }

        HashMap<String, Object> param = (HashMap<String, Object>) actionParams;
        String savedDbType = (String) param.get("dbType");
        boolean isOracle = "oci8".equals(savedDbType);

        String connectionDbName = resolveDbName(params.dbName, (String) param.get("dbName"));

        if (isOracle) {
            executeOracleAction(query, result, params.actionName, connectionDbName, vars, params.debugMode);
        } else {
            Map<String, String> varDirections = (Map<String, String>) param.get("varDirections");
            executePostgresAction(query, result, params.actionName, vars, query.session, params.debugMode, varDirections);
        }

        return result.toString().getBytes();
    }

    private static RequestParams parseRequestParams(HttpExchange query) {
        RequestParams params = new RequestParams();
        JSONObject qp = query.requestParam;

        params.queryType = qp.optString("query_type", "java");

        // Исправление: если пришла строка "null", заменяем на пустую
        String actionName = qp.optString("action_name", "");
        if ("null".equals(actionName)) {
            actionName = "";
        }
        params.actionName = actionName;

        params.pgSchema = qp.optString("pg_schema", "public");
        params.dbName = qp.optString("db", "DB");
        params.dbType = qp.optString("db_type", "jdbc");
        params.debugMode = query.session != null &&
                query.session.containsKey("debug_mode") &&
                (boolean) query.session.get("debug_mode");

        return params;
    }

    private static void logProcedureList() {
        System.out.println("=== procedureList keys ===");
        for (String key : procedureList.keySet()) {
            System.out.println("  " + key);
        }
        System.out.println("========================");
    }

    private static JSONObject parseVarsFromBody(HttpExchange query) {
        JSONObject vars = new JSONObject();
        String body = new String(query.postCharBody);
        if (body == null || body.isEmpty()) return vars;

        try {
            JSONObject requestVars = new JSONObject(body);
            Iterator<String> keys = requestVars.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object val = requestVars.get(key);
                if (val instanceof JSONObject) {
                    vars.put(key, val);
                } else {
                    JSONObject wrapper = new JSONObject();
                    wrapper.put("value", val.toString());
                    wrapper.put("src", key);
                    wrapper.put("srctype", "var");
                    vars.put(key, wrapper);
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing JSON: " + e.getMessage());
        }

        return vars;
    }

    private static Object findActionInCache(RequestParams params) {
        String actionName = params.actionName;
        if (actionName == null || actionName.isEmpty() || "null".equals(actionName)) {
            System.err.println("ERROR: action_name is empty or null");
            System.err.println("Available actions: " + procedureList.keySet());
            return null;
        }

        // Сначала ищем с той схемой, которая пришла в запросе
        String fullNameWithRequestSchema = params.pgSchema + "." + actionName;
        if (procedureList.containsKey(fullNameWithRequestSchema)) {
            System.out.println("Found by request schema: " + fullNameWithRequestSchema);
            return procedureList.get(fullNameWithRequestSchema);
        }

        // Потом просто по имени
        if (procedureList.containsKey(actionName)) {
            System.out.println("Found by name only: " + actionName);
            return procedureList.get(actionName);
        }

        // Поиск по любому совпадению в ключах
        for (String key : procedureList.keySet()) {
            if (key.endsWith("." + actionName)) {
                System.out.println("Found by suffix: " + key);
                return procedureList.get(key);
            }
            if (key.equals(actionName)) {
                System.out.println("Found by exact: " + key);
                return procedureList.get(key);
            }
        }

        System.err.println("Action NOT found: " + actionName);
        System.err.println("Available: " + procedureList.keySet());
        return null;
    }

    private static String resolveDbName(String requestDbName, String savedDbName) {
        if ("DB".equals(requestDbName) && savedDbName != null && !"DB".equals(savedDbName)) {
            return savedDbName;
        }
        return requestDbName;
    }

    // ======================== JAVA ACTION ========================
    private static void executeJavaAction(HttpExchange query, JSONObject result, String actionName,
                                          JSONObject vars, Map<String, Object> session) {
        JSONObject callVars = new JSONObject();
        Iterator<String> keys = vars.keys();

        while (keys.hasNext()) {
            String key = keys.next();
            Object val = vars.get(key);
            if (val instanceof JSONObject) {
                JSONObject obj = (JSONObject) val;
                String value = obj.optString("value", obj.optString("defaultVal", ""));
                callVars.put(key, value);
            } else {
                callVars.put(key, val.toString());
            }
        }

        JSONObject res = ServerResourceHandler.javaStrExecut.runFunction(actionName, callVars, session, null);

        if (res.has("JAVA_ERROR")) {
            result.put("ERROR", res.get("JAVA_ERROR"));
        } else {
            keys = res.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!"JAVA_ERROR".equals(key)) {
                    if (vars.has(key) && vars.get(key) instanceof JSONObject) {
                        vars.getJSONObject(key).put("value", res.get(key).toString());
                    } else {
                        JSONObject wrapper = new JSONObject();
                        wrapper.put("value", res.get(key).toString());
                        wrapper.put("src", key);
                        wrapper.put("srctype", "var");
                        vars.put(key, wrapper);
                    }
                }
            }
            result.put("vars", vars);
        }
    }

    // ======================== ORACLE ACTION ========================
    private static void executeOracleAction(HttpExchange query, JSONObject result, String actionName,
                                            String dbName, JSONObject vars, boolean debugMode) {
        System.out.println("=== executeOracleAction: " + actionName + " ===");

        // Пробуем найти с учётом схемы
        String pgSchema = query.requestParam.optString("pg_schema", "DEV");
        String fullName = pgSchema + "." + actionName;

        DatabaseConfig dbConfig = findOracleConfig(dbName);
        if (dbConfig == null) {
            result.put("ERROR", "Oracle config not found for: " + dbName);
            return;
        }

        // Ищем сначала полное имя, потом короткое
        HashMap<String, Object> param = null;
        if (procedureList.containsKey(fullName)) {
            param = (HashMap<String, Object>) procedureList.get(fullName);
        } else if (procedureList.containsKey(actionName)) {
            param = (HashMap<String, Object>) procedureList.get(actionName);
        } else {
            result.put("ERROR", "Action not found: " + actionName + " (tried: " + fullName + ")");
            System.err.println("Available actions: " + procedureList.keySet());
            return;
        }

        String sql = (String) param.get("SQL");
        List<String> sqlParamNames = (List<String>) param.get("SQL_PARAMS");
        Map<String, String> varTypes = (Map<String, String>) param.get("varTypes");
        Map<String, String> varDirections = (Map<String, String>) param.get("varDirections");

        try (Connection conn = OracleQuery.getConnect(dbConfig);
             CallableStatement cs = conn.prepareCall(sql)) {

            if (conn == null) {
                result.put("ERROR", "Oracle connection failed");
                return;
            }

            // Register OUT parameters
            int idx = 1;
            for (String pname : sqlParamNames) {
                String dir = varDirections.getOrDefault(pname, "IN");
                if ("OUT".equals(dir) || "INOUT".equals(dir)) {
                    registerOracleOutParameter(cs, idx, varTypes.getOrDefault(pname, "string"));
                }
                idx++;
            }

            // Set IN parameters
            idx = 1;
            for (String pname : sqlParamNames) {
                String dir = varDirections.getOrDefault(pname, "IN");
                if ("IN".equals(dir) || "INOUT".equals(dir)) {
                    String value = getValueFromVars(vars, query.session, pname);
                    setOracleParameter(cs, idx, value, varTypes.getOrDefault(pname, "string"));
                }
                idx++;
            }

            cs.execute();

            // Read OUT parameters
            idx = 1;
            for (String pname : sqlParamNames) {
                String dir = varDirections.getOrDefault(pname, "IN");
                if ("OUT".equals(dir) || "INOUT".equals(dir)) {
                    String outValue = getOracleOutParameter(cs, idx, varTypes.getOrDefault(pname, "string"));
                    updateVars(vars, query.session, pname, outValue);
                }
                idx++;
            }

            result.put("vars", vars);
            if (debugMode) result.put("SQL", sql);

        } catch (SQLException e) {
            result.put("ERROR", "Oracle SQL Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static DatabaseConfig findOracleConfig(String dbName) {
        DatabaseConfig cfg = ServerConstant.config.getDatabaseConfig(dbName);
        if (cfg != null && "oci8".equals(cfg.getType())) return cfg;

        cfg = ServerConstant.config.getDatabaseConfig("oracle_test");
        if (cfg != null && "oci8".equals(cfg.getType())) return cfg;

        for (DatabaseConfig c : ServerConstant.config.DATABASES.values()) {
            if ("oci8".equals(c.getType())) return c;
        }

        return null;
    }

    // ======================== POSTGRESQL ACTION ========================
    private static void executePostgresAction(HttpExchange query, JSONObject result, String actionName,
                                              JSONObject vars, Map<String, Object> session,
                                              boolean debugMode, Map<String, String> varDirections) {
        System.out.println("=== executePostgresAction: " + actionName + " ===");

        if (!procedureList.containsKey(actionName)) {
            result.put("ERROR", "Procedure not found: " + actionName);
            return;
        }

        HashMap<String, Object> param = (HashMap<String, Object>) procedureList.get(actionName);
        Map<String, String> varTypes = (Map<String, String>) param.get("varTypes");
        DatabaseConfig dbConfig = (DatabaseConfig) param.get("dbConfig");

        if (dbConfig == null) {
            dbConfig = ServerConstant.config.getDatabaseConfig("default");
        }
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

            // Register OUT parameters
            int idx = 1;
            for (String vname : varsArr) {
                String dir = varDirections != null ? varDirections.getOrDefault(vname, "IN") : "IN";
                if ("OUT".equals(dir) || "INOUT".equals(dir)) {
                    registerPostgresOutParameter(cs, idx, varTypes.getOrDefault(vname, "string"));
                }
                idx++;
            }

            // Set IN parameters
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

            // Read OUT parameters
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

            if (debugMode && param.containsKey("SQL")) {
                result.put("SQL", ((String) param.get("SQL")).split("\n"));
            }

        } catch (SQLException e) {
            result.put("ERROR", "SQL Error: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            result.put("ERROR", "Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String extractSchema(String fullActionName) {
        if (fullActionName.contains(".")) {
            return fullActionName.substring(0, fullActionName.lastIndexOf('.'));
        }
        return "public";
    }

    private static String buildJdbcUrlWithSchema(DatabaseConfig dbConfig, String schema) {
        String jdbcUrl = dbConfig.getJdbcUrl();
        if (!jdbcUrl.contains("currentSchema")) {
            jdbcUrl += (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        }
        return jdbcUrl;
    }

    private static void setSearchPath(Connection conn, String schema) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SET search_path TO " + schema + ", public");
            System.out.println("Search path set to: " + schema + ", public");
        } catch (SQLException e) {
            System.err.println("Error setting search path: " + e.getMessage());
        }
    }

    // ======================== ОБЩИЕ УТИЛИТЫ ДЛЯ ПАРАМЕТРОВ ========================
    private static String getValueFromVars(JSONObject vars, Map<String, Object> session, String name) {
        if (!vars.has(name)) return "";

        Object val = vars.get(name);
        if (val instanceof JSONObject) {
            JSONObject obj = (JSONObject) val;
            if ("session".equals(obj.optString("srctype"))) {
                Object sessionVal = session.get(name);
                return sessionVal != null ? String.valueOf(sessionVal) : obj.optString("defaultVal", "");
            }
            return obj.optString("value", obj.optString("defaultVal", ""));
        }
        return val.toString();
    }

    private static void updateVars(JSONObject vars, Map<String, Object> session, String name, String value) {
        if (vars.has(name) && vars.get(name) instanceof JSONObject) {
            JSONObject obj = vars.getJSONObject(name);
            if ("session".equals(obj.optString("srctype"))) {
                session.put(name, value);
            } else {
                obj.put("value", value);
            }
        } else {
            JSONObject wrapper = new JSONObject();
            wrapper.put("value", value);
            wrapper.put("src", name);
            wrapper.put("srctype", "var");
            vars.put(name, wrapper);
        }
    }

    // ======================== ORACLE ПАРАМЕТРЫ ========================
    private static void setOracleParameter(CallableStatement cs, int idx, String value, String type) throws SQLException {
        if (value == null || value.isEmpty()) {
            cs.setNull(idx, getOracleSqlType(type));
            return;
        }
        switch (type.toLowerCase()) {
            case "integer": case "int": cs.setInt(idx, Integer.parseInt(value)); break;
            case "bigint": case "long": cs.setLong(idx, Long.parseLong(value)); break;
            case "decimal": case "numeric": cs.setBigDecimal(idx, new java.math.BigDecimal(value)); break;
            case "boolean": case "bool": cs.setBoolean(idx, Boolean.parseBoolean(value)); break;
            case "date": cs.setDate(idx, java.sql.Date.valueOf(value)); break;
            case "timestamp": cs.setTimestamp(idx, Timestamp.valueOf(value.replace("T", " "))); break;
            default: cs.setString(idx, value);
        }
    }

    private static void registerOracleOutParameter(CallableStatement cs, int idx, String type) throws SQLException {
        cs.registerOutParameter(idx, getOracleSqlType(type));
    }

    private static String getOracleOutParameter(CallableStatement cs, int idx, String type) throws SQLException {
        switch (type.toLowerCase()) {
            case "integer": case "int": return cs.wasNull() ? "" : String.valueOf(cs.getInt(idx));
            case "bigint": case "long": return cs.wasNull() ? "" : String.valueOf(cs.getLong(idx));
            case "decimal": case "numeric":
                BigDecimal bd = cs.getBigDecimal(idx);
                return bd == null ? "" : bd.toString();
            case "boolean": case "bool": return cs.wasNull() ? "" : String.valueOf(cs.getBoolean(idx));
            case "date": Date d = cs.getDate(idx); return d == null ? "" : d.toString();
            case "timestamp": Timestamp ts = cs.getTimestamp(idx); return ts == null ? "" : ts.toString();
            default: String s = cs.getString(idx); return s == null ? "" : s;
        }
    }

    private static int getOracleSqlType(String type) {
        switch (type.toLowerCase()) {
            case "integer": case "int": return Types.INTEGER;
            case "bigint": case "long": return Types.BIGINT;
            case "decimal": case "numeric": return Types.NUMERIC;
            case "boolean": case "bool": return Types.BOOLEAN;
            case "date": return Types.DATE;
            case "timestamp": return Types.TIMESTAMP;
            default: return Types.VARCHAR;
        }
    }

    // ======================== POSTGRESQL ПАРАМЕТРЫ ========================
    private static void setPostgresParameter(CallableStatement cs, int idx, String value, String type, Connection conn) throws SQLException {
        if (value == null || value.isEmpty()) {
            cs.setNull(idx, Types.VARCHAR);
            return;
        }
        switch (type.toLowerCase()) {
            case "integer": case "int": cs.setInt(idx, Integer.parseInt(value)); break;
            case "bigint": case "long": cs.setLong(idx, Long.parseLong(value)); break;
            case "decimal": case "numeric": cs.setBigDecimal(idx, new java.math.BigDecimal(value)); break;
            case "boolean": case "bool": cs.setBoolean(idx, Boolean.parseBoolean(value)); break;
            case "date": cs.setDate(idx, java.sql.Date.valueOf(value)); break;
            case "timestamp": cs.setTimestamp(idx, Timestamp.valueOf(value.replace("T", " "))); break;
            case "json": case "jsonb": cs.setObject(idx, value, Types.OTHER); break;
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
            default: cs.setString(idx, value);
        }
    }

    private static void registerPostgresOutParameter(CallableStatement cs, int idx, String type) throws SQLException {
        switch (type.toLowerCase()) {
            case "integer": case "int": cs.registerOutParameter(idx, Types.INTEGER); break;
            case "bigint": case "long": cs.registerOutParameter(idx, Types.BIGINT); break;
            case "decimal": case "numeric": cs.registerOutParameter(idx, Types.NUMERIC); break;
            case "boolean": case "bool": cs.registerOutParameter(idx, Types.BOOLEAN); break;
            case "date": cs.registerOutParameter(idx, Types.DATE); break;
            case "timestamp": cs.registerOutParameter(idx, Types.TIMESTAMP); break;
            case "json": case "jsonb": cs.registerOutParameter(idx, Types.OTHER); break;
            case "array": cs.registerOutParameter(idx, Types.ARRAY); break;
            default: cs.registerOutParameter(idx, Types.VARCHAR);
        }
    }

    private static String getPostgresOutParameter(CallableStatement cs, int idx, String type) throws SQLException {
        switch (type.toLowerCase()) {
            case "integer": case "int": return cs.wasNull() ? "" : String.valueOf(cs.getInt(idx));
            case "bigint": case "long": return cs.wasNull() ? "" : String.valueOf(cs.getLong(idx));
            case "decimal": case "numeric":
                BigDecimal bd = cs.getBigDecimal(idx);
                return bd == null ? "" : bd.toString();
            case "boolean": case "bool": return cs.wasNull() ? "" : String.valueOf(cs.getBoolean(idx));
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

    private static class ActionConfig {
        String name;
        String dbName;
        String queryType;
        String schema;
        String dbType;
        String docPath;
        String rootPath;
        boolean isOracle;
        DatabaseConfig dbConfig;
    }

    private static class ActionVar {
        String name;
        String src;
        String srctype;
        String len;
        String defaultVal;
        String type;
        String direction;
    }

    private static class ParsedSql {
        final String processedSql;
        final List<String> paramNames;
        ParsedSql(String sql, List<String> names) {
            this.processedSql = sql;
            this.paramNames = names;
        }
    }

    private static class RequestParams {
        String queryType;
        String actionName;
        String pgSchema;
        String dbName;
        String dbType;
        boolean debugMode;
    }
}