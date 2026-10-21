package ru.miacomsoft.EasyWebServer;

import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import ru.miacomsoft.EasyWebServer.util.structObject.JavaInnerClassObject;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import static ru.miacomsoft.EasyWebServer.HttpExchange.parseErrorRunJava;
import static ru.miacomsoft.EasyWebServer.ServerResource.componentListClass;
import static ru.miacomsoft.EasyWebServer.WebServer.WriteToFile;
import static ru.miacomsoft.EasyWebServer.WebServer.callbackProcedure;

/**
 * Обработчик HTTP-запросов.
 * Отвечает за:
 * - чтение заголовков запроса,
 * - определение типа запроса (GET/POST/TERM),
 * - обработку параметров,
 * - выбор ресурса для ответа,
 * - вызов callback-функций,
 * - отправку HTML-ответа клиенту.
 */
public class ServerResourceHandler implements Runnable {
    /**
     * Глобальный объект для хранения пользовательских данных на сервере.
     */
    public static JSONObject SERVER_SHARE = new JSONObject();

    /**
     * Кэш статических ресурсов (HTML, CSS, JS).
     */
    private final Map<String, Resource> resources = new HashMap<>();

    /**
     * Хранит дату последней модификации файлов.
     */
    private final Map<String, String> resourcesDateTime = new HashMap<>();

    /**
     * Список активных сессий.
     */
    public static HashMap<String, HashMap<String, Object>> sessionList = new HashMap<>();

    /**
     * Утилита для выполнения Java-кода из строки.
     */
    public static JavaStrExecut javaStrExecut = new JavaStrExecut();

    /**
     * Текущий объект запроса.
     */
    private HttpExchange query;

    /**
     * Конструктор.
     *
     * @param socket клиентский сокет
     * @throws IOException   если произошла ошибка при работе с сокетом
     * @throws JSONException если не удалось создать JSON-объект
     */
    public ServerResourceHandler(Socket socket) throws IOException, JSONException {
        this.query = new HttpExchange(socket, null);
    }

    /**
     * Метод запуска потока.
     * Вызывает обработчики запросов и завершает соединение.
     */
    @Override
    public void run() {
        try {
            // Явно завершаем TLS handshake для SSLSocket. Иначе первый read()
            // внутри readRequestHeader() запустит handshake неявно, а ошибки
            // (plain HTTP на TLS-порт, несовместимый клиент) прилетят в общий
            // catch как SEVERE с null-сообщением.
            if (!performTlsHandshake()) {
                return;
            }
            if (readRequestHeader()) {
                sendResponseQuery();
            }
        } catch (javax.net.ssl.SSLException sslEx) {
            // Plain HTTP пришёл на TLS-порт (http://host:port вместо https://host:port,
            // либо сканер/healthcheck). Это клиентская ошибка, не SEVERE.
            String remote = (query != null && query.socket != null)
                    ? String.valueOf(query.socket.getRemoteSocketAddress()) : "?";
            Logger.getLogger(ServerResourceHandler.class.getName()).log(
                    Level.INFO,
                    "TLS handshake failed from " + remote + ": " + sslEx.getClass().getSimpleName()
                            + " (" + sslEx.getMessage() + "). "
                            + "Используйте https://<host>:<port>/ — сервер слушает TLS на этом порту.");
        } catch (Exception ex) {
            Logger.getLogger(ServerResourceHandler.class.getName()).log(
                    Level.SEVERE, "Request handling error", ex);
        } finally {
            try {
                if (query != null) {
                    query.close();
                    query = null;
                }
            } catch (Exception ex) {
                Logger.getLogger(ServerResourceHandler.class.getName()).log(
                        Level.WARNING, "Socket close error", ex);
            }
        }
    }

