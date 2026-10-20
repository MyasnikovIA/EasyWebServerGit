# Динамический Java-код внутри HTML (EasyWebServer)

Документ описывает **фактическую реализацию** `<cmpAction>` и `<cmpDataset>` в режиме `query_type="java"` по коду `EasyWebServer2`. Раздел «Известные ограничения» содержит отличия от распространённого варианта документации (в т.ч. от старого `DYNAMIC_JAVA.md`).

---

## 1. Два разных компонента — два разных результата

| Компонент | Клиентский вызов | Куда писать результат |
|---|---|---|
| `<cmpAction query_type="java">` | `executeAction(name, cb)` | `vars.put("<OUT имя>", ...)` |
| `<cmpDataset query_type="java">` | `refreshDataSet(name, cb)` | `data.put(row)` (и `vars.put` для OUT) |

Внутри оба компилируются в один и тот же класс `SpecialClassToCompileV3`, но:
- для `Action` результат — модифицированный `vars` (по OUT-переменным);
- для `Dataset` результат — `data` (`JSONArray`), плюс опционально `vars`.

---

## 2. Синтаксис внутри HTML

### 2.1. `<cmpAction>` — Java-действие

```html
<cmpAction name="myTestAction" query_type="java">
    String name = vars.optString("userName", "Гость");
    vars.put("greeting",   "Привет, " + name + "!");
    vars.put("serverTime", new java.util.Date().toString());

    <var name="userName"   type="string"/>
    <var name="greeting"   type="string" put="true"/>
    <var name="serverTime" type="string" put="true"/>
</cmpAction>
```

Обратите внимание:
- **`<var>` обязательны**. Без них Java-код ничего не увидит на входе и не отдаст на выходе (см. §4).
- `userName` — вход (по умолчанию `direction="IN"`).
- `greeting` и `serverTime` — выход (`put="true"`).
- В `<cmpAction>` **нет** требования писать `vars.put(...)` для входа — вход вы читаете.
- Возвращаемое значение метода `evalFunc` — это `vars`, но наружу из него забираются **только** объявленные OUT/INOUT-переменные.

### 2.2. `<cmpDataset>` — Java-датасет

```html
<cmpDataset name="getUsers" query_type="java">
    org.json.JSONArray rows = new org.json.JSONArray();
    for (int i = 1; i <= 3; i++) {
        org.json.JSONObject row = new org.json.JSONObject();
        row.put("id", i);
        row.put("name", "User " + i);
        data.put(row);
    }

    <var name="limit" type="integer"/>
</cmpDataset>
```

- Строки результата кладите в `data` (`JSONArray`) — это уйдёт клиенту как `result.data`.
- `<var>` можно не объявлять, если вход не нужен.

### 2.3. Атрибут `action` — вызов **существующей** функции БД (не Java)

```html
<cmpAction name="saveUser" action="some_db_procedure" db="default">
    <var name="inVal"  type="integer" src="inVal" srctype="ctrl"/>
    <var name="outVal" type="string"  put="true"/>
</cmpAction>
```

Если есть `action` **и** тело пустое — код не компилируется, сервер вызывает функцию БД. Для Java-режима `action` **не указывайте**.

---

## 3. Форма Java-кода (компилируется автоматически)

Сервер оборачивает ваш код в метод:

```java
import java.util.HashMap;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

public class SpecialClassToCompileV3 {
    public JSONObject evalFunc(JSONObject vars,
                               HashMap<String, Object> session,
                               JSONArray data) {
        <ваш код>;
        return vars;
    }
}
```

Следствия:

