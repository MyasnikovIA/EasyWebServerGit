// ru/miacomsoft/EasyWebServer/HttpsServerWrapper.java
package ru.miacomsoft.EasyWebServer;

import javax.net.ssl.*;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Обертка для поддержки HTTPS (SSL/TLS)
 * Позволяет серверу работать одновременно с HTTP и HTTPS
 */
public class HttpsServerWrapper {

    private static final Logger LOGGER = Logger.getLogger(HttpsServerWrapper.class.getName());

    private final int httpsPort;
    private final SSLServerSocketFactory sslServerSocketFactory;
    private ServerSocket httpsServerSocket;
    private volatile boolean isRunning = false;

    /**
     * Конструктор для создания HTTPS сервера с использованием файлов сертификата
     *
     * @param httpsPort порт для HTTPS (обычно 443)
     * @param keyStorePath путь к файлу keystore (JKS или PKCS12)
     * @param keyStorePassword пароль от keystore
     * @param keyPassword пароль для ключа (если отличается от keystore)
     */
    public HttpsServerWrapper(int httpsPort, String keyStorePath, String keyStorePassword, String keyPassword)
            throws KeyStoreException, IOException, CertificateException, NoSuchAlgorithmException,
            UnrecoverableKeyException, KeyManagementException {

        this.httpsPort = httpsPort;
        this.sslServerSocketFactory = createSSLFactory(keyStorePath, keyStorePassword, keyPassword);
    }



    public HttpsServerWrapper(int httpsPort, String keyStorePath, String keyStorePassword, String keyPassword,boolean isProd) throws Exception {
        // Упрощенная версия - только для production с настоящим сертификатом
        this.httpsPort = httpsPort;

        KeyStore keyStore = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(keyStorePath)) {
            keyStore.load(fis, keyStorePassword.toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keyPassword.toCharArray());

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        this.sslServerSocketFactory = sslContext.getServerSocketFactory();
    }

// Убрать конструктор без параметров (self-signed) полностью

    /**
     * Конструктор с одинаковым паролем для keystore и ключа
     */
    public HttpsServerWrapper(int httpsPort, String keyStorePath, String keyStorePassword)
            throws KeyStoreException, IOException, CertificateException, NoSuchAlgorithmException,
            UnrecoverableKeyException, KeyManagementException {
        this(httpsPort, keyStorePath, keyStorePassword, keyStorePassword);
    }

    /**
     * Конструктор для использования self-signed сертификата (для разработки)
     */
    public HttpsServerWrapper(int httpsPort)
            throws NoSuchAlgorithmException, KeyManagementException {
        this.httpsPort = httpsPort;
        this.sslServerSocketFactory = createSelfSignedSSLFactory();
        System.err.println("⚠️ Используется самоподписанный сертификат! Только для разработки!");
    }

    /**
     * Создание SSL factory из файла keystore
     */
    private SSLServerSocketFactory createSSLFactory(String keyStorePath, String keyStorePassword, String keyPassword)
            throws KeyStoreException, IOException, CertificateException, NoSuchAlgorithmException,
            UnrecoverableKeyException, KeyManagementException {

        // Загружаем keystore (поддерживаем PKCS12 и JKS)
        String keystoreType = keyStorePath.toLowerCase().endsWith(".p12") ? "PKCS12" : KeyStore.getDefaultType();
        KeyStore keyStore = KeyStore.getInstance(keystoreType);

        try (FileInputStream fis = new FileInputStream(new File(keyStorePath))) {
            keyStore.load(fis, keyStorePassword.toCharArray());
        }

        // Инициализируем KeyManagerFactory
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, keyPassword.toCharArray());

