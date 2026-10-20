# Создание компонентов в EasyWebServer

Компонентная система `EasyWebServer` позволяет писать HTML, в котором теги `<cmpXXX>` автоматически заменяются серверным Java-кодом перед отправкой клиенту. Это близко к JSP-тегам и Thymeleaf-фрагментам, но с прямым доступом к БД и Java из HTML.

---

## 1. Как это работает под капотом

1. Сервер находит HTML-файл (например, `index.html`).
2. `Jsoup` парсит файл в режиме HTML — **имена тегов переводятся в нижний регистр** (`<cmpButton>` → `cmpbutton`).
3. `ServerResourceHandler.parseElementV2(doc, element, parent)` рекурсивно обходит всё дерево DOM.
4. Для каждого элемента проверяется:
   ```java
   String tagName = element.tag().getName();          // например "cmpbutton"
   if (!tagName.equals("cmpform") && tagName.length() > 3) {
       if (tagName.substring(0, 3).equals("cmp")) {
           String classNameCmp = COMPONENT_PATH
               + ".cmp"
               + tagName.substring(3, 4).toUpperCase()
               + tagName.substring(4);
           // cmpbutton → cmpButton
   ```
5. Класс компонента кэшируется в `ServerResource.componentListClass` и находится через `Class.forName(...)`.
6. Создаётся экземпляр через рефлексию: **обязателен трёхаргументный конструктор** `(Document doc, Element element, String tag)`.
7. Новый объект (наследник `Element`) заменяет исходный тег: `element.replaceWith((Element) newClassObject)`.
8. Если класс не найден или упал конструктор — на месте тега будет `<error>…</error>`.
9. Готовый HTML уходит клиенту.

**Важно:**
- Тег `<cmpform>` (в нижнем регистре) **исключён** из автозамены — класс `cmpForm` существует, но через общий механизм не подключается.
- Все имена тегов в Jsoup нормализуются в нижний регистр, поэтому `<cmpButton>` и `<cmpbutton>` обрабатываются одинаково.
- Двухаргументные конструкторы `(Document, Element)` **не используются** `parseElementV2` — оставлены только для обратной совместимости.

---

## 2. Структура проекта

- **Пакет компонентов:** по умолчанию `ru.miacomsoft.EasyWebServer.component`. Путь можно переопределить через `COMPONENT_PATH` в `config.ini`.
- **Базовый класс:** `ru.miacomsoft.EasyWebServer.component.Base` (наследник `org.jsoup.nodes.Element`).
- **Рядом с Java-классами компонентов размещаются классы-провайдеры** JS/CSS с суффиксом `_js` / `_css` (см. §4).

---

## 3. Базовый класс `Base` — что даёт

```java
public class Base extends Element {
    public String name   = "";
    public String id     = "";
    public String cmptype = "base";

    public Base(Document doc, Element element, String tag) { ... }

    // Утилиты
    public static String genUUID();                                     // UUID без дефисов
    public void initCmpType(Element element);                           // проставить cmptype="Button" и т.п.
    public void initCmpId(Element element);                             // сгенерировать id если нет
    public String RemoveArrKeyRtrn(Attributes arr, String key);         // взять+удалить (default "")
    public String RemoveArrKeyRtrn(Attributes arr, String key, String defaultValue);
    public String getDomAttrRemove(String name, Attributes attrs);      // " name=\"value\""
    public String getDomAttrRemove(String name, String value, Attributes attrs);
    public String getAttrRemove(String name, String value, Attributes attrs);
    public void copyEventRemove(Attributes src, Attributes dst, boolean remove);
    public void copyEventRemove(Attributes src, Attributes dst, boolean remove, String prefix);
    public String getJQueryEventString(String ctrlName, Attributes src, boolean removekey);
    public String getNotEventString(Attributes src, boolean removekey);
    public static String getContentHash(Element element);               // MD5 от element.text()
    public static String getMd5Hash(String input);
    public static String getShortHash(String input);                    // первые 8 символов MD5
}
```

**Автоподключение `LIB_CSS` / `LIB_JS` из конфига.** В конструкторе `Base` есть проверка:

