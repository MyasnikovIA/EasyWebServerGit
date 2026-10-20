# Терминальный режим (TERM) для контроллеров ESP в EasyWebServer

Документ описывает **фактически реализованный** в проекте механизм `TERM`. Раздел «Подводные камни» содержит известные ограничения текущей реализации — их нужно учитывать при проектировании IoT-сценариев.

---

## 1. Что такое TERM на самом деле

`TERM` — это **не отдельный протокол и не отдельный сервер**. Это значение HTTP-метода, которое распознаётся в стандартном `ServerResourceHandler.readRequestHeader()`:

```java
query.typeQuery = titleLine.substring(0, titleLine.indexOf(" ")); // "TERM"
...
if (query.typeQuery.toUpperCase().equals("TERM")) {
    sendResponseTerminal();      // ← диспетчеризация в @onTerminal
    return false;                // ← обычный sendResponseQuery() НЕ вызывается
}
```

Всё остальное — обычный `Socket` из `ServerSocket`. HTTP-заголовки читаются, парсятся в `query.headers`, после чего соединение **не закрывается**, а передаётся пользовательскому методу. Далее обмен идёт **сырыми байтами**, без HTTP-обёртки (если только метод явно не вызовет `sendHtml`).

Поэтому `TERM` — это «TCP-туннель через HTTP-заголовок». Подходит для **TCP-клиентов** (ESP, native-приложения, демоны). Для браузера — ограниченно пригоден (см. §9).

---

## 2. Полный поток запроса

1. Клиент открывает TCP-сокет и присылает:
   ```
   TERM /device_esp8266_terminal_message.java HTTP/1.1
   device_name: Drone_001
   
   ```
   (последняя пустая строка обязательна — маркер конца заголовка)

2. `ServerResourceHandler.readRequestHeader()`:
    - читает байты до `\r\n\r\n` (или `\n\n`, или `\r\r`);
    - `query.typeQuery = "TERM"`;
    - `query.requestPath = "/device_esp8266_terminal_message.java"`;
    - парсит заголовки → `query.headers.get("device_name") = "Drone_001"`;
    - `query.session = getSession(query)` (сессия тоже создаётся);
    - `query.typeQuery == TERM` → `sendResponseTerminal()`;
    - `return false` → в `run()` блок `sendResponseQuery()` **не выполняется**.

3. `sendResponseTerminal()` → `JavaStrExecut.runJavaTerminalFile(query)`:
    - если `query.requestPath` есть в `ServerResource.pagesJavaTerminalClass` (наполняется `PackageManager` при старте) — берётся готовый `JavaTerminalClassObject` (метод + экземпляр);
    - иначе файл `.java` компилируется на лету и ищется метод `onTerminal(HttpExchange)`;
    - вызывается `meth.invoke(term.ObjectInstance, query)`.

4. Метод `@onTerminal` работает в блокирующем режиме, пока сам не вернёт управление.

5. Возврат:
    - если метод вернул `byte[]` (не `null`) — он отправляется клиенту через `query.sendHtml(...)` (**с HTTP-заголовками 200 OK**);
    - если метод вернул `void` или `null` — ничего не отправляется автоматически. Всё, что было отправлено через `query.write(...)`, уже ушло в сокет как сырые байты.

Практический вывод: **возвращайте `void`** и пишите сырые байты через `query.write(...)`. Так вы не получите неожиданно приклеенный HTTP-заголовок посреди «терминального» потока.

---

## 3. Требования к методу `@onTerminal`

```java
package ru.miacomsoft;                        // любой пакет из classpath
import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.util.onTerminal;

public class myTerminal {
    @onTerminal(url="my_terminal.java")
    public void onClient(HttpExchange query) { // должно быть public
        // ...
    }
}
```

Проверяется в `PackageManager.processTerminalAnnotation` и `JavaStrExecut.runJavaTerminalFile`:

