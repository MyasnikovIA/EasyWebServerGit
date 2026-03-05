// Сохраняем разные типы данных
D3Api.setSession('number', 12345);
D3Api.setSession('string', 'hello');
D3Api.setSession('object', { name: 'John', age: 30 });
D3Api.setSession('array', [1, 2, 3]);

// Получаем данные (синхронно)
var num = D3Api.getSession('number');
console.log(num); // 12345 (не {value: 12345})

var str = D3Api.getSession('string');
console.log(str); // "hello" (не {value: "hello"})

var obj = D3Api.getSession('object');
console.log(obj); // { name: "John", age: 30 } (весь объект)

var arr = D3Api.getSession('array');
console.log(arr); // [1, 2, 3] (весь массив)

// Асинхронный режим
D3Api.getSession('number', function(err, value) {
    console.log(value); // 12345
});




Вот полностью обновленный метод `createSQLFunctionPG` для `cmpAction.java` с поддержкой определения типа данных и преобразования:

    ```java
    // Исправленный метод createSQLFunctionPG - поддержка определения типа и преобразования
    private void createSQLFunctionPG(String functionName, String schema, Element element, String fileName, boolean debugMode) {
        // Очищаем имя функции от недопустимых символов
        String cleanFunctionName = functionName;
        if (functionName.contains(".")) {
            cleanFunctionName = functionName.substring(functionName.lastIndexOf('.') + 1);
        }
        cleanFunctionName = cleanFunctionName.replaceAll("[^a-zA-Z0-9_]", "");

        // Убеждаемся, что имя функции не начинается с цифры
        if (cleanFunctionName.length() > 0 && Character.isDigit(cleanFunctionName.charAt(0))) {
            cleanFunctionName = "f_" + cleanFunctionName;
        }

        if (procedureList.containsKey(functionName) && !debugMode) {
            // Если процедура уже создана в БД и режим отладки отключен, тогда пропускаем создание новой процедуры
            return;
        }

        Connection conn = getConnect(ServerConstant.config.DATABASE_USER_NAME, ServerConstant.config.DATABASE_USER_PASS);
        if (conn == null) {
            System.err.println("Cannot connect to database for creating procedure");
            return;
        }
        
        StringBuffer vars = new StringBuffer();
        StringBuffer varsColl = new StringBuffer();
        Attributes attrs = element.attributes();
        HashMap<String, Object> param = new HashMap<String, Object>();
        String language = RemoveArrKeyRtrn(attrs, "language", "plpgsql");
        param.put("language", language);
        List<String> varsArr = new ArrayList<>();
        Map<String, String> varTypes = new HashMap<>(); // Храним типы переменных
        String beforeCodeBloc = "";
        String afterCodeBloc = "";

        // Обрабатываем дочерние элементы для сбора информации о переменных
        for (int numChild = 0; numChild < element.childrenSize(); numChild++) {
            Element itemElement = element.child(numChild);
            String tagName = itemElement.tag().toString().toLowerCase();

            if (tagName.equals("before")) {
                // Блок BEFORE содержит код до основного запроса
                beforeCodeBloc = itemElement.text().trim();
                itemElement.text(""); // Очищаем после обработки
            } else if (tagName.equals("after")) {
                // Блок AFTER содержит код после основного запроса
                afterCodeBloc = itemElement.text().trim();
                itemElement.text(""); // Очищаем после обработки
            } else if (tagName.indexOf("var") != -1 || tagName.indexOf("cmpactionvar") != -1) {
                Attributes attrsItem = itemElement.attributes();
                String nameItem = RemoveArrKeyRtrn(attrsItem, "name", "");
                String src = RemoveArrKeyRtrn(attrsItem, "src", nameItem);
                String srctype = RemoveArrKeyRtrn(attrsItem, "srctype", "");
                String len = RemoveArrKeyRtrn(attrsItem, "len", "");
                String type = RemoveArrKeyRtrn(attrsItem, "type", ""); // string, integer, array, json
                
                // Определяем SQL тип
                String sqlType = "VARCHAR";
                
                if (!type.isEmpty()) {
                    // Если тип указан явно
                    switch (type.toLowerCase()) {
                        case "integer":
                        case "int":
                            sqlType = "INTEGER";
                            break;
                        case "bigint":
                        case "long":
                            sqlType = "BIGINT";
                            break;
                        case "decimal":
                        case "numeric":
                            sqlType = "NUMERIC";
                            break;
                        case "boolean":
                        case "bool":
                            sqlType = "BOOLEAN";
                            break;
                        case "date":
                            sqlType = "DATE";
                            break;
                        case "timestamp":
                            sqlType = "TIMESTAMP";
                            break;
                        case "json":
                        case "jsonb":
                            sqlType = "JSONB";
                            break;
                        case "array":
                            sqlType = "TEXT[]";
                            break;
                        case "string":
                        default:
                            if (len.length() > 0 && !len.equals("-1")) {
                                sqlType = "VARCHAR(" + len + ")";
                            } else {
                                sqlType = "TEXT";
                            }
                            break;
                    }
                } else {
                    // Автоматическое определение типа по len
                    if (len.length() > 0 && !len.equals("-1")) {
                        sqlType = "VARCHAR(" + len + ")";
                    } else if (len.equals("-1")) {
                        sqlType = "TEXT";
                    } else {
                        sqlType = "VARCHAR"; // По умолчанию VARCHAR
                    }
                }

                varsArr.add(nameItem);
                varTypes.put(nameItem, type.isEmpty() ? "string" : type.toLowerCase());
                
                // Для действий все параметры INOUT, чтобы можно было возвращать значения
                vars.append(nameItem);
                vars.append(" INOUT ");
                vars.append(sqlType);
                vars.append(",");
                varsColl.append("?,");
                
                System.out.println("Parameter " + nameItem + ": type=" + sqlType + ", direction=INOUT, len=" + len);
            }
        }

        // Убираем последнюю запятую
        String varsStr = vars.toString();
        if (varsStr.length() > 0) {
            varsStr = varsStr.substring(0, varsStr.length() - 1);
        }

        String varsCollStr = varsColl.toString();
        if (varsCollStr.length() > 0) {
            varsCollStr = varsCollStr.substring(0, varsCollStr.length() - 1);
        }

        param.put("vars", varsArr);
        param.put("varTypes", varTypes); // Сохраняем типы для использования в onPage

        // Формируем процедуру
        StringBuffer sb = new StringBuffer();
        sb.append("CREATE OR REPLACE PROCEDURE ");
        sb.append(schema).append(".").append(cleanFunctionName);
        sb.append("(");
        sb.append(varsStr);
        sb.append(")\n");
        sb.append("LANGUAGE ");
        sb.append(language);
        sb.append("\nAS $$\n");
        
        if (beforeCodeBloc.length() > 0) {
            sb.append(beforeCodeBloc);
            if (!beforeCodeBloc.endsWith(";") && !beforeCodeBloc.endsWith("\n")) {
                sb.append(";\n");
            } else {
                sb.append("\n");
            }
        } else {
            sb.append("BEGIN\n");
        }
        
        sb.append("-- cmpAction fileName:");
        sb.append(fileName);
        sb.append("\n");
        sb.append(element.text().trim());
        
        if (!element.text().trim().endsWith(";") && !element.text().trim().endsWith("\n")) {
            sb.append(";\n");
        } else {
            sb.append("\n");
        }
        
        if (afterCodeBloc.length() > 0) {
            sb.append(afterCodeBloc);
            if (!afterCodeBloc.endsWith(";") && !afterCodeBloc.endsWith("\n")) {
                sb.append(";\n");
            } else {
                sb.append("\n");
            }
        }
        
        sb.append("END;\n");
        sb.append("$$\n");

        String createProcedureSQL = sb.toString();
        System.out.println("Creating procedure with SQL:\n" + createProcedureSQL);

        createProcedure(conn, schema + "." + cleanFunctionName, createProcedureSQL);

        String prepareCall = "CALL " + schema + "." + cleanFunctionName + "(" + varsCollStr + ");";

        try {
            CallableStatement cs = conn.prepareCall(prepareCall);
            int ind = 0;
            for (String varOne : varsArr) {
                ind++;
                // Регистрируем OUT параметры с соответствующим SQL типом
                String type = varTypes.getOrDefault(varOne, "string");
                switch (type) {
                    case "integer":
                    case "int":
                        cs.registerOutParameter(ind, Types.INTEGER);
                        break;
                    case "bigint":
                    case "long":
                        cs.registerOutParameter(ind, Types.BIGINT);
                        break;
                    case "decimal":
                    case "numeric":
                        cs.registerOutParameter(ind, Types.NUMERIC);
                        break;
                    case "boolean":
                    case "bool":
                        cs.registerOutParameter(ind, Types.BOOLEAN);
                        break;
                    case "date":
                        cs.registerOutParameter(ind, Types.DATE);
                        break;
                    case "timestamp":
                        cs.registerOutParameter(ind, Types.TIMESTAMP);
                        break;
                    case "json":
                    case "jsonb":
                        cs.registerOutParameter(ind, Types.OTHER);
                        break;
                    case "array":
                        cs.registerOutParameter(ind, Types.ARRAY);
                        break;
                    case "string":
                    default:
                        cs.registerOutParameter(ind, Types.VARCHAR);
                        break;
                }
                System.out.println("Registered OUT parameter " + ind + " for var: " + varOne + " with type: " + type);
            }
            param.put("CallableStatement", cs);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        
        param.put("connect", conn);
        param.put("varsArr", varsArr);
        param.put("SQL", createProcedureSQL);
        param.put("prepareCall", prepareCall);
        
        procedureList.put(schema + "." + cleanFunctionName, param);
        
        System.out.println("Procedure " + schema + "." + cleanFunctionName + " created successfully");
    }
```

