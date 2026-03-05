package ru.miacomsoft.EasyWebServer;

import org.json.JSONArray;
import org.json.JSONObject;
import ru.miacomsoft.EasyWebServer.util.structObject.JavaTerminalClassObject;

import javax.tools.*;
import javax.tools.JavaCompiler.CompilationTask;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static ru.miacomsoft.EasyWebServer.HttpExchange.parseErrorRunJava;


/**
 * Класс предназначени для динамической компиляции и выполнения JAVA кода из текста (есть буфирезация выполненных фрагментов кода)
 */
public class JavaStrExecut {


    /**
     * Места хронения скомпелированных классов и созданных экземпляров классов (CompileObject) по хэш коду класса
     */
    public static HashMap<String, Object> InstanceClassHash = new HashMap<>();

    /**
     * Места хронения скомпелированных классов и созданных экземпляров классов (CompileObject) по читаемому имени класса
     */
    public static HashMap<String, Object> InstanceClassName = new HashMap<>();


    /**
     * Объект компилируемого класса (используется для хронения скомпелированных классов)
     */
    private static class CompileObject {
        Class<?> ClassNat = null;     // объект класса, по которому будет создан экземпляр класса
        Object ObjectInstance = null; // Экземпляр класса
        String CodeText = null;       // Код программы
        String HashClass = null;      // Шеш кода программы
        HashMap<String, Method> methods = new HashMap<>(); // список методов в компелированном классе
        long lastModified = 0;
    }

    /**
     * Выполнить команду Java
     *
     * @param code    - текст выполняемого кода
     * @param vars    - список входящих переменных
     * @param session - список входящих переменных (из сессии для ВЭБ сервера)
     * @return - возвращается объект vars
     * res.get("JAVA_CODE_SRC") - получение исходного кода
     * res.get("JAVA_ERROR") - получение объекта ошибки
     */
    @SuppressWarnings("unchecked")
    public HashMap<String, Object> exec(String code, HashMap<String, Object> vars, HashMap<String, Object> session) {
        HashMap<String, Object> res = new HashMap<>();
        if (session == null) {
            session = new HashMap<>();
        }
        if (vars == null) {
            vars = new HashMap<>();
        }
        String src = "" +
                "import java.util.HashMap; \n"
                + "public class SpecialClassToCompileV2 { \n"
                + "    public HashMap<String, Object>  evalFunc(HashMap<String, Object> vars, HashMap<String, Object> session) {\n"
                + "        " + code + ";\n"
                + "        return vars;\n"
                + "    }\n"
                + "}\n";
        res.put("JAVA_CODE_SRC", src);
        String hashCrs = getMd5Hash(src); // получаем хэш текста исходника функции
        try {
            CompileObject compileObject = new CompileObject();
            if (!InstanceClassHash.containsKey(hashCrs)) {
                SpecialClassLoader classLoader = new SpecialClassLoader();
                compileMemoryMemory(src, "SpecialClassToCompileV2", classLoader);
                compileObject.ClassNat = Class.forName("SpecialClassToCompileV2", false, classLoader);
                compileObject.ObjectInstance = compileObject.ClassNat.getDeclaredConstructor().newInstance();
                InstanceClassHash.put(hashCrs, compileObject); // запоминаем созданный экземпляр класса
            } else {
                compileObject = (CompileObject) InstanceClassHash.get(hashCrs);
            }
            Class<?>[] argTypes = new Class[]{HashMap.class, HashMap.class};             // перечисляем типы входящих переменных
            Method meth = compileObject.ClassNat.getMethod("evalFunc", argTypes);                                // получаем метод по имени и типам входящих переменных
            res = (HashMap<String, Object>) meth.invoke(compileObject.ObjectInstance, vars, session); // запуск мектода на выполнение
            res.put("JAVA_CODE_SRC", src);
        } catch (ClassNotFoundException e) {
            res.put("JAVA_ERROR", e);
        } catch (InvocationTargetException e) {
            res.put("JAVA_ERROR", e);
        } catch (InstantiationException e) {
            res.put("JAVA_ERROR", e);
        } catch (IllegalAccessException e) {
            res.put("JAVA_ERROR", e);
        } catch (NoSuchMethodException e) {
            res.put("JAVA_ERROR", e);
        } catch (Exception e) {
            res.put("JAVA_ERROR", e);
        }
        return res;
    }


