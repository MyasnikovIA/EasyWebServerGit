# Установка, запуск EasyWebServer и работа с самоподписанными сертификатами

Документ описывает три сценария:

1. **EasyWebServer как автономная программа** (`java -jar`, systemd, Docker).
2. **EasyWebServer как Java-библиотека** в составе другого приложения (Maven-зависимость, свой `Main`, `WebServer.start()`).
3. **Создание и регистрация самоподписанного сертификата** — `keytool` (JKS/PKCS12) и `openssl` (PEM), установка в систему Linux Mint и в браузеры, чтобы не было предупреждений.

---

# Часть 1. EasyWebServer как автономная программа

## 1.1. Требования

| Компонент | Минимум | Проверка |
|---|---|---|
| Java JDK/JRE | 21+ | `java -version` |
| Maven (для сборки) | 3.8+ | `mvn -version` |
| Linux Mint | 21+ | `lsb_release -a` |
| Windows | 10/11 | `winver` |

Сборка и запуск — одинаковы на Linux и Windows; отличия только в путях.

## 1.2. Сборка fat-JAR

```bash
cd /home/myasnikovia/JavaProject/EasyWebServerGit.git2
mvn clean package -DskipTests
```

Результат — `target/EasyWebServer-1.0-SNAPSHOT.jar` (fat JAR со всеми зависимостями, `mainClass = ru.miacomsoft.Main`).

Проверка:

```bash
ls -lh target/EasyWebServer-1.0-SNAPSHOT.jar
```

## 1.3. Структура каталогов на сервере

Рекомендуемая раскладка:

```
/opt/easywebserver/
├── EasyWebServer-1.0-SNAPSHOT.jar      ← fat JAR
├── config.ini                           ← конфиг (опционально, если Main читает)
├── certs/
│   └── keystore.p12                     ← TLS-сертификат (или PEM)
└── logs/
    └── server.log                       ← лог (если LOG_FILE задан)

/var/www/easywebserver-www/              ← WEBAPP_DIR (статика, .html, .java, компоненты)
```

Права:

```bash
sudo mkdir -p /opt/easywebserver/{certs,logs} /var/www/easywebserver-www
sudo chown -R myasnikovia:myasnikovia /opt/easywebserver /var/www/easywebserver-www
chmod 755 /opt/easywebserver /var/www/easywebserver-www
chmod 700 /opt/easywebserver/certs
chmod 600 /opt/easywebserver/certs/keystore.p12
```

## 1.4. Запуск из терминала

### Linux / macOS

```bash
cd /opt/easywebserver
java -jar EasyWebServer-1.0-SNAPSHOT.jar
```

Если параметры не захардкожены в `Main.java`, `Main` читает `config.ini` из рабочего каталога:

```bash
java -jar EasyWebServer-1.0-SNAPSHOT.jar
# или явно указать рабочий каталог:
java -Duser.dir=/opt/easywebserver -jar /opt/easywebserver/EasyWebServer-1.0-SNAPSHOT.jar
```

### Windows

```bat
cd C:\EasyWebServer
java -jar EasyWebServer-1.0-SNAPSHOT.jar
```

Если `java` не в PATH, используйте полный путь:

```bat
"C:\Program Files\Java\jdk-21\bin\java.exe" -jar EasyWebServer-1.0-SNAPSHOT.jar
```

**Остановка** — `Ctrl+C`. Для фонового запуска на Linux — `nohup`:

```bash
nohup java -jar EasyWebServer-1.0-SNAPSHOT.jar > /opt/easywebserver/logs/stdout.log 2>&1 &
echo $! > /opt/easywebserver/easywebserver.pid
```

Остановка:

```bash
kill $(cat /opt/easywebserver/easywebserver.pid)
```

`nohup` — быстрый способ, но для продакшена лучше systemd (см. §1.5).

## 1.5. systemd-сервис (Linux Mint, продакшен)

Создать unit-файл:

```bash
sudo nano /etc/systemd/system/easywebserver.service
```

```ini
[Unit]
Description=EasyWebServer
After=network.target

[Service]
Type=simple
User=myasnikovia
Group=myasnikovia
WorkingDirectory=/opt/easywebserver
ExecStart=/usr/bin/java -Xmx512m -jar /opt/easywebserver/EasyWebServer-1.0-SNAPSHOT.jar
Restart=on-failure
RestartSec=5
StandardOutput=append:/opt/easywebserver/logs/stdout.log
StandardError=append:/opt/easywebserver/logs/stderr.log

# Hardening (опционально, но полезно)
NoNewPrivileges=true
PrivateTmp=yes
ProtectSystem=full
ProtectHome=read-only
ReadWritePaths=/opt/easywebserver/logs /var/www/easywebserver-www

[Install]
WantedBy=multi-user.target
```