1. **Только 4 импорта по умолчанию.** `java.util.Date`, `java.sql.Connection`, `com.google.gson.Gson` и т.п. — только через полное имя (`new java.util.Date()`) либо через `<import packet="..."/>` (см. §9 — там важный подвох).
2. **`vars`** — `JSONObject` (вход/выход).
3. **`session`** — `HashMap<String, Object>`. Читайте `session.get("AUTH_USER")`, `session.get("DATABASE")` и т.п.
4. **`data`** — `JSONArray`. Для `Action` приходит пустой и наружу не идёт. Для `Dataset` — это буфер результата.
5. **Никакого `return`** — пишите в `vars`/`data`.
6. **Точка с запятой в конце последней инструкции не обязательна** — обёртка добавляет `;` после вашего кода. Но если ваш код заканчивается блоком `if (...) { ... }`, лишняя `;` не помешает (пустой оператор). А вот если код заканчивается `}` без `;` — сгенерированный `};` создаст лишний пустой statement, что нормально.

---

## 4. Объявления `<var>` — сердце механики

### 4.1. Атрибуты `<var>`

| Атрибут | По умолчанию | Значение |
|---|---|---|
| `name` | — (обязателен) | Имя в Java (`vars.getXXX("name")`) |
| `src` | = `name` | Откуда клиент берёт значение (имя контрола / переменной / ключ сессии) |
| `srctype` | `"var"` | `var` / `ctrl` / `session` |
| `type` | `"string"` | `string` / `integer` / `bigint` / `decimal` / `boolean` / `date` / `timestamp` / `json` / `array`. Для **Java** не влияет на типы — всё передаётся строками |
| `default` | `""` | Значение по умолчанию, если на клиенте ничего не нашлось |
| `len` | `""` | Для SQL-типов (в Java не влияет) |
| `put` | — | Признак OUT. Любое значение (обычно `"true"`) |
| `get` | — | Участвует только в определении INOUT |

### 4.2. Направление (`direction`)

Расчёт в `cmpAction.parseActionVar`:

```java
var.direction = (put != null && get != null) ? "INOUT"
              : (put != null ? "OUT" : "IN");
```

| `put` | `get` | direction |
|---|---|---|
| — | — | `IN` |
| — | `true` | `IN` |
| `true` | — | `OUT` |
| `true` | `true` | `INOUT` |

### 4.3. Вход (IN / INOUT)

Перед вызовом Java-метода собирается `callVars` **только** из объявленных `<var>` с направлением `IN`/`INOUT`:

```java
// EasyWebServer2: JavaActionHandler
for (cmpAction.ActionVar var : variables) {
    if ("IN".equals(var.direction) || "INOUT".equals(var.direction)) {
        String value = getValueFromVars(vars, session, var.src, var.defaultVal);
        callVars.put(var.name, value);
    }
}
```

**Что это значит для вас:**
- Ключи в `vars` внутри Java = `var.name`.
- **Значение всегда строка.** `<var type="integer"/>` в Java всё равно будет `"123"`, парсите через `Integer.parseInt`.
- Ключа, который не объявлен в `<var>`, в `vars` **не будет** — даже если клиент что-то отправил.
- Если `var.src != var.name`, см. §11 — есть нюанс.

### 4.4. Выход (OUT / INOUT)

После вызова Java-метода сервер смотрит на **вернувшийся** `vars` (тот, что был `callVars`, возможно изменённый) и для каждой OUT/INOUT-переменной:

```java
if (res.has(var.name)) {
    updateVars(vars, session, var.src, res.get(var.name).toString(), var.srctype);
}
```

**Что это значит:**
- Чтобы что-то вернуть клиенту, нужен `<var name="x" put="true"/>` **и** `vars.put("x", value)` в Java.
- Ключ в `res` = `var.name`. Значение приводится к строке (`toString()`).
- Наружу уходит под `var.src` (в `updateVars`), плюс сохраняется в `session`, если `srctype="session"`.
- Всё, что вы положили в `vars`, но **не** объявили как `put="true"` — **молча отбрасывается**.

### 4.5. Серверный пример (что видит клиент)

HTML:
```html
<cmpAction name="saveUser" query_type="java">
    String login = vars.optString("login", "");
    vars.put("status", login.isEmpty() ? "empty" : "ok");
    <var name="login"  type="string"/>
    <var name="status" type="string" put="true"/>
</cmpAction>
```

