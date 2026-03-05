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

import static ru.miacomsoft.EasyWebServer.PostgreQuery.*;

@SuppressWarnings("unchecked")
public class cmpAction extends Base {

    // Кэш для хранения информации о существовании функций в БД
    private static Map<String, Boolean> functionExistsCache = new HashMap<>();

    // Кэш для хранения информации о существовании схем в БД
    private static Map<String, Boolean> schemaExistsCache = new HashMap<>();

    // Кэш для хранения информации о существовании баз данных
    private static Map<String, Boolean> databaseExistsCache = new HashMap<>();

    // Временные метки для кэша БД
    private static Map<String, Long> databaseCheckTimestamp = new HashMap<>();

    // Время жизни кэша (60 секунд)
    private static final long CACHE_TTL = 60000;

    // Добавляем поле для хранения режима отладки
    private boolean debugMode = false;

    // Конструктор с тремя параметрами
    public cmpAction(Document doc, Element element, String tag) {
        super(doc, element, tag);
        // Сохраняем режим отладки из документа
        if (doc != null && doc.hasAttr("debug_mode")) {
            debugMode = Boolean.parseBoolean(doc.attr("debug_mode"));
        }
        initialize(doc, element);
    }

    // Конструктор с двумя параметрами
    public cmpAction(Document doc, Element element) {
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

        // Проверяем кэш с учетом времени жизни
        if (databaseExistsCache.containsKey(cacheKey)) {
            Long timestamp = databaseCheckTimestamp.get(cacheKey);
            if (timestamp != null && (System.currentTimeMillis() - timestamp) < CACHE_TTL) {
                return databaseExistsCache.get(cacheKey);
            }
        }

        Connection conn = null;
        try {
            // Создаем конфигурацию для подключения к системной БД postgres
            DatabaseConfig adminConfig = createAdminDatabaseConfig(dbConfig, "postgres");

            conn = getConnectionFromConfig(adminConfig);
            if (conn == null) {
                System.err.println("Cannot connect to postgres database for DB check");
                return false;
            }

            // Проверяем существование базы данных
            String sql = "SELECT 1 FROM pg_database WHERE datname = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, dbName);
                try (ResultSet rs = pstmt.executeQuery()) {
                    boolean exists = rs.next();

                    // Обновляем кэш
                    databaseExistsCache.put(cacheKey, exists);
                    databaseCheckTimestamp.put(cacheKey, System.currentTimeMillis());

                    return exists;
                }
            }
        } catch (SQLException e) {
            System.err.println("Error checking database existence: " + e.getMessage());
        } finally {
            releaseConnection(conn);
        }

