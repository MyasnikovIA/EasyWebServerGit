# Динамические `.java` страницы в EasyWebServer

Документ описывает **фактическую реализацию** в `JavaStrExecut` / `ServerResourceHandler`. Раздел «Подводные камни» содержит отличия от «идеального» поведения — их нужно учитывать.

---

## 1. Как работает

`ServerResourceHandler.processFileResource()` смотрит расширение запроса. Если это `.java` — вызывается `JavaStrExecut.runJavaFile(query)` и **больше ничего не обрабатывается**:

```java
String ext = getFileExt(query.requestPath).toLowerCase();
if ("java".equals(ext)) {
    ServerResourceHandler.javaStrExecut.runJavaFile(query);
    return;
}
```

`runJavaFile` вызывает `compileFile(WEBAPP_DIR, requestPath, info)`:

```java
File nameFileObj = new File(ServerConstant.config.WEBAPP_DIR + '/' + requestPath);
String classNameText = nameFileObj.getName();          // "hello.java" → "hello"
long   lastModified  = nameFileObj.lastModified();
```

Логика:

1. Если файл уже компилировался и `lastModified` совпадает — берётся кэшированный `CompileObject`.
2. Иначе файл **читается целиком**, считается MD5 по содержимому.
3. Если MD5 новый — вызывается `compileMemoryMemory(src, classNameText, ...)` → компилятор `javax.tools.JavaCompiler` в памяти.
4. Создаётся экземпляр класса (`ClassNat.newInstance()` — нужен публичный конструктор без аргументов).
5. Из всех **публичных** методов класса (`getMethods()`) сохраняются те, у которых **есть параметр типа `HttpExchange`** (по имени).
6. Кэш пишется в:
    - `InstanceClassHash` — по MD5 содержимого;
    - `InstanceClassName` — по `requestPath` (например, `/api/hello.java`).
7. `runJavaFile` берёт метод `onPage`, если есть, иначе `page`:

```java
if (compileObject.methods.containsKey("onPage")) {
    meth = compileObject.methods.get("onPage");
} else if (compileObject.methods.containsKey("page")) {
    meth = compileObject.methods.get("page");
}
```
8. Устанавливает `query.mimeType = "text/html"` **принудительно** (см. §4).
9. Вызывает `meth.invoke(compileObject.ObjectInstance, query)`.
10. Обрабатывает возврат по правилам §5.

**Ключевой момент:** имя файла должно совпадать с именем публичного класса — `hello.java` должен содержать `public class hello`, иначе `Class.forName(classNameText, ...)` не найдёт его и попадёт в `ClassNotFoundException`.

---

## 2. Структура каталогов

`.java` файлы лежат **внутри `WEBAPP_DIR`** и подгружаются через URL, повторяющий путь к файлу:

```text
www/
├── index.html
├── api/
│   ├── hello.java            ← GET /api/hello.java
│   └── users/
│       └── list.java         ← GET /api/users/list.java
└── test.java                 ← GET /test.java
```

- Файл должен существовать, иначе `findFileInAllDirectories` вернёт `null` → 404 (`handleNotFound`).
- `WEBAPP_DIRS` (несколько каталогов) — ищется по всем по порядку, как обычные файлы.
- `requestPath` после отсечения `?query` идёт напрямую в `new File(WEBAPP_DIR + '/' + requestPath)` — **path traversal возможен**, если код сервера не проверяет `..`. Для продакшна нужен whitelist.

---

## 3. Требования к `.java` файлу

| Требование | Проверяется | Комментарий |
|---|---|---|
| Имя публичного класса = имя файла без `.java` | `Class.forName(classNameText, ...)` | Иначе `ClassNotFoundException` → 500 |
| Ровно один публичный класс | неявно | package-private классы рядом допустимы |
| Публичный конструктор без аргументов | `newInstance()` | есть по умолчанию, если не задано других |
| Метод `onPage` **или** `page` | `runJavaFile` | имя проверяется явно |
| Метод имеет ровно один параметр `HttpExchange` | `getParameterTypes()` + `invoke` | методы с 0/2+ параметрами дают `IllegalArgumentException` на `invoke` |
| Метод публичный | `getMethods()` возвращает только public | иначе метод «не найден» |
| Возврат `byte[]` или `null` | `(byte[]) meth.invoke(...)` | возврат другого типа → `ClassCastException` |
| Метод **не обязательно `static`** | `invoke(ObjectInstance, query)` | вызывается **на экземпляре** — это `instance method` |

