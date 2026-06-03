package ru.miacomsoft.EasyWebServer.component;

import org.json.JSONObject;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import ru.miacomsoft.EasyWebServer.DatabaseConfig;
import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.ServerConstant;
import ru.miacomsoft.EasyWebServer.component.Action.JavaActionHandler;
import ru.miacomsoft.EasyWebServer.component.Action.OracleActionHandler;
import ru.miacomsoft.EasyWebServer.component.Action.PostgreActionHandler;

import java.util.HashMap;
import java.util.Iterator;

/**
 * Центральный класс cmpAction – точка входа.
 * Отвечает за:
 * - инициализацию компонента (парсинг атрибутов, вызов соответствующих обработчиков)
 * - обработку HTTP-запроса onPage (парсинг параметров, делегирование выполнения)
 */
@SuppressWarnings("unchecked")
public class cmpAction extends Base {

    // ======================== КОНСТРУКТОРЫ ========================
    public cmpAction(Document doc, Element element, String tag) {
        super(doc, element, tag);
        initialize(doc, element);
    }

    public cmpAction(Document doc, Element element) {
        super(doc, element, "textarea");
        initialize(doc, element);
    }

    // ======================== ИНИЦИАЛИЗАЦИЯ КОМПОНЕНТА НА СТРАНИЦЕ ========================
    private void initialize(Document doc, Element element) {
        ActionConfig config = parseActionConfig(doc, element);

        if (config.isOracle) {
            OracleActionHandler.INSTANCE.handleOracleAction(doc, element, config, this);
        } else if ("java".equals(config.queryType)) {
            JavaActionHandler.INSTANCE.handleJavaAction(doc, element, config, this);
        } else {
            PostgreActionHandler.INSTANCE.handlePostgreAction(doc, element, config, this);
        }

        attachJavaScriptLibrary(doc, config.name);
    }

    // ======================== HTTP ЗАПРОСЫ (onPage) ========================
    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/json";

        RequestParams params = parseRequestParams(query);
        System.out.println("=== cmpAction onPage: " + params.actionName + " ===");

        JSONObject vars = parseVarsFromBody(query);
        JSONObject result = new JSONObject();
        result.put("vars", vars);

        // Делегирование в зависимости от типа запроса
        if ("java".equals(params.queryType)) {
            JavaActionHandler.INSTANCE.executeJavaAction(query, result, params.actionName, vars, query.session);
            return result.toString().getBytes();
        }

        if (!"sql".equals(params.queryType)) {
            result.put("ERROR", "Unsupported query type: " + params.queryType);
            return result.toString().getBytes();
        }

        // Определяем, какой обработчик SQL вызвать (по наличию в кэше)
        Object actionParams = findActionInCache(params.actionName);
        if (actionParams == null) {
            result.put("ERROR", "Action not found: " + params.actionName);
            return result.toString().getBytes();
        }

        HashMap<String, Object> param = (HashMap<String, Object>) actionParams;
        String savedDbType = (String) param.get("dbType");
        boolean isOracle = "oci8".equals(savedDbType);
        String connectionDbName = resolveDbName(params.dbName, (String) param.get("dbName"));

        if (isOracle) {
            OracleActionHandler.INSTANCE.executeOracleAction(query, result, params.actionName, connectionDbName, vars, params.debugMode);
        } else {
            PostgreActionHandler.INSTANCE.executePostgresAction(query, result, params.actionName, vars, query.session, params.debugMode);
        }

        return result.toString().getBytes();
    }

    // ======================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ (общие для всех) ========================
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

    private DatabaseConfig getDatabaseConfiguration(ActionConfig config) {
        System.out.println("getDatabaseConfiguration for dbName: " + config.dbName);
        if (config.dbName.equals("default") || config.dbName.equals("db")) {
            DatabaseConfig dbConfig = ServerConstant.config.DATABASES.get("default");
            if (dbConfig == null) {
                System.out.println("No default DB config, creating via PostgreActionHandler");
                dbConfig = PostgreActionHandler.INSTANCE.createDefaultPostgresConfig();
                if (dbConfig == null) {
                    System.err.println("ERROR: Failed to create default PostgreSQL config");
                } else {
                    System.out.println("Default config created: " + dbConfig.getHost() + ":" + dbConfig.getPort() + "/" + dbConfig.getDatabase());
                }
            }
            return dbConfig;
        }
        return ServerConstant.config.getDatabaseConfig(config.dbName.toLowerCase());
    }


    private static RequestParams parseRequestParams(HttpExchange query) {
        RequestParams params = new RequestParams();
        JSONObject qp = query.requestParam;
        params.queryType = qp.optString("query_type", "java");
        String actionName = qp.optString("action_name", "");
        if ("null".equals(actionName)) actionName = "";
        params.actionName = actionName;
        params.pgSchema = qp.optString("pg_schema", "public");
        params.dbName = qp.optString("db", "DB");
        params.dbType = qp.optString("db_type", "jdbc");
        params.debugMode = query.session != null && query.session.containsKey("debug_mode") && (boolean) query.session.get("debug_mode");
        return params;
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
        } catch (Exception e) { System.err.println("Error parsing JSON: " + e.getMessage()); }
        return vars;
    }

    private static Object findActionInCache(String actionName) {
        if (actionName == null || actionName.isEmpty()) return null;
        Object action = JavaActionHandler.INSTANCE.procedureList.get(actionName);
        if (action != null) return action;
        action = OracleActionHandler.INSTANCE.procedureList.get(actionName);
        if (action != null) return action;
        action = PostgreActionHandler.INSTANCE.procedureList.get(actionName);
        if (action != null) return action;
        // поиск по суффиксу
        for (String key : JavaActionHandler.INSTANCE.procedureList.keySet())
            if (key.endsWith("." + actionName)) return JavaActionHandler.INSTANCE.procedureList.get(key);
        for (String key : OracleActionHandler.INSTANCE.procedureList.keySet())
            if (key.endsWith("." + actionName)) return OracleActionHandler.INSTANCE.procedureList.get(key);
        for (String key : PostgreActionHandler.INSTANCE.procedureList.keySet())
            if (key.endsWith("." + actionName)) return PostgreActionHandler.INSTANCE.procedureList.get(key);
        return null;
    }

    private static String resolveDbName(String requestDbName, String savedDbName) {
        if ("DB".equals(requestDbName) && savedDbName != null && !"DB".equals(savedDbName))
            return savedDbName;
        return requestDbName;
    }

    // ======================== ВНУТРЕННИЕ КЛАССЫ (DTO) ========================
    public static class ActionConfig {
        public String name, dbName, queryType, schema, dbType, docPath, rootPath;
        public boolean isOracle;
        public DatabaseConfig dbConfig;
    }

    public static class RequestParams {
        public String queryType, actionName, pgSchema, dbName, dbType;
        public boolean debugMode;
    }
}