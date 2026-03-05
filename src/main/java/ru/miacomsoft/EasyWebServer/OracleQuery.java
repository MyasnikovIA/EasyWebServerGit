package ru.miacomsoft.EasyWebServer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.*;
import java.io.Reader;
import java.io.StringWriter;
import java.sql.Date;
import java.util.*;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Оптимизированный класс для работы с Oracle Database
 * Добавлены: пул соединений, кэширование, улучшенная производительность
 */
public class OracleQuery {

    // Пул соединений для каждой конфигурации БД
    private static final Map<String, ConnectionPool> connectionPools = new HashMap<>();

    // Кэш для обработанных SQL запросов (исходный SQL -> подготовленный SQL + имена параметров)
    private static final Map<String, ParsedSql> sqlCache = new LRUCache<>(1000);

    // Кэш для метаданных таблиц
    private static final Map<String, TableMetadata> metadataCache = new LRUCache<>(500);

    // Флаг отладки - можно отключить в продакшене
    private static final boolean DEBUG = false;

    // Размер пула соединений по умолчанию
    private static final int DEFAULT_POOL_SIZE = 10;

    // Таймаут получения соединения из пула (в миллисекундах)
    private static final int CONNECTION_TIMEOUT = 5000;

    static {
        // Устанавливаем таймзону один раз при загрузке класса
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
    }

    /**
     * Получает подключение к Oracle из пула
     */
    public static Connection getConnect(DatabaseConfig dbConfig) {
        return getConnect(dbConfig, DEFAULT_POOL_SIZE);
    }

