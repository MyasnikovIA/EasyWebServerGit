package ru.miacomsoft.EasyWebServer.component.Function;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import ru.miacomsoft.EasyWebServer.DatabaseConfig;
import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.ServerConstant;
import ru.miacomsoft.EasyWebServer.component.Base;
import ru.miacomsoft.EasyWebServer.component.cmpAction;
import ru.miacomsoft.EasyWebServer.component.cmpDataset;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Обработчик вызова функций PostgreSQL через атрибут action
 */
public class PostgreFunctionHandler {

    public static final PostgreFunctionHandler INSTANCE = new PostgreFunctionHandler();

    // Кэш для функций (имя функции -> параметры)
    public final Map<String, HashMap<String, Object>> functionCache = new ConcurrentHashMap<>();

    private PostgreFunctionHandler() {}

    // ================================ ДЛЯ DATASET ================================

    public void handleDatasetFunction(Document doc, Element element, cmpDataset.DatasetConfig config, Base base) {
        Attributes attrs = element.attributes();
        String functionName = attrs.get("action");

        System.out.println("PostgreSQL dataset function mode: " + config.name + " -> function: " + functionName);

        if (functionName == null || functionName.trim().isEmpty()) {
            base.attr("error", "Action attribute is required for function call");
            return;
        }

        if (config.dbConfig == null) {
            base.attr("error", "Database config not found for: " + config.dbName);
            return;
        }

        List<DatasetFunctionVar> variables = parseDatasetVariables(element);

        // Сохраняем информацию о вызове функции
        HashMap<String, Object> param = new HashMap<>();
        param.put("functionName", functionName);
        param.put("dbConfig", config.dbConfig);
        param.put("schema", config.schema);
        param.put("vars", createVarList(variables));
        param.put("varTypes", createVarTypeMap(variables));
        param.put("varDirections", createVarDirectionMap(variables));
        param.put("query_type", "sql");

        functionCache.put(config.name, param);
        functionCache.put(functionName, param);

        setDatasetFunctionAttributes(element, config, variables, functionName);
        finalizeDatasetElement(element, config, base);
    }

    public void executeDatasetFunction(HttpExchange query, JSONObject result, String datasetName,
                                       JSONObject vars, Map<String, Object> session, boolean debugMode) {
        System.out.println("=== executeDatasetFunction: " + datasetName + " ===");

        HashMap<String, Object> param = functionCache.get(datasetName);
        if (param == null) {
            result.put("ERROR", "Function not found: " + datasetName);
            return;
        }

        String functionName = (String) param.get("functionName");
        DatabaseConfig dbConfig = (DatabaseConfig) param.get("dbConfig");
        String schema = (String) param.get("schema");
        List<String> varNames = (List<String>) param.get("vars");
        Map<String, String> varTypes = (Map<String, String>) param.get("varTypes");
        Map<String, String> varDirections = (Map<String, String>) param.get("varDirections");

        Connection conn = null;
        CallableStatement cs = null;
        ResultSet rs = null;

        try {
            String jdbcUrl = buildJdbcUrlWithSchema(dbConfig, schema);
            Properties props = new Properties();
            props.setProperty("user", dbConfig.getUsername());
            props.setProperty("password", dbConfig.getPassword());
            props.setProperty("currentSchema", schema);
            conn = DriverManager.getConnection(jdbcUrl, props);
            conn.setAutoCommit(false);

            // Определяем, функция возвращает таблицу (SETOF) или скалярное значение
            boolean returnsSet = checkIfReturnsSet(conn, schema, functionName);

            String callSql = buildFunctionCall(functionName, schema, varNames, varDirections, returnsSet);
            cs = conn.prepareCall(callSql);

            // Устанавливаем параметры
            int idx = 1;
            for (String vname : varNames) {
                String dir = varDirections.getOrDefault(vname, "IN");
                if ("OUT".equals(dir) || "INOUT".equals(dir)) {
                    registerOutParameter(cs, idx, varTypes.getOrDefault(vname, "string"));
                }
                idx++;
            }

            idx = 1;
            for (String vname : varNames) {
                String dir = varDirections.getOrDefault(vname, "IN");
                if ("IN".equals(dir) || "INOUT".equals(dir)) {
                    String value = getValueFromVars(vars, session, vname);
                    setParameter(cs, idx, value, varTypes.getOrDefault(vname, "string"), conn);
                }
                idx++;
            }

            JSONArray dataArray = new JSONArray();

            if (returnsSet) {
                // Функция возвращает таблицу (SETOF)
                boolean hasResults = cs.execute();
                if (hasResults) {
                    rs = cs.getResultSet();
                    dataArray = resultSetToJSONArray(rs);
                }
            } else {
                // Функция возвращает скалярное значение
                boolean hasResults = cs.execute();
                if (hasResults) {
                    rs = cs.getResultSet();
                    if (rs.next()) {
                        Object value = rs.getObject(1);
                        JSONObject row = new JSONObject();
                        row.put("result", value);
                        dataArray.put(row);
                    }
                } else {
                    // Проверяем выходные параметры
                    JSONObject row = new JSONObject();
                    idx = 1;
                    for (String vname : varNames) {
                        String dir = varDirections.getOrDefault(vname, "IN");
                        if ("OUT".equals(dir) || "INOUT".equals(dir)) {
                            String outValue = getOutParameter(cs, idx, varTypes.getOrDefault(vname, "string"));
                            row.put(vname, outValue);
                            updateVars(vars, session, vname, outValue);
                        }
                        idx++;
                    }
                    if (row.length() > 0) {
                        dataArray.put(row);
                    }
                }
            }

            result.put("data", dataArray);
            if (debugMode) {
                result.put("function", schema + "." + functionName);
                result.put("call_sql", callSql);
            }

        } catch (SQLException e) {
            result.put("ERROR", "SQL Error: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            result.put("ERROR", "Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignore) {}
            try { if (cs != null) cs.close(); } catch (Exception ignore) {}
            try { if (conn != null) conn.close(); } catch (Exception ignore) {}
        }
    }

