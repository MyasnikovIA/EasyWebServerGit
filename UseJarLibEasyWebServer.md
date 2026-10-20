# Использование EasyWebServer как JAR-библиотеки

Документ описывает **фактическую реализацию** `EasyWebServer2`: как подключить, как регистрировать эндпоинты, как они на самом деле маршрутизируются, чем отличаются `@onPage` от `@Get`/`@Post`, как работает `{component}/...`, динамические `.java` файлы, БД, HTTPS (две схемы) и WebRTC. В конце — раздел «Известные подводные камни»: там отличия от старых версий документации.

---

## 1. Подключение

### 1.1. Сборка

```bash
mvn clean package
```

`maven-shade-plugin` в `pom.xml` собирает **fat JAR** с `mainClass = ru.miacomsoft.Main`:

- `target/EasyWebServer-1.0-SNAPSHOT.jar` — fat (все зависимости внутри).

Для **использования в своём проекте** (не как исполняемый файл) удобнее собирать без `shade` — либо временно отключить `maven-shade-plugin` (`<skip>true</skip>`), либо использовать `-Dshade.skip=true`, либо брать original JAR (`target/original-EasyWebServer-1.0-SNAPSHOT.jar` после сборки).

### 1.2. Maven

```bash
mvn install           # в локальный репозиторий
```

```xml
<dependency>
    <groupId>ru.miacomsoft</groupId>
    <artifactId>EasyWebServer</artifactId>
    <version>1.0-SNAPSHOT</version>
    <!-- Чтобы избежать дублей зависимостей: -->
    <exclusions>
        <!-- <exclusion><groupId>com.oracle.database.jdbc</groupId><artifactId>ojdbc8</artifactId></exclusion> -->
    </exclusions>
</dependency>
```

Зависимости (по `pom.xml`): `ojdbc8` + `xdb` + `orai18n` (Oracle), `postgresql`, `org.json`, `jsoup`. Если вы их уже подключаете в своём проекте — исключайте в `<exclusions>`.

### 1.3. `java -jar`

Fat JAR запускается как готовый сервер:

```bash
java -jar EasyWebServer-1.0-SNAPSHOT.jar
```

`main` — `ru.miacomsoft.Main` (см. `Main.java` в примерах). Чтобы **использовать как библиотеку со своим `main`**, замените `mainClass` в `pom.xml` или не используйте `shade`.

---

## 2. Точка входа

```java
import ru.miacomsoft.EasyWebServer.WebServer;

public class MyApp {
    public static void main(String[] args) {
        WebServer web = new WebServer(MyApp.class);   // ← сканирование пакета MyApp

        web.config("DEFAULT_PORT", "8080");
        web.config("DEFAULT_HOST", "0.0.0.0");
        web.config("WEBAPP_DIR",   "www");

        // Прочие web.config(...) до старта
        web.start();                                  // ← блокирует текущий поток
    }
}
```

Ключевое:

- `new WebServer(MyApp.class)` **сканирует пакет `MyApp` + все подпакеты** на наличие методов с аннотациями `@onPage`, `@Get`, `@Post`, `@Put`, `@Delete`, `@Head`, `@Patch`, `@Options`, `@Trace`, `@onTerminal`. Исключены пакеты `org.*`, `com.ctc.wstx.osgi`, `net.sf.*`, `com.lowagie.*`, `net.sourceforge.barbecue.*`, `oracle.*`, `javax.resource.*`, `javax.transaction.*`.
- Если `MyApp` находится **внутри JAR** (`getProtectionDomain().getCodeSource().getLocation()` начинается с `jar:`), сканирование идёт по **всему JAR** (`PacketManager.getPageJar`).
- `WebServer.start()` **блокирует вызывающий поток** — `thread.join()` ждёт остановки сервера.
- `WebServer.stop()` (или `ShutDown`) останавливает сервер.
- `WebServer` — singleton: поле `static server` (второй `start()` перезапишет первое).

---

## 3. Четыре способа добавить эндпоинт

`EasyWebServer` поддерживает **четыре независимых механизма**. Все они работают одновременно; маршрут определяется по `query.requestPath` в `processMainRequest()` в таком порядке:

