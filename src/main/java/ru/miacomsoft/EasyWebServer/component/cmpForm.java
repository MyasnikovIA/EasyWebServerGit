package ru.miacomsoft.EasyWebServer.component;

import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class cmpForm extends Base {

    public cmpForm(Document doc, Element element, String tag) {
        super(doc, element, tag);

        Attributes attrs = element.attributes();
        Attributes attrsDst = this.attributes();

        // Получаем атрибуты
        String cmptype = attrs.hasKey("cmptype") ? attrs.get("cmptype") : "Form";
        String formClass = RemoveArrKeyRtrn(attrs, "class", "formBackground d3form");
        String scrollable = RemoveArrKeyRtrn(attrs, "scrollable", "true");
        String formName = RemoveArrKeyRtrn(attrs, "formname", "");

        // Если formname не указан, пробуем извлечь из комментария
        if (formName.isEmpty()) {
            // Ищем комментарий перед элементом
            String comment = findFormComment(element);
            if (!comment.isEmpty()) {
                formName = comment.replace(".frm", "").trim();
            }
        }

        // Устанавливаем атрибуты
        attrsDst.add("cmptype", cmptype);
        attrsDst.add("class", formClass);
        attrsDst.add("scrollable", scrollable);

        if (!formName.isEmpty()) {
            attrsDst.add("formname", formName);
        }

        // Копируем остальные атрибуты
        for (Attribute attr : attrs.asList()) {
            attrsDst.add(attr.getKey(), attr.getValue());
        }

        // Обрабатываем дочерние элементы
        Elements children = element.children();
        for (Element child : children) {
            // Проверяем, является ли дочерний элемент уже обработанным компонентом
            if (!child.hasAttr("cmptype")) {
                // Если нет, оставляем как есть
                this.appendChild(child.clone());
            } else {
                // Если да, добавляем его напрямую
                this.appendChild(child.clone());
            }
        }

        // Добавляем дополнительный скрипт для инициализации форм
        Elements body = doc.getElementsByTag("body");
        if (body != null && body.size() > 0) {
            String formInitScript = "<textarea cmptype=\"Script\" style=\"display:none;\">" +
                    "D3Api.MainDom.userForms = \"\"" +
                    "</textarea>" +
                    "<textarea cmptype=\"Script\" style=\"display:none;\">" +
                    "D3Api.MainDom.subForms = \"\"" +
                    "</textarea>" +
                    "<div id=\"cacheInfo\" style=\"display:none;\"></div>";
            body.append(formInitScript);
        }
    }

    /**
     * Извлекает имя формы из комментария перед элементом
     */
    private String findFormComment(Element element) {
        String html = element.outerHtml();
        // Ищем комментарий в формате: <!-- Forms/Test/ComboBoxD3.frm -->
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "<!--\\s*(Forms/[^\\s]+)\\.frm\\s*-->"
        );
        java.util.regex.Matcher matcher = pattern.matcher(html);

        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }
}