| Требование | Где проверяется | Комментарий |
|---|---|---|
| Аннотация `@onTerminal(url=...)` на **методе** | `parseResourceClass` перебирает `clazz.getMethods()` | `@Target(TYPE)` фактически **не обрабатывается** — только методы |
| Метод `public` | `clazz.getMethods()` | иначе не будет найден |
| Ровно **один параметр** типа `HttpExchange` | `parameterTypes.length == 1 && parameterTypes[0].equals(HttpExchange.class)` | 0 или 2+ параметров → метод молча пропускается |
| Класс имеет **публичный конструктор без аргументов** | `clazz.getDeclaredConstructor().newInstance()` | по умолчанию есть, если нет других |
| Возвращаемый тип `void` или `byte[]` | `(byte[]) meth.invoke(...)` | иначе `ClassCastException` |
| Один экземпляр класса на все соединения | `PackageManager` создаёт **один** `ObjectInstance` | все поля экземпляра общие → **state должен быть thread-safe или отсутствовать** |

**Регистрация по URL:** ключом в `pagesJavaTerminalClass` служит `annotation.url()`, а не путь исходного файла. Один класс может содержать несколько `@onTerminal`-методов с разными `url`.

При старте `WebServer.viewWebResource()` печатает список зарегистрированных терминалов:
```
------- Terminal ------
queryType: class ru.miacomsoft.device_esp8266_terminal
method: public void ru.miacomsoft.device_esp8266_terminal.onPage(...)
http://127.0.0.1:PORT/device_esp8266_terminal_message.java
```

---

## 4. `HttpExchange` в контексте TERM

Поля, релевантные TERM-режиму:

| Поле / метод | Назначение |
|---|---|
| `query.headers` | Заголовки HTTP-запроса (уже разобраны до вызова) |
| `query.requestPath` | Путь из строки запроса (например, `/my_terminal.java`) |
| `query.sessionID`, `query.session` | Полноценная сессия (создаётся до вызова) |
| `query.socket` | Прямой доступ к `Socket` |
| `query.write(byte[])` | **Сырая** запись в сокет. Возвращает `true` при успехе |
| `query.sendHtml(String)` | HTTP-200 с `Content-Length`. В TERM **не используйте** посреди потока |
| `query.readLineTerm()` | Чтение «строки» (см. §5) |
| `query.isConnected()` | Проверка `socket.isConnected() && inputStreamReader.ready()` (см. §5) |
| `query.queryPtP` | Произвольная ссылка на встречный `HttpExchange`. Фреймворк **не управляет** ею — это ваш «peer-to-peer» канал |

Таймаут сокета выставляется в конструкторе `HttpExchange`:
```java
this.socket.setSoTimeout(86400000);  // 24 часа
```

Поэтому `read()`/`readLineTerm()` блокируются до **24 часов**, если клиент ничего не присылает. Для долгоживущих сессий — это нормально; для отладки учтите.

---

## 5. `readLineTerm()` — как он работает **на самом деле**

```java
public String readLineTerm() {
    String tmpStr = "";
    try {
        byte[] temp = new byte[16384];
        int bytesRead;
        while ((bytesRead = socket.getInputStream().read(temp)) != -1 && socket.isConnected()) {
            tmpStr = new String(temp, 0, bytesRead);
            break;                              // ← выходим после первого успешного read()
        }
    } catch (IOException e) {
        e.printStackTrace();
        return null;
    }
    return tmpStr;
}
```

**Ключевые особенности:**

1. **Это НЕ построчное чтение.** Метод возвращает **всё, что вернул первый успешный `read()`** — от 1 байта до 16384 байт. Разделители `\r\n`/`\n` **не ищутся** и **не удаляются**.
2. **Грациозное закрытие соединения** (`read()` вернул `-1`) → возвращает `""` (пустую строку), а не `null`.
3. **IOException** (RST, обрыв) → возвращает `null`.
4. `socket.getInputStream()` — **не тот же поток**, что `query.inputStreamReader` (он использовался для чтения заголовков). Если клиент отправил заголовок и первые данные одной пачкой, часть данных после `\r\n\r\n` может «застрять» в `InputStreamReader` и **потеряться**. На практике для ESP-header обычно приходит отдельным пакетом и это не проявляется, но иметь в виду стоит.
5. `socket.isConnected()` возвращает `true` даже после того, как удалённая сторона закрыла соединение — поэтому проверка в условии **не помогает** обнаружить disconnect.