1. `ServerResource.pagesJavaInnerClass` (аннотации `@Get`/`@Post`/...).
2. `ServerResource.pagesListContent` (`web.onPage(path, StringBuffer)`).
3. `ServerResource.pagesList` (`web.onPage(path, CallbackPage)`).
4. Поиск файла в `WEBAPP_DIRS` (статика, `.java`, `.html`).
    - Для `.java` — `JavaStrExecut.runJavaFile` (динамическая компиляция).
    - Для `*.component` — `JavaStrExecut.runJavaComponent`.
    - Для URL с `{component}` — `JavaStrExecut.runJavaServerlet`.

### 3.1. `@onPage` / `@Get` / `@Post` / ... (в classpath)

| Аннотация | `queryType` | Возврат метода | Куда попадает |
|---|---|---|---|
| `@onPage` | `null` | `byte[]` | `query.mimeType = page.mime`, `sendHtml(new String(bytes))` |
| `@Get` | `"Get"` | `JSONObject` | `.toString().getBytes()` → `sendHtml` |
| `@Post` | `"Post"` | `JSONObject` | то же |
| `@Put` | `"Put"` | `JSONObject` | то же |
| `@Delete` | `"Delete"` | `JSONObject` | то же |
| `@Head` | `"Head"` | `JSONObject` | то же |
| `@Patch` | `"Patch"` | `JSONObject` | то же |
| `@Options` | `"Options"` | `JSONObject` | то же |
| `@Trace` | `"Trace"` | `JSONObject` | то же |
| `@onTerminal` | — | `void` или `byte[]` | `runJavaTerminalFile` |

Из `ServerResourceHandler.processJavaInnerClass()`:

```java
query.mimeType = page.mime;                         // ← mime из аннотации
Object result = page.method.invoke(page.ObjectInstance, query);
byte[] messageBytes = (page.queryType == null)
        ? (byte[]) result
        : ((JSONObject) result).toString().getBytes();
```

**Следствия, которые важны:**

- **HTTP-метод запроса НЕ проверяется.** `@Get` не значит «только GET». Метод `@Get(url="x")` ответит на `GET`, `POST`, `PUT`, ... — как на любой другой HTTP-метод, если по `url` запрос совпал. Разделить GET и POST на один URL **невозможно** (см. §3.1.3).
- **`ext` из аннотации не используется для маршрутизации.** Маршрут = точное совпадение `page.url` и `query.requestPath`. `ext` — декоративное поле.
- **`mime` из аннотации** становится `Content-Type` (реальный). Значения по умолчанию: `@onPage` — `text/html`, `@Get`/`@Post`/... — `text/html`; **но `queryType != null` подразумевает JSON-ответ**, поэтому `application/json` — ваша забота (см. §3.1.4).

#### 3.1.1. Пример `@Get` (JSON)

```java
package com.example.myapp;

import org.json.JSONObject;
import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.util.queryType.Get;

public class MyApiController {

    @Get(url = "api/hello.java", ext = "json", mime = "application/json; charset=utf-8")
    public JSONObject hello(HttpExchange query) {
        String name = query.requestParam.optString("name", "Гость");
        JSONObject res = new JSONObject();
        res.put("message", "Привет, " + name + "!");
        res.put("ts", System.currentTimeMillis());
        return res;
    }
}
```

Запрос: `GET /api/hello.java?name=Иван`. Ответ: HTTP-200, `Content-Type: application/json; charset=utf-8`, тело — JSON-строка.

#### 3.1.2. Пример `@onPage` (HTML/bytes)

```java
import ru.miacomsoft.EasyWebServer.util.onPage;

public class MyPage {
    @onPage(url = "mypage.html", ext = "html", mime = "text/html; charset=utf-8")
    public byte[] page(HttpExchange query) {
        String html = "<h1>Привет</h1>";
        return html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
```

Возврат — `byte[]`, отправляется через `sendHtml(new String(bytes))`.

#### 3.1.3. Ограничение: один метод на URL

`PageManager` регистрирует так:

