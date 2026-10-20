# Авторизация, сессии и обмен сообщениями в EasyWebServer

Документ описывает **фактически реализованный** в проекте функционал: серверные сессии (`session`, `sessionDirect`, `loginDataBase`), клиентское ядро `D3Api` (переменные / значения / подписи / watchers / CustomEvents), статическое разделяемое состояние, терминальный режим (`@onTerminal`), WebSocket-сигналинг для WebRTC и обе работающие схемы HTTPS.

---

## 1. Серверные сессии

### 1.1. Механика

- Сессия — это `HashMap<String, Object>`, хранящаяся в `ServerResourceHandler.sessionList` (`HashMap<String, HashMap<String, Object>>`, не Concurrent).
- Ключ — UUID. При первом запросе, если в cookie нет `session`, генерируется UUID:
  ```java
  httpExchange.responseHeaders.put("Set-Cookie",
      "session=" + uuid + "; debug=" + ServerConstant.config.DEBUG + ";");
  ```
- Cookie парсится в `readRequestHeader` из заголовка `Cookie`, поле `query.sessionID`.
- При чтении запроса вызывается `getSession(query)` — создаёт либо возвращает существующую сессию.
- Данные сессии доступны во всех `onPage(HttpExchange)` через `query.session`.

Ключи, которые уже используются фреймворком:

| Ключ | Назначение |
|---|---|
| `DATABASE` | `Map` для `loginDataBase` (`CONNECT`, `DATABASE_USER_NAME`, `DATABASE_USER_PASS`) |
| `debug_mode` | `Boolean`, включается через `?debug=1` в запросе |
| `DATABASE_CONFIG_<dbName>` | Кэш конфигурации БД (см. `HttpExchange.SQL`) |

### 1.2. Компонент `{component}/session`

Серверный endpoint: `ru.miacomsoft.EasyWebServer.component.session`.

| Запрос | Описание |
|---|---|
| `POST /{component}/session?set_session=key` | Сохранить в сессию. Тело — JSON-объект (сохраняется как `JSONObject`) либо произвольная строка |
| `POST /{component}/session?get_session=key` | Получить значение. Возвращает поля объекта либо `{"value": "..."}` либо `{"error": "..."}` |
| `GET /{component}/session?action=getAll` | Получить все данные сессии |
| без параметров | Возвращает `{session_id, data_size}` |

**Особенность `get_session`:** если сохранённое значение содержит единственное поле `value`, клиентский `D3Api.getSession` автоматически разворачивает ответ и возвращает только `value`. Если в объекте больше полей — возвращает весь объект.

### 1.3. Клиентский `D3Api` для работы с сессией (`main_js`)

```javascript
// Записать в серверную сессию (асинхронно)
D3Api.setSession(name, value, callback);

// Прочитать (два режима)
var val = D3Api.getSession(name);              // синхронно через XHR
D3Api.getSession(name, function(err, data) {}); // асинхронно через fetch

// Загрузить всю сессию при старте (используется в main_js при DOMContentLoaded)
D3Api.initSession(function(err, all) { ... });
D3Api.getAllSession(function(err, all) { ... });

// Подписка на изменения
var id = D3Api.onChangeSession('user', function(newVal, oldVal) {
    // если вернём значение — оно будет использовано как newVal
});
D3Api.offChangeSession(id);          // по ID подписки
D3Api.offChangeSession('user');      // все подписки по имени
D3Api.offChangeSession('user', cb);  // конкретный callback
```

Кэш сессии на клиенте: `GLOBAL_SESSION` (внутренний объект), доступ — только через `getSession`.

### 1.4. Глобальные переменные страницы (не путать с сессией)

```javascript
D3Api.setVar(name, value);           // локальная страница (GLOBAL_VARS)
D3Api.getVar(name, defValue);

var id = D3Api.onChangeVar(name, function(newVal, oldVal) {});
D3Api.offChangeVar(id);
```

Значения контролов и подписи:

```javascript
D3Api.setValue('inpName', 'Ivan');   // GLOBAL_CTRL → element
D3Api.getValue('inpName', '');
D3Api.setCaption('btnSave', 'Сохранить');
D3Api.getCaption('btnSave');

D3Api.onChangeValue  ('inpName',  cb);
D3Api.onChangeCaption('btnSave', cb);
```

