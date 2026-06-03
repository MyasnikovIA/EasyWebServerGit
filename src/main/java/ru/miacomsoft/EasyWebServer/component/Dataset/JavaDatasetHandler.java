package ru.miacomsoft.EasyWebServer.component.Dataset;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.JavaStrExecut;
import ru.miacomsoft.EasyWebServer.ServerConstant;
import ru.miacomsoft.EasyWebServer.component.Base;
import ru.miacomsoft.EasyWebServer.component.cmpDataset;

import java.util.*;

public class JavaDatasetHandler {

    public static final JavaDatasetHandler INSTANCE = new JavaDatasetHandler();

    // Кэш для Java-функций (имя -> параметры)
    public final Map<String, HashMap<String, Object>> procedureList = new HashMap<>();

    private JavaDatasetHandler() {}

    public void handleJavaDataset(Document doc, Element element, cmpDataset.DatasetConfig config, Base base) {
        System.out.println("Java dataset mode: " + config.name);

        List<DatasetVar> variables = parseVariables(element);
        String javaCode = element.hasText() ? element.text().trim() : "";

        String functionName = generateFunctionName(config);
        ArrayList<String> jarResourse = new ArrayList<>();
        ArrayList<String> importPacket = new ArrayList<>();

        for (Element child : element.children()) {
            String tagName = child.tag().toString().toLowerCase();
            if (tagName.contains("import")) {
                Attributes attrs = child.attributes();
                if (attrs.hasKey("path")) jarResourse.add(attrs.get("path"));
                if (attrs.hasKey("packet")) importPacket.add(attrs.get("packet"));
            }
        }

        JSONObject infoCompile = new JSONObject();
        JavaStrExecut javaCompiler = new JavaStrExecut();
        if (!javaCompiler.compile(functionName, importPacket, jarResourse, javaCode, infoCompile)) {
            base.removeAttr("style");
            base.html(ru.miacomsoft.EasyWebServer.JavaStrExecut.parseErrorCompile(infoCompile));
            return;
        }

        String fullFunctionName = ServerConstant.config.APP_NAME + "_" + functionName;
        HashMap<String, Object> param = createBaseParam(variables, config);
        param.put("JAVA_CODE", javaCode);
        param.put("FUNCTION_NAME", fullFunctionName);
        param.put("query_type", "java");

        procedureList.put(config.name, param);
        procedureList.put(fullFunctionName, param);
        System.out.println("Java dataset compiled: " + config.name + " -> " + fullFunctionName);

        setJavaDatasetAttributes(element, config, variables, functionName);
        finalizeElement(element, config, base);
    }

    public void executeJavaDataset(HttpExchange query, JSONObject result, String datasetName,
                                   JSONObject vars, Map<String, Object> session, boolean debugMode) {
        System.out.println("=== executeJavaDataset: " + datasetName + " ===");
        HashMap<String, Object> param = procedureList.get(datasetName);
        if (param == null) {
            result.put("ERROR", "Java dataset not found: " + datasetName);
            return;
        }

        String fullFunctionName = (String) param.get("FUNCTION_NAME");
        if (fullFunctionName == null) {
            result.put("ERROR", "FUNCTION_NAME missing for: " + datasetName);
            return;
        }

        // Подготовка входных переменных
        JSONObject callVars = new JSONObject();
        List<String> varNames = (List<String>) param.get("vars");
        if (varNames != null) {
            for (String varName : varNames) {
                String value = "";
                if (vars.has(varName) && vars.get(varName) instanceof JSONObject) {
                    JSONObject obj = vars.getJSONObject(varName);
                    value = obj.optString("value", obj.optString("defaultVal", ""));
                }
                callVars.put(varName, value);
            }
        } else {
            Iterator<String> keys = vars.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object val = vars.get(key);
                if (val instanceof JSONObject) {
                    callVars.put(key, ((JSONObject) val).optString("value", ""));
                } else {
                    callVars.put(key, val.toString());
                }
            }
        }

        JavaStrExecut javaExecutor = new JavaStrExecut();
        JSONArray dataRes = new JSONArray();
        JSONObject res = javaExecutor.runFunction(fullFunctionName, callVars, session, dataRes);