**Практическое следствие для парсинга:**

Если ESP шлёт `up:100;dir:1\r\n`, то один вызов `readLineTerm()` может вернуть:
- `"up:100;dir:1\r\n"` (весь пакет),
- `"up:100;"` (часть, если TCP разбил пакет),
- `"up:100;dir:1\r\nup:100;dir:2\r\n"` (две строки сразу, если они пришли в одном пакете).

**Поэтому разбивать на строки нужно самим:**

```java
String chunk = query.readLineTerm();
if (chunk == null) break;
if (chunk.isEmpty()) {
    // грациозное закрытие или пустой пакет
    if (query.socket.isClosed()) break;
    continue;
}
// аккумулируем и режем по \n
buffer.append(chunk);
int nl;
while ((nl = buffer.indexOf('\n')) >= 0) {
    String line = buffer.substring(0, nl).replace("\r", "").trim();
    buffer.delete(0, nl + 1);
    if (!line.isEmpty()) handleLine(line);
}
```

---

## 6. `write()` vs `sendHtml()` в TERM

- `query.write(byte[])` — **правильный** способ. Пишет байты напрямую в `socket.getOutputStream()`, без заголовков. Возвращает `boolean`.
- `query.sendHtml(String)` — отправляет HTTP/1.1 200 с заголовками и `Content-Length`. В TERM использовать **нельзя**, если вы уже что-то писали через `write()` — клиент получит мусор посреди потока.
- Возврат `byte[]` из `@onTerminal`-метода → `runJavaTerminalFile` вызовет `sendHtml`. Поэтому предпочитайте `void` + `write()`.

Также помните: `query.responseHeaders` в TERM-режиме **не отправляются** (если только не сработал `sendHtml`), поэтому CORS-заголовки и прочее здесь ни при чём.

---

## 7. `query.queryPtP` — peer-to-peer

`queryPtP` — это **просто публичное поле** `public HttpExchange queryPtP = null;`. Фреймворк его не читает и не пишет. Всё, что делает пример `device_esp8266_terminal`, — устанавливает перекрёстные ссылки между двумя активными `HttpExchange`, а затем пишет друг другу через `query.queryPtP.write(...)`.

**Никакой автоматической очистки `queryPtP` нет.** При закрытии одного из соединений второй продолжит писать в уже мёртвый сокет (получит `IOException` внутри `write()` — `write()` вернёт `false`).

**Рекомендуемый паттерн очистки** (используйте `try/finally`):

```java
@onTerminal(url="device_esp8266_terminal_message_send.java")
public void onOperator(HttpExchange query) {
    String operatorName = (String) query.headers.get("device_name");
    String targetName   = (String) query.headers.get("device_name_connect");

    HttpExchange device = GLOBAL_LIST_DEVICE_CONNECT.get(targetName);
    if (device == null) {
        query.write("{\"error\":\"device not found\"}\r\n".getBytes());
        return;   // ← ОБЯЗАТЕЛЬНО вернуться, а не продолжать
    }

    query.queryPtP  = device;
    device.queryPtP = query;
    query.write(("{\"register\":\"" + operatorName + "\"}\r\n").getBytes());

    try {
        StringBuilder buffer = new StringBuilder();
        while (true) {
            String chunk = query.readLineTerm();
            if (chunk == null) break;
            if (chunk.isEmpty()) {
                if (query.socket.isClosed()) break;
                continue;
            }
            buffer.append(chunk);
            int nl;
            while ((nl = buffer.indexOf('\n')) >= 0) {
                String line = buffer.substring(0, nl).replace("\r", "").trim();
                buffer.delete(0, nl + 1);
                if (line.isEmpty()) continue;
                if (device.queryPtP != query) break;   // peer уже отвязан
                if (!device.write(line.getBytes())) break;
            }
        }
    } catch (Exception ignore) {
    } finally {
        if (device.queryPtP == query)  device.queryPtP = null;
        if (query.queryPtP == device)  query.queryPtP = null;
        GLOBAL_LIST_DEVICE_CONNECT.remove(operatorName, query);
    }
}
```

