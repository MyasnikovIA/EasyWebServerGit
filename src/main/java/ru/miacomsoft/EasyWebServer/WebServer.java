package ru.miacomsoft.EasyWebServer;

import ru.miacomsoft.EasyWebServer.util.callbackType.CallbackPage;
import ru.miacomsoft.EasyWebServer.util.callbackType.CallbackProcedure;
import ru.miacomsoft.EasyWebServer.util.structObject.JavaInnerClassObject;
import ru.miacomsoft.EasyWebServer.util.structObject.JavaTerminalClassObject;

import java.io.File;
import java.io.FileWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static ru.miacomsoft.EasyWebServer.PacketManager.getPageJar;
import static ru.miacomsoft.EasyWebServer.PacketManager.getWebPage;

public class WebServer implements Runnable {
    public static CallbackProcedure callbackProcedure = null;
    private static final Logger LOGGER = Logger.getLogger(WebServer.class.getName());
    private static boolean isRunServer = false;
    private static WebServer server;

    /**
     *
     */
    public WebServer() {
    }

    /**
     * @param mainClass
     */
    public WebServer(Class<?> mainClass) {
        System.out.println("Список классов страниц: " + getWebPage(mainClass));
    }

    /**
     *
     */
    public static void start() {
        server = new WebServer();
        Thread thread = new Thread(server);
        thread.start();
        Runtime.getRuntime().addShutdownHook(new ShutDown());
        try {
            thread.join();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     *
     */
    public static void stop() {
        isRunServer = false;
    }

    /**
     *
     */
    static void shutDown() {
        try {
            LOGGER.info("Shutting down server...");
            server.stop();
        } catch (Exception e) {
            e.printStackTrace();
        }
        synchronized (server) {
            server.notifyAll();
        }
    }

    /**
     * @param path
     * @param contentText
     */
    public void onPage(String path, StringBuffer contentText) {
        ServerResource.pagesListContent.put(path, contentText);
    }

    /**
     * @param path
     * @param contentText
     * @param mime
     */
    public void onPage(String path, StringBuffer contentText, String mime) {
        ServerResource.pagesListContent.put(path, contentText);
    }

    /**
     * @param callbackProcedure
     */
    public void onPage(CallbackProcedure callbackProcedure) {
        this.callbackProcedure = callbackProcedure;
    }

    /**
     * прописывание контента в Java коде
     *
     * @param path         - путь к вызываемому содержимому
     * @param callbackPage - JAVA код страницы
     */
    public void onPage(String path, CallbackPage callbackPage) {
        ServerResource.pagesList.put(path, callbackPage);
    }

    /**
     * @param path
     * @param file
     */
    public void onPage(String path, File file) {
        ServerResource.pagesListFile.put(path, file);
    }

    /**
     * @param args
     */
    public void initConfig(String args) {
        if (args.length() == 0) {
            ServerConstant.config = new ServerConstant("config.ini");
        } else {
            ServerConstant.config = new ServerConstant(args);
        }
    }

    /**
     * @param confPropName
     * @param confPropValue
     * @return
     */
    /**
     * @param confPropName
     * @param confPropValue
     * @return
     */
    public Boolean config(String confPropName, String confPropValue) {
        // Проверяем, является ли значение списком (содержит точку с запятой)
        if (confPropValue.contains(";")) {
            return handleListProperty(confPropName, confPropValue);
        }

        // Специальная обработка для DATABASES
        if (confPropName.startsWith("DATABASES.")) {
            return handleDatabaseProperty(confPropName, confPropValue);
        }

        return ServerConstant.config.setProp(confPropName, confPropValue);
    }
    /**
     * Обрабатывает свойства для множественных БД
     */
    private Boolean handleDatabaseProperty(String confPropName, String confPropValue) {
        try {
            // Формат: DATABASES.dbname
            String[] parts = confPropName.split("\\.", 2);
            if (parts.length != 2) {
                return false;
            }

            String dbName = parts[1];
            DatabaseConfig dbConfig = DatabaseConfig.parse(confPropValue);

            if (dbConfig != null) {
                ServerConstant.config.DATABASES.put(dbName, dbConfig);
                System.out.println("Added database config: " + dbName + " -> " + dbConfig.getType());
                return true;
            }
        } catch (Exception e) {
            System.err.println("Error handling database property '" + confPropName + "': " + e.getMessage());
        }

        return false;
    }

    /**
     * Обрабатывает свойства, которые являются списками (массивами)
     * @param confPropName имя свойства
     * @param confPropValue значение свойства с разделителями
     * @return true если успешно обработано
     */
    private Boolean handleListProperty(String confPropName, String confPropValue) {
        try {
            // Если свойство явно не поддерживается как список, сохраняем как строку
            if (!isListProperty(confPropName)) {
                return ServerConstant.config.setProp(confPropName, confPropValue);
            }

            String[] values = confPropValue.split(";");
            List<String> valueList = new ArrayList<>();

            for (String value : values) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty()) {
                    valueList.add(trimmed);
                }
            }

            // Определяем, является ли свойство известным списком
            switch (confPropName) {
                case "WEBAPP_DIR":
                    // Для WEBAPP_DIR используем специальную обработку
                    ServerConstant.config.WEBAPP_DIRS.clear();
                    for (String dir : valueList) {
                        // Корректируем путь, если он относительный
                        String fullPath = dir;
                        if (!dir.contains("/") && !dir.contains("\\")) {
                            fullPath = ServerConstant.config.SERVER_HOM + File.separator + dir;
                        }
                        ServerConstant.config.WEBAPP_DIRS.add(fullPath);
                    }
                    // Для обратной совместимости сохраняем первый каталог в WEBAPP_DIR
                    if (!ServerConstant.config.WEBAPP_DIRS.isEmpty()) {
                        ServerConstant.config.WEBAPP_DIR = ServerConstant.config.WEBAPP_DIRS.get(0);
                    }
                    return true;

                case "LIB_CSS":
                    ServerConstant.config.LIB_CSS.clear();
                    ServerConstant.config.LIB_CSS.addAll(valueList);
                    return true;

                case "LIB_JS":
                    ServerConstant.config.LIB_JS.clear();
                    ServerConstant.config.LIB_JS.addAll(valueList);
                    return true;

                case "LIB_JAR":
                    ServerConstant.config.LIB_JAR.clear();
                    ServerConstant.config.LIB_JAR.addAll(valueList);
                    return true;

                default:
                    // Для других свойств сохраняем как обычную строку
                    // (клиент сам решит, как обрабатывать точку с запятой)
                    return ServerConstant.config.setProp(confPropName, confPropValue);
            }
        } catch (Exception e) {
            System.err.println("Error handling list property '" + confPropName + "': " + e.getMessage());
            return false;
        }
    }
    /**
     * Проверяет, является ли свойство списком
     */
    private boolean isListProperty(String propertyName) {
        return propertyName.endsWith("_LIST") ||
                propertyName.endsWith("_DIRS") ||
                propertyName.equals("WEBAPP_DIR") ||
                propertyName.equals("LIB_CSS") ||
                propertyName.equals("LIB_JS") ||
                propertyName.equals("LIB_JAR") ||
                propertyName.equals("MIME_MAP");
    }
    /**
     * @param confPropName
     * @param confPropValue
     * @return
     */
    public Boolean config(String confPropName, Boolean confPropValue) {
        return ServerConstant.config.setProp(confPropName, confPropValue);
    }