Импорт `ru.miacomsoft.EasyWebServer.HttpExchange` обязателен — `compileFile` **не добавляет** обёртку (в отличие от `JavaStrExecut.compile` для `<cmpAction query_type="java">`).

---

## 4. Базовый шаблон

```java
import ru.miacomsoft.EasyWebServer.HttpExchange;
import org.json.JSONObject;

public class hello {                             // ← имя = hello.java
    public hello() {}                            // можно не писать — default

    public static byte[] onPage(HttpExchange query) {
        String name = query.requestParam.optString("name", "Гость");
        String html = "<html><body><h1>Привет, "
                    + name + "!</h1></body></html>";
        return html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
```

`runJavaFile` перед `invoke` делает `query.mimeType = "text/html"`. После `invoke`, если вернулся `byte[]`:

```java
query.sendHtml(new String(messageBytes));
```

`sendHtml(String)` делает `content.getBytes()` и `sendResponse` — берёт `mimeType` из `query.mimeType`. То есть метод может **переопределить MIME-тип** внутри:

```java
public static byte[] onPage(HttpExchange query) {
    query.mimeType = "application/json; charset=utf-8";
    return "{\"ok\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
}
```

Это работает, потому что `sendHtml` читает `query.mimeType` уже после `invoke`.

---

## 5. Возврат из метода

| Что вернул метод | Что делает сервер |
|---|---|
| `byte[]` (не `null`) | `query.sendHtml(new String(bytes))` — обёртка HTTP-200 с `Content-Type: query.mimeType` |
| `null` | `return;` — **ничего не отправляется**, ответ уже должен быть отправлен из метода (через `query.write(...)`, `query.sendByteFile(...)`, `query.sendFile(...)` и т.п.) |
| Любой другой объект (`JSONObject`, `String`, `int`) | `ClassCastException` → `parseErrorRunJava` → HTML с ошибкой |
| `void` | `meth.invoke` вернёт `null` → не отправлено ничего |

**Строковый возврат `String` работать не будет** — это частая ошибка. Только `byte[]` или `null`.

---

## 6. Метод не найден

Если в классе нет ни `onPage`, ни `page` — `runJavaFile` пишет в ответ:

```
Метод для запуска не найден /api/hello.java
```

Это `text/html`. Клиент получит **HTTP 200** с этой строкой. Не 404, не 500. Учтите это при отладке.

Метод ищется по `compileObject.methods` — то есть по наличию **параметра `HttpExchange`**. Метод с именем `onPage` **без** такого параметра будет проигнорирован.

---

## 7. Работа с запросом внутри метода

`query` — стандартный `HttpExchange`. Полезные поля:

| Поле / метод | Что содержит |
|---|---|
| `query.typeQuery` | `"GET"` / `"POST"` / `"PUT"` / `"PATCH"` / `"TERM"` |
| `query.requestPath` | Путь без `?query` (например, `/api/hello.java`) |
| `query.requestText` | Оригинальный URI с query |
| `query.requestParam` | `JSONObject` с параметрами `?key=value` (значения — строки) |
| `query.requestParamArray` | То же самое, но `JSONArray` из `{key: value}` (для повторяющихся параметров) |
| `query.headers` | `Map<String,Object>` — заголовки запроса |
| `query.cookie` | `Map<String,String>` — cookie |
| `query.postByte` | `byte[]` тела POST/PUT/PATCH |
| `query.postCharBody` | `char[]` — **байты как char'ы**, не декодированная строка |
| `query.messageJson` | `JSONObject` тела, если `Content-Type: application/json` (распарсено автоматически) |
| `query.session` | `Map<String,Object>` — сессия клиента (см. `AUTH_AND_MESSAGING.md`) |
| `query.sessionID` | UUID сессии |
| `query.mimeType` | Тип ответа (`text/html` по умолчанию в `runJavaFile`) |
| `query.responseHeaders` | Доп. заголовки ответа (`CORS`, `Cache-Control`, ...) |

