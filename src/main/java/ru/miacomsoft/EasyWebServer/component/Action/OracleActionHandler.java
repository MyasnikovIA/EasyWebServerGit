package ru.miacomsoft.EasyWebServer.component.Action;

import org.json.JSONObject;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import ru.miacomsoft.EasyWebServer.DatabaseConfig;
import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.OracleQuery;
import ru.miacomsoft.EasyWebServer.ServerConstant;
import ru.miacomsoft.EasyWebServer.component.Base;

import java.math.BigDecimal;
import java.sql.*;
import java.sql.Date;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import ru.miacomsoft.EasyWebServer.component.cmpAction;

public class OracleActionHandler {

    public static final OracleActionHandler INSTANCE = new OracleActionHandler();

    // Кэши Oracle
    public final Map<String, HashMap<String, Object>> procedureList = new ConcurrentHashMap<>();
    private final Map<String, Boolean> functionExistsCache = new HashMap<>();
    private final Map<String, Boolean> schemaExistsCache = new HashMap<>();
    private final Map<String, Boolean> databaseExistsCache = new HashMap<>();
    private final Map<String, String> functionContentHashCache = new ConcurrentHashMap<>();
    private final Map<String, Long> databaseCheckTimestamp = new HashMap<>();
    private static final long CACHE_TTL = 60000;

    private OracleActionHandler() {}

    // ========== ИНИЦИАЛИЗАЦИЯ ==========
    public void handleOracleAction(Document doc, Element element, cmpAction.ActionConfig config, Base base) {

        System.out.println("Oracle mode: " + config.name);
        List<OracleVar> variables = parseVariables(element);
        String sqlContent = element.hasText() ? element.text().trim() : "";
        ParsedSql parsed = parseNamedParameters(sqlContent);

        HashMap<String, Object> param = createBaseParam(variables, config);
        param.put("SQL", parsed.processedSql);
        param.put("SQL_RAW", sqlContent);
        param.put("SQL_PARAMS", parsed.paramNames);

        String fullName = config.schema + "." + config.name;
        procedureList.put(fullName, param);
        procedureList.put(config.name, param);

        setOracleComponentAttributes(element, config, variables);
        finalizeElement(element, config, base);
    }