Булк-операции: `setVars/getVars`, `setValues/getValues`, `setLabels/getLabels`, `setDisabled/setDisableds/setDisabledArr`, `setVisible/setVisibles`, `setStyle`.

### 1.5. `{component}/sessionDirect` — сохранение URL возврата

| Запрос | Описание |
|---|---|
| `POST /{component}/sessionDirect?set_direct=name` | Тело = URL (строка) |
| `POST /{component}/sessionDirect?get_direct=name` | Возвращает `{"redirect": "<url>"}` |

Клиент:
```javascript
D3Api.saveDirect(name);  // name default = 'local'
D3Api.loadDirect(name);
```

Применяется в шаблонах вроде «открыть форму → вернуться назад на сохранённый URL».

### 1.6. `{component}/loginDataBase` — аутентификация в PostgreSQL

Единственный встроенный компонент авторизации в БД.

| Параметр | Откуда | Описание |
|---|---|---|
| `username` | query или JSON-тело | Пользователь БД |
| `userpass` | query или JSON-тело | Пароль |
| `logoff` | query | Отключиться от БД |

Возвращает JSON:
```json
{ "connect": true,  "redirect": "/index.html" }
{ "connect": false, "error": "org.postgresql.util.PSQLException: ..." }
{ "connect": false, "disconnect": true }   // после logoff
```

Соединение кладётся в `session["DATABASE"]["CONNECT"]`. Все последующие запросы (`cmpAction`, `cmpDataset`, `HttpExchange.SQL`) используют **пользовательское** соединение, если оно есть, иначе системное (`ServerConstant.config.DATABASE_*`).

Пример формы (использует `D3Api`):

```html
<cmpEdit name="inpUser" placeholder="Логин"/>
<cmpEdit name="inpPass" placeholder="Пароль" type="password"/>
<cmpButton name="btnLogin" caption="Войти"
    onclick="login();"/>

<cmpScript name="loginHelper">
    window.login = function() {
        var body = JSON.stringify({
            username: D3Api.getValue('inpUser'),
            userpass: D3Api.getValue('inpPass')
        });
        fetch('/{component}/loginDataBase', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: body
        }).then(r => r.json()).then(function(res) {
            if (res.connect) {
                window.location.href = res.redirect;
            } else {
                D3Api.msgbox('Ошибка: ' + (res.error || 'unauthorized'));
            }
        });
    };
</cmpScript>
```

Клиентская обёртка:
```javascript
D3Api.logout();  // POST /{component}/loginDataBase?logoff=1 → redirect
```

### 1.7. Проверка авторизации на сервере

```java
@Get(url="adminPanel.java", ext="json")
public JSONObject adminPanel(HttpExchange query) {
    Object user = query.session.get("AUTH_USER");   // ваш собственный ключ
    if (user == null) {
        query.responseHeaders.put("X-Auth-Status", "deny");
        JSONObject err = new JSONObject();
        err.put("error", "unauthorized");
        return err;
    }
    JSONObject res = new JSONObject();
    res.put("user", user);
    return res;
}
```

Для проверки авторизации **в БД** — `session.get("DATABASE").get("CONNECT") != null`.

### 1.8. CORS

Внутри метода можно писать напрямую в `query.responseHeaders` — они попадут в ответ (`sendResponse` проходит по этой мапе):

```java
query.responseHeaders.put("Access-Control-Allow-Origin", "*");
query.responseHeaders.put("Access-Control-Allow-Credentials", "true");
```

---

## 2. События (CustomEvent)

Всё общение `D3Api` ↔ прикладной код построено на `document.dispatchEvent(new CustomEvent(...))`. Перечень:

| Событие | `detail` | Когда |
|---|---|---|
| `varChanged` | `{name, newValue, oldValue}` | `setVar` |
| `sessionChanged` | `{name, newValue, oldValue}` | `setSession` |
| `sessionSaved` | `{name, value}` | после сохранения в `{component}/session` |
| `sessionLoaded` | `{data}` | после `initSession` |
| `sessionError` | `{error}` | ошибка сессии |
| `valueChanged` | `{name, newValue, oldValue}` | `setValue` |
| `captionChanged` | `{name, newText, oldText}` | `setCaption` |
| `datasetChanged` | `{name, value}` | `D3Api.setDataset` (переопределение сеттера) |
| `d3apiExtensionsLoaded` | `{loaded: true}` | после подгрузки `d3api_js` |
| `scriptLoaded` | `{name, src}` | `<script>` из `cmpScript` |
| `scriptExecuted` | `{name}` | `D3Api.executeScript` |
| `scriptError` | `{name, src, error}` | ошибка |
| `buttonClick`, `buttonHover`, `buttonLeave`, `buttonFocus`, `buttonBlur` | `{name, ...}` | `cmpButton_js` |
| `dependenceChanged` | `[name, isValid]` | `cmpDependences` |

