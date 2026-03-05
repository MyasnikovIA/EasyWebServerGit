package ru.miacomsoft.EasyWebServer.component;

import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class cmpEdit extends Base {

    public cmpEdit(Document doc, Element element, String tag) {
        super(doc, element, tag);

        Attributes attrs = element.attributes();

        // Получаем атрибуты
        String name = attrs.hasKey("name") ? attrs.get("name") : genUUID();
        String value = RemoveArrKeyRtrn(attrs, "value", "");
        String placeholder = RemoveArrKeyRtrn(attrs, "placeholder", "");
        String maxlength = RemoveArrKeyRtrn(attrs, "maxlength", "");
        String type = RemoveArrKeyRtrn(attrs, "type", "text");
        String format = RemoveArrKeyRtrn(attrs, "format", "");
        String mask_type = RemoveArrKeyRtrn(attrs, "mask_type", "");
        String disabled = RemoveArrKeyRtrn(attrs, "disabled", null);
        String readonly = RemoveArrKeyRtrn(attrs, "readonly", null);
        String trim = RemoveArrKeyRtrn(attrs, "trim", "false");
        String onchange = RemoveArrKeyRtrn(attrs, "onchange", "");
        String onformat = RemoveArrKeyRtrn(attrs, "onformat", "");
        String style = RemoveArrKeyRtrn(attrs, "style", "");
        String cssClass = RemoveArrKeyRtrn(attrs, "class", "ctrl_edit editControl");
        String theme = RemoveArrKeyRtrn(attrs, "theme", "default");
        String width = RemoveArrKeyRtrn(attrs, "width", "auto");

        // Создаем контейнер для поля ввода
        Element editDiv = new Element("div");
        editDiv.attr("name", name);
        editDiv.attr("cmptype", "Edit");
        editDiv.attr("data-theme", theme);
        editDiv.attr("data-trim", trim);
        editDiv.attr("class", cssClass);

        // Управляем шириной
        if (!width.isEmpty()) {
            if (!style.isEmpty()) {
                style += "; ";
            }
            if (width.equals("auto")) {
                style += "width: auto; min-width: 100px;";
            } else if (width.equals("full")) {
                style += "width: 100%;";
            } else if (width.matches("\\d+%")) {
                style += "width: " + width + ";";
            } else if (width.matches("\\d+px")) {
                style += "width: " + width + ";";
            }
        }

        if (!style.isEmpty()) {
            editDiv.attr("style", style);
        }

        // Добавляем атрибуты для форматирования
        if (!format.isEmpty()) {
            editDiv.attr("data-format", format);
        }

        if (!mask_type.isEmpty()) {
            editDiv.attr("data-mask-type", mask_type);
        }

        // Создаем input элемент
        Element input = new Element("input");
        input.attr("type", type);
        input.attr("value", value);

        if (!name.isEmpty()) {
            input.attr("name", name + "_input");
        }

        if (!placeholder.isEmpty()) {
            input.attr("placeholder", placeholder);
            editDiv.attr("data-placeholder", placeholder);
        }

        if (!maxlength.isEmpty()) {
            input.attr("maxlength", maxlength);
        }

        if (disabled != null) {
            input.attr("disabled", "disabled");
            editDiv.attr("disabled", disabled);
        }

        if (readonly != null) {
            input.attr("readonly", "readonly");
        }

        // Добавляем обработчики событий
        StringBuilder allEvents = new StringBuilder();

        if (!onchange.isEmpty()) {
            allEvents.append(onchange).append(";");
        }

        // Добавляем обработчик format если есть
        if (!format.isEmpty() && onformat.isEmpty()) {
            String formatScript = "D3Api.EditCtrl.format(this, " + format + ", arguments[0]);";
            allEvents.append(formatScript);
        } else if (!onformat.isEmpty()) {
            allEvents.append(onformat);
        }

        if (allEvents.length() > 0) {
            editDiv.attr("data-onchange", onchange);
            if (!format.isEmpty() || !onformat.isEmpty()) {
                editDiv.attr("data-onformat", allEvents.toString());
            }
        }

        editDiv.appendChild(input);

        // Если есть маска, создаем дополнительный элемент для маски
        if (!mask_type.isEmpty()) {
            Element maskDiv = new Element("div");
            maskDiv.attr("cmptype", "Mask");
            maskDiv.attr("name", name + "_mask_Ctrl");
            maskDiv.attr("controls", name);
            maskDiv.attr("style", "display: none;");

            // Маска будет обрабатываться через JavaScript
            maskDiv.attr("data-mask-type", mask_type);
            if (!format.isEmpty()) {
                maskDiv.attr("data-format", format);
            }

            editDiv.appendChild(maskDiv);
        }

        // Добавляем поле ввода в body документа
        Elements body = doc.getElementsByTag("body");
        if (body != null && body.size() > 0) {
            body.append(editDiv.toString());
        }

        // Автоматическое подключение CSS и JavaScript
        Elements head = doc.getElementsByTag("head");
        if (head != null && head.size() > 0) {
            // Подключаем CSS
            Elements existingCss = head.select("link[href*='cmpEdit_css']");
            if (existingCss.isEmpty()) {
                String cssPath = "{component}/cmpEdit_css";
                head.append("<link rel=\"stylesheet\" cmp=\"edit-css\" href=\"" + cssPath + "\" type=\"text/css\">");
            }

            // Подключаем JavaScript
            Elements existingScripts = head.select("script[src*='cmpEdit_js']");
            if (existingScripts.isEmpty()) {
                String jsPath = "{component}/cmpEdit_js";
                head.append("<script cmp=\"edit-lib\" src=\"" + jsPath + "\" type=\"text/javascript\"></script>");
            }
        }
    }
}