    /**
     * Явно завершает TLS handshake, если socket — SSLSocket.
     * Возвращает true, если сокет не TLS либо handshake прошёл успешно.
     * Возвращает false, если handshake провалился (тогда лог уже написан).
     */
    private boolean performTlsHandshake() {
        Socket raw = (query != null) ? query.socket : null;
        if (!(raw instanceof javax.net.ssl.SSLSocket)) {
            return true; // обычный HTTP
        }
        javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) raw;
        try {
            ssl.startHandshake();
            javax.net.ssl.SSLSession sess = ssl.getSession();
            System.out.println("[TLS] Handshake OK from " + ssl.getRemoteSocketAddress()
                    + " protocol=" + sess.getProtocol()
                    + " cipher=" + sess.getCipherSuite());
            return true;
        } catch (javax.net.ssl.SSLException sslEx) {
            String remote = String.valueOf(ssl.getRemoteSocketAddress());
            Logger.getLogger(ServerResourceHandler.class.getName()).log(
                    Level.INFO,
                    "TLS handshake failed from " + remote + ": " + sslEx.getClass().getSimpleName()
                            + " (" + sslEx.getMessage() + "). "
                            + "Скорее всего, клиент пришёл по http:// вместо https://.");
            return false;
        } catch (java.io.IOException ioEx) {
            Logger.getLogger(ServerResourceHandler.class.getName()).log(
                    Level.INFO,
                    "TLS handshake I/O error: " + ioEx.getMessage());
            return false;
        }
    }

    /**
     * Расширяет массив символов, добавляя новые элементы.
     *
     * @param original   исходный массив
     * @param additional дополнительные данные
     * @param lenArr     количество добавляемых элементов
     * @return новый массив
     */
    public static char[] expandArray(char[] original, char[] additional, int lenArr) {
        char[] newArray = new char[original.length + lenArr];
        System.arraycopy(original, 0, newArray, 0, original.length);
        System.arraycopy(additional, 0, newArray, original.length, lenArr);
        return newArray;
    }

    /**
     * Ищет последовательность символов в массиве.
     *
     * @param buffer       массив для поиска
     * @param sequence     строка, которую ищем
     * @param lastPosition если true, возвращает позицию после найденной последовательности
     * @return индекс последовательности или -1
     */
    public static int findSequence(char[] buffer, String sequence, boolean lastPosition) {
        char[] seqChars = sequence.toCharArray();
        for (int i = 0; i <= buffer.length - seqChars.length; i++) {
            boolean found = true;
            for (int j = 0; j < seqChars.length; j++) {
                if (buffer[i + j] != seqChars[j]) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return lastPosition ? i + sequence.length() : i;
            }
        }
        return -1;
    }

    private boolean readRequestHeader() throws IOException {
        int charInt;
        StringBuffer sb = new StringBuffer();
        StringBuffer sbTmp = new StringBuffer();
        // читаем заголовок HTML запроса
        char[] bufferHead = new char[1024];
        char[] bufferArrHead = new char[0];
        char[] bufferArrPost = new char[0];
        while ((charInt = query.inputStreamReader.read(bufferHead)) > 0) {
            bufferArrHead = expandArray(bufferArrHead, bufferHead, charInt);
            //String strHead = new String(bufferArrHead);
            int index = findSequence(bufferArrHead, "\r\r", true);
            if (index == -1) {
                index = findSequence(bufferArrHead, "\r\n\r\n", true);
            }
            if (index == -1) {
                index = findSequence(bufferArrHead, "\n\n", true);
            }
            if (index != -1) {
                char[] beforeSequence = new char[index];
                bufferArrPost = new char[bufferArrHead.length - index];
                // Копируем данные до последовательности
                System.arraycopy(bufferArrHead, 0, beforeSequence, 0, index);
                sb.append(new String(beforeSequence));
                // Копируем данные после последовательности и помещаем его в тело post запроса
                System.arraycopy(bufferArrHead, index, bufferArrPost, 0, bufferArrPost.length);
                break;
            }
        }

        query.typeQuery = "GET";
        query.headSrc = sb;
        if ((sb.toString().toLowerCase().indexOf("content-length: ")) != -1) {
            // Читаем тело POST запроса
            String sbTmp2 = "0";
            if (sb.toString().indexOf("Content-Length: ") != -1) {
                sbTmp2 = sb.toString().substring(sb.toString().indexOf("Content-Length: ") + "Content-Length: ".length(), sb.toString().length());
            } else if ((sb.toString().toLowerCase().indexOf("content-length: ")) != -1) {
                sbTmp2 = sb.toString().substring(sb.toString().indexOf("content-length: ") + "Content-Length: ".length(), sb.toString().length());
            }
            String lengPostStr = sbTmp2;
            if (sbTmp2.indexOf("\n") != -1) {
                lengPostStr = sbTmp2.substring(0, sbTmp2.indexOf("\n")).replace("\r", "");
            }
            int LengPOstBody = Integer.valueOf(lengPostStr);
            if (LengPOstBody > 0) {
                int maxArrBuf = 1024;
                if (maxArrBuf > LengPOstBody) {
                    maxArrBuf = LengPOstBody;
                }
                int syzeDifference = LengPOstBody - bufferArrPost.length;
                if ((syzeDifference == 4) || (syzeDifference == 2)) {
                    //todo:
                    // Подумать как переписать этот костыль.
                } else if (bufferArrPost.length < LengPOstBody) {
                    char[] bufferArr = new char[maxArrBuf];
                    boolean isBreak = false;
                    while ((charInt = query.inputStreamReader.read(bufferArr)) > 0 && isBreak == false) {
                        bufferArrPost = expandArray(bufferArrPost, bufferArr, charInt);
                        if (charInt < bufferArr.length) {
                            break;
                        }
                        if (query.socket.isConnected() == false) {
                            return false;
                        }
                        if (bufferArrPost.length > LengPOstBody) {
                            isBreak = true;
                            break;
                        }
                    }
                }
                query.postCharBody = bufferArrPost;
                query.postByte = new String(bufferArrPost).getBytes(StandardCharsets.UTF_8);
                query.typeQuery = "POST";
                if (query.headers.containsKey("Content-Type")) {
                    String contentType = ((String) query.headers.get("Content-Type")).toLowerCase();
                    switch (contentType) {
                        case "application/json":
                            // Если тело — JSON, можно попытаться распарсить
                            try {
                                query.messageJson = new JSONObject(bufferArrPost);
                            } catch (JSONException ignored) {
                                /// todo: вернуть ошибку парсинга в JSON объект
                                ignored.printStackTrace();
                            }
                            break;
                        case "application/x-www-form-urlencoded":
                            for (String param : new String(bufferArrPost).split("&")) {
                                if (param.isEmpty()) continue;
                                String[] keyValue = param.split("=", 2);
                                String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8.name());
                                String value = keyValue.length > 1 ? URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8.name()) : "";
                                try {
                                    query.requestParam.put(key, value);
                                    JSONObject obj = new JSONObject();
                                    obj.put(key, value);
                                    query.requestParamArray.put(obj);
                                } catch (JSONException e) {
                                    e.printStackTrace();
                                }
                            }
                            break;
                        default:
                            /// todo: уточнить какие бывают форматы и дописать их обработку
                    }
                    ;
                }
            }
        }
        // Парсим заголовок
        int indLine = 0;
        for (String titleLine : sb.toString().split("\r")) {
            titleLine = titleLine.replace("\n", "");
            indLine++;

            // Пустая строка после разделителя — пропускаем (иначе будет
            // StringIndexOutOfBoundsException на substring(0, -1) при пустом
            // preconnect-запросе от Chrome после TLS-handshake).
            if (titleLine.isEmpty()) {
                continue;
            }

            if (indLine == 1) {
                // Первая строка: "METHOD /path HTTP/1.1"
                int spaceIdx = titleLine.indexOf(' ');
                if (spaceIdx <= 0) {
                    // Невалидная request line — считаем как запрос к главной
                    query.typeQuery = "GET";
                    query.requestPath = ServerConstant.config.INDEX_PAGE;
                    query.requestText = "/" + ServerConstant.config.INDEX_PAGE;
                    break;
                }

                query.typeQuery = titleLine.substring(0, spaceIdx);

                // Остаток после "METHOD " — без ведущего пробела
                String rest = titleLine.substring(spaceIdx + 1).trim();

                // Отрезаем " HTTP/x.y" в конце, если есть
                int httpIdx = rest.lastIndexOf(" HTTP/");
                if (httpIdx > 0) {
                    rest = rest.substring(0, httpIdx);
                }

                // Пусто или "/" — отдаём INDEX_PAGE
                if (rest.isEmpty() || rest.equals("/")) {
                    rest = ServerConstant.config.INDEX_PAGE;
                }

                titleLine = URLDecoder.decode(rest, StandardCharsets.UTF_8.toString());
                query.requestText = titleLine;
                query.requestPath = titleLine;

                // Разбираем параметры из URI
                if (titleLine.contains("?")) {
                    String[] parts = titleLine.split("\\?", 2);
                    query.requestPath = parts[0];

                    for (String param : parts[1].split("&")) {
                        if (param.isEmpty()) continue;
                        String[] keyValue = param.split("=", 2);
                        String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8.name());
                        String value = keyValue.length > 1
                                ? URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8.name())
                                : "";
                        try {
                            query.requestParam.put(key, value);
                            JSONObject obj = new JSONObject();
                            obj.put(key, value);
                            query.requestParamArray.put(obj);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }
                    }
                    query.requestPath = query.requestText.substring(0, query.requestText.indexOf("?"));
                }
            } else {
                if (titleLine.indexOf(":") != -1) {
                    String key = titleLine.substring(0, titleLine.indexOf(":")).trim();
                    String val = titleLine.substring(titleLine.indexOf(":") + 1).trim();
                    query.headers.put(key, val);
                    switch (key) {
                        case "Cookie":
                            for (String elem : val.split("; ")) {
                                String[] valSubArr = elem.split("=");
                                if (valSubArr.length == 2) {
                                    query.cookie.put(valSubArr[0], valSubArr[1]);
                                    if ((valSubArr[0].trim()).toLowerCase().equals("session")) {
                                        query.sessionID = valSubArr[1];
                                    }
                                }
                            }
                            break;
                    }
                }
            }
        }
        query.expansion = getFileExt(query.requestPath).toLowerCase();
        query.session = getSession(query); // генерация или зпгрузка старой сессии
        if (query.typeQuery.toUpperCase().equals("TERM")) { // Обработка запроса с терминала
            sendResponseTerminal();
            return false;
        }
        query.mimeType = getFileMime(query.requestPath);
        return true;
    }

    /**
     * Обрабатывает запрос от терминала.
     */
    private void sendResponseTerminal() {
        if (!javaStrExecut.runJavaTerminalFile(query)) {

        }
    }

    /**
     * Процедура отправки ответа клиенту (браузеру)
     */
    private void sendResponseQuery() {
        if (query.requestPath == null ||
                query.requestPath.equals("/") ||
                query.requestPath.trim().isEmpty()) {
            query.requestPath = ServerConstant.config.INDEX_PAGE;
        }
        // Установка MIME-типа
        setMimeType();

        // Обработка callback
        if (callbackProcedure != null) {
            callbackProcedure.call(query);
            return;
        }

        // Установка режима отладки
        setDebugMode();

        String ext = getFileExt(query.requestPath).toLowerCase();

        // Обработка компонентов
        if (query.requestPath.contains("{component}")) {
            ServerResourceHandler.javaStrExecut.runJavaServerlet(query);
            return;
        } else if ("component".equals(ext)) {
            ServerResourceHandler.javaStrExecut.runJavaComponent(query);
            return;
        }

        // Обработка основного запроса
        processMainRequest();
    }

    private void setMimeType() {
        query.mimeType = getFileMime(query.requestPath);
        if (query.requestParam.has("mime")) {
            query.mimeType = getFileMime(query.requestParam.getString("mime"));
        } else if (query.requestParam.has("mimetype")) {
            query.mimeType = query.requestParam.getString("mimetype");
        }
    }

    private void setDebugMode() {
        // Режим отладки теперь хранится в сессии пользователя
        if (query.requestParam.has("debug")) {
            boolean debugMode = "1".equals(query.requestParam.getString("debug"));
            query.session.put("debug_mode", debugMode);
        }
    }

    private void processMainRequest() {
        String resourcePath = null;
        File file = null;

        // 1. Проверяем специальные ресурсы (Java классы, callback и т.д.)
        if (ServerResource.pagesJavaInnerClass.containsKey(query.requestPath)) {
            processJavaInnerClass();
            return;
        } else if (ServerResource.pagesListContent.containsKey(query.requestPath)) {
            query.sendHtml(ServerResource.pagesListContent.get(query.requestPath).toString());
            return;
        } else if (ServerResource.pagesList.containsKey(query.requestPath)) {
            resourcePath = buildResourcePathForPageList();
            processPageListResource(resourcePath);
            return;
        }
        // 2. Ищем файл во всех доступных каталогах
        file = findFileInAllDirectories(query.requestPath);
        if (file != null) {
            resourcePath = file.getAbsolutePath();
        }
        // 3. Обработка найденного файла
        if (file != null && file.exists()) {
            processFileResource(file, resourcePath);
        } else {
            handleNotFound();
        }
    }

    private String buildResourcePath() {
        if (query == null || query.requestPath == null) {
            if (!ServerConstant.config.WEBAPP_DIRS.isEmpty()) {
                return Paths.get(ServerConstant.config.WEBAPP_DIRS.get(0)).toString();
            }
            return Paths.get(ServerConstant.config.WEBAPP_DIR).toString();
        }

        if (ServerResource.pagesListFile != null) {
            File cachedFile = ServerResource.pagesListFile.get(query.requestPath);
            if (cachedFile != null) {
                return cachedFile.toPath().toString();
            }
        }

        File file = findFileInAllDirectories(query.requestPath);
        if (file != null) {
            return file.getAbsolutePath();
        }

        Path webappPath = Paths.get(ServerConstant.config.WEBAPP_DIR);
        return webappPath.resolve(query.requestPath).normalize().toString();
    }

    private void processJavaInnerClass() {
        JavaInnerClassObject page = ServerResource.pagesJavaInnerClass.get(query.requestPath);
        query.mimeType = page.mime;

        try {
            Object result = page.method.invoke(page.ObjectInstance, query);
            byte[] messageBytes = (page.queryType == null)
                    ? (byte[]) result
                    : ((JSONObject) result).toString().getBytes();

            if (messageBytes != null) {
                query.sendHtml(new String(messageBytes));
            }
        } catch (IllegalAccessException | InvocationTargetException e) {
            query.sendHtml(parseErrorRunJava(e));
        }
    }

    private void processPageListResource(String resourcePath) {
        byte[] res = ServerResource.pagesList.get(query.requestPath).call(query);
        if (res != null) {
            if ("html".equals(query.mimeType) || query.mimeType != null && query.mimeType.contains("html")) {
                String rootPath = getWebappDirForFile(new File(resourcePath));
                res = readResourceContent(new String(res, StandardCharsets.UTF_8), resourcePath,
                        rootPath, "");
            }
            query.sendHtml(new String(res, StandardCharsets.UTF_8));
        }
    }

    private void processFileResource(File file, String resourcePath) {
        String ext = getFileExt(query.requestPath).toLowerCase();

        if ("java".equals(ext)) {
            ServerResourceHandler.javaStrExecut.runJavaFile(query);
            return;
        }

        if (file.length() > ServerConstant.config.LENGTH_CAHE || isBinaryFile(ext)) {
            query.sendByteFile(file);
            return;
        }

        String lastModified = String.valueOf(file.lastModified());
        Resource res;

        boolean debugMode = false;
        if (query.session != null && query.session.containsKey("debug_mode")) {
            debugMode = (boolean) query.session.get("debug_mode");
        }

        if (!debugMode) {
            res = getCachedResource(file, resourcePath, lastModified);
        } else {
            res = readResource(file, query, resourcePath, getWebappDirForFile(file), "");
        }

        query.mimeType = res.mimeType;

        if (res.isBinary) {
            query.sendByteFile(file);
        } else {
            query.sendHtml(new String(res.content, StandardCharsets.UTF_8));
        }
    }

    private Resource getCachedResource(File file, String resourcePath, String lastModified) {
        if (resources.get(resourcePath) == null || !resourcesDateTime.get(resourcePath).equals(lastModified)) {
            String rootPath = getWebappDirForFile(file);
            Resource newRes = readResource(file, query, resourcePath, rootPath, "");
            resources.put(resourcePath, newRes);
            resourcesDateTime.put(resourcePath, lastModified);
            return newRes;
        }
        return resources.get(resourcePath);
    }

    private void handleNotFound() {
        query.mimeType = "text/html";
        String page404Path = ServerConstant.config.PAGE_404;

        if (page404Path.isEmpty()) {
            StringBuilder errorMsg = new StringBuilder();
            errorMsg.append("<h1>404 - Page not found</h1>");
            errorMsg.append("<p><strong>Requested path:</strong> ").append(query.requestPath).append("</p>");

            if (!ServerConstant.config.WEBAPP_DIRS.isEmpty()) {
                errorMsg.append("<p><strong>Searched in directories:</strong></p><ul>");
                for (String dir : ServerConstant.config.WEBAPP_DIRS) {
                    String fullPath = dir + File.separator + query.requestPath.replaceAll("/", File.separator);
                    errorMsg.append("<li>").append(fullPath).append("</li>");
                }
                errorMsg.append("</ul>");
            }

            query.sendHtml(errorMsg.toString());
        } else {
            File found404 = findFileInAllDirectories(page404Path);

            if (found404 != null) {
                query.sendFile(found404.getAbsolutePath());
            } else {
                query.sendHtml("Page not found");
            }
        }

        logNotFoundRequest();
    }

    private void logNotFoundRequest() {
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy");
        String timestamp = dtf.format(LocalDateTime.now());

        String logMessage = String.format(
                "------------------------------------%n" +
                        "%s (NSO +7)%n" +
                        "query.requestPath %s%n" +
                        "query.requestParam %s%n" +
                        "%s%n" +
                        "------------------------------------%n",
                timestamp,
                query.requestPath,
                query.requestParam,
                query.headSrc.toString()
        );

        WriteToFile(logMessage);
    }

    private byte[] readResourceContent(String fileContent, String resourcePath, String rootPath, String fragmentName) {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try {
            Document doc = Jsoup.parse(fileContent);
            Element elsDst;

            if (fragmentName.isEmpty()) {
                Element els = doc.getElementsByTag("body").get(0);
                doc.attr("doc_path", resourcePath);
                doc.attr("rootPath", rootPath);
                parseElementV2(doc, els, null);
                doc.removeAttr("doc_path");
                doc.removeAttr("rootPath");
                elsDst = doc.getElementsByTag("html").get(0);
            } else {
                Elements elements = doc.select("[name=" + fragmentName + "]");
                elsDst = doc;
                for (Element element : elements) {
                    parseElementV2(doc, element, null);
                }
            }

            bout.write(elsDst.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bout.toByteArray();
    }

    private Resource readResource(File file, HttpExchange query, String resourcePath, String rootPath, String fragmentName) {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        String ext = getFileExt(resourcePath).toLowerCase();
        boolean isBinary = isBinaryFile(ext);
        String mimeType = getFileMime(resourcePath);

        try {
            if ("html".equals(ext) && !isBinary) {
                Path path = Paths.get(resourcePath);
                byte[] bytes = Files.readAllBytes(path);
                Document doc = Jsoup.parse(new String(bytes, StandardCharsets.UTF_8));
                Element els = doc.getElementsByTag("body").get(0);
                doc.attr("doc_path", resourcePath);
                doc.attr("rootPath", rootPath);

                boolean debugMode = query.isDebugMode();
                doc.attr("debug_mode", String.valueOf(debugMode));

                parseElementV2(doc, els, null);
                doc.removeAttr("doc_path");
                doc.removeAttr("rootPath");
                doc.removeAttr("debug_mode");
                doc.getElementsByTag("body").get(0).replaceWith(els);
                Element elsDst = doc.getElementsByTag("html").get(0);
                bout.write(elsDst.toString().getBytes(StandardCharsets.UTF_8));
            } else {
                InputStream in = new FileInputStream(resourcePath);
                byte[] bs = new byte[4096];
                int lenReadByts;
                while ((lenReadByts = in.read(bs)) >= 0) {
                    bout.write(bs, 0, lenReadByts);
                }
                in.close();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return new Resource(bout.toByteArray(), mimeType, isBinary);
    }

    public static HashMap<String, Object> getSession(String sessionKey) {
        return sessionList.get(sessionKey);
    }

    public static HashMap<String, Object> getSession(HttpExchange httpExchange) {
        HashMap<String, Object> userSession;
        if (httpExchange.sessionID.isEmpty()) {
            UUID uuid = UUID.randomUUID();
            httpExchange.sessionID = uuid.toString();
            httpExchange.responseHeaders.put("Set-Cookie", "session=" + uuid + "; debug=" + ServerConstant.config.DEBUG + ";");
        }
        if (!sessionList.containsKey(httpExchange.sessionID)) {
            userSession = new HashMap<>();
            sessionList.put(httpExchange.sessionID, userSession);
        } else {
            userSession = sessionList.get(httpExchange.sessionID);
        }
        return userSession;
    }

    public static String getFileMime(String path) {
        String ext = getFileExt(path).toLowerCase();
        return ServerConstant.config.MIME_MAP.getOrDefault(ext, ServerConstant.config.APPLICATION_OCTET_STREAM);
    }

    public static String getFileExt(String path) {
        int dotIndex = path.lastIndexOf('.');
        return (dotIndex >= 0) ? path.substring(dotIndex + 1) : "";
    }

    private static class Resource {
        public final byte[] content;
        public String mimeType;
        public boolean isBinary;

        public Resource(byte[] content) {
            this.content = content;
            this.mimeType = "text/html";
            this.isBinary = false;
        }

        public Resource(byte[] content, String mimeType) {
            this.content = content;
            this.mimeType = mimeType;
            this.isBinary = false;
        }

        public Resource(byte[] content, String mimeType, boolean isBinary) {
            this.content = content;
            this.mimeType = mimeType;
            this.isBinary = isBinary;
        }
    }


    private static Class<?> loadClass(String className) throws ClassNotFoundException {
        return Class.forName(className);
    }

    public static void parseElementV2(Document doc, Element element, Element elementParent) {
        String tagName = element.tag().getName();
        if (!tagName.equals("cmpform") && tagName.length() > 3) {
            if (tagName.substring(0, 3).equals("cmp")) {
                try {
                    if (ServerConstant.config.COMPONENT_PATH.length() == 0) {
                        ServerConstant.config.COMPONENT_PATH = ServerResourceHandler.class.getPackage().getName() + ".component";
                    }
                    String classNameCmp = ServerConstant.config.COMPONENT_PATH + ".cmp" + tagName.substring(3, 4).toUpperCase() + tagName.substring(4);
                    Class classComponent = componentListClass.getOrDefault(classNameCmp, Class.forName(classNameCmp));
                    if (!componentListClass.containsKey(classNameCmp)) {
                        componentListClass.put(classNameCmp, classComponent);
                    }
                    Constructor constructor = classComponent.getConstructor(Document.class, Element.class, String.class);
                    Object newClassObject = constructor.newInstance(doc, element, tagName);

                    element.replaceWith((Element) newClassObject);
                } catch (ClassNotFoundException e) {
                    Element errBody = new Element("error");
                    errBody.text("not found " + ServerConstant.config.COMPONENT_PATH + ".cmp" + tagName.substring(3, 4).toUpperCase() + tagName.substring(4));
                    element.replaceWith(errBody);
                } catch (Exception e) {
                    Element errBody = new Element("error");
                    errBody.text(e.getLocalizedMessage() + " : " + e.getMessage() + ": \n " + parseErrorRunJava(e));
                    element.replaceWith(errBody);
                }
                return;
            }
        }
        for (Attribute attr : element.attributes().asList()) {
        }
        if (element.hasText()) {
        }
        if (element.childNodeSize() == 0) {

        } else {
            int childrenint = 0;
            for (int numChildNode = 0; numChildNode < element.childNodeSize(); numChildNode++) {
                if (element.childNode(numChildNode).nodeName().equals("#text")) {

                } else if (element.childNode(numChildNode).nodeName().equals("#comment")) {

                } else if (element.childNode(numChildNode).nodeName().equals("#cdata")) {

                } else {
                    if (childrenint < element.childrenSize()) {
                        parseElementV2(doc, element.child(childrenint), element);
                        childrenint++;
                    }
                }
            }
        }
        if (elementParent == null) {

        }
    }

    public static void parseElementV2(Document doc, Element element) {
        parseElementV2(doc, element, null);
    }

    public static Element parseElementV2(Document doc, String elementText) {
        Element el = new Element(elementText);
        parseElementV2(doc, el, null);
        return el;
    }

    public static String parseSubElementV2(Document doc, String path, String SelectorQuery) {
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(doc.attr("rootPath") + path), StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = reader.read()) > 0) {
                sb.append((char) c);
            }
            return parseStrElementV2(doc, sb.toString(), SelectorQuery);
        } catch (IOException e) {
            return "Error reading subform: " + e.getMessage();
        }
    }

    public static String parseStrElementV2(Document doc, String htmlText, String SelectorQuery) {
        Document docForm = Jsoup.parse(htmlText);
        if (SelectorQuery.isEmpty()) {
            Element els = docForm.getElementsByTag("body").get(0);
            parseElementV2(docForm, els, null);
            return els.toString();
        } else {
            StringBuilder sb = new StringBuilder();
            for (Element elOne : doc.select(SelectorQuery)) {
                parseElementV2(docForm, elOne, null);
                sb.append(elOne.toString());
            }
            return sb.toString();
        }
    }

    private static boolean isBinaryFile(String extension) {
        extension = extension.toLowerCase();
        String[] textExtensions = {
                "html", "htm", "css", "js", "json", "xml",
                "txt", "csv", "md", "java", "php", "asp",
                "aspx", "jsp", "py", "rb", "pl", "c", "cpp",
                "h", "hpp", "sql", "ini", "cfg", "conf", "yml",
                "yaml", "properties", "log", "bat", "sh", "ps1"
        };

        for (String textExt : textExtensions) {
            if (textExt.equals(extension)) {
                return false;
            }
        }
        return true;
    }

    private File findFileInAllDirectories(String requestPath) {
        String requestPathLocal = requestPath.replace("/", File.separator);
        if (!ServerConstant.config.WEBAPP_DIRS.isEmpty()) {
            for (String webappDir : ServerConstant.config.WEBAPP_DIRS) {
                String resourcePath = webappDir + File.separator + requestPathLocal;
                File file = new File(resourcePath);
                if (file.exists() && file.isFile()) {
                    return file;
                }
            }
        } else {
            String resourcePath = ServerConstant.config.WEBAPP_DIR + File.separator + requestPathLocal;
            File file = new File(resourcePath);
            if (file.exists() && file.isFile()) {
                return file;
            }
        }

        if (!ServerConstant.config.WEBAPP_SYSTEM_DIR.isEmpty()) {
            String systemPath = ServerConstant.config.WEBAPP_SYSTEM_DIR + File.separator + requestPathLocal;
            File systemFile = new File(systemPath);
            if (systemFile.exists() && systemFile.isFile()) {
                return systemFile;
            }
        }

        return null;
    }

    public static File findResourceFile(String fileName) {
        if (ServerResource.pagesListFile != null && ServerResource.pagesListFile.containsKey(fileName)) {
            return ServerResource.pagesListFile.get(fileName);
        }

        if (!ServerConstant.config.WEBAPP_DIRS.isEmpty()) {
            for (String webappDir : ServerConstant.config.WEBAPP_DIRS) {
                File file = new File(webappDir + File.separator + fileName);
                if (file.exists() && file.isFile()) {
                    return file;
                }
            }
        } else {
            File file = new File(ServerConstant.config.WEBAPP_DIR + File.separator + fileName);
            if (file.exists() && file.isFile()) {
                return file;
            }
        }

        if (!ServerConstant.config.WEBAPP_SYSTEM_DIR.isEmpty()) {
            File systemFile = new File(ServerConstant.config.WEBAPP_SYSTEM_DIR + File.separator + fileName);
            if (systemFile.exists() && systemFile.isFile()) {
                return systemFile;
            }
        }

        return null;
    }

    private String buildResourcePathForPageList() {
        File file = findFileInAllDirectories(query.requestPath);
        if (file != null) {
            return file.getAbsolutePath();
        }

        if (!ServerConstant.config.WEBAPP_DIRS.isEmpty()) {
            return ServerConstant.config.WEBAPP_DIRS.get(0) + File.separator +
                    query.requestPath.replaceAll("/", File.separator);
        } else {
            return ServerConstant.config.WEBAPP_DIR + File.separator +
                    query.requestPath.replaceAll("/", File.separator);
        }
    }

    private String getWebappDirForFile(File file) {
        if (file == null) {
            return ServerConstant.config.WEBAPP_DIR;
        }

        String filePath = file.getAbsolutePath();

        if (!ServerConstant.config.WEBAPP_DIRS.isEmpty()) {
            for (String webappDir : ServerConstant.config.WEBAPP_DIRS) {
                if (filePath.startsWith(webappDir)) {
                    return webappDir;
                }
            }
        }

        if (!ServerConstant.config.WEBAPP_SYSTEM_DIR.isEmpty() &&
                filePath.startsWith(ServerConstant.config.WEBAPP_SYSTEM_DIR)) {
            return ServerConstant.config.WEBAPP_SYSTEM_DIR;
        }

        return ServerConstant.config.WEBAPP_DIR;
    }
}