Полезные методы:

```java
query.write(byte[]);                 // сырые байты в сокет (без HTTP-заголовков)
query.send(byte[]);                  // HTTP-ответ с заголовками, использует query.mimeType
query.sendHtml(String);              // HTTP-200, query.mimeType
query.sendHtmlMime(byte[], String);  // HTTP-200, указанный mime
query.sendByteFile(File);            // HTTP-200, mime по расширению
query.sendFile(String path);         // то же, но по пути
query.sendFile(File);                // то же
query.sendImageFile(File);           // HTTP-200 с кэшем (max-age=86400)
query.SQL(String sql);               // JSONArray, БД по умолчанию
query.SQL(String sql, String dbName);// JSONArray, БД из config
```

Тело POST читайте через `postByte`:

```java
String body = new String(query.postByte, java.nio.charset.StandardCharsets.UTF_8);
JSONObject req = new JSONObject(body);
```

`postCharBody` — не строка! Каждый байт стал отдельным `char`. Для ASCII это неважно, для UTF-8 кириллицы — данные искажены. Используйте `postByte`.

`requestParam` всегда содержит **строки**, даже для чисел:

```java
int age = Integer.parseInt(query.requestParam.optString("age", "0"));
```

---

## 8. Сессия и авторизация

`query.session` создаётся автоматически до вызова метода. Если в cookie был `session=<uuid>` — используется существующая сессия, иначе создаётся новая (UUID кладётся в `Set-Cookie`).

```java
Object user = query.session.get("AUTH_USER");
if (user == null) {
    query.mimeType = "application/json";
    return "{\"error\":\"unauthorized\"}".getBytes();
}
```

Cookie `session` выставляется автоматически, но **флаг `HttpOnly`/`Secure` не ставится** — при публикации обязательно настройте HTTPS (`AUTH_AND_MESSAGING.md`, §7).

---

## 9. Работа с БД (`query.SQL`)

`query.SQL(sql)` использует соединение в таком порядке:
1. `session["DATABASE"]["CONNECT"]`, если есть (соединение из `{component}/loginDataBase`).
2. Иначе — системное (`ServerConstant.config.DATABASE_NAME` + `DATABASE_USER_*`).
3. Если БД не настроена или соединение упало — возвращается **пустой `JSONArray`**, не ошибка.

Пример:

```java
import ru.miacomsoft.EasyWebServer.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;

public class users {
    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/json; charset=utf-8";
        try {
            JSONArray rows = query.SQL("SELECT id, name FROM users ORDER BY id");
            return new JSONObject().put("data", rows).toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return new JSONObject().put("error", e.getMessage()).toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
```

**SQL с параметрами через `query.SQL` не поддерживается** — только простой текст (риск SQL-injection при подстановке пользовательских строк). Для параметризованных запросов используйте `PostgreQuery.executeQuery(sql, params, user, pass)` или `cmpAction`/`cmpDataset`.

Выбор БД из `config.ini` (`DATABASES.<name>`):

```java
query.SQL("SELECT * FROM t", "my_db");
```

Если БД не найдена — пустой массив.

---

## 10. Импорты и JAR-библиотеки

`compileFile` компилирует файл **как есть** — никаких автоматических `import` не добавляется. Все нужные импорты должны быть в `.java` файле.

Сторонние библиотеки подключаются двумя путями:

1. Через `LIB_JAR` в `config.ini` — **полные пути** к `.jar` файлам:
   ```json
   "LIB_JAR": [
       "/data/.../lib/json-20230227.jar",
       "/data/.../lib/postgresql-42.2.24.jar"
   ]
   ```
   Код в `compileMemoryMemory`:
   ```java
   for (String libJaR : ServerConstant.config.LIB_JAR) {
       if (libJaR.indexOf(File.separator) == -1) {
           jarResourse.add(libJaR);
       } else {
           jarResourse.add(ServerConstant.config.LIB_DIR + File.separator + libJaR);
       }
   }
   ```
   ⚠️ Логика инвертирована относительно комментария: если путь **содержит** `File.separator` — к нему приклеивается `LIB_DIR`. Если **не содержит** — берётся как есть. В результате либо `LIB_DIR/имя.jar`, либо полный путь без изменений (если в нём нет `/` на Windows это сработает только для коротких имён). На практике указывайте **полный путь** от корня и убедитесь, что `LIB_DIR` содержит нужные JAR'ы.