    /**
     * Получает подключение к Oracle из пула с указанием размера пула
     */
    public static Connection getConnect(DatabaseConfig dbConfig, int poolSize) {
        if (dbConfig == null) return null;

        String poolKey = generatePoolKey(dbConfig);
        ConnectionPool pool = connectionPools.get(poolKey);

        if (pool == null) {
            synchronized (OracleQuery.class) {
                pool = connectionPools.get(poolKey);
                if (pool == null) {
                    pool = new ConnectionPool(dbConfig, poolSize);
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
            return createDirectConnection(dbConfig);
        }
    }

    /**
     * Генерирует ключ для пула соединений
     */
    private static String generatePoolKey(DatabaseConfig dbConfig) {
        return dbConfig.getHost() + ":" +
                dbConfig.getPort() + ":" +
                dbConfig.getDatabase() + ":" +
                dbConfig.getUsername();
    }

    /**
     * Создает прямое соединение (fallback метод)
     */
    private static Connection createDirectConnection(DatabaseConfig dbConfig) {
        try {
            Class.forName("oracle.jdbc.driver.OracleDriver");
            String jdbcUrl = formatOracleJdbcUrl(dbConfig);
            Connection conn = DriverManager.getConnection(
                    jdbcUrl,
                    dbConfig.getUsername(),
                    dbConfig.getPassword()
            );
            // Устанавливаем полезные параметры для Oracle
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
     * Форматирует JDBC URL для Oracle
     */
    private static String formatOracleJdbcUrl(DatabaseConfig dbConfig) {
        String host = dbConfig.getHost();
        String port = dbConfig.getPort();
        String database = dbConfig.getDatabase();

        if (port == null || port.trim().isEmpty()) {
            port = "1521";
        }

        return "jdbc:oracle:thin:@" + host + ":" + port + ":" + database;
    }

    /**
     * Выполняет SQL запрос с параметрами и возвращает результат в JSONArray
     */
    public static JSONArray executeQuery(DatabaseConfig dbConfig, String sql, Map<String, Object> params) {
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try {
            conn = getConnect(dbConfig);
            if (conn == null) {
                return createErrorResult("Database connection failed");
            }

            // Получаем разобранный SQL из кэша
            ParsedSql parsedSql = getParsedSql(sql);

            pstmt = conn.prepareStatement(parsedSql.processedSql);

            // Устанавливаем параметры (оптимизировано)
            if (params != null && !params.isEmpty()) {
                setParametersOptimized(pstmt, parsedSql.paramNames, params);
            }

            rs = pstmt.executeQuery();
            return resultSetToJSONOptimized(rs);

        } catch (SQLException e) {
            if (DEBUG) {
                System.err.println("Oracle query error: " + e.getMessage());
                e.printStackTrace();
            }
            return createErrorResult(e.getMessage());
        } finally {
            // Возвращаем соединение в пул, но закрываем остальные ресурсы
            closeResources(rs, pstmt);
            releaseConnection(conn);
        }
    }

    /**
     * Создает JSON с ошибкой
     */
    private static JSONArray createErrorResult(String errorMessage) {
        JSONArray result = new JSONArray();
        JSONObject error = new JSONObject();
        error.put("error", errorMessage);
        result.put(error);
        return result;
    }

    /**
     * Возвращает разобранный SQL из кэша или парсит новый
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
     * Выполняет SQL запрос с параметрами (с существующим соединением)
     */
    public static JSONArray executeQuery(Connection conn, String sql, Map<String, Object> params) {
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try {
            if (conn == null || conn.isClosed()) {
                return createErrorResult("Connection is null or closed");
            }

            ParsedSql parsedSql = getParsedSql(sql);
            pstmt = conn.prepareStatement(parsedSql.processedSql);

            if (params != null && !params.isEmpty()) {
                setParametersOptimized(pstmt, parsedSql.paramNames, params);
            }

            rs = pstmt.executeQuery();
            return resultSetToJSONOptimized(rs);

        } catch (SQLException e) {
            if (DEBUG) {
                System.err.println("Oracle query error: " + e.getMessage());
            }
            return createErrorResult(e.getMessage());
        } finally {
            closeResources(rs, pstmt);
        }
    }

    /**
     * Оптимизированное преобразование ResultSet в JSONArray
     */
    private static JSONArray resultSetToJSONOptimized(ResultSet rs) throws SQLException {
        JSONArray result = new JSONArray();
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();

        // Предварительно получаем имена колонок
        String[] columnNames = new String[columnCount];
        for (int i = 0; i < columnCount; i++) {
            columnNames[i] = metaData.getColumnLabel(i + 1);
        }

        // Кэш для типов колонок, чтобы не определять каждый раз
        int[] columnTypes = new int[columnCount];
        for (int i = 0; i < columnCount; i++) {
            columnTypes[i] = metaData.getColumnType(i + 1);
        }

        while (rs.next()) {
            JSONObject row = new JSONObject();
            for (int i = 0; i < columnCount; i++) {
                String columnName = columnNames[i];
                int type = columnTypes[i];

                // Оптимизированное получение значений по типу
                Object value = getValueByType(rs, i + 1, type);

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
     * Получает значение из ResultSet по типу (оптимизировано)
     */
    private static Object getValueByType(ResultSet rs, int index, int type) throws SQLException {
        switch (type) {
            case Types.VARCHAR:
            case Types.CHAR:
            case Types.LONGVARCHAR:
                return rs.getString(index);

            case Types.NUMERIC:
            case Types.DECIMAL:
                return rs.getBigDecimal(index);

            case Types.INTEGER:
                return rs.getInt(index);

            case Types.BIGINT:
                return rs.getLong(index);

            case Types.DOUBLE:
            case Types.FLOAT:
                return rs.getDouble(index);

            case Types.DATE:
                Date date = rs.getDate(index);
                return date != null ? date.toString() : null;

            case Types.TIMESTAMP:
                Timestamp ts = rs.getTimestamp(index);
                return ts != null ? ts.toString() : null;

            case Types.CLOB:
                Clob clob = rs.getClob(index);
                return clob != null ? clobToStringOptimized(clob) : null;

            case Types.BLOB:
                // Для BLOB возвращаем только информацию о размере, чтобы не грузить память
                Blob blob = rs.getBlob(index);
                return blob != null ? "[BLOB size:" + blob.length() + "]" : null;

            default:
                Object obj = rs.getObject(index);
                return obj != null ? obj.toString() : null;
        }
    }

    /**
     * Оптимизированное преобразование CLOB в строку
     */
    private static String clobToStringOptimized(Clob clob) {
        if (clob == null) return null;

        // Для больших CLOB используем поточное чтение с буфером
        try (Reader reader = clob.getCharacterStream()) {
            // Оцениваем размер, чтобы выбрать оптимальный буфер
            long length = clob.length();
            if (length > 1000000) { // Больше 1MB
                return "[CLOB size:" + length + "]";
            }

            int bufferSize = (int) Math.min(length, 8192);
            StringWriter writer = new StringWriter(bufferSize);
            char[] buffer = new char[bufferSize];
            int charsRead;
            while ((charsRead = reader.read(buffer)) != -1) {
                writer.write(buffer, 0, charsRead);
            }
            return writer.toString();
        } catch (Exception e) {
            return "[CLOB read error]";
        }
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

            // Быстрое определение типа и установка значения
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
            } else {
                pstmt.setString(i + 1, value.toString());
            }
        }
    }

    /**
     * Парсит именованные параметры (оптимизированная версия)
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
        char quoteChar = 0;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            // Обработка кавычек
            if ((c == '\'' || c == '"') && (i == 0 || sql.charAt(i - 1) != '\\')) {
                if (!inQuote) {
                    inQuote = true;
                    quoteChar = c;
                } else if (quoteChar == c) {
                    inQuote = false;
                }
            }

            // Пропускаем обработку параметров внутри кавычек
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

        // Если строка заканчивается параметром
        if (inParam && paramName.length() > 0) {
            paramNames.add(paramName.toString());
            processedSql.append('?');
        }

        return new ParsedSql(processedSql.toString(), paramNames);
    }

    /**
     * Выполняет обновление с параметрами
     */
    public static int executeUpdate(DatabaseConfig dbConfig, String sql, Map<String, Object> params) {
        Connection conn = null;
        PreparedStatement pstmt = null;

        try {
            conn = getConnect(dbConfig);
            if (conn == null) return -1;

            ParsedSql parsedSql = getParsedSql(sql);
            pstmt = conn.prepareStatement(parsedSql.processedSql);

            if (params != null && !params.isEmpty()) {
                setParametersOptimized(pstmt, parsedSql.paramNames, params);
            }

            int result = pstmt.executeUpdate();
            conn.commit(); // Коммитим изменения
            return result;

        } catch (SQLException e) {
            rollbackQuietly(conn);
            if (DEBUG) {
                System.err.println("Oracle update error: " + e.getMessage());
            }
            return -1;
        } finally {
            closeResources(null, pstmt);
            releaseConnection(conn);
        }
    }

    /**
     * Выполняет обновление с существующим соединением
     */
    public static int executeUpdate(Connection conn, String sql, Map<String, Object> params) {
        PreparedStatement pstmt = null;

        try {
            if (conn == null || conn.isClosed()) return -1;

            ParsedSql parsedSql = getParsedSql(sql);
            pstmt = conn.prepareStatement(parsedSql.processedSql);

            if (params != null && !params.isEmpty()) {
                setParametersOptimized(pstmt, parsedSql.paramNames, params);
            }

            return pstmt.executeUpdate();

        } catch (SQLException e) {
            if (DEBUG) {
                System.err.println("Oracle update error: " + e.getMessage());
            }
            return -1;
        } finally {
            closeResources(null, pstmt);
        }
    }

    /**
     * Откатывает транзакцию без проброса исключения
     */
    private static void rollbackQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.rollback();
            } catch (SQLException ignored) {}
        }
    }

    /**
     * Возвращает соединение в пул
     */
    private static void releaseConnection(Connection conn) {
        if (conn == null) return;

        // Ищем пул, которому принадлежит это соединение
        for (ConnectionPool pool : connectionPools.values()) {
            if (pool.releaseConnection(conn)) {
                return;
            }
        }

        // Если не нашли пул, закрываем соединение
        try {
            conn.close();
        } catch (SQLException ignored) {}
    }

    /**
     * Закрывает ресурсы
     */
    private static void closeResources(ResultSet rs, Statement stmt) {
        try { if (rs != null) rs.close(); } catch (SQLException ignored) {}
        try { if (stmt != null) stmt.close(); } catch (SQLException ignored) {}
    }

    /**
     * Проверяет существование таблицы (с кэшированием)
     */
    public static boolean tableExists(Connection conn, String tableName, String schema) {
        String cacheKey = (schema != null ? schema : "PUBLIC") + "." + tableName.toUpperCase();
        TableMetadata metadata = metadataCache.get(cacheKey);

        if (metadata != null) {
            return metadata.exists;
        }

        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM all_tables WHERE ");
        List<Object> params = new ArrayList<>();

        if (schema != null && !schema.isEmpty()) {
            sql.append("owner = ? AND ");
            params.add(schema.toUpperCase());
        }
        sql.append("table_name = ?");
        params.add(tableName.toUpperCase());

        JSONArray result = executeQuery(conn, sql.toString(),
                createParamsMap(params.toArray()));

        boolean exists = false;
        if (result.length() > 0) {
            JSONObject row = result.optJSONObject(0);
            exists = row != null && row.optInt("COUNT(*)", 0) > 0;
        }

        metadataCache.put(cacheKey, new TableMetadata(cacheKey, exists));
        return exists;
    }

    /**
     * Создает карту параметров из массива
     */
    private static Map<String, Object> createParamsMap(Object... values) {
        Map<String, Object> params = new HashMap<>();
        for (int i = 0; i < values.length; i++) {
            params.put(String.valueOf(i + 1), values[i]);
        }
        return params;
    }

    /**
     * Проверяет существование схемы (с кэшированием)
     */
    public static boolean schemaExists(Connection conn, String schemaName) {
        String cacheKey = "SCHEMA:" + schemaName.toUpperCase();
        TableMetadata metadata = metadataCache.get(cacheKey);

        if (metadata != null) {
            return metadata.exists;
        }

        String sql = "SELECT COUNT(*) FROM all_users WHERE username = ?";
        Map<String, Object> params = new HashMap<>();
        params.put("1", schemaName.toUpperCase());

        JSONArray result = executeQuery(conn, sql, params);

        boolean exists = false;
        if (result.length() > 0) {
            JSONObject row = result.optJSONObject(0);
            exists = row != null && row.optInt("COUNT(*)", 0) > 0;
        }

        metadataCache.put(cacheKey, new TableMetadata(cacheKey, exists));
        return exists;
    }

    /**
     * Проверяет, является ли строка числом
     */
    public static boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) return false;
        int len = str.length();
        boolean hasDecimal = false;

        for (int i = 0; i < len; i++) {
            char c = str.charAt(i);
            if (c == '-' && i == 0) continue;
            if (c == '.') {
                if (hasDecimal) return false;
                hasDecimal = true;
                continue;
            }
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /**
     * Преобразование строки в CamelCase
     */
    public static String toCamelCase(String input) {
        if (input == null || input.isEmpty()) return input;

        StringBuilder result = new StringBuilder(input.length());
        boolean nextUpper = true;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '_') {
                nextUpper = true;
            } else {
                result.append(nextUpper ? Character.toUpperCase(c) : Character.toLowerCase(c));
                nextUpper = false;
            }
        }

        return result.toString();
    }

