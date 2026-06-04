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

    // Кэш для Java-датасетов (имя -> параметры) - сохраняется при инициализации страницы
    public final Map<String, HashMap<String, Object>> procedureList = new HashMap<>();

    // Кэш для проверки, скомпилирован ли уже класс
    private final Map<String, Boolean> compiledCache = new HashMap<>();

    private JavaDatasetHandler() {}

    // ========== ИНИЦИАЛИЗАЦИЯ (ТОЛЬКО СОХРАНЕНИЕ В КЭШ) ==========
    public void handleJavaDataset(Document doc, Element element, cmpDataset.DatasetConfig config, Base base) {
        System.out.println("Java dataset mode (cache only): " + config.name);

        String javaCode = element.hasText() ? element.text().trim() : "";
        List<DatasetVar> variables = parseVariables(element);

        // Сохраняем конфигурацию в кэш - НЕ КОМПИЛИРУЕМ
        saveToCache(config.name, config, variables, javaCode);

        setJavaDatasetAttributes(element, config, variables);
        finalizeElement(element, config, base);
    }

    /**
     * Сохранение конфигурации датасета в кэш без компиляции
     */
    private void saveToCache(String name, cmpDataset.DatasetConfig config,
                             List<DatasetVar> variables, String javaCode) {
        HashMap<String, Object> param = new HashMap<>();
        param.put("JAVA_CODE", javaCode);
        param.put("dbType", config.dbType);
        param.put("dbName", config.dbName);
        param.put("query_type", "java");
        param.put("variables", variables);
        param.put("schema", config.schema);
        param.put("contentHash", getShortHash(javaCode));

        procedureList.put(name, param);
        System.out.println("Java dataset cached (not compiled yet): " + name);
    }

    // ========== ВЫПОЛНЕНИЕ (С ЛЕНИВОЙ КОМПИЛЯЦИЕЙ) ==========
    public void executeJavaDataset(HttpExchange query, JSONObject result, String datasetName,
                                   JSONObject vars, Map<String, Object> session, boolean debugMode) {
        System.out.println("=== executeJavaDataset: " + datasetName + " ===");

        HashMap<String, Object> param = procedureList.get(datasetName);
        if (param == null) {
            result.put("ERROR", "Java dataset not found: " + datasetName);
            return;
        }

        String javaCode = (String) param.get("JAVA_CODE");
        String contentHash = (String) param.get("contentHash");
        List<DatasetVar> variables = (List<DatasetVar>) param.get("variables");

        // Генерируем имя функции на основе имени датасета и хэша содержимого
        String functionName = generateFunctionName(datasetName, contentHash);
        String fullFunctionName = ServerConstant.config.APP_NAME + "_" + functionName;

        // Проверяем, скомпилирован ли уже класс (с учётом хэша)
        boolean needsCompilation = debugMode || !isCompiled(functionName, contentHash);

        if (needsCompilation) {
            // Ленивая компиляция при первом вызове
            System.out.println("Compiling Java dataset on first call: " + datasetName);

            if (!compileDataset(functionName, javaCode, param)) {
                result.put("ERROR", "Compilation failed for: " + datasetName);
                return;
            }

            // Отмечаем как скомпилированный
            markAsCompiled(functionName, contentHash);
        }

        // Выполняем скомпилированный датасет
        executeCompiledDataset(query, result, functionName, variables, vars, session, debugMode);
    }

    /**
     * Проверка, скомпилирован ли уже класс
     */
    private boolean isCompiled(String functionName, String contentHash) {
        String cacheKey = functionName + "_" + contentHash;
        if (compiledCache.containsKey(cacheKey)) {
            return compiledCache.get(cacheKey);
        }

        // Проверяем через JavaStrExecut
        String fullFunctionName = ServerConstant.config.APP_NAME + "_" + functionName;
        boolean exists = JavaStrExecut.InstanceClassName.containsKey(fullFunctionName) ||
                JavaStrExecut.InstanceClassName.containsKey(functionName);
        compiledCache.put(cacheKey, exists);
        return exists;
    }

    /**
     * Отметка о компиляции
     */
    private void markAsCompiled(String functionName, String contentHash) {
        String cacheKey = functionName + "_" + contentHash;
        compiledCache.put(cacheKey, true);
    }

    /**
     * Компиляция датасета
     */
    private boolean compileDataset(String functionName, String javaCode, HashMap<String, Object> param) {
        ArrayList<String> jarResourse = new ArrayList<>();
        ArrayList<String> importPacket = new ArrayList<>();

        // Импорты могут быть сохранены в param, если были распарсены при инициализации
        if (param.containsKey("importPackets")) {
            importPacket.addAll((List<String>) param.get("importPackets"));
        }
        if (param.containsKey("jarResources")) {
            jarResourse.addAll((List<String>) param.get("jarResources"));
        }

        JSONObject infoCompile = new JSONObject();
        JavaStrExecut javaCompiler = new JavaStrExecut();

        return javaCompiler.compile(functionName, importPacket, jarResourse, javaCode, infoCompile);
    }

    /**
     * Выполнение скомпилированного датасета
     */
    private void executeCompiledDataset(HttpExchange query, JSONObject result, String functionName,
                                        List<DatasetVar> variables, JSONObject vars,
                                        Map<String, Object> session, boolean debugMode) {
        String fullFunctionName = ServerConstant.config.APP_NAME + "_" + functionName;

        // Подготовка входных переменных
        JSONObject callVars = new JSONObject();
        for (DatasetVar var : variables) {
            if ("IN".equals(var.direction) || "INOUT".equals(var.direction)) {
                String value = getValueFromVars(vars, session, var.name, var.defaultVal);
                callVars.put(var.name, value);
            }
        }

        JSONArray dataRes = new JSONArray();
        JavaStrExecut javaExecutor = new JavaStrExecut();
        JSONObject res = javaExecutor.runFunction(fullFunctionName, callVars, session, dataRes);

        if (res.has("JAVA_ERROR")) {
            result.put("ERROR", res.get("JAVA_ERROR"));
            if (debugMode && res.has("JAVA_CODE")) {
                result.put("java_code", res.get("JAVA_CODE"));
            }
        } else {
            result.put("data", dataRes);

            // Обновляем выходные переменные
            for (DatasetVar var : variables) {
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

    private void setJavaDatasetAttributes(Element element, cmpDataset.DatasetConfig config,
                                          List<DatasetVar> variables) {
        element.attr("style", "display:none");
        element.attr("dataset_name", config.name);
        element.attr("name", config.name);
        element.attr("vars", buildVarsJson(variables));
        element.attr("query_type", "java");
        element.attr("db", config.dbName);
    }

    private void finalizeElement(Element element, cmpDataset.DatasetConfig config, Base base) {
        element.empty();
        if (base != null) {
            base.attr("query_type", config.queryType);
            base.attr("db_type", config.dbType);
            base.attr("pg_schema", config.schema);
            base.attr("db", config.dbName);
            base.attr("name", config.name);
        }
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

    private String generateFunctionName(String datasetName, String contentHash) {
        String baseName = "ds_" + datasetName + "_" + contentHash;
        // Очищаем имя от недопустимых символов
        baseName = baseName.replaceAll("[^a-zA-Z0-9_]", "_");
        if (baseName.length() > 60) baseName = baseName.substring(0, 60);
        if (baseName.length() > 0 && Character.isDigit(baseName.charAt(0))) baseName = "f_" + baseName;
        return baseName.toLowerCase();
    }

    private String getShortHash(String input) {
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

    private static class DatasetVar {
        String name, src, srctype, len, defaultVal, type, direction;
    }
}