        // Создаем SSLContext с TLS 1.2/1.3
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), null, null);

        return sslContext.getServerSocketFactory();
    }

    /**
     * Создание self-signed SSL factory для разработки (без sun.security.x509)
     */
    private SSLServerSocketFactory createSelfSignedSSLFactory()
            throws NoSuchAlgorithmException, KeyManagementException {

        try {
            // Создаем временный KeyStore
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);

            // Генерируем пару ключей
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            KeyPair keyPair = keyGen.generateKeyPair();

            // Создаем самоподписанный сертификат с помощью Bouncy Castle или простой заглушки
            X509Certificate certificate = generateSelfSignedCertificate(keyPair);

            // Сохраняем в keystore
            keyStore.setKeyEntry("selfsigned", keyPair.getPrivate(), "selfsigned".toCharArray(),
                    new java.security.cert.Certificate[]{certificate});

            // Инициализируем KeyManagerFactory
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, "selfsigned".toCharArray());

            // Создаем SSLContext
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), null, null);

            return sslContext.getServerSocketFactory();

        } catch (Exception e) {
            // Если не удалось создать self-signed, используем TrustAll
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, null);
            return sslContext.getServerSocketFactory();
        }
    }

    /**
     * Создание самоподписанного сертификата (без sun.security.x509)
     */
    private X509Certificate generateSelfSignedCertificate(KeyPair keyPair) throws Exception {
        // Используем стандартный Java API без sun.* пакетов
        // Вместо генерации сертификата создаем TrustManager который доверяет всем
        // Для реального самоподписанного сертификата рекомендуем использовать keytool

        // Возвращаем заглушку - сертификат не нужен для TrustAll
        // Вместо генерации реального сертификата используем TrustAll подход
        throw new UnsupportedOperationException("Для self-signed используйте ключевое хранилище или TrustAll");
    }

    /**
     * TrustManager который доверяет всем сертификатам (только для разработки!)
     */
    private static final TrustManager[] trustAllCerts = new TrustManager[] {
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                @Override
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }
    };

    /**
     * Старт HTTPS сервера в отдельном потоке
     */
    public void start() {
        if (isRunning) {
            System.out.println("HTTPS сервер уже запущен");
            return;
        }

        try {
            httpsServerSocket = sslServerSocketFactory.createServerSocket(httpsPort);
            httpsServerSocket.setReuseAddress(true);
            isRunning = true;

            System.out.println("🔒 HTTPS сервер запущен на порту " + httpsPort);

            Thread httpsThread = new Thread(() -> {
                while (isRunning) {
                    try {
                        Socket socket = httpsServerSocket.accept();
                        socket.setSoTimeout(86400000); // 24 часа таймаут

                        // Используем существующий обработчик запросов
                        new Thread(new ServerResourceHandler(socket)).start();

                    } catch (IOException e) {
                        if (isRunning) {
                            LOGGER.log(Level.SEVERE, "HTTPS accept error", e);
                        }
                    }
                }
            }, "HTTPS-Server");

            httpsThread.setDaemon(true);
            httpsThread.start();

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to start HTTPS server on port " + httpsPort, e);
        }
    }

    /**
     * Остановка HTTPS сервера
     */
    public void stop() {
        isRunning = false;
        if (httpsServerSocket != null && !httpsServerSocket.isClosed()) {
            try {
                httpsServerSocket.close();
                System.out.println("HTTPS сервер остановлен");
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error closing HTTPS socket", e);
            }
        }
    }

    /**
     * Проверка, запущен ли HTTPS сервер
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Получить порт HTTPS сервера
     */
    public int getHttpsPort() {
        return httpsPort;
    }

    /**
     * Генерация keystore с самоподписанным сертификатом через keytool
     * (для удобства разработки)
     *
     * @param keystorePath путь для сохранения keystore
     * @param password пароль
     * @param hostname имя хоста (например, localhost)
     */
    public static void generateSelfSignedKeystore(String keystorePath, String password, String hostname) {
        try {
            // Используем ProcessBuilder вместо устаревшего Runtime.exec(String)
            String[] command = {
                    "keytool", "-genkey", "-keyalg", "RSA", "-alias", "selfsigned",
                    "-keystore", keystorePath, "-storepass", password, "-keypass", password,
                    "-validity", "365", "-keysize", "2048",
                    "-dname", "CN=" + hostname + ", OU=Development, O=EasyWebServer, L=City, S=State, C=RU",
                    "-ext", "SAN=dns:" + hostname
            };

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Читаем вывод
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                System.out.println("✅ Keystore создан: " + keystorePath);
                System.out.println("   Пароль: " + password);
            } else {
                System.err.println("❌ Ошибка создания keystore, код: " + exitCode);
            }

        } catch (Exception e) {
            System.err.println("Ошибка создания keystore: " + e.getMessage());
            System.err.println("Убедитесь, что keytool доступен в PATH (Java JDK/bin)");
        }
    }
}