    /**
     * Функция компиляции кода (поиск по хэшу кода)
     *
     * @param code
     * @return
     */
    public boolean compile(String code) {
        boolean res = true;
        String src = "" +
                "import java.util.HashMap; \n"
                + "public class SpecialClassToCompileV2 { \n"
                + "    public HashMap<String, Object>  evalFunc(HashMap<String, Object> vars, HashMap<String, Object> session) {\n"
                + "        " + code + ";\n"
                + "        return vars;\n"
                + "    }\n"
                + "}\n";
        String hashCrs = getMd5Hash(src); // получаем хэш текста исходника функции
        try {
            if (!InstanceClassHash.containsKey(hashCrs)) {
                SpecialClassLoader classLoader = new SpecialClassLoader();
                compileMemoryMemory(src, "SpecialClassToCompileV2", classLoader);
                CompileObject compileObject = new CompileObject();
                compileObject.ClassNat = Class.forName("SpecialClassToCompileV2", false, classLoader);
                compileObject.ObjectInstance = compileObject.ClassNat.getDeclaredConstructor().newInstance();
                InstanceClassHash.put(hashCrs, compileObject); // запоминаем созданный экземпляр класса
            }
        } catch (Exception e) {
            res = false;
        }
        return res;
    }


    /**
     * Выполнить ранее скомпилированную команду Java
     *
     * @param nameFunction - Имя ранее скомпелированной функции
     * @param vars         - список входящих переменных
     * @param session      - список входящих переменных (из сессии для ВЭБ сервера)
     * @return - возвращается объект vars
     * res.get("JAVA_ERROR") - получение объекта ошибки
     */
    @SuppressWarnings("unchecked")
    public JSONObject runFunction(String nameFunction, JSONObject vars, Map<String, Object> session, JSONArray data) {
        JSONObject res = new JSONObject();
        if (session == null) {
            session = new HashMap<>();
        }
        if (vars == null) {
            vars = new JSONObject();
        }
        if (data == null) {
            data = new JSONArray();
        }
        try {
            if (!InstanceClassName.containsKey(nameFunction)) {
                res.put("JAVA_ERROR", "Compile file not found");
            } else {
                CompileObject compileObject = (CompileObject) InstanceClassName.get(nameFunction);
                Class<?>[] argTypes = new Class[]{JSONObject.class, HashMap.class, JSONArray.class};
                Method meth = compileObject.ClassNat.getMethod("evalFunc", argTypes);

                // Создаем копию vars, где все значения преобразованы в строки
                JSONObject safeVars = new JSONObject();
                Iterator<String> keys = vars.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object value = vars.get(key);

                    // Преобразуем все значения в строки для безопасной передачи
                    if (value != null) {
                        safeVars.put(key, value.toString());
                    } else {
                        safeVars.put(key, "");
                    }
                }

                System.out.println("Calling Java function with safe vars: " + safeVars);

                try {
                    res = (JSONObject) meth.invoke(compileObject.ObjectInstance, safeVars, session, data);
                } catch (InvocationTargetException e) {
                    // Получаем исходное исключение
                    Throwable targetException = e.getTargetException();

                    // Формируем понятное сообщение об ошибке
                    JSONObject errorInfo = new JSONObject();
                    errorInfo.put("error", targetException.getClass().getName());
                    errorInfo.put("message", targetException.getMessage());

                    // Добавляем стектрейс для отладки
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    targetException.printStackTrace(pw);
                    errorInfo.put("stacktrace", sw.toString());

                    res.put("JAVA_ERROR", errorInfo);
                    System.err.println("Error executing Java function: " + targetException.getMessage());
                    targetException.printStackTrace();
                }
            }
        } catch (NoSuchMethodException e) {
            res.put("JAVA_ERROR", createErrorInfo(e));
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
        } catch (IllegalAccessException e) {
            res.put("JAVA_ERROR", createErrorInfo(e));
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
        } catch (Exception e) {
            res.put("JAVA_ERROR", createErrorInfo(e));
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
        }
        return res;
    }

    /**
     * Извлечение имени поля из сообщения об ошибке JSON
     */
    private String extractFieldNameFromError(String errorMessage) {
        if (errorMessage == null) return null;
        // Ищем паттерн JSONObject["FIELD_NAME"]
        int start = errorMessage.indexOf("JSONObject[\"");
        if (start >= 0) {
            start += "JSONObject[\"".length();
            int end = errorMessage.indexOf("\"]", start);
            if (end > start) {
                return errorMessage.substring(start, end);
            }
        }
        return null;
    }

    /**
     * Создание информативного объекта ошибки
     */
    private static JSONObject createErrorInfo(Exception e) {
        JSONObject errorInfo = new JSONObject();
        errorInfo.put("error", e.getClass().getName());
        errorInfo.put("message", e.getMessage());

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        errorInfo.put("stacktrace", sw.toString());

        return errorInfo;
    }

    /**
     * Проверка наличия скомпелированного файла в памяти приложения
     * @param name
     * @return
     */
    public boolean existJavaFunction(String name) {
        // Проверяем прямое имя
        if (InstanceClassName.containsKey(name)) {
            return true;
        }
        // Проверяем с префиксом APP_NAME
        String withPrefix = ServerConstant.config.APP_NAME + "_" + name;
        if (InstanceClassName.containsKey(withPrefix)) {
            return true;
        }
        return false;
    }

    public boolean compile(String name, String code, JSONObject info) {
        return compile(name, null, null, code, info);
    }

    /**
     * Компиляция кода с присваеванием имени, по котором  можно будет его найти
     *
     * @param name - имя скомпелированной функции (произвольное)
     * @param code - тело кода
     * @return
     */
    public boolean compile(String name, ArrayList<String> importPacket, ArrayList<String> jarResourse, String code, JSONObject info) {
        boolean res = true;
        String src = "" +
                "import java.util.HashMap; \n" +
                "import java.util.ArrayList; \n" +
                "import org.json.JSONArray; \n" +
                "import org.json.JSONObject; \n"+
                "\npublic class SpecialClassToCompileV3 { \n"
                + "    public JSONObject evalFunc(JSONObject vars, HashMap<String, Object> session, JSONArray data) {\n"
                + "        " + code + ";\n"
                + "        return vars;\n"
                + "    }\n"
                + "}\n";
        String hashCrs = getMd5Hash(src); // получаем хэш текста исходника функции
        info.put("compile", false);
        info.put("src", src);
        if (!InstanceClassHash.containsKey(hashCrs)) {
            SpecialClassLoader classLoader = new SpecialClassLoader();
            if (!compileMemoryMemory(src, "SpecialClassToCompileV3", classLoader, jarResourse, info)) {
                return false;
            }
            try {
                CompileObject compileObject = new CompileObject();
                compileObject.ClassNat = Class.forName("SpecialClassToCompileV3", false, classLoader);
                compileObject.ObjectInstance = compileObject.ClassNat.getDeclaredConstructor().newInstance();
                compileObject.CodeText = src;
                InstanceClassHash.put(hashCrs, compileObject); // запоминаем созданный экземпляр класса
                InstanceClassName.put(ServerConstant.config.APP_NAME + "_" + name, compileObject);
                System.out.println("COMPILE mem: " + ServerConstant.config.APP_NAME + "_" + name);
                info.put("compile", true);
            } catch (Exception e) {
                info.put("error", parseErrorRunJava(e));
                res = false;
            }
        }
        return res;
    }


    /**
     * Компиляция кода с присваеванием имени, по котором  можно будет его найти
     *
     * @return
     */
    public boolean compileFile(String rootPath, String requestPath, JSONObject info) {
        return compileFile(rootPath, requestPath, info, false);
    }

    /**
     * Компиляция кода с присваеванием имени, по котором  можно будет его найти
     *
     * @return
     */
    @SuppressWarnings("unchecked")
    public boolean compileFile(String rootPath, String requestPath, JSONObject info, boolean debugMode) {
        if (info == null) {
            info = new JSONObject();
        }
        boolean res = true;
        String src = "";
        String resourcePath = rootPath + '/' + requestPath;
        try {
            File nameFileObj = new File(resourcePath);
            String nameFile = nameFileObj.getName();
            String classNameText = nameFile.substring(0, nameFile.length() - 5);
            long lastModified = nameFileObj.lastModified(); // дата последней модификации файла
            String hashCrs ="";
            // Если Java файл не был скомпилирован, или был модифицирован, тогда перекомпилируем его.
            if (!InstanceClassName.containsKey(requestPath) || ((CompileObject) InstanceClassName.get(requestPath)).lastModified != lastModified || debugMode) {
                InputStream in = new FileInputStream(resourcePath);
                InputStreamReader inputStreamReader = new InputStreamReader(in);
                StringBuffer sb = new StringBuffer();
                int charInt;
                while ((charInt = inputStreamReader.read()) > 0) {
                    sb.append((char) charInt);
                }
                src = sb.toString();
                hashCrs = getMd5Hash(src); // получаем хэш текста исходника функции
                info.put("compile", false);
                info.put("src", src);
            } else {
                CompileObject compileObject = (CompileObject) InstanceClassName.get(requestPath);
                info.put("src", compileObject.CodeText);
                hashCrs = compileObject.HashClass;
                info.put("compile", false);
            }

            if (!InstanceClassHash.containsKey(hashCrs) || debugMode) {
                SpecialClassLoader classLoader = new SpecialClassLoader();
                if (!compileMemoryMemory(src, classNameText, classLoader, null, info)) {
                    return false;
                }
                try {
                    CompileObject compileObject = new CompileObject();
                    compileObject.ClassNat = Class.forName(classNameText, false, classLoader);
                    compileObject.ObjectInstance = compileObject.ClassNat.getDeclaredConstructor().newInstance();
                    compileObject.CodeText = src;
                    for (Method method : compileObject.ClassNat.getMethods()) {
                        for (Class<?> paramType : method.getParameterTypes()) {
                            if (paramType.getName().equals(HttpExchange.class.getName())) {
                                // Запоминаем методы, у которых есть параметр типа HttpExchange
                                compileObject.methods.put(method.getName(), method);
                            }
                        }
                    }
                    compileObject.lastModified = lastModified;
                    compileObject.HashClass = hashCrs;
                    InstanceClassHash.put(hashCrs, compileObject); // запоминаем созданный экземпляр класса
                    InstanceClassName.put(requestPath, compileObject);
                    System.out.println("COMPILE File:" + requestPath + (debugMode ? " (debug mode)" : ""));
                    info.put("compile", true);
                } catch (Exception e) {
                    info.put("error", parseErrorRunJava(e));
                    res = false;
                }
            }
        } catch (Exception e) {
            // ошибка чтения файла
            info.put("ERROR", "ошибка чтения файла:" + resourcePath);
            return false;
        }
        return res;
    }

    /**
     * Запуск скомпилированного ранее файла (или скомпилировать его)
     */
    public void runJavaFile(HttpExchange query) {
        JSONObject infoCompile = new JSONObject();
        try {
            // Проверяем режим отладки из сессии
            boolean debugMode = query.isDebugMode();

            if (compileFile(ServerConstant.config.WEBAPP_DIR, query.requestPath, infoCompile, debugMode)) {
                query.mimeType = "text/html";
                CompileObject compileObject = (CompileObject) InstanceClassName.get(query.requestPath);
                Method meth = null;
                if (compileObject.methods.containsKey("onPage")) {
                    meth = compileObject.methods.get("onPage");
                } else if (compileObject.methods.containsKey("page")) {
                    meth = compileObject.methods.get("page");
                }
                if (meth != null) {
                    byte[] messageBytes = (byte[]) meth.invoke(compileObject.ObjectInstance, query); // запуск метода на выполнение
                    if (messageBytes == null)
                        return;                                                      // если возвращается NULL тогда ничего отправлять ненадо
                    query.sendHtml(new String(messageBytes));
                } else {
                    query.sendHtml("Метод для запуска не найден " + query.requestPath);
                }
            } else {
                // Если при компиляции произошла ошибка, тогда отправляем подробности клиенту в браузер
                query.mimeType = "text/html";
                query.sendHtml(parseErrorCompile(infoCompile));
            }
        } catch (Exception e) {
            query.mimeType = "text/plain";
            query.sendHtml(parseErrorRunJava(e));
        }
    }

    /**
     * Запуск обработки запроса терминала JAVA
     *
     * @param query
     */
    public boolean runJavaTerminalFile(HttpExchange query) {
        JSONObject infoCompile = new JSONObject();
        try {
            if (ServerResource.pagesJavaTerminalClass.containsKey(query.requestPath)) {
                query.mimeType = "text/html";
                JavaTerminalClassObject term = ServerResource.pagesJavaTerminalClass.get(query.requestPath);
                Method meth = term.method;   // получаем метод по имени и типам входящих переменных
                byte[] messageBytes; // запуск метода на выполнение
                try {
                    messageBytes = (byte[]) meth.invoke(term.ObjectInstance, query);
                } catch (IllegalAccessException e) {
                    query.write(("IllegalAccessException ERROR: " + e).getBytes());
                    return false;
                } catch (InvocationTargetException e) {
                    query.write(("InvocationTargetException ERROR: " + e).getBytes());
                    return false;
                }
                if (messageBytes != null) {
                    query.sendHtml(new String(messageBytes));
                }
            } else {
                // Проверяем режим отладки из сессии
                boolean debugMode = query.isDebugMode();

                if (compileFile(ServerConstant.config.WEBAPP_DIR, query.requestPath, infoCompile, debugMode)) {
                    query.mimeType = "text/html";
                    CompileObject compileObject = (CompileObject) InstanceClassName.get(query.requestPath);
                    Class<?>[] argTypes = new Class[]{HttpExchange.class};
                    Method meth = compileObject.ClassNat.getMethod("onTerminal", argTypes);   // получаем метод по имени и типам входящих переменных
                    byte[] messageBytes = (byte[]) meth.invoke(compileObject.ObjectInstance, query); // запуск метода на выполнение
                    if (messageBytes == null)
                        return true;                                                      // если возвращается NULL тогда ничего отправлять ненадо
                    query.write(messageBytes);
                } else {
                    // Если при компиляции произошла ошибка, тогда отправляем подробности клиенту в браузер
                    query.mimeType = "text/plain";
                    System.err.println("ERROR compile  " + infoCompile);
                    query.write((parseErrorCompileTerminal(infoCompile) + "\r\n").getBytes());
                }
            }
            return true;                                                      // если возвращается NULL тогда ничего отправлять ненадо
        } catch (Exception e) {
            query.write(parseErrorRunJava(e).getBytes());
            return false;                                                      // если возвращается NULL тогда ничего отправлять ненадо
        }
    }

    public static String parseErrorCompileTerminal(JSONObject infoCompile) {
        StringBuffer message = new StringBuffer("HTTP error compile Java file:");
        message.append("\r\n");
        if (infoCompile.has("ERROR")) {
            JSONArray arrError = infoCompile.getJSONArray("ERROR");
            message.append("Found ");
            message.append(arrError.length());
            message.append(" error.\r\n");
            for (int i = 0; i < arrError.length(); i++) {
                JSONObject objError = arrError.getJSONObject(i);
                if (objError.has("ErrorString")) {
                    message.append(objError.getString("ErrorString"));
                    message.append("\r\n");
                }
            }
        }
        return message.toString().replace("\n", "\r\n");
    }

    /**
     * Функция разбора ошибки компиляции и визуализации в виде HTML страницы
     *
     * @param infoCompile
     * @return
     */
    public static String parseErrorCompile(JSONObject infoCompile) {
        StringBuffer message = new StringBuffer("HTTP error compile Java file:");
        String srcCode = infoCompile.getString("src");
        StringBuffer dstError = new StringBuffer();
        message.append("<br/>");
        if (infoCompile.has("ERROR")) {
            JSONArray arrError = infoCompile.getJSONArray("ERROR");
            message.append("Found ");
            message.append(arrError.length());
            message.append(" error.<br/>");
            for (int i = 0; i < arrError.length(); i++) {
                JSONObject objError = arrError.getJSONObject(i);
                if (objError.has("ErrorString")) {
                    message.append("<pre>");
                    message.append(objError.getString("ErrorString"));
                    message.append("</pre>");
                }
            }
            for (int i = 0; i < arrError.length(); i++) {
                JSONObject objError = arrError.getJSONObject(i);
                StringBuffer dstTmpError = new StringBuffer();
                dstTmpError.append(srcCode.substring(0, objError.getInt("StartPosition"))); // фрагменьт кода до обшибки
                if (objError.getInt("StartPosition") == objError.getInt("EndPosition")) {
                    dstTmpError.append("<span style=\"color: crimson;\">");
                    dstTmpError.append(" >#< ");
                    dstError.append(objError.getString("Message"));
                    dstTmpError.append("</span>");
                } else {
                    dstTmpError.append("<span style=\"color: crimson;\">");
                    dstTmpError.append(srcCode.substring(objError.getInt("StartPosition"), objError.getInt("EndPosition")));
                    dstTmpError.append("</span>");
                }
                dstTmpError.append(srcCode.substring(objError.getInt("EndPosition"))); // фрагменьт кода до обшибки
                int indLine = 0;
                for (String line : dstTmpError.toString().split("\r")) {
                    indLine++;
                    if (objError.getInt("LineNumber") == indLine) {
                        if (objError.getInt("StartPosition") == objError.getInt("EndPosition")) {
                            dstError.append("\r<span style=\"color: crimson;\">");
                            dstError.append(objError.getString("Message"));
                            dstError.append("</span>");
                        } else {
                            dstError.append("\n<span style=\"color: crimson;\">Error: ");
                            dstError.append(objError.getString("Message"));
                            dstError.append("</span>");
                        }
                    }
                    dstError.append(line);
                }
                break;
            }
        }
        message.append("<br/>");
        message.append("<pre>");
        message.append(dstError);
        message.append("</pre>");
        message.append("\r\n");
        return message.toString();
    }

    public void runJavaComponent(HttpExchange query) {
        String classNameCmp = query.requestPath.substring(0, query.requestPath.length() - ".component".length()).replaceAll("/", ".");
        try {
            Class<?>[] argTypes = new Class[]{HttpExchange.class};
            Class<?> classNat = Class.forName(classNameCmp);
            Method meth = classNat.getMethod("onPage", argTypes);
            byte[] messageBytes = (byte[]) meth.invoke(null, query); // запуск мектода на выполнение
            if (messageBytes != null) {
                query.sendHtml(new String(messageBytes));
            }
        } catch (Exception e) {
            query.mimeType = "text/plain";
            query.sendHtml(parseErrorRunJava(e));
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public void runJavaDataset(String classNameCmp, HttpExchange query) {
        try {
            Class<?>[] argTypes = new Class[]{HttpExchange.class};
            Class<?> classNat = Class.forName(classNameCmp);
            Method meth = classNat.getMethod("onPage", argTypes);
            byte[] messageBytes = (byte[]) meth.invoke(null, query); // запуск мектода на выполнение
            if (messageBytes != null) {
                query.sendHtml(new String(messageBytes));
            }
        } catch (Exception e) {
            query.mimeType = "text/plain";
            query.sendHtml(parseErrorRunJava(e));
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public void runJavaServerlet(HttpExchange query) {
        String classNameCmp = null;
        try {
            // Безопасное извлечение имени класса
            String requestPath = query.requestPath;
            if (requestPath == null || !requestPath.contains("component}/")) {
                throw new Exception("Invalid component path: " + requestPath);
            }

            String[] parts = requestPath.split("component}/");
            if (parts.length < 2) {
                throw new Exception("Invalid component path format: " + requestPath);
            }

            String classPath = parts[1];
            // Убираем параметры запроса
            if (classPath.contains("?")) {
                classPath = classPath.substring(0, classPath.indexOf("?"));
            }

            // Формируем полное имя класса
            String basePackage = JavaStrExecut.class.getPackage().getName();
            classNameCmp = basePackage + ".component." + classPath.replace('/', '.');

            // Убираем расширение .java если есть
            if (classNameCmp.endsWith(".java")) {
                classNameCmp = classNameCmp.substring(0, classNameCmp.length() - 5);
            }

            System.out.println("Looking for component class: " + classNameCmp);

            Class<?> classServerlet = Class.forName(classNameCmp);
            Method meth = classServerlet.getMethod("onPage", HttpExchange.class);

            Object result;
            if (java.lang.reflect.Modifier.isStatic(meth.getModifiers())) {
                result = meth.invoke(null, query);
            } else {
                Object instance = classServerlet.getDeclaredConstructor().newInstance();
                result = meth.invoke(instance, query);
            }

            byte[] messageBytes = null;
            if (result instanceof byte[]) {
                messageBytes = (byte[]) result;
            } else if (result instanceof String) {
                messageBytes = ((String) result).getBytes(StandardCharsets.UTF_8);
            } else if (result != null) {
                messageBytes = result.toString().getBytes(StandardCharsets.UTF_8);
            }

            if (messageBytes != null) {
                // Проверяем, является ли ответ JSON
                String responseStr = new String(messageBytes, StandardCharsets.UTF_8).trim();
                if (responseStr.startsWith("{") || responseStr.startsWith("[")) {
                    query.mimeType = "application/json";
                } else {
                    query.mimeType = "text/html";
                }
                query.send(messageBytes);
            }

        } catch (ClassNotFoundException e) {
            String errorMsg = "Component class not found: " + classNameCmp + " - " + e.getMessage();
            System.err.println("runJavaServerlet Exception: " + errorMsg);
            sendErrorResponse(query, errorMsg, 404);
        } catch (NoSuchMethodException e) {
            String errorMsg = "Method 'onPage' not found in component: " + classNameCmp;
            System.err.println("runJavaServerlet Exception: " + errorMsg);
            sendErrorResponse(query, errorMsg, 500);
        } catch (Exception e) {
            String errorMsg = "Error executing component: " + e.getClass().getName() + " - " + e.getMessage();
            System.err.println("runJavaServerlet Exception: " + errorMsg);
            e.printStackTrace();
            sendErrorResponse(query, errorMsg, 500);
        }
    }

    private void sendErrorResponse(HttpExchange query, String errorMessage, int statusCode) {
        try {
            JSONObject error = new JSONObject();
            error.put("error", errorMessage);
            error.put("status", statusCode);
            query.mimeType = "application/json";
            query.send(error.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            // Если не удалось отправить JSON, отправляем простой текст
            query.mimeType = "text/plain";
            query.send(("Error: " + errorMessage).getBytes(StandardCharsets.UTF_8));
        }
    }

    public void compileMemoryMemory(String src, String name, SpecialClassLoader classLoader) {
        compileMemoryMemory(src, name, classLoader, null, null);
    }

    /**
     * Компиляция JAVA файла из текстовой строки в памяти приложения
     *
     * @param src
     * @param name
     * @param classLoader
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean compileMemoryMemory(String src, String name, SpecialClassLoader classLoader, ArrayList<String> jarResourse, JSONObject info) {
        boolean resultColl = true;
        if (info == null) info = new JSONObject();
        if (jarResourse == null) jarResourse = new ArrayList<>();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diacol = new DiagnosticCollector<>(); // Объект в котором хрониться информация о процессе компиляции
        StandardJavaFileManager standartFileManager = compiler.getStandardFileManager(diacol, null, null);
        SpecialJavaFileManager fileManager = new SpecialJavaFileManager(standartFileManager, classLoader);
        List<String> optionList = new ArrayList<>();
        for (String libJaR : ServerConstant.config.LIB_JAR) {
            if (libJaR.indexOf(File.separator) == -1) {
                jarResourse.add(libJaR);
            } else {
                jarResourse.add(ServerConstant.config.LIB_DIR + File.separator + libJaR);
            }
        }
        Set<String> jarSet = new LinkedHashSet<>(jarResourse);
        StringBuffer libList = new StringBuffer(System.getProperty("java.class.path")); // получаем пут к библиотекам, которые подключены к проету
        for (String key : jarSet) {
            File file = new File(key);
            if (file.exists()) {
                libList.append(";");
                libList.append(file.getAbsolutePath()); // подключаем путь располежения библиотек из конфигурационного файла
            }
        }
        optionList.addAll(Arrays.asList("-classpath", libList.toString()));
        CompilationTask compile = compiler.getTask(null, fileManager, diacol, optionList, null, Arrays.asList(new JavaFileObject[]{new MemorySource(name, src)}));
        boolean status = compile.call();
        if (!status) {
            JSONArray listErrInfo = new JSONArray();
            List<Diagnostic<? extends JavaFileObject>> diagnostics = diacol.getDiagnostics();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
                JSONObject errInfoOne = new JSONObject();
                errInfoOne.put("Message", diagnostic.getMessage(null));
                errInfoOne.put("Code", diagnostic.getCode());
                errInfoOne.put("ColumnNumber", diagnostic.getColumnNumber());
                errInfoOne.put("Kind", diagnostic.getKind().toString());
                errInfoOne.put("StartPosition", diagnostic.getStartPosition());
                errInfoOne.put("Position", diagnostic.getPosition());
                errInfoOne.put("EndPosition", diagnostic.getEndPosition());
                errInfoOne.put("LineNumber", diagnostic.getLineNumber());
                errInfoOne.put("FullInfo", diagnostic.getKind() + ":\t Line [" + diagnostic.getLineNumber() + "] \t Position [" + diagnostic.getPosition() + "]\t" + diagnostic.getMessage(Locale.ROOT) + "\n");
                errInfoOne.put("ErrorString", diagnostic.toString());
                listErrInfo.put(errInfoOne);
            }
            info.put("ERROR", listErrInfo);
            resultColl = false;
        }
        return resultColl;
    }

    /**
     * @param input
     * @return System.out.println(" MD5 Hash : " + getMd5Hash ( input));
     */
    public static String getMd5Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }
}

/**
 * Класс для создания кода из строки
 *
 * @author vampirus
 */
class MemorySource extends SimpleJavaFileObject {

    private String src;

    public MemorySource(String name, String src) {
        super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
        this.src = src;
    }

    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return src;
    }
}

/**
 * Класс для записи байткода в память
 *
 * @author vampirus
 */
class MemoryByteCode extends SimpleJavaFileObject {

    private ByteArrayOutputStream oStream;

    public MemoryByteCode(String name) {
        super(URI.create("byte:///" + name.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
    }

    public OutputStream openOutputStream() {
        oStream = new ByteArrayOutputStream();
        return oStream;
    }

    public byte[] getBytes() {
        return oStream.toByteArray();
    }
}

/**
 * Файловый менеджер
 *
 * @author vampirus
 */
class SpecialJavaFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
    private SpecialClassLoader classLoader;

    public SpecialJavaFileManager(StandardJavaFileManager fileManager, SpecialClassLoader specClassLoader) {
        super(fileManager);
        classLoader = specClassLoader;
    }

    public JavaFileObject getJavaFileForOutput(Location location, String name, JavaFileObject.Kind kind, FileObject sibling) throws IOException {
        MemoryByteCode byteCode = new MemoryByteCode(name);
        classLoader.addClass(byteCode);
        return byteCode;
    }
}

/**
 * Загрузчик
 *
 * @author vampirus
 */
class SpecialClassLoader extends ClassLoader {
    private MemoryByteCode byteCode;

    protected Class<?> findClass(String name) {
        return defineClass(name, byteCode.getBytes(), 0, byteCode.getBytes().length);
    }

    public void addClass(MemoryByteCode code) {
        byteCode = code;
    }
}