```java
if (doc.select("[cmp=\"common\"]").toString().length() == 0) {
    Elements head = doc.getElementsByTag("head");
    for (String cssPath : ServerConstant.config.LIB_CSS)
        head.append("<link cmp=\"common\" href=\"" + cssPath + "\" .../>");
    for (String jsPath : ServerConstant.config.LIB_JS)
        head.append("<script cmp=\"common\" src=\"" + jsPath + "\"></script>");
}
```

То есть **при первом же компоненте на странице** в `<head>` автоматически добавляются все `LIB_CSS` и `LIB_JS` из `config.ini`. Маркер `cmp="common"` предотвращает повторное добавление.

---

## 4. Классы-провайдеры JS/CSS: как это устроено

Кроме компонентов-тегов существуют классы, которые отдают статику (JS или CSS) по URL `{component}/ИмяКласса`. Это обычные Java-классы с **статическим** методом:

```java
public static byte[] onPage(HttpExchange query)
```

Диспетчеризация — `JavaStrExecut.runJavaServerlet`:

```java
String classNameCmp = JavaStrExecut.class.getPackage().getName()
        + ".component." + query.requestPath.split("component}/")[1];
Class<?> cl = Class.forName(classNameCmp);
Method m = cl.getMethod("onPage", HttpExchange.class);
byte[] res = (byte[]) m.invoke(null, query);
```

**Соглашение об именах:** любой класс в пакете `...component` с публичным статическим `onPage(HttpExchange)` доступен по `{component}/ИмяКласса`.

Типичный провайдер:

```java
public class md5 {
    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/javascript";
        return "var MD5 = function(d) { ... };".getBytes();
    }
}
```

Также компонентные JS/CSS-файлы обычно **сами себя подключают** из конструктора своего компонента-тега, добавляя `<script>`/`<link>` в `<head>` с собственным маркером:

```java
Elements head = doc.getElementsByTag("head");
if (head.select("link[href*='cmpButton_css']").isEmpty()) {
    head.append("<link rel=\"stylesheet\" cmp=\"button-css\" "
              + "href=\"{component}/cmpButton_css\">");
}
if (head.select("script[src*='cmpButton_js']").isEmpty()) {
    head.append("<script cmp=\"button-lib\" "
              + "src=\"{component}/cmpButton_js\"></script>");
}
```

---

## 5. Встроенные компоненты-теги

| Тег | Java-класс | Что делает |
|---|---|---|
| `<cmpButton>` | `cmpButton` | Создаёт `<div cmptype="Button">` с caption/icon/onclick/popupmenu |
| `<cmpEdit>` | `cmpEdit` | Создаёт `<div cmptype="Edit">` c `<input>` (маска, формат, trim, readonly) |
| `<cmpDataset>` | `cmpDataset` | Загрузка данных (SQL / Java / вызов БД-функции) |
| `<cmpAction>` | `cmpAction` | Выполнение действия (SQL-процедура / Java / вызов БД-процедуры) |
| `<cmpScript>` | `cmpScript` | Скрытый контейнер клиентского JS (исполняется через `cmpScript_js`) |
| `<cmpDependences>` | `cmpDependences` | Управление зависимостями (required → depend) на клиенте |
| `<cmpForm>` | `cmpForm` | Обёртка формы; **не подключается** через `parseElementV2` |

### 5.1. `<cmpButton>`

Клиент: `<div cmptype="Button" ...>`, **не** `<button>`.

Атрибуты:

| Атрибут | По умолчанию | Описание |
|---|---|---|
| `name` | UUID | Имя |
| `caption` | `"Кнопка"` | Текст |
| `onclick` | `""` | JS-обработчик |
| `disabled` | — | Отключить |
| `title` | `""` | Подсказка |
| `tabindex` | `"0"` | Tabindex |
| `style` | `""` | Inline-стили |
| `class` | `ctrl_button box-sizing-force` | CSS-классы |
| `icon` | `""` | URL иконки |
| `onlyicon` | — | Без текста |
| `popupmenu` | `""` | Имя popup-меню |
| `theme` | `default` | `default` / `primary` / `tfoms` |
| `nominwidth` | — | Убрать `min-width` |