    // ========== ВЫПОЛНЕНИЕ ==========
    public void executeOracleAction(HttpExchange query, JSONObject result, String actionName,
                                    String dbName, JSONObject vars, boolean debugMode) {
        System.out.println("=== executeOracleAction: " + actionName + " ===");
        String pgSchema = query.requestParam.optString("pg_schema", "DEV");
        String fullName = pgSchema + "." + actionName;

        DatabaseConfig dbConfig = findOracleConfig(dbName);
        if (dbConfig == null) {
            result.put("ERROR", "Oracle config not found for: " + dbName);
            return;
        }

        HashMap<String, Object> param = null;
        if (procedureList.containsKey(fullName))
            param = procedureList.get(fullName);
        else if (procedureList.containsKey(actionName))
            param = procedureList.get(actionName);
        else {
            result.put("ERROR", "Action not found: " + actionName + " (tried: " + fullName + ")");
            return;
        }

        String sql = (String) param.get("SQL");
        List<String> sqlParamNames = (List<String>) param.get("SQL_PARAMS");
        Map<String, String> varTypes = (Map<String, String>) param.get("varTypes");
        Map<String, String> varDirections = (Map<String, String>) param.get("varDirections");

        try (Connection conn = OracleQuery.getConnect(dbConfig);
             CallableStatement cs = conn.prepareCall(sql)) {
            if (conn == null) { result.put("ERROR", "Oracle connection failed"); return; }

            int idx = 1;
            for (String pname : sqlParamNames) {
                String dir = varDirections.getOrDefault(pname, "IN");
                if ("OUT".equals(dir) || "INOUT".equals(dir))
                    registerOracleOutParameter(cs, idx, varTypes.getOrDefault(pname, "string"));
                idx++;
            }
            idx = 1;
            for (String pname : sqlParamNames) {
                String dir = varDirections.getOrDefault(pname, "IN");
                if ("IN".equals(dir) || "INOUT".equals(dir)) {
                    String value = getValueFromVars(vars, query.session, pname);
                    setOracleParameter(cs, idx, value, varTypes.getOrDefault(pname, "string"));
                }
                idx++;
            }
            cs.execute();
            idx = 1;
            for (String pname : sqlParamNames) {
                String dir = varDirections.getOrDefault(pname, "IN");
                if ("OUT".equals(dir) || "INOUT".equals(dir)) {
                    String outValue = getOracleOutParameter(cs, idx, varTypes.getOrDefault(pname, "string"));
                    updateVars(vars, query.session, pname, outValue);
                }
                idx++;
            }
            result.put("vars", vars);
            if (debugMode) result.put("SQL", sql);
        } catch (SQLException e) {
            result.put("ERROR", "Oracle SQL Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ========== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ (специфичные для Oracle) ==========
    private List<OracleVar> parseVariables(Element element) {
        List<OracleVar> vars = new ArrayList<>();
        for (Element child : element.children()) {
            String tag = child.tag().toString().toLowerCase();
            if (tag.contains("var") || tag.contains("cmpactionvar"))
                vars.add(parseActionVar(child));
        }
        return vars;
    }

    private OracleVar parseActionVar(Element element) {
        Attributes attrs = element.attributes();
        OracleVar var = new OracleVar();
        var.name = attrs.get("name");
        var.src = baseRemoveArrKeyRtrn(attrs, "src", var.name);
        var.srctype = baseRemoveArrKeyRtrn(attrs, "srctype", "var");
        var.len = baseRemoveArrKeyRtrn(attrs, "len", "");
        var.defaultVal = baseRemoveArrKeyRtrn(attrs, "default", "");
        var.type = baseRemoveArrKeyRtrn(attrs, "type", "string");
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
        return param;
    }

    private void setOracleComponentAttributes(Element element, cmpAction.ActionConfig config, List<OracleVar> variables) {
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
        base.attr("query_type", config.queryType);
        base.attr("db_type", config.dbType);
        base.attr("pg_schema", config.schema);
        base.attr("db", config.dbName);
        base.attr("name", config.name);
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
        if (sql == null || sql.isEmpty()) return new ParsedSql(sql, Collections.emptyList());
        StringBuilder processed = new StringBuilder();
        List<String> paramNames = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inParam = false, inQuote = false;
        char quoteChar = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if ((c == '\'' || c == '"') && (i == 0 || sql.charAt(i-1) != '\\')) {
                if (!inQuote) { inQuote = true; quoteChar = c; }
                else if (quoteChar == c) inQuote = false;
            }
            if (inQuote) { processed.append(c); continue; }
            if (c == ':' && !inParam) { inParam = true; current = new StringBuilder(); }
            else if (inParam && (Character.isLetterOrDigit(c) || c == '_')) current.append(c);
            else if (inParam) {
                inParam = false;
                if (current.length() > 0) paramNames.add(current.toString());
                processed.append('?').append(c);
            } else processed.append(c);
        }
        if (inParam && current.length() > 0) { paramNames.add(current.toString()); processed.append('?'); }
        return new ParsedSql(processed.toString(), paramNames);
    }

    private DatabaseConfig findOracleConfig(String dbName) {
        DatabaseConfig cfg = ServerConstant.config.getDatabaseConfig(dbName);
        if (cfg != null && "oci8".equals(cfg.getType())) return cfg;
        cfg = ServerConstant.config.getDatabaseConfig("oracle_test");
        if (cfg != null && "oci8".equals(cfg.getType())) return cfg;
        for (DatabaseConfig c : ServerConstant.config.DATABASES.values())
            if ("oci8".equals(c.getType())) return c;
        return null;
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
            if ("session".equals(obj.optString("srctype")))
                session.put(name, value);
            else
                obj.put("value", value);
        } else {
            JSONObject wrapper = new JSONObject();
            wrapper.put("value", value);
            wrapper.put("src", name);
            wrapper.put("srctype", "var");
            vars.put(name, wrapper);
        }
    }

    private void setOracleParameter(CallableStatement cs, int idx, String value, String type) throws SQLException {
        if (value == null || value.isEmpty()) { cs.setNull(idx, getOracleSqlType(type)); return; }
        switch (type.toLowerCase()) {
            case "int": case "integer": cs.setInt(idx, Integer.parseInt(value)); break;
            case "long": case "bigint": cs.setLong(idx, Long.parseLong(value)); break;
            case "decimal": case "numeric": cs.setBigDecimal(idx, new BigDecimal(value)); break;
            case "bool": case "boolean": cs.setBoolean(idx, Boolean.parseBoolean(value)); break;
            case "date": cs.setDate(idx, Date.valueOf(value)); break;
            case "timestamp": cs.setTimestamp(idx, Timestamp.valueOf(value.replace("T", " "))); break;
            default: cs.setString(idx, value);
        }
    }

    private void registerOracleOutParameter(CallableStatement cs, int idx, String type) throws SQLException {
        cs.registerOutParameter(idx, getOracleSqlType(type));
    }

    private String getOracleOutParameter(CallableStatement cs, int idx, String type) throws SQLException {
        switch (type.toLowerCase()) {
            case "int": case "integer": return cs.wasNull() ? "" : String.valueOf(cs.getInt(idx));
            case "long": case "bigint": return cs.wasNull() ? "" : String.valueOf(cs.getLong(idx));
            case "decimal": case "numeric":
                BigDecimal bd = cs.getBigDecimal(idx);
                return bd == null ? "" : bd.toString();
            case "bool": case "boolean": return cs.wasNull() ? "" : String.valueOf(cs.getBoolean(idx));
            case "date": Date d = cs.getDate(idx); return d == null ? "" : d.toString();
            case "timestamp": Timestamp ts = cs.getTimestamp(idx); return ts == null ? "" : ts.toString();
            default: String s = cs.getString(idx); return s == null ? "" : s;
        }
    }

    private int getOracleSqlType(String type) {
        switch (type.toLowerCase()) {
            case "int": case "integer": return Types.INTEGER;
            case "long": case "bigint": return Types.BIGINT;
            case "decimal": case "numeric": return Types.NUMERIC;
            case "bool": case "boolean": return Types.BOOLEAN;
            case "date": return Types.DATE;
            case "timestamp": return Types.TIMESTAMP;
            default: return Types.VARCHAR;
        }
    }

    private String escapeJson(String s) { return s.replace("'", "\\\\'"); }
    private String baseRemoveArrKeyRtrn(Attributes arr, String key, String defaultValue) {
        if (arr.hasKey(key)) { String val = arr.get(key); arr.remove(key); return val; }
        return defaultValue;
    }

    // DTO
    private static class OracleVar {
        String name, src, srctype, len, defaultVal, type, direction;
    }

    private static class ParsedSql {
        final String processedSql;
        final List<String> paramNames;
        ParsedSql(String sql, List<String> names) { this.processedSql = sql; this.paramNames = names; }
    }
}