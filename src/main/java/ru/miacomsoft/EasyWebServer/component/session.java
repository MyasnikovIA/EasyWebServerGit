package ru.miacomsoft.EasyWebServer.component;

import org.json.JSONObject;
import org.json.JSONException;
import ru.miacomsoft.EasyWebServer.HttpExchange;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Страница для сохранения произвольных объектов в пользовательской сессии
 * Поддерживает:
 * - POST /{component}/session?set_session=key - сохранение данных в сессию
 * - POST /{component}/session?get_session=key - получение данных из сессии
 * - GET /{component}/session?action=getAll - получение всех данных сессии
 */
public class session {
    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/json";
        Map<String, Object> session = query.session;
        JSONObject queryProperty = query.requestParam;
        JSONObject result = new JSONObject();

        try {
            // Определяем тип запроса по параметрам
            if (queryProperty.has("set_session")) {
                // Сохранение данных в сессию
                String key = queryProperty.getString("set_session");

                // Получаем данные из тела запроса - используем postByte вместо postCharBody
                if (query.postByte != null && query.postByte.length > 0) {
                    String postBodyStr = new String(query.postByte, StandardCharsets.UTF_8);

                    try {
                        // Пробуем распарсить как JSON
                        JSONObject vars = new JSONObject(postBodyStr);
                        session.put(key, vars);
                        result.put("success", true);
                        result.put("message", "Session saved: " + key);
                    } catch (JSONException e) {
                        // Если не JSON, сохраняем как строку
                        session.put(key, postBodyStr);
                        result.put("success", true);
                        result.put("message", "Session saved (as string): " + key);
                    }
                } else {
                    // Если тело пустое, сохраняем пустой объект
                    session.put(key, new JSONObject());
                    result.put("success", true);
                    result.put("message", "Empty session saved: " + key);
                }
            }
            else if (queryProperty.has("get_session")) {
                // Получение данных из сессии
                String key = queryProperty.getString("get_session");

                if (session.containsKey(key)) {
                    Object value = session.get(key);

                    if (value instanceof JSONObject) {
                        // Если это JSONObject, копируем все поля
                        JSONObject jsonValue = (JSONObject) value;
                        for (String field : jsonValue.keySet()) {
                            result.put(field, jsonValue.get(field));
                        }
                    } else if (value instanceof String) {
                        // Если строка, пробуем распарсить как JSON
                        try {
                            JSONObject jsonValue = new JSONObject((String) value);
                            for (String field : jsonValue.keySet()) {
                                result.put(field, jsonValue.get(field));
                            }
                        } catch (JSONException e) {
                            // Если не JSON, возвращаем как строку
                            result.put("value", value);
                        }
                    } else {
                        // Другие типы
                        result.put("value", String.valueOf(value));
                    }
                } else {
                    result.put("error", "Key not found: " + key);
                }
            }
            else if (queryProperty.has("action") && "getAll".equals(queryProperty.getString("action"))) {
                // Получение всех данных сессии
                for (Map.Entry<String, Object> entry : session.entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();

                    if (value instanceof JSONObject) {
                        result.put(key, value);
                    } else if (value instanceof String) {
                        try {
                            // Пробуем распарсить строку как JSON
                            JSONObject jsonValue = new JSONObject((String) value);
                            result.put(key, jsonValue);
                        } catch (JSONException e) {
                            // Если не JSON, сохраняем как строку
                            result.put(key, value);
                        }
                    } else {
                        result.put(key, String.valueOf(value));
                    }
                }
            }
            else {
                // По умолчанию - возвращаем информацию о сессии
                result.put("session_id", query.sessionID);
                result.put("data_size", session.size());
            }

        } catch (Exception e) {
            result.put("error", e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
        }

        return result.toString().getBytes();
    }
}