Компонент сам подключает `{component}/cmpButton_css` и `{component}/cmpButton_js`.

Пример:
```html
<cmpButton name="btnSave" caption="Сохранить" theme="primary"
           onclick="executeAction('saveUser');"/>
```

### 5.2. `<cmpEdit>`

Клиент: `<div cmptype="Edit"><input .../></div>`.

Атрибуты:

| Атрибут | По умолчанию | Описание |
|---|---|---|
| `name` | UUID | Имя |
| `value` | `""` | Начальное значение |
| `placeholder` | `""` | Placeholder |
| `maxlength` | `""` | Максимальная длина |
| `type` | `text` | Тип input |
| `format` | `""` | JSON-описание форматирования (number/date/hours) |
| `mask_type` | `""` | Тип маски |
| `disabled` / `readonly` | — | Стандартные состояния |
| `trim` | `false` | Обрезать пробелы |
| `onchange` / `onformat` | `""` | Обработчики |
| `style` | `""` | Доп. стили |
| `class` | `ctrl_edit editControl` | CSS-классы |
| `theme` | `default` | `default` / `modern` / `compact` / `material` / `dark` |
| `width` | `auto` | `auto` / `full` / `100%` / `200px` |

Подключает `{component}/cmpEdit_css` и `{component}/cmpEdit_js`.

Пример:
```html
<cmpEdit name="inpName" placeholder="Имя" theme="modern" width="full"
         format='{"toType":"number","locales":"ru-RU","options":{}}'/>
```

### 5.3. `<cmpDataset>` — загрузка данных

Компонент рендерится как скрытая `<textarea cmptype="Dataset">`. JS-API: `refreshDataSet(name, callback)`, `D3Api.GLOBAL_DATA_SET[name].data`.

**Режим 1. SQL-запрос (по умолчанию)**

```html
<cmpDataset name="userList" query_type="sql" db="default" schema="public">
    SELECT id, name FROM users ORDER BY name
</cmpDataset>
```

- Функция в БД создаётся **лениво** при первом HTTP-вызове `{component}/cmpDataset?dataset_name=userList`.
- Имя функции: `ds_<name>_<contentHash>` в схеме `public`.
- Кэш — в `PostgreDatasetHandler.procedureList`.

**Режим 2. Java**

```html
<cmpDataset name="userList" query_type="java">
    <import packet="java.util.HashMap"/>
    JSONObject row = new JSONObject();
    row.put("id", 1);
    row.put("name", "Test");
    data.put(row);
</cmpDataset>
```

Компилируется в первом вызове через `JavaStrExecut`; результат — в `data` (`JSONArray`).

**Режим 3. Вызов существующей функции БД через `action`**

```html
<cmpDataset name="userList" action="get_users" db="default" schema="public">
    <var name="filter" src="inpFilter" srctype="ctrl" type="string"/>
</cmpDataset>
```

Функция `schema.get_users(filter VARCHAR)` должна уже существовать. Атрибут `action` + пустое тело тега = вызов без создания.

**Дочерние `<var>`**

| Атрибут | Описание |
|---|---|
| `name` | Имя параметра (обязательно) |
| `src` | Откуда брать значение (по умолчанию = `name`) |
| `srctype` | `var` / `ctrl` / `session` |
| `type` | `string` / `integer` / `bigint` / `decimal` / `boolean` / `date` / `timestamp` / `json` / `array` |
| `default` | Значение по умолчанию |
| `len` | Длина для `VARCHAR` |
| `put` / `get` | Направление OUT/INOUT (для `<cmpAction>`, для датасетов обычно только IN) |

**Клиент:**

```javascript
refreshDataSet('userList', function(data) {
    console.log(data);
});

// кэш
D3Api.GLOBAL_DATA_SET['userList'].data;
```

### 5.4. `<cmpAction>` — выполнение действия

Скрытая `<textarea cmptype="Action">`. Выполняется через `executeAction(name, callback)`.

Атрибуты: те же, что у `<cmpDataset>` (`query_type`, `db`, `schema`, `db_type`, `action`).

**Режим 1. SQL-процедура**

