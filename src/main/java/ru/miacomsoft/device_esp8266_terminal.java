package ru.miacomsoft;

import org.json.JSONObject;
import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.util.onTerminal;
import ru.miacomsoft.EasyWebServer.util.queryType.Get;

import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static ru.miacomsoft.EasyWebServer.HttpExchange.BROADCAST_MESSAGE_LIST;
import static ru.miacomsoft.device_esp8266_web.GLOBAL_LIST_DEVICE;

public class device_esp8266_terminal {

    /*
ESP8266
TERM /device_esp8266_terminal_message.java
device_name: HowerBord_001
UserName: user1

Терминал отправляющий команды
TERM /device_esp8266_terminal_message_send.java
device_name: Oper_001
device_name_connect: HowerBord_001


--------------------------------------------------
TERM /device_esp8266_terminal_message_send.java
device_name: Oper_001
device_name_connect: HowerBord_001



up:100
up:100;dir:1
up:100;dir:2
up:100;dir:3
up:100;dir:4
up:100;dir:4;button_a:1
up:100;dir:4;button_b:1
up:100;dir:4;button_select:1
dir:0

     */

    public static ConcurrentHashMap<String,HttpExchange> GLOBAL_LIST_DEVICE_CONNECT = new ConcurrentHashMap<>();

    @onTerminal(url="device_esp8266_terminal_message.java")
    public void onPage(HttpExchange query) {
        String DeviceName = "";
        String UserName = "";
        String lastCommandName = "";
        String DeviceNameSendTo = "";
        System.out.println("query.headers " + query.headers);
        if (query.headers.containsKey("device_name")) {
            DeviceName = (String) query.headers.get("device_name");
        }
        GLOBAL_LIST_DEVICE_CONNECT.put(DeviceName, query);
        query.write(("{\"register\":\"" + DeviceName + "\"}").getBytes());
        query.write("\r\n".getBytes());
        boolean isRunning = true;
        while (isRunning) {
            String message = query.readLineTerm();
            if(message.length()==0) {
                continue;
            }
            if(message==null) {
                GLOBAL_LIST_DEVICE_CONNECT.remove(DeviceName);
                break;
            }
            System.out.println("device_esp8266_terminal_message---"+message);
            if (query.queryPtP != null) {
                query.queryPtP.write((message).getBytes());
            }
            if (!query.queryPtP.isConnected()) {
                try {
                    query.queryPtP.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                break;
            }
        }
        if (GLOBAL_LIST_DEVICE_CONNECT.containsKey(DeviceName)) {
            GLOBAL_LIST_DEVICE_CONNECT.remove(DeviceName);
        }
    }

    @onTerminal(url="device_esp8266_terminal_message_send.java")
    public void onSend(HttpExchange query) {
        String DeviceName = "";
        String DeviceNameConnect = "";
        if (query.headers.containsKey("device_name")) {
            DeviceName = (String) query.headers.get("device_name");
        }
        if (query.headers.containsKey("device_name_connect")) {
            DeviceNameConnect = (String) query.headers.get("device_name_connect");
        }
        if (!GLOBAL_LIST_DEVICE_CONNECT.containsKey(DeviceNameConnect)) {
            query.write(("{\"error\":\"" + DeviceNameConnect + "\",\"message\":\"устройство не найдено\"}").getBytes());
        }
        GLOBAL_LIST_DEVICE_CONNECT.put(DeviceName, query);
        HttpExchange querySend = (HttpExchange) GLOBAL_LIST_DEVICE_CONNECT.get(DeviceNameConnect);
        querySend.queryPtP = query;
        query.queryPtP = querySend;
        query.write(("{\"register\":\"" + DeviceName + "\"}").getBytes());
        System.out.println("device_esp8266_terminal_message_send---"+DeviceName);
        boolean isRunning = true;
        while (isRunning) {
            try {
                // Проверяем соединение перед чтением
                if (query.socket.isClosed() || !query.socket.isConnected()) {
                    System.out.println("Socket connection lost");
                    break;
                }
                // Читаем сообщение с таймаутом
                String message = query.readLineTerm();
                if (message == null) {
                    break;
                }
                if (message.length() == 0) {
                    continue;
                }
                System.out.println("device_esp8266_terminal_message_send---" + message);

                // Проверяем соединение перед отправкой
                if (querySend.socket.isClosed() || !querySend.socket.isConnected()) {
                    System.out.println("Send socket connection lost");
                    break;
                }

                // Отправляем сообщение
                querySend.write(message.getBytes());
          // } catch (SocketException e) {
          //     System.err.println("Socket error: " + e.getMessage());
          //     break;
          // } catch (IOException e) {
          //     System.err.println("IO error: " + e.getMessage());
          //     break;
            } catch (Exception e) {
                System.err.println("Unexpected error: " + e.getMessage());
                break;
            }
        }
        query.queryPtP = null;
        querySend.queryPtP = null;

        /*
        // Удаляем устройство из списка при отключении
        String deviceToRemove3 = "";
        for (Map.Entry<String, HttpExchange> entry : GLOBAL_LIST_DEVICE_CONNECT.entrySet()) {
            if (entry.getValue() == query) {
                deviceToRemove3 = entry.getKey();
                break;
            }
        }
        if (!deviceToRemove3.isEmpty()) {
            GLOBAL_LIST_DEVICE_CONNECT.remove(deviceToRemove3);
            System.out.println("Device disconnected: " + deviceToRemove3);
        }

         */
    }


    @Get(url="device_esp8266_terminal_message_send.java",ext="json")
    public JSONObject onPageViewWeb(HttpExchange query) {
        System.out.println(BROADCAST_MESSAGE_LIST);
        System.out.println(GLOBAL_LIST_DEVICE_CONNECT);
        JSONObject res = new JSONObject();
        for(Map.Entry<String, HttpExchange> entry : GLOBAL_LIST_DEVICE_CONNECT.entrySet()) {
            String key = entry.getKey();
            HttpExchange value = entry.getValue();
            res.put(key,value.toString());
        }
        return res;
    }

    @Get(url="device_esp8266_view_terminal.java",ext="json")
    public JSONObject onPageView(HttpExchange query) {
        System.out.println(BROADCAST_MESSAGE_LIST);
        System.out.println(GLOBAL_LIST_DEVICE_CONNECT);
        JSONObject res = new JSONObject();
        for(Map.Entry<String, HttpExchange> entry : GLOBAL_LIST_DEVICE_CONNECT.entrySet()) {
            String key = entry.getKey();
            HttpExchange value = entry.getValue();
            res.put(key,value.toString());
        }
        return res;
    }

}