    // ================================ ДЛЯ ACTION ================================

    public void handleActionFunction(Document doc, Element element, cmpAction.ActionConfig config, Base base) {
        Attributes attrs = element.attributes();
        String functionName = attrs.get("action");

        System.out.println("PostgreSQL action function mode: " + config.name + " -> function: " + functionName);

        if (functionName == null || functionName.trim().isEmpty()) {
            base.attr("error", "Action attribute is required for function call");
            return;
        }

        if (config.dbConfig == null) {
            base.attr("error", "Database config not found for: " + config.dbName);
            return;
        }

        List<ActionFunctionVar> variables = parseActionVariables(element);

        HashMap<String, Object> param = new HashMap<>();
        param.put("functionName", functionName);
        param.put("dbConfig", config.dbConfig);
        param.put("schema", config.schema);
        param.put("vars", createActionVarList(variables));
        param.put("varTypes", createActionVarTypeMap(variables));
        param.put("varDirections", createActionVarDirectionMap(variables));
        param.put("query_type", "sql");

        functionCache.put(config.name, param);
        functionCache.put(functionName, param);

        setActionFunctionAttributes(element, config, variables, functionName);
        finalizeActionElement(element, config, base);
    }

    public void executeActionFunction(HttpExchange query, JSONObject result, String actionName,
                                      JSONObject vars, Map<String, Object> session, boolean debugMode) {
        System.out.println("=== executeActionFunction: " + actionName + " ===");

        HashMap<String, Object> param = functionCache.get(actionName);
        if (param == null) {
            result.put("ERROR", "Function not found: " + actionName);
            return;
        }

        String functionName = (String) param.get("functionName");
        DatabaseConfig dbConfig = (DatabaseConfig) param.get("dbConfig");
        String schema = (String) param.get("schema");
        List<String> varNames = (List<String>) param.get("vars");
        Map<String, String> varTypes = (Map<String, String>) param.get("varTypes");
        Map<String, String> varDirections = (Map<String, String>) param.get("varDirections");

        Connection conn = null;
        CallableStatement cs = null;

        try {
            String jdbcUrl = buildJdbcUrlWithSchema(dbConfig, schema);
            Properties props = new Properties();
            props.setProperty("user", dbConfig.getUsername());
            props.setProperty("password", dbConfig.getPassword());
            props.setProperty("currentSchema", schema);
            conn = DriverManager.getConnection(jdbcUrl, props);
            conn.setAutoCommit(false);

            String callSql = buildProcedureCall(functionName, schema, varNames, varDirections);
            cs = conn.prepareCall(callSql);

            int idx = 1;
            for (String vname : varNames) {
                String dir = varDirections.getOrDefault(vname, "IN");
                if ("OUT".equals(dir) || "INOUT".equals(dir)) {
                    registerOutParameter(cs, idx, varTypes.getOrDefault(vname, "string"));
                }
                idx++;
            }

            idx = 1;
            for (String vname : varNames) {
                String dir = varDirections.getOrDefault(vname, "IN");
                if ("IN".equals(dir) || "INOUT".equals(dir)) {
                    String value = getValueFromVars(vars, session, vname);
                    setParameter(cs, idx, value, varTypes.getOrDefault(vname, "string"), conn);
                }
                idx++;
            }

            cs.execute();
            conn.commit();

            // Получаем выходные параметры
            idx = 1;
            for (String vname : varNames) {
                String dir = varDirections.getOrDefault(vname, "IN");
                if ("OUT".equals(dir) || "INOUT".equals(dir)) {
                    String outValue = getOutParameter(cs, idx, varTypes.getOrDefault(vname, "string"));
                    updateVars(vars, session, vname, outValue);
                }
                idx++;
            }

            result.put("vars", vars);
            if (debugMode) {
                result.put("function", schema + "." + functionName);
                result.put("call_sql", callSql);
            }

        } catch (SQLException e) {
            result.put("ERROR", "SQL Error: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            result.put("ERROR", "Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try { if (cs != null) cs.close(); } catch (Exception ignore) {}
            try { if (conn != null) conn.close(); } catch (Exception ignore) {}
        }
    }

    // ================================ ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ================================

    private List<DatasetFunctionVar> parseDatasetVariables(Element element) {
        List<DatasetFunctionVar> vars = new ArrayList<>();
        for (Element child : element.children()) {
            String tag = child.tag().toString().toLowerCase();
            if (tag.contains("var") || tag.contains("cmpdatasetvar")) {
                vars.add(parseDatasetVar(child));
            }
        }
        return vars;
    }

    private DatasetFunctionVar parseDatasetVar(Element element) {
        Attributes attrs = element.attributes();
        DatasetFunctionVar var = new DatasetFunctionVar();
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

    private List<ActionFunctionVar> parseActionVariables(Element element) {
        List<ActionFunctionVar> vars = new ArrayList<>();
        for (Element child : element.children()) {
            String tag = child.tag().toString().toLowerCase();
            if (tag.contains("var") || tag.contains("cmpactionvar")) {
                vars.add(parseActionVar(child));
            }
        }
        return vars;
    }

    private ActionFunctionVar parseActionVar(Element element) {
        Attributes attrs = element.attributes();
        ActionFunctionVar var = new ActionFunctionVar();
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

    private List<String> createVarList(List<? extends BaseFunctionVar> variables) {
        List<String> names = new ArrayList<>();
        for (BaseFunctionVar v : variables) {
            names.add(v.name);
        }
        return names;
    }

    private List<String> createActionVarList(List<ActionFunctionVar> variables) {
        List<String> names = new ArrayList<>();
        for (ActionFunctionVar v : variables) {
            names.add(v.name);
        }
        return names;
    }

    private Map<String, String> createVarTypeMap(List<? extends BaseFunctionVar> variables) {
        Map<String, String> types = new HashMap<>();
        for (BaseFunctionVar v : variables) {
            types.put(v.name, v.type);
        }
        return types;
    }

    private Map<String, String> createActionVarTypeMap(List<ActionFunctionVar> variables) {
        Map<String, String> types = new HashMap<>();
        for (ActionFunctionVar v : variables) {
            types.put(v.name, v.type);
        }
        return types;
    }

    private Map<String, String> createVarDirectionMap(List<? extends BaseFunctionVar> variables) {
        Map<String, String> dirs = new HashMap<>();
        for (BaseFunctionVar v : variables) {
            dirs.put(v.name, v.direction);
        }
        return dirs;
    }

    private Map<String, String> createActionVarDirectionMap(List<ActionFunctionVar> variables) {
        Map<String, String> dirs = new HashMap<>();
        for (ActionFunctionVar v : variables) {
            dirs.put(v.name, v.direction);
        }
        return dirs;
    }

    private void setDatasetFunctionAttributes(Element element, cmpDataset.DatasetConfig config,
                                              List<DatasetFunctionVar> variables, String functionName) {
        element.attr("style", "display:none");
        element.attr("dataset_name", functionName);
        element.attr("name", config.name);
        element.attr("vars", buildDatasetVarsJson(variables));
        element.attr("query_type", "sql");
        element.attr("action_type", "function");
        element.attr("db", config.dbName);
        element.attr("pg_schema", config.schema);
        element.attr("db_type", config.dbType);
    }

    private void setActionFunctionAttributes(Element element, cmpAction.ActionConfig config,
                                             List<ActionFunctionVar> variables, String functionName) {
        element.attr("style", "display:none");
        element.attr("action_name", functionName);
        element.attr("name", config.name);
        element.attr("vars", buildActionVarsJson(variables));
        element.attr("query_type", "sql");
        element.attr("action_type", "function");
        element.attr("db", config.dbName);
        element.attr("pg_schema", config.schema);
        element.attr("db_type", config.dbType);
    }

    private void finalizeDatasetElement(Element element, cmpDataset.DatasetConfig config, Base base) {
        element.empty();
        base.attr("query_type", "sql");
        base.attr("db_type", config.dbType);
        base.attr("pg_schema", config.schema);
        base.attr("db", config.dbName);
        base.attr("name", config.name);
        //base.attr("action", element.attr("action"));
        base.attr("action_type", "function");
    }

    private void finalizeActionElement(Element element, cmpAction.ActionConfig config, Base base) {
        element.empty();
        base.attr("query_type", "sql");
        base.attr("db_type", config.dbType);
        base.attr("pg_schema", config.schema);
        base.attr("db", config.dbName);
        base.attr("name", config.name);
        base.attr("action_type", "function");
    }

    private String buildDatasetVarsJson(List<DatasetFunctionVar> variables) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < variables.size(); i++) {
            DatasetFunctionVar v = variables.get(i);
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

    private String buildActionVarsJson(List<ActionFunctionVar> variables) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < variables.size(); i++) {
            ActionFunctionVar v = variables.get(i);
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

    private boolean checkIfReturnsSet(Connection conn, String schema, String functionName) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT pg_proc.prorettype = 'pg_catalog.setof_record'::regtype OR " +
                        "EXISTS (SELECT 1 FROM pg_proc p JOIN pg_type t ON p.prorettype = t.oid " +
                        "WHERE p.proname = ? AND p.pronamespace = (SELECT oid FROM pg_namespace WHERE nspname = ?) " +
                        "AND t.typtype = 'c')")) {
            ps.setString(1, functionName);
            ps.setString(2, schema);
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getBoolean(1);
        } catch (SQLException e) {
            return false;
        }
    }

