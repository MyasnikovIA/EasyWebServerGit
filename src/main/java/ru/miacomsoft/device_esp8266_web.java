package ru.miacomsoft;

import org.json.JSONObject;
import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.util.queryType.Get;
import ru.miacomsoft.EasyWebServer.util.queryType.Post;

public class device_esp8266_web {
    public  static JSONObject GLOBAL_LIST_DEVICE = new JSONObject();
    @Post(url="device_esp8266.java",ext="json")
    public JSONObject onPage(HttpExchange query) {
        JSONObject obj = new JSONObject();
        String val =new String(query.postByte);
        for (String elem : val.split("&")) {
            String[] valSubArr = elem.split("=");
            obj.put(valSubArr[0], valSubArr[1]);
        }
        if (obj.has("device")) {
            GLOBAL_LIST_DEVICE.put((String) obj.get("device"),obj);
        }
        System.out.println(obj.toString(4));
        return GLOBAL_LIST_DEVICE;
    }

    @Get(url="device_esp8266_view.java",ext="json")
    public JSONObject onPageView(HttpExchange query) {
        return GLOBAL_LIST_DEVICE;
    }

}
