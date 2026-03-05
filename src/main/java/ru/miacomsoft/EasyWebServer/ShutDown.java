package ru.miacomsoft.EasyWebServer;

/**
 * Класс остановки сервера в новом потоке
 */
public class ShutDown  extends Thread {
    @Override
    public void run() {
        WebServer.shutDown();
    }
}
