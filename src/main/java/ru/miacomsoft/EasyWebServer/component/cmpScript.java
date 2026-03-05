package ru.miacomsoft.EasyWebServer.component;

import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class cmpScript extends Base {

    public cmpScript(Document doc, Element element, String tag) {
        super(doc, element, tag);

        Attributes attrs = element.attributes();

        // Получаем атрибуты
        String name = attrs.hasKey("name") ? attrs.get("name") : genUUID();
        String type = RemoveArrKeyRtrn(attrs, "type", "text/javascript");
        String src = RemoveArrKeyRtrn(attrs, "src", "");
        String async = RemoveArrKeyRtrn(attrs, "async", null);
        String defer = RemoveArrKeyRtrn(attrs, "defer", null);
        String charset = RemoveArrKeyRtrn(attrs, "charset", "UTF-8");
        String crossorigin = RemoveArrKeyRtrn(attrs, "crossorigin", "");
        String integrity = RemoveArrKeyRtrn(attrs, "integrity", "");
        String referrerpolicy = RemoveArrKeyRtrn(attrs, "referrerpolicy", "");
        String nomodule = RemoveArrKeyRtrn(attrs, "nomodule", null);
        String nonce = RemoveArrKeyRtrn(attrs, "nonce", "");

        // Получаем содержимое скрипта (содержимое между открывающим и закрывающим тегами)
        String scriptContent = element.html();

        // Обрабатываем возможные CDATA секции
        if (scriptContent.startsWith("&lt;![CDATA[") && scriptContent.endsWith("]]&gt;")) {
            // Удаляем экранированную CDATA обертку
            scriptContent = scriptContent
                    .substring(12, scriptContent.length() - 5)
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&amp;", "&")
                    .replace("&quot;", "\"")
                    .replace("&#39;", "'");
        } else if (scriptContent.startsWith("<![CDATA[") && scriptContent.endsWith("]]>")) {
            // Удаляем CDATA обертку
            scriptContent = scriptContent.substring(9, scriptContent.length() - 3);
        }

        // Строим HTML строку вручную
        StringBuilder scriptHtml = new StringBuilder();
        scriptHtml.append("<script");
        scriptHtml.append(" name=\"").append(name).append("\"");
        scriptHtml.append(" cmptype=\"Script\"");
        scriptHtml.append(" type=\"").append(type).append("\"");
        scriptHtml.append(" charset=\"").append(charset).append("\"");

        if (!src.isEmpty()) {
            scriptHtml.append(" src=\"").append(src).append("\"");
        }
        if (async != null) {
            scriptHtml.append(" async=\"async\"");
        }
        if (defer != null) {
            scriptHtml.append(" defer=\"defer\"");
        }
        if (!crossorigin.isEmpty()) {
            scriptHtml.append(" crossorigin=\"").append(crossorigin).append("\"");
        }
        if (!integrity.isEmpty()) {
            scriptHtml.append(" integrity=\"").append(integrity).append("\"");
        }
        if (!referrerpolicy.isEmpty()) {
            scriptHtml.append(" referrerpolicy=\"").append(referrerpolicy).append("\"");
        }
        if (nomodule != null) {
            scriptHtml.append(" nomodule=\"nomodule\"");
        }
        if (!nonce.isEmpty()) {
            scriptHtml.append(" nonce=\"").append(nonce).append("\"");
        }

        scriptHtml.append(">");

        if (!scriptContent.isEmpty()) {
            scriptHtml.append("\n").append(scriptContent).append("\n");
        }

        scriptHtml.append("</script>");

        // Определяем куда добавить скрипт
        String target = RemoveArrKeyRtrn(attrs, "target", "body"); // head или body

        Elements targetElement;
        if (target.equalsIgnoreCase("head")) {
            targetElement = doc.getElementsByTag("head");
        } else {
            targetElement = doc.getElementsByTag("body");
        }

        // Добавляем скрипт в документ
        if (targetElement != null && targetElement.size() > 0) {
            targetElement.append(scriptHtml.toString());
        }

        // Очищаем исходный элемент (он будет заменен)
        element.empty();

        // Если скрипт внешний и мы в head, проверяем не подключен ли он уже
        if (!src.isEmpty() && target.equalsIgnoreCase("head")) {
            Elements existingScripts = doc.head().select("script[src='" + src + "']");
            if (existingScripts.size() > 1) {
                // Удаляем дубликат (оставляем первый)
                existingScripts.last().remove();
            }
        }
    }
}