Клиент:
```javascript
executeAction('saveUser', function(res) {
    console.log(res.vars.status);   // "ok" или "empty"
    console.log(res.vars.login);    // исходное значение (эхо)
});
```

---

## 5. Клиентская сторона

### 5.1. `executeAction(name, callback)`

```javascript
// cmpAction_js.java
window.executeAction = function(nameAction, callBack) { ... }
```

- **Нет аргумента `data`.** Всё, что нужно на входе, компонент читает по объявленным `<var>`:
    - `srctype="var"` → `D3Api.getVar(src)`
    - `srctype="ctrl"` → `D3Api.getValue(src)`
    - `srctype="session"` → `D3Api.getSession(src)`
- Значения **всегда** отправляются строками (`String(value)`).
- Ответ: `callback(dataObj.vars)` (только при `res.ERROR == null`; иначе `callback({error: dataObj.ERROR, vars: dataObj.vars})`).
- Глобально ответ дублируется в `D3Api.GLOBAL_ACTION[nameAction]`.

### 5.2. `refreshDataSet(name, callback)`

```javascript
// cmpDataset_js.java
window.refreshDataSet = function(nameDataset, callBack) { ... }
```

- Тоже без аргумента `data`.
- Ответ: `callback(dataObj.data)` — массив строк.
- Кэш: `D3Api.GLOBAL_DATA_SET[nameDataset].data`.

### 5.3. Возврат к контролам после OUT

Если `<var srctype="ctrl" src="inpStatus" put="true"/>` — после успешного вызова `D3Api.setValue("inpStatus", value)`. Для `srctype="var"` — `D3Api.setVar`. Для `srctype="session"` — `D3Api.setSession`.

---

## 6. Формат ответа сервера

Успешный `cmpAction`:

```json
{
  "vars": {
    "login":  { "src":"login",  "srctype":"var", "value":"ivan", "defaultVal":"" },
    "status": { "src":"status", "srctype":"var", "value":"ok",   "defaultVal":"" }
  }
}
```

`vars` содержит **все исходные ключи** (даже те, что не объявлены в `<var>`) плюс записи, добавленные `updateVars`.

Успешный `cmpDataset`:

```json
{
  "data": [ { "id":1, "name":"User 1" }, { "id":2, "name":"User 2" } ],
  "vars": { "limit": { "src":"limit", "srctype":"var", "value":"10" } }
}
```

Ответ при ошибке:

```json
{ "ERROR": "Compilation failed: ...", "vars": { ... } }
```

- `ERROR` — либо строка, либо массив строк.
- HTTP-код ответа **всегда 200**.

---

## 7. Работа с сессией

Внутри Java `session` — `HashMap<String, Object>`:

```java
Object user = session.get("AUTH_USER");
if (user == null) {
    vars.put("status", "unauthorized");
} else {
    vars.put("status", "ok");
    vars.put("user", user.toString());
}
```

Пример (проверка соединения с БД):

```java
Object dbObj = session.get("DATABASE");
if (dbObj instanceof java.util.Map) {
    java.util.Map<?, ?> db = (java.util.Map<?, ?>) dbObj;
    Object conn = db.get("CONNECT");
    vars.put("dbConnected", String.valueOf(conn != null));
}
```

Выходные переменные с `srctype="session"` пишутся в сессию **автоматически**:

```html
<cmpAction name="login">
    vars.put("token", "abc123");
    <var name="token" srctype="session" put="true"/>
</cmpAction>
```

После вызова `session.get("token") == "abc123"` на сервере.

---

## 8. Доступ к БД

Есть три пути:

### 8.1. Через сессию (пользовательское соединение)

```java
Object dbObj = session.get("DATABASE");
if (dbObj instanceof java.util.Map) {
    java.util.Map<?, ?> db = (java.util.Map<?, ?>) dbObj;
    java.sql.Connection conn = (java.sql.Connection) db.get("CONNECT");
    if (conn != null) {
        try (java.sql.Statement st = conn.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SELECT id, name FROM users")) {
            while (rs.next()) {
                org.json.JSONObject row = new org.json.JSONObject();
                row.put("id", rs.getInt("id"));
                row.put("name", rs.getString("name"));
                data.put(row);
            }
        }
    }
}
```