---

## 3. Статическое разделяемое состояние (сервер)

Для обмена данными между всеми клиентами сервер предоставляет готовые разделяемые структуры:

| Поле | Тип | Назначение |
|---|---|---|
| `HttpExchange.SHARE` | `JSONObject` (static) | Произвольное JSON-состояние, доступное из всех запросов |
| `HttpExchange.DevList` | `ConcurrentHashMap<String, HttpExchange>` | Зарегистрированные устройства/соединения |
| `HttpExchange.MESSAGE_LIST` | `ConcurrentHashMap<String, String>` | Плоский список сообщений |
| `HttpExchange.BROADCAST_MESSAGE_LIST` | `ConcurrentHashMap<String, List<String>>` | Списки сообщений по ключу (каналы/комнаты) |
| `ServerResourceHandler.SERVER_SHARE` | `JSONObject` (static) | Разделяемый объект уровня handler |
| `ServerResourceHandler.sessionList` | `HashMap<String, HashMap<String,Object>>` | Все сессии (для админ-задач) |

Пример мини-чата через `BROADCAST_MESSAGE_LIST`:

```java
public class chat {
    @Post(url="chat_send.java", ext="json")
    public JSONObject send(HttpExchange query) {
        String body = new String(query.postByte, java.nio.charset.StandardCharsets.UTF_8);
        JSONObject req = new JSONObject(body);
        String room = req.optString("room", "main");
        String text = req.optString("text", "");
        String user = String.valueOf(query.session.getOrDefault("AUTH_USER", "Гость"));

        HttpExchange.BROADCAST_MESSAGE_LIST
            .computeIfAbsent(room, k -> java.util.Collections.synchronizedList(new java.util.ArrayList<>()))
            .add(user + ": " + text);
        return new JSONObject().put("ok", true);
    }

    @Get(url="chat_list.java", ext="json")
    public JSONObject list(HttpExchange query) {
        String room = query.requestParam.optString("room", "main");
        java.util.List<String> msgs = HttpExchange.BROADCAST_MESSAGE_LIST.get(room);
        return new JSONObject().put("messages", msgs == null
                ? new org.json.JSONArray() : new org.json.JSONArray(msgs));
    }
}
```

Клиент: `setInterval(() => fetch('/chat_list.java?room=main')..., 1500)` (long-polling или SSE — см. §5).

---

## 4. Терминальный режим (`@onTerminal`) — IoT и peer-to-peer

Предназначен для постоянного TCP-соединения (ESP8266, операторы, дроны). Не HTTP: вместо GET/POST используется `TERM`.

### 4.1. Как работает

1. Клиент отправляет `TERM /device_esp8266_terminal_message.java\r\n`, затем заголовки (`device_name: ...`).
2. `readRequestHeader()` видит `typeQuery == "TERM"` → `sendResponseTerminal()` → `JavaStrExecut.runJavaTerminalFile(query)`.
3. Метод ищется в `ServerResource.pagesJavaTerminalClass` (наполняется `@onTerminal`-аннотацией), либо `.java` компилируется на лету и вызывается `onTerminal(HttpExchange)`.
4. Внутри цикла обработки используется `query.readLineTerm()` (читает сырые байты) и `query.write(byte[])`.
5. `query.write(...)` пишет без HTTP-заголовков — сырые данные в сокет.

### 4.2. Регистрация метода

```java
import ru.miacomsoft.EasyWebServer.util.onTerminal;

public class device_esp8266_terminal {
    public static final ConcurrentHashMap<String, HttpExchange> GLOBAL_LIST_DEVICE_CONNECT =
        new ConcurrentHashMap<>();

    @onTerminal(url="device_esp8266_terminal_message.java")
    public void onPage(HttpExchange query) {
        String name = (String) query.headers.get("device_name");
        GLOBAL_LIST_DEVICE_CONNECT.put(name, query);
        query.write(("{\"register\":\"" + name + "\"}\r\n").getBytes());

        while (true) {
            String msg = query.readLineTerm();
            if (msg == null) break;
            if (msg.isEmpty()) continue;

            // peer-to-peer: пересылаем «оператору», если он привязан
            if (query.queryPtP != null && !query.queryPtP.socket.isClosed()) {
                query.queryPtP.write(msg.getBytes());
            }
        }
        GLOBAL_LIST_DEVICE_CONNECT.remove(name);
    }
}
```

