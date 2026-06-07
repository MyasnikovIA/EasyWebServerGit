package ru.miacomsoft.EasyWebServer.component;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import ru.miacomsoft.EasyWebServer.DatabaseConfig;
import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.ServerConstant;
import ru.miacomsoft.EasyWebServer.component.Dataset.JavaDatasetHandler;
import ru.miacomsoft.EasyWebServer.component.Dataset.OracleDatasetHandler;
import ru.miacomsoft.EasyWebServer.component.Dataset.PostgreDatasetHandler;
import ru.miacomsoft.EasyWebServer.component.Function.OracleFunctionHandler;
import ru.miacomsoft.EasyWebServer.component.Function.PostgreFunctionHandler;

import java.util.*;

@SuppressWarnings("unchecked")
public class cmpDataset extends Base {

    // ======================== КОНСТРУКТОРЫ ========================
    public cmpDataset(Document doc, Element element, String tag) {
        super(doc, element, tag);
        initialize(doc, element);
    }

    public cmpDataset(Document doc, Element element) {
        super(doc, element, "textarea");
        initialize(doc, element);
    }

    // ======================== ИНИЦИАЛИЗАЦИЯ ========================
    private void initialize(Document doc, Element element) {
        DatasetConfig config = parseDatasetConfig(doc, element);
        Attributes attrs = element.attributes();

        // Проверяем, нужно ли вызывать существующую функцию БД
        // Условие: есть атрибут action И нет содержимого внутри тега
        boolean hasAction = attrs.hasKey("action") && !attrs.get("action").trim().isEmpty();
        boolean hasNoContent = element.text().trim().isEmpty();

        if (hasAction && hasNoContent) {
            // Вызов существующей функции БД
            if (config.isOracle) {
                OracleFunctionHandler.INSTANCE.handleDatasetFunction(doc, element, config, this);
            } else {
                PostgreFunctionHandler.INSTANCE.handleDatasetFunction(doc, element, config, this);
            }
            attachJavaScriptLibrary(doc, config.name);
            return;
        }

        // Существующая логика для SQL/Java (есть SQL запрос или Java код внутри тега)
        if (config.isOracle) {
            OracleDatasetHandler.INSTANCE.handleOracleDataset(doc, element, config, this);
        } else if ("java".equals(config.queryType)) {
            JavaDatasetHandler.INSTANCE.handleJavaDataset(doc, element, config, this);
        } else {
            PostgreDatasetHandler.INSTANCE.handlePostgreDataset(doc, element, config, this);
        }

        attachJavaScriptLibrary(doc, config.name);
    }

    // ======================== HTTP ЗАПРОСЫ (onPage) ========================
    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/json";

        RequestParams params = parseRequestParams(query);
        System.out.println("=== cmpDataset onPage: " + params.datasetName + " ===");

        JSONObject vars = parseVarsFromBody(query);
        JSONObject result = new JSONObject();
        result.put("data", new JSONArray());
        result.put("vars", vars);
        
        // Обработка Java датасета
        if ("java".equals(params.queryType)) {
            JavaDatasetHandler.INSTANCE.executeJavaDataset(query, result, params.datasetName, vars, query.session, params.debugMode);
            return result.toString().getBytes();
        }

        // Обработка обычного SQL запроса
        if ("sql".equals(params.queryType)) {
            // Поиск датасета в кэшах обработчиков
            Object datasetParams = findDatasetInCache(params.datasetName, params.pgSchema);
            if (datasetParams == null) {
                result.put("ERROR", "Dataset not found: " + params.datasetName);
                return result.toString().getBytes();
            }

            HashMap<String, Object> param = (HashMap<String, Object>) datasetParams;
            String savedDbType = (String) param.get("dbType");
            String action = (String) param.get("action");
            boolean isOracle = "oci8".equals(savedDbType);
            String connectionDbName = resolveDbName(params.dbName, (String) param.get("dbName"));

            if (isOracle) {
                OracleDatasetHandler.INSTANCE.executeOracleQuery(query, result, params.datasetName,params.dbName, vars, params.debugMode);
            } else {
                String fullName = params.datasetName;
                if (params.pgSchema!=null && params.pgSchema.length()>0) {
                    fullName = params.pgSchema + "." + params.datasetName;
                }
                PostgreDatasetHandler.INSTANCE.executePostgresQuery(query, result, fullName, vars, params.debugMode);
            }
            return result.toString().getBytes();
        }

