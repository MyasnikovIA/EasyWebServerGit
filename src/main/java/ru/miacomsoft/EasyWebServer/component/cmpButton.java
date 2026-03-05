package ru.miacomsoft.EasyWebServer.component;

import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import ru.miacomsoft.EasyWebServer.HttpExchange;

public class cmpButton extends Base {

    public cmpButton(Document doc, Element element, String tag) {
        super(doc, element, tag);

        Attributes attrs = element.attributes();

        // Получаем атрибуты
        String name = attrs.hasKey("name") ? attrs.get("name") : genUUID();
        String caption = RemoveArrKeyRtrn(attrs, "caption", "Кнопка");
        String onclick = RemoveArrKeyRtrn(attrs, "onclick", "");
        String disabled = RemoveArrKeyRtrn(attrs, "disabled", null);
        String title = RemoveArrKeyRtrn(attrs, "title", "");
        String tabindex = RemoveArrKeyRtrn(attrs, "tabindex", "0");
        String style = RemoveArrKeyRtrn(attrs, "style", "");
        String cssClass = RemoveArrKeyRtrn(attrs, "class", "ctrl_button box-sizing-force");
        String icon = RemoveArrKeyRtrn(attrs, "icon", "");
        String onlyicon = RemoveArrKeyRtrn(attrs, "onlyicon", null);
        String popupmenu = RemoveArrKeyRtrn(attrs, "popupmenu", "");
        String theme = RemoveArrKeyRtrn(attrs, "theme", "default");

        // Создаем элемент кнопки (div)
        Element buttonDiv = new Element("div");
        buttonDiv.attr("name", name);
        buttonDiv.attr("cmptype", "Button");
        buttonDiv.attr("tabindex", tabindex);
        buttonDiv.attr("data-theme", theme);

        // Добавляем классы
        String finalClass = cssClass;
        if (onlyicon != null || caption.trim().isEmpty()) {
            finalClass += " onlyicon";
        }
        buttonDiv.attr("class", finalClass);

        if (!style.isEmpty()) {
            buttonDiv.attr("style", style);
        }

        if (!title.isEmpty()) {
            buttonDiv.attr("title", title);
        }

        if (disabled != null) {
            buttonDiv.attr("disabled", disabled);
            buttonDiv.addClass("ctrl_disable");
        }

        if (!popupmenu.isEmpty()) {
            buttonDiv.attr("data-popupmenu", popupmenu);
        }

        // Добавляем иконку если есть
        if (!icon.isEmpty()) {
            Element iconDiv = new Element("div");
            iconDiv.attr("class", "btn_icon");

            Element img = new Element("img");
            img.attr("src", icon);
            img.attr("class", "btn_icon_img");
            img.attr("alt", "");

            iconDiv.appendChild(img);
            buttonDiv.appendChild(iconDiv);
        }

        // Создаем внутренний div для текста кнопки
        Element captionDiv = new Element("div");
        String captionClass = "btn_caption btn_center";
        if (RemoveArrKeyRtrn(attrs, "nominwidth", null) == null) {
            captionClass += " minwidth";
        }
        captionDiv.attr("class", captionClass);
        captionDiv.text(caption);

        buttonDiv.appendChild(captionDiv);

        // Добавляем стрелку для popup меню
        if (!popupmenu.isEmpty()) {
            Element arrow = new Element("i");
            arrow.attr("class", "fas fa-angle-down btn-arrow");
            arrow.attr("style", "padding-left: 5px; float: right; padding-top: 4px");
            buttonDiv.appendChild(arrow);
        }

        // Добавляем обработчик onclick
        if (!onclick.isEmpty()) {
            if (!popupmenu.isEmpty()) {
                // Комбинируем onclick с показом popup меню
                buttonDiv.attr("onclick", onclick + " D3Api.ButtonCtrl.showPopupMenu(event,this,'" + popupmenu + "');");
            } else {
                buttonDiv.attr("onclick", onclick);
            }
        } else if (!popupmenu.isEmpty()) {
            buttonDiv.attr("onclick", "D3Api.ButtonCtrl.showPopupMenu(event,this,'" + popupmenu + "');");
        }

        // Добавляем кнопку в body документа
        Elements body = doc.getElementsByTag("body");
        if (body != null && body.size() > 0) {
            body.append(buttonDiv.toString());
        }

        // Автоматическое подключение CSS и JavaScript
        Elements head = doc.getElementsByTag("head");
        if (head != null && head.size() > 0) {
            // Подключаем CSS
            Elements existingCss = head.select("link[href*='cmpButton_css']");
            if (existingCss.isEmpty()) {
                String cssPath = "{component}/cmpButton_css";
                head.append("<link rel=\"stylesheet\" cmp=\"button-css\" href=\"" + cssPath + "\" type=\"text/css\">");
            }

            // Подключаем JavaScript
            Elements existingScripts = head.select("script[src*='cmpButton_js']");
            if (existingScripts.isEmpty()) {
                String jsPath = "{component}/cmpButton_js";
                head.append("<script cmp=\"button-lib\" src=\"" + jsPath + "\" type=\"text/javascript\"></script>");
            }
        }
    }
}