    private String buildFunctionCall(String functionName, String schema, List<String> varNames,
                                     Map<String, String> varDirections, boolean returnsSet) {
        StringBuilder params = new StringBuilder();
        for (int i = 0; i < varNames.size(); i++) {
            if (i > 0) params.append(",");
            params.append("?");
        }
        if (returnsSet) {
            return "SELECT * FROM " + schema + "." + functionName + "(" + params.toString() + ")";
        } else {
            return "{? = call " + schema + "." + functionName + "(" + params.toString() + ")}";
        }
    }

    private String buildProcedureCall(String functionName, String schema, List<String> varNames,
                                      Map<String, String> varDirections) {
        StringBuilder params = new StringBuilder();
        for (int i = 0; i < varNames.size(); i++) {
            if (i > 0) params.append(",");
            params.append("?");
        }
        return "CALL " + schema + "." + functionName + "(" + params.toString() + ")";
    }

    private JSONArray resultSetToJSONArray(ResultSet rs) throws SQLException {
        JSONArray result = new JSONArray();
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();

        while (rs.next()) {
            JSONObject row = new JSONObject();
            for (int i = 1; i <= columnCount; i++) {
                row.put(meta.getColumnLabel(i), rs.getObject(i));
            }
            result.put(row);
        }
        return result;
    }

