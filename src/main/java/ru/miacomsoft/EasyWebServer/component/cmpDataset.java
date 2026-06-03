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
public class cmpDataset extends Base {

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
    public cmpDataset(Document doc, Element element, String tag) {
        super(doc, element, tag);
        // Сохраняем режим отладки из документа
        if (doc != null && doc.hasAttr("debug_mode")) {
            debugMode = Boolean.parseBoolean(doc.attr("debug_mode"));
        }
        initialize(doc, element);
    }

    // Конструктор с двумя параметрами
    public cmpDataset(Document doc, Element element) {
        super(doc, element, "teaxtarea");
        // Сохраняем режим отладки из документа
        if (doc != null && doc.hasAttr("debug_mode")) {
            debugMode = Boolean.parseBoolean(doc.attr("debug_mode"));
        }
        initialize(doc, element);
    }

    /**
     * Проверка существования базы данных PostgreSQL
     */
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


    /**
     * Создание конфигурации для подключения к административной БД
     */
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

    /**
     * Проверка существования схемы в PostgreSQL
     */
    private boolean checkSchemaExists(String schemaName, String dbName, DatabaseConfig dbConfig) {
        String cacheKey = dbName + ":schema:" + schemaName;

        if (schemaExistsCache.containsKey(cacheKey)) {
            return schemaExistsCache.get(cacheKey);
        }

        Connection conn = null;
        try {
            conn = getConnectionFromConfig(dbConfig);
            if (conn == null) {
                System.err.println("Cannot connect to database for schema check: " + dbName);
                return false;
            }

            String sql = "SELECT EXISTS(SELECT 1 FROM information_schema.schemata WHERE schema_name = ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, schemaName);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        boolean exists = rs.getBoolean(1);
                        schemaExistsCache.put(cacheKey, exists);
                        return exists;
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Error checking schema existence: " + e.getMessage());
        } finally {
            releaseConnection(conn);
        }

        schemaExistsCache.put(cacheKey, false);
        return false;
    }

    /**
     * Создание схемы в PostgreSQL
     */
    private boolean createSchema(String schemaName, String dbName, DatabaseConfig dbConfig) {
        Connection conn = null;
        try {
            conn = getConnectionFromConfig(dbConfig);
            if (conn == null) {
                System.err.println("Cannot connect to database for schema creation: " + dbName);
                return false;
            }

            // Определяем владельца схемы
            String owner = dbConfig.getUsername();

            String sql = String.format("CREATE SCHEMA IF NOT EXISTS \"%s\" AUTHORIZATION \"%s\"",
                    schemaName.replace("\"", "\"\""),
                    owner.replace("\"", "\"\""));

            System.out.println("Creating schema with SQL: " + sql);

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
            rollbackQuietly(conn);
        } finally {
            releaseConnection(conn);
        }
        return false;
    }

    /**
     * Проверка существования функции в PostgreSQL
     */
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

    /**
     * Проверяет, нужно ли пересоздать функцию (по хэшу содержимого)
     */
    private boolean needsRecreation(String functionName, String currentHash, DatabaseConfig dbConfig, String schema) {
        if (debugMode) {
            System.out.println("Debug mode enabled, forcing recreation of: " + functionName);
            return true;
        }

        String cacheKey = functionName;
        String cachedHash = functionContentHashCache.get(cacheKey);

        // Если хэша нет в кэше - нужно создать
        if (cachedHash == null) {
            System.out.println("No cached hash for: " + functionName + ", will create");
            return true;
        }

        // Если хэш изменился - нужно пересоздать
        if (!cachedHash.equals(currentHash)) {
            System.out.println("Content hash changed for: " + functionName);
            System.out.println("  Old: " + cachedHash);
            System.out.println("  New: " + currentHash);
            return true;
        }

        // Проверяем, существует ли функция в БД (на всякий случай)
        if (!checkFunctionExistsInDB(functionName, schema, dbConfig)) {
            System.out.println("Function exists in cache but not in DB: " + functionName + ", will recreate");
            return true;
        }

        System.out.println("Function " + functionName + " is up to date, skipping recreation");
        return false;
    }

    /**
     * Проверка существования функции в БД
     */
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

    /**
     * Обновляет кэш хэша функции
     */
    private void updateFunctionHashCache(String functionName, String hash) {
        functionContentHashCache.put(functionName, hash);
        System.out.println("Updated hash cache for: " + functionName + " -> " + hash);
    }

    /**
     * Вспомогательный метод для получения соединения из конфигурации
     */
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

