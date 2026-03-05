package ru.miacomsoft.EasyWebServer;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.sql.Date;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Оптимизированный класс для работы с PostgreSQL
 * Добавлены: пул соединений, кэширование, улучшенная производительность
 */
public class PostgreQuery {

    // Пул соединений для каждой конфигурации БД
    private static final Map<String, ConnectionPool> connectionPools = new HashMap<>();

    // Кэш для обработанных SQL запросов
    private static final Map<String, ParsedSql> sqlCache = new LRUCache<>(1000);

    // Кэш для метаданных таблиц
    private static final Map<String, TableMetadata> metadataCache = new LRUCache<>(500);

    // Кэш для prepared statements (по SQL + схема)
    private static final Map<String, PreparedStatementCache> statementCache = new LRUCache<>(200);

    // Флаг отладки
    private static boolean DEBUG = false;

    // Размер пула соединений по умолчанию
    private static final int DEFAULT_MIN_POOL_SIZE = 2;
    private static final int DEFAULT_MAX_POOL_SIZE = 20;

    // Таймаут получения соединения из пула
    private static final int CONNECTION_TIMEOUT = 5000;

    // Максимальное количество prepared statements в кэше
    private static final int MAX_STATEMENTS_PER_CONNECTION = 50;

    // Статическая карта процедур (сохраняем для обратной совместимости)
    public static HashMap<String, HashMap<String, Object>> procedureList = new HashMap<>();


    private static final ExecutorService asyncExecutor =
            Executors.newFixedThreadPool(
                    Math.max(2, Runtime.getRuntime().availableProcessors() * 2)
            );

    private static final Map<String, CompletableFuture<JSONArray>> asyncQueryCache =
            new ConcurrentHashMap<>();

    private static final ScheduledExecutorService cacheCleaner =
            Executors.newSingleThreadScheduledExecutor();


    private static final Map<String, CachedResultSetMeta> resultSetMetaCache =
            new ConcurrentHashMap<>();

    static {
        // Очистка кэша асинхронных запросов каждые 5 минут
        cacheCleaner.scheduleAtFixedRate(() -> {
            asyncQueryCache.clear();
        }, 5, 5, TimeUnit.MINUTES);

        // Очистка кэша метаданных каждые 10 минут
        cacheCleaner.scheduleAtFixedRate(() -> {
            resultSetMetaCache.entrySet().removeIf(entry ->
                    !entry.getValue().isValid());
        }, 10, 10, TimeUnit.MINUTES);
    }

    /**
     * Установка режима отладки
     */
    public static void setDebug(boolean debug) {
        DEBUG = debug;
    }

    /**
     * Функция подключения к PostgreSQL (с пулом соединений)
     */
    public static Connection getConnect(String userName, String userPass) {
        return getConnect(userName, userPass, (String) null);
    }

    /**
     * Функция подключения к PostgreSQL с указанием конкретной БД из конфигурации
     */
    public static Connection getConnect(String userName, String userPass, String dbName) {
        DatabaseConfig dbConfig = ServerConstant.config.getDatabaseConfig(dbName);

        if (dbConfig != null) {
            return getConnectFromConfig(dbConfig, userName, userPass);
        }

        // Используем стандартную конфигурацию
        return getConnectFromUrl(ServerConstant.config.DATABASE_NAME, userName, userPass);
    }

    /**
     * Подключение через DatabaseConfig
     */
    private static Connection getConnectFromConfig(DatabaseConfig dbConfig, String userName, String userPass) {
        String poolKey = generatePoolKey(dbConfig, userName);
        ConnectionPool pool = connectionPools.get(poolKey);

        if (pool == null) {
            synchronized (PostgreQuery.class) {
                pool = connectionPools.get(poolKey);
                if (pool == null) {
                    pool = new ConnectionPool(dbConfig, userName, userPass,
                            DEFAULT_MIN_POOL_SIZE, DEFAULT_MAX_POOL_SIZE);
                    connectionPools.put(poolKey, pool);
                }
            }
        }

        try {
            return pool.getConnection();
        } catch (Exception e) {
            if (DEBUG) {
                System.err.println("Error getting connection from pool: " + e.getMessage());
            }
            return createDirectConnection(dbConfig, userName, userPass);
        }
    }

    /**
     * Подключение по URL
     */
    private static Connection getConnectFromUrl(String url, String userName, String userPass) {
        String poolKey = url + "|" + userName;
        ConnectionPool pool = connectionPools.get(poolKey);

        if (pool == null) {
            synchronized (PostgreQuery.class) {
                pool = connectionPools.get(poolKey);
                if (pool == null) {
                    pool = new ConnectionPool(url, userName, userPass,
                            DEFAULT_MIN_POOL_SIZE, DEFAULT_MAX_POOL_SIZE);
                    connectionPools.put(poolKey, pool);
                }
            }
        }

        try {
            return pool.getConnection();
        } catch (Exception e) {
            if (DEBUG) {
                System.err.println("Error getting connection from pool: " + e.getMessage());
            }
            return createDirectConnection(url, userName, userPass);
        }
    }

