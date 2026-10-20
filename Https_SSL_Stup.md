# Настройка и обслуживание SSL/HTTPS в EasyWebServer

В проекте реализованы **две независимые схемы HTTPS**. Обе могут работать одновременно, либо можно использовать только одну. Выбор зависит от того, как вы получили сертификат.

---

## 1. Две схемы HTTPS

| Схема | Класс | Основа | Порт | Конфиг |
|---|---|---|---|---|
| **A. PEM** (Let's Encrypt, fullchain+privkey) | `SSLManager` | файлы `.crt`/`.key` | основной `DEFAULT_PORT` | `HTTPS_ENABLED`, `HTTPS_CERT`, `HTTPS_KEY` |
| **B. Keystore** (JKS/PKCS12) | `HttpsServerWrapper` | файл `.jks`/`.p12` | отдельный `HTTPS_PORT` | `HTTPS_PORT`, `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD` |

Особенности:

- **A** переключает основной порт с HTTP на HTTPS. Удобно, если нужен один порт и сертификат Let's Encrypt.
- **B** запускает **второй** сервер на `HTTPS_PORT` параллельно основному. Основной продолжает работать как HTTP (или как HTTPS-A, если `HTTPS_ENABLED=true`).
- Оба класса живут в `ru.miacomsoft.EasyWebServer`.
- Вызовы происходят из `WebServer`:
    - `WebServer.start()` → `initHttps()` — схема **B**.
    - `WebServer.run()` — выбирает HTTP / HTTPS-A для `DEFAULT_PORT` по флагу `HTTPS_ENABLED`.

---

## 2. Схема A. PEM-сертификаты (`SSLManager`)

### 2.1. Требования к файлам

`SSLManager.createSSLServerSocketFactory(certPath, keyPath)`:

- `certPath` — файл `.crt`/`.pem` с **цепочкой** (leaf + intermediate). `fullchain.pem` от Let's Encrypt подходит как есть.
- `keyPath` — приватный ключ **в формате PKCS#8** (`-----BEGIN PRIVATE KEY-----`). PKCS#1 (`-----BEGIN RSA PRIVATE KEY-----`) **не поддерживается**.

Если ключ в PKCS#1, его нужно сконвертировать:

```bash
openssl pkcs8 -topk8 -nocrypt -in privkey.pem -out privkey_pkcs8.pem
```

В случае ошибки `SSLManager` бросает:

```
Ошибка чтения ключа. Конвертируйте его в PKCS#8:
  openssl pkcs8 -topk8 -nocrypt -in key.pem -out key_pkcs8.pem
```

### 2.2. Получение сертификата Let's Encrypt (DNS Challenge)

Устанавливаем утилиты:

```bash
sudo apt update
sudo apt install certbot openssl
```

Запускаем интерактивный Certbot:

```bash
sudo certbot certonly --manual --preferred-challenges dns \
     -d example.com -d www.example.com
```

Certbot выдаст **два** TXT-кода (по одному на домен). **Не нажимайте Enter** — сначала добавьте в DNS:

| Имя | Значение |
|---|---|
| `_acme-challenge` | первый код |
| `_acme-challenge.www` | второй код |

Подождите 2–5 минут, затем вернитесь в терминал и нажмите Enter. Certbot создаст файлы в `/etc/letsencrypt/live/example.com/`.

### 2.3. Копирование файлов и конвертация ключа

Создайте каталог для сертификатов в проекте (пример):

```bash
mkdir -p /opt/EasyWebServer/certs
```

Скопируйте **fullchain** и **privkey**:

```bash
sudo cp /etc/letsencrypt/live/example.com/fullchain.pem /opt/EasyWebServer/certs/example.com.crt
sudo cp /etc/letsencrypt/live/example.com/privkey.pem  /opt/EasyWebServer/certs/example.com.key.pem
```

Убедитесь, что ключ в PKCS#8:

```bash
head -1 /opt/EasyWebServer/certs/example.com.key.pem
# должно быть: -----BEGIN PRIVATE KEY-----
# если -----BEGIN RSA PRIVATE KEY----- :
sudo openssl pkcs8 -topk8 -nocrypt \
  -in /opt/EasyWebServer/certs/example.com.key.pem \
  -out /opt/EasyWebServer/certs/example.com.key8.pem
sudo mv /opt/EasyWebServer/certs/example.com.key8.pem /opt/EasyWebServer/certs/example.com.key.pem
```

Права на ключ (сервер читает его от своего пользователя):

```bash
sudo chown -R $USER:$USER /opt/EasyWebServer/certs
chmod 600 /opt/EasyWebServer/certs/example.com.key.pem
chmod 644 /opt/EasyWebServer/certs/example.com.crt
```

### 2.4. `config.ini`

Поддерживаются **два формата** записи (взаимоисключающие по смыслу, но можно использовать оба — они сливаются).

**Плоские ключи:**

```json
"HTTPS_ENABLED": true,
"HTTPS_CERT": "/opt/EasyWebServer/certs/example.com.crt",
"HTTPS_KEY":  "/opt/EasyWebServer/certs/example.com.key.pem",
"DEFAULT_PORT": "8443"
```

**Вложенный блок (стиль EasyWebServer1):**

```json
"https": {
    "enabled": true,
    "cert": "/opt/EasyWebServer/certs/example.com.crt",
    "key":  "/opt/EasyWebServer/certs/example.com.key.pem"
},
"DEFAULT_PORT": "8443"
```

Парсинг (`ServerConstant.parseConfig`) сначала читает плоские ключи, затем перезаписывает значениями из `"https"`, если блок есть.

### 2.5. Поведение при старте

`WebServer.run()`:

```java
if (ServerConstant.config.HTTPS_ENABLED
        && !ServerConstant.config.HTTPS_CERT.isEmpty()
        && !ServerConstant.config.HTTPS_KEY.isEmpty()) {
    System.out.println("[WebServer] Starting in HTTPS mode (PEM via SSLManager)...");
    System.out.println("[WebServer] Cert: " + HTTPS_CERT);
    System.out.println("[WebServer] Key:  " + HTTPS_KEY);
    SSLServerSocketFactory ssf = SSLManager.createSSLServerSocketFactory(HTTPS_CERT, HTTPS_KEY);
    ss = ssf.createServerSocket(port);
} else {
    System.out.println("[WebServer] Starting in HTTP mode...");
    ss = new ServerSocket(port);
}
```

Логи в консоли:

```
[WebServer] Starting in HTTPS mode (PEM via SSLManager)...
[WebServer] Cert: /opt/EasyWebServer/certs/example.com.crt
[WebServer] Key:  /opt/EasyWebServer/certs/example.com.key.pem
```

Если `HTTPS_ENABLED=true`, но пути пустые — сервер стартует как HTTP (условие `&& !HTTPS_CERT.isEmpty() && !HTTPS_KEY.isEmpty()`).

Если файл не читается или ключ не PKCS#8 — `SSLManager` бросает исключение, `WebServer.run()` ловит его через `Logger.log(Level.SEVERE, ...)` и **поток завершается**. Сервер не поднимается.

### 2.6. Проверка

```bash
curl -v https://example.com:8443/ 2>&1 | head -30
```

В `curl` не должно быть `SSL certificate problem`. В браузере — зелёный замок, `https://example.com:8443/`.

### 2.7. Программное включение без `config.ini`

Помимо `config.ini` можно сконфигурировать через API `WebServer` (актуально, если сервер запускается из кода — `Main.java`):

```java
WebServer web = new WebServer(Main.class);
web.config("HTTPS_ENABLED", "true");
web.config("HTTPS_CERT",    "/opt/EasyWebServer/certs/example.com.crt");
web.config("HTTPS_KEY",     "/opt/EasyWebServer/certs/example.com.key.pem");
web.config("DEFAULT_PORT",  "8443");
web.start();
```

`WebServer.config(...)` для строк идёт в `ServerConstant.setProp(...)`; для `HTTPS_ENABLED` (boolean) — перегрузка `config(String, Boolean)`.

---

## 3. Схема B. Keystore (`HttpsServerWrapper`)

### 3.1. Генерация self-signed keystore для разработки

```bash
keytool -genkey -keyalg RSA -alias easywebserver \
        -keystore /opt/EasyWebServer/keystore.jks \
        -storepass changeit \
        -keypass  changeit \
        -validity 365 -keysize 2048 \
        -dname "CN=localhost, OU=Dev, O=EasyWebServer, C=RU" \
        -ext "SAN=dns:localhost,ip:127.0.0.1"
```

`HttpsServerWrapper.generateSelfSignedKeystore(path, password, hostname)` делает то же самое из Java. Но проще через `keytool`.

### 3.2. Использование настоящего сертификата в keystore

Если у вас есть `fullchain.pem` + `privkey.pem` (Let's Encrypt):

```bash
openssl pkcs12 -export \
  -in /etc/letsencrypt/live/example.com/fullchain.pem \
  -inkey /etc/letsencrypt/live/example.com/privkey.pem \
  -out /opt/EasyWebServer/keystore.p12 \
  -name easywebserver \
  -passout pass:changeit
```

Для JKS (если нужен именно JKS):

```bash
keytool -importkeystore \
  -srckeystore /opt/EasyWebServer/keystore.p12 \
  -srcstoretype PKCS12 \
  -srcstorepass changeit \
  -destkeystore /opt/EasyWebServer/keystore.jks \
  -deststoretype JKS \
  -deststorepass changeit \
  -destkeypass changeit
```

### 3.3. `config.ini`

```json
"HTTPS_PORT": "443",
"KEYSTORE_PATH": "/opt/EasyWebServer/keystore.jks",
"KEYSTORE_PASSWORD": "changeit",
"KEY_PASSWORD": ""
```

- `HTTPS_PORT <= 0` → схема B **отключена**.
- `KEYSTORE_PATH` содержит `.p12` → `HttpsServerWrapper` использует `PKCS12`, иначе `JKS`.

### 3.4. Поведение при старте

`WebServer.start()` вызывает `initHttps()`:

```java
if (ServerConstant.config.HTTPS_PORT <= 0) {
    System.out.println("HTTPS (keystore) отключён (порт <= 0)");
    return;
}
...
httpsServer = new HttpsServerWrapper(HTTPS_PORT, KEYSTORE_PATH, KEYSTORE_PASSWORD, keyPass);
httpsServer.start();
```

Логи:

```
🔒 HTTPS сервер запущен на порту 443
```

или:

```
HTTPS (keystore) отключён (порт <= 0)
```

Если `KEYSTORE_PATH` пуст — используется **self-signed** сертификат (только для разработки, браузер выдаст `NET::ERR_CERT_AUTHORITY_INVALID`).

### 3.5. Проверка

```bash
curl -vk https://localhost:443/
```

(`-k` — игнорировать self-signed на этапе разработки).

---

## 4. Совмещение схем A и B

`config.ini`:

```json
"DEFAULT_PORT": "8080",
"HTTPS_ENABLED": true,
"HTTPS_CERT": "/opt/EasyWebServer/certs/example.com.crt",
"HTTPS_KEY":  "/opt/EasyWebServer/certs/example.com.key.pem",

"HTTPS_PORT": "443",
"KEYSTORE_PATH": "/opt/EasyWebServer/keystore.jks",
"KEYSTORE_PASSWORD": "changeit",
"KEY_PASSWORD": ""
```

Итог:

| Порт | Протокол |
|---|---|
| 8080 | HTTPS (схема A, PEM) |
| 443  | HTTPS (схема B, keystore) |

Оба сервера используют один и тот же `ServerResourceHandler` для обработки запросов.

**Не ставьте `HTTPS_PORT` == `DEFAULT_PORT`** — будет `BindException: Address already in use`.

---

## 5. Переезд на новый сервер

### 5.1. Схема A (PEM)

1. Скопируйте на новый сервер:
    - `example.com.crt` (fullchain);
    - `example.com.key.pem` (**PKCS#8**).

2. Проверьте права:

   ```bash
   chmod 600 /opt/EasyWebServer/certs/example.com.key.pem
   chmod 644 /opt/EasyWebServer/certs/example.com.crt
   chown <user>:<group> /opt/EasyWebServer/certs/example.com.key.pem
   ```

3. Обновите пути в `config.ini`:

   ```json
   "HTTPS_ENABLED": true,
   "HTTPS_CERT": "/new/path/certs/example.com.crt",
   "HTTPS_KEY":  "/new/path/certs/example.com.key.pem"
   ```

4. Запустите сервер. Срок действия сертификата **не меняется** — до той даты, когда он истекал на старом сервере.

### 5.2. Схема B (keystore)

1. Скопируйте `keystore.jks` (или `.p12`) на новый сервер.
2. Обновите пути в `config.ini` (`KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`).
3. Права:

   ```bash
   chmod 600 /opt/EasyWebServer/keystore.jks
   ```

4. Запустите сервер. Браузер увидит тот же сертификат.

---

## 6. Продление (схема A, Let's Encrypt)

Сертификаты Let's Encrypt действуют **90 дней**. При ручном DNS-челлендже автообновления нет.

За 10–15 дней до истечения:

1. Повторно запустите Certbot (файлы обновятся в `/etc/letsencrypt/live/example.com/`):

   ```bash
   sudo certbot certonly --manual --preferred-challenges dns \
        -d example.com -d www.example.com
   ```

2. Замените TXT-записи в DNS на новые коды.
3. Дождитесь 5 минут, нажмите Enter.
4. Скопируйте обновлённые файлы:

   ```bash
   sudo cp /etc/letsencrypt/live/example.com/fullchain.pem /opt/EasyWebServer/certs/example.com.crt
   sudo cp /etc/letsencrypt/live/example.com/privkey.pem  /opt/EasyWebServer/certs/example.com.key.pem
   # если ключ снова в PKCS#1 — сконвертируйте в PKCS#8 (см. §2.3)
   ```

5. **Перезапустите сервер.** `SSLManager` не перечитывает сертификаты на лету — TLS-контекст создаётся один раз при старте (`WebServer.run()` → `SSLManager.createSSLServerSocketFactory(...)`).

### 6.1. Автоматизация (не встроена, но возможна)

`EasyWebServer` не содержит встроенного `CertificateWatcher`. Варианты:

- **Cron + restart**: cron-задача обновляет PEM и перезапускает сервис.
- **Systemd timer + `systemctl restart`**: то же, но аккуратнее.
- **Вручную**, если сертификат один и обновляется редко.

После обновления PEM **обязателен restart**, иначе `SSLServerSocketFactory` продолжит использовать старые сертификаты в памяти.

### 6.2. Продление keystore (схема B)

Если keystore был сделан из тех же PEM:

```bash
openssl pkcs12 -export \
  -in /etc/letsencrypt/live/example.com/fullchain.pem \
  -inkey /etc/letsencrypt/live/example.com/privkey.pem \
  -out /opt/EasyWebServer/keystore.p12 \
  -name easywebserver -passout pass:changeit
```

Затем — restart.

---

## 7. Диагностика

| Симптом | Причина / решение |
|---|---|
| `SSLException: ... InvalidKeySpecException` | Приватный ключ не в PKCS#8. Сконвертируйте через `openssl pkcs8 -topk8 -nocrypt` (§2.3). |
| `SSLException: ... X509` | `.crt` содержит не сертификат или не fullchain (только leaf). Скачайте `fullchain.pem`. |
| `BindException: Address already in use` | `HTTPS_PORT == DEFAULT_PORT`, либо порт занят. Смените порт. |
| `[WebServer] Starting in HTTP mode...` при `HTTPS_ENABLED=true` | `HTTPS_CERT` или `HTTPS_KEY` пустые. Проверьте `config.ini`. |
| `NET::ERR_CERT_AUTHORITY_INVALID` | Используется self-signed (схема B без `KEYSTORE_PATH`) — нормально для dev. |
| `NET::ERR_CERT_COMMON_NAME_INVALID` | CN/SAN сертификата не совпадает с запрошенным хостом. Выпустите на правильный домен. |
| После обновления PEM изменения не видны | Не был сделан restart. TLS-контекст создан при старте. |
| `KeyStoreException: Invalid keystore format` | Схема B ожидает `JKS`/`PKCS12`, но путь/файл не те. `.p12` → PKCS12, иначе JKS. |
| `UnrecoverableKeyException` | Неверный `KEY_PASSWORD` или `KEYSTORE_PASSWORD`. |
| Сервер стартует, но HTTPS «висит» | Порт заблокирован файрволом. Проверьте `ss -ltnp` / `netstat`. |

---

## 8. Безопасность

1. **Права на приватный ключ/keystore** — `chmod 600`, владелец = пользователь, от которого запускается сервер. Никакой `chmod 644` для ключа.
2. **Не публикуйте `HTTPS_KEY` и keystore** в git — добавьте в `.gitignore`.
3. **`HTTPS_ENABLED=true` на основном порту** — тогда весь трафик идёт по TLS. В противном случае всё, что отправлено через HTTP (в т.ч. cookie `session` и пароли `loginDataBase`), передаётся открытым текстом.
4. **`VIDEOCHAT_PORT` тоже требует TLS** для работы `getDisplayMedia()` в браузере — но WebRTC-signaling сервер (`WebRTCSignalingServer`) сам TLS не поддерживает. Для продакшна поднимайте за реверс-прокси (Nginx/Caddy) с TLS.
5. **`session` cookie не имеет `HttpOnly`/`Secure`** в текущей реализации. При выпуске на публичный домен — включите HTTPS (A или B) и убедитесь, что приложение не читает `session` из JS.
6. **Не используйте self-signed в продакшне.** Только для локальной разработки.

---

## 9. Резюме

- **Схема A (PEM, `SSLManager`)** — основной `DEFAULT_PORT`, включается через `HTTPS_ENABLED=true` + `HTTPS_CERT` + `HTTPS_KEY` (или вложенный `"https": {...}`). Ключ — **PKCS#8**. Перечитывания на лету нет: при обновлении сертификата нужен restart.
- **Схема B (keystore, `HttpsServerWrapper`)** — отдельный `HTTPS_PORT`, включается при `HTTPS_PORT > 0`. Формат keystore — `.p12`/PKCS12 или JKS. Пустой `KEYSTORE_PATH` → self-signed для разработки.
- **Обе схемы можно включать одновременно** — на разных портах.
- **Продление** — обновить PEM/keystore и **перезапустить сервер**.
- **Перенос на новый сервер** — скопировать файлы, обновить пути, поправить права, restart. Срок действия не меняется.