```java
ServerResource.pagesJavaInnerClass.put(page.url, page);
```

`page.url` — ключ. Если два метода имеют одинаковый `url` (например, `@Get(url="x")` и `@Post(url="x")`), **второй затирает первый**. **Работать будет только последний метод, независимо от HTTP-метода запроса**.

Чтобы разделить GET/POST на «одной ручке», используйте динамический `.java` файл (см. §3.3) и проверяйте `query.typeQuery` вручную:

```java
public class api_foo {
    public byte[] onPage(HttpExchange query) {
        if ("GET".equalsIgnoreCase(query.typeQuery))   { /* ... */ }
        if ("POST".equalsIgnoreCase(query.typeQuery))  { /* ... */ }
        return null;
    }
}
```

#### 3.1.4. MIME для JSON

По умолчанию у `@Get`/`@Post` `mime="text/html"`. Чтобы получить честный JSON, задавайте `mime` **явно**:

```java
@Get(url="api/x.java", mime="application/json; charset=utf-8")
```

Или устанавливайте внутри метода **до возврата** — но здесь это не сработает, т.к. `query.mimeType = page.mime` присваивается **после** `invoke`. То есть внутри метода `query.mimeType` переопределять бессмысленно. Только через `mime=` в аннотации.

#### 3.1.5. Требования к классу

- Класс в пакете `mainClass` **или подпакетах** (см. §2).
- Публичный конструктор без аргументов (`getDeclaredConstructor().newInstance()`).
- Метод **публичный** (`getMethods()`).
- Параметр **ровно один** — `HttpExchange`.

### 3.2. `{component}/<ClassName>` — провайдеры JS/CSS/JSON

Любой класс в пакете `ru.miacomsoft.EasyWebServer.component` (или в `COMPONENT_PATH` из `config.ini`) с **публичным статическим** методом:

```java
public static byte[] onPage(HttpExchange query)
```

доступен по URL:

```
http://host:port/{component}/<ClassName>
```

`JavaStrExecut.runJavaServerlet` делает:

```java
String classNameCmp = JavaStrExecut.class.getPackage().getName()
        + ".component." + query.requestPath.split("component}/")[1];
Class<?> cl = Class.forName(classNameCmp);
Method m = cl.getMethod("onPage", HttpExchange.class);
byte[] res = (byte[]) m.invoke(null, query);
if (res != null) query.sendHtml(new String(res));
```

Пример — `md5.java`, `session.java`, `cmpButton_js.java`, `cmpButton_css.java`, `main_js.java`.

Минимальный собственный провайдер:

```java
package ru.miacomsoft.EasyWebServer.component;

import ru.miacomsoft.EasyWebServer.HttpExchange;

public class my_js {
    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/javascript";
        return "console.log('hi');".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
```

Доступ: `/{component}/my_js`.

`COMPONENT_PATH` в `config.ini` позволяет сменить пакет (например, `"COMPONENT_PATH": "com.example.components"`). Тогда `{component}/Xxx` ищется в `com.example.components.Xxx`.

### 3.3. `.java` файлы в `WEBAPP_DIR`

Любой файл `<path>.java` в `WEBAPP_DIR` компилируется **в рантайме** при первом запросе.

```text
www/
└── api/
    └── hello.java       → GET /api/hello.java
```

Требования:

| Требование | Причина |
|---|---|
| Имя публичного класса = имя файла без `.java` | `Class.forName(fileName)` |
| Метод `onPage` **или** `page` | проверка в `runJavaFile` |
| Возврат `byte[]` или `null` | `(byte[]) meth.invoke(...)` |
| Ровно один параметр `HttpExchange` | `invoke` через `(ObjectInstance, query)` |
| Импорты — явные | шаблон не добавляет `import` |
| Публичный конструктор без аргументов | `newInstance()` |

Пример:

```java
// www/api/hello.java
import ru.miacomsoft.EasyWebServer.HttpExchange;

public class hello {
    public byte[] onPage(HttpExchange query) {
        query.mimeType = "application/json; charset=utf-8";
        return "{\"msg\":\"hi\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
```