2. Через **classpath JVM** (`java -cp ...`) — стандартный механизм, `System.getProperty("java.class.path")` уже включён в classpath компилятора.

Пример:

```java
import ru.miacomsoft.EasyWebServer.HttpExchange;
import com.google.gson.Gson;

public class jsonTest {
    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/json";
        Gson gson = new Gson();
        return gson.toJson("Hello").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
```

---

## 11. Кэширование и перекомпиляция

| Что кэшируется | Ключ | Когда обновляется |
|---|---|---|
| `InstanceClassHash` | MD5 содержимого `.java` | при каждом новом хэше (новая компиляция) |
| `InstanceClassName` | `requestPath` (например, `/api/hello.java`) | после успешной компиляции |

Перекомпиляция происходит **всегда при изменении файла** — не только в `DEBUG`:

```java
if (!InstanceClassName.containsKey(requestPath) ||
    ((CompileObject) InstanceClassName.get(requestPath)).lastModified != lastModified) {
    // перечитываем файл
}
```

Но если содержимое не изменилось (тот же MD5) — `compileMemoryMemory` не запускается повторно:

```java
if (!InstanceClassHash.containsKey(hashCrs)) {
    // компиляция
}
```

**Практическое следствие:** правки в `.java` подхватываются на следующем запросе автоматически. Ручная перезагрузка сервера нужна только при изменении JAR-зависимостей или `config.ini`.

**Известная проблема с `lastModified`.** Если файл изменён, но его `mtime` совпадает с предыдущим (например, восстановление из бэкапа с той же датой), перекомпиляции не будет. В этом случае либо коснитесь файла (`touch`), либо перезапустите сервер.

---

## 12. Ошибки компиляции и выполнения

### 12.1. Ошибка компиляции

`runJavaFile` вызывает `parseErrorCompile(infoCompile)`, которая возвращает **HTML** с подсветкой места ошибки. HTML отправляется как `query.sendHtml(...)` (HTTP 200, `text/html`).

Пример вывода:

```html
HTTP error compile Java file:<br/>
Found 1 error.<br/>
<pre>COMPILATION ERROR : 
/hello.java:[5,12] cannot find symbol
  symbol:   variable myVar
  location: class hello</pre><br/>
<pre>... фрагмент кода с подсветкой ...</pre>
```

### 12.2. Ошибка выполнения

Если `meth.invoke` бросил `InvocationTargetException`, `IllegalAccessException`, `ClassCastException` — вызывается `parseErrorRunJava(e)`:

```
<pre>
Произошла ошибка: <сообщение>
Тип ошибки: <класс исключения>
Подробное описание:
<стектрейс>
</pre>
```

`query.mimeType = "text/plain"` **устанавливается только в этом catch-блоке**, а не для ошибки компиляции (там HTML).

### 12.3. Что вернуть при ошибке самому

Лучше обрабатывать ошибки внутри `onPage` и возвращать корректный JSON/HTML с HTTP-кодом в теле (HTTP-код ответа всегда 200 в текущей реализации):

```java
try {
    // ...
} catch (Exception e) {
    return new JSONObject()
            .put("status", "error")
            .put("message", e.getMessage())
            .toString().getBytes();
}
```

HTTP-статус (400/401/500) `sendHtml` **не поддерживает** — только 200.

---

## 13. Продвинутые примеры

### 13.1. JSON-эндпоинт

```java
// www/api/getUsers.java
import ru.miacomsoft.EasyWebServer.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;

public class getUsers {
    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/json; charset=utf-8";
        JSONObject res = new JSONObject();
        try {
            JSONArray rows = query.SQL("SELECT id, name FROM users ORDER BY id");
            res.put("status", "ok").put("data", rows);
        } catch (Exception e) {
            res.put("status", "error").put("message", e.getMessage());
        }
        return res.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
```

### 13.2. POST с JSON-телом

