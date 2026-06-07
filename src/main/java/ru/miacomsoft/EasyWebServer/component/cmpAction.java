package ru.miacomsoft.EasyWebServer.component;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import ru.miacomsoft.EasyWebServer.DatabaseConfig;
import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.JavaStrExecut;
import ru.miacomsoft.EasyWebServer.ServerConstant;
import ru.miacomsoft.EasyWebServer.component.Action.JavaActionHandler;
import ru.miacomsoft.EasyWebServer.component.Action.OracleActionHandler;
import ru.miacomsoft.EasyWebServer.component.Action.PostgreActionHandler;
import ru.miacomsoft.EasyWebServer.component.Function.OracleFunctionHandler;
import ru.miacomsoft.EasyWebServer.component.Function.PostgreFunctionHandler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("unchecked")
public class cmpAction extends Base {

    // ======================== КЭШИ НА СЕРВЕРЕ ========================
    // Кэш конфигураций действий (имя -> ActionCache)
    private static final Map<String, ActionCache> actionCache = new ConcurrentHashMap<>();

    // ======================== КОНСТРУКТОРЫ ========================
    public cmpAction(Document doc, Element element, String tag) {
        super(doc, element, tag);
        initialize(doc, element);
    }

    public cmpAction(Document doc, Element element) {
        super(doc, element, "textarea");
        initialize(doc, element);
    }

    // ======================== ИНИЦИАЛИЗАЦИЯ ========================
    private void initialize(Document doc, Element element) {
        ActionConfig config = parseActionConfig(doc, element);
        Attributes attrs = element.attributes();

        // Проверяем, нужно ли вызывать существующую функцию БД
        boolean hasAction = attrs.hasKey("action") && !attrs.get("action").trim().isEmpty();
        boolean hasNoContent = element.text().trim().isEmpty();

        if (hasAction && hasNoContent) {
            // Вызов существующей функции/процедуры БД - только сохраняем конфигурацию
            saveActionToCache(config.name, config);
            setMinimalAttributes(element, config);
            finalizeElement(element, config, this);
            attachJavaScriptLibrary(doc, config.name);
            return;
        }

        // Для SQL/Java режимов: сохраняем конфигурацию, НО НЕ СОЗДАЁМ ПРОЦЕДУРЫ СРАЗУ
        // Процедуры будут созданы при первом вызове executeAction
        saveActionToCache(config.name, config);

        setMinimalAttributes(element, config);
        finalizeElement(element, config, this);
        attachJavaScriptLibrary(doc, config.name);
    }

    /**
     * Сохранение конфигурации действия в кэш
     */
    private void saveActionToCache(String name, ActionConfig config) {
        ActionCache cache = new ActionCache();
        cache.name = config.name;
        cache.dbName = config.dbName;
        cache.queryType = config.queryType;
        cache.schema = config.schema;
        cache.dbType = config.dbType;
        cache.isOracle = config.isOracle;
        cache.dbConfig = config.dbConfig;
        cache.sqlContent = config.sqlContent;
        cache.javaCode = config.javaCode;
        cache.docPath = config.docPath;
        cache.rootPath = config.rootPath;
        cache.variables = config.variables;

        // Создаём новые ArrayList из List
        cache.importPackets = new ArrayList<>(config.importPackets);
        cache.jarResources = new ArrayList<>(config.jarResources);

        actionCache.put(name, cache);
        System.out.println("Action cached: " + name);
    }

    /**
     * Установка минимальных атрибутов на клиенте
     */
    private void setMinimalAttributes(Element element, ActionConfig config) {
        element.attr("style", "display:none");
        element.attr("action_name", config.name);
        element.attr("name", config.name);
        element.attr("vars", buildVarsJson(config.variables));
        // Только минимально необходимые атрибуты
        if (config.queryType != null && !config.queryType.equals("sql")) {
            element.attr("query_type", config.queryType);
        }
    }

    private void finalizeElement(Element element, ActionConfig config, Base base) {
        element.empty();
        base.attr("name", config.name);
    }