Включить и запустить:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now easywebserver
sudo systemctl status easywebserver
```

Логи в реальном времени:

```bash
journalctl -u easywebserver -f
# или
tail -f /opt/easywebserver/logs/stdout.log /opt/easywebserver/logs/stderr.log
```

Рестарт / остановка:

```bash
sudo systemctl restart easywebserver
sudo systemctl stop easywebserver
```

**Важно:** `AmbientCapabilities` **не нужны**, если сервер слушает непривилегированный порт (9092, 8080). Если нужен порт 80/443 — либо проброс `443 → 9092` на роутере / nft-redirect (см. `Настройка_HTTPS_и_SSL-сертификата_для_FileServer.MD`), либо `AmbientCapabilities=CAP_NET_BIND_SERVICE`. **Не** используйте `setcap` на `java` — это ломает `libjli.so`.

## 1.6. Запуск в Docker

`Dockerfile`:

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/EasyWebServer-1.0-SNAPSHOT.jar app.jar
COPY certs/keystore.p12 certs/keystore.p12
COPY config.ini config.ini
EXPOSE 9092
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

`docker-compose.yml`:

```yaml
version: "3.9"
services:
  easywebserver:
    build: .
    ports:
      - "9092:9092"
      - "8081:8081"
    volumes:
      - ./www:/app/www
      - ./logs:/app/logs
    restart: unless-stopped
```

Сборка и запуск:

```bash
docker compose up -d --build
docker compose logs -f easywebserver
```

Остановка:

```bash
docker compose down
```

## 1.7. Windows как служба (через NSSM)

1. Скачать **NSSM** — `https://nssm.cc/download`, распаковать.
2. Открыть `cmd.exe` от имени администратора:

```bat
nssm install EasyWebServer "C:\Program Files\Java\jdk-21\bin\java.exe" "-jar C:\EasyWebServer\EasyWebServer-1.0-SNAPSHOT.jar"
nssm set EasyWebServer AppDirectory "C:\EasyWebServer"
nssm set EasyWebServer AppStdout "C:\EasyWebServer\logs\stdout.log"
nssm set EasyWebServer AppStderr "C:\EasyWebServer\logs\stderr.log"
nssm start EasyWebServer
```

Проверка:

```bat
nssm status EasyWebServer
sc query EasyWebServer
```

Остановка и удаление:

```bat
nssm stop EasyWebServer
nssm remove EasyWebServer confirm
```

## 1.8. Проверка после запуска

```bash
# сервер слушает порт?
sudo ss -ltnp | grep ':9092 '

# локально TLS работает?
curl -vk https://127.0.0.1:9092/

# из локальной сети (другой IP)
curl -vk https://192.168.х.х:9092/

# из интернета (если настроен проброс на роутере)
curl -v https://www.smwrap.ru/
```

В логе при старте:

```
[WebServer] Starting in HTTPS mode...
[WebServer] Keystore: /opt/easywebserver/certs/keystore.p12
[WebServer] Listen port: 9092
[SSLManager] Alias 'server'
[SSLManager]   Subject: CN=smwrap.ru
[SSLManager]   Issuer : CN=YE2, O=Let's Encrypt, C=US
[SSLManager] SSLContext init OK (keystore), protocol=TLS
[SSLManager] SSLServerSocket ready on port 9092
```

---

# Часть 2. EasyWebServer в составе другой Java-программы

## 2.1. Вариант A — Maven-зависимость

### 2.1.1. Установить EasyWebServer в локальный репозиторий

```bash
cd /home/myasnikovia/JavaProject/EasyWebServerGit.git2
mvn install -DskipTests
```

В `~/.m2/repository/ru/miacomsoft/EasyWebServer/1.0-SNAPSHOT/` появятся артефакты.

### 2.1.2. Подключить в `pom.xml` своего проекта

```xml
<dependency>
    <groupId>ru.miacomsoft</groupId>
    <artifactId>EasyWebServer</artifactId>
    <version>1.0-SNAPSHOT</version>
    <exclusions>
        <!-- Исключите, если у вас уже есть эти зависимости: -->
        <!-- <exclusion><groupId>com.oracle.database.jdbc</groupId><artifactId>ojdbc8</artifactId></exclusion> -->
    </exclusions>
</dependency>
```

Зависимости проекта (по `pom.xml` EasyWebServer): `ojdbc8`, `xdb`, `orai18n` (Oracle), `postgresql`, `org.json`, `jsoup`.

### 2.1.3. Свой `Main` + `WebServer.start()`

```java
package com.example.myapp;

import ru.miacomsoft.EasyWebServer.WebServer;

public class MyApp {
    public static void main(String[] args) {
        WebServer web = new WebServer(MyApp.class);   // ← сканирует com.example.myapp.*

        // --- Основные параметры ---
        web.config("DEFAULT_HOST", "0.0.0.0");
        web.config("DEFAULT_PORT", "9092");
        web.config("APP_NAME",     "myapp");
        web.config("WEBAPP_DIR",   "/opt/myapp/www");
        web.config("DEBUG",        "false");
        web.config("CAHEBLE",      "true");

        // --- HTTPS через keystore ---
        web.config("HTTPS_ENABLED",           "true");
        web.config("HTTPS_KEYSTORE",          "/opt/myapp/certs/keystore.p12");
        web.config("HTTPS_KEYSTORE_PASSWORD", "changeit");

        // --- WebRTC (опционально) ---
        // web.config("VIDEOCHAT_PORT", "8081");

        web.start();   // блокирует поток — ставьте последним
    }
}
```