```html
<cmpAction name="saveUser" query_type="sql" db="default" schema="public">
    UPDATE users SET name = inpName WHERE id = userId;
    <var name="userId" type="integer" src="userId" srctype="var"/>
    <var name="inpName" type="string" len="100" src="inpName" srctype="ctrl"/>
</cmpAction>
```

- Процедура создаётся лениво в первом вызове `{component}/cmpAction?action_name=saveUser`.
- Имя процедуры: `name` (в схеме), параметры — все `INOUT`.
- Кэш — в `PostgreActionHandler.procedureList`.

**Режим 2. Java**

```html
<cmpAction name="myAction" query_type="java">
    <import packet="org.json.JSONObject"/>
    vars.put("result", "OK");
    <var name="result" type="string"/>
</cmpAction>
```

**Режим 3. Вызов существующей процедуры БД**

```html
<cmpAction name="doSomething" action="some_procedure" db="default" schema="public">
    <var name="inVal"  type="integer" src="inVal" srctype="ctrl"/>
    <var name="outVal" type="string"  put="true"/>
</cmpAction>
```

**Клиент:**

```javascript
executeAction('saveUser', function(vars) {
    // vars — обновлённые выходные параметры
    console.log(vars);
});
```

### 5.5. `<cmpScript>` — клиентский JS

Тег **не выполняет Java на сервере**. Он копирует свой текст в скрытую `<textarea cmptype="Script">`; затем `cmpScript_js` на клиенте берёт содержимое и выполняет через `new Function(...)`.

```html
<cmpScript name="initMyModule">
    // это обычный JavaScript, выполнится на клиенте
    D3Api.setVar('hello', 'world');
    console.log('cmpScript executed');
</cmpScript>
```

JS-API: `D3Api.loadScript(name, src)`, `D3Api.executeScript(name, content)`, `D3Api.getScriptStatus(name)`, `D3Api.waitForScript(name)`.

### 5.6. `<cmpDependences>`

Невидимый `<div cmptype="Dependences" style="display:none">`, управляет активностью полей на клиенте.

| Атрибут | Описание |
|---|---|
| `name` | Имя зависимости |
| `required` | `;`-список обязательных полей |
| `depend` | `;`-список полей, которые надо отключить до заполнения |
| `checkMode` | `all` (все) или `any` (хотя бы одно) |
| `validateMask` | `true`/`false` — учитывать ли маску |
| `autoInit` | `true`/`false` |

```html
<cmpDependences name="formGuard"
    required="inpName;inpEmail"
    depend="btnSave;btnSaveAndNext"
    checkMode="all"
    validateMask="true"/>
```

JS-API: `D3Api.createDependence`, `D3Api.updateDependence`, `D3Api.enableDependence`, `D3Api.addRequiredFields`, `D3Api.addDependFields`, `D3Api.getDependenceState`, `D3Api.removeDependence`.

### 5.7. `<cmpForm>` — обёртка формы

Класс `cmpForm` существует, но `<cmpForm>` через `parseElementV2` **не подключается** (`tagName.equals("cmpform")` — исключение). Используется, если инстанцируется вручную.

Логика: переносит содержимое в `<div cmptype="Form">` и пытается извлечь имя формы из комментария:

```html
<!-- Forms/Test/ComboBoxD3.frm -->
```

---

## 6. Встроенные JS/CSS-провайдеры

Все доступны как `/{component}/ИмяКласса`:

| Класс | MIME | Назначение |
|---|---|---|
| `main_js` | JS | Ядро `D3Api` (переменные, сессия, values, onchange, форматирование, скрипты) |
| `d3api_js` | JS | DOM-расширения `D3Api` (getEvent, stopEvent, addClass, getAbsolutePos, …) |
| `cmpBase_js` | JS | `D3Api.BaseCtrl`, `D3Api.ControlBaseProperties`, `D3Api.D3Base` |
| `cmpButton_js` / `cmpButton_css` | JS / CSS | Кнопка |
| `cmpEdit_js` / `cmpEdit_css` | JS / CSS | Поле ввода |
| `cmpDataset_js` | JS | `refreshDataSet`, кэш датасетов |
| `cmpAction_js` | JS | `executeAction` |
| `cmpScript_js` / `cmpScript_css` | JS / CSS | Клиентские скрипты |
| `cmpWindow_js` / `cmpWindow_css` | JS / CSS | `D3Api.openD3Form(name, modal\|domParent, data)` — модальные/полноэкранные окна с iframe |
| `md5` | JS | Функция `MD5(str)` |
| `session` | JS(JSON) | `GET / POST {component}/session` — сохранение в серверной сессии |
| `sessionDirect` | JSON | `set_direct` / `get_direct` — сохранение URL для возврата |
| `loginDataBase` | JSON | Аутентификация в PostgreSQL |