### 8.2. Системное соединение

```java
java.sql.Connection conn = ru.miacomsoft.EasyWebServer.PostgreQuery.getConnect(
        ru.miacomsoft.EasyWebServer.ServerConstant.config.DATABASE_USER_NAME,
        ru.miacomsoft.EasyWebServer.ServerConstant.config.DATABASE_USER_PASS);
```

### 8.3. Параметризованные запросы (рекомендуется)

```java
java.util.Map<String, Object> p = new java.util.HashMap<>();
p.put("name", vars.optString("login"));
org.json.JSONArray rows = ru.miacomsoft.EasyWebServer.PostgreQuery.executeQuery(
    "SELECT id FROM users WHERE name = :name", p,
    ru.miacomsoft.EasyWebServer.ServerConstant.config.DATABASE_USER_NAME,
    ru.miacomsoft.EasyWebServer.ServerConstant.config.DATABASE_USER_PASS);
```

---

## 9. Импорты и JAR-библиотеки — осторожно

### 9.1. Полное имя класса — самый надёжный путь

```java
java.util.Date d = new java.util.Date();
com.google.gson.Gson g = new com.google.gson.Gson();
```

### 9.2. `<import path="..."/>` — добавление JAR в classpath

```html
<cmpAction name="myAction" query_type="java">
    <import path="/opt/EasyWebServer/lib/gson-2.10.1.jar"/>
    com.google.gson.Gson g = new com.google.gson.Gson();
    vars.put("out", g.toJson("Hello"));
    <var name="out" type="string" put="true"/>
</cmpAction>
```

**Как это реально работает.** `compileMemoryMemory` идёт по `ServerConstant.config.LIB_JAR`:

```java
for (String libJaR : ServerConstant.config.LIB_JAR) {
    if (libJaR.indexOf(File.separator) == -1) {
        jarResourse.add(libJaR);
    } else {
        jarResourse.add(ServerConstant.config.LIB_DIR + File.separator + libJaR);
    }
}
```

