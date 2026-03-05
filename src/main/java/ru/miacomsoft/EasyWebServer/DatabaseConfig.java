package ru.miacomsoft.EasyWebServer;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Класс для хранения конфигурации подключения к БД
 */
public class DatabaseConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    private String type;           // jdbc, oci8, pdo
    private String driver;          // драйвер для JDBC
    private String username;
    private String password;
    private String host;
    private String port;
    private String database;
    private String schema;          // текущая схема
    private Map<String, String> params = new HashMap<>(); // дополнительные параметры

    // Кэш подключений для каждого имени БД
    private transient Object connection; // Будет хранить Connection

    public DatabaseConfig() {
    }

    public DatabaseConfig(String type, String username, String password, ConnectionInfo info) {
        this.type = type;
        this.username = username;
        this.password = password;
        this.host = info.host;
        this.port = info.port;
        this.database = info.database;
        this.schema = info.schema;
        this.params = info.params;

        // Устанавливаем драйвер в зависимости от типа
        if ("oci8".equals(type)) {
            this.driver = "oracle.jdbc.driver.OracleDriver";
        } else if ("jdbc".equals(type) || "pdo".equals(type)) {
            this.driver = "org.postgresql.Driver";
        }
    }

    /**
     * Парсит строку подключения в формате:
     * oci8://user:pass@host:port/database:pooled?param=value
     * pdo://user:pass@host:port/database?currentSchema=schema&type=pgsql
     */
    public static DatabaseConfig parse(String connectionString) {
        if (connectionString == null || connectionString.isEmpty()) {
            return null;
        }

        try {
            DatabaseConfig config = new DatabaseConfig();

            // Парсим протокол (тип)
            int protocolEnd = connectionString.indexOf("://");
            if (protocolEnd < 0) {
                return null;
            }

            config.type = connectionString.substring(0, protocolEnd);
            String rest = connectionString.substring(protocolEnd + 3);

            // Парсим user:pass@
            int atIndex = rest.indexOf("@");
            if (atIndex < 0) {
                return null;
            }

            String userPass = rest.substring(0, atIndex);
            String[] userPassParts = userPass.split(":", 2);
            config.username = userPassParts[0];
            if (userPassParts.length > 1) {
                config.password = userPassParts[1];
            }

            // Оставшаяся часть: host:port/database?params
            String hostPortDb = rest.substring(atIndex + 1);

            // Парсим параметры после ?
            int questionIndex = hostPortDb.indexOf("?");
            String paramsPart = "";
            if (questionIndex >= 0) {
                paramsPart = hostPortDb.substring(questionIndex + 1);
                hostPortDb = hostPortDb.substring(0, questionIndex);
            }

            // Парсим параметры
            if (!paramsPart.isEmpty()) {
                for (String param : paramsPart.split("&")) {
                    String[] keyValue = param.split("=", 2);
                    if (keyValue.length == 2) {
                        config.params.put(keyValue[0], keyValue[1]);

                        // Особые параметры
                        if ("currentSchema".equals(keyValue[0])) {
                            config.schema = keyValue[1];
                        }
                    }
                }
            }

            // Парсим host:port/database
            String[] hostPortDbParts = hostPortDb.split("/", 2);
            String hostPort = hostPortDbParts[0];
            if (hostPortDbParts.length > 1) {
                String dbPart = hostPortDbParts[1];
                // Убираем :pooled если есть
                int colonIndex = dbPart.indexOf(":");
                if (colonIndex >= 0) {
                    config.database = dbPart.substring(0, colonIndex);
                } else {
                    config.database = dbPart;
                }
            }

            String[] hostPortParts = hostPort.split(":", 2);
            config.host = hostPortParts[0];
            if (hostPortParts.length > 1) {
                config.port = hostPortParts[1];
            }

            // Устанавливаем драйвер по умолчанию
            if ("oci8".equals(config.type)) {
                config.driver = "oracle.jdbc.driver.OracleDriver";
            } else {
                config.driver = "org.postgresql.Driver";
            }

            return config;

        } catch (Exception e) {
            System.err.println("Error parsing database connection string: " + e.getMessage());
            return null;
        }
    }

    /**
     * Формирует JDBC URL для подключения
     */
    public String getJdbcUrl() {
        if ("oci8".equals(type)) {
            // jdbc:oracle:thin:@host:port:database
            return "jdbc:oracle:thin:@" + host + ":" + port + ":" + database;
        } else {
            // jdbc:postgresql://host:port/database
            StringBuilder url = new StringBuilder("jdbc:postgresql://");
            url.append(host);
            if (port != null && !port.isEmpty()) {
                url.append(":").append(port);
            }
            url.append("/").append(database);

            // Добавляем параметры
            if (!params.isEmpty()) {
                url.append("?");
                boolean first = true;
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    if (!first) {
                        url.append("&");
                    }
                    url.append(entry.getKey()).append("=").append(entry.getValue());
                    first = false;
                }
            }

            return url.toString();
        }
    }

    // Геттеры и сеттеры
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDriver() { return driver; }
    public void setDriver(String driver) { this.driver = driver; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public String getPort() { return port; }
    public void setPort(String port) { this.port = port; }

    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }

    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }

    public Map<String, String> getParams() { return params; }

    public Object getConnection() { return connection; }
    public void setConnection(Object connection) { this.connection = connection; }

    @Override
    public String toString() {
        return "DatabaseConfig{" +
                "type='" + type + '\'' +
                ", host='" + host + '\'' +
                ", port='" + port + '\'' +
                ", database='" + database + '\'' +
                ", schema='" + schema + '\'' +
                ", username='" + username + '\'' +
                '}';
    }

    /**
     * Внутренний класс для информации о подключении
     */
    public static class ConnectionInfo {
        public String host = "localhost";
        public String port = "5432";
        public String database = "";
        public String schema = "public";
        public Map<String, String> params = new HashMap<>();
    }
}