`main_js` при инициализации **сам подгружает** `d3api_js`, а затем — `cmpBase_js` (ожидание через `waitForD3Api`/`requestAnimationFrame`). Остальные модули по тому же паттерну.

Пример подключения в `config.ini` (фрагмент `LIB_JS`):

```json
"LIB_JS": [
    "/System/jquery/1.9.0/jquery.js",
    "/System/jqueryui/1.13.2(Samoothnes)/jquery-ui.min.js",
    "/System/jquery_layout/1.4.0/jquery.layout.1.4.4.js",
    "/System/main.js",
    "/{component}/md5"
]
```

`/System/main.js`, в свою очередь, содержит код, который последовательно подгружает `main_js → d3api_js → cmpBase_js` (или эквивалент через `D3Api.loadExtensions()`).

---

## 7. Создание своего компонента

### Шаг 1. Java-класс `cmpUserCard.java`

```java
package ru.miacomsoft.EasyWebServer.component;

import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class cmpUserCard extends Base {

    public cmpUserCard(Document doc, Element element, String tag) {
        super(doc, element, tag);           // tag = "cmpusercard"

        Attributes attrs = element.attributes();
        String name    = RemoveArrKeyRtrn(attrs, "name", genUUID());
        String avatar  = RemoveArrKeyRtrn(attrs, "avatar", "");
        String role    = RemoveArrKeyRtrn(attrs, "role", "Пользователь");
        String caption = RemoveArrKeyRtrn(attrs, "caption", "");

        this.attr("name", name);
        this.attr("cmptype", "UserCard");
        this.addClass("user-card");

        // Собираем вложенный HTML
        Element img = new Element("img").attr("src", avatar).attr("alt", "");
        Element h3  = new Element("h3").text(caption);
        Element p   = new Element("p").text(role);
        this.appendChild(img);
        this.appendChild(h3);
        this.appendChild(p);

        // Подключаем собственный CSS
        Elements head = doc.getElementsByTag("head");
        if (head.select("link[href*='cmpUserCard_css']").isEmpty()) {
            head.append("<link rel=\"stylesheet\" cmp=\"usercard-css\" "
                      + "href=\"{component}/cmpUserCard_css\">");
        }

        // Удаляем служебные атрибуты
        attrs.remove("name");
        attrs.remove("avatar");
        attrs.remove("role");
        attrs.remove("caption");
    }
}
```

### Шаг 2. Провайдер стилей `cmpUserCard_css.java`

```java
package ru.miacomsoft.EasyWebServer.component;

import ru.miacomsoft.EasyWebServer.HttpExchange;

public class cmpUserCard_css {
    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "text/css";
        String css = """
            .user-card { border:1px solid #ddd; border-radius:6px;
                         padding:12px; display:inline-block; min-width:180px; }
            .user-card img { width:48px; height:48px; border-radius:50%; }
            .user-card h3 { margin:6px 0 2px; font-size:16px; }
            .user-card p  { margin:0; color:#666; font-size:13px; }
            """;
        return css.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
```

### Шаг 3. Использование

```html
<!DOCTYPE html>
<html>
<head><meta charset="utf-8"><title>Карточки</title></head>
<body>
    <cmpUserCard name="u1" caption="Иван Иванов"
                 avatar="/img/a1.png" role="Администратор"/>
    <cmpUserCard name="u2" caption="Пётр Петров"
                 avatar="/img/a2.png" role="Пользователь"/>
</body>
</html>
```

При загрузке страницы сервер заменит оба `<cmpUserCard>` на готовые `<div class="user-card">`.

---