```java
// www/api/saveUser.java
import ru.miacomsoft.EasyWebServer.HttpExchange;
import org.json.JSONObject;

public class saveUser {
    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/json; charset=utf-8";

        if (!"POST".equalsIgnoreCase(query.typeQuery)) {
            return "{\"error\":\"POST required\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        JSONObject req;
        try {
            // messageJson уже распарсен в readRequestHeader, если Content-Type: application/json
            req = query.messageJson != null
                    ? query.messageJson
                    : new JSONObject(new String(query.postByte, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "{\"error\":\"invalid json\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        String name = req.optString("name", "");
        if (name.isEmpty()) {
            return "{\"error\":\"name required\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        // Здесь должна быть параметризованная вставка через PostgreQuery —
        // query.SQL не принимает параметры, только готовый SQL.
        // Пример через прямую JDBC:
        //   Map<String,Object> p = new HashMap<>();
        //   p.put("name", name);
        //   PostgreQuery.executeUpdate("INSERT INTO users(name) VALUES(:name)", p, user, pass);

        return "{\"status\":\"ok\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
```

### 13.3. Скачивание файла

```java
// www/download.java
import ru.miacomsoft.EasyWebServer.HttpExchange;
import java.io.File;

public class download {
    public static byte[] onPage(HttpExchange query) {
        String name = query.requestParam.optString("file", "");
        if (name.isEmpty() || name.contains("..")) {
            return "Bad request".getBytes();
        }
        File f = new File(ServerConstant.config.WEBAPP_DIR, "files/" + name);
        if (!f.exists()) {
            query.mimeType = "text/plain";
            return "File not found".getBytes();
        }
        query.sendByteFile(f);   // уже отправили HTTP-ответ
        return null;             // ← важно: не даём серверу отправить ещё раз
    }
}
```

### 13.4. TERM из `.java` файла

`.java` файл можно использовать и в TERM-режиме (`TERM /api/socket.java HTTP/1.1`). Логика:

- файл компилируется так же, через `compileFile`;
- но ищется метод `onTerminal(HttpExchange)`, а не `onPage`:

```java
// JavaStrExecut.runJavaTerminalFile
Method meth = compileObject.ClassNat.getMethod("onTerminal", argTypes);
```

После вызова, если вернулся `byte[]` — `query.write(messageBytes)` (**не** `sendHtml`, как для `onPage`). То есть для TERM можно возвращать `byte[]` и он уйдёт как сырые байты.

Подробнее про TERM — `CreateTermController.md`.

---

## 14. Что **не** является «динамическим `.java`» (не путать)

Три других механизма тоже работают через URL, но устроены иначе:

### 14.1. `{component}/ИмяКласса` — провайдеры JS/CSS/JSON

`runJavaServerlet`. Класс **уже в classpath** (не компилируется из файла), пакет **фиксирован**:
```
ru.miacomsoft.EasyWebServer.component.ИмяКласса
```
Метод `public static byte[] onPage(HttpExchange)`.

Пример — `md5.java`, `cmpButton_js.java`, `session.java`.

### 14.2. `/path/file.component` — обработчик компонентов

`runJavaComponent`. Класс из classpath, имя = путь без `.component` с `/` → `.`:
```
GET /ru/miacomsoft/MyHandler.component → ru.miacomsoft.MyHandler
```
Метод `public static byte[] onPage(HttpExchange)`.

Практически не используется — большинство компонентов работают через теги `<cmpXXX>` (см. `CreateComponents.md`).

### 14.3. `@Get/@Post/@onPage/...` — аннотации в classpath

Регистрируются в `ServerResource.pagesJavaInnerClass` при старте `PackageManager`. Не связаны с `.java` файлами в `www`. Пример — `index.java`, `device_esp8266_web.java`.

| Механизм | Файл `.java` в `www` | Компиляция в рантайме | Метод | Статический |
|---|---|---|---|---|
| Динамическая страница | да | да | `onPage` / `page` | нет (экземпляр) |
| `{component}/Xxx` | нет (classpath) | нет | `onPage` | да |
| `*.component` | нет (classpath) | нет | `onPage` | да |
| `@Get/@Post` | нет (classpath) | нет | любое имя | зависит от `invoke` |

---

## 15. Подводные камни (актуальная реализация)

1. **`query.mimeType` перезаписывается сервером на `text/html`** перед `invoke`. Метод должен сам установить нужный `mimeType` (например, `application/json`), если возвращает `byte[]`. Работает, потому что `sendHtml` читает `mimeType` после `invoke`.