Требования:
- метод **ровно один параметр** `HttpExchange` (проверяется в `PackageManager.processTerminalAnnotation`);
- возвращаемый тип — `byte[]` или `void`. Если `byte[]` и не `null` — будет отправлен через `query.sendHtml`. Если `void` — возврат игнорируется.

### 4.3. Peer-to-peer связка

Механизм подключения «оператор ↔ устройство» реализован **вручную** через `query.queryPtP` (ссылка на встречный `HttpExchange`). Пример — `device_esp8266_terminal.onSend`: при подключении оператора находит нужное устройство в `GLOBAL_LIST_DEVICE_CONNECT`, связывает оба объекта `queryPtP`, затем цикл читает от оператора и пишет в сокет устройства.

Готовый референс — `ru.miacomsoft.device_esp8266_terminal` и `ru.miacomsoft.device_esp8266_web`.

---

## 5. Server-Sent Events (SSE)

Работает через прямой доступ к сокету. Удобно для уведомлений и стриминга (когда WebSocket не нужен).

```java
public class sse {
    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "text/event-stream";
        query.responseHeaders.put("Cache-Control", "no-cache");

        try {
            java.io.OutputStream out = query.socket.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\n"
                     + "Content-Type: text/event-stream\r\n"
                     + "Cache-Control: no-cache\r\n\r\n").getBytes());

            for (int i = 0; i < 30; i++) {
                out.write(("data: {\"t\":" + System.currentTimeMillis() + "}\n\n")
                          .getBytes());
                out.flush();
                Thread.sleep(2000);
            }
        } catch (Exception ignore) {}
        return null;  // важно: отключаем автоматический ответ
    }
}
```

Ключевой момент — вернуть `null` из `onPage`: тогда `runJavaFile`/`processJavaInnerClass` **не отправит** ответ повторно (проверка `if (messageBytes != null)`).

Клиент:
```javascript
const es = new EventSource('/sse.java');
es.onmessage = e => console.log(JSON.parse(e.data));
```

---

## 6. WebRTC-сигналинг (`WebRTCSignalingServer`)

Полноценный WebSocket-сервер, **отдельный порт** (`VIDEOCHAT_PORT` в `config.ini`). Запускается в `WebServer.run()`, если `VIDEOCHAT_PORT > 0`. Готовый пример клиента — `conference.html`.

### 6.1. Запуск

```json
"VIDEOCHAT_PORT": "8081"
```

```
[WebRTC] Signaling server started on port 8081
```

Сервер реализует минимально необходимый WebSocket (RFC 6455) вручную: handshake, чтение/запись фреймов, close-фреймы. Никакой зависимости от внешних библиотек.

### 6.2. Endpoints

| Endpoint | Роль |
|---|---|
| `ws://host:VIDEOCHAT_PORT/ws/publish?room=X&pass=Y` | Вещатель (screen share / webcam) |
| `ws://host:VIDEOCHAT_PORT/ws/subscribe?room=X&pass=Y` | Зритель |

Параметры:
- `room` — имя комнаты (default `"main"`);
- `pass` — пароль (опциональный, если первый подключившийся указал, остальные обязаны совпасть).

### 6.3. Комнаты

```java
static class Room {
    String name;
    String password;
    volatile WsConnection publisherWs;
    final Map<String, WsConnection> viewers = new ConcurrentHashMap<>();
}
```

Глобальный реестр — `WebRTCSignalingServer.ROOMS` (`ConcurrentHashMap<String, Room>`).

**Поведение:**
- Один вещатель на комнату; при подключении второго старый получает `close(1000, "replaced")`, а все зрители отключаются с `"publisher restarted"`.
- При подключении зрителя ему приходит `{viewerId: "..."}`; вещателю — `{viewerJoined: "<viewerId>"}`.
- При отключении зрителя вещателю приходит `{viewerLeft: "<viewerId>"}`.