---

## 8. Пример: устройство ↔ оператор (рабочая схема)

Реестр соединений — статический `ConcurrentHashMap` (т.к. экземпляр терминального класса один):

```java
public static final ConcurrentHashMap<String, HttpExchange> GLOBAL_LIST_DEVICE_CONNECT
        = new ConcurrentHashMap<>();
```

### 8.1. Сторона устройства

```java
@onTerminal(url="device_esp8266_terminal_message.java")
public void onDevice(HttpExchange query) {
    String deviceName = (String) query.headers.get("device_name");
    if (deviceName == null || deviceName.isEmpty()) {
        query.write("{\"error\":\"device_name required\"}\r\n".getBytes());
        return;
    }

    // заменяем старое соединение с тем же именем
    HttpExchange prev = GLOBAL_LIST_DEVICE_CONNECT.put(deviceName, query);
    if (prev != null && prev != query) {
        try { prev.socket.close(); } catch (Exception ignore) {}
    }
    query.write(("{\"register\":\"" + deviceName + "\"}\r\n").getBytes());

    try {
        StringBuilder buffer = new StringBuilder();
        while (true) {
            String chunk = query.readLineTerm();
            if (chunk == null) break;
            if (chunk.isEmpty()) {
                if (query.socket.isClosed()) break;
                continue;
            }
            buffer.append(chunk);
            int nl;
            while ((nl = buffer.indexOf('\n')) >= 0) {
                String line = buffer.substring(0, nl).replace("\r", "").trim();
                buffer.delete(0, nl + 1);
                if (line.isEmpty()) continue;

                HttpExchange peer = query.queryPtP;
                if (peer != null && !peer.socket.isClosed()) {
                    if (!peer.write((line + "\r\n").getBytes())) break;
                }
            }
        }
    } catch (Exception ignore) {
    } finally {
        GLOBAL_LIST_DEVICE_CONNECT.remove(deviceName, query);
        HttpExchange peer = query.queryPtP;
        if (peer != null && peer.queryPtP == query) peer.queryPtP = null;
        query.queryPtP = null;
    }
}
```

### 8.2. Сторона оператора

См. `onOperator` в §7. Ключевые отличия от примера из старой версии:

- проверка `device == null` **завершается `return`**, а не «пишет ошибку и идёт дальше»;
- `finally` отвязывает peer-ссылки с обеих сторон;
- используется `buffer` для сборки строк;
- вместо `query.socket.isConnected()` используется `isClosed()` и проверка возврата `write()`.

---

## 9. ESP-клиент (Arduino/ESP8266)

TCP-клиент, никакого HTTP-клиента не нужно — только сырой сокет.

```cpp
#include <ESP8266WiFi.h>

const char* ssid       = "YOUR_WIFI";
const char* password   = "YOUR_PASSWORD";
const char* serverHost = "192.168.1.100";
const int   serverPort = 8080;
const char* deviceName = "Drone_001";

WiFiClient client;
String readBuffer;

void setup() {
  Serial.begin(115200);
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) { delay(500); }
}

void loop() {
  if (!client.connected()) {
    if (!client.connect(serverHost, serverPort)) { delay(2000); return; }

    // HTTP-заголовок с методом TERM — обязателен
    client.print("TERM /device_esp8266_terminal_message.java HTTP/1.1\r\n");
    client.print("Host: ");          client.print(serverHost); client.print("\r\n");
    client.print("device_name: ");   client.print(deviceName); client.print("\r\n");
    client.print("\r\n");            // ← конец заголовка, начало «терминала»
    readBuffer = "";
  }

  // телеметрия раз в 2 секунды
  static unsigned long last = 0;
  if (millis() - last > 2000) {
    last = millis();
    client.print("TEMP:25.5;HUM:60;STATUS:OK\r\n");
  }

  // приём команд от оператора
  while (client.available()) {
    char c = (char) client.read();
    if (c == '\n') {
      String line = readBuffer; readBuffer = "";
      line.replace("\r", "");
      line.trim();
      if (line.length() > 0) {
        // пример: "up:100;dir:1"
        Serial.println("CMD: " + line);
      }
    } else {
      readBuffer += c;
      if (readBuffer.length() > 4096) readBuffer = "";  // защита от переполнения
    }
  }
}
```