И обновленный метод `onPage` для поддержки преобразования типов в `cmpAction.java`:

```java
    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/json"; // Изменить mime ответа
        Map<String, Object> session = query.session;
        JSONObject result = new JSONObject();
        JSONObject queryProperty = query.requestParam;

        System.out.println("=== cmpAction onPage called ===");
        System.out.println("queryProperty: " + queryProperty.toString());

        // Получаем тело запроса
        String postBodyStr = new String(query.postCharBody);
        JSONObject vars;

        try {
            if (postBodyStr.trim().startsWith("{")) {
                vars = new JSONObject(postBodyStr);
            } else {
                vars = new JSONObject();
                System.out.println("Body is not JSON, using empty object");
            }
        } catch (Exception e) {
            System.err.println("Error parsing JSON body: " + e.getMessage());
            vars = new JSONObject();
        }

        System.out.println("Action called - Raw vars: " + vars.toString());

        String query_type = queryProperty.optString("query_type", "java");
        String action_name = queryProperty.optString("action_name", "");
        String pg_schema = queryProperty.optString("pg_schema", "public");

        // Формируем полное имя функции со схемой
        String fullActionName;
        if (action_name.contains(".")) {
            fullActionName = action_name; // уже содержит схему
        } else {
            fullActionName = pg_schema + "." + action_name;
        }

        System.out.println("Action name: " + fullActionName);
        System.out.println("Query type: " + query_type);
        System.out.println("PG Schema: " + pg_schema);

        // Проверяем режим отладки из сессии
        boolean debugMode = false;
        if (query.session != null && query.session.containsKey("debug_mode")) {
            debugMode = (boolean) query.session.get("debug_mode");
        }

        if (ru.miacomsoft.EasyWebServer.ServerResourceHandler.javaStrExecut.existJavaFunction(fullActionName)) {
            // Обработка Java функции
            try {
                // Подготавливаем переменные для Java функции - все как строки
                JSONObject varFun = new JSONObject();
                Iterator<String> keys = vars.keys();

                while (keys.hasNext()) {
                    String key = keys.next();
                    Object varValue = vars.get(key);

                    if (varValue instanceof JSONObject) {
                        JSONObject varObj = (JSONObject) varValue;
                        // Всегда берем строковое значение
                        String value = varObj.optString("value", "");
                        if (value.isEmpty()) {
                            value = varObj.optString("defaultVal", "");
                        }
                        varFun.put(key, value);
                        System.out.println("Variable " + key + " = " + value + " (from object)");
                    } else {
                        varFun.put(key, varValue.toString());
                        System.out.println("Variable " + key + " = " + varValue + " (direct)");
                    }
                }

                System.out.println("Calling Java function with vars: " + varFun.toString());

                // Вызываем Java функцию
                JSONObject resFun = ru.miacomsoft.EasyWebServer.ServerResourceHandler.javaStrExecut.runFunction(fullActionName, varFun, session, null);

                System.out.println("Java function result: " + resFun.toString());

                // Обрабатываем результаты
                if (resFun.has("JAVA_ERROR")) {
                    // Если есть ошибка, добавляем её в результат
                    result.put("ERROR", resFun.get("JAVA_ERROR"));

                    // Возвращаем исходные vars без изменений, чтобы клиент не потерял данные
                    result.put("vars", vars);
                } else {
                    // Обрабатываем успешный результат
                    keys = resFun.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        Object keyvalue = resFun.get(key);

                        if (key.equals("JAVA_ERROR")) {
                            continue;
                        }

                        // Обновляем значения в vars, сохраняя структуру
                        if (vars.has(key) && vars.get(key) instanceof JSONObject) {
                            // Сохраняем как строку
                            vars.getJSONObject(key).put("value", keyvalue.toString());
                        } else {
                            JSONObject newVar = new JSONObject();
                            newVar.put("value", keyvalue.toString());
                            newVar.put("src", key);
                            newVar.put("srctype", "var");
                            vars.put(key, newVar);
                        }
                    }
                    result.put("vars", vars);
                }
            } catch (Exception e) {
                System.err.println("Error executing Java function: " + e.getMessage());
                e.printStackTrace();
                result.put("ERROR", "Java function error: " + e.getMessage());
                result.put("vars", vars);
            }

        } else if (query_type.equals("sql")) {
            // Обработка SQL запросов
            Connection conn = null;
            CallableStatement cs = null;

            try {
                if (!procedureList.containsKey(fullActionName)) {
                    result.put("ERROR", "Procedure not found: " + fullActionName);
                    result.put("vars", vars);
                    System.err.println("Procedure not found: " + fullActionName);
                    return result.toString().getBytes();
                }

                System.out.println("Found procedure in list: " + fullActionName);

                HashMap<String, Object> param = procedureList.get(fullActionName);
                Map<String, String> varTypes = (Map<String, String>) param.get("varTypes");

                // Получаем соединение с БД
                if (session.containsKey("DATABASE")) {
                    HashMap<String, Object> data_base = (HashMap<String, Object>) session.get("DATABASE");

                    if (data_base.containsKey("CONNECT")) {
                        conn = (Connection) data_base.get("CONNECT");
                        // Проверяем, не закрыто ли соединение
                        try {
                            if (conn == null || conn.isClosed()) {
                                System.out.println("Connection is closed, reconnecting...");
                                conn = getConnect(String.valueOf(data_base.get("DATABASE_USER_NAME")),
                                        String.valueOf(data_base.get("DATABASE_USER_PASS")));
                                if (conn != null) {
                                    data_base.put("CONNECT", conn);
                                }
                            }
                        } catch (SQLException e) {
                            System.err.println("Error checking connection: " + e.getMessage());
                            conn = getConnect(String.valueOf(data_base.get("DATABASE_USER_NAME")),
                                    String.valueOf(data_base.get("DATABASE_USER_PASS")));
                            if (conn != null) {
                                data_base.put("CONNECT", conn);
                            }
                        }
                    } else {
                        System.out.println("Creating new connection...");
                        conn = getConnect(String.valueOf(data_base.get("DATABASE_USER_NAME")),
                                String.valueOf(data_base.get("DATABASE_USER_PASS")));
                        if (conn != null) {
                            data_base.put("CONNECT", conn);
                        }
                    }
                } else {
                    // Используем системного пользователя
                    System.out.println("Using system connection...");
                    conn = getConnect(ServerConstant.config.DATABASE_USER_NAME,
                            ServerConstant.config.DATABASE_USER_PASS);
                }

                if (conn == null) {
                    result.put("redirect", ServerConstant.config.LOGIN_PAGE);
                    result.put("ERROR", "Database connection failed");
                    result.put("vars", vars);
                    System.err.println("Database connection failed");
                    return result.toString().getBytes();
                }

                String prepareCall = (String) param.get("prepareCall");
                System.out.println("PrepareCall: " + prepareCall);

                cs = conn.prepareCall(prepareCall);

                List<String> varsArr = (List<String>) param.get("vars");
                System.out.println("Vars array: " + varsArr);

                // Регистрируем OUT параметры с соответствующими типами
                int ind = 0;
                for (String varName : varsArr) {
                    ind++;
                    String type = varTypes != null ? varTypes.getOrDefault(varName, "string") : "string";
                    
                    switch (type) {
                        case "integer":
                        case "int":
                            cs.registerOutParameter(ind, Types.INTEGER);
                            break;
                        case "bigint":
                        case "long":
                            cs.registerOutParameter(ind, Types.BIGINT);
                            break;
                        case "decimal":
                        case "numeric":
                            cs.registerOutParameter(ind, Types.NUMERIC);
                            break;
                        case "boolean":
                        case "bool":
                            cs.registerOutParameter(ind, Types.BOOLEAN);
                            break;
                        case "date":
                            cs.registerOutParameter(ind, Types.DATE);
                            break;
                        case "timestamp":
                            cs.registerOutParameter(ind, Types.TIMESTAMP);
                            break;
                        case "json":
                        case "jsonb":
                            cs.registerOutParameter(ind, Types.OTHER);
                            break;
                        case "array":
                            cs.registerOutParameter(ind, Types.ARRAY);
                            break;
                        case "string":
                        default:
                            cs.registerOutParameter(ind, Types.VARCHAR);
                            break;
                    }
                    System.out.println("Registered OUT parameter " + ind + " for var: " + varName + " with type: " + type);
                }

                if (debugMode) {
                    result.put("SQL", ((String) param.get("SQL")).split("\n"));
                }

                // Устанавливаем IN параметры с преобразованием типов
                ind = 0;
                for (String varNameOne : varsArr) {
                    ind++;
                    String valueStr = "";
                    String targetType = varTypes != null ? varTypes.getOrDefault(varNameOne, "string") : "string";

                    if (vars.has(varNameOne)) {
                        Object varObj = vars.get(varNameOne);
                        if (varObj instanceof JSONObject) {
                            JSONObject varOne = (JSONObject) varObj;

                            if (varOne.optString("srctype").equals("session")) {
                                Object sessionVal = session.get(varNameOne);
                                if (sessionVal != null) {
                                    valueStr = String.valueOf(sessionVal);
                                } else {
                                    valueStr = varOne.optString("defaultVal", "");
                                }
                            } else {
                                valueStr = varOne.optString("value", varOne.optString("defaultVal", ""));
                            }
                        } else {
                            valueStr = varObj.toString();
                        }
                    }

                    System.out.println("Setting IN parameter " + ind + " (" + varNameOne + "): " + valueStr + " (type: " + targetType + ")");

                    // Преобразуем значение в соответствии с целевым типом
                    try {
                        switch (targetType) {
                            case "integer":
                            case "int":
                                if (valueStr.isEmpty()) {
                                    cs.setNull(ind, Types.INTEGER);
                                } else {
                                    cs.setInt(ind, Integer.parseInt(valueStr));
                                }
                                break;
                            case "bigint":
                            case "long":
                                if (valueStr.isEmpty()) {
                                    cs.setNull(ind, Types.BIGINT);
                                } else {
                                    cs.setLong(ind, Long.parseLong(valueStr));
                                }
                                break;
                            case "decimal":
                            case "numeric":
                                if (valueStr.isEmpty()) {
                                    cs.setNull(ind, Types.NUMERIC);
                                } else {
                                    cs.setBigDecimal(ind, new java.math.BigDecimal(valueStr));
                                }
                                break;
                            case "boolean":
                            case "bool":
                                if (valueStr.isEmpty()) {
                                    cs.setNull(ind, Types.BOOLEAN);
                                } else {
                                    cs.setBoolean(ind, Boolean.parseBoolean(valueStr));
                                }
                                break;
                            case "json":
                            case "jsonb":
                                if (valueStr.isEmpty()) {
                                    cs.setNull(ind, Types.OTHER);
                                } else {
                                    cs.setObject(ind, valueStr, Types.OTHER);
                                }
                                break;
                            case "array":
                                if (valueStr.isEmpty()) {
                                    cs.setNull(ind, Types.ARRAY);
                                } else {
                                    try {
                                        JSONArray jsonArray = new JSONArray(valueStr);
                                        String[] stringArray = new String[jsonArray.length()];
                                        for (int i = 0; i < jsonArray.length(); i++) {
                                            stringArray[i] = jsonArray.getString(i);
                                        }
                                        Array array = conn.createArrayOf("text", stringArray);
                                        cs.setArray(ind, array);
                                    } catch (Exception e) {
                                        cs.setString(ind, valueStr);
                                    }
                                }
                                break;
                            case "string":
                            default:
                                if (valueStr.isEmpty()) {
                                    cs.setNull(ind, Types.VARCHAR);
                                } else {
                                    cs.setString(ind, valueStr);
                                }
                                break;
                        }
                    } catch (Exception e) {
                        System.err.println("Error converting parameter " + varNameOne + " to type " + targetType + ": " + e.getMessage());
                        cs.setString(ind, valueStr);
                    }
                }

                // Выполняем процедуру
                System.out.println("Executing procedure...");
                cs.execute();
                System.out.println("Procedure executed successfully");

                // Получаем OUT параметры с преобразованием в строки для JSON
                ind = 0;
                for (String varNameOne : varsArr) {
                    ind++;
                    String outParam = "";
                    String targetType = varTypes != null ? varTypes.getOrDefault(varNameOne, "string") : "string";
                    
                    try {
                        switch (targetType) {
                            case "integer":
                            case "int":
                                int intVal = cs.getInt(ind);
                                outParam = cs.wasNull() ? "" : String.valueOf(intVal);
                                break;
                            case "bigint":
                            case "long":
                                long longVal = cs.getLong(ind);
                                outParam = cs.wasNull() ? "" : String.valueOf(longVal);
                                break;
                            case "decimal":
                            case "numeric":
                                java.math.BigDecimal decimalVal = cs.getBigDecimal(ind);
                                outParam = decimalVal == null ? "" : decimalVal.toString();
                                break;
                            case "boolean":
                            case "bool":
                                boolean boolVal = cs.getBoolean(ind);
                                outParam = cs.wasNull() ? "" : String.valueOf(boolVal);
                                break;
                            case "date":
                            case "timestamp":
                                java.sql.Timestamp timestampVal = cs.getTimestamp(ind);
                                outParam = timestampVal == null ? "" : timestampVal.toString();
                                break;
                            case "json":
                            case "jsonb":
                                Object jsonVal = cs.getObject(ind);
                                outParam = jsonVal == null ? "" : jsonVal.toString();
                                break;
                            case "array":
                                Array arrayVal = cs.getArray(ind);
                                if (arrayVal != null) {
                                    Object[] array = (Object[]) arrayVal.getArray();
                                    JSONArray jsonArray = new JSONArray();
                                    for (Object item : array) {
                                        jsonArray.put(item.toString());
                                    }
                                    outParam = jsonArray.toString();
                                } else {
                                    outParam = "";
                                }
                                break;
                            case "string":
                            default:
                                outParam = cs.getString(ind);
                                if (outParam == null) outParam = "";
                                break;
                        }
                    } catch (SQLException e) {
                        System.err.println("Error getting OUT parameter " + ind + ": " + e.getMessage());
                        outParam = "";
                    }
                    
                    System.out.println("OUT parameter " + ind + " (" + varNameOne + "): " + outParam);

                    if (vars.has(varNameOne) && vars.get(varNameOne) instanceof JSONObject) {
                        JSONObject varOne = vars.getJSONObject(varNameOne);
                        if (varOne.optString("srctype").equals("session")) {
                            session.put(varNameOne, outParam);
                        } else {
                            varOne.put("value", outParam);
                        }
                    }
                }

                result.put("vars", vars);

            } catch (SQLException e) {
                System.err.println("SQL Error: " + e.getMessage());
                e.printStackTrace();
                result.put("ERROR", "SQL Error: " + e.getMessage());
                result.put("vars", vars);
            } catch (Exception e) {
                System.err.println("Unexpected error: " + e.getMessage());
                e.printStackTrace();
                result.put("ERROR", "Error: " + e.getMessage());
                result.put("vars", vars);
            } finally {
                // Закрываем resources
                try {
                    if (cs != null) cs.close();
                } catch (Exception e) {}
                // НЕ закрываем connection, так как он может использоваться повторно
            }
        } else {
            result.put("ERROR", "Unsupported query type: " + query_type);
            result.put("vars", vars);
        }

        String resultText = result.toString();
        System.out.println("Action response: " + resultText);
        return resultText.getBytes();
    }
    
    
    
    
    
    
```

Эти изменения добавляют:

    1. **Поддержку атрибута `type`** для указания типа данных (`string`, `integer`, `bigint`, `decimal`, `boolean`, `date`, `timestamp`, `json`, `array`)
2. **Автоматическое определение типа** по наличию `len` (как и в cmpDataset)
3. **Преобразование типов** при установке IN параметров
4. **Корректное получение OUT параметров** с преобразованием в строки для JSON ответа
5. **Регистрацию OUT параметров** с соответствующими SQL типами

Пример использования в HTML:

    ```html
<cmpAction name="myAction">
    <before>
        <![CDATA[
        -- код до основного действия
        ]]>
    </before>
    <![CDATA[
        -- основное действие
        UPDATE users SET name = inpName WHERE id = userId;
    ]]>
    <after>
        <![CDATA[
        -- код после основного действия
        ]]>
    </after>
    <var name="userId" src="userId" srctype="var" type="integer"/>
    <var name="inpName" src="inpName" srctype="var" type="string" len="100"/>
    <var name="result" src="result" srctype="var" type="string" len="-1"/>
</cmpAction>
```
