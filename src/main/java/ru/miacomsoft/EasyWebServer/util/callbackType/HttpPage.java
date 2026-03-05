package ru.miacomsoft.EasyWebServer.util.callbackType;

import ru.miacomsoft.EasyWebServer.HttpExchange;

public interface HttpPage {
    public abstract byte[]  onPage (HttpExchange query);
}