> **Замечание по построению строк.** ESP сам решает, чем разделять сообщения (`\r\n`, `\n`, или вообще бинарный протокол). Сервер это **не навязывает**. Приведённый выше формат `...\r\n` — это соглашение примера, а не требование фреймворка.

---

## 10. Браузер как оператор — что реально работает

`TERM` в браузере **неудобен**: современные `fetch`/`XHR` не позволяют дуплексно писать в один и тот же сокет после отправки заголовка. Реальные варианты:

### Вариант A (рекомендуемый). Разделение на два endpoint'а

**Отправка команды на устройство** — обычный `@Post`:

```java
@Post(url="device_command.java", ext="json")
public JSONObject sendCommand(HttpExchange query) {
    JSONObject req = new JSONObject(new String(query.postByte, java.nio.charset.StandardCharsets.UTF_8));
    String target = req.optString("device");
    String cmd    = req.optString("command");

    HttpExchange device = device_esp8266_terminal.GLOBAL_LIST_DEVICE_CONNECT.get(target);
    if (device == null) return new JSONObject().put("error", "device offline");

    return new JSONObject().put("ok",
        device.write((cmd + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
}
```

**Приём телеметрии** — polling через `@Get` + `MESSAGE_LIST`/`BROADCAST_MESSAGE_LIST`:

```java
@Get(url="device_telemetry.java", ext="json")
public JSONObject telemetry(HttpExchange query) {
    String target = query.requestParam.optString("device");
    java.util.List<String> list = HttpExchange.BROADCAST_MESSAGE_LIST.get(target);
    return new JSONObject().put("messages",
        list == null ? new org.json.JSONArray() : new org.json.JSONArray(list));
}
```

В `@onTerminal` устройства дописывайте каждую строку:

```java
HttpExchange.BROADCAST_MESSAGE_LIST
    .computeIfAbsent(deviceName, k -> java.util.Collections.synchronizedList(new java.util.ArrayList<>()))
    .add(line);
if (list.size() > 500) list.subList(0, list.size() - 500).clear();   // обрезка
```

Браузер:
```javascript
function sendCmd(cmd) {
    fetch('/device_command.java', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({device: 'Drone_001', command: cmd})
    });
}
setInterval(() => fetch('/device_telemetry.java?device=Drone_001')
    .then(r => r.json()).then(j => render(j.messages)), 1000);
```

### Вариант B. SSE для приёма + `@Post` для отправки

`ssе` на сервере читает из `BROADCAST_MESSAGE_LIST[device]` и стримит браузеру. Отправка — тем же `@Post`, что и в варианте A.

### Вариант C. WebRTC (для видео/аудио с устройства)

Используйте `WebRTCSignalingServer` (см. `AUTH_AND_MESSAGING.md`, §6) — если нужна трансляция видео с камеры/экрана, а не телеметрия.

### Вариант D. Полноценный WebSocket

Фреймворк WebSocket «из коробки» (кроме сигналинга WebRTC) не предоставляет. Для произвольного двунаправленного чата/команд реализуйте свой WebSocket-сервер по образцу `WebRTCSignalingServer` и запускайте его отдельным `Thread` в `WebServer.start()`.

---

## 11. Альтернативы `TERM`, встроенные в фреймворк

Помимо `TERM` для передачи данных между клиентами можно использовать:

| Механизм | Где | Когда подходит |
|---|---|---|
| `HttpExchange.SHARE` (`JSONObject`) | static | Небольшие общие JSON-объекты, конфиги, простые «шины» |
| `HttpExchange.DevList` (`ConcurrentHashMap<String, HttpExchange>`) | static | Альтернатива локальному `GLOBAL_LIST_DEVICE_CONNECT` — использовать напрямую |
| `HttpExchange.MESSAGE_LIST` (`ConcurrentHashMap<String, String>`) | static | Плоские пары `key → значение` (последняя телеметрия, статусы) |
| `HttpExchange.BROADCAST_MESSAGE_LIST` (`ConcurrentHashMap<String, List<String>>`) | static | Каналы/комнаты с историей (чат, лог) |
| `{component}/session` | HTTP | Хранение состояния **внутри сессии** конкретного клиента |
| `WebRTCSignalingServer` | отдельный порт | Видео/аудио P2P |
| SSE (свой код через `query.socket.getOutputStream()`) | HTTP | Push от сервера клиенту в одну сторону |