**Ключевое:**

- `new WebServer(MyApp.class)` **сканирует пакет `MyApp` и все подпакеты** на методы с `@onPage`, `@Get`, `@Post`, `@Put`, `@Delete`, `@Head`, `@Patch`, `@Options`, `@Trace`, `@onTerminal`.
- `web.start()` **блокирует** вызывающий поток (`thread.join()`). Если после `start()` нужна работа — запускайте сервер в отдельном потоке:

  ```java
  new Thread(web::start, "easywebserver").start();
  ```

- `WebServer` — **singleton** (static `server`). Второй `new WebServer(...)` не даст второй сервер.
- `WebServer.stop()` — остановка.

### 2.1.4. Регистрация эндпоинтов

**Через аннотации** (`@Get` / `@Post` / `@onPage`):

```java
package com.example.myapp;

import org.json.JSONObject;
import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.util.queryType.Get;
import ru.miacomsoft.EasyWebServer.util.queryType.Post;

public class MyApiController {

    @Get(url = "api/hello", mime = "application/json; charset=utf-8")
    public JSONObject hello(HttpExchange query) {
        String name = query.requestParam.optString("name", "Гость");
        return new JSONObject().put("message", "Привет, " + name + "!");
    }

    @Post(url = "api/save", mime = "application/json; charset=utf-8")
    public JSONObject save(HttpExchange query) {
        JSONObject data = query.messageJson;
        if (data == null) data = new JSONObject(
                new String(query.postByte, java.nio.charset.StandardCharsets.UTF_8));
        return new JSONObject().put("status", "ok").put("received", data);
    }
}
```

**Через программную регистрацию** (без аннотаций):

```java
web.onPage("/inline.html", new StringBuffer("<h1>Inline</h1>"));

web.onPage("/dynamic.html", (CallbackPage) query -> {
    String name = query.requestParam.optString("name", "Guest");
    return ("<h1>" + name + "</h1>").getBytes();
});

web.onPage("/my.json", new java.io.File("/opt/myapp/data/my.json"));
```

**Глобальный `CallbackProcedure`** — перехватывает все запросы до стандартной обработки:

```java
web.onPage((CallbackProcedure) query -> {
    // если не хотите перехватывать — просто ничего не пишите в query
});
```

### 2.1.5. Точки отказа при встраивании

| Что | Не работает | Работает |
|---|---|---|
| `@Get` / `@Post` | HTTP-метод не проверяется | `url` — точное совпадение |
| `ext` в аннотации | не используется | игнорируется |
| `mime` в аннотации | применяется ПОСЛЕ `invoke` | задавайте явно |
| `@onPage` возврат | `JSONObject` → `ClassCastException` | только `byte[]` |
| `@Get` возврат | `String`/`byte[]` → `ClassCastException` | только `JSONObject` |
| Один URL — один метод | `@Get("x")` + `@Post("x")` → второй затирает первый | один метод на URL |
| Разделить GET/POST | только через `.java`-файл с `if (query.typeQuery)` | §3.3 в `UseJarLibEasyWebServer.md` |
| `query.SQL` | не параметризован | `PostgreQuery.executeQuery(...)` |

## 2.2. Вариант B — «частичное» использование (только HTTP-сервер, без БД и компонентов)

Если нужен только HTTP-сервер без `EasyWebServer.component`, `WebServer.start()` сам по себе уже даёт базовые вещи: статика, `.java`-файлы, `{component}/Xxx`, `<cmpXXX>`.

Чтобы **не тянуть** все компоненты, можно использовать `WebServer` напрямую, а не `new WebServer(MyApp.class)`:

```java
WebServer web = new WebServer();       // без сканирования
web.config("DEFAULT_PORT", "8080");
web.config("WEBAPP_DIR",   "/opt/myapp/www");
web.start();
```

Тогда:
- `@onPage` / `@Get` / `@Post` в вашем приложении **не регистрируются** (нет сканирования).
- Статика, `.java`-файлы, компоненты из `WEBAPP_DIR` **работают**.
- `web.onPage(...)` (программная регистрация) — работает.

## 2.3. Вариант C — в составе Spring Boot / другого фреймворка

EasyWebServer использует `java.net.ServerSocket` на своём порту. Он **не мешает** встроенному Tomcat/Jetty (у них другой порт). Пример:

```java
@SpringBootApplication
public class App {
    public static void main(String[] args) {
        // Свой EasyWebServer на 9092 — в отдельном потоке
        new Thread(() -> {
            WebServer web = new WebServer(App.class);
            web.config("DEFAULT_PORT", "9092");
            web.config("WEBAPP_DIR",   "www");
            web.start();
        }, "easywebserver").start();

        // Spring Boot на 8080
        SpringApplication.run(App.class, args);
    }
}
```

Оба сервера работают одновременно на разных портах. `WebServer` регистрирует `ShutDown` в `Runtime.addShutdownHook` — корректно остановится при завершении JVM.