В отличие от `@Get`, **здесь HTTP-метод и MIME полностью под вашим контролем** (`query.mimeType = ...` до возврата).

Перекомпиляция — автоматически при изменении `mtime` файла. Хэш содержимого + `InstanceClassName` кэш.

Подробнее — `DYNAMIC_PAGES.md`.

### 3.4. `web.onPage(...)` — программная регистрация

Три перегрузки + глобальный callback:

```java
WebServer web = new WebServer(MyApp.class);

// 1. Инлайн-контент (строка). Отдаётся «как есть», без парсинга `<cmpXXX>`
web.onPage("/inline.html", new StringBuffer("<h1>Inline</h1>"));

// 2. Callback (byte[] отдаёт ваш код)
web.onPage("/dynamic.html", (CallbackPage) query -> {
    String name = query.requestParam.optString("name", "Guest");
    return ("<h1>" + name + "</h1>").getBytes();
});

// 3. Файл (объект File — будет прочитан и отдан)
web.onPage("/my.json", new java.io.File("data/my.json"));

// 4. Глобальный callback — перехватывает ВСЕ запросы до стандартной обработки
web.onPage((CallbackProcedure) query -> {
    // если нужно — сформировать ответ вручную
    // если не хотите перехватывать — просто не пишите в query.write()
});
```

Проверка в `processMainRequest`:

```java
if (ServerResource.pagesJavaInnerClass.containsKey(query.requestPath)) { ... }   // 3.1
else if (ServerResource.pagesListContent.containsKey(query.requestPath)) {
    query.sendHtml(ServerResource.pagesListContent.get(query.requestPath).toString());
}
else if (ServerResource.pagesList.containsKey(query.requestPath)) {
    byte[] res = ServerResource.pagesList.get(query.requestPath).call(query);
    if (res != null) query.sendHtml(new String(res, StandardCharsets.UTF_8));
}
// затем — поиск файла в WEBAPP_DIRS
```

`CallbackProcedure` — самый ранний хук (`sendResponseQuery` вызывает его раньше `processMainRequest`):

```java
if (callbackProcedure != null) {
    callbackProcedure.call(query);
    return;
}
```

## 4. Статический контент

`WEBAPP_DIR` — каталог со статикой. `WEBAPP_DIRS` — список каталогов (поиск по очереди).

```java
web.config("WEBAPP_DIR", "www");                // один каталог
web.config("WEBAPP_DIR", "www;shared;assets");  // несколько (через ;)
```