`TERM` выбирайте, когда нужен **постоянный TCP-канал с устройства** (ESP/native) или **P2P между двумя TCP-клиентами** без промежуточного HTTP.

---

## 12. Известные подводные камни (актуальная реализация)

1. **`readLineTerm()` не построчный.** Не полагайтесь на то, что один вызов = одна строка. Собирайте в буфер и режьте по `\n`.

2. **Грациозное закрытие возвращает `""`, а не `null`.** Следствие: цикл `while (true) { String s = readLineTerm(); if (s.length() == 0) continue; ... }` на закрытом соединении даёт **100% CPU** (read сразу возвращает `-1`, метод возвращает `""`, `continue`).

3. **`null` возвращается только при `IOException`.** Проверка `if (s.length() == 0)` до `if (s == null)` фатальна — вы получите `NullPointerException` при RST.

4. **`query.socket.isConnected()` не обнаруживает disconnect.** Используйте `isClosed()` + `write() == false` + буферизацию.

5. **Возврат `byte[]` из `@onTerminal`-метода вызовет `sendHtml`** — с HTTP-200 и заголовками. Не возвращайте `byte[]`, если уже писали через `write()`.

6. **Один экземпляр терминального класса на все соединения.** Все поля экземпляра — общие. Используйте `static ConcurrentHashMap` (или thread-safe структуры), а не поля экземпляра.

7. **`@onTerminal` требует ровно один параметр `HttpExchange`.** С двумя параметрами или без параметров метод молча **не зарегистрируется**.

8. **Класс-аннотация `@Target(TYPE)` фактически не работает** — `PackageManager` перебирает только методы.

9. **Потеря первых байт тела.** `readRequestHeader` читает заголовок чанками по 1024 символа. Если клиент прислал заголовок и первые данные в одном пакете, часть после `\r\n\r\n` попадёт в `bufferArrPost`, но `query.postCharBody` **не устанавливается** (нет `Content-Length`), и эти данные будут потеряны. Практический вывод: **ESP должен отправлять заголовок отдельным `client.print`/пакетом** и подождать ~50–100 мс перед первой строкой данных.

10. **Ошибка в `device_esp8266_terminal.onSend`.** В исходном коде при отсутствии устройства он пишет `{"error":...}`, **не возвращаясь**, после чего делает `querySend.queryPtP = query` с `querySend == null` → NPE. См. исправленный пример в §7–§8.

11. **`System.out.println` внутри цикла** — синхронный и медленный. При 100 msg/s это узкое место. Используйте лог-фреймворк с уровнем `DEBUG` или буферизируйте.

12. **`socket.setSoTimeout(86400000)`** — `readLineTerm()` может блокироваться до 24 часов. Если нужен таймаут — вызывайте `query.socket.setSoTimeout(мс)` в начале метода и обрабатывайте `SocketTimeoutException`.

---

## 13. Резюме

- **`TERM`** — это HTTP-метод, распознаваемый в `readRequestHeader`; после заголовка соединение превращается в сырой TCP.
- Обработчик — `@onTerminal(url=...) public void method(HttpExchange query)` (ровно один параметр).
- Обмен — `query.write(byte[])` для отправки, `query.readLineTerm()` для приёма **чанков** (не строк!).
- **P2P** — вручную через `query.queryPtP`, с обязательной очисткой в `finally`.
- Реестр соединений — статический `ConcurrentHashMap` (экземпляр один).
- **Браузер как оператор** — используйте `@Post` для отправки команд + polling/SSE для приёма; `TERM` из браузера — не основной сценарий.
- **Известные ограничения** (не построчное чтение, `""` на close, потеря первых байт тела, один экземпляр) — учитывайте при проектировании IoT-протокола.