## 2.4. Встраивание с управлением жизненным циклом

Если EasyWebServer — вспомогательная часть, а основная программа должна им управлять:

```java
public class MyApp {
    private static WebServer web;

    public static void main(String[] args) throws Exception {
        web = new WebServer(MyApp.class);
        web.config("DEFAULT_PORT", "9092");
        web.config("WEBAPP_DIR",   "www");

        Thread serverThread = new Thread(() -> {
            try {
                web.start();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "easywebserver");
        serverThread.setDaemon(true);
        serverThread.start();

        // ... остальная логика ...

        // При необходимости остановить:
        // WebServer.stop();
    }
}
```

`setDaemon(true)` — если основной поток завершится, сервер тоже остановится.

## 2.5. Сборка fat-JAR с встроенным EasyWebServer

Если ваш проект использует Maven — подключите `maven-shade-plugin`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <version>3.5.0</version>
    <executions>
        <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
                <transformers>
                    <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                        <mainClass>com.example.myapp.MyApp</mainClass>
                    </transformer>
                </transformers>
                <filters>
                    <filter>
                        <artifact>*:*</artifact>
                        <excludes>
                            <exclude>META-INF/*.SF</exclude>
                            <exclude>META-INF/*.DSA</exclude>
                            <exclude>META-INF/*.RSA</exclude>
                        </excludes>
                    </filter>
                </filters>
            </configuration>
        </execution>
    </executions>
</plugin>
```

Сборка и запуск:

```bash
mvn clean package
java -jar target/myapp-1.0-SNAPSHOT.jar
```

В `MyApp` — свой `mainClass`. **Внутри JAR не должно быть `ru.miacomsoft.Main` как entrypoint** (shade использует ваш `mainClass`).

## 2.6. Запуск EasyWebServer только по HTTPS

Одна схема HTTPS (в актуальной версии `HttpsServerWrapper` и `HTTPS_PORT` удалены). Через `SSLManager` на `DEFAULT_PORT`:

```java
web.config("HTTPS_ENABLED",           "true");
web.config("HTTPS_KEYSTORE",          "/opt/myapp/certs/keystore.p12");
web.config("HTTPS_KEYSTORE_PASSWORD", "changeit");
web.config("DEFAULT_PORT",            "9092");
```

Если keystore не задан, но заданы `HTTPS_CERT` + `HTTPS_KEY` — используется PEM-вариант:

```java
web.config("HTTPS_CERT", "/opt/myapp/certs/fullchain.pem");
web.config("HTTPS_KEY",  "/opt/myapp/certs/privkey.pem");
```

`HTTP_REDIRECT_PORT` — отдельный plain HTTP listener для `301 → HTTPS`:

```java
web.config("HTTP_REDIRECT_PORT", "80");
```

Порты `DEFAULT_PORT` и `HTTP_REDIRECT_PORT` **должны различаться**.

## 2.7. Использование как библиотеки без БД и без компонентов

Если ваш проект не использует PostgreSQL/Oracle и `ru.miacomsoft.EasyWebServer.component.*`, можно **не тащить** тяжёлые зависимости через `<exclusions>` в `pom.xml`. Сервер работать будет, но:

- `query.SQL` вернёт **пустой `JSONArray`** (нет JDBC).
- `<cmpXXX>` не заработают (нет компонентов в classpath).
- `PostgreQuery` / `OracleQuery` упадут при вызове — не используйте их.

Тогда достаточно оставить только `org.json`, `jsoup` — сами по себе они небольшие.

---

# Часть 3. Создание и регистрация самоподписанного сертификата

Самоподписанный сертификат (`self-signed`) подписан **сам собой** (`Issuer = Subject`), а не публичным CA. Браузеры по умолчанию **не доверяют** ему, но если **добавить сертификат в доверенные** на клиенте — предупреждения исчезнут.

**Область применения:**

- ✅ Локальная разработка.
- ✅ Внутренняя сеть (LAN), где все клиенты под вашим контролем.
- ❌ Публичный сайт в интернете — нужен настоящий Let's Encrypt или платный сертификат.

## 3.1. Создание keystore через `keytool` (JKS или PKCS12)

`keytool` — входит в JDK.

### 3.1.1. Одна команда — PKCS12

```bash
keytool -genkeypair \
  -alias server \
  -keyalg RSA \
  -keysize 2048 \
  -validity 3650 \
  -storetype PKCS12 \
  -keystore /opt/easywebserver/certs/keystore.p12 \
  -storepass changeit \
  -keypass changeit \
  -dname "CN=myapp.local, OU=Dev, O=MyCompany, L=City, S=State, C=RU" \
  -ext "SAN=dns:myapp.local,dns:localhost,ip:127.0.0.1,ip:192.168.1.42"
```

Разбор параметров:

| Параметр | Значение |
|---|---|
| `-alias server` | алиас — `SSLManager` перебирает все alias'ы, `server` — стандартное имя |
| `-keyalg RSA -keysize 2048` | алгоритм и длина ключа |
| `-validity 3650` | срок действия — 10 лет (для локального удобства) |
| `-storetype PKCS12` | формат `keystore.p12` (Java умеет читать) |
| `-storepass changeit` | пароль keystore (на проде сменить) |
| `-keypass changeit` | пароль ключа (обычно совпадает) |
| `-dname` | subject: `CN` — **должен** совпадать с hostname, к которому обращаются |
| `-ext "SAN=..."` | **обязательно** — современные браузеры проверяют SAN, а не CN |

**SAN — самое важное.** Браузер сверяет именно SAN (Subject Alternative Name) с hostname из URL. Если в SAN нет `www.smwrap.ru`, браузер откажет в доступе к `https://www.smwrap.ru/`, даже если CN=`smwrap.ru`.

Правила SAN:

- `dns:` — для доменных имён (`myapp.local`, `localhost`, `www.smwrap.ru`).
- `ip:` — для IP-адресов (`127.0.0.1`, `192.168.1.42`).
- Перечисление через запятую.
- `*.example.com` — wildcard (только для одного уровня).

### 3.1.2. JKS (устаревший формат, но поддерживается)

```bash
keytool -genkeypair \
  -alias server \
  -keyalg RSA \
  -keysize 2048 \
  -validity 3650 \
  -storetype JKS \
  -keystore /opt/easywebserver/certs/keystore.jks \
  -storepass changeit \
  -keypass changeit \
  -dname "CN=myapp.local, OU=Dev, O=MyCompany, L=City, S=State, C=RU" \
  -ext "SAN=dns:myapp.local,dns:localhost,ip:127.0.0.1"
```

`SSLManager` определяет тип по расширению: `.p12`/`.pfx` → PKCS12, иначе → JKS.

### 3.1.3. Проверка keystore

```bash
keytool -list -v \
  -keystore /opt/easywebserver/certs/keystore.p12 \
  -storepass changeit
```

Ключевое в выводе:

```
Alias name: server
Entry type: PrivateKeyEntry
Owner: CN=myapp.local, OU=Dev, O=MyCompany, L=City, S=State, C=RU
Issuer: CN=myapp.local, OU=Dev, O=MyCompany, L=City, S=State, C=RU    ← = Owner ⇒ self-signed
Valid from: ... until: ...
Subject Alternative Name:
    DNS:myapp.local, DNS:localhost, IP Address:127.0.0.1
```

`Issuer = Owner` — это нормально для self-signed. Проблема возникает, если `Issuer` — **публичный CA** (тогда сертификат должен быть выдан CA, а не создан локально).

### 3.1.4. Экспорт сертификата (`.crt`) для клиентов

Чтобы клиенты могли добавить сертификат в доверенные — нужен только **публичный** сертификат (без приватного ключа):

```bash
keytool -exportcert \
  -alias server \
  -keystore /opt/easywebserver/certs/keystore.p12 \
  -storepass changeit \
  -rfc \
  -file /opt/easywebserver/certs/myapp.crt
```

`-rfc` → формат PEM (`-----BEGIN CERTIFICATE-----`). Файл `myapp.crt` можно раздавать клиентам.

## 3.2. Создание сертификата через `openssl` (PEM)

Если предпочитаете PEM-файлы (для `HTTPS_CERT` / `HTTPS_KEY`):

### 3.2.1. Создание приватного ключа и самоподписанного сертификата одной командой

```bash
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout /opt/easywebserver/certs/privkey.pem \
  -out /opt/easywebserver/certs/fullchain.pem \
  -days 3650 \
  -subj "/CN=myapp.local/OU=Dev/O=MyCompany/L=City/S=State/C=RU" \
  -addext "subjectAltName=DNS:myapp.local,DNS:localhost,IP:127.0.0.1,IP:192.168.1.42"
```

Разбор:

| Параметр | Значение |
|---|---|
| `-x509` | создать самоподписанный (не запрос в CA) |
| `-newkey rsa:2048` | сгенерировать новый ключ |
| `-nodes` | ключ без пароля (иначе `openssl` попросит пароль при каждом чтении) |
| `-keyout privkey.pem` | приватный ключ (PKCS#8, `-----BEGIN PRIVATE KEY-----`) |
| `-out fullchain.pem` | публичный сертификат |
| `-days 3650` | 10 лет |
| `-subj` | subject (CN и т.д.) |
| `-addext "subjectAltName=..."` | **SAN** — обязательное поле |

### 3.2.2. Проверка PEM-сертификата

```bash
openssl x509 -in /opt/easywebserver/certs/fullchain.pem -noout -text | \
  grep -E "Subject:|Issuer:|Not Before|Not After|DNS:"

openssl pkey -in /opt/easywebserver/certs/privkey.pem -pubout
```

Ключ должен начинаться с `-----BEGIN PRIVATE KEY-----` (**PKCS#8**). Если `-----BEGIN RSA PRIVATE KEY-----` (**PKCS#1**) — конвертируйте:

```bash
openssl pkcs8 -topk8 -nocrypt \
  -in /opt/easywebserver/certs/privkey.pem \
  -out /opt/easywebserver/certs/privkey_pkcs8.pem
```

`SSLManager` читает **только PKCS#8**.

### 3.2.3. Упаковка PEM в `keystore.p12`

Если хотите использовать PEM, но `EasyWebServer` настроен на keystore — конвертируйте:

```bash
openssl pkcs12 -export \
  -in /opt/easywebserver/certs/fullchain.pem \
  -inkey /opt/easywebserver/certs/privkey.pem \
  -out /opt/easywebserver/certs/keystore.p12 \
  -name server \
  -passout pass:changeit
```

Проверка `keytool -list` — как в §3.1.3.

## 3.3. Конфигурация EasyWebServer на использование сертификата

### 3.3.1. Через `Main.java`

**keystore (PKCS12/JKS):**

```java
web.config("HTTPS_ENABLED",           "true");
web.config("HTTPS_KEYSTORE",          "/opt/easywebserver/certs/keystore.p12");
web.config("HTTPS_KEYSTORE_PASSWORD", "changeit");
```

**PEM (fallback):**

```java
web.config("HTTPS_ENABLED", "true");
web.config("HTTPS_CERT",    "/opt/easywebserver/certs/fullchain.pem");
web.config("HTTPS_KEY",     "/opt/easywebserver/certs/privkey_pem_pkcs8.pem");
```

Приоритет: если задан `HTTPS_KEYSTORE` — используется он, `HTTPS_CERT`/`HTTPS_KEY` игнорируются.

### 3.3.2. Через `config.ini`

```json
"https": {
    "enabled": true,
    "keystore": "/opt/easywebserver/certs/keystore.p12",
    "password": "changeit"
}
```

или

```json
"https": {
    "enabled": true,
    "cert": "/opt/easywebserver/certs/fullchain.pem",
    "key":  "/opt/easywebserver/certs/privkey_pkcs8.pem"
}
```

### 3.3.3. Запуск и проверка

```bash
java -jar EasyWebServer-1.0-SNAPSHOT.jar
```

В логе:

```
[SSLManager] Alias 'server'
[SSLManager]   Subject: CN=myapp.local, OU=Dev, O=MyCompany, L=City, S=State, C=RU
[SSLManager]   Issuer : CN=myapp.local, OU=Dev, O=MyCompany, L=City, S=State, C=RU
[SSLManager] SSLContext init OK (keystore), protocol=TLS
[SSLManager] SSLServerSocket ready on port 9092
```

`Issuer = Subject` — это и есть self-signed.

Проверка:

```bash
# Локально (self-signed не пройдёт проверку — используйте -k)
curl -vk https://127.0.0.1:9092/

# Или с явно указанным сертификатом
curl --cacert /opt/easywebserver/certs/myapp.crt https://127.0.0.1:9092/
```

`curl --cacert` — если сертификат добавлен в доверенные **для этого запроса**. Дальше — постоянная регистрация в системе (§3.4) и браузерах (§3.5).

## 3.4. Регистрация самоподписанного сертификата в системе Linux Mint

Чтобы система (и `curl` без `-k`, и Java-приложения) доверяла вашему сертификату.

### 3.4.1. Системное хранилище CA (Debian/Ubuntu/Mint)

```bash
# 1. Скопировать .crt (PEM-сертификат) в системный каталог
sudo cp /opt/easywebserver/certs/myapp.crt /usr/local/share/ca-certificates/myapp.crt

# 2. Обновить хранилище
sudo update-ca-certificates
```

Вывод должен содержать:

```
Adding debian:myapp.pem
```

Проверка:

```bash
# curl без -k теперь должен работать, если SAN совпадает с hostname
curl -v https://myapp.local:9092/

# или
openssl s_client -connect myapp.local:9092 -servername myapp.local -CAfile /etc/ssl/certs/ca-certificates.crt < /dev/null
# Verify return code: 0 (ok)
```

Отзыв (если больше не нужен):

```bash
sudo rm /usr/local/share/ca-certificates/myapp.crt
sudo update-ca-certificates --fresh
```

### 3.4.2. Доверие в Java (JVM cacerts)

Если клиент — **Java-приложение** (не браузер), оно использует **свой** truststore, а не системный. Добавьте сертификат:

```bash
sudo keytool -importcert \
  -alias myapp \
  -file /opt/easywebserver/certs/myapp.crt \
  -keystore $JAVA_HOME/lib/security/cacerts \
  -storepass changeit \
  -noprompt
```

`$JAVA_HOME` — корень JDK/JRE. Пароль по умолчанию — `changeit`. Проверка:

```bash
keytool -list -v -keystore $JAVA_HOME/lib/security/cacerts -storepass changeit | grep -A2 myapp
```

Отзыв:

```bash
sudo keytool -delete -alias myapp \
  -keystore $JAVA_HOME/lib/security/cacerts -storepass changeit
```

## 3.5. Регистрация самоподписанного сертификата в браузере

### 3.5.1. Chrome / Chromium / Edge (Linux)

Chrome на Linux использует **системное хранилище** NSS. На Linux Mint (Debian-based) после §3.4.1 Chrome должен автоматически доверять сертификату. Перезапустите браузер.

Если не работает — добавьте вручную в NSS:

```bash
# Установить certutil
sudo apt install libnss3-tools

# Добавить сертификат в базу NSS пользователя
certutil -d sql:$HOME/.pki/nssdb -A \
  -t "C,," \
  -n "MyApp Self-Signed" \
  -i /opt/easywebserver/certs/myapp.crt
```

Проверка:

```bash
certutil -d sql:$HOME/.pki/nssdb -L
```

Отзыв:

```bash
certutil -d sql:$HOME/.pki/nssdb -D -n "MyApp Self-Signed"
```

### 3.5.2. Chrome / Edge (Windows)

1. **Win + R** → `certmgr.msc` → **Доверенные корневые центры сертификации** → **Сертификаты**.
2. Правый клик → **Все задачи → Импорт**.
3. Выбрать `/opt/easywebserver/certs/myapp.crt` (или `myapp.pem`).
4. **Далее → Далее → Готово**. При предупреждении — **Да**.
5. Перезапустить Chrome.

### 3.5.3. Firefox (свой truststore)

Firefox на Linux использует **свой** NSS-профиль, не системный. Варианты:

**Через GUI:**

1. **Настройки → Приватность и защита → Сертификаты → Просмотр сертификатов → Сертификаты → Импорт**.
2. Выбрать `myapp.crt`.
3. Поставить галочку **«Доверять этому сертификату для идентификации веб-сайтов»**.

**Через `certutil` (для всех профилей):**

```bash
for prof in $HOME/.mozilla/firefox/*.default*; do
  certutil -d sql:"$prof" -A -t "C,," -n "MyApp Self-Signed" -i /opt/easywebserver/certs/myapp.crt
done
```

Перезапустить Firefox.

### 3.5.4. Safari (macOS)

Открыть `myapp.crt` (двойной клик) → **Keychain Access** → **System** → **Certificates** → двойной клик по `myapp` → **Trust** → **When using this certificate: Always Trust**.

### 3.5.5. Android

1. Скопировать `myapp.crt` на устройство.
2. **Настройки → Безопасность → Шифрование и учётные данные → Установить сертификат → Сертификат CA**.
3. Выбрать файл, подтвердить.
4. На Android 7+ **пользовательские CA** используются для браузера и обычных приложений, но **не для всех** (некоторые приложения игнорируют user CA — требуют system CA или явной настройки).

### 3.5.6. iOS

1. Скопировать `myapp.crt` в Files или отправить по AirDrop.
2. Открыть → **Установить профиль** → **Установить**.
3. **Настройки → Основные → Об этом устройстве → Доверие сертификатам** → включить полный доступ к `myapp.crt`.

## 3.6. Проверка после регистрации

С клиента:

```bash
# curl с системным CA
curl -v https://myapp.local:9092/

# Ожидается:
# * SSL certificate verify ok.
# < HTTP/1.1 200 OK
```

В браузере:

- Открыть `https://myapp.local:9092/`.
- Замок — зелёный, без предупреждений.
- Если предупреждение — **не** нажимайте «Перейти на сайт (небезопасно)», а разберитесь, чего не хватает: чаще всего SAN не покрывает hostname или сертификат не в том truststore.

Диагностика:

```bash
# Проверка, что сервер отдаёт сертификат
openssl s_client -connect myapp.local:9092 -servername myapp.local -showcerts < /dev/null

# SAN сертификата
echo | openssl s_client -connect myapp.local:9092 -servername myapp.local 2>/dev/null | \
  openssl x509 -noout -ext subjectAltName

# Проверка против системного CA
openssl s_client -connect myapp.local:9092 -servername myapp.local -CAfile /etc/ssl/certs/ca-certificates.crt < /dev/null | grep "Verify return code"
# Verify return code: 0 (ok)
```

## 3.7. Обновление самоподписанного сертификата

Срок действия — 10 лет (при `-days 3650`). При истечении (или при смене hostname/IP — тогда нужен новый SAN):

1. Пересоздать keystore / PEM (§3.1 или §3.2).
2. Заменить файл `keystore.p12` / `fullchain.pem` на сервере.
3. **Перезапустить EasyWebServer** — `SSLManager` читает сертификат только при старте.
4. **Экспортировать новый сертификат** (§3.1.4) и **обновить его на клиентах** (§3.4, §3.5).
5. Старые версии сертификата в truststore удалить.

## 3.8. Что делать, если браузер всё равно ругается

| Симптом | Причина | Решение |
|---|---|---|
| `NET::ERR_CERT_AUTHORITY_INVALID` | сертификат не в truststore | добавить в §3.4/§3.5 |
| `NET::ERR_CERT_COMMON_NAME_INVALID` | SAN не покрывает hostname | пересоздать с нужным `-ext SAN=...` |
| `NET::ERR_CERT_DATE_INVALID` | истёк / системное время неверно | пересоздать, проверить `date` |
| `NET::ERR_CERT_SYMANTEC_LEGACY` | устаревший алгоритм подписи | использовать RSA 2048 и SHA-256 (по умолчанию в новых JDK/OpenSSL) |
| Chrome игнорирует системный CA | Chrome на Linux использует NSS | `certutil` (§3.5.1) |
| Firefox игнорирует системный CA | Firefox использует свой NSS | `certutil` (§3.5.3) |
| Java-клиент отказывается | JVM не доверяет | `keytool -importcert` в `$JAVA_HOME/lib/security/cacerts` (§3.4.2) |
| Android 7+ игнорирует user CA | политика безопасности | только через system CA (root) или Network Security Config в приложении |
| iOS «Не удалось проверить» | не включено полное доверие | Настройки → Доверие сертификатам (§3.5.6) |
| `SSLHandshakeException: Remote host terminated the handshake` | клиент отбросил серт | проверить SAN и добавить в truststore |

**Важно:** самоподписанный сертификат **не** решает проблему MitM и **не** заменяет настоящий. Используйте только для локальной разработки или внутренней сети с контролируемыми клиентами. Для публичного сайта — Let's Encrypt (см. `Настройка_HTTPS_и_SSL-сертификата_для_FileServer.MD`).

---

# 4. Итоговая сводка

## 4.1. Автономная программа

| Шаг | Команда |
|---|---|
| Сборка | `mvn clean package` |
| Запуск | `java -jar target/EasyWebServer-1.0-SNAPSHOT.jar` |
| systemd | `/etc/systemd/system/easywebserver.service` + `systemctl enable --now easywebserver` |
| Docker | `Dockerfile` + `docker compose up -d` |
| Windows-служба | NSSM |

## 4.2. Как библиотека

| Шаг | Команда |
|---|---|
| Установка | `mvn install` |
| Зависимость | `<dependency>` в `pom.xml` |
| Точка входа | `new WebServer(MyApp.class)` + `web.config(...)` + `web.start()` |
| Эндпоинты | `@Get` / `@Post` / `@onPage` / `web.onPage(...)` |
| HTTPS | `HTTPS_KEYSTORE` / `HTTPS_KEYSTORE_PASSWORD` |

## 4.3. Самоподписанный сертификат

| Задача | Команда |
|---|---|
| Создать keystore | `keytool -genkeypair -alias server -storetype PKCS12 -keystore keystore.p12 -dname "..." -ext "SAN=..."` |
| Создать PEM | `openssl req -x509 -newkey rsa:2048 -nodes -keyout privkey.pem -out fullchain.pem -days 3650 -addext "subjectAltName=..."` |
| PEM → keystore | `openssl pkcs12 -export -in fullchain.pem -inkey privkey.pem -out keystore.p12 -name server -passout pass:changeit` |
| Экспорт для клиента | `keytool -exportcert -rfc -alias server -keystore keystore.p12 -storepass changeit -file myapp.crt` |
| Регистрация в Linux | `sudo cp myapp.crt /usr/local/share/ca-certificates/ && sudo update-ca-certificates` |
| Регистрация в Chrome (Linux) | `certutil -d sql:$HOME/.pki/nssdb -A -t "C,," -n "MyApp" -i myapp.crt` |
| Регистрация в Firefox | `certutil -d sql:$PROFILE -A -t "C,," -n "MyApp" -i myapp.crt` |
| Регистрация в Java | `sudo keytool -importcert -alias myapp -file myapp.crt -keystore $JAVA_HOME/lib/security/cacerts -storepass changeit -noprompt` |

## 4.4. Что важно не забыть

- **`SAN`** — обязательное поле для современного TLS (браузеры сверяют SAN, а не CN).
- **`SSLManager` читает keystore только при старте** — после замены `keystore.p12` нужен **перезапуск**.
- **Приватный ключ PEM** — **PKCS#8** (`-----BEGIN PRIVATE KEY-----`), не PKCS#1. Конвертация: `openssl pkcs8 -topk8 -nocrypt -in privkey.pem -out privkey_pkcs8.pem`.
- **`changeit`** — дефолтный пароль. На проде менять и хранить в `EnvironmentFile` / secret-manager.
- **`setcap` на `java`** — не делать. Ломает `libjli.so`.
- **Самоподписанный сертификат** — только для разработки или LAN. Для публичного сайта — Let's Encrypt.
- **`DEFAULT_PORT`** — непривилегированный (9092, 8080). Порт 80/443 — через проброс на роутере или `HTTP_REDIRECT_PORT` / nft-redirect.
- **Не пробрасывать `:80 → :DEFAULT_PORT`** — plain HTTP на TLS-порт даст `SSLException` и «Без названия».
- **`Issuer ≠ Subject`** — признак настоящего сертификата. `Issuer = Subject` — self-signed, браузеры по умолчанию не доверяют.
- **`@Get` / `@Post` не проверяют HTTP-метод** — разделение по методу только через `.java`-файлы с `if (query.typeQuery)`.
- **`query.SQL` не параметризован** — используйте `PostgreQuery.executeQuery(sql, params, dbName, user, pass)`.