### 6.4. Протокол (JSON-фреймы)

**От вещателя к зрителю** — адресация по `target: "<viewerId>"`:

```json
{"target":"<viewerId>", "sdp":  { ... RTCSessionDescription } }
{"target":"<viewerId>", "candidate": { ... RTCIceCandidate } }
```

**От зрителя к вещателю** — `target: "publisher"`, сервер добавляет `viewerId`:

```json
{"target":"publisher", "sdp":  { ... } }
{"target":"publisher", "candidate": { ... } }
```

Сервер не парсит содержимое `sdp`/`candidate` — просто перекладывает JSON.

### 6.5. Клиент (фрагмент из `conference.html`)

```javascript
function getWsUrl(path) {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${proto}//${location.hostname}:8081${path}`
         + `?room=${encodeURIComponent(roomName)}&pass=${encodeURIComponent(pass)}`;
}

// Вещатель
const ws = new WebSocket(getWsUrl('/ws/publish'));
ws.onmessage = async (e) => {
    const msg = JSON.parse(e.data);
    if (msg.viewerJoined) {
        const pc = new RTCPeerConnection({iceServers:[{urls:'stun:stun.l.google.com:19302'}]});
        localStream.getTracks().forEach(t => pc.addTrack(t, localStream));
        pc.onicecandidate = ev => {
            if (ev.candidate) ws.send(JSON.stringify({target: msg.viewerJoined, candidate: ev.candidate}));
        };
        peers.set(msg.viewerJoined, pc);
        const offer = await pc.createOffer();
        await pc.setLocalDescription(offer);
        ws.send(JSON.stringify({target: msg.viewerJoined, sdp: pc.localDescription}));
    } else if (msg.sdp) {
        await peers.get(msg.viewerId).setRemoteDescription(msg.sdp);
    } else if (msg.candidate) {
        await peers.get(msg.viewerId).addIceCandidate(msg.candidate);
    }
};

// Зритель
const ws = new WebSocket(getWsUrl('/ws/subscribe'));
ws.onmessage = async (e) => {
    const msg = JSON.parse(e.data);
    if (msg.viewerId) return; // регистрация
    if (msg.sdp) {
        if (!pc) {
            pc = new RTCPeerConnection({iceServers:[{urls:'stun:stun.l.google.com:19302'}]});
            pc.ontrack = ev => remoteVideo.srcObject = ev.streams[0];
            pc.onicecandidate = ev => {
                if (ev.candidate) ws.send(JSON.stringify({target:'publisher', candidate: ev.candidate}));
            };
        }
        await pc.setRemoteDescription(msg.sdp);
        await pc.setLocalDescription(await pc.createAnswer());
        ws.send(JSON.stringify({target:'publisher', sdp: pc.localDescription}));
    } else if (msg.candidate) {
        await pc.addIceCandidate(msg.candidate);
    }
};
```

> **Браузерный нюанс:** `getDisplayMedia()` работает только на HTTPS или `localhost`. Локально поднимайте сервер с `HTTPS_ENABLED=true` (см. §7) либо тестируйте с `http://localhost:PORT`.

---

## 7. HTTPS — две независимые схемы

Проект поддерживает **два механизма HTTPS одновременно**. Оба работают независимо.

### 7.1. Схема 1. `SSLManager` (PEM-сертификаты, основной порт)

Поведение **основного** `DEFAULT_PORT` переключается на TLS. Использует `SSLServerSocketFactory` из PEM (`fullchain.pem` + `privkey.pem`). Удобно для Let's Encrypt.

**config.ini:**
```json
"HTTPS_ENABLED": true,
"HTTPS_CERT": "/home/user/certs/smwrap.ru.crt",
"HTTPS_KEY":  "/home/user/certs/smwrap.ru.key"
```
или в стиле EasyWebServer1:
```json
"https": {
    "enabled": true,
    "cert": "/home/user/certs/smwrap.ru.crt",
    "key":  "/home/user/certs/smwrap.ru.key"
}
```

В `WebServer.run()`:
```java
if (HTTPS_ENABLED && !HTTPS_CERT.isEmpty() && !HTTPS_KEY.isEmpty()) {
    SSLServerSocketFactory ssf = SSLManager.createSSLServerSocketFactory(
        HTTPS_CERT, HTTPS_KEY);
    ss = ssf.createServerSocket(port);
} else {
    ss = new ServerSocket(port);
}
```

