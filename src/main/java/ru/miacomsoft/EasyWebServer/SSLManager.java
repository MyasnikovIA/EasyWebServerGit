package ru.miacomsoft.EasyWebServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
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
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;

public class SSLManager {

    public static SSLServerSocketFactory createSSLServerSocketFactory(String certPath, String keyPath) throws Exception {
        // 1. Загружаем сертификат
        String certPem = new String(Files.readAllBytes(Paths.get(certPath)), StandardCharsets.UTF_8);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");

        Collection<? extends Certificate> certs = cf.generateCertificates(
                new ByteArrayInputStream(certPem.getBytes(StandardCharsets.UTF_8))
        );
        Certificate[] certChain = certs.toArray(new Certificate[0]);

        // 2. Загружаем приватный ключ
        String keyPem = new String(Files.readAllBytes(Paths.get(keyPath)), StandardCharsets.UTF_8);

        // Очищаем от заголовков (поддерживаем PKCS#1 и PKCS#8)
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
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
            privateKey = kf.generatePrivate(keySpec);
        } catch (java.security.spec.InvalidKeySpecException e) {
            throw new Exception("Ошибка чтения ключа. Конвертируйте его в PKCS#8: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key_pkcs8.pem", e);
        }

        // 3. Создаем KeyStore
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("server", privateKey, new char[0], certChain);

        // 4. Создаем SSLContext
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, new char[0]);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        return sslContext.getServerSocketFactory();
    }
}