    /**
     * Подключить к серверу сторонние Jar файлы ВЭБ страниц
     *
     * @param pathJarFile
     */
    public void addPageJar(String pathJarFile) {
        System.out.println("Список страниц из Jar файла " + getPageJar(pathJarFile));
    }

    @Override
    public void run() {
        int port = Integer.parseInt(ServerConstant.config.DEFAULT_PORT);
        try {
            isRunServer = true;
            viewWebResource(port);
            ServerSocket ss = new ServerSocket(port);
            while (isRunServer == true) {
                // ждем новое подключение Socket клиента
                Socket socket = ss.accept();
                // Запускаем обработку нового соединение в паралельном потоке и ждем следующее соединение
                new Thread(new ServerResourceHandler(socket)).start();
            }
        } catch (Exception ex) {
            Logger.getLogger(WebServer.class.getName()).log(Level.SEVERE, null, ex);
        } catch (Throwable ex) {
            Logger.getLogger(WebServer.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * Показать в консоли список доступных ресурсов через браузер
     *
     * @param port
     */
    private void viewWebResource(int port) {
        System.out.print("port: ");
        System.out.println(port);
        System.out.print("http://127.0.0.1:");
        System.out.print(port);
        System.out.println("/");
        System.out.println("-----------------------");
        String portStr = String.valueOf(port);
        for (Map.Entry<String, JavaInnerClassObject> entry : ServerResource.pagesJavaInnerClass.entrySet()) {
            String key = entry.getKey();
            JavaInnerClassObject page = entry.getValue();
            System.out.print("queryType: ");
            System.out.println(page.queryType);
            System.out.print("mime: ");
            System.out.println(page.mime);
            System.out.println(page.classNat);
            System.out.print("method: ");
            System.out.println(page.method);
            System.out.print("http://127.0.0.1:");
            System.out.print(portStr);
            System.out.println("/" + page.url);
            System.out.println("-----------------------");
        }
        if (!ServerResource.pagesJavaTerminalClass.isEmpty()) {
            System.out.println("-----------------------");
            System.out.println("------- Terminal ------");
            System.out.println("-----------------------");
            for (Map.Entry<String, JavaTerminalClassObject> entry : ServerResource.pagesJavaTerminalClass.entrySet()) {
                String key = entry.getKey();
                JavaTerminalClassObject page = entry.getValue();
                System.out.print("queryType: ");
                System.out.println(page.classNat);
                System.out.print("method: ");
                System.out.println(page.method);
                System.out.print("http://127.0.0.1:");
                System.out.print(portStr);
                System.out.println("/" + page.url);
                System.out.println("-----------------------");
            }
        }

    }

    /**
     * @param stringMessage
     */
    protected synchronized static void WriteToFile(String stringMessage) {
        if (ServerConstant.config.LOG_FILE.length() == 0) {
            System.err.println(stringMessage);
        } else {
            try {
                FileWriter filelog = new FileWriter(new File(ServerConstant.config.LOG_FILE), true);
                filelog.write(stringMessage);
                filelog.flush();
            } catch (Exception error) {
                System.err.println(error);
            }
        }
    }
    /**
     * Определяет тип операционной системы
     * Возвращает: "windows", "linux", "mac", "solaris", "bsd", "aix", или "unknown"
     */
    public static String getOS() {
        String osName = System.getProperty("os.name").toLowerCase();

        if (osName.contains("win")) {
            return "windows";
        } else if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
            return "linux";
        } else if (osName.contains("mac")) {
            return "mac";
        } else if (osName.contains("sunos") || osName.contains("solaris")) {
            return "solaris";
        } else if (osName.contains("bsd")) {
            return "bsd";
        } else {
            return "unknown";
        }
    }

    /**
     * Проверяет, является ли ОС Windows
     */
    public static boolean isWindows() {
        return getOS().equals("windows");
    }

    /**
     * Проверяет, является ли ОС Linux
     */
    public static boolean isLinux() {
        return getOS().equals("linux");
    }

    /**
     * Проверяет, является ли ОС Unix-подобной (Linux, Mac, BSD, Solaris)
     */
    public static boolean isUnixLike() {
        String os = getOS();
        return os.equals("linux") || os.equals("mac") ||
                os.equals("solaris") || os.equals("bsd") ||
                os.equals("aix");
    }
}