    /**
     * Вспомогательный метод для освобождения соединения
     */
    private void releaseConnection(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                // ignore
            }
        }
    }

    /**
     * Вспомогательный метод для отката транзакции
     */
    private void rollbackQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.rollback();
            } catch (SQLException e) {
                // ignore
            }
        }
    }

    private void initialize(Document doc, Element element) {
        Attributes attrs = element.attributes();
        Attributes attrsDst = this.attributes();
        attrsDst.add("schema", "Dataset");
        String name = attrs.get("name");
        this.attr("name", name);
        attrsDst.add("name", name);
        this.initCmpType(element);

        String dbName = RemoveArrKeyRtrn(attrs, "db", "default");
        String query_type = "sql";
        if (attrs.hasKey("query_type")) {
            query_type = attrs.get("query_type");
        }
        attrsDst.add("query_type", query_type);

        DatabaseConfig dbConfig = null;
        String pgSchema = "public";
        String dbType = "jdbc";
        boolean isOracle = false;

        // Для SQL запросов получаем конфигурацию БД и проверяем существование БД/схемы
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

            // === ПРОВЕРКА И СОЗДАНИЕ БД И СХЕМЫ ДЛЯ PostgreSQL ===
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

        // Добавляем атрибуты в результирующий элемент
        attrsDst.add("pg_schema", pgSchema);
        attrsDst.add("db_type", dbType);
        attrsDst.add("query_type", query_type);
        attrsDst.add("db", dbName);

        // Формирование имени функции
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

        // Вычисляем хэш содержимого
        String contentHash = getShortHash(element.hasText() ? element.text().trim() : "");
        currentContentHash = contentHash;

        // Формируем имя функции
        String functionName;
        if (isOracle) {
            // Для Oracle - используем простое имя из атрибута name (без хэша)
            functionName = element.attr("name");
            System.out.println("Oracle mode: using simple name (no function creation): " + functionName);
        } else {
            // Для PostgreSQL - формируем имя с хэшем содержимого
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
        this.attr("dataset_name", functionName);
        this.attr("name", element.attr("name"));

        // Обработка переменных
        StringBuffer jsonVar = new StringBuffer();
        ArrayList<String> jarResourse = new ArrayList<String>();
        ArrayList<String> importPacket = new ArrayList<String>();

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
            } else if (tagName.indexOf("var") != -1) {
                String nameItem = attrsItem.get("name");
                String src = RemoveArrKeyRtrn(attrsItem, "src", nameItem);
                String srctype = RemoveArrKeyRtrn(attrsItem, "srctype", "");
                String len = RemoveArrKeyRtrn(attrsItem, "len", "");
                String defaultVal = RemoveArrKeyRtrn(attrsItem, "default", "");
                jsonVar.append("'" + nameItem + "':{");
                jsonVar.append("'src':'" + src + "',");
                jsonVar.append("'srctype':'" + srctype + "'");
                if (len.length()>0) jsonVar.append(",'len':'" + len + "'");
                if (defaultVal.length()>0) jsonVar.append(",'defaultVal':'" + defaultVal.replaceAll("'", "\\'") + "'");
                jsonVar.append("},");
            }
        }

        String jsonVarStr = jsonVar.toString();
        if (jsonVarStr.length()>0) {
            jsonVarStr = jsonVarStr.substring(0, jsonVarStr.length() - 1);
        }
        this.attr("vars", "{" + jsonVarStr + "}");

        // Обработка тела компонента
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

            } else if (query_type.equals("sql")) {
                // ДЛЯ ORACLE - сохраняем SQL для прямого выполнения, НЕ создаём функции
                if (isOracle) {
                    System.out.println("Oracle detected: saving SQL for direct execution, no function creation");

                    HashMap<String, Object> param = new HashMap<String, Object>();
                    param.put("SQL", element.text().trim());
                    param.put("vars", new ArrayList<String>());
                    param.put("dbType", dbType);
                    param.put("dbName", dbName);
                    param.put("contentHash", contentHash);

                    procedureList.put(functionName, param);
                    System.out.println("Oracle dataset saved for direct execution: " + functionName);

                } else {
                    // ДЛЯ POSTGRESQL - создаём/обновляем функцию
                    String fullFunctionName = pgSchema + "." + functionName;

                    if (needsRecreation(fullFunctionName, contentHash, dbConfig, pgSchema)) {
                        System.out.println("Creating/updating function: " + fullFunctionName);
                        createOrReplaceSQLFunction(fullFunctionName, pgSchema, element,
                                docPath + " (" + element.attr("name") + ")",
                                debugMode, dbConfig, contentHash);
                        updateFunctionHashCache(fullFunctionName, contentHash);
                    } else {
                        System.out.println("Function " + fullFunctionName + " is up to date, skipping creation");
                        loadExistingFunctionToCache(fullFunctionName, pgSchema, element, dbConfig);
                    }
                }
            }
        }

        // Очищаем содержимое
        this.text("");

        // Сохраняем важные атрибуты
        String savedQueryType = this.attr("query_type");
        String savedDbType = this.attr("db_type");
        String savedPgSchema = this.attr("pg_schema");
        String savedDb = this.attr("db");
        String savedDatasetName = this.attr("dataset_name");
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
        if (savedDatasetName != null) this.attr("dataset_name", savedDatasetName);
        if (savedName != null) this.attr("name", savedName);
        if (savedVars != null) this.attr("vars", savedVars);
        if (savedStyle != null) this.attr("style", savedStyle);

        StringBuffer sb = new StringBuffer();
        sb.append("<script> $(function() {");
        sb.append("  D3Api.setDatasetAuto('" + name + "');");
        sb.append("}); </script>");
        if (doc != null) {
            Elements elements = doc.getElementsByTag("body");
            if (elements != null && elements.size() > 0) {
                elements.append(sb.toString());
            }
        }

        // Автоматическое подключение JavaScript библиотеки
        if (doc != null) {
            Elements head = doc.getElementsByTag("head");
            if (head != null && head.size() > 0) {
                Elements existingScripts = head.select("script[src*='cmpDataset_js']");
                if (existingScripts.isEmpty()) {
                    String jsPath = "/{component}/cmpDataset_js";
                    head.append("<script cmp=\"dataset-lib\" src=\"" + jsPath + "\" type=\"text/javascript\"></script>");
                    System.out.println("cmpDataset: JavaScript library auto-included for dataset: " + name);
                }
            }
        }
    }

    /**
     * Вспомогательный метод для проверки наличия var элементов
     */
    private boolean hasVarElements(Element element) {
        for (int i = 0; i < element.childrenSize(); i++) {
            Element child = element.child(i);
            if (child.tag().toString().toLowerCase().indexOf("var") != -1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Проверка существования функции в БД с использованием EXISTS с учетом схемы (для обратной совместимости)
     */
    private boolean checkFunctionExistsInDB(String fullFunctionName, String schema) {
        Connection conn = null;
        try {
            conn = getConnect(ServerConstant.config.DATABASE_USER_NAME, ServerConstant.config.DATABASE_USER_PASS);
            if (conn == null) return false;

            Statement stmt = conn.createStatement();
            // Извлекаем имя функции без схемы
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
                try {
                    conn.close();
                } catch (SQLException e) {
                    // ignore
                }
            }
        }
    }

    /**
     * Проверка существования функции в БД (для обратной совместимости)
     */
    private boolean functionExistsInDB(String functionName) {
        String fullFunctionName = ServerConstant.config.APP_NAME + "_" + functionName;
        return checkFunctionExistsInDB(fullFunctionName, "public");
    }

    /**
     * Создание или замена SQL функции в PostgreSQL
     */
    private void createOrReplaceSQLFunction(String functionName, String schema, Element element, String fileName, boolean debugMode, DatabaseConfig dbConfig, String contentHash) {
        // Очищаем имя функции
        String cleanFunctionName = functionName;
        if (functionName.contains(".")) {
            cleanFunctionName = functionName.substring(functionName.lastIndexOf('.') + 1);
        }

        if (cleanFunctionName.length() > 0 && Character.isDigit(cleanFunctionName.charAt(0))) {
            cleanFunctionName = "f_" + cleanFunctionName;
        }

        // Формируем полное имя для сохранения в procedureList
        String fullFunctionName = schema + "." + cleanFunctionName;

        Connection conn = null;

        // Используем переданный dbConfig для подключения к правильной БД
        if (dbConfig != null) {
            try {
                Class.forName("org.postgresql.Driver");
                conn = DriverManager.getConnection(
                        dbConfig.getJdbcUrl(),
                        dbConfig.getUsername(),
                        dbConfig.getPassword()
                );
                System.out.println("Connected to database using config: " + dbConfig.getDatabase() + " (" + dbConfig.getJdbcUrl() + ")");
            } catch (Exception e) {
                System.err.println("Error connecting to database using config: " + e.getMessage());
                conn = null;
            }
        }

        // Если не удалось подключиться через конфигурацию, пробуем стандартный способ
        if (conn == null) {
            conn = getConnect(ServerConstant.config.DATABASE_USER_NAME, ServerConstant.config.DATABASE_USER_PASS);
            System.out.println("Connected to database using default credentials");
        }

        if (conn == null) {
            System.err.println("Cannot connect to database for creating function");
            return;
        }

        StringBuffer vars = new StringBuffer();
        StringBuffer varsColl = new StringBuffer();
        Attributes attrs = element.attributes();
        HashMap<String, Object> param = new HashMap<String, Object>();
        String language = RemoveArrKeyRtrn(attrs, "language", "plpgsql");
        param.put("language", language);
        List<String> varsArr = new ArrayList<>();
        Map<String, String> varTypes = new HashMap<>();
        String beforeCodeBloc = "";
        String declareBlocText = "";
        String afterCodeBloc = "";

        for (int numChild = 0; numChild < element.childrenSize(); numChild++) {
            Element itemElement = element.child(numChild);
            String tagName = itemElement.tag().toString().toLowerCase();

            if (tagName.equals("before")) {
                String beforeText = itemElement.text().trim();
                if (beforeText.length() > 0) {
                    String lowerBefore = beforeText.toLowerCase();
                    if (lowerBefore.contains("declare") && lowerBefore.contains("begin")) {
                        int declarePos = lowerBefore.indexOf("declare");
                        int beginPos = lowerBefore.indexOf("begin", declarePos);

                        if (declarePos >= 0 && beginPos > declarePos) {
                            declareBlocText = beforeText.substring(declarePos + "declare".length(), beginPos).trim();
                            beforeCodeBloc = beforeText.substring(beginPos + "begin".length()).trim();

                            if (beforeCodeBloc.toLowerCase().endsWith("end;")) {
                                beforeCodeBloc = beforeCodeBloc.substring(0, beforeCodeBloc.length() - 4).trim();
                            }
                        }
                    } else {
                        beforeCodeBloc = beforeText;
                    }
                }
                itemElement.text("");
            } else if (tagName.equals("after")) {
                afterCodeBloc = itemElement.text().trim();
                itemElement.text("");
            } else if (tagName.indexOf("var") != -1) {
                Attributes attrsItem = itemElement.attributes();
                String nameItem = RemoveArrKeyRtrn(attrsItem, "name", "");
                String len = RemoveArrKeyRtrn(attrsItem, "len", "");
                String type = RemoveArrKeyRtrn(attrsItem, "type", "");

                String sqlType = "VARCHAR";

                if (!type.isEmpty()) {
                    switch (type.toLowerCase()) {
                        case "integer":
                        case "int":
                            sqlType = "INTEGER";
                            break;
                        case "bigint":
                        case "long":
                            sqlType = "BIGINT";
                            break;
                        case "decimal":
                        case "numeric":
                            sqlType = "NUMERIC";
                            break;
                        case "boolean":
                        case "bool":
                            sqlType = "BOOLEAN";
                            break;
                        case "date":
                            sqlType = "DATE";
                            break;
                        case "timestamp":
                            sqlType = "TIMESTAMP";
                            break;
                        case "json":
                        case "jsonb":
                            sqlType = "JSONB";
                            break;
                        case "array":
                            sqlType = "TEXT[]";
                            break;
                        case "string":
                        default:
                            if (len.length() > 0 && !len.equals("-1")) {
                                sqlType = "VARCHAR(" + len + ")";
                            } else {
                                sqlType = "TEXT";
                            }
                            break;
                    }
                } else {
                    if (len.length() > 0 && !len.equals("-1")) {
                        sqlType = "VARCHAR(" + len + ")";
                    } else if (len.equals("-1")) {
                        sqlType = "TEXT";
                    } else {
                        sqlType = "VARCHAR";
                    }
                }

                varsArr.add(nameItem);
                varTypes.put(nameItem, type.isEmpty() ? "string" : type.toLowerCase());

                vars.append(nameItem);
                vars.append(" IN ");
                vars.append(sqlType);
                vars.append(",");
                varsColl.append("?,");

                System.out.println("Parameter " + nameItem + ": type=" + sqlType + ", direction=IN, len=" + len);
            }
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

        String mainSql = element.text().trim();
        mainSql = mainSql.replaceAll(";+$", "");

        StringBuffer sb = new StringBuffer();
        sb.append("CREATE OR REPLACE FUNCTION ").append(schema).append(".").append(cleanFunctionName).append("(");
        sb.append(varsStr);
        sb.append(")\n");
        sb.append("RETURNS JSON AS\n");
        sb.append("$$\n");

        if (declareBlocText.length() > 0) {
            sb.append("DECLARE\n");
            sb.append(declareBlocText).append("\n");
        }

        sb.append("BEGIN\n");
        sb.append("-- cmpDataset fileName:");
        sb.append(fileName);
        sb.append("\n");
        sb.append("-- contentHash:");
        sb.append(contentHash);
        sb.append("\n");

        if (beforeCodeBloc.length() > 0) {
            sb.append(beforeCodeBloc);
            if (!beforeCodeBloc.endsWith(";") && !beforeCodeBloc.endsWith("\n")) {
                sb.append(";\n");
            } else {
                sb.append("\n");
            }
        }

        sb.append("RETURN (\n");
        sb.append("SELECT COALESCE(json_agg(row_to_json(tempTab)), '[]'::json) FROM (\n");
        sb.append(mainSql);
        sb.append("\n) tempTab\n");
        sb.append(");\n");

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
        sb.append("LANGUAGE ").append(language).append(";");

        String createFunctionSQL = sb.toString();
        System.out.println("Creating function with SQL:\n" + createFunctionSQL);

        try (Statement stmt = conn.createStatement()) {
            // Сначала удаляем старую функцию, если она существует
            try {
                stmt.execute("DROP FUNCTION IF EXISTS " + schema + "." + cleanFunctionName + " CASCADE;");
                System.out.println("Dropped existing function: " + schema + "." + cleanFunctionName);
            } catch (SQLException e) {
                System.out.println("No existing function to drop or error: " + e.getMessage());
            }

            // Создаем новую функцию
            stmt.execute(createFunctionSQL);
            // conn.commit();
            System.out.println("Function created/replaced successfully: " + fullFunctionName);

            // Для функции, возвращающей JSON, используем SELECT
            String prepareCall;
            if (varsCollStr.isEmpty()) {
                prepareCall = "SELECT " + schema + "." + cleanFunctionName + "()";
            } else {
                prepareCall = "SELECT " + schema + "." + cleanFunctionName + "(" + varsCollStr + ")";
            }

            param.put("prepareCall", prepareCall);
            param.put("connect", conn);
            param.put("SQL", createFunctionSQL);
            param.put("dbConfig", dbConfig);
            param.put("contentHash", contentHash);

            // Сохраняем под полным именем со схемой
            procedureList.put(fullFunctionName, param);

            // Также сохраняем под именем без схемы для обратной совместимости
            procedureList.put(cleanFunctionName, param);

            System.out.println("Function " + fullFunctionName + " created successfully and saved to procedureList with prepareCall: " + prepareCall);

        } catch (SQLException e) {
            System.err.println("Error creating function: " + e.getMessage());
            System.err.println("SQL was:\n" + createFunctionSQL);
            e.printStackTrace();
        } finally {
            try {
                if (conn != null && !conn.isClosed()) {
                    conn.close();
                }
            } catch (SQLException e) {
                // ignore
            }
        }
    }

    /**
     * Загрузка существующей функции в кэш
     */
    private void loadExistingFunctionToCache(String fullFunctionName, String schema, Element element, DatabaseConfig dbConfig) {
        HashMap<String, Object> param = new HashMap<>();
        param.put("SQL", element.text().trim());

        List<String> varsArr = new ArrayList<>();
        Map<String, String> varTypes = new HashMap<>();

        for (int numChild = 0; numChild < element.childrenSize(); numChild++) {
            Element itemElement = element.child(numChild);
            String tagName = itemElement.tag().toString().toLowerCase();
            if (tagName.indexOf("var") != -1) {
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
        String prepareCall;
        if (varsColl.length() == 0) {
            prepareCall = "SELECT " + schema + "." + functionNameOnly + "()";
        } else {
            prepareCall = "SELECT " + schema + "." + functionNameOnly + "(" + varsColl.toString() + ")";
        }

        param.put("prepareCall", prepareCall);

        procedureList.put(fullFunctionName, param);
        procedureList.put(functionNameOnly, param);

        System.out.println("Loaded existing function into cache: " + fullFunctionName);
    }

    /**
     * Получает соединение с правильным search_path для схемы
     */
    private Connection getConnectionWithSchema(DatabaseConfig dbConfig, String schemaName) {
        try {
            Class.forName(dbConfig.getDriver());
            Properties props = new Properties();
            props.setProperty("user", dbConfig.getUsername());
            props.setProperty("password", dbConfig.getPassword());
            props.setProperty("connectTimeout", "10");
            props.setProperty("socketTimeout", "30");

            // Устанавливаем search_path при подключении
            props.setProperty("currentSchema", schemaName);

            Connection conn = DriverManager.getConnection(dbConfig.getJdbcUrl(), props);
            conn.setAutoCommit(false);

            // Дополнительно устанавливаем search_path через SQL
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET search_path TO " + schemaName + ", public");
            }

            return conn;
        } catch (Exception e) {
            System.err.println("Error connecting to database with schema: " + e.getMessage());
            return null;
        }
    }

    /**
     * Проверяет, доступна ли схема для текущего пользователя
     */
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

            // Альтернативная проверка - пробуем установить search_path
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET search_path TO " + schemaName);
                return true;
            }
        } catch (SQLException e) {
            System.err.println("Schema " + schemaName + " is not accessible: " + e.getMessage());
            return false;
        }
    }

    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/json";
        Map<String, Object> session = query.session;
        JSONObject queryProperty = query.requestParam;
        JSONObject vars;
        String postBodyStr = new String(query.postCharBody);
        System.out.println("=== cmpDataset onPage called ===");
        System.out.println("queryProperty: " + queryProperty.toString());
        System.out.println("postBodyStr: " + postBodyStr);

        // Парсим входные переменные
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

        System.out.println("Parsed vars: " + vars.toString());

        JSONObject result = new JSONObject();
        result.put("data", new JSONArray());
        result.put("vars", vars);

        String query_type = queryProperty.optString("query_type", "sql");
        String dataset_name = queryProperty.optString("dataset_name", "");
        String pg_schema = queryProperty.optString("pg_schema", "public");
        String db_type = queryProperty.optString("db_type", "jdbc").toLowerCase();
        String database_name = queryProperty.optString("database_name", "default");
        boolean isOracleQuery = db_type.equals("oci8");

        System.out.println("Dataset name: " + dataset_name);
        System.out.println("Query type: " + query_type);
        System.out.println("DB type: " + db_type);
        System.out.println("DB name: " + database_name);
        System.out.println("PG Schema: " + pg_schema);

        boolean debugMode = false;
        if (query.session != null && query.session.containsKey("debug_mode")) {
            debugMode = (boolean) query.session.get("debug_mode");
        }

        // Проверка Java функции
        String fullFunctionName = ServerConstant.config.APP_NAME + "_" + dataset_name;
        System.out.println("Java function exists check: " + ServerResourceHandler.javaStrExecut.existJavaFunction(fullFunctionName));
        if (query_type.equals("java")) {
            dataset_name = fullFunctionName;
        }

        if (ServerResourceHandler.javaStrExecut.existJavaFunction(dataset_name)) {
            // Обработка Java функции
            try {
                JSONObject varFun = new JSONObject();
                Iterator<String> keys = vars.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object val = vars.get(key);
                    if (val instanceof JSONObject) {
                        JSONObject varOne = (JSONObject) val;
                        String value = "";
                        if (varOne.optString("srctype").equals("session")) {
                            if (session.containsKey(key)) {
                                value = String.valueOf(session.get(key));
                            } else {
                                value = varOne.optString("defaultVal", "");
                            }
                        } else {
                            value = varOne.optString("value", varOne.optString("defaultVal", ""));
                        }
                        varFun.put(key, value);
                    } else {
                        varFun.put(key, val.toString());
                    }
                }

                JSONArray dataRes = new JSONArray();
                JSONObject resFun = ServerResourceHandler.javaStrExecut.runFunction(dataset_name, varFun, session, dataRes);

                if (resFun.has("JAVA_ERROR")) {
                    result.put("ERROR", resFun.get("JAVA_ERROR"));
                } else {
                    result.put("data", dataRes);
                    if (resFun.length() > 0) {
                        keys = resFun.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            if (!key.equals("JAVA_ERROR") && !key.equals("data")) {
                                Object value = resFun.get(key);
                                if (vars.has(key) && vars.get(key) instanceof JSONObject) {
                                    vars.getJSONObject(key).put("value", value.toString());
                                } else {
                                    JSONObject newVar = new JSONObject();
                                    newVar.put("value", value.toString());
                                    newVar.put("src", key);
                                    newVar.put("srctype", "var");
                                    vars.put(key, newVar);
                                }
                            }
                        }
                        result.put("vars", vars);
                    }
                }
            } catch (Exception e) {
                result.put("ERROR", "Java function error: " + e.getMessage());
                e.printStackTrace();
            }

        } else if (query_type.equals("sql")) {
            // ПОИСК ДЕЙСТВИЯ В procedureList
            String foundKey = null;
            Object foundParam = null;

            // Вариант 1: полное имя со схемой
            String fullName = pg_schema + "." + dataset_name;
            if (procedureList.containsKey(fullName)) {
                foundKey = fullName;
                foundParam = procedureList.get(fullName);
                System.out.println("Found dataset by full name: " + fullName);
            }
            // Вариант 2: только имя (без схемы)
            else if (procedureList.containsKey(dataset_name)) {
                foundKey = dataset_name;
                foundParam = procedureList.get(dataset_name);
                System.out.println("Found dataset by name only: " + dataset_name);
            }
            // Вариант 3: поиск по частичному совпадению
            else {
                for (String key : procedureList.keySet()) {
                    if (key.endsWith(dataset_name)) {
                        foundKey = key;
                        foundParam = procedureList.get(key);
                        System.out.println("Found dataset by suffix match: " + key);
                        break;
                    }
                }
            }

            if (foundParam == null) {
                result.put("ERROR", "Dataset not found: " + dataset_name);
                result.put("vars", vars);
                System.err.println("Available keys in procedureList: " + procedureList.keySet());
                return result.toString().getBytes();
            }

            // ОПРЕДЕЛЯЕМ ТИП БД ИЗ СОХРАНЁННОГО ПАРАМЕТРА
            HashMap<String, Object> param = (HashMap<String, Object>) foundParam;
            String savedDbType = (String) param.get("dbType");
            boolean isOracleDataset = "oci8".equals(savedDbType);

            System.out.println("Found dataset: " + foundKey + ", saved dbType: " + savedDbType + ", isOracle: " + isOracleDataset);

            if (isOracleDataset) {
                executeOracleQuery(query, result, foundKey, database_name, vars, debugMode);
            } else {
                String fullDatasetName = foundKey.contains(".") ? foundKey : pg_schema + "." + foundKey;
                executePostgresQuery(query, result, fullDatasetName, vars, debugMode);
            }
        } else {
            result.put("ERROR", "Unsupported query type: " + query_type);
        }

        String resultText = result.toString();
        System.out.println("Response: " + resultText);
        return resultText.getBytes();
    }

    private static void executeOracleQuery(HttpExchange query, JSONObject result, String datasetName, String dbName, JSONObject vars, boolean debugMode) {
        try {
            DatabaseConfig dbConfig = ServerConstant.config.getDatabaseConfig(dbName);
            if (dbConfig == null) {
                result.put("ERROR", "Database configuration not found: " + dbName);
                return;
            }

            if (!procedureList.containsKey(datasetName)) {
                result.put("ERROR", "Dataset not found: " + datasetName);
                return;
            }

            HashMap<String, Object> param = (HashMap<String, Object>) procedureList.get(datasetName);
            String sql = (String) param.get("SQL");

            // Преобразуем vars в карту параметров
            Map<String, Object> params = new HashMap<>();
            if (vars != null && vars.length() > 0) {
                Iterator<String> keys = vars.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object val = vars.get(key);

                    if (val instanceof JSONObject) {
                        JSONObject varOne = (JSONObject) val;
                        String value = varOne.optString("value", varOne.optString("defaultVal", ""));
                        params.put(key, value);
                    } else {
                        params.put(key, val.toString());
                    }
                }
            }

            System.out.println("Executing Oracle SQL: " + sql);
            System.out.println("With params: " + params);

            JSONArray dataArray = OracleQuery.executeQuery(dbConfig, sql, params);
            result.put("data", dataArray);

            if (debugMode) {
                result.put("SQL", sql);
                result.put("params", new JSONObject(params));
            }

        } catch (Exception e) {
            System.err.println("Error in Oracle query: " + e.getMessage());
            e.printStackTrace();
            result.put("ERROR", "Oracle query error: " + e.getMessage());
        }
    }

    /**
     * Выполняет запрос к PostgreSQL через функцию
     */
    private static void executePostgresQuery(HttpExchange query, JSONObject result, String fullDatasetName, JSONObject vars, boolean debugMode) {
        Connection conn = null;
        CallableStatement selectFunctionStatement = null;
        ResultSet rs = null;
        Map<String, Object> session = query.session;

        try {
            if (!procedureList.containsKey(fullDatasetName)) {
                result.put("ERROR", "Procedure not found: " + fullDatasetName);
                System.err.println("Procedure not found: " + fullDatasetName);
                return;
            }

            System.out.println("Found procedure in list: " + fullDatasetName);

            HashMap<String, Object> param = (HashMap<String, Object>) procedureList.get(fullDatasetName);
            Map<String, String> varTypes = (Map<String, String>) param.get("varTypes");
            DatabaseConfig dbConfig = (DatabaseConfig) param.get("dbConfig");

            // Получаем имя схемы из полного имени
            String schemaName = "public";
            String functionNameOnly = fullDatasetName;
            if (fullDatasetName.contains(".")) {
                schemaName = fullDatasetName.substring(0, fullDatasetName.lastIndexOf('.'));
                functionNameOnly = fullDatasetName.substring(fullDatasetName.lastIndexOf('.') + 1);
            }

            // Если dbConfig не сохранен в param, пытаемся получить из сессии или конфигурации
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
                    // Используем конфигурацию из ServerConstant
                    dbConfig = ServerConstant.config.getDatabaseConfig("default");
                }
            }

            if (dbConfig == null) {
                result.put("ERROR", "Database configuration not found");
                return;
            }

            // Устанавливаем правильную схему в URL
            String jdbcUrl = dbConfig.getJdbcUrl();
            if (!jdbcUrl.contains("currentSchema")) {
                if (jdbcUrl.contains("?")) {
                    jdbcUrl += "&currentSchema=" + schemaName;
                } else {
                    jdbcUrl += "?currentSchema=" + schemaName;
                }
            }

            // Подключаемся с правильным search_path
            Class.forName("org.postgresql.Driver");
            Properties props = new Properties();
            props.setProperty("user", dbConfig.getUsername());
            props.setProperty("password", dbConfig.getPassword());
            props.setProperty("currentSchema", schemaName);

            conn = DriverManager.getConnection(jdbcUrl, props);
            conn.setAutoCommit(false);

            // Явно устанавливаем search_path
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET search_path TO " + schemaName + ", public");
                System.out.println("Search path set to: " + schemaName + ", public");
            } catch (SQLException e) {
                System.err.println("Error setting search path: " + e.getMessage());
            }

            String prepareCall = (String) param.get("prepareCall");
            System.out.println("PrepareCall: " + prepareCall);

            // Для функций, возвращающих JSON, используем SELECT
            selectFunctionStatement = conn.prepareCall(prepareCall);

            List<String> varsArr = (List<String>) param.get("vars");
            System.out.println("Vars array: " + varsArr);

            // Устанавливаем параметры (индексы с 1)
            int ind = 1;
            for (String varNameOne : varsArr) {
                String valueStr = "";
                String targetType = varTypes != null ? varTypes.getOrDefault(varNameOne, "string") : "string";

                if (vars.has(varNameOne)) {
                    Object varObj = vars.get(varNameOne);
                    if (varObj instanceof JSONObject) {
                        JSONObject varOne = (JSONObject) varObj;
                        valueStr = varOne.optString("value", varOne.optString("defaultVal", ""));
                    } else {
                        valueStr = varObj.toString();
                    }
                }

                System.out.println("Setting parameter " + ind + " (" + varNameOne + "): " + valueStr + " (type: " + targetType + ")");
                setParameter(selectFunctionStatement, ind, valueStr, targetType, conn);
                ind++;
            }
            System.out.println("Executing query...");
            boolean hasResults = selectFunctionStatement.execute();
            System.out.println("Query executed, hasResults: " + hasResults);
            if (hasResults) {
                rs = selectFunctionStatement.getResultSet();
                if (rs != null) {
                    JSONArray dataArray = new JSONArray();
                    ResultSetMetaData metaData = rs.getMetaData();
                    int columnCount = metaData.getColumnCount();
                    while (rs.next()) {
                        JSONObject row = new JSONObject();
                        for (int i = 1; i <= columnCount; i++) {
                            String columnName = metaData.getColumnLabel(i);
                            Object value = rs.getObject(i);
                            if (value == null) {
                                row.put(columnName, JSONObject.NULL);
                            } else {
                                row.put(columnName, value);
                            }
                        }
                        dataArray.put(row);
                    }
                    result.put("data", dataArray);
                }
            } else {
                try {
                    Object jsonResult = selectFunctionStatement.getObject(1);
                    if (jsonResult != null) {
                        String jsonString = jsonResult.toString();
                        System.out.println("JSON result from OUT parameter: " + jsonString);
                        try {
                            JSONArray dataArray = new JSONArray(jsonString);
                            result.put("data", dataArray);
                        } catch (Exception e) {
                            System.err.println("Error parsing JSON result: " + e.getMessage());
                            result.put("data", new JSONArray());
                        }
                    }
                } catch (SQLException e) {
                    // Игнорируем, если нет OUT параметра
                }
            }
            System.out.println("Query completed successfully");
        } catch (Exception e) {
            System.err.println("Error executing function: " + e.getMessage());
            e.printStackTrace();
            result.put("ERROR", "SQL Error: " + e.getMessage());
        } finally {
            try {
                if (rs != null) rs.close();
            } catch (Exception e) {}
            try {
                if (selectFunctionStatement != null) selectFunctionStatement.close();
            } catch (Exception e) {}
            try {
                if (conn != null) conn.close();
            } catch (Exception e) {}
        }
    }

    /**
     * Устанавливает параметр
     */
    private static void setParameter(CallableStatement cs, int index, String value, String type, Connection conn) throws SQLException {
        if (value == null || value.isEmpty()) {
            cs.setNull(index, Types.VARCHAR);
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
                case "json":
                case "jsonb":
                    cs.setObject(index, value, Types.OTHER);
                    break;
                case "array":
                    try {
                        JSONArray jsonArray = new JSONArray(value);
                        String[] stringArray = new String[jsonArray.length()];
                        for (int i = 0; i < jsonArray.length(); i++) {
                            stringArray[i] = jsonArray.getString(i);
                        }
                        Array array = conn.createArrayOf("text", stringArray);
                        cs.setArray(index, array);
                    } catch (Exception e) {
                        cs.setString(index, value);
                    }
                    break;
                default:
                    cs.setString(index, value);
                    break;
            }
        } catch (Exception e) {
            System.err.println("Error setting parameter: " + e.getMessage());
            cs.setString(index, value);
        }
    }

    /**
     * Регистрирует OUT параметр
     */
    private static void registerOutParameter(CallableStatement cs, int index, String type) throws SQLException {
        switch (type.toLowerCase()) {
            case "integer":
            case "int":
                cs.registerOutParameter(index, Types.INTEGER);
                break;
            case "bigint":
            case "long":
                cs.registerOutParameter(index, Types.BIGINT);
                break;
            case "decimal":
            case "numeric":
                cs.registerOutParameter(index, Types.NUMERIC);
                break;
            case "boolean":
            case "bool":
                cs.registerOutParameter(index, Types.BOOLEAN);
                break;
            case "date":
                cs.registerOutParameter(index, Types.DATE);
                break;
            case "timestamp":
                cs.registerOutParameter(index, Types.TIMESTAMP);
                break;
            case "json":
            case "jsonb":
                cs.registerOutParameter(index, Types.OTHER);
                break;
            case "array":
                cs.registerOutParameter(index, Types.ARRAY);
                break;
            default:
                cs.registerOutParameter(index, Types.VARCHAR);
                break;
        }
    }
}