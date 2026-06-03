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

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static ru.miacomsoft.EasyWebServer.PostgreQuery.*;

@SuppressWarnings("unchecked")
public class cmpAction extends Base {

    // Кэш для хранения информации о существовании функций в БД
    private static Map<String, Boolean> functionExistsCache = new HashMap<>();

    // Кэш для хранения информации о существовании схем в БД
    private static Map<String, Boolean> schemaExistsCache = new HashMap<>();

    // Кэш для хранения информации о существовании баз данных
    private static Map<String, Boolean> databaseExistsCache = new HashMap<>();

    // Кэш для хэшей содержимого функций (для автоматического обновления)
    private static final Map<String, String> functionContentHashCache = new ConcurrentHashMap<>();

    private static final Map<String, String> oracleQueryCache = new ConcurrentHashMap<>();

    // Временные метки для кэша БД
    private static Map<String, Long> databaseCheckTimestamp = new HashMap<>();

    // Время жизни кэша (60 секунд)
    private static final long CACHE_TTL = 60000;

    // Добавляем поле для хранения режима отладки
    private boolean debugMode = false;

    // Храним текущий хэш содержимого
    private String currentContentHash;
    private String currentFunctionName;

    // Конструктор с тремя параметрами
    public cmpAction(Document doc, Element element, String tag) {
        super(doc, element, tag);
        if (doc != null && doc.hasAttr("debug_mode")) {
            debugMode = Boolean.parseBoolean(doc.attr("debug_mode"));
        }
        initialize(doc, element);
    }

    // Конструктор с двумя параметрами
    public cmpAction(Document doc, Element element) {
        super(doc, element, "teaxtarea");
        if (doc != null && doc.hasAttr("debug_mode")) {
            debugMode = Boolean.parseBoolean(doc.attr("debug_mode"));
        }
        initialize(doc, element);
    }

    private boolean checkDatabaseExists(String dbName, DatabaseConfig dbConfig) {
        String cacheKey = "db:" + dbName;
        if (databaseExistsCache.containsKey(cacheKey)) {
            Long timestamp = databaseCheckTimestamp.get(cacheKey);
            if (timestamp != null && (System.currentTimeMillis() - timestamp) < CACHE_TTL) {
                return databaseExistsCache.get(cacheKey);
            }
        }

        Connection conn = null;
        try {
            // Подключаемся к системной БД postgres
            String adminUrl = "jdbc:postgresql://" + dbConfig.getHost() + ":" + dbConfig.getPort() + "/postgres";
            Class.forName("org.postgresql.Driver");
            Properties props = new Properties();
            props.setProperty("user", dbConfig.getUsername());
            props.setProperty("password", dbConfig.getPassword());
            props.setProperty("connectTimeout", "10");
            props.setProperty("socketTimeout", "30");

            conn = DriverManager.getConnection(adminUrl, props);

            String sql = "SELECT 1 FROM pg_database WHERE datname = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, dbName);
                try (ResultSet rs = pstmt.executeQuery()) {
                    boolean exists = rs.next();
                    databaseExistsCache.put(cacheKey, exists);
                    databaseCheckTimestamp.put(cacheKey, System.currentTimeMillis());
                    return exists;
                }
            }
        } catch (Exception e) {
            System.err.println("Error checking database existence: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) {}
        }

        databaseExistsCache.put(cacheKey, false);
        databaseCheckTimestamp.put(cacheKey, System.currentTimeMillis());
        return false;
    }