    /**
     * Создание прямого соединения
     */
    private static Connection createDirectConnection(String url, String userName, String userPass) {
        try {
            Class.forName("org.postgresql.Driver");
            Connection conn = DriverManager.getConnection(url, userName, userPass);
            conn.setAutoCommit(false);
            return conn;
        } catch (Exception e) {
            if (DEBUG) {
                System.err.println("Direct connection failed: " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Создание прямого соединения из конфигурации
     */
    private static Connection createDirectConnection(DatabaseConfig dbConfig, String userName, String userPass) {
        try {
            Class.forName("org.postgresql.Driver");
            Connection conn = DriverManager.getConnection(
                    dbConfig.getJdbcUrl(),
                    userName != null ? userName : dbConfig.getUsername(),
                    userPass != null ? userPass : dbConfig.getPassword()
            );
            conn.setAutoCommit(false);

            if (dbConfig.getSchema() != null && !dbConfig.getSchema().isEmpty()) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SET search_path TO '" + dbConfig.getSchema() + "'");
                }
            }

            return conn;
        } catch (Exception e) {
            if (DEBUG) {
                System.err.println("Direct connection failed: " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Генерация ключа для пула
     */
    private static String generatePoolKey(DatabaseConfig dbConfig, String userName) {
        return dbConfig.getHost() + ":" +
                dbConfig.getPort() + ":" +
                dbConfig.getDatabase() + ":" +
                (userName != null ? userName : dbConfig.getUsername());
    }

    /**
     * getConnect с JSONObject (для обратной совместимости)
     */
    public static Connection getConnect(String userName, String userPass, JSONObject info) {
        Connection conn = getConnect(userName, userPass);
        if (conn == null && info != null) {
            info.put("error", "Database connection failed");
        }
        return conn;
    }

    /**
     * Получение версии PostgreSQL (оптимизировано)
     */
    public static String getVersionPostgres(String userName, String userPass) {
        Connection conn = null;
        Statement st = null;
        ResultSet rs = null;

        try {
            conn = getConnect(userName, userPass);
            if (conn == null) return "[]";

            st = conn.createStatement();
            rs = st.executeQuery("SELECT json_agg(version())");

            if (rs.next()) {
                return rs.getString(1);
            }
        } catch (Exception e) {
            if (DEBUG) {
                System.err.println("Error getting version: " + e.getMessage());
            }
            return "[" + new JSONObject().put("error", e.getMessage()).toString() + "]";
        } finally {
            closeResources(rs, st);
            releaseConnection(conn);
        }

        return "[]";
    }

    /**
     * Выполнение SQL запроса с возвратом JSONArray
     */
    public static JSONArray executeQuery(String sql, String userName, String userPass) {
        return executeQuery(sql, null, userName, userPass);
    }

    /**
     * Выполнение SQL запроса с параметрами
     */
    public static JSONArray executeQuery(String sql, Map<String, Object> params, String userName, String userPass) {
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try {
            conn = getConnect(userName, userPass);
            if (conn == null) {
                return createErrorResult("Database connection failed");
            }

            ParsedSql parsedSql = getParsedSql(sql);
            pstmt = prepareStatement(conn, parsedSql.processedSql);

            if (params != null && !params.isEmpty()) {
                setParametersOptimized(pstmt, parsedSql.paramNames, params);
            }

            rs = pstmt.executeQuery();
            return resultSetToJSONOptimized(rs);

        } catch (SQLException e) {
            if (DEBUG) {
                System.err.println("Query error: " + e.getMessage());
            }
            return createErrorResult(e.getMessage());
        } finally {
            closeResources(rs, pstmt);
            releaseConnection(conn);
        }
    }

    /**
     * Выполнение запроса с указанием конкретной БД
     */
    public static JSONArray executeQuery(String sql, Map<String, Object> params, String dbName, String userName, String userPass) {
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try {
            conn = getConnect(userName, userPass, dbName);
            if (conn == null) {
                return createErrorResult("Database connection failed for: " + dbName);
            }

            ParsedSql parsedSql = getParsedSql(sql);
            pstmt = prepareStatement(conn, parsedSql.processedSql);

            if (params != null && !params.isEmpty()) {
                setParametersOptimized(pstmt, parsedSql.paramNames, params);
            }

            rs = pstmt.executeQuery();
            return resultSetToJSONOptimized(rs);

        } catch (SQLException e) {
            if (DEBUG) {
                System.err.println("Query error on " + dbName + ": " + e.getMessage());
            }
            return createErrorResult(e.getMessage());
        } finally {
            closeResources(rs, pstmt);
            releaseConnection(conn);
        }
    }

    /**
     * Выполнение обновления (INSERT, UPDATE, DELETE)
     */
    public static int executeUpdate(String sql, Map<String, Object> params, String userName, String userPass) {
        Connection conn = null;
        PreparedStatement pstmt = null;

        try {
            conn = getConnect(userName, userPass);
            if (conn == null) return -1;

            ParsedSql parsedSql = getParsedSql(sql);
            pstmt = prepareStatement(conn, parsedSql.processedSql);

            if (params != null && !params.isEmpty()) {
                setParametersOptimized(pstmt, parsedSql.paramNames, params);
            }

            int result = pstmt.executeUpdate();
            conn.commit();
            return result;

        } catch (SQLException e) {
            rollbackQuietly(conn);
            if (DEBUG) {
                System.err.println("Update error: " + e.getMessage());
            }
            return -1;
        } finally {
            closeResources(null, pstmt);
            releaseConnection(conn);
        }
    }

    /**
     * Получение разобранного SQL из кэша
     */
    private static ParsedSql getParsedSql(String sql) {
        ParsedSql parsed = sqlCache.get(sql);
        if (parsed == null) {
            parsed = parseNamedParameters(sql);
            sqlCache.put(sql, parsed);
        }
        return parsed;
    }

    /**
     * Подготовка statement с кэшированием
     */
    private static PreparedStatement prepareStatement(Connection conn, String sql) throws SQLException {
        if (conn instanceof PooledConnection) {
            String cacheKey = sql.hashCode() + "|" + conn.hashCode();
            PreparedStatementCache cached = statementCache.get(cacheKey);

            if (cached != null && cached.isValid(conn)) {
                return cached.statement;
            }

            PreparedStatement pstmt = conn.prepareStatement(sql);
            statementCache.put(cacheKey, new PreparedStatementCache(pstmt, conn));
            return pstmt;
        }

        return conn.prepareStatement(sql);
    }

    /**
     * Парсинг именованных параметров PostgreSQL (:param)
     */
    private static ParsedSql parseNamedParameters(String sql) {
        if (sql == null || sql.isEmpty()) {
            return new ParsedSql(sql, Collections.emptyList());
        }

        StringBuilder processedSql = new StringBuilder(sql.length() + 16);
        List<String> paramNames = new ArrayList<>();
        StringBuilder paramName = new StringBuilder();
        boolean inParam = false;
        boolean inQuote = false;
        boolean inDollarQuote = false;
        char quoteChar = 0;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            if ((c == '\'' || c == '"') && (i == 0 || sql.charAt(i - 1) != '\\')) {
                if (!inQuote && !inDollarQuote) {
                    inQuote = true;
                    quoteChar = c;
                } else if (inQuote && quoteChar == c) {
                    inQuote = false;
                }
            }

            if (c == '$' && i + 1 < sql.length() && Character.isLetter(sql.charAt(i + 1))) {
                inDollarQuote = !inDollarQuote;
            }

            if (inQuote || inDollarQuote) {
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

    /**
     * Оптимизированная установка параметров
     */
    private static void setParametersOptimized(PreparedStatement pstmt,
                                               List<String> paramNames,
                                               Map<String, Object> params) throws SQLException {
        for (int i = 0; i < paramNames.size(); i++) {
            String name = paramNames.get(i);
            Object value = params.get(name);

            if (value == null) {
                pstmt.setNull(i + 1, Types.VARCHAR);
                continue;
            }

            Class<?> valueClass = value.getClass();

            if (valueClass == String.class) {
                pstmt.setString(i + 1, (String) value);
            } else if (valueClass == Integer.class) {
                pstmt.setInt(i + 1, (Integer) value);
            } else if (valueClass == Long.class) {
                pstmt.setLong(i + 1, (Long) value);
            } else if (valueClass == Double.class) {
                pstmt.setDouble(i + 1, (Double) value);
            } else if (valueClass == Boolean.class) {
                pstmt.setBoolean(i + 1, (Boolean) value);
            } else if (value instanceof Date) {
                pstmt.setTimestamp(i + 1, new Timestamp(((Date) value).getTime()));
            } else if (value instanceof JSONObject) {
                pstmt.setObject(i + 1, value.toString(), Types.OTHER);
            } else {
                pstmt.setString(i + 1, value.toString());
            }
        }
    }

    /**
     * Оптимизированное преобразование ResultSet в JSONArray с кэшированием метаданных
     */
    private static JSONArray resultSetToJSONOptimized(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        String cacheKey = generateMetaCacheKey(metaData);

        CachedResultSetMeta cachedMeta = resultSetMetaCache.get(cacheKey);
        if (cachedMeta == null || !cachedMeta.isValid()) {
            cachedMeta = new CachedResultSetMeta(metaData);
            resultSetMetaCache.put(cacheKey, cachedMeta);
        }

        JSONArray result = new JSONArray();
        int columnCount = cachedMeta.columnNames.length;

        while (rs.next()) {
            JSONObject row = new JSONObject();
            for (int i = 0; i < columnCount; i++) {
                String columnName = cachedMeta.columnNames[i];
                int type = cachedMeta.columnTypes[i];

                Object value = getValueByTypeFast(rs, i + 1, type);

                if (value == null) {
                    row.put(columnName, JSONObject.NULL);
                } else {
                    row.put(columnName, value);
                }
            }
            result.put(row);
        }

        return result;
    }

    /**
     * Генерация ключа кэша для метаданных
     */
    private static String generateMetaCacheKey(ResultSetMetaData meta) throws SQLException {
        StringBuilder key = new StringBuilder();
        int count = meta.getColumnCount();

        for (int i = 1; i <= count; i++) {
            if (i > 1) key.append('|');
            key.append(meta.getColumnLabel(i))
                    .append(':')
                    .append(meta.getColumnType(i));

            try {
                String tableName = meta.getTableName(i);
                if (tableName != null && !tableName.isEmpty()) {
                    key.append('@').append(tableName);
                }
            } catch (SQLException e) {
                // Игнорируем
            }
        }

        return key.toString();
    }

    /**
     * Быстрое получение значения по типу
     */
    private static Object getValueByTypeFast(ResultSet rs, int index, int type) throws SQLException {
        switch (type) {
            case Types.VARCHAR:
            case Types.CHAR:
            case Types.LONGVARCHAR:
                return rs.getString(index);

            case Types.INTEGER:
                int intVal = rs.getInt(index);
                return rs.wasNull() ? null : intVal;

            case Types.BIGINT:
                long longVal = rs.getLong(index);
                return rs.wasNull() ? null : longVal;

            case Types.NUMERIC:
            case Types.DECIMAL:
                return rs.getBigDecimal(index);

            case Types.DOUBLE:
            case Types.FLOAT:
                double doubleVal = rs.getDouble(index);
                return rs.wasNull() ? null : doubleVal;

            case Types.BOOLEAN:
            case Types.BIT:
                boolean boolVal = rs.getBoolean(index);
                return rs.wasNull() ? null : boolVal;

            case Types.DATE:
                Date date = rs.getDate(index);
                return date != null ? date.toString() : null;

            case Types.TIMESTAMP:
                Timestamp ts = rs.getTimestamp(index);
                return ts != null ? ts.toString() : null;

            case Types.OTHER:
                Object obj = rs.getObject(index);
                if (obj instanceof org.postgresql.util.PGobject) {
                    return ((org.postgresql.util.PGobject) obj).getValue();
                }
                return obj != null ? obj.toString() : null;

            case Types.ARRAY:
                Array array = rs.getArray(index);
                if (array != null) {
                    Object[] arrayObj = (Object[]) array.getArray();
                    JSONArray jsonArray = new JSONArray();
                    for (Object item : arrayObj) {
                        jsonArray.put(item != null ? item.toString() : null);
                    }
                    return jsonArray;
                }
                return null;

            default:
                Object objDefault = rs.getObject(index);
                return objDefault != null ? objDefault.toString() : null;
        }
    }

    /**
     * Получение значения по типу (для обратной совместимости)
     */
    private static Object getValueByType(ResultSet rs, int index, int type) throws SQLException {
        return getValueByTypeFast(rs, index, type);
    }

    /**
     * Создание JSON с ошибкой
     */
    private static JSONArray createErrorResult(String errorMessage) {
        JSONArray result = new JSONArray();
        JSONObject error = new JSONObject();
        error.put("error", errorMessage);
        result.put(error);
        return result;
    }

    /**
     * Создание процедуры (оптимизировано)
     */
    public static void createProcedure(Connection conn, String nameProcedure, String procText) {
        Statement stmt = null;
        try {
            stmt = conn.createStatement();
            stmt.execute("DROP PROCEDURE IF EXISTS " + nameProcedure);
            stmt.execute(procText);
            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly(conn);
            if (DEBUG) {
                System.err.println("Error creating procedure: " + e.getMessage());
            }
        } finally {
            closeResources(null, stmt);
        }
    }

    /**
     * Создание функции (оптимизировано)
     */
    public static void createFunction(Connection conn, String nameProcedure, String procText) {
        Statement stmt = null;
        try {
            stmt = conn.createStatement();
            stmt.execute("DROP FUNCTION IF EXISTS " + nameProcedure);
            stmt.execute(procText);
            conn.commit();
        } catch (SQLException e) {
            rollbackQuietly(conn);
            if (DEBUG) {
                System.err.println("Error creating function: " + e.getMessage());
            }
        } finally {
            closeResources(null, stmt);
        }
    }

    /**
     * Проверка существования таблицы
     */
    public static boolean tableExists(Connection conn, String tableName, String schema) {
        String cacheKey = (schema != null ? schema : "public") + "." + tableName.toLowerCase();
        TableMetadata metadata = metadataCache.get(cacheKey);

        if (metadata != null) {
            return metadata.exists;
        }

        String sql = "SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE ";
        List<Object> params = new ArrayList<>();

        if (schema != null && !schema.isEmpty()) {
            sql += "table_schema = ? AND ";
            params.add(schema);
        }
        sql += "table_name = ?)";
        params.add(tableName.toLowerCase());

        boolean exists = false;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try {
            pstmt = conn.prepareStatement(sql);
            for (int i = 0; i < params.size(); i++) {
                pstmt.setString(i + 1, (String) params.get(i));
            }
            rs = pstmt.executeQuery();
            if (rs.next()) {
                exists = rs.getBoolean(1);
            }
        } catch (SQLException e) {
            if (DEBUG) {
                System.err.println("Error checking table: " + e.getMessage());
            }
        } finally {
            closeResources(rs, pstmt);
        }

        metadataCache.put(cacheKey, new TableMetadata(cacheKey, exists));
        return exists;
    }

    /**
     * Очистка процедур веб-страниц (оптимизировано)
     */
    public void clearWebPageProcedure(Connection conn) {
        CallableStatement cs = null;
        try {
            String procName = "clear_" + ServerConstant.config.APP_NAME + "_proc";

            String createProc =
                    "CREATE OR REPLACE PROCEDURE " + procName + "() LANGUAGE plpgsql AS $$\n" +
                            "DECLARE\n" +
                            "    r RECORD;\n" +
                            "BEGIN\n" +
                            "    FOR r IN SELECT proname FROM pg_proc \n" +
                            "             WHERE pronamespace = 'public'::regnamespace\n" +
                            "             AND proname LIKE '" + ServerConstant.config.APP_NAME + "_%'\n" +
                            "    LOOP\n" +
                            "        EXECUTE 'DROP PROCEDURE IF EXISTS ' || r.proname || ' CASCADE';\n" +
                            "    END LOOP;\n" +
                            "END;\n" +
                            "$$;";

            createProcedure(conn, procName, createProc);

            cs = conn.prepareCall("CALL " + procName + "()");
            cs.execute();
            conn.commit();

        } catch (SQLException e) {
            rollbackQuietly(conn);
            if (DEBUG) {
                System.err.println("Error clearing procedures: " + e.getMessage());
            }
        } finally {
            closeResources(null, cs);
        }
    }

    /**
     * Закрытие ресурсов
     */
    private static void closeResources(ResultSet rs, Statement stmt) {
        try { if (rs != null) rs.close(); } catch (SQLException ignored) {}
        try { if (stmt != null) stmt.close(); } catch (SQLException ignored) {}
    }

    /**
     * Возврат соединения в пул
     */
    private static void releaseConnection(Connection conn) {
        if (conn == null) return;

        try {
            if (conn instanceof PooledConnection) {
                conn.close();
            } else {
                conn.close();
            }
        } catch (SQLException ignored) {}
    }

    /**
     * Откат транзакции без проброса исключения
     */
    private static void rollbackQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {}
        }
    }

    /**
     * Предварительная загрузка метаданных для часто используемых таблиц
     */
    public static void preloadMetadata(String tableName, String userName, String userPass) {
        Connection conn = null;
        try {
            conn = getConnect(userName, userPass);
            if (conn == null) return;

            String sql = "SELECT * FROM " + tableName + " LIMIT 0";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {

                ResultSetMetaData meta = rs.getMetaData();
                String cacheKey = generateMetaCacheKey(meta);
                CachedResultSetMeta cachedMeta = new CachedResultSetMeta(meta);
                resultSetMetaCache.put(cacheKey, cachedMeta);

                if (DEBUG) {
                    System.out.println("Preloaded metadata for table: " + tableName);
                }
            }
        } catch (SQLException e) {
            if (DEBUG) {
                System.err.println("Failed to preload metadata for " + tableName +
                        ": " + e.getMessage());
            }
        } finally {
            releaseConnection(conn);
        }
    }

    /**
     * Инвалидация кэша метаданных для таблицы
     */
    public static void invalidateMetadata(String tableName) {
        resultSetMetaCache.entrySet().removeIf(entry ->
                tableName.equals(entry.getValue().tableName));

        if (DEBUG) {
            System.out.println("Invalidated metadata cache for table: " + tableName);
        }
    }

    /**
     * Получение статистики кэша метаданных
     */
    public static JSONObject getMetadataCacheStats() {
        JSONObject stats = new JSONObject();
        stats.put("size", resultSetMetaCache.size());

        long validCount = resultSetMetaCache.values().stream()
                .filter(CachedResultSetMeta::isValid)
                .count();
        stats.put("valid_entries", validCount);

        JSONArray tables = new JSONArray();
        resultSetMetaCache.values().stream()
                .filter(meta -> meta.tableName != null)
                .map(meta -> meta.tableName)
                .distinct()
                .forEach(tables::put);
        stats.put("cached_tables", tables);

        return stats;
    }


    /**
     * Асинхронное выполнение запроса с кэшированием результатов
     */
    public static CompletableFuture<JSONArray> executeQueryAsync(
            String sql,
            Map<String, Object> params,
            String userName,
            String userPass,
            long cacheTimeMs) {

        String cacheKey = sql + "|" + (params != null ? params.hashCode() : "") +
                "|" + userName;

        if (cacheTimeMs > 0 && asyncQueryCache.containsKey(cacheKey)) {
            CompletableFuture<JSONArray> cached = asyncQueryCache.get(cacheKey);
            if (!cached.isCompletedExceptionally()) {
                return cached;
            }
        }

        CompletableFuture<JSONArray> future = CompletableFuture.supplyAsync(() ->
                executeQuery(sql, params, userName, userPass), asyncExecutor
        ).thenApply(result -> {
            if (result != null && result.length() > 0 &&
                    result.optJSONObject(0) != null &&
                    result.getJSONObject(0).has("error")) {
                throw new RuntimeException(result.getJSONObject(0).getString("error"));
            }
            return result;
        });

        if (cacheTimeMs > 0) {
            asyncQueryCache.put(cacheKey, future);
            future.thenRun(() -> {
                cacheCleaner.schedule(() -> {
                    asyncQueryCache.remove(cacheKey, future);
                }, cacheTimeMs, TimeUnit.MILLISECONDS);
            });
        }

        return future;
    }

    /**
     * Асинхронное выполнение нескольких запросов параллельно
     */
    public static CompletableFuture<List<JSONArray>> executeParallelQueries(
            List<QueryTask> queries,
            String userName,
            String userPass) {

        List<CompletableFuture<JSONArray>> futures = new ArrayList<>();

        for (QueryTask task : queries) {
            futures.add(CompletableFuture.supplyAsync(() ->
                            executeQuery(task.sql, task.params, userName, userPass),
                    asyncExecutor
            ));
        }

        return CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
        ).thenApply(v ->
                futures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toList())
        );
    }

    /**
     * Класс для описания задачи запроса
     */
    public static class QueryTask {
        public final String sql;
        public final Map<String, Object> params;

        public QueryTask(String sql, Map<String, Object> params) {
            this.sql = sql;
            this.params = params;
        }
    }

    /**
     * Асинхронное выполнение с таймаутом
     */
    public static CompletableFuture<JSONArray> executeQueryWithTimeout(
            String sql,
            Map<String, Object> params,
            String userName,
            String userPass,
            long timeout,
            TimeUnit unit) {

        CompletableFuture<JSONArray> future = CompletableFuture.supplyAsync(() ->
                executeQuery(sql, params, userName, userPass), asyncExecutor
        );

        return future.orTimeout(timeout, unit)
                .exceptionally(throwable -> {
                    if (throwable instanceof TimeoutException) {
                        JSONArray error = new JSONArray();
                        JSONObject errObj = new JSONObject();
                        errObj.put("error", "Query timeout after " + timeout + " " + unit);
                        error.put(errObj);
                        return error;
                    }
                    return createErrorResult(throwable.getMessage());
                });
    }

    /**
     * Асинхронное выполнение update операции
     */
    public static CompletableFuture<Integer> executeUpdateAsync(
            String sql,
            Map<String, Object> params,
            String userName,
            String userPass) {

        return CompletableFuture.supplyAsync(() ->
                executeUpdate(sql, params, userName, userPass), asyncExecutor
        );
    }

    /**
     * Закрытие пула асинхронных запросов
     */
    public static void shutdownAsyncExecutor() {
        asyncExecutor.shutdown();
        cacheCleaner.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
            if (!cacheCleaner.awaitTermination(5, TimeUnit.SECONDS)) {
                cacheCleaner.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
            cacheCleaner.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Потоковая передача результатов через Consumer
     */
    public static void streamQuery(
            String sql,
            Map<String, Object> params,
            String userName,
            String userPass,
            int batchSize,
            ResultSetConsumer consumer) {

        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try {
            conn = getConnect(userName, userPass);
            if (conn == null) {
                consumer.onError("Database connection failed");
                return;
            }

            conn.setAutoCommit(false);

            pstmt = conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY);
            pstmt.setFetchSize(batchSize);

            ParsedSql parsedSql = getParsedSql(sql);
            if (params != null && !params.isEmpty()) {
                setParametersOptimized(pstmt, parsedSql.paramNames, params);
            }

            rs = pstmt.executeQuery();

            int rowCount = 0;
            long startTime = System.currentTimeMillis();

            while (rs.next()) {
                JSONObject row = resultSetRowToJSONOptimized(rs);
                consumer.accept(row, rowCount);
                rowCount++;

                if (rowCount % 10000 == 0) {
                    conn.commit();

                    if (DEBUG) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        double rate = rowCount / (elapsed / 1000.0);
                        System.out.printf("Streamed %d rows at %.2f rows/sec%n",
                                rowCount, rate);
                    }
                }
            }

            conn.commit();
            consumer.onComplete(rowCount);

        } catch (SQLException e) {
            consumer.onError(e.getMessage());
            rollbackQuietly(conn);
        } finally {
            closeResources(rs, pstmt);
            releaseConnection(conn);
        }
    }

    /**
     * Потоковая передача с пагинацией (курсорная навигация)
     */
    public static class StreamingCursor implements AutoCloseable {
        private final Connection conn;
        private final PreparedStatement pstmt;
        private final ResultSet rs;
        private final int batchSize;
        private boolean hasMore = true;
        private int totalRead = 0;

        private StreamingCursor(Connection conn, PreparedStatement pstmt,
                                ResultSet rs, int batchSize) {
            this.conn = conn;
            this.pstmt = pstmt;
            this.rs = rs;
            this.batchSize = batchSize;
        }

        public static StreamingCursor create(
                String sql,
                Map<String, Object> params,
                String userName,
                String userPass,
                int batchSize) throws SQLException {

            Connection conn = getConnect(userName, userPass);
            if (conn == null) {
                throw new SQLException("Database connection failed");
            }

            conn.setAutoCommit(false);

            PreparedStatement pstmt = conn.prepareStatement(
                    sql,
                    ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY
            );
            pstmt.setFetchSize(batchSize);

            ParsedSql parsedSql = getParsedSql(sql);
            if (params != null && !params.isEmpty()) {
                setParametersOptimized(pstmt, parsedSql.paramNames, params);
            }

            ResultSet rs = pstmt.executeQuery();

            return new StreamingCursor(conn, pstmt, rs, batchSize);
        }

        public List<JSONObject> nextBatch() throws SQLException {
            if (!hasMore) return Collections.emptyList();

            List<JSONObject> batch = new ArrayList<>(batchSize);
            int count = 0;

            while (count < batchSize && rs.next()) {
                JSONObject row = resultSetRowToJSONOptimized(rs);
                batch.add(row);
                count++;
                totalRead++;
            }

            hasMore = !rs.isAfterLast() && count == batchSize;
            return batch;
        }

        public boolean hasMore() {
            return hasMore;
        }

        public int getTotalRead() {
            return totalRead;
        }

        @Override
        public void close() throws Exception {
            closeResources(rs, pstmt);
            releaseConnection(conn);
        }
    }

    /**
     * Утилита для экспорта больших результатов в CSV
     */
    public static void exportToCSV(
            String sql,
            Map<String, Object> params,
            String userName,
            String userPass,
            OutputStream outputStream,
            char delimiter) throws IOException {

        try (PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {

            streamQuery(sql, params, userName, userPass, 1000,
                    new ResultSetConsumer() {
                        private boolean headerWritten = false;
                        private String[] columnNames;

                        @Override
                        public void accept(JSONObject row, int rowNum) {
                            if (!headerWritten) {
                                columnNames = row.keySet().toArray(new String[0]);
                                StringBuilder header = new StringBuilder();
                                for (int i = 0; i < columnNames.length; i++) {
                                    if (i > 0) header.append(delimiter);
                                    header.append('"')
                                            .append(escapeCSV(columnNames[i]))
                                            .append('"');
                                }
                                writer.println(header);
                                headerWritten = true;
                            }

                            StringBuilder line = new StringBuilder();
                            for (int i = 0; i < columnNames.length; i++) {
                                if (i > 0) line.append(delimiter);
                                String value = row.optString(columnNames[i], "");
                                line.append('"').append(escapeCSV(value)).append('"');
                            }
                            writer.println(line);

                            if (rowNum % 1000 == 0) {
                                writer.flush();
                            }
                        }

                        private String escapeCSV(String s) {
                            return s.replace("\"", "\"\"");
                        }
                    }
            );

            writer.flush();
        }
    }

    /**
     * Интерфейс для потребителя результатов
     */
    public interface ResultSetConsumer {
        void accept(JSONObject row, int rowNum);
        default void onError(String error) {
            System.err.println("Streaming error: " + error);
        }
        default void onComplete(int totalRows) {
            if (DEBUG) System.out.println("Streaming complete: " + totalRows + " rows");
        }
    }

    /**
     * Оптимизированное преобразование одной строки ResultSet в JSONObject
     */
    private static JSONObject resultSetRowToJSONOptimized(ResultSet rs) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();

        JSONObject row = new JSONObject();
        for (int i = 1; i <= columnCount; i++) {
            String columnName = metaData.getColumnLabel(i);
            int type = metaData.getColumnType(i);

            Object value = getValueByTypeFast(rs, i, type);

            if (value == null) {
                row.put(columnName, JSONObject.NULL);
            } else {
                row.put(columnName, value);
            }
        }
        return row;
    }


    /**
     * Класс для хранения разобранного SQL
     */
    private static class ParsedSql {
        final String processedSql;
        final List<String> paramNames;

        ParsedSql(String processedSql, List<String> paramNames) {
            this.processedSql = processedSql;
            this.paramNames = paramNames;
        }
    }

    /**
     * Класс для хранения метаданных таблиц
     */
    private static class TableMetadata {
        final String key;
        final boolean exists;
        final long timestamp;

        TableMetadata(String key, boolean exists) {
            this.key = key;
            this.exists = exists;
            this.timestamp = System.currentTimeMillis();
        }
    }

    /**
     * Кэш для PreparedStatement
     */
    private static class PreparedStatementCache {
        final PreparedStatement statement;
        final Connection connection;
        final long timestamp;

        PreparedStatementCache(PreparedStatement statement, Connection connection) {
            this.statement = statement;
            this.connection = connection;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isValid(Connection currentConn) throws SQLException {
            return connection == currentConn &&
                    !statement.isClosed() &&
                    System.currentTimeMillis() - timestamp < 60000;
        }
    }

    /**
     * Кэшированные метаданные ResultSet
     */
    private static class CachedResultSetMeta {
        final String[] columnNames;
        final int[] columnTypes;
        final long timestamp;
        final String tableName;

        CachedResultSetMeta(ResultSetMetaData meta) throws SQLException {
            int count = meta.getColumnCount();
            this.columnNames = new String[count];
            this.columnTypes = new int[count];

            String table = null;
            try {
                if (count > 0) {
                    table = meta.getTableName(1);
                }
            } catch (SQLException e) {
                // Игнорируем
            }
            this.tableName = table;

            for (int i = 0; i < count; i++) {
                this.columnNames[i] = meta.getColumnLabel(i + 1);
                this.columnTypes[i] = meta.getColumnType(i + 1);
            }
            this.timestamp = System.currentTimeMillis();
        }

        boolean isValid() {
            return System.currentTimeMillis() - timestamp < 300000; // 5 минут
        }
    }

    /**
     * LRU кэш
     */
    private static class LRUCache<K, V> extends LinkedHashMap<K, V> {
        private final int maxSize;

        LRUCache(int maxSize) {
            super(maxSize + 1, 0.75f, true);
            this.maxSize = maxSize;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > maxSize;
        }
    }


    private static class ConnectionPool {
        private final ArrayBlockingQueue<PooledConnection> pool;
        private final String url;
        private final String userName;
        private final String userPass;
        private final int maxSize;
        private final AtomicInteger created = new AtomicInteger(0);
        private final ScheduledExecutorService keepAliveService;
        private final Map<Connection, Long> lastUsed = new ConcurrentHashMap<>();

        private static final int KEEPALIVE_INTERVAL = 30000;
        private static final int MAX_IDLE_TIME = 300000;
        private static final int VALIDATION_TIMEOUT = 3;

        // Конструктор для URL
        ConnectionPool(String url, String userName, String userPass, int minSize, int maxSize) {
            this.url = url;
            this.userName = userName;
            this.userPass = userPass;
            this.maxSize = maxSize;
            this.pool = new ArrayBlockingQueue<>(maxSize);

            for (int i = 0; i < minSize; i++) {
                try {
                    PooledConnection conn = createPooledConnection();
                    pool.offer(conn);
                    lastUsed.put(conn, System.currentTimeMillis());
                    created.incrementAndGet();
                } catch (Exception e) {
                    if (DEBUG) {
                        System.err.println("Failed to create initial connection: " +
                                e.getMessage());
                    }
                }
            }

            this.keepAliveService = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "DB-KeepAlive");
                t.setDaemon(true);
                return t;
            });

            this.keepAliveService.scheduleAtFixedRate(
                    this::performKeepAlive,
                    KEEPALIVE_INTERVAL,
                    KEEPALIVE_INTERVAL,
                    TimeUnit.MILLISECONDS
            );
        }

        // Конструктор для DatabaseConfig
        ConnectionPool(DatabaseConfig dbConfig, String userName, String userPass, int minSize, int maxSize) {
            this(dbConfig.getJdbcUrl(), userName, userPass, minSize, maxSize);
        }

        private PooledConnection createPooledConnection() throws SQLException {
            try {
                Class.forName("org.postgresql.Driver");

                // Настраиваем свойства соединения для таймаутов
                Properties props = new Properties();
                props.setProperty("user", userName);
                props.setProperty("password", userPass);
                props.setProperty("socketTimeout", "30"); // 30 секунд
                props.setProperty("connectTimeout", "10"); // 10 секунд
                props.setProperty("tcpKeepAlive", "true");

                Connection conn = DriverManager.getConnection(url, props);
                conn.setAutoCommit(false);

                // Выполняем простой запрос для инициализации соединения
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SELECT 1");
                }

                return new PooledConnection(conn, this);

            } catch (ClassNotFoundException e) {
                throw new SQLException("PostgreSQL driver not found", e);
            }
        }

        private void performKeepAlive() {
            try {
                List<PooledConnection> connections = new ArrayList<>();
                pool.drainTo(connections);

                long now = System.currentTimeMillis();

                for (PooledConnection conn : connections) {
                    try {
                        Long lastUsedTime = lastUsed.get(conn);

                        if (lastUsedTime != null &&
                                now - lastUsedTime > MAX_IDLE_TIME) {
                            try {
                                conn.realClose();
                                created.decrementAndGet();
                            } catch (Exception e) {
                                // Игнорируем
                            }
                            try {
                                PooledConnection newConn = createPooledConnection();
                                pool.offer(newConn);
                                lastUsed.put(newConn, now);
                                created.incrementAndGet();
                            } catch (Exception e) {
                                if (DEBUG) e.printStackTrace();
                            }
                            continue;
                        }

                        if (!isConnectionValid(conn)) {
                            try {
                                conn.realClose();
                                created.decrementAndGet();
                            } catch (Exception e) {
                                // Игнорируем
                            }

                            try {
                                PooledConnection newConn = createPooledConnection();
                                pool.offer(newConn);
                                lastUsed.put(newConn, now);
                                created.incrementAndGet();
                            } catch (Exception e) {
                                if (DEBUG) e.printStackTrace();
                            }
                        } else {
                            try {
                                sendKeepAlive(conn);
                                pool.offer(conn);
                                lastUsed.put(conn, now);
                            } catch (Exception e) {
                                try {
                                    conn.realClose();
                                    created.decrementAndGet();
                                } catch (Exception ex) {
                                    // Игнорируем
                                }

                                try {
                                    PooledConnection newConn = createPooledConnection();
                                    pool.offer(newConn);
                                    lastUsed.put(newConn, now);
                                    created.incrementAndGet();
                                } catch (Exception ex) {
                                    if (DEBUG) ex.printStackTrace();
                                }
                            }
                        }

                    } catch (Exception e) {
                        try {
                            pool.offer(conn);
                        } catch (Exception ex) {
                            // Игнорируем
                        }
                    }
                }

                int currentSize = pool.size();
                if (currentSize < maxSize && created.get() < maxSize) {
                    int toCreate = Math.min(2, maxSize - currentSize);
                    for (int i = 0; i < toCreate; i++) {
                        try {
                            if (created.get() < maxSize) {
                                PooledConnection newConn = createPooledConnection();
                                pool.offer(newConn);
                                lastUsed.put(newConn, now);
                                created.incrementAndGet();
                            }
                        } catch (Exception e) {
                            break;
                        }
                    }
                }

            } catch (Exception e) {
                if (DEBUG) e.printStackTrace();
            }
        }

        private boolean isConnectionValid(Connection conn) {
            try {
                if (conn == null || conn.isClosed()) {
                    return false;
                }
                return conn.isValid(VALIDATION_TIMEOUT);
            } catch (SQLException e) {
                return false;
            }
        }

        private void sendKeepAlive(Connection conn) throws SQLException {
            String keepaliveQuery = "SELECT 1";

            if (conn instanceof org.postgresql.PGConnection) {
                keepaliveQuery = "SET LOCAL lock_timeout = '1s'";
            }

            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(2);
                stmt.execute(keepaliveQuery);
            }
        }

        Connection getConnection() throws Exception {
            PooledConnection pooled = pool.poll();

            if (pooled != null) {
                Connection conn = pooled.getConnection();
                if (conn != null && !conn.isClosed() && isConnectionValid(conn)) {
                    lastUsed.put(pooled, System.currentTimeMillis());
                    return conn;
                }
                created.decrementAndGet();
            }

            synchronized (this) {
                if (created.get() < maxSize) {
                    created.incrementAndGet();
                    PooledConnection newConn = createPooledConnection();
                    lastUsed.put(newConn, System.currentTimeMillis());
                    return newConn.getConnection();
                }
            }

            pooled = pool.poll(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS);
            if (pooled != null) {
                Connection conn = pooled.getConnection();
                if (conn != null && !conn.isClosed() && isConnectionValid(conn)) {
                    lastUsed.put(pooled, System.currentTimeMillis());
                    return conn;
                }
                created.decrementAndGet();
            }

            throw new SQLException("Connection timeout - no available connections");
        }

        boolean releaseConnection(Connection conn) {
            if (conn instanceof PooledConnection) {
                PooledConnection pooled = (PooledConnection) conn;
                lastUsed.put(pooled, System.currentTimeMillis());
                return pool.offer(pooled);
            }
            return false;
        }

        void shutdown() {
            keepAliveService.shutdown();
            try {
                keepAliveService.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            List<PooledConnection> connections = new ArrayList<>();
            pool.drainTo(connections);
            for (PooledConnection conn : connections) {
                try {
                    conn.realClose();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Обертка для соединения с пулом
     */
    private static class PooledConnection implements Connection {
        private final Connection delegate;
        private final ConnectionPool pool;
        private boolean closed = false;

        PooledConnection(Connection delegate, ConnectionPool pool) {
            this.delegate = delegate;
            this.pool = pool;
        }

        Connection getConnection() {
            return closed ? null : this;
        }

        void realClose() throws SQLException {
            closed = true;
            delegate.close();
        }

        @Override
        public void close() throws SQLException {
            if (!closed) {
                closed = true;
                if (!pool.releaseConnection(this)) {
                    delegate.close();
                }
            }
        }

        @Override
        public boolean isClosed() throws SQLException {
            return closed || delegate.isClosed();
        }

        // Делегирование всех остальных методов Connection
        @Override public Statement createStatement() throws SQLException { return delegate.createStatement(); }
        @Override public PreparedStatement prepareStatement(String sql) throws SQLException { return delegate.prepareStatement(sql); }
        @Override public CallableStatement prepareCall(String sql) throws SQLException { return delegate.prepareCall(sql); }
        @Override public String nativeSQL(String sql) throws SQLException { return delegate.nativeSQL(sql); }
        @Override public void setAutoCommit(boolean autoCommit) throws SQLException { delegate.setAutoCommit(autoCommit); }
        @Override public boolean getAutoCommit() throws SQLException { return delegate.getAutoCommit(); }
        @Override public void commit() throws SQLException { delegate.commit(); }
        @Override public void rollback() throws SQLException { delegate.rollback(); }
        @Override public DatabaseMetaData getMetaData() throws SQLException { return delegate.getMetaData(); }
        @Override public void setReadOnly(boolean readOnly) throws SQLException { delegate.setReadOnly(readOnly); }
        @Override public boolean isReadOnly() throws SQLException { return delegate.isReadOnly(); }
        @Override public void setCatalog(String catalog) throws SQLException { delegate.setCatalog(catalog); }
        @Override public String getCatalog() throws SQLException { return delegate.getCatalog(); }
        @Override public void setTransactionIsolation(int level) throws SQLException { delegate.setTransactionIsolation(level); }
        @Override public int getTransactionIsolation() throws SQLException { return delegate.getTransactionIsolation(); }
        @Override public SQLWarning getWarnings() throws SQLException { return delegate.getWarnings(); }
        @Override public void clearWarnings() throws SQLException { delegate.clearWarnings(); }
        @Override public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
            return delegate.createStatement(resultSetType, resultSetConcurrency);
        }
        @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
            return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency);
        }
        @Override public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
            return delegate.prepareCall(sql, resultSetType, resultSetConcurrency);
        }
        @Override public Map<String, Class<?>> getTypeMap() throws SQLException { return delegate.getTypeMap(); }
        @Override public void setTypeMap(Map<String, Class<?>> map) throws SQLException { delegate.setTypeMap(map); }
        @Override public void setHoldability(int holdability) throws SQLException { delegate.setHoldability(holdability); }
        @Override public int getHoldability() throws SQLException { return delegate.getHoldability(); }
        @Override public Savepoint setSavepoint() throws SQLException { return delegate.setSavepoint(); }
        @Override public Savepoint setSavepoint(String name) throws SQLException { return delegate.setSavepoint(name); }
        @Override public void rollback(Savepoint savepoint) throws SQLException { delegate.rollback(savepoint); }
        @Override public void releaseSavepoint(Savepoint savepoint) throws SQLException { delegate.releaseSavepoint(savepoint); }
        @Override public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            return delegate.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
        }
        @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            return delegate.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        }
        @Override public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            return delegate.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        }
        @Override public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
            return delegate.prepareStatement(sql, autoGeneratedKeys);
        }
        @Override public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
            return delegate.prepareStatement(sql, columnIndexes);
        }
        @Override public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
            return delegate.prepareStatement(sql, columnNames);
        }
        @Override public Clob createClob() throws SQLException { return delegate.createClob(); }
        @Override public Blob createBlob() throws SQLException { return delegate.createBlob(); }
        @Override public NClob createNClob() throws SQLException { return delegate.createNClob(); }
        @Override public SQLXML createSQLXML() throws SQLException { return delegate.createSQLXML(); }
        @Override public boolean isValid(int timeout) throws SQLException { return delegate.isValid(timeout); }
        @Override public void setClientInfo(String name, String value) throws SQLClientInfoException { delegate.setClientInfo(name, value); }
        @Override public void setClientInfo(Properties properties) throws SQLClientInfoException { delegate.setClientInfo(properties); }
        @Override public String getClientInfo(String name) throws SQLException { return delegate.getClientInfo(name); }
        @Override public Properties getClientInfo() throws SQLException { return delegate.getClientInfo(); }
        @Override public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
            return delegate.createArrayOf(typeName, elements);
        }
        @Override public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
            return delegate.createStruct(typeName, attributes);
        }
        @Override public void setSchema(String schema) throws SQLException { delegate.setSchema(schema); }
        @Override public String getSchema() throws SQLException { return delegate.getSchema(); }
        @Override public void abort(java.util.concurrent.Executor executor) throws SQLException { delegate.abort(executor); }
        @Override public void setNetworkTimeout(java.util.concurrent.Executor executor, int milliseconds) throws SQLException {
            delegate.setNetworkTimeout(executor, milliseconds);
        }
        @Override public int getNetworkTimeout() throws SQLException { return delegate.getNetworkTimeout(); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { return delegate.unwrap(iface); }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return delegate.isWrapperFor(iface); }
    }
}