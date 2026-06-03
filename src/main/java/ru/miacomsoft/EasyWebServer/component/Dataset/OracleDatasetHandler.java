package ru.miacomsoft.EasyWebServer.component.Dataset;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import ru.miacomsoft.EasyWebServer.DatabaseConfig;
import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.OracleQuery;
import ru.miacomsoft.EasyWebServer.ServerConstant;
import ru.miacomsoft.EasyWebServer.component.Base;
import ru.miacomsoft.EasyWebServer.component.cmpDataset;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class OracleDatasetHandler {

    public static final OracleDatasetHandler INSTANCE = new OracleDatasetHandler();

    public final Map<String, HashMap<String, Object>> procedureList = new ConcurrentHashMap<>();

    private OracleDatasetHandler() {}

    public void handleOracleDataset(Document doc, Element element, cmpDataset.DatasetConfig config, Base base) {
        System.out.println("Oracle dataset mode: " + config.name);

        String sqlContent = element.hasText() ? element.text().trim() : "";
        HashMap<String, Object> param = new HashMap<>();
        param.put("SQL", sqlContent);
        param.put("dbType", config.dbType);
        param.put("dbName", config.dbName);
        param.put("query_type", "sql");

        String fullName = config.schema + "." + config.name;
        procedureList.put(fullName, param);
        procedureList.put(config.name, param);

        setOracleDatasetAttributes(element, config);
        finalizeElement(element, config, base);
    }

    public void executeOracleQuery(HttpExchange query, JSONObject result, String datasetName,
                                   String dbName, JSONObject vars, boolean debugMode) {
        System.out.println("=== executeOracleDataset: " + datasetName + " ===");
        try {
            DatabaseConfig dbConfig = ServerConstant.config.getDatabaseConfig(dbName);
            if (dbConfig == null) {
                result.put("ERROR", "Database config not found: " + dbName);
                return;
            }

            HashMap<String, Object> param = procedureList.get(datasetName);
            if (param == null) {
                result.put("ERROR", "Dataset not found: " + datasetName);
                return;
            }
            String sql = (String) param.get("SQL");

            Map<String, Object> params = new HashMap<>();
            if (vars != null && vars.length() > 0) {
                Iterator<String> keys = vars.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object val = vars.get(key);
                    if (val instanceof JSONObject) {
                        JSONObject obj = (JSONObject) val;
                        String value = obj.optString("value", obj.optString("defaultVal", ""));
                        params.put(key, value);
                    } else {
                        params.put(key, val.toString());
                    }
                }
            }

            System.out.println("Executing Oracle SQL: " + sql);
            JSONArray dataArray = OracleQuery.executeQuery(dbConfig, sql, params);
            result.put("data", dataArray);
            if (debugMode) {
                result.put("SQL", sql);
                result.put("params", new JSONObject(params));
            }
        } catch (Exception e) {
            result.put("ERROR", "Oracle query error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ========== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ==========
    private void setOracleDatasetAttributes(Element element, cmpDataset.DatasetConfig config) {
        element.attr("style", "display:none");
        element.attr("dataset_name", config.name);
        element.attr("name", config.name);
        element.attr("query_type", config.queryType);
        element.attr("db_type", config.dbType);
        element.attr("pg_schema", config.schema);
        element.attr("db", config.dbName);
    }

    private void finalizeElement(Element element, cmpDataset.DatasetConfig config, Base base) {
        element.empty();
        base.attr("query_type", config.queryType);
        base.attr("db_type", config.dbType);
        base.attr("pg_schema", config.schema);
        base.attr("db", config.dbName);
        base.attr("name", config.name);
    }
}