    private boolean createDatabase(String dbName, DatabaseConfig dbConfig) {
        Connection conn = null;
        Statement stmt = null;
        try {
            String adminUrl = "jdbc:postgresql://" + dbConfig.getHost() + ":" + dbConfig.getPort() + "/postgres";
            Class.forName("org.postgresql.Driver");
            Properties props = new Properties();
            props.setProperty("user", dbConfig.getUsername());
            props.setProperty("password", dbConfig.getPassword());
            props.setProperty("connectTimeout", "10");
            props.setProperty("socketTimeout", "30");

            conn = DriverManager.getConnection(adminUrl, props);
            conn.setAutoCommit(true);

            String owner = dbConfig.getUsername();
            String escapedDbName = dbName.replace("\"", "\"\"");
            String escapedOwner = owner.replace("\"", "\"\"");

            String sql = String.format(
                    "CREATE DATABASE \"%s\" WITH OWNER = \"%s\" ENCODING = 'UTF8' LC_COLLATE = 'C' LC_CTYPE = 'C' TEMPLATE template0",
                    escapedDbName, escapedOwner);

            System.out.println("Creating database with SQL: " + sql);
            stmt = conn.createStatement();
            stmt.executeUpdate(sql);

            String cacheKey = "db:" + dbName;
            databaseExistsCache.put(cacheKey, true);
            databaseCheckTimestamp.put(cacheKey, System.currentTimeMillis());

            System.out.println("Database created successfully: " + dbName);
            return true;
        } catch (Exception e) {
            System.err.println("Error creating database: " + e.getMessage());
            e.printStackTrace();
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                String cacheKey = "db:" + dbName;
                databaseExistsCache.put(cacheKey, true);
                databaseCheckTimestamp.put(cacheKey, System.currentTimeMillis());
                System.out.println("Database already exists: " + dbName);
                return true;
            }
            return false;
        } finally {
            if (stmt != null) try { stmt.close(); } catch (SQLException e) {}
            if (conn != null) try { conn.close(); } catch (SQLException e) {}
        }
    }

    private DatabaseConfig createAdminDatabaseConfig(DatabaseConfig sourceConfig, String adminDbName) {
        DatabaseConfig adminConfig = new DatabaseConfig();
        adminConfig.setType(sourceConfig.getType());
        adminConfig.setDriver(sourceConfig.getDriver());
        adminConfig.setHost(sourceConfig.getHost());
        adminConfig.setPort(sourceConfig.getPort());
        adminConfig.setDatabase(adminDbName);
        adminConfig.setUsername(sourceConfig.getUsername());
        adminConfig.setPassword(sourceConfig.getPassword());
        adminConfig.setSchema("public");
        return adminConfig;
    }

    private boolean checkSchemaExists(String schemaName, String dbName, DatabaseConfig dbConfig) {
        String cacheKey = dbName + ":schema:" + schemaName;
        if (schemaExistsCache.containsKey(cacheKey)) {
            return schemaExistsCache.get(cacheKey);
        }

        Connection conn = null;
        try {
            Class.forName("org.postgresql.Driver");
            Properties props = new Properties();
            props.setProperty("user", dbConfig.getUsername());
            props.setProperty("password", dbConfig.getPassword());
            props.setProperty("connectTimeout", "10");
            props.setProperty("socketTimeout", "30");

            conn = DriverManager.getConnection(dbConfig.getJdbcUrl(), props);

            String sql = "SELECT EXISTS(SELECT 1 FROM information_schema.schemata WHERE schema_name = ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, schemaName);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        boolean exists = rs.getBoolean(1);
                        schemaExistsCache.put(cacheKey, exists);
                        System.out.println("Schema " + schemaName + " exists: " + exists);
                        return exists;
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Error checking schema existence: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Unexpected error checking schema: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) {}
        }

        schemaExistsCache.put(cacheKey, false);
        return false;
    }

    private boolean createSchema(String schemaName, String dbName, DatabaseConfig dbConfig) {
        Connection conn = null;
        try {
            System.out.println("=== createSchema called ===");
            System.out.println("schemaName: " + schemaName);
            System.out.println("dbName: " + dbName);
            System.out.println("dbConfig host: " + dbConfig.getHost());
            System.out.println("dbConfig port: " + dbConfig.getPort());
            System.out.println("dbConfig database: " + dbConfig.getDatabase());

            // ПРЯМОЕ ПОДКЛЮЧЕНИЕ К ЦЕЛЕВОЙ БД
            Class.forName("org.postgresql.Driver");
            Properties props = new Properties();
            props.setProperty("user", dbConfig.getUsername());
            props.setProperty("password", dbConfig.getPassword());
            props.setProperty("connectTimeout", "10");
            props.setProperty("socketTimeout", "30");

            conn = DriverManager.getConnection(dbConfig.getJdbcUrl(), props);
            conn.setAutoCommit(false);

            System.out.println("Connection established to: " + dbConfig.getDatabase());

            String owner = dbConfig.getUsername();
            String sql = String.format("CREATE SCHEMA IF NOT EXISTS \"%s\" AUTHORIZATION \"%s\"",
                    schemaName.replace("\"", "\"\""),
                    owner.replace("\"", "\"\""));

            System.out.println("Executing SQL: " + sql);

            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(sql);
                conn.commit();

                String cacheKey = dbName + ":schema:" + schemaName;
                schemaExistsCache.put(cacheKey, true);

                System.out.println("Schema created successfully: " + schemaName);
                return true;
            }
        } catch (SQLException e) {
            System.err.println("Error creating schema: " + e.getMessage());
            e.printStackTrace();
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) {}
            return false;
        } catch (Exception e) {
            System.err.println("Unexpected error creating schema: " + e.getMessage());
            e.printStackTrace();
            return false;
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) {}
        }
    }

    private boolean checkFunctionExists(String fullFunctionName, String schemaName, String dbName, DatabaseConfig dbConfig) {
        String cacheKey = dbName + ":func:" + fullFunctionName;
        if (functionExistsCache.containsKey(cacheKey)) {
            return functionExistsCache.get(cacheKey);
        }

        Connection conn = null;
        try {
            conn = getConnectionFromConfig(dbConfig);
            if (conn == null) {
                System.err.println("Cannot connect to database for function check: " + dbName);
                return false;
            }

            String functionName = fullFunctionName;
            if (fullFunctionName.contains(".")) {
                functionName = fullFunctionName.substring(fullFunctionName.lastIndexOf('.') + 1);
            }

            String sql = "SELECT EXISTS(SELECT 1 FROM pg_proc p " +
                    "JOIN pg_namespace n ON p.pronamespace = n.oid " +
                    "WHERE n.nspname = ? AND p.proname = ?)";

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, schemaName);
                pstmt.setString(2, functionName);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        boolean exists = rs.getBoolean(1);
                        functionExistsCache.put(cacheKey, exists);
                        return exists;
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Error checking function existence: " + e.getMessage());
        } finally {
            releaseConnection(conn);
        }

        functionExistsCache.put(cacheKey, false);
        return false;
    }

    private boolean needsRecreation(String functionName, String currentHash, DatabaseConfig dbConfig, String schema) {
        if (debugMode) {
            System.out.println("Debug mode enabled, forcing recreation of: " + functionName);
            return true;
        }

        String cacheKey = functionName;
        String cachedHash = functionContentHashCache.get(cacheKey);

        if (cachedHash == null) {
            System.out.println("No cached hash for: " + functionName + ", will create");
            return true;
        }

        if (!cachedHash.equals(currentHash)) {
            System.out.println("Content hash changed for: " + functionName);
            System.out.println("  Old: " + cachedHash);
            System.out.println("  New: " + currentHash);
            return true;
        }

        if (!checkFunctionExistsInDB(functionName, schema, dbConfig)) {
            System.out.println("Function exists in cache but not in DB: " + functionName + ", will recreate");
            return true;
        }

        System.out.println("Function " + functionName + " is up to date, skipping recreation");
        return false;
    }

    private boolean checkFunctionExistsInDB(String functionName, String schema, DatabaseConfig dbConfig) {
        Connection conn = null;
        try {
            conn = getConnectionFromConfig(dbConfig);
            if (conn == null) return false;

            String cleanName = functionName;
            if (functionName.contains(".")) {
                cleanName = functionName.substring(functionName.lastIndexOf('.') + 1);
            }

            String sql = "SELECT EXISTS(SELECT 1 FROM pg_proc p " +
                    "JOIN pg_namespace n ON p.pronamespace = n.oid " +
                    "WHERE n.nspname = ? AND p.proname = ?)";

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, schema);
                pstmt.setString(2, cleanName);
                try (ResultSet rs = pstmt.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error checking function existence: " + e.getMessage());
            return false;
        } finally {
            releaseConnection(conn);
        }
    }

    private void updateFunctionHashCache(String functionName, String hash) {
        functionContentHashCache.put(functionName, hash);
        System.out.println("Updated hash cache for: " + functionName + " -> " + hash);
    }

    private Connection getConnectionFromConfig(DatabaseConfig dbConfig) {
        try {
            Class.forName(dbConfig.getDriver());
            Properties props = new Properties();
            props.setProperty("user", dbConfig.getUsername());
            props.setProperty("password", dbConfig.getPassword());
            props.setProperty("connectTimeout", "10");
            props.setProperty("socketTimeout", "30");

            Connection conn = DriverManager.getConnection(dbConfig.getJdbcUrl(), props);
            conn.setAutoCommit(false);
            return conn;
        } catch (Exception e) {
            System.err.println("Error connecting to database: " + e.getMessage());
            return null;
        }
    }

    private void releaseConnection(Connection conn) {
        if (conn != null) {
            try { conn.close(); } catch (SQLException e) {}
        }
    }

    private void rollbackQuietly(Connection conn) {
        if (conn != null) {
            try { conn.rollback(); } catch (SQLException e) {}
        }
    }

    private Connection getConnectionWithSchema(DatabaseConfig dbConfig, String schemaName) {
        try {
            Class.forName(dbConfig.getDriver());
            Properties props = new Properties();
            props.setProperty("user", dbConfig.getUsername());
            props.setProperty("password", dbConfig.getPassword());
            props.setProperty("connectTimeout", "10");
            props.setProperty("socketTimeout", "30");
            props.setProperty("currentSchema", schemaName);

            Connection conn = DriverManager.getConnection(dbConfig.getJdbcUrl(), props);
            conn.setAutoCommit(false);

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET search_path TO " + schemaName + ", public");
            }

            return conn;
        } catch (Exception e) {
            System.err.println("Error connecting to database with schema: " + e.getMessage());
            return null;
        }
    }

    private boolean checkSchemaAccessible(String schemaName, Connection conn) {
        try {
            String sql = "SELECT has_schema_privilege(current_user, ?, 'USAGE')";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, schemaName);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getBoolean(1);
                    }
                }
            }
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET search_path TO " + schemaName);
                return true;
            }
        } catch (SQLException e) {
            System.err.println("Schema " + schemaName + " is not accessible: " + e.getMessage());
            return false;
        }
    }

    private boolean hasVarElements(Element element) {
        for (int i = 0; i < element.childrenSize(); i++) {
            Element child = element.child(i);
            if (child.tag().toString().toLowerCase().indexOf("var") != -1 ||
                    child.tag().toString().toLowerCase().indexOf("cmpactionvar") != -1) {
                return true;
            }
        }
        return false;
    }

    private void initialize(Document doc, Element element) {
        Attributes attrs = element.attributes();
        Attributes attrsDst = this.attributes();
        attrsDst.add("schema", "Action");
        String name = attrs.get("name");
        this.attr("name", name);
        attrsDst.add("name", name);
        this.initCmpType(element);

        String dbName = RemoveArrKeyRtrn(attrs, "db", "default");
        String query_type = "sql";
        if (element.attributes().hasKey("query_type")) {
            query_type = element.attributes().get("query_type");
        }
        attrsDst.add("query_type", query_type);

        DatabaseConfig dbConfig = null;
        String pgSchema = "public";
        String dbType = "jdbc";
        boolean isOracle = false;

        if (query_type.equals("sql")) {
            dbConfig = ServerConstant.config.getDatabaseConfig(dbName.equals("db") ? null : dbName.toLowerCase());

            if (attrs.hasKey("schema")) {
                pgSchema = RemoveArrKeyRtrn(attrs, "schema", "public");
            } else {
                if (ServerConstant.config.DATABASES.containsKey(dbName.toLowerCase())) {
                    pgSchema = dbConfig.getSchema();
                }
            }

            if (dbConfig != null) {
                dbType = dbConfig.getType().toLowerCase();
                isOracle = dbConfig.getType().equals("oci8");
            }

            if (!isOracle && dbConfig != null) {
                String targetDbName = dbConfig.getDatabase();

                boolean dbExists = checkDatabaseExists(targetDbName, dbConfig);
                if (!dbExists && !debugMode) {
                    System.out.println("=== Database " + targetDbName + " does not exist, creating... ===");
                    if (createDatabase(targetDbName, dbConfig)) {
                        System.out.println("=== Database " + targetDbName + " created successfully ===");
                        try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    } else {
                        String errorMsg = "Failed to create database: " + targetDbName;
                        System.err.println(errorMsg);
                        this.attr("error", errorMsg);
                    }
                } else if (dbExists) {
                    System.out.println("=== Database " + targetDbName + " already exists ===");
                }

                boolean schemaExists = checkSchemaExists(pgSchema, dbName, dbConfig);
                if (!schemaExists && !debugMode) {
                    System.out.println("=== Schema " + pgSchema + " does not exist, creating... ===");
                    if (createSchema(pgSchema, dbName, dbConfig)) {
                        System.out.println("=== Schema " + pgSchema + " created successfully ===");
                        try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    } else {
                        String errorMsg = "Failed to create schema: " + pgSchema;
                        System.err.println(errorMsg);
                        this.attr("error", errorMsg);
                    }
                } else if (schemaExists) {
                    System.out.println("=== Schema " + pgSchema + " already exists ===");
                }
            }
        } else {
            System.out.println("Java mode detected, skipping database initialization");
        }

        attrsDst.add("pg_schema", pgSchema);
        attrsDst.add("db_type", dbType);
        attrsDst.add("query_type", query_type);
        attrsDst.add("db", dbName);

        System.out.println("ServerConstant.config.DATABASES: " + ServerConstant.config.DATABASES);
        System.out.println("dbName: " + dbName);
        System.out.println("pgSchema: " + pgSchema);
        System.out.println("dbType: " + dbType);
        System.out.println("query_type: " + query_type);
        System.out.println("---------------------------------");

        String docPath = doc != null ? doc.attr("doc_path") : "";
        String rootPath = doc != null ? doc.attr("rootPath") : "";
        String relativePath = "";
        if (docPath.length() > rootPath.length() && docPath.length() > 5) {
            relativePath = docPath.substring(rootPath.length(), docPath.length() - 5);
        }
        relativePath = relativePath.replaceAll("[/\\\\]", "_");
        relativePath = relativePath.replaceAll("[^a-zA-Z0-9_]", "");

        String pathHash = getMd5Hash(relativePath);
        if (pathHash.length() > 8) {
            pathHash = pathHash.substring(0, 8);
        }

        if (pathHash.length() > 0 && Character.isDigit(pathHash.charAt(0))) {
            pathHash = "f" + pathHash;
        }

        String fileName = "";
        if (relativePath.lastIndexOf('/') > 0) {
            fileName = relativePath.substring(relativePath.lastIndexOf('/') + 1);
            if (fileName.lastIndexOf('.') > 0) {
                fileName = fileName.substring(0, fileName.lastIndexOf('.'));
            }
        }

        String contentHash = getShortHash(element.hasText() ? element.text().trim() : "");
        currentContentHash = contentHash;

        String functionName;
        if (isOracle) {
            functionName = element.attr("name");
            System.out.println("Oracle mode: using simple name (no function creation): " + functionName);
        } else {
            String functionNameBase = (pathHash + "_" + contentHash + "_" + element.attr("name")).toLowerCase();
            if (functionNameBase.length() > 60) {
                functionNameBase = functionNameBase.substring(0, 60);
            }
            if (functionNameBase.length() > 0 && Character.isDigit(functionNameBase.charAt(0))) {
                functionNameBase = "f_" + functionNameBase;
            }
            functionName = functionNameBase;
        }
        currentFunctionName = functionName;

        this.attr("style", "display:none");
        this.attr("action_name", functionName);
        this.attr("name", element.attr("name"));

        StringBuffer jsonVar = new StringBuffer();
        ArrayList<String> jarResourse = new ArrayList<String>();
        ArrayList<String> importPacket = new ArrayList<String>();

        List<String> varsArr = new ArrayList<>();
        Map<String, String> varTypes = new HashMap<>();
        Map<String, String> varDirections = new HashMap<>();

        for (int numChild = 0; numChild < element.childrenSize(); numChild++) {
            Element itemElement = element.child(numChild);
            Attributes attrsItem = itemElement.attributes();

            String tagName = itemElement.tag().toString().toLowerCase();

            if (tagName.indexOf("import") != -1) {
                if (attrsItem.hasKey("path")) {
                    jarResourse.add(attrsItem.get("path"));
                }
                if (attrsItem.hasKey("packet")) {
                    importPacket.add(attrsItem.get("packet"));
                }
            } else if (tagName.indexOf("var") != -1 || tagName.indexOf("cmpactionvar") != -1) {
                String nameItem = attrsItem.get("name");
                String src = RemoveArrKeyRtrn(attrsItem, "src", nameItem);
                String srctype = RemoveArrKeyRtrn(attrsItem, "srctype", "");
                String len = RemoveArrKeyRtrn(attrsItem, "len", "");
                String defaultVal = RemoveArrKeyRtrn(attrsItem, "default", "");
                String type = RemoveArrKeyRtrn(attrsItem, "type", "string");

                String putAttr = attrsItem.hasKey("put") ? attrsItem.get("put") : null;
                String getAttr = attrsItem.hasKey("get") ? attrsItem.get("get") : null;

                String direction = "IN";
                if (putAttr != null && getAttr != null) {
                    direction = "INOUT";
                } else if (putAttr != null) {
                    direction = "OUT";
                }

                varsArr.add(nameItem);
                varTypes.put(nameItem, type.isEmpty() ? "string" : type.toLowerCase());
                varDirections.put(nameItem, direction);

                jsonVar.append("'" + nameItem + "':{");
                jsonVar.append("'src':'" + src + "',");
                jsonVar.append("'srctype':'" + srctype + "',");
                jsonVar.append("'direction':'" + direction + "'");
                if (defaultVal.length() > 0) {
                    jsonVar.append(",'defaultVal':'" + defaultVal.replaceAll("'", "\\\\'") + "'");
                }
                if (len.length() > 0) {
                    jsonVar.append(",'len':'" + len + "'");
                }
                jsonVar.append("},");
            }
        }

        String jsonVarStr = jsonVar.toString();
        if (jsonVarStr.length() > 0) {
            jsonVarStr = jsonVarStr.substring(0, jsonVarStr.length() - 1);
        }
        jsonVarStr = "{" + jsonVarStr + "}";

        this.attr("vars", jsonVarStr);
        attrsDst.add("query_type", query_type);
        attrsDst.add("db", dbName);

        if (element.hasText()) {
            if (query_type.equals("java")) {
                System.out.println("Java mode: compiling function " + functionName);
                JSONObject infoCompile = new JSONObject();
                if (!ServerResourceHandler.javaStrExecut.compile(functionName, importPacket, jarResourse, element.text().trim(), infoCompile)) {
                    this.removeAttr("style");
                    this.html(ru.miacomsoft.EasyWebServer.JavaStrExecut.parseErrorCompile(infoCompile));
                    return;
                }
                System.out.println("Java function compiled successfully: " + functionName);

                String fullJavaFunctionName = ServerConstant.config.APP_NAME + "_" + functionName;
                System.out.println("Java function exists check: " + ServerResourceHandler.javaStrExecut.existJavaFunction(fullJavaFunctionName));

            } else if (query_type.equals("sql")) {
                if (isOracle) {
                    System.out.println("Oracle detected: saving SQL for direct execution, no function creation");

                    HashMap<String, Object> param = new HashMap<String, Object>();
                    param.put("vars", varsArr);
                    param.put("varTypes", varTypes);
                    param.put("varDirections", varDirections);
                    param.put("dbType", dbType);
                    param.put("dbName", dbName);
                    param.put("contentHash", contentHash);

                    ParsedSql parsed = parseNamedParameters(element.text().trim());
                    param.put("SQL_RAW", element.text().trim());
                    param.put("SQL", parsed.processedSql);
                    param.put("SQL_PARAMS", parsed.paramNames);

                    procedureList.put(functionName, param);
                    System.out.println("Oracle action saved for direct execution: " + functionName);

                } else {
                    String fullFunctionName = pgSchema + "." + functionName;

                    if (needsRecreation(fullFunctionName, contentHash, dbConfig, pgSchema)) {
                        System.out.println("Creating/updating procedure: " + fullFunctionName);
                        createOrReplaceSQLFunctionPG(fullFunctionName, pgSchema, element,
                                docPath + " (" + element.attr("name") + ")",
                                debugMode, dbConfig, contentHash, varsArr, varTypes, varDirections);
                        updateFunctionHashCache(fullFunctionName, contentHash);
                    } else {
                        System.out.println("Procedure " + fullFunctionName + " is up to date, skipping creation");
                        loadExistingProcedureToCache(fullFunctionName, pgSchema, element, dbConfig);
                    }
                }
            }
        }

        this.text("");

        String savedQueryType = this.attr("query_type");
        String savedDbType = this.attr("db_type");
        String savedPgSchema = this.attr("pg_schema");
        String savedDb = this.attr("db");
        String savedActionName = this.attr("action_name");
        String savedName = this.attr("name");
        String savedVars = this.attr("vars");
        String savedStyle = this.attr("style");

        for (Attribute attr : element.attributes().asList()) {
            if ("error".equals(attr.getKey())) continue;
            this.removeAttr(attr.getKey());
        }

        if (savedQueryType != null) this.attr("query_type", savedQueryType);
        if (savedDbType != null) this.attr("db_type", savedDbType);
        if (savedPgSchema != null) this.attr("pg_schema", savedPgSchema);
        if (savedDb != null) this.attr("db", savedDb);
        if (savedActionName != null) this.attr("action_name", savedActionName);
        if (savedName != null) this.attr("name", savedName);
        if (savedVars != null) this.attr("vars", savedVars);
        if (savedStyle != null) this.attr("style", savedStyle);

        if (doc != null) {
            Elements head = doc.getElementsByTag("head");
            if (head != null && head.size() > 0) {
                Elements existingScripts = head.select("script[src*='cmpAction_js']");
                if (existingScripts.isEmpty()) {
                    String jsPath = "{component}/cmpAction_js";
                    head.append("<script cmp=\"action-lib\" src=\"" + jsPath + "\" type=\"text/javascript\"></script>");
                    System.out.println("cmpAction: JavaScript library auto-included for action: " + name);
                }
            }
        }
    }

    private boolean checkFunctionExistsInDB(String fullFunctionName, String schema) {
        Connection conn = null;
        try {
            conn = getConnect(ServerConstant.config.DATABASE_USER_NAME, ServerConstant.config.DATABASE_USER_PASS);
            if (conn == null) return false;

            Statement stmt = conn.createStatement();
            String functionName = fullFunctionName;
            if (fullFunctionName.contains(".")) {
                functionName = fullFunctionName.substring(fullFunctionName.lastIndexOf('.') + 1);
            }

            String sql = "SELECT EXISTS(SELECT 1 FROM pg_proc p " +
                    "JOIN pg_namespace n ON p.pronamespace = n.oid " +
                    "WHERE n.nspname = '" + schema + "' AND p.proname = '" + functionName + "') AS function_exists";
            System.out.println("Checking function existence: " + sql);
            ResultSet rs = stmt.executeQuery(sql);

            if (rs.next()) {
                return rs.getBoolean(1);
            }
            return false;
        } catch (SQLException e) {
            System.err.println("Error checking function existence: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (SQLException e) {}
            }
        }
    }

    private void createOrReplaceSQLFunctionPG(String functionName, String schema, Element element, String fileName,
                                              boolean debugMode, DatabaseConfig dbConfig, String contentHash,
                                              List<String> varsArr, Map<String, String> varTypes, Map<String, String> varDirections) {
        String cleanFunctionName = functionName;
        if (functionName.contains(".")) {
            cleanFunctionName = functionName.substring(functionName.lastIndexOf('.') + 1);
        }
        cleanFunctionName = cleanFunctionName.replaceAll("[^a-zA-Z0-9_]", "");

        if (cleanFunctionName.length() > 0 && Character.isDigit(cleanFunctionName.charAt(0))) {
            cleanFunctionName = "f_" + cleanFunctionName;
        }

        String fullFunctionName = schema + "." + cleanFunctionName;

        Connection conn = null;

        if (dbConfig != null) {
            try {
                Class.forName("org.postgresql.Driver");
                conn = DriverManager.getConnection(
                        dbConfig.getJdbcUrl(),
                        dbConfig.getUsername(),
                        dbConfig.getPassword()
                );
                System.out.println("Connected to database using config: " + dbConfig.getDatabase());
            } catch (Exception e) {
                System.err.println("Error connecting to database using config: " + e.getMessage());
                conn = null;
            }
        }

        if (conn == null) {
            conn = getConnect(ServerConstant.config.DATABASE_USER_NAME, ServerConstant.config.DATABASE_USER_PASS);
            System.out.println("Connected to database using default credentials");
        }

        if (conn == null) {
            System.err.println("Cannot connect to database for creating procedure");
            return;
        }

        StringBuffer vars = new StringBuffer();
        StringBuffer varsColl = new StringBuffer();
        Attributes attrs = element.attributes();
        HashMap<String, Object> param = new HashMap<String, Object>();
        String language = RemoveArrKeyRtrn(attrs, "language", "plpgsql");
        param.put("language", language);

        String beforeCodeBloc = "";
        String afterCodeBloc = "";

        for (int numChild = 0; numChild < element.childrenSize(); numChild++) {
            Element itemElement = element.child(numChild);
            String tagName = itemElement.tag().toString().toLowerCase();

            if (tagName.equals("before")) {
                beforeCodeBloc = itemElement.text().trim();
                itemElement.text("");
            } else if (tagName.equals("after")) {
                afterCodeBloc = itemElement.text().trim();
                itemElement.text("");
            }
        }

        for (String nameItem : varsArr) {
            String type = varTypes.getOrDefault(nameItem, "string");
            String direction = varDirections.getOrDefault(nameItem, "IN");

            String sqlType = "VARCHAR";
            switch (type.toLowerCase()) {
                case "integer": case "int": sqlType = "INTEGER"; break;
                case "bigint": case "long": sqlType = "BIGINT"; break;
                case "decimal": case "numeric": sqlType = "NUMERIC"; break;
                case "boolean": case "bool": sqlType = "BOOLEAN"; break;
                case "date": sqlType = "DATE"; break;
                case "timestamp": sqlType = "TIMESTAMP"; break;
                case "json": case "jsonb": sqlType = "JSONB"; break;
                case "array": sqlType = "TEXT[]"; break;
                case "string": default: sqlType = "TEXT"; break;
            }

            vars.append(nameItem);
            vars.append(" ");
            vars.append(direction);
            vars.append(" ");
            vars.append(sqlType);
            vars.append(",");

            varsColl.append("?,");
        }

        String varsStr = vars.toString();
        if (varsStr.length() > 0) {
            varsStr = varsStr.substring(0, varsStr.length() - 1);
        }

        String varsCollStr = varsColl.toString();
        if (varsCollStr.length() > 0) {
            varsCollStr = varsCollStr.substring(0, varsCollStr.length() - 1);
        }

        param.put("vars", varsArr);
        param.put("varTypes", varTypes);
        param.put("varDirections", varDirections);

        StringBuffer sb = new StringBuffer();
        sb.append("CREATE OR REPLACE PROCEDURE ");
        sb.append(schema).append(".").append(cleanFunctionName);
        sb.append("(");
        sb.append(varsStr);
        sb.append(")\n");
        sb.append("LANGUAGE ");
        sb.append(language);
        sb.append("\nAS $$\n");

        if (beforeCodeBloc.length() > 0) {
            sb.append(beforeCodeBloc);
            if (!beforeCodeBloc.endsWith(";") && !beforeCodeBloc.endsWith("\n")) {
                sb.append(";\n");
            } else {
                sb.append("\n");
            }
        } else {
            sb.append("BEGIN\n");
        }

        sb.append("-- cmpAction fileName:");
        sb.append(fileName);
        sb.append("\n");
        sb.append("-- contentHash:");
        sb.append(contentHash);
        sb.append("\n");
        sb.append(element.text().trim());

        if (!element.text().trim().endsWith(";") && !element.text().trim().endsWith("\n")) {
            sb.append(";\n");
        } else {
            sb.append("\n");
        }

        if (afterCodeBloc.length() > 0) {
            sb.append(afterCodeBloc);
            if (!afterCodeBloc.endsWith(";") && !afterCodeBloc.endsWith("\n")) {
                sb.append(";\n");
            } else {
                sb.append("\n");
            }
        }

        sb.append("END;\n");
        sb.append("$$\n");

        String createProcedureSQL = sb.toString();
        System.out.println("Creating procedure with SQL:\n" + createProcedureSQL);

        try (Statement stmt = conn.createStatement()) {
            try {
                stmt.execute("DROP PROCEDURE IF EXISTS " + schema + "." + cleanFunctionName + " CASCADE");
                System.out.println("Dropped existing procedure: " + schema + "." + cleanFunctionName);
            } catch (SQLException e) {
                System.out.println("No existing procedure to drop or error: " + e.getMessage());
            }

            stmt.execute(createProcedureSQL);
            System.out.println("Procedure created/replaced successfully: " + fullFunctionName);

            String prepareCall = "CALL " + schema + "." + cleanFunctionName + "(" + varsCollStr + ");";
            param.put("prepareCall", prepareCall);
            param.put("connect", conn);
            param.put("SQL", createProcedureSQL);
            param.put("dbConfig", dbConfig);
            param.put("contentHash", contentHash);

            procedureList.put(fullFunctionName, param);
            procedureList.put(cleanFunctionName, param);

            System.out.println("Procedure " + fullFunctionName + " created successfully and saved to procedureList");

        } catch (SQLException e) {
            System.err.println("Error creating procedure: " + e.getMessage());
            System.err.println("SQL was:\n" + createProcedureSQL);
            e.printStackTrace();
        } finally {
            try {
                if (conn != null && !conn.isClosed()) {
                    conn.close();
                }
            } catch (SQLException e) {}
        }
    }

    private void loadExistingProcedureToCache(String fullFunctionName, String schema, Element element, DatabaseConfig dbConfig) {
        HashMap<String, Object> param = new HashMap<>();
        param.put("SQL", element.text().trim());

        List<String> varsArr = new ArrayList<>();
        Map<String, String> varTypes = new HashMap<>();

        for (int numChild = 0; numChild < element.childrenSize(); numChild++) {
            Element itemElement = element.child(numChild);
            String tagName = itemElement.tag().toString().toLowerCase();
            if (tagName.indexOf("var") != -1 || tagName.indexOf("cmpactionvar") != -1) {
                Attributes attrsItem = itemElement.attributes();
                String nameItem = RemoveArrKeyRtrn(attrsItem, "name", "");
                String type = RemoveArrKeyRtrn(attrsItem, "type", "string");
                varsArr.add(nameItem);
                varTypes.put(nameItem, type);
            }
        }

        param.put("vars", varsArr);
        param.put("varTypes", varTypes);
        param.put("dbConfig", dbConfig);

        StringBuilder varsColl = new StringBuilder();
        for (int i = 0; i < varsArr.size(); i++) {
            if (i > 0) varsColl.append(",");
            varsColl.append("?");
        }

        String functionNameOnly = fullFunctionName.contains(".") ?
                fullFunctionName.substring(fullFunctionName.lastIndexOf('.') + 1) : fullFunctionName;
        String prepareCall = "CALL " + schema + "." + functionNameOnly + "(" + varsColl.toString() + ");";
        param.put("prepareCall", prepareCall);

        procedureList.put(fullFunctionName, param);
        procedureList.put(functionNameOnly, param);

        System.out.println("Loaded existing procedure into cache: " + fullFunctionName);
    }

    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/json";
        Map<String, Object> session = query.session;
        JSONObject queryProperty = query.requestParam;
        JSONObject vars;
        String postBodyStr = new String(query.postCharBody);

        System.out.println("=== cmpAction onPage called ===");
        System.out.println("queryProperty: " + queryProperty.toString());

        System.out.println("=== procedureList keys ===");
        for (String key : procedureList.keySet()) {
            System.out.println("  " + key);
        }
        System.out.println("========================");

        vars = new JSONObject();
        if (postBodyStr != null && !postBodyStr.isEmpty()) {
            try {
                JSONObject requestVars = new JSONObject(postBodyStr);
                Iterator<String> keys = requestVars.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object val = requestVars.get(key);
                    if (val instanceof JSONObject) {
                        vars.put(key, val);
                    } else {
                        JSONObject varObj = new JSONObject();
                        varObj.put("value", val.toString());
                        varObj.put("src", key);
                        varObj.put("srctype", "var");
                        vars.put(key, varObj);
                    }
                }
            } catch (Exception e) {
                System.err.println("Error parsing JSON: " + e.getMessage());
            }
        }

        JSONObject result = new JSONObject();
        result.put("vars", vars);

        String query_type = queryProperty.optString("query_type", "java");
        String action_name = queryProperty.optString("action_name", "");
        String pg_schema = queryProperty.optString("pg_schema", "public");
        String db_name = queryProperty.optString("db", "DB");
        String db_type = queryProperty.optString("db_type", "jdbc");
        boolean isOracle = "oci8".equals(db_type);

        System.out.println("Action name: " + action_name);
        System.out.println("Query type: " + query_type);
        System.out.println("DB type: " + db_type);
        System.out.println("DB name: " + db_name);
        System.out.println("PG Schema: " + pg_schema);

        boolean debugMode = false;
        if (query.session != null && query.session.containsKey("debug_mode")) {
            debugMode = (boolean) query.session.get("debug_mode");
        }

        String javaFunctionName = ServerConstant.config.APP_NAME + "_" + action_name;
        if (query_type.equals("java") && ServerResourceHandler.javaStrExecut.existJavaFunction(javaFunctionName)) {
            executeJavaAction(query, result, javaFunctionName, vars, session);
            return result.toString().getBytes();
        }

        if (query_type.equals("sql")) {
            String foundKey = null;
            Object foundParam = null;

            String fullName = pg_schema + "." + action_name;
            if (procedureList.containsKey(fullName)) {
                foundKey = fullName;
                foundParam = procedureList.get(fullName);
                System.out.println("Found by full name: " + fullName);
            } else if (procedureList.containsKey(action_name)) {
                foundKey = action_name;
                foundParam = procedureList.get(action_name);
                System.out.println("Found by name only: " + action_name);
            } else {
                for (String key : procedureList.keySet()) {
                    if (key.endsWith(action_name)) {
                        foundKey = key;
                        foundParam = procedureList.get(key);
                        System.out.println("Found by suffix match: " + key);
                        break;
                    }
                    if (action_name.endsWith(key)) {
                        foundKey = key;
                        foundParam = procedureList.get(key);
                        System.out.println("Found by prefix match: " + key);
                        break;
                    }
                }
            }

            if (foundParam == null) {
                result.put("ERROR", "Action not found: " + action_name + ". Available keys: " + procedureList.keySet());
                result.put("vars", vars);
                return result.toString().getBytes();
            }

            HashMap<String, Object> param = (HashMap<String, Object>) foundParam;
            String sql = (String) param.get("SQL");
            String savedDbType = (String) param.get("dbType");
            String savedDbName = (String) param.get("dbName");

            if (sql == null || sql.isEmpty()) {
                result.put("ERROR", "SQL is null or empty for action: " + foundKey);
                result.put("vars", vars);
                return result.toString().getBytes();
            }

            System.out.println("Found SQL for action: " + foundKey);
            System.out.println("SQL: " + sql);
            System.out.println("savedDbType: " + savedDbType);
            System.out.println("savedDbName: " + savedDbName);

            boolean isOracleAction = "oci8".equals(savedDbType) || isOracle;
            String connectionDbName = db_name;

            if ("DB".equals(connectionDbName) && savedDbName != null && !"DB".equals(savedDbName)) {
                connectionDbName = savedDbName;
                System.out.println("Using saved dbName: " + connectionDbName);
            }

            if (isOracleAction) {
                executeOracleAction(query, result, foundKey, connectionDbName, vars, debugMode);
            } else {
                Map<String, String> varDirections = (Map<String, String>) param.get("varDirections");
                executePostgresAction(query, result, foundKey, vars, session, debugMode, varDirections);
            }
        } else {
            result.put("ERROR", "Unsupported query type: " + query_type);
        }

        return result.toString().getBytes();
    }

    private static void executeJavaAction(HttpExchange query, JSONObject result, String fullActionName, JSONObject vars, Map<String, Object> session) {
        try {
            JSONObject varFun = new JSONObject();
            Iterator<String> keys = vars.keys();

            while (keys.hasNext()) {
                String key = keys.next();
                Object varValue = vars.get(key);

                if (varValue instanceof JSONObject) {
                    JSONObject varObj = (JSONObject) varValue;
                    String value = varObj.optString("value", "");
                    if (value.isEmpty()) {
                        value = varObj.optString("defaultVal", "");
                    }
                    varFun.put(key, value);
                    System.out.println("Variable " + key + " = " + value + " (from object)");
                } else {
                    varFun.put(key, varValue.toString());
                    System.out.println("Variable " + key + " = " + varValue + " (direct)");
                }
            }

            System.out.println("Calling Java function with vars: " + varFun.toString());

            JSONObject resFun = ServerResourceHandler.javaStrExecut.runFunction(fullActionName, varFun, session, null);

            System.out.println("Java function result: " + resFun.toString());

            if (resFun.has("JAVA_ERROR")) {
                result.put("ERROR", resFun.get("JAVA_ERROR"));
                result.put("vars", vars);
            } else {
                keys = resFun.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object keyvalue = resFun.get(key);

                    if (key.equals("JAVA_ERROR")) {
                        continue;
                    }

                    if (vars.has(key) && vars.get(key) instanceof JSONObject) {
                        vars.getJSONObject(key).put("value", keyvalue.toString());
                    } else {
                        JSONObject newVar = new JSONObject();
                        newVar.put("value", keyvalue.toString());
                        newVar.put("src", key);
                        newVar.put("srctype", "var");
                        vars.put(key, newVar);
                    }
                }
                result.put("vars", vars);
            }
        } catch (Exception e) {
            System.err.println("Error executing Java function: " + e.getMessage());
            e.printStackTrace();
            result.put("ERROR", "Java function error: " + e.getMessage());
            result.put("vars", vars);
        }
    }

    private static void executeOracleAction(HttpExchange query, JSONObject result,
                                            String actionName, String dbName,
                                            JSONObject vars, boolean debugMode) {
        Connection conn = null;
        CallableStatement cs = null;
        Map<String, Object> session = query.session;

        try {
            System.out.println("=== executeOracleAction called ===");
            System.out.println("actionName: " + actionName);
            System.out.println("dbName: " + dbName);

            DatabaseConfig dbConfig = null;

            if (dbName != null && !"DB".equals(dbName) && !dbName.isEmpty()) {
                dbConfig = ServerConstant.config.getDatabaseConfig(dbName);
                System.out.println("Trying to get config by name: " + dbName + " -> " + (dbConfig != null ? dbConfig.getType() : "null"));
            }

            if (dbConfig == null) {
                dbConfig = ServerConstant.config.getDatabaseConfig("oracle_test");
                System.out.println("Trying to get config by 'oracle_test': " + (dbConfig != null ? dbConfig.getType() : "null"));
            }

            if (dbConfig == null) {
                for (Map.Entry<String, DatabaseConfig> entry : ServerConstant.config.DATABASES.entrySet()) {
                    if ("oci8".equals(entry.getValue().getType())) {
                        dbConfig = entry.getValue();
                        System.out.println("Found Oracle config by scanning: " + entry.getKey() + " -> " + dbConfig.getType());
                        break;
                    }
                }
            }

            if (dbConfig == null) {
                result.put("ERROR", "Database configuration not found for: " + dbName + " (tried oracle_test as well)");
                result.put("vars", vars);
                return;
            }

            System.out.println("dbConfig type: " + dbConfig.getType());
            System.out.println("dbConfig host: " + dbConfig.getHost());
            System.out.println("dbConfig port: " + dbConfig.getPort());
            System.out.println("dbConfig database: " + dbConfig.getDatabase());
            System.out.println("dbConfig username: " + dbConfig.getUsername());

            if (!"oci8".equals(dbConfig.getType())) {
                result.put("ERROR", "Not an Oracle database config. Type: " + dbConfig.getType() + ". Expected 'oci8'");
                result.put("vars", vars);
                return;
            }

            if (!procedureList.containsKey(actionName)) {
                result.put("ERROR", "Action not found: " + actionName);
                result.put("vars", vars);
                System.err.println("Action not found: " + actionName);
                return;
            }

            HashMap<String, Object> param = (HashMap<String, Object>) procedureList.get(actionName);
            String sql = (String) param.get("SQL");
            List<String> sqlParamNames = (List<String>) param.get("SQL_PARAMS");
            List<String> varsArr = (List<String>) param.get("vars");
            Map<String, String> varTypes = (Map<String, String>) param.get("varTypes");
            Map<String, String> varDirections = (Map<String, String>) param.get("varDirections");

            System.out.println("Executing Oracle SQL: " + sql);
            System.out.println("Vars array: " + varsArr);
            System.out.println("SQL Param names: " + sqlParamNames);

            conn = OracleQuery.getConnect(dbConfig);
            if (conn == null) {
                result.put("ERROR", "Oracle connection failed for: " + dbConfig.getHost() + ":" + dbConfig.getPort() + "/" + dbConfig.getDatabase());
                result.put("vars", vars);
                return;
            }

            System.out.println("Oracle connection established successfully");

            cs = conn.prepareCall(sql);

            // Регистрируем OUT параметры по sqlParamNames
            int idx = 1;
            for (String paramName : sqlParamNames) {
                String direction = varDirections.getOrDefault(paramName, "IN");
                String targetType = varTypes.getOrDefault(paramName, "string");
                if ("OUT".equals(direction) || "INOUT".equals(direction)) {
                    registerOracleOutParameter(cs, idx, targetType);
                    System.out.println("Registered OUT parameter " + idx + " for: " + paramName);
                }
                idx++;
            }

            // Устанавливаем IN параметры
            idx = 1;
            for (String paramName : sqlParamNames) {
                String direction = varDirections.getOrDefault(paramName, "IN");
                if ("IN".equals(direction) || "INOUT".equals(direction)) {
                    String valueStr = getValueFromVars(vars, session, paramName);
                    String targetType = varTypes.getOrDefault(paramName, "string");
                    setOracleParameter(cs, idx, valueStr, targetType);
                    System.out.println("Set IN parameter " + idx + " (" + paramName + "): " + valueStr);
                }
                idx++;
            }

            System.out.println("Executing Oracle statement...");
            boolean hasResults = cs.execute();
            System.out.println("Oracle statement executed, hasResults: " + hasResults);

            idx = 1;
            for (String paramName : sqlParamNames) {
                String direction = varDirections.getOrDefault(paramName, "IN");
                if ("OUT".equals(direction) || "INOUT".equals(direction)) {
                    String targetType = varTypes.getOrDefault(paramName, "string");
                    String outParam = getOracleOutParameter(cs, idx, targetType);
                    System.out.println("OUT parameter " + idx + " (" + paramName + "): " + outParam);
                    updateVars(vars, session, paramName, outParam);
                }
                idx++;
            }

            result.put("vars", vars);

            if (hasResults) {
                ResultSet rs = cs.getResultSet();
                if (rs != null) {
                    JSONArray dataArray = new JSONArray();
                    ResultSetMetaData metaData = rs.getMetaData();
                    int columnCount = metaData.getColumnCount();

                    while (rs.next()) {
                        JSONObject row = new JSONObject();
                        for (int i = 1; i <= columnCount; i++) {
                            String columnName = metaData.getColumnName(i);
                            Object value = rs.getObject(i);

                            if (value == null) {
                                row.put(columnName, JSONObject.NULL);
                            } else if (value instanceof oracle.sql.CLOB || value instanceof Clob) {
                                Clob clob = (Clob) value;
                                try (java.io.Reader reader = clob.getCharacterStream()) {
                                    java.io.StringWriter writer = new java.io.StringWriter();
                                    char[] buffer = new char[4096];
                                    int charsRead;
                                    while ((charsRead = reader.read(buffer)) != -1) {
                                        writer.write(buffer, 0, charsRead);
                                    }
                                    row.put(columnName, writer.toString());
                                } catch (Exception e) {
                                    row.put(columnName, "[CLOB]");
                                }
                            } else {
                                row.put(columnName, value);
                            }
                        }
                        dataArray.put(row);
                    }
                    result.put("data", dataArray);
                    rs.close();
                }
            }

            if (debugMode) {
                result.put("SQL", sql);
            }

            System.out.println("Oracle action completed successfully");

        } catch (SQLException e) {
            System.err.println("Oracle SQL Error: " + e.getMessage());
            e.printStackTrace();
            result.put("ERROR", "Oracle SQL Error: " + e.getMessage());
            result.put("vars", vars);
        } catch (Exception e) {
            System.err.println("Unexpected error in Oracle action: " + e.getMessage());
            e.printStackTrace();
            result.put("ERROR", "Error: " + e.getMessage());
            result.put("vars", vars);
        } finally {
            try { if (cs != null) cs.close(); } catch (Exception e) {}
            try { if (conn != null) conn.close(); } catch (Exception e) {}
        }
    }

    private static void setOracleParameter(CallableStatement cs, int index, String value, String type) throws SQLException {
        if (value == null || value.isEmpty()) {
            cs.setNull(index, getOracleSqlType(type));
            return;
        }

        try {
            switch (type.toLowerCase()) {
                case "integer":
                case "int":
                    cs.setInt(index, Integer.parseInt(value));
                    break;
                case "bigint":
                case "long":
                    cs.setLong(index, Long.parseLong(value));
                    break;
                case "decimal":
                case "numeric":
                    cs.setBigDecimal(index, new java.math.BigDecimal(value));
                    break;
                case "boolean":
                case "bool":
                    cs.setBoolean(index, Boolean.parseBoolean(value));
                    break;
                case "date":
                    cs.setDate(index, java.sql.Date.valueOf(value));
                    break;
                case "timestamp":
                    cs.setTimestamp(index, Timestamp.valueOf(value.replace("T", " ")));
                    break;
                case "json":
                case "jsonb":
                    cs.setString(index, value);
                    break;
                case "array":
                    cs.setString(index, value);
                    break;
                case "string":
                default:
                    cs.setString(index, value);
                    break;
            }
        } catch (Exception e) {
            System.err.println("Error converting parameter to " + type + ", using string: " + e.getMessage());
            cs.setString(index, value);
        }
    }

    private static void registerOracleOutParameter(CallableStatement cs, int index, String type) throws SQLException {
        cs.registerOutParameter(index, getOracleSqlType(type));
    }

    private static String getOracleOutParameter(CallableStatement cs, int index, String type) throws SQLException {
        try {
            switch (type.toLowerCase()) {
                case "integer":
                case "int":
                    int intVal = cs.getInt(index);
                    return cs.wasNull() ? "" : String.valueOf(intVal);
                case "bigint":
                case "long":
                    long longVal = cs.getLong(index);
                    return cs.wasNull() ? "" : String.valueOf(longVal);
                case "decimal":
                case "numeric":
                    java.math.BigDecimal decimalVal = cs.getBigDecimal(index);
                    return decimalVal == null ? "" : decimalVal.toString();
                case "boolean":
                case "bool":
                    boolean boolVal = cs.getBoolean(index);
                    return cs.wasNull() ? "" : String.valueOf(boolVal);
                case "date":
                    java.sql.Date dateVal = cs.getDate(index);
                    return dateVal == null ? "" : dateVal.toString();
                case "timestamp":
                    Timestamp tsVal = cs.getTimestamp(index);
                    return tsVal == null ? "" : tsVal.toString();
                case "json":
                case "jsonb":
                case "array":
                case "string":
                default:
                    String strVal = cs.getString(index);
                    return strVal == null ? "" : strVal;
            }
        } catch (SQLException e) {
            System.err.println("Error getting OUT parameter at index " + index + ": " + e.getMessage());
            return "";
        }
    }

    private static int getOracleSqlType(String type) {
        switch (type.toLowerCase()) {
            case "integer":
            case "int":
                return Types.INTEGER;
            case "bigint":
            case "long":
                return Types.BIGINT;
            case "decimal":
            case "numeric":
                return Types.NUMERIC;
            case "boolean":
            case "bool":
                return Types.BOOLEAN;
            case "date":
                return Types.DATE;
            case "timestamp":
                return Types.TIMESTAMP;
            case "json":
            case "jsonb":
            case "array":
            case "string":
            default:
                return Types.VARCHAR;
        }
    }

    private static void executePostgresAction(HttpExchange query, JSONObject result, String fullActionName,
                                              JSONObject vars, Map<String, Object> session, boolean debugMode,
                                              Map<String, String> varDirections) {
        Connection conn = null;
        CallableStatement cs = null;

        try {
            if (!procedureList.containsKey(fullActionName)) {
                result.put("ERROR", "Procedure not found: " + fullActionName);
                result.put("vars", vars);
                System.err.println("Procedure not found: " + fullActionName);
                return;
            }

            System.out.println("Found procedure in list: " + fullActionName);

            HashMap<String, Object> param = (HashMap<String, Object>) procedureList.get(fullActionName);
            Map<String, String> varTypes = (Map<String, String>) param.get("varTypes");
            DatabaseConfig dbConfig = (DatabaseConfig) param.get("dbConfig");

            String schemaName = "public";
            String functionNameOnly = fullActionName;
            if (fullActionName.contains(".")) {
                schemaName = fullActionName.substring(0, fullActionName.lastIndexOf('.'));
                functionNameOnly = fullActionName.substring(fullActionName.lastIndexOf('.') + 1);
            }

            if (dbConfig == null) {
                if (session.containsKey("DATABASE")) {
                    HashMap<String, Object> data_base = (HashMap<String, Object>) session.get("DATABASE");
                    dbConfig = new DatabaseConfig();
                    dbConfig.setDriver("org.postgresql.Driver");
                    dbConfig.setHost("localhost");
                    dbConfig.setPort("5432");
                    dbConfig.setDatabase((String) data_base.get("DATABASE_NAME"));
                    dbConfig.setUsername((String) data_base.get("DATABASE_USER_NAME"));
                    dbConfig.setPassword((String) data_base.get("DATABASE_USER_PASS"));
                    dbConfig.setType("jdbc");
                } else {
                    String dbName = query.requestParam.optString("db", "default");
                    dbConfig = ServerConstant.config.getDatabaseConfig(dbName);
                }
            }

            if (dbConfig == null) {
                result.put("ERROR", "Database configuration not found");
                result.put("vars", vars);
                return;
            }

            String jdbcUrl = dbConfig.getJdbcUrl();
            if (!jdbcUrl.contains("currentSchema")) {
                if (jdbcUrl.contains("?")) {
                    jdbcUrl += "&currentSchema=" + schemaName;
                } else {
                    jdbcUrl += "?currentSchema=" + schemaName;
                }
            }

            Class.forName("org.postgresql.Driver");
            Properties props = new Properties();
            props.setProperty("user", dbConfig.getUsername());
            props.setProperty("password", dbConfig.getPassword());
            props.setProperty("currentSchema", schemaName);

            conn = DriverManager.getConnection(jdbcUrl, props);
            conn.setAutoCommit(false);

            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET search_path TO " + schemaName + ", public");
                System.out.println("Search path set to: " + schemaName + ", public");
            } catch (SQLException e) {
                System.err.println("Error setting search path: " + e.getMessage());
            }

            String prepareCall = (String) param.get("prepareCall");
            System.out.println("PrepareCall: " + prepareCall);

            cs = conn.prepareCall(prepareCall);

            List<String> varsArr = (List<String>) param.get("vars");
            System.out.println("Vars array: " + varsArr);

            int ind = 0;
            for (String varName : varsArr) {
                ind++;
                String direction = (varDirections != null) ? varDirections.getOrDefault(varName, "IN") : "IN";
                String type = varTypes != null ? varTypes.getOrDefault(varName, "string") : "string";

                if ("OUT".equals(direction) || "INOUT".equals(direction)) {
                    switch (type) {
                        case "integer": case "int": cs.registerOutParameter(ind, Types.INTEGER); break;
                        case "bigint": case "long": cs.registerOutParameter(ind, Types.BIGINT); break;
                        case "decimal": case "numeric": cs.registerOutParameter(ind, Types.NUMERIC); break;
                        case "boolean": case "bool": cs.registerOutParameter(ind, Types.BOOLEAN); break;
                        case "date": cs.registerOutParameter(ind, Types.DATE); break;
                        case "timestamp": cs.registerOutParameter(ind, Types.TIMESTAMP); break;
                        case "json": case "jsonb": cs.registerOutParameter(ind, Types.OTHER); break;
                        case "array": cs.registerOutParameter(ind, Types.ARRAY); break;
                        default: cs.registerOutParameter(ind, Types.VARCHAR); break;
                    }
                    System.out.println("Registered " + direction + " parameter " + ind + " for var: " + varName);
                }
            }

            if (debugMode) {
                result.put("SQL", ((String) param.get("SQL")).split("\n"));
            }

            ind = 0;
            for (String varNameOne : varsArr) {
                ind++;
                String direction = (varDirections != null) ? varDirections.getOrDefault(varNameOne, "IN") : "IN";
                String targetType = varTypes != null ? varTypes.getOrDefault(varNameOne, "string") : "string";
                String valueStr = "";

                if ("IN".equals(direction) || "INOUT".equals(direction)) {
                    if (vars.has(varNameOne)) {
                        Object varObj = vars.get(varNameOne);
                        if (varObj instanceof JSONObject) {
                            JSONObject varOne = (JSONObject) varObj;
                            if (varOne.optString("srctype").equals("session")) {
                                Object sessionVal = session.get(varNameOne);
                                if (sessionVal != null) {
                                    valueStr = String.valueOf(sessionVal);
                                } else {
                                    valueStr = varOne.optString("defaultVal", "");
                                }
                            } else {
                                valueStr = varOne.optString("value", varOne.optString("defaultVal", ""));
                            }
                        } else {
                            valueStr = varObj.toString();
                        }
                    }

                    System.out.println("Setting IN parameter " + ind + " (" + varNameOne + "): " + valueStr);

                    try {
                        switch (targetType) {
                            case "integer": case "int":
                                if (valueStr.isEmpty()) cs.setNull(ind, Types.INTEGER);
                                else cs.setInt(ind, Integer.parseInt(valueStr));
                                break;
                            case "bigint": case "long":
                                if (valueStr.isEmpty()) cs.setNull(ind, Types.BIGINT);
                                else cs.setLong(ind, Long.parseLong(valueStr));
                                break;
                            case "decimal": case "numeric":
                                if (valueStr.isEmpty()) cs.setNull(ind, Types.NUMERIC);
                                else cs.setBigDecimal(ind, new java.math.BigDecimal(valueStr));
                                break;
                            case "boolean": case "bool":
                                if (valueStr.isEmpty()) cs.setNull(ind, Types.BOOLEAN);
                                else cs.setBoolean(ind, Boolean.parseBoolean(valueStr));
                                break;
                            case "date":
                                if (valueStr.isEmpty()) cs.setNull(ind, Types.DATE);
                                else cs.setDate(ind, java.sql.Date.valueOf(valueStr));
                                break;
                            case "timestamp":
                                if (valueStr.isEmpty()) cs.setNull(ind, Types.TIMESTAMP);
                                else cs.setTimestamp(ind, Timestamp.valueOf(valueStr.replace("T", " ")));
                                break;
                            default:
                                if (valueStr.isEmpty()) cs.setNull(ind, Types.VARCHAR);
                                else cs.setString(ind, valueStr);
                                break;
                        }
                    } catch (Exception e) {
                        cs.setString(ind, valueStr);
                    }
                }
            }

            System.out.println("Executing procedure...");
            cs.execute();
            System.out.println("Procedure executed successfully");

            ind = 0;
            for (String varNameOne : varsArr) {
                ind++;
                String direction = (varDirections != null) ? varDirections.getOrDefault(varNameOne, "IN") : "IN";
                String targetType = varTypes != null ? varTypes.getOrDefault(varNameOne, "string") : "string";
                String outParam = "";

                if ("OUT".equals(direction) || "INOUT".equals(direction)) {
                    try {
                        switch (targetType) {
                            case "integer": case "int":
                                int intVal = cs.getInt(ind);
                                outParam = cs.wasNull() ? "" : String.valueOf(intVal);
                                break;
                            case "bigint": case "long":
                                long longVal = cs.getLong(ind);
                                outParam = cs.wasNull() ? "" : String.valueOf(longVal);
                                break;
                            case "decimal": case "numeric":
                                java.math.BigDecimal decimalVal = cs.getBigDecimal(ind);
                                outParam = decimalVal == null ? "" : decimalVal.toString();
                                break;
                            case "boolean": case "bool":
                                boolean boolVal = cs.getBoolean(ind);
                                outParam = cs.wasNull() ? "" : String.valueOf(boolVal);
                                break;
                            case "date":
                                java.sql.Date dateVal = cs.getDate(ind);
                                outParam = dateVal == null ? "" : dateVal.toString();
                                break;
                            case "timestamp":
                                Timestamp timestampVal = cs.getTimestamp(ind);
                                outParam = timestampVal == null ? "" : timestampVal.toString();
                                break;
                            case "json": case "jsonb":
                                Object jsonVal = cs.getObject(ind);
                                outParam = jsonVal == null ? "" : jsonVal.toString();
                                break;
                            case "array":
                                Array arrayVal = cs.getArray(ind);
                                if (arrayVal != null) {
                                    Object[] array = (Object[]) arrayVal.getArray();
                                    JSONArray jsonArray = new JSONArray();
                                    for (Object item : array) {
                                        jsonArray.put(item != null ? item.toString() : null);
                                    }
                                    outParam = jsonArray.toString();
                                }
                                break;
                            default:
                                outParam = cs.getString(ind);
                                if (outParam == null) outParam = "";
                                break;
                        }
                    } catch (SQLException e) {
                        System.err.println("Error getting OUT parameter " + ind + ": " + e.getMessage());
                        outParam = "";
                    }

                    System.out.println("OUT parameter " + ind + " (" + varNameOne + "): " + outParam);

                    if (vars.has(varNameOne) && vars.get(varNameOne) instanceof JSONObject) {
                        JSONObject varOne = vars.getJSONObject(varNameOne);
                        if (varOne.optString("srctype").equals("session")) {
                            session.put(varNameOne, outParam);
                        } else {
                            varOne.put("value", outParam);
                        }
                    } else {
                        JSONObject newVar = new JSONObject();
                        newVar.put("value", outParam);
                        newVar.put("src", varNameOne);
                        newVar.put("srctype", "var");
                        vars.put(varNameOne, newVar);
                    }
                }
            }

            result.put("vars", vars);

        } catch (SQLException e) {
            System.err.println("SQL Error: " + e.getMessage());
            e.printStackTrace();
            result.put("ERROR", "SQL Error: " + e.getMessage());
            result.put("vars", vars);
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
            result.put("ERROR", "Error: " + e.getMessage());
            result.put("vars", vars);
        } finally {
            try { if (cs != null) cs.close(); } catch (Exception e) {}
            try { if (conn != null) conn.close(); } catch (Exception e) {}
        }
    }

    private static ParsedSql parseNamedParameters(String sql) {
        if (sql == null || sql.isEmpty()) {
            return new ParsedSql(sql, Collections.emptyList());
        }

        StringBuilder processedSql = new StringBuilder(sql.length() + 16);
        List<String> paramNames = new ArrayList<>();
        StringBuilder paramName = new StringBuilder();
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
                processedSql.append(c);
                continue;
            }

            if (c == ':' && !inParam) {
                inParam = true;
                paramName = new StringBuilder();
            } else if (inParam && (Character.isLetterOrDigit(c) || c == '_')) {
                paramName.append(c);
            } else if (inParam) {
                inParam = false;
                if (paramName.length() > 0) {
                    paramNames.add(paramName.toString());
                }
                processedSql.append('?').append(c);
            } else {
                processedSql.append(c);
            }
        }

        if (inParam && paramName.length() > 0) {
            paramNames.add(paramName.toString());
            processedSql.append('?');
        }

        return new ParsedSql(processedSql.toString(), paramNames);
    }

    private static String getValueFromVars(JSONObject vars, Map<String, Object> session, String paramName) {
        if (vars.has(paramName)) {
            Object varObj = vars.get(paramName);
            if (varObj instanceof JSONObject) {
                JSONObject varOne = (JSONObject) varObj;
                if (varOne.optString("srctype").equals("session")) {
                    Object sessionVal = session.get(paramName);
                    return sessionVal != null ? String.valueOf(sessionVal) : varOne.optString("defaultVal", "");
                } else {
                    return varOne.optString("value", varOne.optString("defaultVal", ""));
                }
            } else {
                return varObj.toString();
            }
        }
        return "";
    }

    private static void updateVars(JSONObject vars, Map<String, Object> session, String paramName, String value) {
        if (vars.has(paramName) && vars.get(paramName) instanceof JSONObject) {
            JSONObject varOne = vars.getJSONObject(paramName);
            if (varOne.optString("srctype").equals("session")) {
                session.put(paramName, value);
            } else {
                varOne.put("value", value);
            }
        } else {
            JSONObject newVar = new JSONObject();
            newVar.put("value", value);
            newVar.put("src", paramName);
            newVar.put("srctype", "var");
            vars.put(paramName, newVar);
        }
    }

    private static class ParsedSql {
        final String processedSql;
        final List<String> paramNames;

        ParsedSql(String processedSql, List<String> paramNames) {
            this.processedSql = processedSql;
            this.paramNames = paramNames;
        }
    }
}