        // В случае ошибки считаем, что БД не существует, но не кэшируем надолго
        databaseExistsCache.put(cacheKey, false);
        databaseCheckTimestamp.put(cacheKey, System.currentTimeMillis());
        return false;
    }

    /**
     * Создание базы данных PostgreSQL (исправленная версия)
     */
    private boolean createDatabase(String dbName, DatabaseConfig dbConfig) {
        Connection conn = null;
        Statement stmt = null;
        try {
            // Подключаемся к системной БД postgres для создания новой БД
            DatabaseConfig adminConfig = createAdminDatabaseConfig(dbConfig, "postgres");

            // Получаем соединение и отключаем auto-commit для CREATE DATABASE
            Class.forName(adminConfig.getDriver());
            Properties props = new Properties();
            props.setProperty("user", adminConfig.getUsername());
            props.setProperty("password", adminConfig.getPassword());
            props.setProperty("connectTimeout", "10");
            props.setProperty("socketTimeout", "30");

            conn = DriverManager.getConnection(adminConfig.getJdbcUrl(), props);

            // Важно: CREATE DATABASE нельзя выполнять в транзакции
            conn.setAutoCommit(true);

            // Определяем владельца БД (обычно тот же пользователь)
            String owner = dbConfig.getUsername();

            // Экранируем имена
            String escapedDbName = dbName.replace("\"", "\"\"");
            String escapedOwner = owner.replace("\"", "\"\"");

            // Создаем базу данных с правильной кодировкой
            String sql = String.format(
                    "CREATE DATABASE \"%s\" WITH OWNER = \"%s\" ENCODING = 'UTF8' LC_COLLATE = 'C' LC_CTYPE = 'C' TEMPLATE template0",
                    escapedDbName, escapedOwner
            );

            System.out.println("Creating database with SQL: " + sql);

            stmt = conn.createStatement();
            stmt.executeUpdate(sql);

            // Принудительно коммитим (хотя для CREATE DATABASE это не нужно)
            try {
                conn.commit();
            } catch (SQLException e) {
                // Игнорируем
            }

            // Обновляем кэш
            String cacheKey = "db:" + dbName;
            databaseExistsCache.put(cacheKey, true);
            databaseCheckTimestamp.put(cacheKey, System.currentTimeMillis());

            System.out.println("Database created successfully: " + dbName);
            return true;

        } catch (Exception e) {
            System.err.println("Error creating database: " + e.getMessage());
            e.printStackTrace();

            // Если ошибка из-за того, что БД уже существует (race condition)
            if (e.getMessage() != null &&
                    (e.getMessage().contains("already exists") ||
                            e.getMessage().contains("duplicate database"))) {
                String cacheKey = "db:" + dbName;
                databaseExistsCache.put(cacheKey, true);
                databaseCheckTimestamp.put(cacheKey, System.currentTimeMillis());
                System.out.println("Database already exists: " + dbName);
                return true;
            }

            return false;
        } finally {
            // Закрываем ресурсы
            if (stmt != null) {
                try { stmt.close(); } catch (SQLException e) {}
            }
            if (conn != null) {
                try { conn.close(); } catch (SQLException e) {}
            }
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

    /**
     * Вспомогательный метод для проверки наличия var элементов
     */
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

    // Общая логика инициализации
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

                // Шаг 1: Проверяем и создаем базу данных
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

                // Шаг 2: Проверяем и создаем схему
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
            // Для Java запросов устанавливаем значения по умолчанию
            System.out.println("Java mode detected, skipping database initialization");
        }

        // Добавляем атрибуты в результирующий элемент
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

        // Формирование имени функции (общее для Java и SQL)
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
        String functionName = (pathHash + "_" + fileName + "_" + element.attr("name")).toLowerCase();

        if (functionName.length() > 0 && Character.isDigit(functionName.charAt(0))) {
            functionName = "f_" + functionName;
        }

        if (functionName.length() > 60) {
            functionName = functionName.substring(0, 60);
        }

        this.attr("style", "display:none");
        this.attr("action_name", functionName);
        this.attr("name", element.attr("name"));

        // Обработка переменных (общая для Java и SQL)
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
            } else if (tagName.indexOf("var") != -1 || tagName.indexOf("cmpactionvar") != -1) {
                String nameItem = attrsItem.get("name");
                String src = RemoveArrKeyRtrn(attrsItem, "src", nameItem);
                String srctype = RemoveArrKeyRtrn(attrsItem, "srctype", "");
                String len = RemoveArrKeyRtrn(attrsItem, "len", "");
                String defaultVal = RemoveArrKeyRtrn(attrsItem, "default", "");

                jsonVar.append("'" + nameItem + "':{");
                jsonVar.append("'src':'" + src + "',");
                jsonVar.append("'srctype':'" + srctype + "'");
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

        // Обработка тела компонента в зависимости от типа
        if (element.hasText()) {
            if (query_type.equals("java")) {
                // Для Java - только компиляция, без работы с БД
                System.out.println("Java mode: compiling function " + functionName);
                JSONObject infoCompile = new JSONObject();
                if (!ServerResourceHandler.javaStrExecut.compile(functionName, importPacket, jarResourse, element.text().trim(), infoCompile)) {
                    this.removeAttr("style");
                    this.html(ru.miacomsoft.EasyWebServer.JavaStrExecut.parseErrorCompile(infoCompile));
                    return;
                }
                System.out.println("Java function compiled successfully: " + functionName);

                // Проверяем наличие функции с учетом префикса APP_NAME
                String fullFunctionName = ServerConstant.config.APP_NAME + "_" + functionName;
                System.out.println("Java function exists check: " + ServerResourceHandler.javaStrExecut.existJavaFunction(fullFunctionName));

            } else if (query_type.equals("sql")) {
                // Для Oracle не создаем процедуры, сохраняем для прямого выполнения
                if ("oci8".equals(dbType)) {
                    System.out.println("Oracle detected, using direct SQL for action: " + functionName);

                    // Собираем информацию о переменных
                    List<String> varsArr = new ArrayList<>();
                    Map<String, String> varTypes = new HashMap<>();
                    Map<String, String> varDirections = new HashMap<>(); // IN, OUT, INOUT

                    for (int numChild = 0; numChild < element.childrenSize(); numChild++) {
                        Element itemElement = element.child(numChild);
                        String tagNameItem = itemElement.tag().toString().toLowerCase();

                        if (tagNameItem.indexOf("var") != -1 || tagNameItem.indexOf("cmpactionvar") != -1) {
                            Attributes attrsItem = itemElement.attributes();
                            String nameItem = RemoveArrKeyRtrn(attrsItem, "name", "");
                            String type = RemoveArrKeyRtrn(attrsItem, "type", "string");
                            String direction = RemoveArrKeyRtrn(attrsItem, "direction", "IN");

                            varsArr.add(nameItem);
                            varTypes.put(nameItem, type);
                            varDirections.put(nameItem, direction.toUpperCase());
                        }
                    }

                    // Сохраняем параметры для выполнения - используем functionName без схемы
                    HashMap<String, Object> param = new HashMap<String, Object>();
                    param.put("SQL", element.text().trim());
                    param.put("vars", varsArr);
                    param.put("varTypes", varTypes);
                    param.put("varDirections", varDirections);
                    param.put("dbType", dbType);
                    param.put("dbName", dbName);

                    procedureList.put(functionName, param);
                    System.out.println("Oracle action saved with name: " + functionName);

                } else {
                    // Для PostgreSQL создаем процедуру
                    String fullFunctionName = pgSchema + "." + functionName;

                    boolean needToCreate = debugMode;

                    if (!needToCreate) {
                        Boolean exists = functionExistsCache.containsKey(fullFunctionName);
                        if (!exists) {
                            exists = checkFunctionExists(fullFunctionName, pgSchema, dbName, dbConfig);
                            functionExistsCache.put(fullFunctionName, exists);
                            System.out.println("Function " + fullFunctionName + " exists in DB: " + exists);
                        }
                        needToCreate = !exists;
                    }

                    if (needToCreate) {
                        createSQLFunctionPG(fullFunctionName, pgSchema, element, docPath + " (" + element.attr("name") + ")", debugMode, dbConfig);
                        functionExistsCache.put(fullFunctionName, true);
                    } else {
                        System.out.println("Function " + fullFunctionName + " already exists, skipping creation");

                        // Загружаем существующую процедуру в procedureList
                        HashMap<String, Object> param = new HashMap<String, Object>();
                        param.put("SQL", element.text().trim());

                        List<String> varsArr = new ArrayList<>();
                        Map<String, String> varTypes = new HashMap<>();

                        for (int numChild = 0; numChild < element.childrenSize(); numChild++) {
                            Element itemElement = element.child(numChild);
                            String tagNameItem = itemElement.tag().toString().toLowerCase();

                            if (tagNameItem.indexOf("var") != -1 || tagNameItem.indexOf("cmpactionvar") != -1) {
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

                        String prepareCall = "CALL " + pgSchema + "." + functionName + "(" + varsColl.toString() + ");";
                        param.put("prepareCall", prepareCall);

                        procedureList.put(fullFunctionName, param);
                        procedureList.put(functionName, param);

                        System.out.println("Loaded existing procedure into procedureList: " + fullFunctionName);
                    }
                }
            }
        }

        // Очищаем содержимое
        this.text("");

        // Сохраняем важные атрибуты перед удалением
        String savedQueryType = this.attr("query_type");
        String savedDbType = this.attr("db_type");
        String savedPgSchema = this.attr("pg_schema");
        String savedDb = this.attr("db");
        String savedActionName = this.attr("action_name");
        String savedName = this.attr("name");
        String savedVars = this.attr("vars");
        String savedStyle = this.attr("style");

        // Удаляем все атрибуты из исходного элемента
        for (Attribute attr : element.attributes().asList()) {
            if ("error".equals(attr.getKey())) continue;
            this.removeAttr(attr.getKey());
        }

        // Восстанавливаем важные атрибуты
        if (savedQueryType != null) this.attr("query_type", savedQueryType);
        if (savedDbType != null) this.attr("db_type", savedDbType);
        if (savedPgSchema != null) this.attr("pg_schema", savedPgSchema);
        if (savedDb != null) this.attr("db", savedDb);
        if (savedActionName != null) this.attr("action_name", savedActionName);
        if (savedName != null) this.attr("name", savedName);
        if (savedVars != null) this.attr("vars", savedVars);
        if (savedStyle != null) this.attr("style", savedStyle);

        // Автоматическое подключение JavaScript библиотеки для cmpAction
        if (doc != null) {
            Elements head = doc.getElementsByTag("head");

            // Проверяем, не подключена ли уже библиотека
            if (head != null && head.size() > 0) {
                Elements existingScripts = head.select("script[src*='cmpAction_js']");
                if (existingScripts.isEmpty()) {
                    // Добавляем ссылку на JS библиотеку
                    String jsPath = "{component}/cmpAction_js";
                    head.append("<script cmp=\"action-lib\" src=\"" + jsPath + "\" type=\"text/javascript\"></script>");
                    System.out.println("cmpAction: JavaScript library auto-included for action: " + name);
                }
            }
        }
    }

    /**
     * Проверка существования функции в БД с использованием EXISTS с учетом схемы
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
     * Вспомогательный метод для вычисления MD5 хэша
     * Возвращает строку, которая гарантированно начинается с буквы
     */
    private String getMd5Hash(String input) {
        if (input == null) return "f_empty";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            String hash = sb.toString();
            // Убеждаемся, что хэш не начинается с цифры
            if (hash.length() > 0 && Character.isDigit(hash.charAt(0))) {
                return "f" + hash;
            }
            return hash;
        } catch (Exception e) {
            return "f_" + Integer.toHexString(input.hashCode());
        }
    }

    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/json";
        Map<String, Object> session = query.session;
        JSONObject queryProperty = query.requestParam;
        JSONObject vars;
        String postBodyStr = new String(query.postCharBody);

        System.out.println("=== cmpAction onPage called ===");
        System.out.println("queryProperty: " + queryProperty.toString());
        System.out.println("postBodyStr: " + postBodyStr);

        // Парсим входные переменные - новый формат с объектами
        vars = new JSONObject();
        if (postBodyStr != null && !postBodyStr.isEmpty()) {
            try {
                // Пробуем распарсить как JSON объект
                JSONObject requestVars = new JSONObject(postBodyStr);
                // Проходим по всем ключам и копируем значения
                Iterator<String> keys = requestVars.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object val = requestVars.get(key);
                    if (val instanceof JSONObject) {
                        // Если значение уже объект, сохраняем как есть
                        vars.put(key, val);
                    } else {
                        // Если простое значение, создаем объект с value
                        JSONObject varObj = new JSONObject();
                        varObj.put("value", val.toString());
                        varObj.put("src", key);
                        varObj.put("srctype", "var");
                        vars.put(key, varObj);
                    }
                }
            } catch (Exception e) {
                System.err.println("Error parsing JSON: " + e.getMessage());
                // Если не удалось распарсить как JSON, пробуем старый формат
                if (postBodyStr.indexOf("{") == -1) {
                    int indParam = 0;
                    for (String par : postBodyStr.split("&")) {
                        String[] val = par.split("=");
                        JSONObject varObj = new JSONObject();
                        if (val.length == 2) {
                            varObj.put("value", val[1]);
                            varObj.put("src", val[0]);
                            varObj.put("srctype", "var");
                            vars.put(val[0], varObj);
                        } else {
                            indParam++;
                            varObj.put("value", val[0]);
                            varObj.put("src", "param" + indParam);
                            varObj.put("srctype", "var");
                            vars.put("param" + indParam, varObj);
                        }
                    }
                }
            }
        }

        System.out.println("Parsed vars: " + vars.toString());

        JSONObject result = new JSONObject();
        result.put("vars", vars); // Возвращаем vars в том же формате

        String query_type = queryProperty.optString("query_type", "java");
        String action_name = queryProperty.optString("action_name", "");
        String pg_schema = queryProperty.optString("pg_schema", "public");
        String db_name = queryProperty.optString("db", "DB");
        String db_type = queryProperty.optString("db_type", "jdbc");

        // Формируем полное имя функции со схемой
        String fullActionName;
        if (action_name.contains(".")) {
            fullActionName = action_name; // уже содержит схему
        } else {
            fullActionName = pg_schema + "." + action_name;
        }

        System.out.println("Action name: " + fullActionName);
        System.out.println("Query type: " + query_type);
        System.out.println("DB type: " + db_type);
        System.out.println("DB name: " + db_name);
        System.out.println("PG Schema: " + pg_schema);

        // Проверяем режим отладки из сессии
        boolean debugMode = false;
        if (query.session != null && query.session.containsKey("debug_mode")) {
            debugMode = (boolean) query.session.get("debug_mode");
        }

        // Для Java функций добавляем префикс APP_NAME
        String javaFunctionName = action_name;
        if (query_type.equals("java")) {
            javaFunctionName = ServerConstant.config.APP_NAME + "_" + action_name;
            System.out.println("Java function name with prefix: " + javaFunctionName);
        }

        if (ServerResourceHandler.javaStrExecut.existJavaFunction(javaFunctionName)) {
            // Обработка Java функции
            executeJavaAction(query, result, javaFunctionName, vars, session);
        } else if (query_type.equals("sql")) {
            // Для Oracle выполняем прямой SQL запрос
            if ("oci8".equals(db_type)) {
                executeOracleAction(query, result, action_name, db_name, vars, debugMode);
            } else {
                // Для PostgreSQL выполняем через процедуру
                executePostgresAction(query, result, fullActionName, vars, session, debugMode);
            }
        } else {
            result.put("ERROR", "Unsupported query type: " + query_type);
        }

        String resultText = result.toString();
        System.out.println("Action response: " + resultText);
        return resultText.getBytes();
    }

    /**
     * Выполняет Java-действие
     */
    private static void executeJavaAction(HttpExchange query, JSONObject result, String fullActionName, JSONObject vars, Map<String, Object> session) {
        try {
            // Подготавливаем переменные для Java функции - все как строки
            JSONObject varFun = new JSONObject();
            Iterator<String> keys = vars.keys();

            while (keys.hasNext()) {
                String key = keys.next();
                Object varValue = vars.get(key);

                if (varValue instanceof JSONObject) {
                    JSONObject varObj = (JSONObject) varValue;
                    // Всегда берем строковое значение
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

            // Вызываем Java функцию
            JSONObject resFun = ServerResourceHandler.javaStrExecut.runFunction(fullActionName, varFun, session, null);

            System.out.println("Java function result: " + resFun.toString());

            // Обрабатываем результаты
            if (resFun.has("JAVA_ERROR")) {
                // Если есть ошибка, добавляем её в результат
                result.put("ERROR", resFun.get("JAVA_ERROR"));
                // Возвращаем исходные vars без изменений, чтобы клиент не потерял данные
                result.put("vars", vars);
            } else {
                // Обрабатываем успешный результат
                keys = resFun.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object keyvalue = resFun.get(key);

                    if (key.equals("JAVA_ERROR")) {
                        continue;
                    }

                    // Обновляем значения в vars, сохраняя структуру
                    if (vars.has(key) && vars.get(key) instanceof JSONObject) {
                        // Сохраняем как строку
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

    /**
     * Выполняет прямой SQL запрос к Oracle (для действий)
     */
    private static void executeOracleAction(HttpExchange query, JSONObject result, String actionName, String dbName, JSONObject vars, boolean debugMode) {
        Connection conn = null;
        CallableStatement cs = null;
        Map<String, Object> session = query.session;

        try {
            // Получаем конфигурацию БД
            DatabaseConfig dbConfig = ServerConstant.config.getDatabaseConfig(dbName.equals("DB") ? null : dbName);
            if (dbConfig == null) {
                result.put("ERROR", "Database configuration not found: " + dbName);
                result.put("vars", vars);
                return;
            }

            // Получаем SQL из procedureList
            if (!procedureList.containsKey(actionName)) {
                result.put("ERROR", "Action not found: " + actionName);
                result.put("vars", vars);
                System.err.println("Action not found: " + actionName);
                return;
            }

            HashMap<String, Object> param = (HashMap<String, Object>) procedureList.get(actionName);
            String sql = (String) param.get("SQL");
            List<String> varsArr = (List<String>) param.get("vars");
            Map<String, String> varTypes = (Map<String, String>) param.get("varTypes");
            Map<String, String> varDirections = (Map<String, String>) param.get("varDirections");

            // Подключаемся к Oracle
            conn = OracleQuery.getConnect(dbConfig);
            if (conn == null) {
                result.put("ERROR", "Oracle connection failed");
                result.put("vars", vars);
                return;
            }

            System.out.println("Executing Oracle SQL: " + sql);
            System.out.println("Vars array: " + varsArr);

            // Подготавливаем вызов (для Oracle используем обычный CallableStatement)
            cs = conn.prepareCall(sql);

            // Регистрируем параметры
            int ind = 1;
            for (String varName : varsArr) {
                String valueStr = "";
                String targetType = varTypes != null ? varTypes.getOrDefault(varName, "string") : "string";
                String direction = varDirections != null ? varDirections.getOrDefault(varName, "IN") : "IN";

                // Получаем значение из vars
                if (vars.has(varName)) {
                    Object varObj = vars.get(varName);
                    if (varObj instanceof JSONObject) {
                        JSONObject varOne = (JSONObject) varObj;
                        if (varOne.optString("srctype").equals("session")) {
                            Object sessionVal = session.get(varName);
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

                System.out.println("Parameter " + ind + " (" + varName + "): " + valueStr +
                        " (type: " + targetType + ", direction: " + direction + ")");

                // Устанавливаем параметр в зависимости от направления
                if (direction.equals("IN") || direction.equals("INOUT")) {
                    setOracleParameter(cs, ind, valueStr, targetType);
                }

                if (direction.equals("OUT") || direction.equals("INOUT")) {
                    registerOracleOutParameter(cs, ind, targetType);
                }

                ind++;
            }

            // Выполняем
            System.out.println("Executing Oracle statement...");
            boolean hasResults = cs.execute();
            System.out.println("Oracle statement executed, hasResults: " + hasResults);

            // Получаем OUT параметры
            ind = 1;
            for (String varName : varsArr) {
                String direction = varDirections != null ? varDirections.getOrDefault(varName, "IN") : "IN";

                if (direction.equals("OUT") || direction.equals("INOUT")) {
                    String outParam = getOracleOutParameter(cs, ind, varTypes != null ? varTypes.get(varName) : "string");

                    System.out.println("OUT parameter " + ind + " (" + varName + "): " + outParam);

                    // Обновляем vars
                    if (vars.has(varName) && vars.get(varName) instanceof JSONObject) {
                        JSONObject varOne = vars.getJSONObject(varName);
                        if (varOne.optString("srctype").equals("session")) {
                            session.put(varName, outParam);
                        } else {
                            varOne.put("value", outParam);
                        }
                    } else {
                        JSONObject newVar = new JSONObject();
                        newVar.put("value", outParam);
                        newVar.put("src", varName);
                        newVar.put("srctype", "var");
                        vars.put(varName, newVar);
                    }
                }
                ind++;
            }

            result.put("vars", vars);

            // Если есть результаты (SELECT), обрабатываем их
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

    /**
     * Устанавливает параметр для Oracle CallableStatement с преобразованием типа
     */
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
                    // Предполагаем формат yyyy-MM-dd
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
                    // Для простоты передаем как строку
                    cs.setString(index, value);
                    break;
                case "string":
                default:
                    cs.setString(index, value);
                    break;
            }
        } catch (Exception e) {
            // В случае ошибки преобразования, передаем как строку
            System.err.println("Error converting parameter to " + type + ", using string: " + e.getMessage());
            cs.setString(index, value);
        }
    }


    /**
     * Регистрирует OUT параметр для Oracle
     */
    private static void registerOracleOutParameter(CallableStatement cs, int index, String type) throws SQLException {
        cs.registerOutParameter(index, getOracleSqlType(type));
    }

    /**
     * Получает OUT параметр из Oracle
     */
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

    /**
     * Возвращает SQL тип для Oracle
     */
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

    /**
     * Выполняет действие через PostgreSQL процедуру
     */
    private static void executePostgresAction(HttpExchange query, JSONObject result, String fullActionName, JSONObject vars, Map<String, Object> session, boolean debugMode) {
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

            // Получаем имя схемы из полного имени
            String schemaName = "public";
            String functionNameOnly = fullActionName;
            if (fullActionName.contains(".")) {
                schemaName = fullActionName.substring(0, fullActionName.lastIndexOf('.'));
                functionNameOnly = fullActionName.substring(fullActionName.lastIndexOf('.') + 1);
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
                    // Используем конфигурацию из параметров запроса
                    String dbName = query.requestParam.optString("db", "default");
                    dbConfig = ServerConstant.config.getDatabaseConfig(dbName);
                }
            }

            if (dbConfig == null) {
                result.put("ERROR", "Database configuration not found");
                result.put("vars", vars);
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

            cs = conn.prepareCall(prepareCall);

            List<String> varsArr = (List<String>) param.get("vars");
            System.out.println("Vars array: " + varsArr);

            // Регистрируем OUT параметры с соответствующими типами
            int ind = 0;
            for (String varName : varsArr) {
                ind++;
                String type = varTypes != null ? varTypes.getOrDefault(varName, "string") : "string";

                switch (type) {
                    case "integer":
                    case "int":
                        cs.registerOutParameter(ind, Types.INTEGER);
                        break;
                    case "bigint":
                    case "long":
                        cs.registerOutParameter(ind, Types.BIGINT);
                        break;
                    case "decimal":
                    case "numeric":
                        cs.registerOutParameter(ind, Types.NUMERIC);
                        break;
                    case "boolean":
                    case "bool":
                        cs.registerOutParameter(ind, Types.BOOLEAN);
                        break;
                    case "date":
                        cs.registerOutParameter(ind, Types.DATE);
                        break;
                    case "timestamp":
                        cs.registerOutParameter(ind, Types.TIMESTAMP);
                        break;
                    case "json":
                    case "jsonb":
                        cs.registerOutParameter(ind, Types.OTHER);
                        break;
                    case "array":
                        cs.registerOutParameter(ind, Types.ARRAY);
                        break;
                    case "string":
                    default:
                        cs.registerOutParameter(ind, Types.VARCHAR);
                        break;
                }
                System.out.println("Registered OUT parameter " + ind + " for var: " + varName + " with type: " + type);
            }

            if (debugMode) {
                result.put("SQL", ((String) param.get("SQL")).split("\n"));
            }

            // Устанавливаем IN параметры с преобразованием типов
            ind = 0;
            for (String varNameOne : varsArr) {
                ind++;
                String valueStr = "";
                String targetType = varTypes != null ? varTypes.getOrDefault(varNameOne, "string") : "string";

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

                System.out.println("Setting IN parameter " + ind + " (" + varNameOne + "): " + valueStr + " (type: " + targetType + ")");

                // Преобразуем значение в соответствии с целевым типом
                try {
                    switch (targetType) {
                        case "integer":
                        case "int":
                            if (valueStr.isEmpty()) {
                                cs.setNull(ind, Types.INTEGER);
                            } else {
                                cs.setInt(ind, Integer.parseInt(valueStr));
                            }
                            break;
                        case "bigint":
                        case "long":
                            if (valueStr.isEmpty()) {
                                cs.setNull(ind, Types.BIGINT);
                            } else {
                                cs.setLong(ind, Long.parseLong(valueStr));
                            }
                            break;
                        case "decimal":
                        case "numeric":
                            if (valueStr.isEmpty()) {
                                cs.setNull(ind, Types.NUMERIC);
                            } else {
                                cs.setBigDecimal(ind, new java.math.BigDecimal(valueStr));
                            }
                            break;
                        case "boolean":
                        case "bool":
                            if (valueStr.isEmpty()) {
                                cs.setNull(ind, Types.BOOLEAN);
                            } else {
                                cs.setBoolean(ind, Boolean.parseBoolean(valueStr));
                            }
                            break;
                        case "date":
                            if (valueStr.isEmpty()) {
                                cs.setNull(ind, Types.DATE);
                            } else {
                                cs.setDate(ind, java.sql.Date.valueOf(valueStr));
                            }
                            break;
                        case "timestamp":
                            if (valueStr.isEmpty()) {
                                cs.setNull(ind, Types.TIMESTAMP);
                            } else {
                                cs.setTimestamp(ind, Timestamp.valueOf(valueStr.replace("T", " ")));
                            }
                            break;
                        case "json":
                        case "jsonb":
                            if (valueStr.isEmpty()) {
                                cs.setNull(ind, Types.OTHER);
                            } else {
                                cs.setObject(ind, valueStr, Types.OTHER);
                            }
                            break;
                        case "array":
                            if (valueStr.isEmpty()) {
                                cs.setNull(ind, Types.ARRAY);
                            } else {
                                try {
                                    JSONArray jsonArray = new JSONArray(valueStr);
                                    String[] stringArray = new String[jsonArray.length()];
                                    for (int i = 0; i < jsonArray.length(); i++) {
                                        stringArray[i] = jsonArray.getString(i);
                                    }
                                    Array array = conn.createArrayOf("text", stringArray);
                                    cs.setArray(ind, array);
                                } catch (Exception e) {
                                    cs.setString(ind, valueStr);
                                }
                            }
                            break;
                        case "string":
                        default:
                            if (valueStr.isEmpty()) {
                                cs.setNull(ind, Types.VARCHAR);
                            } else {
                                cs.setString(ind, valueStr);
                            }
                            break;
                    }
                } catch (Exception e) {
                    System.err.println("Error converting parameter " + varNameOne + " to type " + targetType + ": " + e.getMessage());
                    cs.setString(ind, valueStr);
                }
            }

            // Выполняем процедуру
            System.out.println("Executing procedure...");
            cs.execute();
            System.out.println("Procedure executed successfully");

            // Получаем OUT параметры с преобразованием в строки для JSON
            ind = 0;
            for (String varNameOne : varsArr) {
                ind++;
                String outParam = "";
                String targetType = varTypes != null ? varTypes.getOrDefault(varNameOne, "string") : "string";

                try {
                    switch (targetType) {
                        case "integer":
                        case "int":
                            int intVal = cs.getInt(ind);
                            outParam = cs.wasNull() ? "" : String.valueOf(intVal);
                            break;
                        case "bigint":
                        case "long":
                            long longVal = cs.getLong(ind);
                            outParam = cs.wasNull() ? "" : String.valueOf(longVal);
                            break;
                        case "decimal":
                        case "numeric":
                            java.math.BigDecimal decimalVal = cs.getBigDecimal(ind);
                            outParam = decimalVal == null ? "" : decimalVal.toString();
                            break;
                        case "boolean":
                        case "bool":
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
                        case "json":
                        case "jsonb":
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
                            } else {
                                outParam = "";
                            }
                            break;
                        case "string":
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

                // Обновляем vars с результатами
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


    // Исправленный метод createSQLFunctionPG с поддержкой dbConfig
    private void createSQLFunctionPG(String functionName, String schema, Element element, String fileName, boolean debugMode, DatabaseConfig dbConfig) {
        // Очищаем имя функции от недопустимых символов
        String cleanFunctionName = functionName;
        if (functionName.contains(".")) {
            cleanFunctionName = functionName.substring(functionName.lastIndexOf('.') + 1);
        }
        cleanFunctionName = cleanFunctionName.replaceAll("[^a-zA-Z0-9_]", "");

        // Убеждаемся, что имя функции не начинается с цифры
        if (cleanFunctionName.length() > 0 && Character.isDigit(cleanFunctionName.charAt(0))) {
            cleanFunctionName = "f_" + cleanFunctionName;
        }

        // Формируем полное имя для сохранения
        String fullFunctionName = schema + "." + cleanFunctionName;

        if (procedureList.containsKey(fullFunctionName) && !debugMode) {
            System.out.println("Procedure " + fullFunctionName + " already exists in procedureList, skipping creation");
            return;
        }

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
            System.err.println("Cannot connect to database for creating procedure");
            return;
        }

        StringBuffer vars = new StringBuffer();
        StringBuffer varsColl = new StringBuffer();
        Attributes attrs = element.attributes();
        HashMap<String, Object> param = new HashMap<String, Object>();
        String language = RemoveArrKeyRtrn(attrs, "language", "plpgsql");
        param.put("language", language);
        List<String> varsArr = new ArrayList<>();
        Map<String, String> varTypes = new HashMap<>(); // Храним типы переменных
        String beforeCodeBloc = "";
        String afterCodeBloc = "";

        // Обрабатываем дочерние элементы для сбора информации о переменных
        for (int numChild = 0; numChild < element.childrenSize(); numChild++) {
            Element itemElement = element.child(numChild);
            String tagName = itemElement.tag().toString().toLowerCase();

            if (tagName.equals("before")) {
                // Блок BEFORE содержит код до основного запроса
                beforeCodeBloc = itemElement.text().trim();
                itemElement.text(""); // Очищаем после обработки
            } else if (tagName.equals("after")) {
                // Блок AFTER содержит код после основного запроса
                afterCodeBloc = itemElement.text().trim();
                itemElement.text(""); // Очищаем после обработки
            } else if (tagName.indexOf("var") != -1 || tagName.indexOf("cmpactionvar") != -1) {
                Attributes attrsItem = itemElement.attributes();
                String nameItem = RemoveArrKeyRtrn(attrsItem, "name", "");
                String src = RemoveArrKeyRtrn(attrsItem, "src", nameItem);
                String srctype = RemoveArrKeyRtrn(attrsItem, "srctype", "");
                String len = RemoveArrKeyRtrn(attrsItem, "len", "");
                String type = RemoveArrKeyRtrn(attrsItem, "type", ""); // string, integer, array, json

                // Определяем SQL тип
                String sqlType = "VARCHAR";

                if (!type.isEmpty()) {
                    // Если тип указан явно
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
                    // Автоматическое определение типа по len
                    if (len.length() > 0 && !len.equals("-1")) {
                        sqlType = "VARCHAR(" + len + ")";
                    } else if (len.equals("-1")) {
                        sqlType = "TEXT";
                    } else {
                        sqlType = "VARCHAR"; // По умолчанию VARCHAR
                    }
                }

                varsArr.add(nameItem);
                varTypes.put(nameItem, type.isEmpty() ? "string" : type.toLowerCase());

                // Для действий все параметры INOUT, чтобы можно было возвращать значения
                vars.append(nameItem);
                vars.append(" INOUT ");
                vars.append(sqlType);
                vars.append(",");
                varsColl.append("?,");

                System.out.println("Parameter " + nameItem + ": type=" + sqlType + ", direction=INOUT, len=" + len);
            }
        }

        // Убираем последнюю запятую
        String varsStr = vars.toString();
        if (varsStr.length() > 0) {
            varsStr = varsStr.substring(0, varsStr.length() - 1);
        }

        String varsCollStr = varsColl.toString();
        if (varsCollStr.length() > 0) {
            varsCollStr = varsCollStr.substring(0, varsCollStr.length() - 1);
        }

        param.put("vars", varsArr);
        param.put("varTypes", varTypes); // Сохраняем типы для использования в onPage

        // Формируем процедуру
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

        createProcedure(conn, schema + "." + cleanFunctionName, createProcedureSQL);

        String prepareCall = "CALL " + schema + "." + cleanFunctionName + "(" + varsCollStr + ");";

        try {
            CallableStatement cs = conn.prepareCall(prepareCall);
            int ind = 0;
            for (String varOne : varsArr) {
                ind++;
                // Регистрируем OUT параметры с соответствующим SQL типом
                String type = varTypes.getOrDefault(varOne, "string");
                switch (type) {
                    case "integer":
                    case "int":
                        cs.registerOutParameter(ind, Types.INTEGER);
                        break;
                    case "bigint":
                    case "long":
                        cs.registerOutParameter(ind, Types.BIGINT);
                        break;
                    case "decimal":
                    case "numeric":
                        cs.registerOutParameter(ind, Types.NUMERIC);
                        break;
                    case "boolean":
                    case "bool":
                        cs.registerOutParameter(ind, Types.BOOLEAN);
                        break;
                    case "date":
                        cs.registerOutParameter(ind, Types.DATE);
                        break;
                    case "timestamp":
                        cs.registerOutParameter(ind, Types.TIMESTAMP);
                        break;
                    case "json":
                    case "jsonb":
                        cs.registerOutParameter(ind, Types.OTHER);
                        break;
                    case "array":
                        cs.registerOutParameter(ind, Types.ARRAY);
                        break;
                    case "string":
                    default:
                        cs.registerOutParameter(ind, Types.VARCHAR);
                        break;
                }
                System.out.println("Registered OUT parameter " + ind + " for var: " + varOne + " with type: " + type);
            }
            param.put("CallableStatement", cs);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        param.put("connect", conn);
        param.put("varsArr", varsArr);
        param.put("SQL", createProcedureSQL);
        param.put("prepareCall", prepareCall);
        param.put("dbConfig", dbConfig); // Сохраняем конфигурацию для последующего использования

        // Сохраняем под полным именем со схемой
        procedureList.put(fullFunctionName, param);

        // Также сохраняем под именем без схемы для обратной совместимости
        procedureList.put(cleanFunctionName, param);

        System.out.println("Procedure " + fullFunctionName + " created successfully and saved to procedureList");
    }
}