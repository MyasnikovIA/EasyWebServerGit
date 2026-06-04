package ru.miacomsoft.EasyWebServer.component.Action;

import org.json.JSONArray;
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

    // Кэш для Java-действий (имя -> параметры) - сохраняется при инициализации страницы
    public final Map<String, HashMap<String, Object>> procedureList = new HashMap<>();

    // Кэш для проверки, скомпилирован ли уже класс
    private final Map<String, Boolean> compiledCache = new HashMap<>();

    private JavaActionHandler() {}

    // ========== ИНИЦИАЛИЗАЦИЯ (ТОЛЬКО СОХРАНЕНИЕ В КЭШ) ==========
    public void handleJavaAction(Document doc, Element element, cmpAction.ActionConfig config, Base base) {
        System.out.println("Java mode (cache only): " + config.name);

        String javaCode = element.hasText() ? element.text().trim() : "";
        List<JavaVar> variables = parseVariables(element);

        // Парсим импорты и jar ресурсы
        List<String> importPackets = new ArrayList<>();
        List<String> jarResources = new ArrayList<>();
        for (Element child : element.children()) {
            String tagName = child.tag().toString().toLowerCase();
            if (tagName.contains("import")) {
                Attributes attrs = child.attributes();
                if (attrs.hasKey("path")) jarResources.add(attrs.get("path"));
                if (attrs.hasKey("packet")) importPackets.add(attrs.get("packet"));
            }
        }

        // Сохраняем конфигурацию в кэш - НЕ КОМПИЛИРУЕМ
        saveToCache(config.name, config, variables, javaCode, importPackets, jarResources);

        setJavaComponentAttributes(element, config, variables);
        finalizeElement(element, config, base);
    }

    /**
     * Сохранение конфигурации действия в кэш без компиляции
     */
    private void saveToCache(String name, cmpAction.ActionConfig config,
                             List<JavaVar> variables, String javaCode,
                             List<String> importPackets, List<String> jarResources) {
        HashMap<String, Object> param = new HashMap<>();
        param.put("JAVA_CODE", javaCode);
        param.put("dbType", config.dbType);
        param.put("dbName", config.dbName);
        param.put("query_type", "java");
        param.put("variables", variables);
        param.put("importPackets", new ArrayList<>(importPackets)); // Convert to ArrayList
        param.put("jarResources", new ArrayList<>(jarResources));   // Convert to ArrayList
        param.put("contentHash", getShortHash(javaCode));

        procedureList.put(name, param);
        System.out.println("Java action cached (not compiled yet): " + name);
    }

    // ========== ВЫПОЛНЕНИЕ (С ЛЕНИВОЙ КОМПИЛЯЦИЕЙ) ==========
    public void executeJavaAction(HttpExchange query, JSONObject result, String actionName,
                                  JSONObject vars, Map<String, Object> session) {
        System.out.println("=== executeJavaAction: " + actionName + " ===");

        HashMap<String, Object> param = procedureList.get(actionName);
        if (param == null) {
            result.put("ERROR", "Java action not found: " + actionName);
            return;
        }

        String javaCode = (String) param.get("JAVA_CODE");
        String contentHash = (String) param.get("contentHash");

        @SuppressWarnings("unchecked")
        List<JavaVar> variables = (List<JavaVar>) param.get("variables");

        @SuppressWarnings("unchecked")
        List<String> importPackets = (List<String>) param.get("importPackets");
        if (importPackets == null) importPackets = new ArrayList<>();

        @SuppressWarnings("unchecked")
        List<String> jarResources = (List<String>) param.get("jarResources");
        if (jarResources == null) jarResources = new ArrayList<>();

        // Генерируем имя функции на основе имени действия и хэша содержимого
        String functionName = generateFunctionName(actionName, contentHash);
        String fullFunctionName = ServerConstant.config.APP_NAME + "_" + functionName;

        // Проверяем, скомпилирован ли уже класс
        boolean needsCompilation = isCompilationNeeded(functionName, contentHash);

        if (needsCompilation) {
            // Ленивая компиляция при первом вызове
            System.out.println("Compiling Java action on first call: " + actionName);

            JSONObject infoCompile = new JSONObject();
            JavaStrExecut javaCompiler = new JavaStrExecut();

            // Convert to ArrayList for compile method
            ArrayList<String> importPacketList = new ArrayList<>(importPackets);
            ArrayList<String> jarResourceList = new ArrayList<>(jarResources);

            if (!javaCompiler.compile(functionName, importPacketList, jarResourceList, javaCode, infoCompile)) {
                result.put("ERROR", "Compilation failed: " + infoCompile.toString());
                if (infoCompile.has("ERROR")) {
                    result.put("compile_errors", infoCompile.get("ERROR"));
                }
                return;
            }

            // Отмечаем как скомпилированный
            markAsCompiled(functionName, contentHash);
        }

        // Выполняем скомпилированное действие
        executeCompiledAction(query, result, functionName, variables, vars, session);
    }

    /**
     * Проверка, нужно ли компилировать (с учётом кэша и хэша)
     */
    private boolean isCompilationNeeded(String functionName, String contentHash) {
        String cacheKey = functionName + "_" + contentHash;

        if (compiledCache.containsKey(cacheKey)) {
            return !compiledCache.get(cacheKey);
        }

        // Проверяем через JavaStrExecut - возможно, уже скомпилировано от другого имени
        String fullFunctionName = ServerConstant.config.APP_NAME + "_" + functionName;
        boolean exists = JavaStrExecut.InstanceClassName.containsKey(fullFunctionName) ||
                JavaStrExecut.InstanceClassName.containsKey(functionName);

        if (exists) {
            compiledCache.put(cacheKey, true);
            return false;
        }

        compiledCache.put(cacheKey, false);
        return true;
    }

    /**
     * Отметка о компиляции
     */
    private void markAsCompiled(String functionName, String contentHash) {
        String cacheKey = functionName + "_" + contentHash;
        compiledCache.put(cacheKey, true);
    }

    /**
     * Выполнение скомпилированного действия
     */
    private void executeCompiledAction(HttpExchange query, JSONObject result, String functionName,
                                       List<JavaVar> variables, JSONObject vars,
                                       Map<String, Object> session) {
        String fullFunctionName = ServerConstant.config.APP_NAME + "_" + functionName;

        // Подготовка входных переменных
        JSONObject callVars = new JSONObject();
        for (JavaVar var : variables) {
            if ("IN".equals(var.direction) || "INOUT".equals(var.direction)) {
                String value = getValueFromVars(vars, session, var.name, var.defaultVal);
                callVars.put(var.name, value);
            }
        }

        JavaStrExecut javaExecutor = new JavaStrExecut();
        JSONArray dataArr = new JSONArray();
        JSONObject res = javaExecutor.runFunction(fullFunctionName, callVars, session, dataArr);

        if (res.has("JAVA_ERROR")) {
            result.put("ERROR", res.get("JAVA_ERROR"));
        } else {
            // Обновляем выходные переменные
            for (JavaVar var : variables) {
                if ("OUT".equals(var.direction) || "INOUT".equals(var.direction)) {
                    if (res.has(var.name)) {
                        updateVars(vars, session, var.name, res.get(var.name).toString(), var.srctype);
                    }
                }
            }
            result.put("vars", vars);
        }
    }

    // ========== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ==========

    private List<JavaVar> parseVariables(Element element) {
        List<JavaVar> vars = new ArrayList<>();
        for (Element child : element.children()) {
            String tag = child.tag().toString().toLowerCase();
            if (tag.contains("var") || tag.contains("cmpactionvar")) {
                vars.add(parseActionVar(child));
            }
        }
        return vars;
    }

    private JavaVar parseActionVar(Element element) {
        Attributes attrs = element.attributes();
        JavaVar var = new JavaVar();
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

    private void setJavaComponentAttributes(Element element, cmpAction.ActionConfig config,
                                            List<JavaVar> variables) {
        element.attr("style", "display:none");
        element.attr("action_name", config.name);
        element.attr("name", config.name);
        element.attr("vars", buildVarsJson(variables));
        element.attr("query_type", "java");
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

    private String generateFunctionName(String actionName, String contentHash) {
        String baseName = "act_" + actionName + "_" + contentHash;
        baseName = baseName.replaceAll("[^a-zA-Z0-9_]", "_");
        if (baseName.length() > 60) baseName = baseName.substring(0, 60);
        if (baseName.length() > 0 && Character.isDigit(baseName.charAt(0))) baseName = "f_" + baseName;
        return baseName.toLowerCase();
    }

    private String getShortHash(String input) {
        if (input == null || input.isEmpty()) return "empty";
        String hash = getMd5Hash(input);
        return hash.length() > 12 ? hash.substring(0, 12) : hash;
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

    // ========== ВНУТРЕННИЙ КЛАСС ==========

    private static class JavaVar {
        String name, src, srctype, len, defaultVal, type, direction;
    }
}