        if (res.has("JAVA_ERROR")) {
            result.put("ERROR", res.get("JAVA_ERROR"));
            if (debugMode && param.containsKey("JAVA_CODE")) {
                result.put("java_code", param.get("JAVA_CODE"));
            }
        } else {
            result.put("data", dataRes);
            // Обновляем vars, если есть выходные переменные
            if (varNames != null) {
                for (String varName : varNames) {
                    if (res.has(varName)) {
                        updateVars(vars, session, varName, res.get(varName).toString());
                    }
                }
            } else {
                Iterator<String> keys = res.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    if (!"JAVA_ERROR".equals(key) && !"data".equals(key)) {
                        updateVars(vars, session, key, res.get(key).toString());
                    }
                }
            }
            result.put("vars", vars);
            if (debugMode && param.containsKey("JAVA_CODE")) {
                result.put("java_code", param.get("JAVA_CODE"));
            }
        }
    }

    // ========== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ==========
    private List<DatasetVar> parseVariables(Element element) {
        List<DatasetVar> vars = new ArrayList<>();
        for (Element child : element.children()) {
            String tag = child.tag().toString().toLowerCase();
            if (tag.contains("var") || tag.contains("cmpdatasetvar")) {
                vars.add(parseActionVar(child));
            }
        }
        return vars;
    }

    private DatasetVar parseActionVar(Element element) {
        Attributes attrs = element.attributes();
        DatasetVar var = new DatasetVar();
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

    private HashMap<String, Object> createBaseParam(List<DatasetVar> variables, cmpDataset.DatasetConfig config) {
        HashMap<String, Object> param = new HashMap<>();
        List<String> names = new ArrayList<>();
        Map<String, String> types = new HashMap<>();
        Map<String, String> dirs = new HashMap<>();
        for (DatasetVar v : variables) {
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

    private void setJavaDatasetAttributes(Element element, cmpDataset.DatasetConfig config,
                                          List<DatasetVar> variables, String functionName) {
        element.attr("style", "display:none");
        element.attr("dataset_name", functionName);
        element.attr("name", config.name);
        element.attr("vars", buildVarsJson(variables));
        element.attr("query_type", "java");
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

    private String buildVarsJson(List<DatasetVar> variables) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < variables.size(); i++) {
            DatasetVar v = variables.get(i);
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

    private String generateFunctionName(cmpDataset.DatasetConfig config) {
        String relativePath = getRelativePath(config.docPath, config.rootPath);
        String pathHash = getMd5Hash(relativePath);
        if (pathHash.length() > 8) pathHash = pathHash.substring(0, 8);
        if (pathHash.length() > 0 && Character.isDigit(pathHash.charAt(0))) pathHash = "f" + pathHash;
        String baseName = pathHash + "_java_" + config.name;
        if (baseName.length() > 60) baseName = baseName.substring(0, 60);
        if (baseName.length() > 0 && Character.isDigit(baseName.charAt(0))) baseName = "f_" + baseName;
        return baseName.toLowerCase();
    }

    private String getRelativePath(String docPath, String rootPath) {
        if (docPath.length() <= rootPath.length() || docPath.length() <= 5) return "";
        String relative = docPath.substring(rootPath.length(), docPath.length() - 5);
        return relative.replaceAll("[/\\\\]", "_").replaceAll("[^a-zA-Z0-9_]", "");
    }

    private String getMd5Hash(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return String.valueOf(input.hashCode()); }
    }

    private String escapeJson(String s) { return s.replace("'", "\\\\'"); }
    private String removeAttr(Attributes attrs, String key, String defaultValue) {
        if (attrs.hasKey(key)) { String val = attrs.get(key); attrs.remove(key); return val; }
        return defaultValue;
    }

    private void updateVars(JSONObject vars, Map<String, Object> session, String name, String value) {
        if (vars.has(name) && vars.get(name) instanceof JSONObject) {
            vars.getJSONObject(name).put("value", value);
        } else {
            JSONObject wrapper = new JSONObject();
            wrapper.put("value", value);
            wrapper.put("src", name);
            wrapper.put("srctype", "var");
            vars.put(name, wrapper);
        }
    }

    private static class DatasetVar {
        String name, src, srctype, len, defaultVal, type, direction;
    }
}