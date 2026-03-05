package ru.miacomsoft.EasyWebServer.component;


import org.json.JSONObject;
import ru.miacomsoft.EasyWebServer.HttpExchange;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class loginDataBase {
    public static byte[] onPage(HttpExchange query) {
        // http://127.0.0.1:8080/{component}/loginDataBase
        query.mimeType = "application/javascript"; // Изменить mime ответа
        Map<String, Object> session = query.session;
        JSONObject queryProperty = query.requestParam;
        JSONObject vars = new JSONObject(new String(query.postCharBody));
        JSONObject info = new JSONObject();
        info.put("connect", false);
        Connection conn = null;
        if (!session.containsKey("DATABASE")) {
            session.put("DATABASE", new HashMap<String, Object>());
        }
        Map<String, Object> data_base = (Map<String, Object>) session.get("DATABASE");
        if (data_base.containsKey("CONNECT")) {
            conn = (Connection) data_base.get("CONNECT");
            if (conn != null) {
                try {
                    if (!conn.isClosed()) {
                        conn.close();
                    }
                } catch (SQLException e) {
                    info.put("error", e.getClass().getName() + ": " + e.getMessage());
                }
            }
        }
        String username = "";
        String userpass = "";
        if (queryProperty.has("logoff")) {
            data_base.put("DATABASE_USER_NAME", "");
            data_base.put("DATABASE_USER_PASS", "");
            if (data_base.containsKey("CONNECT")) {
                conn = (Connection) data_base.get("CONNECT");
                try {
                    if (!conn.isClosed()) {
                        conn.close();
                        info.put("disconnect", true);
                    }
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            }
            ;
            data_base.put("CONNECT", null);
            info.put("connect", false);
            return info.toString().getBytes();
        }
        if (queryProperty.has("username")) {
            username = queryProperty.getString("username");
        }
        if (queryProperty.has("userpass")) {
            userpass = queryProperty.getString("userpass");
        }
        if (vars.has("username")) {
            username = vars.getString("username");
        }
        if (vars.has("userpass")) {
            userpass = vars.getString("userpass");
        }
        data_base.put("DATABASE_USER_NAME", username);
        data_base.put("DATABASE_USER_PASS", userpass);
        conn = ru.miacomsoft.EasyWebServer.PostgreQuery.getConnect(String.valueOf(data_base.get("DATABASE_USER_NAME")), String.valueOf(data_base.get("DATABASE_USER_PASS")), info);
        data_base.put("CONNECT", conn);
        if (conn != null) {
            info.put("redirect", "/" + ru.miacomsoft.EasyWebServer.ServerConstant.config.INDEX_PAGE);
            info.put("connect", true);
        }
        return info.toString().getBytes();
    }
}
