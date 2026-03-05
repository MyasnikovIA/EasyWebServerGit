package ru.miacomsoft;


import org.json.JSONObject;
import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.util.queryType.Get;


public class index {
    @Get(url="getPanoFromGrid.java",ext="json")
    public JSONObject onPage(HttpExchange query) {
        JSONObject obj = new JSONObject();
        return obj;
    }

    @Get(url="getPanoFromGrid.java",ext="json")
    public JSONObject onPagePost(HttpExchange query, JSONObject objIn) {

        query.responseHeaders.put("Access-Control-Allow-Origin","*");
        query.responseHeaders.put("Access-Control-Allow-Credentials","true");
        query.responseHeaders.put("Access-Control-Expose-Headers","FooBar");

        JSONObject obj = new JSONObject();
        return obj;
    }
}