2. **`query.sendHtml` жёстко отправляет HTTP-200.** HTTP-статусы 4xx/5xx не поддерживаются — только 200. Код ошибки придётся возвращать в теле (`{"status":"error"}`, `{"error":"..."}`) и разбирать на клиенте.

3. **`byte[]` → `new String(byte[])` → `sendHtml(String)` → `content.getBytes()`.** Тело проходит через строку (default charset). На UTF-8-системах это работает без потерь, на других может быть искажение. Для 100% контроля используйте `query.sendHtmlMime(bytes, mime)` или `query.send(bytes)` и возвращайте `null`.

4. **Возврат `String`/`JSONObject` из метода — не работает.** Только `byte[]` или `null`. Иначе `ClassCastException`.

5. **Метод должен иметь ровно один параметр `HttpExchange`.** Метод с 0/2+ параметрами попадает в `methods` (по одному из параметров), но `invoke` упадёт с `IllegalArgumentException`.

6. **Имя класса = имя файла без `.java`.** Иначе `ClassNotFoundException`.

7. **Импорты не добавляются автоматически.** Нужен `import ru.miacomsoft.EasyWebServer.HttpExchange;` в каждом файле (в отличие от `<cmpAction query_type="java">`, где шаблон добавляет `import java.util.*` и `org.json.*`).

8. **`.java` файл обрабатывается всегда, даже в DEBUG=false.** Перекомпиляция идёт по `lastModified`, а не по режиму `DEBUG`. При правке файла — перезагрузка не нужна; при изменении `LIB_JAR` или `config.ini` — нужна.

9. **Кэши `InstanceClassHash` и `InstanceClassName` — обычные `HashMap`, не thread-safe.** При массовых одновременных правках `.java` файлов может быть race condition. На практике редко проявляется, но имейте в виду.

10. **Path traversal.** `new File(WEBAPP_DIR + "/" + requestPath)` не нормализуется. Если `requestPath` содержит `../`, файл может быть прочитан за пределами `WEBAPP_DIR`. Для продакшна добавьте проверку `Path.getCanonicalPath()`.

11. **Ошибка компиляции** отдаётся как **HTML** (HTTP 200), а ошибка выполнения — как **text/plain**. Если клиент ждёт JSON, при ошибке компиляции он его не получит.

12. **`parseErrorCompile` упадёт с `JSONException`, если файл не читается** (только `ERROR`, без `src`). Редкий edge case: файл существует, но `read` не удаётся.

13. **`postCharBody` — не строка.** Для тела POST используйте `postByte`.

14. **`messageJson` заполняется автоматически** только при `Content-Type: application/json` и корректном JSON. Иначе — пустой `JSONObject`.

15. **`query.SQL` не параметризован.** Подстановка пользовательских строк = SQL-injection. Для параметризованных запросов — `PostgreQuery.executeQuery(sql, params, user, pass)` или `<cmpAction>`/`<cmpDataset>`.

---

## 16. Резюме

- **Где:** `.java` файлы в `WEBAPP_DIR`. URL = путь к файлу. Имя публичного класса = имя файла.
- **Как вызывается:** `runJavaFile` → `compileFile` → `compileMemoryMemory` → `getMethods()` → `invoke`.
- **Метод:** `public byte[] onPage(HttpExchange query)` (или `page`). Не обязательно `static` — вызывается на экземпляре.
- **MIME:** `runJavaFile` ставит `text/html`; метод может переопределить `query.mimeType` перед возвратом.
- **Возврат:** `byte[]` (уходит в `sendHtml`) или `null` (если отправка уже сделана через `query.write`/`sendByteFile`).
- **Импорты:** пишутся явно в `.java`. JAR'ы — через `LIB_JAR` в `config.ini`.
- **Ошибки:** компиляции — HTML с подсветкой; выполнения — text/plain со стектрейсом. HTTP-код всегда 200.
- **Кэш:** по `requestPath` + `lastModified` + MD5 содержимого. Перекомпиляция при правке файла — автоматически.
- **Не путать:** `{component}/Xxx`, `*.component`, `@Get/@Post` — три других механизма (см. §14).