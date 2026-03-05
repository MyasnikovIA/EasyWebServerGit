package ru.miacomsoft.EasyWebServer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.DriverManager;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class HttpExchange {
    public Socket socket;
    public final Map<String, Object> headers = new HashMap<>();
    public final Map<String, String> cookie = new HashMap<>();
    public final Map<String, String> responseHeaders = new HashMap<>();
    public static final JSONObject SHARE = new JSONObject();
    public String sessionID = "";
    public Map<String, Object> session;
    public InputStreamReader inputStreamReader;
    public String typeQuery = "";
    public String mimeType = "text/html";
    public long contentLength = 0;
    public String requestText = "";
    public String requestPath = "";
    public String webappDir = "";
    public String expansion = "";
    public StringBuffer headSrc = new StringBuffer();
    public char[] postCharBody = null;
    public byte[] postByte = null;
    public JSONObject requestParam = new JSONObject();
    public JSONArray requestParamArray = new JSONArray();
    public String message = ""; // Raw message from client
    public JSONObject messageJson = new JSONObject();
    public int countQuery = 0;

    /// PtP - объект для обмена данными между устройствами "точка ту точка"
    public HttpExchange queryPtP = null;

    // Thread-safe shared resources
    public static final Map<String, HttpExchange> DevList = new ConcurrentHashMap<>();
    public static final Map<String, String> MESSAGE_LIST = new ConcurrentHashMap<>();
    public static final Map<String, List<String>> BROADCAST_MESSAGE_LIST = new ConcurrentHashMap<>();

    // Кэш для PreparedStatement на уровне сессии
    private final Map<String, Object> statementCache = new ConcurrentHashMap<>();
    
    public HttpExchange(Socket socket, Map<String, Object> session) throws IOException, JSONException {
        this.SHARE.put("server", "WebServerLite");
        this.socket = socket;
        if (this.socket != null) {
            this.socket.setSoTimeout(86400000);
            this.inputStreamReader = new InputStreamReader(socket.getInputStream());
        }
        this.responseHeaders.put("Connection", "close");
        this.responseHeaders.put("Server", "EasyWebServer");

        if (!ServerConstant.config.WEBAPP_DIRS.isEmpty()) {
            this.webappDir = ServerConstant.config.WEBAPP_DIRS.get(0);
        } else {
            this.webappDir = ServerConstant.config.WEBAPP_DIR;
        }

        this.session = session;
    }

    public void close() throws IOException {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    public boolean isConnected() {
        try {
            return socket != null && !socket.isClosed() && socket.isConnected() && inputStreamReader.ready();
        } catch (IOException e) {
            return false;
        }
    }

    public Map<String, Object> getRequestHeaders() {
        return headers;
    }

    public void sendFile(String pathFile) {
        File file = new File(pathFile);
        if (!file.exists()) return;

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("#")) {
                    line = line.split("#")[0];
                }
                sb.append(line).append("\n");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        sendHtml(sb.toString());
    }

    public void sendFile(File file) {
        String filename = file.getName().toLowerCase();
        String mimeType = ServerConstant.config.MIME_MAP.getOrDefault(
                getFileExtension(filename),
                "application/octet-stream"
        );
        sendFile(file, mimeType);
    }

    public void sendFile(File file, String contentType) {
        try (InputStream in = new FileInputStream(file);
             OutputStream out = socket.getOutputStream()) {

            // Подготавливаем заголовки
            ByteArrayOutputStream headers = new ByteArrayOutputStream();
            headers.write("HTTP/1.1 200 OK\r\n".getBytes());
            headers.write(("Content-Type: " + contentType + "\r\n").getBytes());
            headers.write(("Content-Length: " + file.length() + "\r\n").getBytes());
            headers.write("Accept-Ranges: bytes\r\n".getBytes());

            // Для изображений добавляем кеширование
            if (contentType.startsWith("image/")) {
                headers.write("Cache-Control: public, max-age=86400\r\n".getBytes());
            }

            // Пользовательские заголовки
            for (Map.Entry<String, String> entry : responseHeaders.entrySet()) {
                headers.write((entry.getKey() + ": " + entry.getValue() + "\r\n").getBytes());
            }

            headers.write("\r\n".getBytes());

            // Отправляем заголовки и файл
            out.write(headers.toByteArray());

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) > 0) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean write(byte[] content) {
        try {
            socket.getOutputStream().write(content);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public void send(byte[] content) {
        // Для бинарного контента нужно отправлять с правильными заголовками
        if (this.mimeType != null && this.mimeType.startsWith("text/")) {
            // Текстовый контент - используем существующую логику
            sendHtml(new String(content, StandardCharsets.UTF_8));
        } else {
            // Бинарный контент - отправляем с заголовками
            sendResponse(content, false);
        }
    }

    public void send(String content) {
        send(content.getBytes(Charset.forName("UTF-8")));
    }

    public void sendHtml(String content) {
        sendResponse(content.getBytes(), false);
    }

    public void sendHtmlCrosDomen(String content) {
        sendResponse(content.getBytes(), true);
    }

    public void sendHtmlMime(byte[] content, String mime) {
        this.mimeType = mime;
        sendResponse(content, false);
    }

    private void sendResponse(byte[] content, boolean corsEnabled) {
        try (OutputStream out = socket.getOutputStream()) {
            out.write("HTTP/1.1 200 OK\r\n".getBytes());

            out.write(("Content-Type: " + mimeType + "; charset=utf-8\r\n").getBytes());
            out.write(("Content-Length: " + content.length + "\r\n").getBytes());

            if (corsEnabled) {
                out.write("Access-Control-Allow-Origin: *\r\n".getBytes());
                out.write("Access-Control-Allow-Credentials: true\r\n".getBytes());
                out.write("Access-Control-Expose-Headers: FooBar\r\n".getBytes());
            }

            for (Map.Entry<String, String> entry : responseHeaders.entrySet()) {
                out.write((entry.getKey() + ": " + entry.getValue() + "\r\n").getBytes());
            }

            out.write("\r\n".getBytes());
            out.write(content);
            out.flush();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void sendByteFile(File file) {
        try (InputStream in = new FileInputStream(file);
             OutputStream out = socket.getOutputStream()) {

            // Определяем MIME-тип по расширению файла
            String fileExtension = getFileExtension(file.getName());
            String contentType = ServerConstant.config.MIME_MAP.getOrDefault(
                    fileExtension.toLowerCase(),
                    "application/octet-stream"
            );

            // Отправляем заголовки
            out.write("HTTP/1.1 200 OK\r\n".getBytes());
            out.write(("Content-Type: " + contentType + "\r\n").getBytes());
            out.write(("Content-Length: " + file.length() + "\r\n").getBytes());
            out.write(("Accept-Ranges: bytes\r\n").getBytes());

            // Добавляем кеширование для изображений
            if (fileExtension.matches("(?i)(jpg|jpeg|png|gif|ico|svg|webp)")) {
                out.write("Cache-Control: public, max-age=86400\r\n".getBytes());
            }

            for (Map.Entry<String, String> entry : responseHeaders.entrySet()) {
                out.write((entry.getKey() + ": " + entry.getValue() + "\r\n").getBytes());
            }
            out.write("\r\n".getBytes());
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) > 0) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Вспомогательный метод для получения расширения файла
    private String getFileExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        return (dotIndex >= 0) ? filename.substring(dotIndex + 1) : "";
    }

    public void sendImageFile(File file) {
        try (InputStream in = new FileInputStream(file);
             OutputStream out = socket.getOutputStream()) {

            String filename = file.getName().toLowerCase();
            String contentType;

            if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
                contentType = "image/jpeg";
            } else if (filename.endsWith(".png")) {
                contentType = "image/png";
            } else if (filename.endsWith(".gif")) {
                contentType = "image/gif";
            } else if (filename.endsWith(".webp")) {
                contentType = "image/webp";
            } else if (filename.endsWith(".svg")) {
                contentType = "image/svg+xml";
            } else {
                contentType = "application/octet-stream";
            }

            // Отправляем заголовки
            out.write("HTTP/1.1 200 OK\r\n".getBytes());
            out.write(("Content-Type: " + contentType + "\r\n").getBytes());
            out.write(("Content-Length: " + file.length() + "\r\n").getBytes());
            out.write(("Accept-Ranges: bytes\r\n".getBytes()));
            out.write("Cache-Control: public, max-age=86400\r\n".getBytes());

            out.write("\r\n".getBytes());

            // Отправляем файл
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) > 0) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String readLine() {
        StringBuilder sb = new StringBuilder();
        int c;
        try {
            while ((c = inputStreamReader.read()) != -1) {
                char ch = (char) c;
                sb.append(ch);
                if (ch == '\n') break;
            }
        } catch (IOException e) {
            return null;
        }
        return sb.toString();
    }

    public String readLineTerm() {
        String tmpStr = "";
        try {
            byte[] temp = new byte[16384];
            int bytesRead;
            while ((bytesRead = socket.getInputStream().read(temp)) != -1 && socket.isConnected()) {
                tmpStr = new String(temp, 0, bytesRead);
                break;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
        return tmpStr;
    }

    public String read() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            byte[] temp = new byte[1024];
            int bytesRead;
            while ((bytesRead = socket.getInputStream().read(temp)) != -1) {
                buffer.write(temp, 0, bytesRead);
                String tmpStr = new String(temp, 0, bytesRead);
                if ((tmpStr.contains("\r\n\r\n") || tmpStr.contains("\r\r")) || tmpStr.contains("\n\n")) break;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }

        String messageStr = buffer.toString(Charset.forName("UTF-8"));
        this.message = messageStr;

        for (String line : messageStr.split("\r\n")) {
            if (line.contains(":")) {
                String[] parts = line.split(":", 2);
                headers.put(parts[0].trim(), parts[1].trim());
            }
        }

        countQuery++;
        return messageStr;
    }

    /**
     * Выполняет SQL запрос с использованием БД по умолчанию
     */
    public JSONArray SQL(String sql) {
        return SQL(sql, null);
    }

    /**
     * Выполняет SQL запрос с указанием имени БД из конфигурации
     * @param sql SQL запрос
     * @param dbName имя БД из конфигурации (если null, используется БД по умолчанию)
     * @return результат в формате JSONArray
     */
    public JSONArray SQL(String sql, String dbName) {
        JSONArray result = new JSONArray();
        Connection conn = null;
        DatabaseConfig dbConfig = getDatabaseConfig(dbName);

        try {
            // Проверяем наличие сессии и DATABASE
            if (session == null) {
                System.err.println("No session available for database connection");
                return result;
            }

            if (dbConfig == null) {
                System.err.println("No database configuration found for: " + (dbName != null ? dbName : "default"));
                return result;
            }

            // Получаем или создаем подключение
            conn = getDatabaseConnection(dbConfig);
            if (conn == null) {
                System.err.println("Failed to connect to database: " + dbConfig.getType());
                return result;
            }

            // Проверяем и создаем схему если нужно
            ensureSchemaExists(conn, sql, dbConfig);

            // Используем кэшированный Statement если возможно
            String cacheKey = sql.hashCode() + "|" + dbName;
            Statement stmt = (Statement) statementCache.get(cacheKey);

            if (stmt == null || stmt.isClosed()) {
                stmt = conn.createStatement();
                // Оптимизация для больших результатов
                stmt.setFetchSize(100);
                statementCache.put(cacheKey, stmt);
            }

            try (ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    JSONObject row = new JSONObject();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        String colName = rs.getMetaData().getColumnName(i);
                        Object value = rs.getObject(i);
                        row.put(colName, value == null ? "null" : value);
                    }
                    result.put(row);
                }
            }

        } catch (SQLException | JSONException e) {
            System.err.println("SQL Error: " + e.getMessage());

            // Проверяем, не связана ли ошибка с отсутствием схемы
            if (conn != null && e.getMessage() != null && e.getMessage().contains("schema") &&
                    (e.getMessage().contains("does not exist") || e.getMessage().contains("not found"))) {

                // Извлекаем имя схемы из сообщения об ошибке
                String schemaName = extractSchemaNameFromError(e.getMessage());
                if (schemaName != null && !schemaName.isEmpty()) {
                    try {
                        // Пытаемся создать схему
                        if (createSchemaIfNotExists(conn, schemaName, dbConfig)) {
                            // Повторяем запрос
                            return SQL(sql, dbName);
                        }
                    } catch (Exception ex) {
                        System.err.println("Failed to create schema: " + ex.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            // Ловим любые другие ошибки, чтобы не нарушать работу
            System.err.println("Unexpected error in SQL method: " + e.getMessage());
        }

        return result;
    }

    /**
     * Получает конфигурацию БД по имени
     */
    private DatabaseConfig getDatabaseConfig(String dbName) {
        // Если в сессии уже есть информация о БД, используем её
        if (session != null && session.containsKey("DATABASE_CONFIG_" + (dbName != null ? dbName : "default"))) {
            return (DatabaseConfig) session.get("DATABASE_CONFIG_" + (dbName != null ? dbName : "default"));
        }

        // Иначе получаем из глобальной конфигурации
        DatabaseConfig dbConfig = ServerConstant.config.getDatabaseConfig(dbName);

        // Кэшируем в сессии
        if (dbConfig != null && session != null) {
            session.put("DATABASE_CONFIG_" + (dbName != null ? dbName : "default"), dbConfig);
        }

        return dbConfig;
    }

    /**
     * Получает подключение к БД (с кэшированием)
     */
    private Connection getDatabaseConnection(DatabaseConfig dbConfig) {
        if (dbConfig == null) return null;

        try {
            // Проверяем кэшированное подключение
            Connection conn = (Connection) dbConfig.getConnection();
            if (conn != null && !conn.isClosed()) {
                return conn;
            }

            // Создаем новое подключение
            Class.forName(dbConfig.getDriver());

            Connection newConn;
            if ("oci8".equals(dbConfig.getType())) {
                // Oracle подключение
                newConn = DriverManager.getConnection(
                        dbConfig.getJdbcUrl(),
                        dbConfig.getUsername(),
                        dbConfig.getPassword()
                );
            } else {
                // PostgreSQL подключение с оптимизированными параметрами
                java.util.Properties props = new java.util.Properties();
                props.setProperty("user", dbConfig.getUsername());
                props.setProperty("password", dbConfig.getPassword());
                props.setProperty("socketTimeout", "30");
                props.setProperty("connectTimeout", "10");
                props.setProperty("tcpKeepAlive", "true");
                props.setProperty("prepareThreshold", "3"); // Кэширование prepared statements

                newConn = DriverManager.getConnection(dbConfig.getJdbcUrl(), props);

                // Устанавливаем схему если указана
                if (dbConfig.getSchema() != null && !dbConfig.getSchema().isEmpty()) {
                    try (Statement stmt = newConn.createStatement()) {
                        stmt.execute("SET search_path TO '" + dbConfig.getSchema() + "'");
                    }
                }
            }

            // Оптимизация для пула соединений
            newConn.setAutoCommit(false);

            // Кэшируем подключение
            dbConfig.setConnection(newConn);

            return newConn;

        } catch (Exception e) {
            System.err.println("Error connecting to database: " + e.getMessage());
            return null;
        }
    }

    /**
     * Извлекает имя схемы из сообщения об ошибке
     */
    private String extractSchemaNameFromError(String errorMessage) {
        if (errorMessage == null) return null;

        try {
            // Для PostgreSQL: schema "schema_name" does not exist
            int startQuote = errorMessage.indexOf("\"");
            if (startQuote >= 0) {
                int endQuote = errorMessage.indexOf("\"", startQuote + 1);
                if (endQuote > startQuote) {
                    return errorMessage.substring(startQuote + 1, endQuote);
                }
            }

            // Для Oracle: ORA-00942: table or view does not exist
            // Не можем извлечь схему из такого сообщения
        } catch (Exception e) {
            // Игнорируем ошибки парсинга
        }
        return null;
    }

    /**
     * Создает схему, если она не существует
     */
    private boolean createSchemaIfNotExists(Connection conn, String schemaName, DatabaseConfig dbConfig) {
        if (conn == null || schemaName == null || schemaName.isEmpty()) {
            return false;
        }

        try {
            if (conn.isClosed()) {
                System.err.println("Connection is closed, cannot create schema");
                return false;
            }

            // Используем dbConfig для определения типа БД
            if (dbConfig != null && "oci8".equals(dbConfig.getType())) {
                // Oracle не имеет схем в том же понимании, что PostgreSQL
                // В Oracle схема это пользователь
                System.err.println("Schema creation not supported for Oracle");
                return false;
            } else {
                // PostgreSQL
                try (Statement stmt = conn.createStatement()) {
                    // Проверяем существование схемы
                    String checkSql = "SELECT 1 FROM information_schema.schemata WHERE schema_name = '" + schemaName + "'";
                    try (ResultSet rs = stmt.executeQuery(checkSql)) {
                        if (!rs.next()) {
                            // Схема не существует - создаем её
                            String createSql = "CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"";
                            stmt.executeUpdate(createSql);
                            conn.commit();
                            System.out.println("Created schema: " + schemaName);
                            return true;
                        }
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Error creating schema: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("Unexpected error creating schema: " + e.getMessage());
        }
        return false;
    }

    /**
     * Проверяет существование схемы в SQL запросе
     */
    private void ensureSchemaExists(Connection conn, String sql, DatabaseConfig dbConfig) {
        if (conn == null || sql == null || sql.isEmpty()) {
            return;
        }

        // Проверяем тип БД - для Oracle не создаем схемы автоматически
        if (dbConfig != null && "oci8".equals(dbConfig.getType())) {
            // Oracle не поддерживает автоматическое создание схем в том же виде, что PostgreSQL
            return;
        }

        try {
            if (conn.isClosed()) {
                return;
            }

            // Ищем имена схем в SQL запросе (паттерн: schema_name.table_name)
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "(?:from|join|into|update|table)\\s+(?:([\"`]?)([\\w\\.]+?)\\1\\.)?([\"`]?)([\\w]+)\\3",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            );

            java.util.regex.Matcher matcher = pattern.matcher(sql);
            while (matcher.find()) {
                String possibleSchema = matcher.group(2);
                if (possibleSchema != null && !possibleSchema.isEmpty()) {
                    // Убираем кавычки если есть
                    possibleSchema = possibleSchema.replace("\"", "").replace("`", "");

                    // Проверяем, не является ли это частью сложного имени
                    if (!possibleSchema.contains(".")) {
                        createSchemaIfNotExists(conn, possibleSchema, dbConfig);
                    }
                }
            }

            // Дополнительно ищем явное указание схемы с кавычками
            pattern = java.util.regex.Pattern.compile(
                    "schema\\s+[\"`]?([\\w]+)[\"`]?",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            );

            matcher = pattern.matcher(sql);
            while (matcher.find()) {
                String schemaName = matcher.group(1);
                createSchemaIfNotExists(conn, schemaName, dbConfig);
            }
        } catch (Exception e) {
            // Игнорируем все ошибки при проверке схем
            System.err.println("Error in ensureSchemaExists: " + e.getMessage());
        }
    }

    public static String parseErrorRunJava(Exception exception) {
        StringBuilder sbError = new StringBuilder();
        sbError.append("<pre>\n");
        sbError.append("Произошла ошибка: ").append(exception.getMessage()).append("\n");
        sbError.append("Тип ошибки: ").append(exception.getClass().getName()).append("\n");

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        exception.printStackTrace(pw);
        sbError.append("Подробное описание:\n").append(sw);

        sbError.append("</pre>");
        return sbError.toString();
    }

    /**
     * Получить режим отладки для текущей сессии
     * @return true если включен режим отладки
     */
    public boolean isDebugMode() {
        if (session != null && session.containsKey("debug_mode")) {
            return (boolean) session.get("debug_mode");
        }
        return false;
    }

    /**
     * Асинхронное выполнение SQL запроса
     * @param sql SQL запрос
     * @param dbName имя БД (опционально)
     * @return CompletableFuture с результатом
     */
    public CompletableFuture<JSONArray> SQLAsync(String sql, String dbName) {
        return CompletableFuture.supplyAsync(() -> SQL(sql, dbName));
    }

    /**
     * Асинхронное выполнение SQL запроса с таймаутом
     */
    public CompletableFuture<JSONArray> SQLAsyncWithTimeout(
            String sql,
            String dbName,
            long timeout,
            TimeUnit unit) {

        CompletableFuture<JSONArray> future = CompletableFuture.supplyAsync(
                () -> SQL(sql, dbName)
        );

        return future.orTimeout(timeout, unit)
                .exceptionally(throwable -> {
                    JSONArray error = new JSONArray();
                    JSONObject errObj = new JSONObject();
                    errObj.put("error", "Query timeout after " + timeout + " " + unit);
                    errObj.put("sql", sql);
                    error.put(errObj);
                    return error;
                });
    }

    /**
     * Параллельное выполнение нескольких SQL запросов
     */
    public CompletableFuture<Map<String, JSONArray>> executeParallel(
            Map<String, String> queries,
            String dbName) {

        Map<String, CompletableFuture<JSONArray>> futures = new HashMap<>();

        for (Map.Entry<String, String> entry : queries.entrySet()) {
            futures.put(entry.getKey(),
                    CompletableFuture.supplyAsync(() -> SQL(entry.getValue(), dbName))
            );
        }

        return CompletableFuture.allOf(
                futures.values().toArray(new CompletableFuture[0])
        ).thenApply(v -> {
            Map<String, JSONArray> results = new HashMap<>();
            futures.forEach((key, future) -> results.put(key, future.join()));
            return results;
        });
    }

    /**
     * Потоковая обработка результатов SQL запроса
     * @param sql SQL запрос
     * @param dbName имя БД
     * @param batchSize размер пакета
     * @param consumer потребитель строк
     */
    public void streamSQL(String sql, String dbName, int batchSize, Consumer<JSONObject> consumer) {
        Connection conn = null;
        Statement stmt = null;
        ResultSet rs = null;

        try {
            DatabaseConfig dbConfig = getDatabaseConfig(dbName);
            if (dbConfig == null) {
                System.err.println("No database configuration found for: " + dbName);
                return;
            }

            conn = getDatabaseConnection(dbConfig);
            if (conn == null) {
                System.err.println("Failed to connect to database");
                return;
            }

            // Оптимизация для потоковой передачи
            stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            stmt.setFetchSize(batchSize);

            rs = stmt.executeQuery(sql);

            int rowCount = 0;
            while (rs.next()) {
                JSONObject row = new JSONObject();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    String colName = rs.getMetaData().getColumnName(i);
                    Object value = rs.getObject(i);
                    row.put(colName, value == null ? "null" : value);
                }
                consumer.accept(row);
                rowCount++;

                // Периодический коммит для очень больших наборов
                if (rowCount % 10000 == 0) {
                    conn.commit();
                }
            }

            conn.commit();

        } catch (SQLException e) {
            System.err.println("Streaming SQL error: " + e.getMessage());
        } finally {
            try { if (rs != null) rs.close(); } catch (SQLException ignored) {}
            try { if (stmt != null) stmt.close(); } catch (SQLException ignored) {}
            // Connection возвращается в пул через PostgreQuery
        }
    }

    /**
     * Экспорт результатов SQL запроса в CSV
     */
    public void exportToCSV(String sql, String dbName, OutputStream outputStream, char delimiter) {
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {

            final boolean[] headerWritten = {false};
            final String[][] columnNames = {null};

            streamSQL(sql, dbName, 1000, row -> {
                if (!headerWritten[0]) {
                    columnNames[0] = row.keySet().toArray(new String[0]);
                    StringBuilder header = new StringBuilder();
                    for (int i = 0; i < columnNames[0].length; i++) {
                        if (i > 0) header.append(delimiter);
                        header.append('"').append(escapeCSV(columnNames[0][i])).append('"');
                    }
                    writer.println(header);
                    headerWritten[0] = true;
                }

                StringBuilder line = new StringBuilder();
                for (int i = 0; i < columnNames[0].length; i++) {
                    if (i > 0) line.append(delimiter);
                    String value = row.optString(columnNames[0][i], "");
                    line.append('"').append(escapeCSV(value)).append('"');
                }
                writer.println(line);
            });

            writer.flush();
        }
    }

    private String escapeCSV(String s) {
        return s.replace("\"", "\"\"");
    }

    /**
     * Очистка кэша statement'ов при завершении
     */
    public void cleanup() {
        for (Object stmt : statementCache.values()) {
            try {
                if (stmt instanceof Statement) {
                    ((Statement) stmt).close();
                }
            } catch (SQLException ignored) {}
        }
        statementCache.clear();
    }
}