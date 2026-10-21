package ru.miacomsoft.EasyWebServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;

/**
 * Единый менеджер HTTPS. Поддерживает оба источника сертификата,
 * как и FileServer_git:
 *   1) PKCS12 / JKS keystore   (приоритет, если задан)
 *   2) PEM fullchain + PEM key (fallback)
 *
 * Загруженный SSLContext печатает в лог subject / issuer / chain length —
 * это сразу показывает, self-signed сертификат или настоящий.
 */
public class SSLManager {

    /**
     * Создаёт SSLServerSocket на заданном порту.
     * Если keystorePath не пуст — используется он (как в FileServer_git);
     * иначе — PEM cert+key.
     */
    public static SSLServerSocket createSSLServerSocket(
            int port,
            String keystorePath, String keystorePassword, String keyPassword,
            String certPath, String keyPath) throws Exception {

        SSLServerSocketFactory factory;

        if (keystorePath != null && !keystorePath.isEmpty()) {
            factory = createFactoryFromKeystore(keystorePath, keystorePassword, keyPassword);
        } else if (certPath != null && !certPath.isEmpty()
                && keyPath != null && !keyPath.isEmpty()) {
            factory = createFactoryFromPem(certPath, keyPath);
        } else {
            throw new IllegalArgumentException(
                    "HTTPS: не задан ни keystore, ни пара cert+key");
        }

        SSLServerSocket ss = (SSLServerSocket) factory.createServerSocket(port);
        ss.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
        ss.setNeedClientAuth(false);
        ss.setWantClientAuth(false);
        System.out.println("[SSLManager] SSLServerSocket ready on port " + port);
        System.out.println("[SSLManager] Enabled protocols: " +
                String.join(",", ss.getEnabledProtocols()));
        return ss;
    }

    // ---------- keystore (.p12 / .jks) ----------

    private static SSLServerSocketFactory createFactoryFromKeystore(
            String keystorePath, String storePassword, String keyPassword) throws Exception {

        System.out.println("[SSLManager] Loading keystore: " + keystorePath);

        String type = keystorePath.toLowerCase().endsWith(".p12")
                || keystorePath.toLowerCase().endsWith(".pfx")
                ? "PKCS12"
                : KeyStore.getDefaultType();

        char[] storePass = (storePassword == null || storePassword.isEmpty())
                ? new char[0] : storePassword.toCharArray();
        char[] keyPass = (keyPassword == null || keyPassword.isEmpty())
                ? storePass : keyPassword.toCharArray();

        KeyStore keyStore = KeyStore.getInstance(type);
        try (InputStream is = Files.newInputStream(Paths.get(keystorePath))) {
            keyStore.load(is, storePass);
        }

        logKeystoreContents(keyStore);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keyPass);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);
        System.out.println("[SSLManager] SSLContext init OK (keystore), protocol=" +
                sslContext.getProtocol());
        return sslContext.getServerSocketFactory();
    }

    private static void logKeystoreContents(KeyStore ks) {
        try {
            java.util.Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                Certificate c = ks.getCertificate(alias);
                if (c instanceof X509Certificate) {
                    X509Certificate x = (X509Certificate) c;
                    System.out.println("[SSLManager] Alias '" + alias + "'");
                    System.out.println("[SSLManager]   Subject: " + x.getSubjectX500Principal());
                    System.out.println("[SSLManager]   Issuer : " + x.getIssuerX500Principal());
                    System.out.println("[SSLManager]   Valid  : " + x.getNotBefore() + " .. " + x.getNotAfter());
                }
            }
        } catch (Exception e) {
            System.out.println("[SSLManager] (не удалось прочитать alias'ы keystore: " + e.getMessage() + ")");
        }
    }

    // ---------- PEM (fallback) ----------

    private static SSLServerSocketFactory createFactoryFromPem(String certPath, String keyPath) throws Exception {
        System.out.println("[SSLManager] Loading PEM cert: " + certPath);

        String certPem = new String(Files.readAllBytes(Paths.get(certPath)), StandardCharsets.UTF_8);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certs = cf.generateCertificates(
                new ByteArrayInputStream(certPem.getBytes(StandardCharsets.UTF_8)));
        Certificate[] certChain = certs.toArray(new Certificate[0]);
        if (certChain.length == 0) {
            throw new IllegalStateException("Сертификат пуст: " + certPath);
        }
        if (certChain[0] instanceof X509Certificate) {
            X509Certificate x = (X509Certificate) certChain[0];
            System.out.println("[SSLManager]   Subject: " + x.getSubjectX500Principal());
            System.out.println("[SSLManager]   Issuer : " + x.getIssuerX500Principal());
            System.out.println("[SSLManager]   Valid  : " + x.getNotBefore() + " .. " + x.getNotAfter());
        }
        System.out.println("[SSLManager]   Chain length: " + certChain.length);

        String keyPem = new String(Files.readAllBytes(Paths.get(keyPath)), StandardCharsets.UTF_8);
        String privateKeyPEM = keyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(privateKeyPEM);

        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey privateKey;
        try {
            privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (java.security.spec.InvalidKeySpecException e) {
            throw new Exception(
                    "Ошибка чтения PEM-ключа. Нужен PKCS#8: " +
                            "openssl pkcs8 -topk8 -nocrypt -in key.pem -out key_pkcs8.pem", e);
        }

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("server", privateKey, new char[0], certChain);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, new char[0]);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);
        System.out.println("[SSLManager] SSLContext init OK (PEM), protocol=" +
                sslContext.getProtocol());
        return sslContext.getServerSocketFactory();
    }
}