    private void registerOutParameter(CallableStatement cs, int idx, String type) throws SQLException {
        switch (type.toLowerCase()) {
            case "int": case "integer": cs.registerOutParameter(idx, Types.INTEGER); break;
            case "long": case "bigint": cs.registerOutParameter(idx, Types.BIGINT); break;
            case "decimal": case "numeric": cs.registerOutParameter(idx, Types.NUMERIC); break;
            case "bool": case "boolean": cs.registerOutParameter(idx, Types.BOOLEAN); break;
            case "date": cs.registerOutParameter(idx, Types.DATE); break;
            case "timestamp": cs.registerOutParameter(idx, Types.TIMESTAMP); break;
            case "json": case "jsonb": cs.registerOutParameter(idx, Types.OTHER); break;
            default: cs.registerOutParameter(idx, Types.VARCHAR);
        }
    }

    private void setParameter(CallableStatement cs, int idx, String value, String type, Connection conn) throws SQLException {
        if (value == null || value.isEmpty()) {
            cs.setNull(idx, Types.VARCHAR);
            return;
        }
        switch (type.toLowerCase()) {
            case "int": case "integer": cs.setInt(idx, Integer.parseInt(value)); break;
            case "long": case "bigint": cs.setLong(idx, Long.parseLong(value)); break;
            case "decimal": case "numeric": cs.setBigDecimal(idx, new java.math.BigDecimal(value)); break;
            case "bool": case "boolean": cs.setBoolean(idx, Boolean.parseBoolean(value)); break;
            case "json": case "jsonb": cs.setObject(idx, value, Types.OTHER); break;
            default: cs.setString(idx, value);
        }
    }

