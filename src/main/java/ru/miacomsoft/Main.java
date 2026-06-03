package ru.miacomsoft;

import ru.miacomsoft.EasyWebServer.WebServer;

import java.io.File;
import java.net.URL;

public class Main {
    // # Создание keystore с настоящим сертификатом
    // keytool -genkey -keyalg RSA -alias easywebserver -keystore keystore.jks -storepass your_password -validity 365 -keysize 2048
    // # Импорт существующего сертификата (если есть)
    // keytool -import -alias easywebserver -keystore keystore.jks -file certificate.crt


    public static void main(String[] args) {
        WebServer web = new WebServer(Main.class);

        // Основная БД (опционально)
        web.config("DATABASE_NAME" , "jdbc:postgresql://192.168.241.36:5432/MisAnalis");
        web.config("DATABASE_USER_NAME" , "postgres");
        web.config("DATABASE_USER_PASS" , "postgres");

        // Дополнительные БД
        web.config("DATABASES.default", "pdo://postgres:postgres@192.168.241.36:5432/Panorama360?currentSchema=public&type=pgsql");
        web.config("DATABASES.auth", "pdo://postgres:postgres@192.168.241.36:5432/auth?currentSchema=auth&type=pgsql");
        web.config("DATABASES.settings", "pdo://postgres:postgres@192.168.241.36:5432/mis?currentSchema=settings&type=pgsql");
        web.config("DATABASES.org", "pdo://postgres:postgres@192.168.241.36:5432/mis?currentSchema=org&type=pgsql");
        web.config("DATABASES.oracle_test", "oci8://dev:postgres@192.168.241.141:1521/med2dev:pooled");

        web.config("LOGIN_PAGE" , "login.html");
        web.config("PAGE_404" , "page_404.html");
        web.config("INDEX_PAGE" , "index.html");
        web.config("DEBUG" , "false");
        web.config("CAHEBLE" , "true");

        web.config("ORACLE_POOL_SIZE", "15");
        web.config("POSTGRES_MIN_POOL_SIZE", "5");
        web.config("POSTGRES_MAX_POOL_SIZE", "30");

       // // Настройка HTTPS
       // web.config("HTTPS_PORT", "443");
       // // Для production - используйте настоящий сертификат
       // web.config("KEYSTORE_PATH", "/etc/ssl/easywebserver/keystore.jks");
       // web.config("KEYSTORE_PASSWORD", "secure_password");
       // // ИЛИ для разработки - самоподписанный (не использовать в production!)
       // // (оставьте KEYSTORE_PATH пустым, будет автоматически создан self-signed)

        String os = web.getOS();
        if (os.equals("windows")) {
            web.config("WEBAPP_DIR", "C:\\AppServ\\www;Y:\\files\\home\\storage\\downloads\\www");
            web.config("DEFAULT_PORT" , "9092");
        }
        if (os.equals("linux")) {
            // termux
            //web.config("WEBAPP_DIR" , "/data/data/com.termux/files/home/www;/storage/emulated/0/Download/www");
            web.config("WEBAPP_DIR" , "/var/www/EasyWebServerGit_www");
            web.config("DEFAULT_PORT" , "80");
        }

        web.config("DEFAULT_HOST" , "0.0.0.0");
        web.config("APP_NAME" , "webpage");

        try {
            URL location = Main.class.getProtectionDomain().getCodeSource().getLocation();
            File file = new File(location.toURI());
            String path = file.getPath();
            web.config("SERVER_HOM" , path);
            System.out.println("Путь к файлу Main.class: " + path);
        } catch (Exception e) {
            e.printStackTrace();
        }

        web.start();
    }
}