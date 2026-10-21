# EasyWebServer

Легковесный Java-веб-сервер с компонентной системой `<cmpXXX>`, динамической компиляцией `.java` прямо в рантайме, встроенной поддержкой HTTPS (keystore / PEM), WebRTC-сигналингом, терминальным режимом для IoT и клиентским ядром `D3Api`.

Работает как **автономная программа** (`java -jar`) и как **Java-библиотека** в составе другого приложения (Maven-зависимость, свой `Main`, `WebServer.start()`).

---

## Возможности

- **HTTP и HTTPS** на одном порту: TLS через `keystore.p12`/`.jks` (PKCS12/JKS) **или** через PEM (`fullchain.pem` + `privkey.pem` в формате PKCS#8).
- **Динамические `.java`-страницы** — файлы в `WEBAPP_DIR` компилируются на лету через `javax.tools.JavaCompiler`; правки подхватываются без перезапуска.
- **Компонентная система `<cmpXXX>`** — HTML-теги на серверной стороне заменяются Java-кодом (аналог JSP-тегов / Thymeleaf-фрагментов) с доступом к БД.
- **`@Get` / `@Post` / `@onPage` / `@onTerminal`** — аннотации для регистрации эндпоинтов в classpath.
- **`{component}/Xxx`** — статические JS/CSS/JSON-провайдеры.
- **Клиентское ядро `D3Api`** — переменные, сессия, `setValue/getValue`, watchers, `CustomEvent`, `executeAction`, `refreshDataSet`.
- **PostgreSQL и Oracle** — пулы соединений, параметризованные запросы, работа с пользовательскими сессиями через `{component}/loginDataBase`.
- **Терминальный режим (`TERM`)** — постоянный TCP-канал с ESP8266 / native-клиентами, peer-to-peer через `queryPtP`.
- **WebRTC-сигналинг** — встроенный WebSocket-сервер (`/ws/publish`, `/ws/subscribe`) для видеоконференций.
- **SSE** — Server-Sent Events через прямой доступ к сокету.
- **`HTTP_REDIRECT_PORT`** — опциональный plain-HTTP listener для `301 → HTTPS`.

---

## Быстрый старт

### Сборка

```bash
mvn clean package -DskipTests
```

Результат — `target/EasyWebServer-1.0-SNAPSHOT.jar` (fat JAR со всеми зависимостями, `mainClass = ru.miacomsoft.Main`).

### Запуск

```bash
java -jar target/EasyWebServer-1.0-SNAPSHOT.jar
```

Сервер стартует на порту `9092` (или из `config.ini`). При наличии keystore — сразу по HTTPS.

### Как библиотека

```java
import ru.miacomsoft.EasyWebServer.WebServer;

public class MyApp {
    public static void main(String[] args) {
        WebServer web = new WebServer(MyApp.class);   // сканирует пакет MyApp + подпакеты

        web.config("DEFAULT_HOST", "0.0.0.0");
        web.config("DEFAULT_PORT", "9092");
        web.config("WEBAPP_DIR",   "www");

        web.start();   // блокирует текущий поток
    }
}
```

Подключение через Maven:

```xml
<dependency>
    <groupId>ru.miacomsoft</groupId>
    <artifactId>EasyWebServer</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

После `mvn install` из корня EasyWebServer.

---

## Структура проекта

```
EasyWebServerGit.git2/
├── src/main/java/ru/miacomsoft/
│   ├── Main.java                     ← точка входа (пример)
│   ├── EasyWebServer/                ← ядро
│   │   ├── WebServer.java
│   │   ├── ServerResourceHandler.java
│   │   ├── HttpExchange.java
│   │   ├── ServerConstant.java
│   │   ├── SSLManager.java
│   │   ├── WebRTCSignalingServer.java
│   │   ├── PacketManager.java
│   │   ├── JavaStrExecut.java
│   │   ├── PostgreQuery.java
│   │   ├── OracleQuery.java
│   │   ├── DatabaseConfig.java
│   │   └── component/                ← встроенные компоненты + провайдеры JS/CSS
│   └── ...                            ← прикладные классы (@Get/@Post/@onPage/@onTerminal)
├── www/                               ← WEBAPP_DIR (статика, .java, .html, компоненты)
├── certs/                             ← keystore.p12 / PEM
├── config.ini                         ← опциональный конфиг
└── pom.xml
```

---

## Документация

В проекте есть подробные инструкции по каждой подсистеме. Все они лежат в корне репозитория.

### Ядро и компоненты

| Документ | Что внутри |
|---|---|
| [`UseJarLibEasyWebServer.md`](UseJarLibEasyWebServer.md) | Использование как JAR-библиотеки: подключение, `WebServer.start()`, 4 способа эндпоинтов (`@Get`/`@Post`/`@onPage`/`{component}`), статика, БД, HTTPS, WebRTC, подводные камни. |
| [`CreateComponents.md`](CreateComponents.md) | Компонентная система `<cmpXXX>`: как работает `parseElementV2`, базовый класс `Base`, встроенные компоненты (`cmpButton`, `cmpEdit`, `cmpDataset`, `cmpAction`, `cmpScript`, `cmpDependences`, `cmpForm`), создание своего компонента. |
| [`DynamicJAVA.md`](DynamicJAVA.md) | Динамические `.java`-страницы в `WEBAPP_DIR`: требования к классу, компиляция в рантайме, кэш, перекомпиляция, работа с `query` (`requestParam`, `postByte`, `session`, `SQL`), ошибки компиляции и выполнения. |

### Аутентификация, сессии, обмен сообщениями

| Документ | Что внутри |
|---|---|
| [`AuthAndMessaging.md`](AuthAndMessaging.md) | Серверные сессии (`session`, `sessionDirect`, `loginDataBase`), клиентский `D3Api` (переменные / значения / подписи / watchers / CustomEvents), статическое разделяемое состояние (`SHARE`, `MESSAGE_LIST`, `BROADCAST_MESSAGE_LIST`), терминальный режим `@onTerminal`, SSE, WebRTC-сигналинг, обе схемы HTTPS. |

### Терминальный режим для IoT

| Документ | Что внутри |
|---|---|
| [`CreateTermController.md`](CreateTermController.md) | `TERM`-режим: как устроен, `@onTerminal`, `readLineTerm()` (не построчный!), `write()`, peer-to-peer через `queryPtP`, ESP8266-клиент на Arduino, варианты для браузера (polling/SSE/WebRTC), подводные камни (потеря первых байт, `""` на close, один экземпляр класса). |

### HTTPS / SSL-сертификаты

| Документ | Что внутри |
|---|---|
| [`Https_SSL_Stup.md`](Https_SSL_Stup.md) | HTTPS в EasyWebServer: обе схемы (`SSLManager` — PEM, `HttpsServerWrapper` — keystore), получение Let's Encrypt через DNS Challenge, конвертация PKCS#1 → PKCS#8, диагностика TLS-ошибок, продление сертификата. |
| [`SelfSigned_P12_RegRu.md`](SelfSigned_P12_RegRu.md) | Создание самоподписанного `keystore.p12` на Linux Mint + настройка A-записей в Reg.ru: установка `keytool`/`openssl`/`certutil`, SAN, регистрация в Linux/Chrome/Firefox/Java/Android/iOS, проброс портов, обновление сертификата. |
| [`SetupEasyWebServer _Linux Mint.md`](SetupEasyWebServer%20_Linux%20Mint.md) | Автономная программа: требования, сборка, systemd, Docker, Windows-служба (NSSM). Как библиотека: Maven-зависимость, свой `Main`, `WebServer.start()`, `@Get`/`@Post`, `web.onPage(...)`, жизненный цикл. Создание и регистрация самоподписанного сертификата (`keytool`/`openssl`), установка в truststore. |

> Примечание: файл `Настройка_HTTPS_и_SSL-сертификата_для_FileServer.MD` — это инструкция для смежного проекта **FileServer_git**, но подход к `keystore.p12` и Let's Encrypt общий и полностью совместим с EasyWebServer.

---

## Минимальный пример

### `Main.java`

```java
package ru.miacomsoft;

import ru.miacomsoft.EasyWebServer.WebServer;

public class Main {
    public static void main(String[] args) {
        WebServer web = new WebServer(Main.class);

        web.config("DEFAULT_HOST", "0.0.0.0");
        web.config("DEFAULT_PORT", "9092");
        web.config("WEBAPP_DIR",   "/var/www/easywebserver-www");
        web.config("APP_NAME",     "webpage");

        // HTTPS через keystore (приоритет над PEM)
        web.config("HTTPS_ENABLED",           "true");
        web.config("HTTPS_KEYSTORE",          "/opt/easywebserver/certs/keystore.p12");
        web.config("HTTPS_KEYSTORE_PASSWORD", "changeit");

        // Опционально: plain-HTTP listener для 301 → HTTPS
        // web.config("HTTP_REDIRECT_PORT", "80");

        web.start();
    }
}
```

### Эндпоинт через аннотацию

```java
package ru.miacomsoft;

import org.json.JSONObject;
import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.util.queryType.Get;

public class ApiController {
    @Get(url = "api/hello", mime = "application/json; charset=utf-8")
    public JSONObject hello(HttpExchange query) {
        String name = query.requestParam.optString("name", "Гость");
        return new JSONObject().put("message", "Привет, " + name + "!");
    }
}
```

Запрос: `GET https://<host>:9092/api/hello?name=Иван` → `{"message":"Привет, Иван!"}`.

### HTML с компонентами

```html
<cmpEdit name="inpName" placeholder="Имя"/>
<cmpButton name="btnSave" caption="Сохранить"
           onclick="executeAction('saveUser', () => alert('Сохранено'));"/>

<cmpAction name="saveUser" query_type="sql" db="default" schema="public">
    INSERT INTO users(name) VALUES(:inpName);
    <var name="inpName" type="string" len="100" src="inpName" srctype="ctrl"/>
</cmpAction>
```

---

## Сборка и публикация

### Maven-зависимости проекта

| Артефакт | Назначение |
|---|---|
| `ojdbc8`, `xdb`, `orai18n` | Oracle JDBC + XML + NLS |
| `postgresql` | PostgreSQL JDBC |
| `org.json` | JSON |
| `jsoup` | HTML-парсер (компоненты `<cmpXXX>`) |

### Сборка fat-JAR

```bash
mvn clean package -DskipTests
```

`maven-shade-plugin` собирает fat-JAR с `mainClass = ru.miacomsoft.Main`. Для использования как библиотеки — `mvn install` + `<dependency>` в своём проекте (см. `UseJarLibEasyWebServer.md`).

### Диаграмма запуска HTTPS

```
Интернет
    │
    ▼
Роутер (port forward)             ← если сервер за NAT
    │  TCP 443  → <server>:9092
    │  TCP 9092 → <server>:9092
    ▼
EasyWebServer (Java, порт 9092)   ← слушает непривилегированный порт
    │  SSLManager → keystore.p12 (или PEM)
    ▼
ServerResourceHandler             ← HTTP/HTTPS, SSE, TERM
    │  D3Api, компоненты, статика, БД
    ▼
Ответ клиенту
```

Порт 443 на сервере **не занят Java** — его обрабатывает роутер или nft-redirect. `setcap` на `java` не нужен и опасен (ломает `libjli.so`).

---

## Лицензия

См. `LICENSE` в корне репозитория (если файл отсутствует — уточните у автора).

---

## Контакты

Автор проекта — Мясников Иван Александрович (`Miacom@mail.ru`). Вопросы и предложения — через issue-трекер репозитория.
