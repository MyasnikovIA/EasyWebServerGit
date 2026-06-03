package ru.miacomsoft.EasyWebServer.component.Action;

import org.json.JSONObject;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.JavaStrExecut;
import ru.miacomsoft.EasyWebServer.ServerConstant;
import ru.miacomsoft.EasyWebServer.component.Base;
import ru.miacomsoft.EasyWebServer.component.cmpAction;

import java.util.*;

public class JavaActionHandler {

    public static final JavaActionHandler INSTANCE = new JavaActionHandler();

    // Кэш скомпилированных действий (имя -> параметры)
    public final Map<String, HashMap<String, Object>> procedureList = new HashMap<>();

    private JavaActionHandler() {}

    // ========== ИНИЦИАЛИЗАЦИЯ (компиляция Java-кода) ==========
    public void handleJavaAction(Document doc, Element element, cmpAction.ActionConfig config, Base base) {
        System.out.println("Java mode: " + config.name);

        List<JavaVar> variables = parseVariables(element);
        String javaCode = element.hasText() ? element.text().trim() : "";

        String functionName = generateJavaFunctionName(config);
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
            element.empty();
            element.append(JavaStrExecut.parseErrorCompile(infoCompile));
            element.removeAttr("style");
            return;
        }

        String fullFunctionName = ServerConstant.config.APP_NAME + "_" + functionName;
        HashMap<String, Object> param = createBaseParam(variables, config);
        param.put("JAVA_CODE", javaCode);
        param.put("FUNCTION_NAME", fullFunctionName);
        param.put("query_type", "java");

        procedureList.put(config.name, param);
        procedureList.put(fullFunctionName, param);
        System.out.println("Java action compiled: " + config.name + " -> " + fullFunctionName);

        setJavaComponentAttributes(element, config, variables, functionName);
        finalizeElement(element, config, base);
    }

    // ========== ВЫПОЛНЕНИЕ Java-действия ==========
    public void executeJavaAction(HttpExchange query, JSONObject result, String actionName,
                                  JSONObject vars, Map<String, Object> session) {
        System.out.println("=== executeJavaAction: " + actionName + " ===");
        HashMap<String, Object> param = procedureList.get(actionName);
        if (param == null) {
            result.put("ERROR", "Java action not found: " + actionName);
            return;
        }

        String fullFunctionName = (String) param.get("FUNCTION_NAME");
        if (fullFunctionName == null) {
            result.put("ERROR", "FUNCTION_NAME missing for: " + actionName);
            return;
        }

        // Подготовка входных переменных
        JSONObject callVars = new JSONObject();
        List<String> varNames = (List<String>) param.get("vars");
        if (varNames != null) {
            for (String varName : varNames) {
                String value = "";
                if (vars.has(varName)) {
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
                if (val instanceof JSONObject)
                    callVars.put(key, ((JSONObject) val).optString("value", ""));
                else
                    callVars.put(key, val.toString());
            }
        }

        JavaStrExecut javaExecutor = new JavaStrExecut();
        JSONObject res = javaExecutor.runFunction(fullFunctionName, callVars, session, null);

        if (res.has("JAVA_ERROR")) {
            result.put("ERROR", res.get("JAVA_ERROR"));
        } else {
            if (varNames != null) {
                for (String varName : varNames) {
                    if (res.has(varName))
                        updateVars(vars, session, varName, res.get(varName).toString());
                }
            } else {
                Iterator<String> keys = res.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    updateVars(vars, session, key, res.get(key).toString());
                }
            }
            result.put("vars", vars);
        }
    }

    // ========== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ (специфичные для Java) ==========
    private List<JavaVar> parseVariables(Element element) {
        List<JavaVar> vars = new ArrayList<>();
        for (Element child : element.children()) {
            String tag = child.tag().toString().toLowerCase();
            if (tag.contains("var") || tag.contains("cmpactionvar"))
                vars.add(parseActionVar(child));
        }
        return vars;
    }

    private JavaVar parseActionVar(Element element) {
        Attributes attrs = element.attributes();
        JavaVar var = new JavaVar();
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

    private HashMap<String, Object> createBaseParam(List<JavaVar> variables, cmpAction.ActionConfig config) {
        HashMap<String, Object> param = new HashMap<>();
        List<String> names = new ArrayList<>();
        Map<String, String> types = new HashMap<>();
        Map<String, String> dirs = new HashMap<>();
        for (JavaVar v : variables) {
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

    private void setJavaComponentAttributes(Element element, cmpAction.ActionConfig config,
                                            List<JavaVar> variables, String functionName) {
        element.attr("style", "display:none");
        element.attr("action_name", functionName);
        element.attr("name", config.name);
        element.attr("vars", buildVarsJson(variables));
        element.attr("query_type", "java");
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

    private String buildVarsJson(List<JavaVar> variables) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < variables.size(); i++) {
            JavaVar v = variables.get(i);
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

    private String generateJavaFunctionName(cmpAction.ActionConfig config) {
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
    private String baseRemoveArrKeyRtrn(Attributes arr, String key, String defaultValue) {
        if (arr.hasKey(key)) { String val = arr.get(key); arr.remove(key); return val; }
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

    // DTO
    private static class JavaVar {
        String name, src, srctype, len, defaultVal, type, direction;
    }
}