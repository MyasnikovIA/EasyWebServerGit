package ru.miacomsoft.EasyWebServer.util.callbackType;

import ru.miacomsoft.EasyWebServer.HttpExchange;

public interface CallbackProcedure {
    public void call(HttpExchange query);
}