`;`-список обрабатывается `WebServer.handleListProperty`: относительные пути (`без /` и `без \`) дополняются `SERVER_HOM`, абсолютные — берутся как есть.

Поиск файла — `findFileInAllDirectories(requestPath)`:

1. `WEBAPP_DIRS` — по порядку.
2. `WEBAPP_SYSTEM_DIR` (если задан).
3. Иначе 404 (`PAGE_404` или стандартная страница с диагностикой).

MIME определяется по расширению (`ServerConstant.MIME_MAP`, дефолт из `Map.ofEntries` в статическом блоке). Добавить свои расширения:

```java
web.config("MIME_MAP", "wasm:application/wasm;avif:image/avif");  // ← это НЕ сработает автоматически
```

⚠️ `MIME_MAP` — `final Map`, `setProp` его не перезапишет. Расширяйте через `ServerConstant.config.MIME_MAP.put(...)` напрямую **до старта** или через `config.ini` (где `"MIME_MAP": {...}` парсится в `parseConfig`).

Кэширование статики: `resources` (`Map<String, Resource>`) внутри `ServerResourceHandler` + `resourcesDateTime` (по `mtime`). Кэш **на инстанс** — но `ServerResourceHandler` создаётся на каждый сокет, так что кэш фактически на запрос. Глобального кэша статики в текущей версии **нет**.

Дебаг-режим отключает кэш (`?debug=1` → `session.put("debug_mode", true)`).

## 5. Динамические HTML-страницы с `<cmpXXX>`

`.html` файлы в `WEBAPP_DIR` парсятся `Jsoup`, теги `<cmpXXX>` заменяются одноимёнными классами из `COMPONENT_PATH` (`ru.miacomsoft.EasyWebServer.component` по умолчанию).

Компонент — наследник `Base`, конструктор `(Document, Element, String)`. Подробно — `CreateComponents.md`.

## 6. База данных

`EasyWebServer` работает с **PostgreSQL** (основной) и **Oracle** (второй пул). Конфиги:

### 6.1. Одна БД (по умолчанию)

```java
web.config("DATABASE_NAME", "jdbc:postgresql://localhost:5432/mydb");
web.config("DATABASE_USER_NAME", "postgres");
web.config("DATABASE_USER_PASS", "secret");
```

`query.SQL(sql)` → `JSONArray`:

```java
@Get(url="api/users.java", mime="application/json; charset=utf-8")
public JSONObject getUsers(HttpExchange query) {
    JSONArray rows = query.SQL("SELECT id, name FROM users");
    return new JSONObject().put("data", rows);
}
```

Важно:

- `query.SQL` использует **сессионное** соединение, если оно есть (`session["DATABASE"]["CONNECT"]`, из `loginDataBase`). Иначе — системное.
- При ошибке SQL возвращается **пустой `JSONArray`**, не исключение. Проверяйте `length()`.
- При отсутствии соединения — тоже пустой массив.

### 6.2. Несколько БД

```java
web.config("DATABASES.reporting",
        "pdo://reader:pass@db.example.com:5432/reports?currentSchema=public");
web.config("DATABASES.oracle_test",
        "oci8://user:pass@oracle.host:1521/service:pooled");
```

Формат строк — `DatabaseConfig.parse()`:

```
<type>://<user>:<pass>@<host>:<port>/<database>[:pooled][?param=value&...]
<type>   — jdbc | pdo | oci8
params   — currentSchema, type=pgsql, connectionType=service|sid, ...
```

Использование:

```java
query.SQL("SELECT * FROM reports", "reporting");    // по имени
```

Прямая работа с пулом:

```java
import ru.miacomsoft.EasyWebServer.PostgreQuery;
import ru.miacomsoft.EasyWebServer.DatabaseConfig;

JSONArray rows = PostgreQuery.executeQuery(
        "SELECT * FROM users WHERE age > :min",
        java.util.Map.of("min", 18),
        "reporting", dbUser, dbPass);
```

`executeQuery` поддерживает именованные параметры (`:name`). `query.SQL` — нет.

### 6.3. Параметризованные запросы (обязательно)

`query.SQL` **не** параметризован. Используйте:

```java
import ru.miacomsoft.EasyWebServer.PostgreQuery;
Map<String,Object> p = new HashMap<>();
p.put("name", query.requestParam.optString("name"));
JSONArray rows = PostgreQuery.executeQuery(
        "SELECT * FROM users WHERE name = :name", p, dbUser, dbPass);
```

Или компоненты `<cmpAction>`/`<cmpDataset>` с `<var>`-параметрами (см. `CreateComponents.md`).

### 6.4. Пользовательские соединения

`{component}/loginDataBase` кладёт соединение в `session["DATABASE"]["CONNECT"]`. Далее `query.SQL` использует его. `{component}/loginDataBase?logoff=1` закрывает.

### 6.5. Пулы

Размеры пулов — в `config.ini`:

```json
"POSTGRES_MIN_POOL_SIZE": "2",
"POSTGRES_MAX_POOL_SIZE": "20",
"ORACLE_POOL_SIZE":       "10"
```

Пул — `ConnectionPool` в `PostgreQuery` / `OracleQuery`, создаётся лениво при первом обращении к БД. Остановка — `shutdownPools()` в `WebServer.shutDown()`.

## 7. Сессии

Сессия — `HashMap<String, Object>` в `ServerResourceHandler.sessionList` (глобальный `static`). `sessionID` — UUID, `Set-Cookie: session=<uuid>` выставляется автоматически.

```java
@Get(url="api/me.java", mime="application/json")
public JSONObject me(HttpExchange query) {
    Object user = query.session.get("AUTH_USER");
    if (user == null) return new JSONObject().put("error", "unauthorized");
    return new JSONObject().put("user", user);
}
```

`{component}/session` — set/get/getAll. `{component}/loginDataBase` — вход в PostgreSQL. Подробнее — `AUTH_AND_MESSAGING.md`.

## 8. HTTPS

**Две независимые схемы**, работают одновременно или по отдельности.

### 8.1. Схема A — PEM на основном порту (`SSLManager`)

```java
web.config("HTTPS_ENABLED", "true");
web.config("HTTPS_CERT",    "/etc/letsencrypt/live/example.com/fullchain.pem");
web.config("HTTPS_KEY",     "/etc/letsencrypt/live/example.com/privkey.pem");
web.config("DEFAULT_PORT",  "8443");
```

- Ключ — **PKCS#8** (`-----BEGIN PRIVATE KEY-----`).
- Условие запуска: `HTTPS_ENABLED=true && !HTTPS_CERT.isEmpty() && !HTTPS_KEY.isEmpty()`.
- **Основной порт** становится HTTPS.

### 8.2. Схема B — keystore на отдельном порту (`HttpsServerWrapper`)

```java
web.config("HTTPS_PORT",        "443");
web.config("KEYSTORE_PATH",     "/opt/easy/keystore.jks");
web.config("KEYSTORE_PASSWORD", "changeit");
web.config("KEY_PASSWORD",      "");              // опционально, обычно = KEYSTORE_PASSWORD
```

- `HTTPS_PORT <= 0` → отключено.
- Работает **параллельно** с основным портом.

### 8.3. Одновременно

```java
web.config("HTTPS_ENABLED", "true");
web.config("HTTPS_CERT",    "...");
web.config("HTTPS_KEY",     "...");
web.config("DEFAULT_PORT",  "8080");   // HTTPS на 8080
web.config("HTTPS_PORT",    "443");    // keystore на 443
```

Порты **должны различаться** — иначе `BindException`.

Подробности — `Https_SSL_Stup.md`.

## 9. WebRTC (видеоконференции)

```java
web.config("VIDEOCHAT_PORT", "8081");   // 0 или не задано = отключено
```

`WebServer.run()` после создания основного сокета:

```java
if (ServerConstant.config.VIDEOCHAT_PORT > 0) {
    webrtcServer = new WebRTCSignalingServer(ServerConstant.config.VIDEOCHAT_PORT);
    webrtcServer.start();
}
```

Отдельный **WebSocket-сервер** на `VIDEOCHAT_PORT` с endpoint'ами:

- `ws://host:VIDEOCHAT_PORT/ws/publish?room=<name>&pass=<pass>` — вещатель;
- `ws://host:VIDEOCHAT_PORT/ws/subscribe?room=<name>&pass=<pass>` — зритель.

Клиент — `conference.html`. Подробнее — `AUTH_AND_MESSAGING.md`, §6.

**WebRTC-сервер сам TLS не поддерживает.** Для работы `getDisplayMedia()` в браузере нужен HTTPS (или `localhost`). Если вещание идёт снаружи — ставьте реверс-прокси (Nginx/Caddy) перед signaling-сервером.

## 10. Config API

### 10.1. `config(String, String)`

```java
web.config("DEFAULT_PORT", "8080");
web.config("WEBAPP_DIR",   "www");
```

Поведение (`WebServer.config`):

1. Если значение содержит `;` и имя — известный список (`WEBAPP_DIR`, `LIB_CSS`, `LIB_JS`, `LIB_JAR`, `*_LIST`, `*_DIRS`) → `handleListProperty`.
2. Если имя начинается с `DATABASES.` → `handleDatabaseProperty` (парсит строку, добавляет в `DATABASES`).
3. Иначе → `ServerConstant.setProp(name, value)` через рефлексию (`String`, `boolean`, `int`).

### 10.2. `config(String, Boolean)`

```java
web.config("DEBUG", true);
web.config("CAHEBLE", true);
web.config("HTTPS_ENABLED", true);
```

`ServerConstant.setProp(name, boolean)` — только для полей `boolean`/`Boolean`.

### 10.3. Прямой доступ

```java
ServerConstant.config.DEFAULT_PORT = "8080";
ServerConstant.config.DEBUG = true;
ServerConstant.config.MIME_MAP.put("wasm", "application/wasm");
```

Иногда проще, чем `config()` с рефлексией.

## 11. Использование `config.ini`

Если сервер запускается через `java -jar` (без своего `main`) — файл `config.ini` в рабочем каталоге. Формат — JSON с `#`-комментариями (обрезаются при чтении). Заполняет `ServerConstant.config`.

Порядок в `Main.java` (пример): часть параметров — из `config.ini` (если файл есть), часть — через `web.config(...)`. **`web.config` переопределяет** уже загруженное.

Полный список полей — `ServerConstant.java` (главные: `DATABASE_*`, `DEFAULT_PORT`, `WEBAPP_DIR`, `HTTPS_*`, `LIB_*`, `APP_NAME`, `MIME_MAP`, `LOG_FILE`, `VIDEOCHAT_PORT`, `POSTGRES_*`, `ORACLE_POOL_SIZE`).

## 12. Известные подводные камни

1. **`@Get`/`@Post` не различают HTTP-метод.** Разделение — только по `url`. На один URL — один метод (второй затирает первый).

2. **`ext` в аннотациях не используется.** Маршрут — точное совпадение `url`. Расширение в URL (`.java`, `.json`) — как хотите, так и называйте.

3. **`mime` из аннотации применяется ПОСЛЕ `invoke`.** Переопределить `query.mimeType` внутри `@Get`-метода бесполезно. Задавайте `mime=` в аннотации.

4. **`@Get`/`@Post`/... должны возвращать `JSONObject`.** `String`, `byte[]` — `ClassCastException` → `parseErrorRunJava` в ответе.

5. **`@onPage` должен возвращать `byte[]`.** `JSONObject`, `String` — `ClassCastException`.

6. **Метод должен быть `public`.** `getMethods()` возвращает только public.

7. **Параметр ровно один — `HttpExchange`.** Иначе `IllegalArgumentException` на `invoke`.

8. **Публичный конструктор без аргументов.** `getDeclaredConstructor().newInstance()`.

9. **`WebServer.start()` блокирует.** Не запускайте в главном потоке, если после него нужна работа.

10. **`WebServer` — singleton.** Второй `new WebServer(mainClass)` не даст второй сервер (поле `static server`).

11. **`shade`-JAR имеет `mainClass = ru.miacomsoft.Main`.** Для использования как библиотеки — либо отключите shade, либо используйте original-JAR.

12. **`query.SQL` не параметризован** и возвращает пустой `JSONArray` при ошибках. Для параметров — `PostgreQuery.executeQuery(sql, params, dbName, user, pass)`.

13. **`session` не в `ConcurrentHashMap`.** `ServerResourceHandler.sessionList` — `HashMap`. При активной параллельной нагрузке — риски.

14. **`session` cookie без `HttpOnly`/`Secure`.** Для прода — HTTPS-схема A или B + реверс-прокси.

15. **`{component}/...` — фиксированный пакет** (`ru.miacomsoft.EasyWebServer.component` или `COMPONENT_PATH`). Если ваш класс в другом пакете — он не подхватится автоматически.

16. **`{component}`-классы должны иметь `public static byte[] onPage(HttpExchange)`.** Экземпляр не создаётся (`meth.invoke(null, query)`).

17. **`mvn package` → `target/EasyWebServer-*.jar`.** Это fat JAR. Для зависимости — `mvn install` + `<dependency>` (см. §1.2). Учитывайте `exclusions`, чтобы не тащить дубли.

18. **`WEBAPP_DIR` кэширует нестатические `.html` в `resources`** (инстанс-локально). `DEBUG=false` → при изменении файла нужен либо reload, либо `?debug=1`. На практике `resources` создаётся на каждый запрос (`ServerResourceHandler` — новый инстанс), так что глобального кэша нет — только `lastModified` внутри запроса.

19. **WebRTC требует TLS для `getDisplayMedia()`.** Локально — `HTTPS_ENABLED` (схема A) или `localhost`.

20. **HTTP-код ответа всегда 200.** `sendHtml`/`send`/`sendByteFile` — все отправляют `HTTP/1.1 200 OK`. Ошибки — в теле.

21. **`WebServer.stop()` не закрывает активные `Socket`.** Только `isRunServer=false`; `ServerSocket.accept()` завершится после `ss`-close в `run()`.

## 13. Минимальный шаблон проекта

```text
my-app/
├── pom.xml
├── src/main/java/com/example/myapp/
│   ├── MyApp.java
│   └── MyApiController.java
└── www/
    ├── index.html
    └── css/style.css
```

`MyApp.java`:

```java
package com.example.myapp;

import ru.miacomsoft.EasyWebServer.WebServer;

public class MyApp {
    public static void main(String[] args) {
        WebServer web = new WebServer(MyApp.class);           // сканирует com.example.myapp.*
        web.config("DEFAULT_PORT", "8080");
        web.config("DEFAULT_HOST", "0.0.0.0");
        web.config("WEBAPP_DIR",   "www");
        web.config("APP_NAME",     "myapp");
        web.config("DEBUG",        false);
        web.config("CAHEBLE",      true);

        // HTTPS (схема A)
        // web.config("HTTPS_ENABLED", true);
        // web.config("HTTPS_CERT", "/etc/letsencrypt/live/example.com/fullchain.pem");
        // web.config("HTTPS_KEY",  "/etc/letsencrypt/live/example.com/privkey.pem");

        // WebRTC
        // web.config("VIDEOCHAT_PORT", "8081");

        web.start();   // блокирует
    }
}
```

`MyApiController.java`:

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
        JSONObject data = query.messageJson;                 // уже распарсен
        if (data == null) data = new JSONObject(
                new String(query.postByte, java.nio.charset.StandardCharsets.UTF_8));
        return new JSONObject().put("status", "ok").put("received", data);
    }
}
```

`pom.xml` — зависимости `ru.miacomsoft:EasyWebServer`, `org.json`, `jsoup`. Своя `mainClass` — `com.example.myapp.MyApp`.

## 14. Резюме

- **Подключение**: `mvn install` + `<dependency>`, либо fat JAR (но там `mainClass = ru.miacomsoft.Main`).
- **Точка входа**: `new WebServer(MyApp.class)` — сканирует **пакет MyApp + подпакеты**; `web.config(...)` перед `web.start()`; `start()` **блокирует**.
- **4 способа эндпоинтов**:
    - `@Get`/`@Post`/... — `JSONObject`, mime из аннотации, HTTP-метод **не** проверяется.
    - `@onPage` — `byte[]`, mime из аннотации.
    - `{component}/<ClassName>` — `public static byte[] onPage(HttpExchange)` в фикс. пакете.
    - `.java` файлы в `WEBAPP_DIR` — динамическая компиляция, `onPage`/`page` → `byte[]` или `null`.
    - `web.onPage(path, ...)` — программная регистрация.
- **`ext` в аннотациях не работает**. URL — точное совпадение.
- **HTTP-метод не проверяется** у `@Get`/`@Post`. Для разделения по методу — динамический `.java`.
- **MIME** для `@Get`/`@Post` — только через `mime=` в аннотации (после `invoke` переопределять поздно).
- **БД**: `query.SQL` (не параметризованный, ошибки → пустой массив), `PostgreQuery.executeQuery(sql, params, dbName, user, pass)` (параметризованный).
- **HTTPS**: две схемы — PEM (`HTTPS_ENABLED` + `HTTPS_CERT`/`HTTPS_KEY`) на основном порту; keystore (`HTTPS_PORT` + `KEYSTORE_PATH`) на отдельном.
- **WebRTC**: `VIDEOCHAT_PORT > 0` → запускается `WebRTCSignalingServer`; браузерный `getDisplayMedia()` требует HTTPS или `localhost`.
- **HTTP-код ответа всегда 200**; ошибки — в теле.
- **Подводные камни** — §12: `@Get` не различает HTTP-метод, `ext` не работает, `start()` блокирует, `@onPage`/`@Get` — разные возвращаемые типы и т.д.