    private String getOutParameter(CallableStatement cs, int idx, String type) throws SQLException {
        switch (type.toLowerCase()) {
            case "int": case "integer": return cs.wasNull() ? "" : String.valueOf(cs.getInt(idx));
            case "long": case "bigint": return cs.wasNull() ? "" : String.valueOf(cs.getLong(idx));
            case "decimal": case "numeric":
                java.math.BigDecimal bd = cs.getBigDecimal(idx);
                return bd == null ? "" : bd.toString();
            case "bool": case "boolean": return cs.wasNull() ? "" : String.valueOf(cs.getBoolean(idx));
            case "json": case "jsonb":
                Object o = cs.getObject(idx);
                return o == null ? "" : o.toString();
            default:
                String s = cs.getString(idx);
                return s == null ? "" : s;
        }
    }

    private String getValueFromVars(JSONObject vars, Map<String, Object> session, String name) {
        if (!vars.has(name)) return "";
        Object val = vars.get(name);
        if (val instanceof JSONObject) {
            JSONObject obj = (JSONObject) val;
            if ("session".equals(obj.optString("srctype"))) {
                Object sessionVal = session.get(name);
                return sessionVal != null ? sessionVal.toString() : obj.optString("defaultVal", "");
            }
            return obj.optString("value", obj.optString("defaultVal", ""));
        }
        return val.toString();
    }

    private void updateVars(JSONObject vars, Map<String, Object> session, String name, String value) {
        if (vars.has(name) && vars.get(name) instanceof JSONObject) {
            JSONObject obj = vars.getJSONObject(name);
            if ("session".equals(obj.optString("srctype"))) {
                session.put(name, value);
            } else {
                obj.put("value", value);
            }
        } else {
            JSONObject wrapper = new JSONObject();
            wrapper.put("value", value);
            wrapper.put("src", name);
            wrapper.put("srctype", "var");
            vars.put(name, wrapper);
        }
    }

    private String buildJdbcUrlWithSchema(DatabaseConfig dbConfig, String schema) {
        String url = dbConfig.getJdbcUrl();
        if (!url.contains("currentSchema")) {
            url += (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        }
        return url;
    }

    private String escapeJson(String s) {
        return s.replace("'", "\\\\'");
    }

    private String removeAttr(Attributes attrs, String key, String defaultValue) {
        if (attrs.hasKey(key)) {
            String val = attrs.get(key);
            attrs.remove(key);
            return val;
        }
        return defaultValue;
    }

    // ================================ ВНУТРЕННИЕ КЛАССЫ ================================

    private static class BaseFunctionVar {
        String name, src, srctype, len, defaultVal, type, direction;
    }

    private static class DatasetFunctionVar extends BaseFunctionVar {}

    private static class ActionFunctionVar extends BaseFunctionVar {}
}