    /**
     * Преобразование строки в camelCase (первая буква маленькая)
     */
    public static String toCamelCaseLower(String input) {
        String camelCase = toCamelCase(input);
        if (camelCase == null || camelCase.isEmpty()) return camelCase;
        return Character.toLowerCase(camelCase.charAt(0)) + camelCase.substring(1);
    }

    // ==================== Внутренние классы ====================

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
     * Простая реализация LRU кэша
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

    /**
     * Пул соединений с Oracle
     */
    private static class ConnectionPool {
        private final ArrayBlockingQueue<PooledConnection> pool;
        private final DatabaseConfig dbConfig;
        private final int maxSize;
        private int created = 0;

        ConnectionPool(DatabaseConfig dbConfig, int size) {
            this.dbConfig = dbConfig;
            this.maxSize = size;
            this.pool = new ArrayBlockingQueue<>(size);

            // Создаем начальные соединения
            for (int i = 0; i < Math.min(2, size); i++) {
                try {
                    pool.offer(createPooledConnection());
                    created++;
                } catch (Exception e) {
                    if (DEBUG) {
                        System.err.println("Failed to create initial connection: " + e.getMessage());
                    }
                }
            }
        }

        private PooledConnection createPooledConnection() throws SQLException {
            try {
                Class.forName("oracle.jdbc.driver.OracleDriver");
                String jdbcUrl = formatOracleJdbcUrl(dbConfig);
                Connection conn = DriverManager.getConnection(
                        jdbcUrl,
                        dbConfig.getUsername(),
                        dbConfig.getPassword()
                );
                conn.setAutoCommit(false);
                return new PooledConnection(conn, this);
            } catch (ClassNotFoundException e) {
                throw new SQLException("Oracle driver not found", e);
            }
        }

        Connection getConnection() throws Exception {
            PooledConnection pooled = pool.poll();

            if (pooled != null) {
                Connection conn = pooled.getConnection();
                if (conn != null && !conn.isClosed()) {
                    return conn;
                }
                // Соединение невалидно, создаем новое
                created--;
            }

            synchronized (this) {
                if (created < maxSize) {
                    created++;
                    return createPooledConnection().getConnection();
                }
            }

            // Ждем освобождения соединения
            pooled = pool.poll(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS);
            if (pooled != null) {
                return pooled.getConnection();
            }

            throw new SQLException("Connection timeout");
        }

        boolean releaseConnection(Connection conn) {
            if (conn instanceof PooledConnection) {
                return pool.offer((PooledConnection) conn);
            }
            return false;
        }

        void close() {
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

        // Делегируем все остальные методы
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