Процесс получения сертификата Let's Encrypt, конвертация ключа в PKCS#8, продление — см. `HTTPS_SETUP.md`.

### 7.2. Схема 2. `HttpsServerWrapper` (keystore, отдельный порт)

Дополнительный HTTPS-сервер на **отдельном** `HTTPS_PORT`, параллельно с HTTP (или схемой 1). Использует JKS/PKCS12 keystore.

**config.ini:**
```json
"HTTPS_PORT": "443",
"KEYSTORE_PATH": "/etc/ssl/easywebserver/keystore.jks",
"KEYSTORE_PASSWORD": "your_password",
"KEY_PASSWORD": ""
```

Генерация keystore:
```bash
keytool -genkey -keyalg RSA -alias easywebserver \
        -keystore keystore.jks -storepass your_password \
        -validity 365 -keysize 2048
```

В `WebServer.start()` вызывается `initHttps()`; при `HTTPS_PORT <= 0` — пропускается.

### 7.3. Что выбрать

| Задача | Схема |
|---|---|
| Let's Encrypt / PEM (Linux, ручной DNS-challenge) | 1 (`SSLManager`) |
| Один порт и HTTP, и HTTPS | 1 (`SSLManager`) |
| Несколько доменов / сложный keystore | 2 (`HttpsServerWrapper`) |
| Оба режима одновременно | обе схемы |

---

## 8. Рекомендации по безопасности

1. **Всегда HTTPS в проде.** Для схемы 1 — `HTTPS_ENABLED=true`, для схемы 2 — `HTTPS_PORT=443`. Без TLS `BasicAuth`, `session`-cookie и пароли видны в трафике.
2. **Не пишите session-ID в URL.** Только `Set-Cookie` (уже реализовано). `sessionDirect` пишет **URL**, а не токен — это безопасно.
3. **Проверяйте `session` на сервере**, а не на клиенте. Всё, что приходит из `query.requestParam`, `query.postByte`, `query.headers` — недоверенное.
4. **`loginDataBase`** открывает соединение от имени пользователя БД. Если нужен ограниченный доступ — используйте отдельных пользователей с грантами на схему, не `postgres`.
5. **WebRTC `pass`** — единственная защита комнаты. Для продакшна используйте длинный случайный пароль.
6. **Терминальные устройства** (`@onTerminal`) не имеют встроенной аутентификации. Идентификация — по `device_name` в заголовках; для продакшна добавьте проверку токена в `query.headers`.
7. **CORS**: заголовки `Access-Control-Allow-Origin` добавляйте только в тех `onPage`, где действительно нужны.
8. **XSS**: экранируйте HTML (`Jsoup.clean`) при вставке пользовательских строк в HTML-ответ.

---

## 9. Резюме

- **Сессии** — `HashMap<String,Object>` в `ServerResourceHandler.sessionList`, доступ через `query.session`. Cookie `session=<uuid>` выставляется автоматически.
- **`{component}/session`** — set/get/getAll; клиентская обёртка — `D3Api.setSession/getSession/initSession`.
- **`{component}/sessionDirect`** — сохранение URL для возврата; клиент — `D3Api.saveDirect/loadDirect`.
- **`{component}/loginDataBase`** — вход в PostgreSQL; пароль хранится в `session["DATABASE"]`.
- **Watchers** — `onChangeVar/Value/Caption/Session` + CustomEvents на `document`.
- **Статическое разделяемое состояние** — `HttpExchange.SHARE`, `MESSAGE_LIST`, `BROADCAST_MESSAGE_LIST`, `DevList`.
- **Терминальный режим** — `@onTerminal(url=...)` + `query.readLineTerm()` + `query.write()` + `query.queryPtP` для peer-to-peer. Пример — `device_esp8266_terminal`.
- **WebRTC-сигналинг** — встроенный WebSocket-сервер на `VIDEOCHAT_PORT` (`/ws/publish` + `/ws/subscribe`, JSON-фреймы, комнаты с паролем). Пример клиента — `conference.html`.
- **SSE** — через `query.socket.getOutputStream()` + `return null` из `onPage`.
- **HTTPS** — `SSLManager` (PEM, основной порт, `HTTPS_ENABLED`) или `HttpsServerWrapper` (keystore, отдельный `HTTPS_PORT`); могут работать одновременно.