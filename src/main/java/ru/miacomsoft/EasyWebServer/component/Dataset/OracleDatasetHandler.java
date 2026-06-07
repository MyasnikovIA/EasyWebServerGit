package ru.miacomsoft.EasyWebServer.component.Dataset;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import ru.miacomsoft.EasyWebServer.DatabaseConfig;
import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.OracleQuery;
import ru.miacomsoft.EasyWebServer.component.Base;
import ru.miacomsoft.EasyWebServer.component.cmpDataset;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class OracleDatasetHandler {

    public static final OracleDatasetHandler INSTANCE = new OracleDatasetHandler();

    public final Map<String, HashMap<String, Object>> procedureList = new ConcurrentHashMap<>();

    private OracleDatasetHandler() {}

    // ========== ИНИЦИАЛИЗАЦИЯ ==========
    public void handleOracleDataset(Document doc, Element element, cmpDataset.DatasetConfig config, Base base) {
        System.out.println("Oracle dataset mode: " + config.name);

        if (config.dbConfig == null) {
            System.err.println("Database config not found for: " + config.dbName);
            if (base != null) {
                base.attr("error", "Database configuration not found for: " + config.dbName);
            }
            return;
        }

        String sqlContent = element.hasText() ? element.text().trim() : "";

        saveToCache(config.name, config, sqlContent);

        setOracleDatasetAttributes(element, config);
        if (base != null) {
            base.attr("query_type", config.queryType);
            base.attr("db_type", config.dbType);
            base.attr("pg_schema", config.schema);
            base.attr("db", config.dbName);
            base.attr("name", config.name);
        }
    }

    @SuppressWarnings("unchecked")
    private void saveToCache(String name, cmpDataset.DatasetConfig config, String sqlContent) {
        HashMap<String, Object> param = new HashMap<>();
        param.put("SQL", sqlContent);
        param.put("SQL_RAW", sqlContent);
        param.put("dbConfig", config.dbConfig);
        param.put("schema", config.schema);
        param.put("dbName", config.dbName);
        param.put("dbType", config.dbType);
        param.put("query_type", "sql");
        param.put("isOracle", true);
        param.put("contentHash", getShortHash(sqlContent));

        // Сохраняем переменные
        if (config.variables != null && !config.variables.isEmpty()) {
            param.put("variables", config.variables);
            param.put("varTypes", createVarTypeMap(config.variables));
        }

        procedureList.put(name, param);
        System.out.println("Oracle dataset cached: " + name);
    }

    private Map<String, String> createVarTypeMap(List<cmpDataset.DatasetVar> variables) {
        Map<String, String> types = new HashMap<>();
        if (variables != null) {
            for (cmpDataset.DatasetVar var : variables) {
                types.put(var.name, var.type);
            }
        }
        return types;
    }

    // ========== ВЫПОЛНЕНИЕ ==========
    @SuppressWarnings("unchecked")
    public void executeOracleQuery(HttpExchange query, JSONObject result, String datasetName,
                                   String dbName, JSONObject vars, boolean debugMode) {
        System.out.println("=== executeOracleDataset: " + datasetName + " ===");

        HashMap<String, Object> param = procedureList.get(datasetName);
        if (param == null) {
            result.put("ERROR", "Dataset not found: " + datasetName);
            return;
        }

        String sqlContent = (String) param.get("SQL_RAW");
        DatabaseConfig dbConfig = (DatabaseConfig) param.get("dbConfig");

        List<cmpDataset.DatasetVar> variables = (List<cmpDataset.DatasetVar>) param.get("variables");
        Map<String, String> varTypes = (Map<String, String>) param.get("varTypes");

        if (dbConfig == null) {
            result.put("ERROR", "Database configuration not found");
            return;
        }

        // Преобразуем :param в ?
        String processedSql = sqlContent;
        List<String> paramNames = new ArrayList<>();

        if (variables != null && !variables.isEmpty()) {
            for (cmpDataset.DatasetVar var : variables) {
                String placeholder = ":" + var.name;
                if (processedSql.contains(placeholder)) {
                    processedSql = processedSql.replace(placeholder, "?");
                    paramNames.add(var.name);
                }
            }
        }

        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try {
            conn = OracleQuery.getConnect(dbConfig);
            if (conn == null) {
                result.put("ERROR", "Oracle connection failed");
                return;
            }

            pstmt = conn.prepareStatement(processedSql);

            // Устанавливаем параметры
            if (!paramNames.isEmpty()) {
                int idx = 1;
                for (String pname : paramNames) {
                    String value = getValueFromVars(vars, query.session, pname, getVariableDefault(pname, variables));
                    String type = getVariableType(pname, variables, varTypes);
                    setParameter(pstmt, idx, value, type);
                    idx++;
                }
            }

            rs = pstmt.executeQuery();
            JSONArray dataArray = resultSetToJSONArray(rs);
            result.put("data", dataArray);

            if (debugMode) {
                result.put("sql", processedSql);
                result.put("db", dbName);
            }

        } catch (SQLException e) {
            result.put("ERROR", "Oracle SQL Error: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            result.put("ERROR", "Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignore) {}
            try { if (pstmt != null) pstmt.close(); } catch (Exception ignore) {}
            OracleQuery.releaseConnection(conn);
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
        element.empty();
    }

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

    private String getShortHash(String input) {
        String hash = getMd5Hash(input);
        return hash.length() > 8 ? hash.substring(0, 8) : hash;
    }

    private String getMd5Hash(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    private String getVariableDefault(String name, List<cmpDataset.DatasetVar> variables) {
        if (variables == null) return "";
        for (cmpDataset.DatasetVar var : variables) {
            if (var.name.equals(name)) return var.defaultVal != null ? var.defaultVal : "";
        }
        return "";
    }

    private String getVariableType(String name, List<cmpDataset.DatasetVar> variables, Map<String, String> varTypes) {
        if (varTypes != null && varTypes.containsKey(name)) {
            return varTypes.get(name);
        }
        if (variables != null) {
            for (cmpDataset.DatasetVar var : variables) {
                if (var.name.equals(name)) return var.type != null ? var.type : "string";
            }
        }
        return "string";
    }

    private void setParameter(PreparedStatement pstmt, int idx, String value, String type) throws SQLException {
        if (value == null || value.isEmpty()) {
            pstmt.setNull(idx, Types.VARCHAR);
            return;
        }
        switch (type.toLowerCase()) {
            case "int": case "integer":
                pstmt.setInt(idx, Integer.parseInt(value));
                break;
            case "long": case "bigint":
                pstmt.setLong(idx, Long.parseLong(value));
                break;
            case "double": case "float":
                pstmt.setDouble(idx, Double.parseDouble(value));
                break;
            default:
                pstmt.setString(idx, value);
        }
    }

    private String getValueFromVars(JSONObject vars, Map<String, Object> session, String name, String defaultValue) {
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
}