package ru.miacomsoft.EasyWebServer.component.Action;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.JavaStrExecut;
import ru.miacomsoft.EasyWebServer.ServerConstant;
import ru.miacomsoft.EasyWebServer.component.cmpAction;

import java.util.*;

public class JavaActionHandler {

    public static final JavaActionHandler INSTANCE = new JavaActionHandler();

    // Кэш для проверки, скомпилирован ли уже класс
    private final Map<String, Boolean> compiledCache = new HashMap<>();

    private JavaActionHandler() {}

    // ========== ВЫПОЛНЕНИЕ ==========
    public void executeJavaAction(HttpExchange query, JSONObject result, cmpAction.ActionCache cache,
                                  JSONObject vars, Map<String, Object> session) {
        System.out.println("=== executeJavaAction: " + cache.name + " ===");

        String javaCode = cache.javaCode;
        String contentHash = getShortHash(javaCode);
        List<cmpAction.ActionVar> variables = cache.variables;

        // Используем простое имя (без APP_NAME) для компиляции
        String simpleFunctionName = cache.name;
        String fullFunctionName = ServerConstant.config.APP_NAME + "_" + simpleFunctionName;

        // Проверяем, скомпилирован ли уже класс по полному имени
        boolean needsCompilation = !isCompiled(fullFunctionName, contentHash);

        if (needsCompilation) {
            System.out.println("Compiling Java action on first call: " + cache.name);

            JSONObject infoCompile = new JSONObject();
            JavaStrExecut javaCompiler = new JavaStrExecut();

            ArrayList<String> importPacketList = new ArrayList<>(cache.importPackets);
            ArrayList<String> jarResourceList = new ArrayList<>(cache.jarResources);

            // Передаём простое имя, JavaStrExecut сам добавит APP_NAME
            if (!javaCompiler.compile(simpleFunctionName, importPacketList, jarResourceList, javaCode, infoCompile)) {
                result.put("ERROR", "Compilation failed: " + infoCompile.toString());
                if (infoCompile.has("ERROR")) {
                    result.put("compile_errors", infoCompile.get("ERROR"));
                }
                return;
            }

            markAsCompiled(fullFunctionName, contentHash);
        }

        // Выполняем скомпилированное действие, передавая полное имя
        executeCompiledAction(query, result, fullFunctionName, variables, vars, session);
    }

    private boolean isCompiled(String fullFunctionName, String contentHash) {
        String cacheKey = fullFunctionName + "_" + contentHash;
        if (compiledCache.containsKey(cacheKey)) {
            return compiledCache.get(cacheKey);
        }

        boolean exists = JavaStrExecut.InstanceClassName.containsKey(fullFunctionName);
        compiledCache.put(cacheKey, exists);
        return exists;
    }

    private void markAsCompiled(String fullFunctionName, String contentHash) {
        String cacheKey = fullFunctionName + "_" + contentHash;
        compiledCache.put(cacheKey, true);
    }


    private void executeCompiledAction(HttpExchange query, JSONObject result, String fullFunctionName,
                                       List<cmpAction.ActionVar> variables, JSONObject vars,
                                       Map<String, Object> session) {
        if (!JavaStrExecut.InstanceClassName.containsKey(fullFunctionName)) {
            result.put("ERROR", "Compiled class not found: " + fullFunctionName);
            return;
        }

        // Подготовка входных переменных
        JSONObject callVars = new JSONObject();
        for (cmpAction.ActionVar var : variables) {
            if ("IN".equals(var.direction) || "INOUT".equals(var.direction)) {
                String value = getValueFromVars(vars, session, var.src, var.defaultVal);
                callVars.put(var.name, value);
            }
        }

        JavaStrExecut javaExecutor = new JavaStrExecut();
        JSONArray dataArr = new JSONArray();
        // runFunction ищет по полному имени
        JSONObject res = javaExecutor.runFunction(fullFunctionName, callVars, session, dataArr);

        if (res.has("JAVA_ERROR")) {
            result.put("ERROR", res.get("JAVA_ERROR"));
        } else {
            for (cmpAction.ActionVar var : variables) {
                if ("OUT".equals(var.direction) || "INOUT".equals(var.direction)) {
                    if (res.has(var.name)) {
                        updateVars(vars, session, var.src, res.get(var.name).toString(), var.srctype);
                    }
                }
            }
            result.put("vars", vars);
        }
    }

    // ========== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ==========

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

    private String getValueFromVars(JSONObject vars, Map<String, Object> session, String name, String defaultValue) {
        // name здесь - это var.src (имя контрола или переменной)
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
        // Найти оригинальную переменную, чтобы получить правильный src
        String src = name;

        if (vars.has(name) && vars.get(name) instanceof JSONObject) {
            JSONObject obj = vars.getJSONObject(name);
            if ("session".equals(srctype)) {
                session.put(name, value);
            } else {
                obj.put("value", value);
                // Сохраняем оригинальный src и srctype
                if (obj.has("src")) {
                    src = obj.getString("src");
                }
            }
            return;
        }

        JSONObject wrapper = new JSONObject();
        wrapper.put("value", value);
        wrapper.put("src", src);
        wrapper.put("srctype", srctype != null ? srctype : "var");
        vars.put(name, wrapper);
    }
}