        // Если тип запроса не распознан
        result.put("ERROR", "Unsupported query type: " + params.queryType);
        return result.toString().getBytes();
    }

    // ======================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ========================
    private void attachJavaScriptLibrary(Document doc, String name) {
        if (doc == null) return;
        Elements head = doc.getElementsByTag("head");
        if (head.isEmpty()) return;
        Elements existing = head.select("script[src*='cmpDataset_js']");
        if (existing.isEmpty()) {
            head.append("<script cmp=\"dataset-lib\" src=\"{component}/cmpDataset_js\" type=\"text/javascript\"></script>");
            System.out.println("cmpDataset JS attached: " + name);
        }
    }

    private DatasetConfig parseDatasetConfig(Document doc, Element element) {
        DatasetConfig config = new DatasetConfig();
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
        config.variables = parseVariables(element);
        return config;
    }

    private List<DatasetVar> parseVariables(Element element) {
        List<DatasetVar> vars = new ArrayList<>();
        if (element == null) return vars;

        for (Element child : element.children()) {
            String tag = child.tag().toString().toLowerCase();
            if (tag.contains("var") || tag.contains("cmpdatasetvar")) {
                DatasetVar var = new DatasetVar();
                Attributes attrs = child.attributes();
                var.name = attrs.get("name");
                var.src = RemoveArrKeyRtrn(attrs, "src", var.name);
                var.srctype = RemoveArrKeyRtrn(attrs, "srctype", "var");
                var.type = RemoveArrKeyRtrn(attrs, "type", "string");
                var.defaultVal = RemoveArrKeyRtrn(attrs, "default", "");
                var.len = RemoveArrKeyRtrn(attrs, "len", "");
                String put = attrs.hasKey("put") ? attrs.get("put") : null;
                String get = attrs.hasKey("get") ? attrs.get("get") : null;
                var.direction = (put != null && get != null) ? "INOUT" : (put != null ? "OUT" : "IN");
                vars.add(var);
            }
        }
        return vars;
    }
    private DatabaseConfig getDatabaseConfiguration(DatasetConfig config) {
        if (config.dbName.equals("default") || config.dbName.equals("db")) {
            DatabaseConfig dbConfig = ServerConstant.config.DATABASES.get("default");
            if (dbConfig == null) {
                dbConfig = createDefaultPostgresConfig();
            }
            return dbConfig;
        }
        return ServerConstant.config.getDatabaseConfig(config.dbName.toLowerCase());
    }

    private DatabaseConfig createDefaultPostgresConfig() {
        String url = ServerConstant.config.DATABASE_NAME;
        String user = ServerConstant.config.DATABASE_USER_NAME;
        String pass = ServerConstant.config.DATABASE_USER_PASS;
        if (url == null || url.isEmpty()) return null;
        try {
            DatabaseConfig cfg = new DatabaseConfig();
            cfg.setType("jdbc");
            cfg.setDriver("org.postgresql.Driver");
            String withoutProtocol = url.substring(url.indexOf("://") + 3);
            String[] parts = withoutProtocol.split("/", 2);
            String hostPort = parts[0];
            String database = parts.length > 1 ? parts[1] : "postgres";
            String[] hp = hostPort.split(":");
            cfg.setHost(hp[0]);
            cfg.setPort(hp.length > 1 ? hp[1] : "5432");
            cfg.setDatabase(database);
            cfg.setUsername(user);
            cfg.setPassword(pass);
            cfg.setSchema("public");
            return cfg;
        } catch (Exception e) { return null; }
    }

    private static RequestParams parseRequestParams(HttpExchange query) {
        RequestParams params = new RequestParams();
        JSONObject qp = query.requestParam;
        String datasetName = qp.optString("dataset_name", "");
        if ("null".equals(datasetName)) datasetName = "";
        params.datasetName = datasetName;
        params.pgSchema = qp.optString("pg_schema", "public");
        params.dbName = qp.optString("db", "DB");
        params.dbType = qp.optString("db_type", "jdbc");
        params.debugMode = query.session != null && query.session.containsKey("debug_mode") && (boolean) query.session.get("debug_mode");

        // Определяем query_type по наличию в кэшах
        if (JavaDatasetHandler.INSTANCE.procedureList.containsKey(datasetName)) {
            params.queryType = "java";
        } else {
            params.queryType = qp.optString("query_type", "sql");
        }

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
                if (val instanceof JSONObject) {
                    vars.put(key, val);
                } else {
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

    private static Object findDatasetInCache(String datasetName, String pgSchema) {
        if (datasetName == null || datasetName.isEmpty()) return null;

        // Поиск в кэшах датасетов
        Object obj = JavaDatasetHandler.INSTANCE.procedureList.get(datasetName);
        if (obj != null) return obj;
        obj = OracleDatasetHandler.INSTANCE.procedureList.get(datasetName);
        if (obj != null) return obj;
        obj = PostgreDatasetHandler.INSTANCE.procedureList.get(datasetName);
        if (obj != null) return obj;

        // Поиск в кэшах функций (для случая, когда вызывается функция через action)
        obj = OracleFunctionHandler.INSTANCE.functionCache.get(datasetName);
        if (obj != null) return obj;
        obj = PostgreFunctionHandler.INSTANCE.functionCache.get(datasetName);
        if (obj != null) return obj;

        // поиск по полному имени со схемой
        String fullName = pgSchema + "." + datasetName;
        obj = OracleDatasetHandler.INSTANCE.procedureList.get(fullName);
        if (obj != null) return obj;
        obj = PostgreDatasetHandler.INSTANCE.procedureList.get(fullName);
        if (obj != null) return obj;
        obj = OracleFunctionHandler.INSTANCE.functionCache.get(fullName);
        if (obj != null) return obj;
        obj = PostgreFunctionHandler.INSTANCE.functionCache.get(fullName);
        if (obj != null) return obj;

        // поиск по суффиксу
        for (String key : JavaDatasetHandler.INSTANCE.procedureList.keySet())
            if (key.endsWith("." + datasetName)) return JavaDatasetHandler.INSTANCE.procedureList.get(key);
        for (String key : OracleDatasetHandler.INSTANCE.procedureList.keySet())
            if (key.endsWith("." + datasetName)) return OracleDatasetHandler.INSTANCE.procedureList.get(key);
        for (String key : PostgreDatasetHandler.INSTANCE.procedureList.keySet())
            if (key.endsWith("." + datasetName)) return PostgreDatasetHandler.INSTANCE.procedureList.get(key);
        for (String key : OracleFunctionHandler.INSTANCE.functionCache.keySet())
            if (key.endsWith("." + datasetName)) return OracleFunctionHandler.INSTANCE.functionCache.get(key);
        for (String key : PostgreFunctionHandler.INSTANCE.functionCache.keySet())
            if (key.endsWith("." + datasetName)) return PostgreFunctionHandler.INSTANCE.functionCache.get(key);

        return null;
    }

    private static String resolveDbName(String requestDbName, String savedDbName) {
        if ("DB".equals(requestDbName) && savedDbName != null && !"DB".equals(savedDbName))
            return savedDbName;
        return requestDbName;
    }

    public static class DatasetConfig {
        public String name, dbName, queryType, schema, dbType, docPath, rootPath;
        public boolean isOracle;
        public DatabaseConfig dbConfig;
        public List<DatasetVar> variables;
    }

    public static class DatasetVar {
        public String name;
        public String src;
        public String srctype;
        public String len;
        public String defaultVal;
        public String type;
        public String direction;
    }

    public static class RequestParams {
        public String queryType, datasetName, pgSchema, dbName, dbType;
        public boolean debugMode;
    }
}