    private String buildVarsJson(java.util.List<ActionVar> variables) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < variables.size(); i++) {
            ActionVar v = variables.get(i);
            if (i > 0) json.append(",");
            json.append("'").append(v.name).append("':{");
            json.append("'src':'").append(v.src).append("',");
            json.append("'srctype':'").append(v.srctype).append("',");
            json.append("'direction':'").append(v.direction).append("'");
            if (v.defaultVal != null && !v.defaultVal.isEmpty())
                json.append(",'defaultVal':'").append(escapeJson(v.defaultVal)).append("'");
            if (v.len != null && !v.len.isEmpty())
                json.append(",'len':'").append(v.len).append("'");
            json.append("}");
        }
        json.append("}");
        return json.toString();
    }

    private void attachJavaScriptLibrary(Document doc, String name) {
        if (doc == null) return;
        Elements head = doc.getElementsByTag("head");
        if (head.isEmpty()) return;
        Elements existing = head.select("script[src*='cmpAction_js']");
        if (existing.isEmpty()) {
            head.append("<script cmp=\"action-lib\" src=\"{component}/cmpAction_js\" type=\"text/javascript\"></script>");
            System.out.println("cmpAction JS attached: " + name);
        }
    }

    // ======================== ПАРСИНГ КОНФИГУРАЦИИ ========================
    private ActionConfig parseActionConfig(Document doc, Element element) {
        ActionConfig config = new ActionConfig();
        Attributes attrs = element.attributes();

        config.name = attrs.get("name");
        config.dbName = RemoveArrKeyRtrn(attrs, "db", "default");
        config.queryType = attrs.hasKey("query_type") ? attrs.get("query_type") : "sql";
        config.schema = attrs.hasKey("schema") ? attrs.get("schema") : "public";
        config.dbType = attrs.hasKey("db_type") ? attrs.get("db_type") : "jdbc";
        config.docPath = doc != null ? doc.attr("doc_path") : "";
        config.rootPath = doc != null ? doc.attr("rootPath") : "";

        // Парсим содержимое (SQL или Java код)
        config.sqlContent = element.hasText() ? element.text().trim() : "";
        config.javaCode = config.sqlContent; // для Java режима

        // Парсим импорты для Java
        config.importPackets = new ArrayList<>();  // ArrayList
        config.jarResources = new ArrayList<>();   // ArrayList

        for (Element child : element.children()) {
            String tagName = child.tag().toString().toLowerCase();
            if (tagName.contains("import")) {
                Attributes childAttrs = child.attributes();
                if (childAttrs.hasKey("path")) config.jarResources.add(childAttrs.get("path"));
                if (childAttrs.hasKey("packet")) config.importPackets.add(childAttrs.get("packet"));
            }
        }

        // Парсим переменные
        config.variables = parseVariables(element);

        config.dbConfig = getDatabaseConfiguration(config);
        if (config.dbConfig != null) {
            config.dbType = config.dbConfig.getType().toLowerCase();
            config.isOracle = config.dbConfig.getType().equals("oci8");
            if (!attrs.hasKey("schema") && config.dbName.equals("default")) {
                config.schema = config.dbConfig.getSchema() != null ? config.dbConfig.getSchema() : "public";
            }
        }

        return config;
    }

    private java.util.List<ActionVar> parseVariables(Element element) {
        java.util.List<ActionVar> vars = new java.util.ArrayList<>();
        for (Element child : element.children()) {
            String tag = child.tag().toString().toLowerCase();
            if (tag.contains("var") || tag.contains("cmpactionvar")) {
                vars.add(parseActionVar(child));
            }
        }
        return vars;
    }

    private ActionVar parseActionVar(Element element) {
        Attributes attrs = element.attributes();
        ActionVar var = new ActionVar();
        var.name = attrs.get("name");
        var.src = removeAttr(attrs, "src", var.name);
        var.srctype = removeAttr(attrs, "srctype", "var");
        var.len = removeAttr(attrs, "len", "");
        var.defaultVal = removeAttr(attrs, "default", "");
        var.type = removeAttr(attrs, "type", "string");
        String put = attrs.hasKey("put") ? attrs.get("put") : null;
        String get = attrs.hasKey("get") ? attrs.get("get") : null;
        var.direction = (put != null && get != null) ? "INOUT" : (put != null ? "OUT" : "IN");
        return var;
    }

    private DatabaseConfig getDatabaseConfiguration(ActionConfig config) {
        if (config.dbName.equals("default") || config.dbName.equals("db")) {
            DatabaseConfig dbConfig = ServerConstant.config.DATABASES.get("default");
            if (dbConfig == null) {
                dbConfig = PostgreActionHandler.INSTANCE.createDefaultPostgresConfig();
            }
            return dbConfig;
        }
        return ServerConstant.config.getDatabaseConfig(config.dbName.toLowerCase());
    }

    private String removeAttr(Attributes attrs, String key, String defaultValue) {
        if (attrs.hasKey(key)) {
            String val = attrs.get(key);
            attrs.remove(key);
            return val;
        }
        return defaultValue;
    }

    private String escapeJson(String s) {
        return s.replace("'", "\\\\'");
    }

    // ======================== HTTP ЗАПРОСЫ (onPage) ========================
    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/json";

        String actionName = query.requestParam.optString("action_name", "");
        if (actionName.isEmpty()) {
            actionName = query.requestParam.optString("name", "");
        }

        System.out.println("=== cmpAction onPage: " + actionName + " ===");

        // Получаем конфигурацию из кэша на сервере
        ActionCache cache = actionCache.get(actionName);
        if (cache == null) {
            JSONObject error = new JSONObject();
            error.put("ERROR", "Action not found: " + actionName);
            return error.toString().getBytes();
        }

        JSONObject vars = parseVarsFromBody(query);
        JSONObject result = new JSONObject();
        result.put("vars", vars);

        // Обработка Java действия
        if ("java".equals(cache.queryType)) {
            executeJavaAction(query, result, cache, vars);
            return result.toString().getBytes();
        }

        // Обработка SQL действия
        boolean debugMode = query.session != null &&
                query.session.containsKey("debug_mode") &&
                (boolean) query.session.get("debug_mode");

        if (cache.isOracle) {
            executeOracleAction(query, result, cache, vars, debugMode);
        } else {
            executePostgresAction(query, result, cache, vars, debugMode);
        }

        return result.toString().getBytes();
    }

    /**
     * Выполнение Java действия (создание/вызов при первом обращении)
     */
    private static void executeJavaAction(HttpExchange query, JSONObject result, ActionCache cache, JSONObject vars) {
        System.out.println("=== executeJavaAction: " + cache.name + " ===");
        JavaActionHandler.INSTANCE.executeJavaAction(query, result, cache, vars, query.session);
    }

    /**
     * Выполнение Oracle действия (создание процедуры при первом вызове)
     */
    private static void executeOracleAction(HttpExchange query, JSONObject result,
                                            ActionCache cache, JSONObject vars, boolean debugMode) {
        System.out.println("=== executeOracleAction: " + cache.name + " ===");

        // Проверяем, создана ли уже процедура в кэше OracleActionHandler
        if (!OracleActionHandler.INSTANCE.procedureList.containsKey(cache.name)) {
            // Первый вызов - создаём процедуру в БД и кэшируем
            System.out.println("First call - creating Oracle procedure: " + cache.name);

            // Создаём временный элемент для передачи в OracleActionHandler
            Element tempElement = new Element("textarea");
            tempElement.text(cache.sqlContent);
            for (ActionVar var : cache.variables) {
                Element varElement = new Element("var");
                varElement.attr("name", var.name);
                varElement.attr("src", var.src);
                varElement.attr("srctype", var.srctype);
                varElement.attr("type", var.type);
                if ("IN".equals(var.direction)) {
                    varElement.attr("get", "true");
                } else if ("OUT".equals(var.direction)) {
                    varElement.attr("put", "true");
                } else {
                    varElement.attr("get", "true");
                    varElement.attr("put", "true");
                }
                if (var.defaultVal != null && !var.defaultVal.isEmpty()) {
                    varElement.attr("default", var.defaultVal);
                }
                if (var.len != null && !var.len.isEmpty()) {
                    varElement.attr("len", var.len);
                }
                tempElement.appendChild(varElement);
            }

            cmpAction.ActionConfig actionConfig = new cmpAction.ActionConfig();
            actionConfig.name = cache.name;
            actionConfig.dbName = cache.dbName;
            actionConfig.queryType = cache.queryType;
            actionConfig.schema = cache.schema;
            actionConfig.dbType = cache.dbType;
            actionConfig.isOracle = cache.isOracle;
            actionConfig.dbConfig = cache.dbConfig;

            OracleActionHandler.INSTANCE.handleOracleAction(null, tempElement, actionConfig, null);
        }

        // Выполняем действие
        OracleActionHandler.INSTANCE.executeOracleAction(query, result, cache.name,
                cache.dbName, vars, debugMode);
    }

    /**
     * Выполнение PostgreSQL действия (создание процедуры при первом вызове)
     */
    private static void executePostgresAction(HttpExchange query, JSONObject result,
                                              ActionCache cache, JSONObject vars, boolean debugMode) {
        System.out.println("=== executePostgresAction: " + cache.name + " ===");

        // Проверяем, создана ли уже процедура в кэше PostgreActionHandler
        if (!PostgreActionHandler.INSTANCE.procedureList.containsKey(cache.name)) {
            // Первый вызов - создаём процедуру в БД и кэшируем
            System.out.println("First call - creating PostgreSQL procedure: " + cache.name);

            // Создаём временный элемент для передачи в PostgreActionHandler
            Element tempElement = new Element("textarea");
            tempElement.text(cache.sqlContent);
            for (ActionVar var : cache.variables) {
                Element varElement = new Element("var");
                varElement.attr("name", var.name);
                varElement.attr("src", var.src);
                varElement.attr("srctype", var.srctype);
                varElement.attr("type", var.type);
                if ("IN".equals(var.direction)) {
                    varElement.attr("get", "true");
                } else if ("OUT".equals(var.direction)) {
                    varElement.attr("put", "true");
                } else {
                    varElement.attr("get", "true");
                    varElement.attr("put", "true");
                }
                if (var.defaultVal != null && !var.defaultVal.isEmpty()) {
                    varElement.attr("default", var.defaultVal);
                }
                if (var.len != null && !var.len.isEmpty()) {
                    varElement.attr("len", var.len);
                }
                tempElement.appendChild(varElement);
            }

            cmpAction.ActionConfig actionConfig = new cmpAction.ActionConfig();
            actionConfig.name = cache.name;
            actionConfig.dbName = cache.dbName;
            actionConfig.queryType = cache.queryType;
            actionConfig.schema = cache.schema;
            actionConfig.dbType = cache.dbType;
            actionConfig.isOracle = cache.isOracle;
            actionConfig.dbConfig = cache.dbConfig;

            PostgreActionHandler.INSTANCE.handlePostgreAction(null, tempElement, actionConfig, null);
        }

        // Выполняем действие
        PostgreActionHandler.INSTANCE.executePostgresAction(query, result, cache.name,
                vars, query.session, debugMode);
    }


    private static JSONObject parseVarsFromBody(HttpExchange query) {
        JSONObject vars = new JSONObject();
        String body = new String(query.postCharBody);
        if (body == null || body.isEmpty()) return vars;
        try {
            JSONObject requestVars = new JSONObject(body);
            Iterator<String> keys = requestVars.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object val = requestVars.get(key);
                if (val instanceof JSONObject) vars.put(key, val);
                else {
                    JSONObject wrapper = new JSONObject();
                    wrapper.put("value", val.toString());
                    wrapper.put("src", key);
                    wrapper.put("srctype", "var");
                    vars.put(key, wrapper);
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing JSON: " + e.getMessage());
        }
        return vars;
    }

    private static String getValueFromVars(JSONObject vars, Map<String, Object> session,
                                           String name, String defaultValue) {
        if (!vars.has(name)) return defaultValue;
        Object val = vars.get(name);
        if (val instanceof JSONObject) {
            JSONObject obj = (JSONObject) val;
            if ("session".equals(obj.optString("srctype"))) {
                Object sessionVal = session.get(name);
                return sessionVal != null ? sessionVal.toString() : obj.optString("defaultVal", defaultValue);
            }
            return obj.optString("value", obj.optString("defaultVal", defaultValue));
        }
        return val.toString();
    }

    private static void updateVars(JSONObject vars, Map<String, Object> session,
                                   String name, String value, String srctype) {
        if (vars.has(name) && vars.get(name) instanceof JSONObject) {
            JSONObject obj = vars.getJSONObject(name);
            if ("session".equals(srctype)) {
                session.put(name, value);
            } else {
                obj.put("value", value);
            }
        } else {
            JSONObject wrapper = new JSONObject();
            wrapper.put("value", value);
            wrapper.put("src", name);
            wrapper.put("srctype", srctype != null ? srctype : "var");
            vars.put(name, wrapper);
        }
    }

    // ======================== ВНУТРЕННИЕ КЛАССЫ ========================

    public static class ActionConfig {
        public String name, dbName, queryType, schema, dbType, docPath, rootPath;
        public boolean isOracle;
        public DatabaseConfig dbConfig;
        public String sqlContent;
        public String javaCode;
        public List<String> importPackets;
        public List<String> jarResources;
        public List<ActionVar> variables;
    }

    // Измените с private static class на public static class
    public static class ActionVar {
        public String name, src, srctype, len, defaultVal, type, direction;
    }

    public static class ActionCache {
        public String name, dbName, queryType, schema, dbType, docPath, rootPath;
        public boolean isOracle;
        public DatabaseConfig dbConfig;
        public String sqlContent;
        public String javaCode;
        public List<String> importPackets;
        public List<String> jarResources;
        public List<ActionVar> variables;
    }
}