- Если путь **не содержит** разделитель (`/` или `\`) — берётся как есть (например, `json-20230227.jar` → `new File("json-20230227.jar")`).
- Если **содержит** — приклеивается `LIB_DIR` в начало (например, `/opt/lib/gson.jar` → `LIB_DIR/opt/lib/gson.jar` — почти наверняка несуществующий путь).

**Практический вывод:** прописывайте JAR'ы в `LIB_JAR` как **относительные имена** и кладите их в `LIB_DIR`:

```json
"LIB_DIR": "/opt/EasyWebServer/lib",
"LIB_JAR": [
    "gson-2.10.1.jar",
    "json-20230227.jar",
    "postgresql-42.2.24.jar"
]
```

Тогда `LIB_DIR/gson-2.10.1.jar` попадёт в `-classpath` компилятора.

Альтернатива — **вынести JAR'ы в classpath JVM** (`java -cp app.jar:lib/* ...`) — работает без `LIB_JAR`.

### 9.3. `<import packet="..."/>` — **не работает**

Проверено по коду `JavaStrExecut.compile`:

```java
StringBuffer importPacketString = new StringBuffer();
for (String key : importSet) {
    importPacketString.append("\nimport ");
    importPacketString.append(key);
    importPacketString.append(";");
}
String src = "" +
    "import java.util.HashMap; \n" +
    "import java.util.ArrayList; \n" +
    "import org.json.JSONArray; \n" +
    "import org.json.JSONObject; \n" +
    "\npublic class SpecialClassToCompileV3 { \n" ...
```

`importPacketString` собирается, но **никогда не попадает в `src`**. То есть `<import packet="com.example.Foo"/>` игнорируется. Пишите полное имя класса.

---

## 10. Компиляция, кэш и перезапуск

### 10.1. Когда происходит компиляция

Компиляция **ленивая**, при **первом вызове** `executeAction`/`refreshDataSet` (не при рендере страницы). В `cmpAction.initialize` / `cmpDataset.initialize` в кэш кладётся только конфиг с текстом Java-кода.

Плюс: страница рендерится быстро, даже с десятком `<cmpAction query_type="java">`. Ошибка компиляции **не видна** при загрузке страницы — она появляется при первом вызове.

### 10.2. Ключ кэша

`JavaStrExecut.InstanceClassName` — по ключу `ServerConstant.config.APP_NAME + "_" + functionName`. Для `Action` `functionName = cache.name`. Для `Dataset` — `"ds_" + datasetName + "_" + contentHash`.

**Хэш содержимого** (`getShortHash(javaCode)`) — для Dataset. Для Action ключ — только имя; изменили код в `<cmpAction>` → перезапуск сервера или смена имени.

### 10.3. Режим отладки

В `JavaActionHandler.isCompiled`:

```java
boolean needsCompilation = !isCompiled(fullFunctionName, contentHash);
```

`contentHash` для Action **не используется** (сравнивается только наличие класса). То есть **перекомпиляции при правке `<cmpAction>` не будет** — только при перезапуске сервера или новом имени.

Для Dataset — есть `contentHash`:

```java
boolean needsCompilation = debugMode || !isCompiled(functionName, contentHash);
```

`debugMode` берётся из `session.get("debug_mode")` (флаг включается через `?debug=1`). В `debugMode` перекомпиляция — **каждый раз**.

**Вывод:** для отладки `<cmpAction query_type="java">` используйте `?debug=1` (или перезапуск).

---

## 11. Известные ограничения и подводные камни

1. **OUT-переменные молча теряются.** Если в Java `vars.put("x", ...)`, но `<var name="x" put="true"/>` не объявлен — клиент не увидит `x`. Всегда декларируйте OUT.

2. **`var.src != var.name` → путаница в `Action`.** `getValueFromVars(vars, session, var.src, ...)` ищет по `src` в клиентском `vars`, но клиент присылает под `name`. При `src != name` вход не найдётся (используется `default`). Для `Action` используйте `src == name` (или вообще не указывайте `src`). В `Dataset` — `getValueFromVars(vars, session, var.name, ...)`, там всё согласовано.

3. **Все входные значения — строки.** `<var type="integer"/>` не преобразует. Парсите вручную:
   ```java
   int limit = 10;
   try { limit = Integer.parseInt(vars.optString("limit", "10")); } catch (Exception ignore) {}
   ```

4. **`<import packet="..."/>` не работает** (см. §9.3). Полное имя класса — ваш друг.

5. **`LIB_JAR` требует `LIB_DIR` + имя без разделителя.** Абсолютный путь с `/` даст неверный `-classpath` (см. §9.2). Либо используйте `-cp` в команде запуска.

6. **Клиентский API без `data`.** `executeAction('name', cb)` — никакого второго аргумента с данными. Все параметры — через `<var>` + `D3Api.setVar`/`setValue`/`setSession` **до** вызова.

7. **`Action` не перекомпилируется при правке кода.** Ни в `DEBUG=true`, ни без. Перезапустите сервер или используйте `Dataset` (у него хэш-базированный ключ + `debugMode`).

8. **Всё, что возвращает Java, приводится к строке.** `vars.put("count", 42)` → на клиенте `res.vars.count.value === "42"` (строка). При необходимости парсите `parseInt` на клиенте.

9. **HTTP-код ответа всегда 200**, даже при `ERROR`. Клиент должен проверять `dataObj.ERROR`, а не `response.status`.

10. **`session` для `Action` — `HashMap<String, Object>`, не `JSONObject`.** `session.get("x")` вернёт `Object` (обычно `String` или `JSONObject`). Приводите тип.

11. **Первый вызов = компиляция.** Первый `executeAction` может занять сотни миллисекунд (компилятор `javax.tools`). Прогрейте вызовом до реальной работы — если время отклика критично.

12. **Ошибка компиляции — не HTML, а строка.** Приходит в `dataObj.ERROR` (поле `result.ERROR`). Полные диагностики (с указанием строки и подсветкой) — **нет** в пути `executeJavaAction`. Просто `"Compilation failed: " + infoCompile.toString()` (JSON dump). Для `runJavaFile` (`DYNAMIC_PAGES.md`) — есть `parseErrorCompile` с HTML, но это другой механизм (`.java` файлы в `www`, а не `<cmpAction>`).

13. **`System.out.println` внутри Java-кода** идёт в stdout сервера. Полезно для отладки, но не заменяет `result.ERROR`.

14. **Порядок var-меток не важен**, но клиентский `executeAction` собирает `requestData` именно из `vars`-атрибута, сгенерированного при рендере страницы. Если страница закэширована (а она кэшируется в `ServerResourceHandler.resources`), изменения в `<var>` подхватятся только при перезагрузке страницы.

15. **`<var>` не поддерживает `type` в `Action` для Java**, хотя `type` парсится. Он нужен только для SQL-режима (`cmpAction query_type="sql"`).

---

## 12. Минимальный рабочий пример

### 12.1. Action

HTML (`page.html`):
```html
<cmpEdit name="inpLogin" placeholder="Логин"/>

<cmpAction name="checkLogin" query_type="java">
    String login = vars.optString("login", "");
    String status;
    if (login.isEmpty())         status = "empty";
    else if (login.length() < 3) status = "short";
    else                          status = "ok";
    vars.put("status", status);

    <var name="login"  type="string"/>
    <var name="status" type="string" put="true"/>
</cmpAction>

<cmpButton name="btnCheck" caption="Проверить"
    onclick="executeAction('checkLogin', function(res){
        if (res.error) { alert('Error: ' + res.error); return; }
        alert('Статус: ' + res.vars.status.value);
    });"/>
```

### 12.2. Dataset

HTML:
```html
<cmpDataset name="listUsers" query_type="java">
    org.json.JSONArray rows = new org.json.JSONArray();
    for (int i = 1; i <= 5; i++) {
        org.json.JSONObject row = new org.json.JSONObject();
        row.put("id",   i);
        row.put("name", "User " + i);
        rows.put(row);
    }
    for (int i = 0; i < rows.length(); i++) {
        data.put(rows.get(i));
    }

    <var name="limit" type="integer" default="5"/>
</cmpDataset>

<cmpButton name="btnList" caption="Загрузить"
    onclick="refreshDataSet('listUsers', function(rows){
        console.log(rows);   // массив {id, name}
    });"/>
```

---

## 13. Резюме

- **`<cmpAction query_type="java">`** и **`<cmpDataset query_type="java">`** — два режима одного и того же компилятора. Первый возвращает `vars`, второй — `data`.
- **`<var>` обязательны** для входа (IN/INOUT) и выхода (OUT/INOUT). Без `put="true"` результат из Java не уйдёт клиенту.
- **Java-сигнатура** — `(JSONObject vars, HashMap<String,Object> session, JSONArray data)`. Только 4 импорта по умолчанию, остальное — полностью квалифицированными именами.
- **Клиентский API** — `executeAction(name, cb)` / `refreshDataSet(name, cb)`. **Нет аргумента `data`**; вход собирается из объявленных `<var>` (через `D3Api.getVar/getValue/getSession`).
- **Всё строковое**. Парсить числа/даты — вручную.
- **`<import packet="..."/>` не работает**, `<import path="..."/>` и `LIB_JAR` работают с оговорками (§9.2).
- **`Action` не перекомпилируется при правке** — перезапуск сервера или переименование. `Dataset` перекомпилируется по `contentHash` + в `debugMode` (`?debug=1`).
- **HTTP-код всегда 200.** Ошибки — в `result.ERROR`, проверяйте на клиенте.