## 8. Обработка HTTP-запросов от компонента

Если компоненту нужен серверный endpoint (как у `cmpDataset`, `cmpAction`), добавьте статический `onPage(HttpExchange query)` **прямо в класс компонента**:

```java
public class cmpUserCard extends Base {
    ...
    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/json";
        // ...
        return new JSONObject().put("ok", true).toString().getBytes();
    }
}
```

URL будет `/{component}/cmpUserCard?...` — диспетчеризация идёт через `JavaStrExecut.runJavaServerlet`, тот же путь, что у любого `_js`/`_css`-провайдера.

---

## 9. Лучшие практики

1. **Имя класса** = `cmp` + имя тега с заглавной после `cmp`. `<cmpUserCard>` → `cmpUserCard`. Регистр в HTML не важен — Jsoup всё приводит к нижнему.
2. **Конструктор обязателен с тремя аргументами** `(Document, Element, String)` — его вызывает `parseElementV2`.
3. **Наследуйтесь от `Base`** — получаете автоподключение `LIB_CSS`/`LIB_JS` и утилиты.
4. **Удаляйте служебные атрибуты** через `RemoveArrKeyRtrn(...)`, чтобы не протекали в итоговый HTML.
5. **Подключайте свой JS/CSS** один раз на страницу — проверяйте `head.select("...").isEmpty()`.
6. **Не парсите `<script>` вручную** — используйте готовый паттерн `cmpScript_js` (через `new Function`).
7. **Для БД-компонентов** создавайте объекты в БД **лениво** (при первом вызове `onPage`), как это сделано в `PostgreDatasetHandler` / `PostgreActionHandler`; кэшируйте по имени/хэшу контента.
8. **XSS**: всегда экранируйте пользовательские данные. Для строк используйте `org.jsoup.nodes.Entities.escape(...)` или `Jsoup.clean(...)`.
9. **Ошибки конструктора** не роняют страницу — компонент заменяется на `<error>…</error>`, а стек выводится через `parseErrorRunJava`.
10. **Кэширование**: большие компоненты выгодно кэшировать на сервере (см. `ServerResourceHandler.resources`).

---

## 10. Полный пример страницы

```html
<!DOCTYPE html>
<html lang="ru">
<head>
    <meta charset="utf-8">
    <title>Демо компонентов</title>
</head>
<body>

<h1>Форма пользователя</h1>

<cmpEdit name="inpName"  placeholder="Имя"  theme="modern"/>
<cmpEdit name="inpEmail" placeholder="Email" type="email"/>

<cmpDependences name="formGuard"
    required="inpName;inpEmail"
    depend="btnSave" checkMode="all" validateMask="true"/>

<cmpButton name="btnSave" caption="Сохранить" theme="primary"
           onclick="executeAction('saveUser', function(v){ alert('Сохранено'); });"/>

<cmpAction name="saveUser" query_type="sql" db="default" schema="public">
    INSERT INTO users(name, email) VALUES(:inpName, :inpEmail);
    <var name="inpName"  type="string" len="100" src="inpName"  srctype="ctrl"/>
    <var name="inpEmail" type="string" len="200" src="inpEmail" srctype="ctrl"/>
</cmpAction>

<cmpDataset name="userList" query_type="sql" db="default" schema="public">
    SELECT id, name, email FROM users ORDER BY id
</cmpDataset>

<cmpButton name="btnLoad" caption="Загрузить список"
           onclick="refreshDataSet('userList', console.log);"/>

<cmpScript name="hint">
    console.log('ready');
</cmpScript>

</body>
</html>
```

---

## 11. Заключение

- `<cmpXXX>` — компоненты на серверной стороне, генерируют HTML во время парсинга страницы.
- `{component}/Xxx` — статические JS/CSS/JSON-провайдеры (публичный `static byte[] onPage(HttpExchange)`).
- БД-компоненты создают свои процедуры **лениво**, что позволяет рендерить страницу даже без доступа к БД.
- Клиентский JS-стек (`D3Api` + `cmpXxx_js`) связывает все компоненты через общее состояние (`setVar/getVar`, `setSession/getSession`, `setValue/getValue`, `executeAction`, `refreshDataSet`).