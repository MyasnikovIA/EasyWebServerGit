package ru.miacomsoft.EasyWebServer.component.Action;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import ru.miacomsoft.EasyWebServer.DatabaseConfig;
import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.OracleQuery;
import ru.miacomsoft.EasyWebServer.component.Base;
import ru.miacomsoft.EasyWebServer.component.cmpAction;

import java.math.BigDecimal;
import java.sql.*;
import java.sql.Date;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class OracleActionHandler {

    public static final OracleActionHandler INSTANCE = new OracleActionHandler();
    public final Map<String, HashMap<String, Object>> procedureList = new ConcurrentHashMap<>();
    private OracleActionHandler() {}
    public void handleOracleAction(Document doc, Element element, cmpAction.ActionConfig config, Base base) {
        System.out.println("=== handleOracleAction (cache only) for: " + config.name);

        if (config.dbConfig == null) {
            System.err.println("Database config not found for: " + config.dbName);
            if (base != null) {
                base.attr("error", "Database configuration not found for: " + config.dbName);
            }
            return;
        }

        String sqlContent = element.hasText() ? element.text().trim() : "";
        List<OracleVar> variables = parseVariables(element);
        saveToCache(config.name, config, variables, sqlContent);
        setComponentAttributes(element, config, variables);
        finalizeElement(element, config, base);
    }

    /**
     * Сохранение конфигурации действия в кэш
     */
    private void saveToCache(String name, cmpAction.ActionConfig config,
                             List<OracleVar> variables, String sqlContent) {
        // Парсим именованные параметры в SQL
        ParsedSql parsed = parseNamedParameters(sqlContent);

        HashMap<String, Object> param = createBaseParam(variables, config);
        param.put("SQL", parsed.processedSql);
        param.put("SQL_RAW", sqlContent);
        param.put("SQL_PARAMS", parsed.paramNames);
        param.put("dbConfig", config.dbConfig);
        param.put("schema", config.schema);
        param.put("dbName", config.dbName);
        param.put("dbType", config.dbType);
        param.put("query_type", "sql");
        param.put("isOracle", true);
        param.put("variables", variables);

        procedureList.put(name, param);
        if (config.schema != null && !config.schema.isEmpty()) {
            procedureList.put(config.schema + "." + name, param);
        }

        System.out.println("Oracle action cached: " + name);
    }

    // ========== ВЫПОЛНЕНИЕ (ПРЯМОЕ ВЫПОЛНЕНИЕ SQL, БЕЗ СОЗДАНИЯ ПРОЦЕДУР) ==========
    public void executeOracleAction(HttpExchange query, JSONObject result, String actionName,
                                    String dbName, JSONObject vars, boolean debugMode) {
        System.out.println("=== executeOracleAction: " + actionName + " ===");

        // Получаем конфигурацию из кэша
        HashMap<String, Object> param = findActionInCache(actionName);
        if (param == null) {
            result.put("ERROR", "Action not found: " + actionName);
            return;
        }

        String sql = (String) param.get("SQL");
        List<String> sqlParamNames = (List<String>) param.get("SQL_PARAMS");
        List<OracleVar> variables = (List<OracleVar>) param.get("variables");
        DatabaseConfig dbConfig = (DatabaseConfig) param.get("dbConfig");

        if (dbConfig == null) {
            result.put("ERROR", "Database configuration not found");
            return;
        }

        // Выполняем SQL напрямую (через OracleQuery.executeQuery или executeUpdate)
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try {
            conn = OracleQuery.getConnect(dbConfig);
            if (conn == null) {
                result.put("ERROR", "Oracle connection failed");
                return;
            }

            // Проверяем, это SELECT или UPDATE/INSERT/DELETE
            String sqlUpper = sql.trim().toUpperCase();
            boolean isSelect = sqlUpper.startsWith("SELECT") || sqlUpper.startsWith("WITH");
            boolean isCall = sqlUpper.startsWith("BEGIN") || sqlUpper.startsWith("DECLARE");

            if (isSelect) {
                // SELECT запрос - возвращаем данные
                pstmt = conn.prepareStatement(sql);

                // Устанавливаем параметры
                int idx = 1;
                for (String pname : sqlParamNames) {
                    String value = getValueFromVars(vars, query.session, pname, getVariableDefault(pname, variables));
                    setParameter(pstmt, idx, value, getVariableType(pname, variables));
                    idx++;
                }

                rs = pstmt.executeQuery();
                JSONArray dataArray = resultSetToJSONArray(rs);
                result.put("data", dataArray);

            } else if (isCall) {
                // PL/SQL блок - может содержать OUT параметры
                // Для Oracle используем CallableStatement
                String callSql = sql;
                CallableStatement cs = conn.prepareCall(callSql);

                // Регистрируем OUT параметры
                int idx = 1;
                for (String pname : sqlParamNames) {
                    String dir = getVariableDirection(pname, variables);
                    if ("OUT".equals(dir) || "INOUT".equals(dir)) {
                        String type = getVariableType(pname, variables);
                        registerOutParameter(cs, idx, type);
                    }
                    idx++;
                }

                // Устанавливаем IN параметры
                idx = 1;
                for (String pname : sqlParamNames) {
                    String dir = getVariableDirection(pname, variables);
                    if ("IN".equals(dir) || "INOUT".equals(dir)) {
                        String value = getValueFromVars(vars, query.session, pname, getVariableDefault(pname, variables));
                        String type = getVariableType(pname, variables);
                        setParameter(cs, idx, value, type);
                    }
                    idx++;
                }

                cs.execute();
                conn.commit();

                // Получаем OUT параметры
                JSONObject outVars = new JSONObject();
                idx = 1;
                for (String pname : sqlParamNames) {
                    String dir = getVariableDirection(pname, variables);
                    if ("OUT".equals(dir) || "INOUT".equals(dir)) {
                        String type = getVariableType(pname, variables);
                        String srctype = getVariableSrcType(pname, variables);
                        String outValue = getOutParameter(cs, idx, type);
                        updateVars(vars, query.session, pname, outValue, srctype);
                        outVars.put(pname, outValue);
                    }
                    idx++;
                }

                if (outVars.length() > 0) {
                    result.put("vars_out", outVars);
                }
                result.put("vars", vars);

                cs.close();

            } else {
                // UPDATE/INSERT/DELETE
                pstmt = conn.prepareStatement(sql);

                int idx = 1;
                for (String pname : sqlParamNames) {
                    String value = getValueFromVars(vars, query.session, pname, getVariableDefault(pname, variables));
                    setParameter(pstmt, idx, value, getVariableType(pname, variables));
                    idx++;
                }

                int affectedRows = pstmt.executeUpdate();
                conn.commit();
                result.put("affectedRows", affectedRows);
            }

            if (debugMode) {
                result.put("sql", sql);
                result.put("db", dbName);
            }

        } catch (SQLException e) {
            result.put("ERROR", "Oracle SQL Error: " + e.getMessage());
            e.printStackTrace();
            try { if (conn != null) conn.rollback(); } catch (SQLException ignored) {}
        } catch (Exception e) {
            result.put("ERROR", "Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignore) {}
            try { if (pstmt != null) pstmt.close(); } catch (Exception ignore) {}
            OracleQuery.releaseConnection(conn);
        }
    }

    /**
     * Поиск действия в кэше по имени
     */
    private HashMap<String, Object> findActionInCache(String actionName) {
        if (procedureList.containsKey(actionName)) {
            return procedureList.get(actionName);
        }
        for (Map.Entry<String, HashMap<String, Object>> entry : procedureList.entrySet()) {
            String key = entry.getKey();
            if (key.endsWith("." + actionName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Преобразование ResultSet в JSONArray
     */
    private JSONArray resultSetToJSONArray(ResultSet rs) throws SQLException {
        JSONArray result = new JSONArray();
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();

        while (rs.next()) {
            JSONObject row = new JSONObject();
            for (int i = 1; i <= columnCount; i++) {
                String columnName = meta.getColumnLabel(i);
                Object value = rs.getObject(i);
                row.put(columnName, value != null ? value : JSONObject.NULL);
            }
            result.put(row);
        }
        return result;
    }
    private List<OracleVar> parseVariables(Element element) {
        List<OracleVar> vars = new ArrayList<>();
        for (Element child : element.children()) {
            String tag = child.tag().toString().toLowerCase();
            if (tag.contains("var") || tag.contains("cmpactionvar")) {
                vars.add(parseActionVar(child));
            }
        }
        return vars;
    }

    private OracleVar parseActionVar(Element element) {
        Attributes attrs = element.attributes();
        OracleVar var = new OracleVar();
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

    private HashMap<String, Object> createBaseParam(List<OracleVar> variables, cmpAction.ActionConfig config) {
        HashMap<String, Object> param = new HashMap<>();
        List<String> names = new ArrayList<>();
        Map<String, String> types = new HashMap<>();
        Map<String, String> dirs = new HashMap<>();
        for (OracleVar v : variables) {
            names.add(v.name);
            types.put(v.name, v.type);
            dirs.put(v.name, v.direction);
        }
        param.put("vars", names);
        param.put("varTypes", types);
        param.put("varDirections", dirs);
        if (config != null) param.put("dbType", config.dbType);
        param.put("name", config != null ? config.name : null);
        return param;
    }

    private void setComponentAttributes(Element element, cmpAction.ActionConfig config, List<OracleVar> variables) {
        element.attr("style", "display:none");
        element.attr("action_name", config.name);
        element.attr("name", config.name);
        element.attr("vars", buildVarsJson(variables));
        element.attr("query_type", config.queryType);
        element.attr("db_type", config.dbType);
        element.attr("pg_schema", config.schema);
        element.attr("db", config.dbName);
    }

    private void finalizeElement(Element element, cmpAction.ActionConfig config, Base base) {
        element.empty();
        if (base != null) {
            base.attr("query_type", config.queryType);
            base.attr("db_type", config.dbType);
            base.attr("pg_schema", config.schema);
            base.attr("db", config.dbName);
            base.attr("name", config.name);
        }
    }

    private String buildVarsJson(List<OracleVar> variables) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < variables.size(); i++) {
            OracleVar v = variables.get(i);
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
    private ParsedSql parseNamedParameters(String sql) {
        if (sql == null || sql.isEmpty()) {
            return new ParsedSql(sql, Collections.emptyList());
        }
        StringBuilder processed = new StringBuilder();
        List<String> paramNames = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inParam = false, inQuote = false;
        char quoteChar = 0;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if ((c == '\'' || c == '"') && (i == 0 || sql.charAt(i-1) != '\\')) {
                if (!inQuote) {
                    inQuote = true;
                    quoteChar = c;
                } else if (quoteChar == c) {
                    inQuote = false;
                }
            }
            if (inQuote) {
                processed.append(c);
                continue;
            }
            if (c == ':' && !inParam) {
                inParam = true;
                current = new StringBuilder();
            } else if (inParam && (Character.isLetterOrDigit(c) || c == '_')) {
                current.append(c);
            } else if (inParam) {
                inParam = false;
                if (current.length() > 0) paramNames.add(current.toString());
                processed.append('?').append(c);
            } else {
                processed.append(c);
            }
        }
        if (inParam && current.length() > 0) {
            paramNames.add(current.toString());
            processed.append('?');
        }
        return new ParsedSql(processed.toString(), paramNames);
    }

    // ========== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ДЛЯ РАБОТЫ С ПЕРЕМЕННЫМИ ==========

    private String getVariableDirection(String name, List<OracleVar> variables) {
        for (OracleVar var : variables) {
            if (var.name.equals(name)) return var.direction;
        }
        return "IN";
    }

    private String getVariableType(String name, List<OracleVar> variables) {
        for (OracleVar var : variables) {
            if (var.name.equals(name)) return var.type;
        }
        return "string";
    }

    private String getVariableDefault(String name, List<OracleVar> variables) {
        for (OracleVar var : variables) {
            if (var.name.equals(name)) return var.defaultVal;
        }
        return "";
    }

    private String getVariableSrcType(String name, List<OracleVar> variables) {
        for (OracleVar var : variables) {
            if (var.name.equals(name)) return var.srctype;
        }
        return "var";
    }

    private void registerOutParameter(CallableStatement cs, int idx, String type) throws SQLException {
        switch (type.toLowerCase()) {
            case "int": case "integer": cs.registerOutParameter(idx, Types.INTEGER); break;
            case "long": case "bigint": cs.registerOutParameter(idx, Types.BIGINT); break;
            case "decimal": case "numeric": cs.registerOutParameter(idx, Types.NUMERIC); break;
            case "bool": case "boolean": cs.registerOutParameter(idx, Types.BOOLEAN); break;
            case "date": cs.registerOutParameter(idx, Types.DATE); break;
            case "timestamp": cs.registerOutParameter(idx, Types.TIMESTAMP); break;
            default: cs.registerOutParameter(idx, Types.VARCHAR);
        }
    }

    private void setParameter(PreparedStatement pstmt, int idx, String value, String type) throws SQLException {
        if (value == null || value.isEmpty()) {
            pstmt.setNull(idx, Types.VARCHAR);
            return;
        }
        switch (type.toLowerCase()) {
            case "int": case "integer": pstmt.setInt(idx, Integer.parseInt(value)); break;
            case "long": case "bigint": pstmt.setLong(idx, Long.parseLong(value)); break;
            case "decimal": case "numeric": pstmt.setBigDecimal(idx, new BigDecimal(value)); break;
            case "bool": case "boolean": pstmt.setBoolean(idx, Boolean.parseBoolean(value)); break;
            case "date": pstmt.setDate(idx, Date.valueOf(value)); break;
            case "timestamp": pstmt.setTimestamp(idx, Timestamp.valueOf(value.replace("T", " "))); break;
            default: pstmt.setString(idx, value);
        }
    }

    private String getOutParameter(CallableStatement cs, int idx, String type) throws SQLException {
        switch (type.toLowerCase()) {
            case "int": case "integer": return cs.wasNull() ? "" : String.valueOf(cs.getInt(idx));
            case "long": case "bigint": return cs.wasNull() ? "" : String.valueOf(cs.getLong(idx));
            case "decimal": case "numeric":
                BigDecimal bd = cs.getBigDecimal(idx);
                return bd == null ? "" : bd.toString();
            case "bool": case "boolean": return cs.wasNull() ? "" : String.valueOf(cs.getBoolean(idx));
            case "date": Date d = cs.getDate(idx); return d == null ? "" : d.toString();
            case "timestamp": Timestamp ts = cs.getTimestamp(idx); return ts == null ? "" : ts.toString();
            default:
                String s = cs.getString(idx);
                return s == null ? "" : s;
        }
    }

    private String getValueFromVars(JSONObject vars, Map<String, Object> session, String name, String defaultValue) {
        if (!vars.has(name)) return defaultValue != null ? defaultValue : "";
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

    private void updateVars(JSONObject vars, Map<String, Object> session, String name, String value, String srctype) {
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
    private static class OracleVar {
        String name, src, srctype, len, defaultVal, type, direction;
    }

    private static class ParsedSql {
        final String processedSql;
        final List<String> paramNames;
        ParsedSql(String sql, List<String> names) {
            this.processedSql = sql;
            this.paramNames = names;
        }
    }
}