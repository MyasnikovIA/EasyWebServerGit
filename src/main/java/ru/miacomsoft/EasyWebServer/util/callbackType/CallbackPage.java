package ru.miacomsoft.EasyWebServer.util.callbackType;

import ru.miacomsoft.EasyWebServer.HttpExchange;

public interface CallbackPage {
    public byte[] call(HttpExchange query);
}
