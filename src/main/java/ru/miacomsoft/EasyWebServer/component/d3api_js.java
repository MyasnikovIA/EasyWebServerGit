package ru.miacomsoft.EasyWebServer.component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Расширение функционала D3Api с DOM-методами из _d3api.js
 */
public class d3api_js {

    protected static final ConcurrentHashMap<String, byte[]> JS_CACHE = new ConcurrentHashMap<>();
    private static final Object LOCK = new Object();
    protected static String cachedHash = null;
    private static long lastModified = 0;

    private static final String JS_SOURCE_CODE = """
// Расширение функционала D3Api DOM-методами
(function() {
    // Проверяем, что D3Api существует
    if (typeof D3Api === 'undefined') {
        console.error('D3Api не найден. Невозможно добавить DOM-методы.');
        return;
    }

    /**
     * Получение события
     * @param {Event} e - Объект события (опционально)
     * @returns {Event} - Объект события
     */
    D3Api.getEvent = function(e) {
        return D3Api.isEvent(e) ? e : window.event || D3Api._event_;
    };

    /**
     * Установка текущего события
     * @param {Event} event - Объект события
     */
    D3Api.setEvent = function(event) {
        D3Api._event_ = event || window.event;
        if (!D3Api.isEvent(D3Api._event_)) {
            D3Api._event_ = null;
        }
    };

    /**
     * Проверка, является ли объект событием
     * @param {*} e - Проверяемый объект
     * @returns {boolean} - true, если объект является событием
     */
    D3Api.isEvent = function(e) {
        return (e instanceof Object) && (e instanceof Event || e.target || e.currentTarget || e.srcElement);
    };

    /**
     * Получение цели события
     * @param {Event} e - Объект события
     * @returns {Element|null} - Элемент, на котором произошло событие
     */
    D3Api.getEventTarget = function(e) {
        var ev = D3Api.getEvent(e);
        if (!ev) return null;
        return ev.target || ev.srcElement;
    };

    /**
     * Получение текущей цели события
     * @param {Event} e - Объект события
     * @returns {Element|null} - Текущий элемент события
     */
    D3Api.getEventCurrentTarget = function(e) {
        var ev = D3Api.getEvent(e);
        if (!ev) return null;
        return ev.currentTarget || ev.srcElement;
    };

    /**
     * Остановка распространения события
     * @param {Event} e - Объект события
     * @param {boolean} preventDefault - Предотвращать ли действие по умолчанию (по умолчанию true)
     * @returns {boolean} - Всегда возвращает false
     */
    D3Api.stopEvent = function(e, preventDefault) {
        var ev = D3Api.getEvent(e);
        if (!ev) return false;
        
        if (ev.stopPropagation) {
            ev.stopPropagation();
        } else {
            ev.cancelBubble = true;
            ev.returnValue = false;
        }
        
        if (preventDefault !== false && ev.preventDefault) {
            ev.preventDefault();
        }
        return false;
    };

    /**
     * Получение кода символа из события клавиатуры
     * @param {Event} e - Объект события
     * @returns {number} - Код символа
     */
    D3Api.charCodeEvent = function(e) {
        if (e.charCode) {
            return e.charCode;
        } else if (e.keyCode) {
            return e.keyCode;
        } else if (e.which) {
            return e.which;
        } else {
            return 0;
        }
    };

    /**
     * Проверка на пустоту значения
     * @param {*} v - Проверяемое значение
     * @returns {boolean} - true, если значение пустое
     */
    D3Api.empty = function(v) {
        if (v instanceof RegExp) {
            return v == undefined || v == null || v == '';
        } else if (v instanceof Function) {
            return v == undefined || v == null || v == '';
        } else if (v instanceof Node) {
            return v == undefined || v == null || v == '';
        } else if (v instanceof EventTarget) {
            return v == undefined || v == null || v == '';
        } else if (v instanceof Object) {
            var res = true;
            for (var p in v) {
                if (v.hasOwnProperty(p)) {
                    res = res && D3Api.empty(v[p]);
                }
            }
            return res;
        }
        return v == undefined || v == null || v == '';
    };

    /**
     * Проверка на undefined
     * @param {*} v - Проверяемое значение
     * @returns {boolean} - true, если значение undefined или null
     */
    D3Api.isUndefined = function(v) {
        return v === undefined || v === null;
    };

    /**
     * Получение логического значения из различных типов
     * @param {*} v - Значение для преобразования
     * @returns {boolean} - Логическое значение
     */
    D3Api.getBoolean = function(v) {
        if (typeof v === 'string') {
            v = v.trim();
        }
        return v !== 'false' && v !== '0' && !!v;
    };

    /**
     * Добавление класса элементу
     * @param {Element} c - DOM-элемент
     * @param {string} className - Имя класса
     */
    D3Api.addClass = function(c, className) {
        if (!D3Api.empty(className)) {
            className = className.replace(/[()]+/g, '');
        }
        var re = new RegExp("(^|\\s)" + className + "(\\s|$)", "g");
        if (c.className == undefined) {
            c.className = className;
            return;
        }
        if (re.test(c.className)) return;
        c.className = (c.className + " " + className).replace(/\\s+/g, " ").replace(/(^ | $)/g, "");
    };

    /**
     * Удаление класса у элемента
     * @param {Element} c - DOM-элемент
     * @param {string} className - Имя класса
     */
    D3Api.removeClass = function(c, className) {
        if (!D3Api.empty(className)) {
            className = className.replace(/[()]+/g, '');
        }
        var re = new RegExp("(^|\\s)" + className + "(\\s|$)", "g");
        if (c.className == undefined) return;
        c.className = c.className.replace(re, "$1").replace(/\\s+/g, " ").replace(/(^ | $)/g, "");
    };

    /**
     * Переключение класса
     * @param {Element} c - DOM-элемент
     * @param {string} className1 - Первый класс
     * @param {string} className2 - Второй класс
     * @param {boolean} firstOnly - Только первый класс
     */
    D3Api.toggleClass = function(c, className1, className2, firstOnly) {
        if (D3Api.hasClass(c, className1)) {
            D3Api.removeClass(c, className1);
            D3Api.addClass(c, className2);
        } else if (!firstOnly) {
            D3Api.removeClass(c, className2);
            D3Api.addClass(c, className1);
        }
    };

    /**
     * Проверка наличия класса
     * @param {Element} c - DOM-элемент
     * @param {string} className - Имя класса
     * @returns {boolean} - true, если класс присутствует
     */
    D3Api.hasClass = function(c, className) {
        if (!D3Api.empty(className)) {
            className = className.replace(/[()]+/g, '');
        }
        if (!className) {
            return c.className != '' && c.className != undefined;
        }
        return (c.className && c.className.search('\\\\b' + className + '\\\\b') != -1);
    };

    /**
     * Получение абсолютной позиции элемента
     * @param {Element} element - DOM-элемент
     * @returns {Object} - Объект с координатами {x, y}
     */
    D3Api.getAbsolutePos = function(element) {
        if (!element) return {x: 0, y: 0};
        var SL = 0, ST = 0;
        var is_div = /^div$/i.test(element.tagName);
        if (is_div && element.scrollLeft) SL = element.scrollLeft;
        if (is_div && element.scrollTop) ST = element.scrollTop;
        var r = {x: element.offsetLeft - SL, y: element.offsetTop - ST};
        if (element.offsetParent) {
            var tmp = D3Api.getAbsolutePos(element.offsetParent);
            r.x += tmp.x;
            r.y += tmp.y;
        }
        return r;
    };

    /**
     * Получение абсолютного размера элемента
     * @param {Element} element - DOM-элемент
     * @returns {Object} - Объект с размерами {width, height}
     */
    D3Api.getAbsoluteSize = function(element) {
        if (!element) return {width: 0, height: 0};
        var display = element.style.display;
        if (display != 'none' && display != null) {
            return {width: element.offsetWidth, height: element.offsetHeight};
        }

        var els = element.style;
        var originalVisibility = els.visibility;
        var originalPosition = els.position;
        var originalDisplay = els.display;
        els.visibility = 'hidden';
        els.position = 'absolute';
        els.display = 'block';
        var originalWidth = element.clientWidth;
        var originalHeight = element.clientHeight;
        els.display = originalDisplay;
        els.position = originalPosition;
        els.visibility = originalVisibility;
        return {width: originalWidth, height: originalHeight};
    };

    /**
     * Получение прямоугольника элемента
     * @param {Element} element - DOM-элемент
     * @param {boolean} scrollNeed - Учитывать прокрутку
     * @returns {Object} - Объект с координатами и размерами {x, y, width, height}
     */
    D3Api.getAbsoluteRect = function(element, scrollNeed) {
        if (!element) return {x: 0, y: 0, width: 0, height: 0};
        var pos = D3Api.getAbsolutePos(element);
        var size = D3Api.getAbsoluteSize(element);
        if (scrollNeed) {
            pos.x -= D3Api.getBodyScrollLeft();
            pos.y -= D3Api.getBodyScrollTop();
        }
        return {x: pos.x, y: pos.y, width: size.width, height: size.height};
    };

    /**
     * Получение клиентского прямоугольника элемента
     * @param {Element} elem - DOM-элемент
     * @param {boolean} xScroll - Учитывать горизонтальную прокрутку
     * @param {boolean} yScroll - Учитывать вертикальную прокрутку
     * @returns {Object} - Объект с координатами и размерами
     */
    D3Api.getAbsoluteClientRect = function(elem, xScroll, yScroll) {
        var rect = elem.getBoundingClientRect();
        var scrollTop = D3Api.getBodyScrollTop();
        var scrollLeft = D3Api.getBodyScrollLeft();

        var coordy = rect.top + ((yScroll === false) ? 0 : scrollTop);
        var coordx = rect.left + ((xScroll === false) ? 0 : scrollLeft);

        return {
            y: Math.round(coordy),
            x: Math.round(coordx),
            width: rect.width || (rect.right - rect.left),
            height: rect.height || (rect.bottom - rect.top)
        };
    };

    /**
     * Получение вертикальной прокрутки страницы
     * @returns {number} - Значение прокрутки
     */
    D3Api.getBodyScrollTop = function() {
        return self.pageYOffset || (document.documentElement && document.documentElement.scrollTop) || (document.body && document.body.scrollTop) || 0;
    };

    /**
     * Получение горизонтальной прокрутки страницы
     * @returns {number} - Значение прокрутки
     */
    D3Api.getBodyScrollLeft = function() {
        return self.pageXOffset || (document.documentElement && document.documentElement.scrollLeft) || (document.body && document.body.scrollLeft) || 0;
    };

    /**
     * Получение координат события относительно страницы
     * @param {Event} evt - Объект события
     * @returns {Object} - Объект с координатами {left, top}
     */
    D3Api.getPageEventCoords = function(evt) {
        var coords = {left: 0, top: 0};
        if (evt.pageX) {
            coords.left = evt.pageX;
            coords.top = evt.pageY;
        } else if (evt.clientX) {
            coords.left = evt.clientX + document.body.scrollLeft - document.body.clientLeft;
            coords.top = evt.clientY + document.body.scrollTop - document.body.clientTop;

            if (document.body.parentElement && document.body.parentElement.clientLeft) {
                var bodParent = document.body.parentElement;
                coords.left += bodParent.scrollLeft - bodParent.clientLeft;
                coords.top += bodParent.scrollTop - bodParent.clientTop;
            }
        }
        return coords;
    };

    /**
     * Проверка, является ли элемент дочерним для контейнера
     * @param {Element} child - Дочерний элемент
     * @param {Element} container - Контейнер
     * @returns {boolean} - true, если элемент дочерний
     */
    D3Api.isChildOf = function(child, container) {
        var c = child.parentNode;
        while (c != undefined && c != document.body && c != container) {
            c = c.parentNode;
        }
        return (c == container);
    };

    /**
     * Создание DOM-элемента из HTML-строки
     * @param {string} text - HTML-строка
     * @returns {Element|null} - Созданный элемент или null
     */
    D3Api.createDom = function(text) {
        var dom = document.createElement('div');
        try {
            dom.innerHTML = text;
            var res = dom.removeChild(dom.firstChild);
            dom = null;
            return res;
        } catch (e) {
            return null;
        }
    };

    /**
     * Добавление DOM-элемента
     * @param {Element} dom - Родительский элемент
     * @param {Element} newDom - Добавляемый элемент
     * @returns {Element} - Добавленный элемент
     */
    D3Api.addDom = function(dom, newDom) {
        return dom.appendChild(newDom);
    };

    /**
     * Вставка элемента перед другим элементом
     * @param {Element} dom - Элемент, перед которым вставляем
     * @param {Element} newDom - Вставляемый элемент
     * @returns {Element} - Вставленный элемент
     */
    D3Api.insertBeforeDom = function(dom, newDom) {
        return dom.parentNode.insertBefore(newDom, dom);
    };

    /**
     * Вставка элемента после другого элемента
     * @param {Element} dom - Элемент, после которого вставляем
     * @param {Element} newDom - Вставляемый элемент
     * @returns {Element} - Вставленный элемент
     */
    D3Api.insertAfterDom = function(dom, newDom) {
        return dom.parentNode.insertBefore(newDom, dom.nextSibling);
    };

    /**
     * Удаление DOM-элемента
     * @param {Element} dom - Удаляемый элемент
     */
    D3Api.removeDom = function(dom) {
        if (dom && dom.parentNode) {
            dom.parentNode.removeChild(dom);
        }
    };

    /**
     * Очистка DOM-элемента
     * @param {Element} dom - Очищаемый элемент
     */
    D3Api.clearDom = function(dom) {
        while (dom.childNodes.length > 0) {
            dom.removeChild(dom.childNodes[0]);
        }
    };

    /**
     * Показать/скрыть DOM-элемент
     * @param {Element} dom - DOM-элемент
     * @param {boolean} state - true - показать, false - скрыть
     */
    D3Api.showDom = function(dom, state) {
        dom.style.display = (state) ? '' : 'none';
    };

    /**
     * Показать элемент как block
     * @param {Element} dom - DOM-элемент
     */
    D3Api.showDomBlock = function(dom) {
        dom.style.display = 'block';
    };

    /**
     * Установка стандартного отображения
     * @param {Element} dom - DOM-элемент
     */
    D3Api.setDomDisplayDefault = function(dom) {
        dom.style.display = '';
    };

    /**
     * Скрытие DOM-элемента
     * @param {Element} dom - DOM-элемент
     */
    D3Api.hideDom = function(dom) {
        dom.style.display = 'none';
    };

    /**
     * Проверка, показан ли элемент
     * @param {Element} dom - DOM-элемент
     * @returns {boolean} - true, если элемент показан
     */
    D3Api.showedDom = function(dom) {
        return dom.style.display != 'none';
    };

    /**
     * Получение свойства элемента
     * @param {Element} dom - DOM-элемент
     * @param {string} name - Имя свойства
     * @param {*} def - Значение по умолчанию
     * @returns {*} - Значение свойства
     */
    D3Api.getProperty = function(dom, name, def) {
        var p = dom.getAttribute(name);
        if (p || dom.attributes[name]) {
            return (p) ? p : dom.attributes[name].value;
        } else {
            return def;
        }
    };

    /**
     * Установка свойства элемента
     * @param {Element} dom - DOM-элемент
     * @param {string} name - Имя свойства
     * @param {*} value - Значение свойства
     * @returns {*} - Установленное значение
     */
    D3Api.setProperty = function(dom, name, value) {
        if (value == null) value = '';
        return dom.setAttribute(name, value);
    };

    /**
     * Проверка наличия свойства
     * @param {Element} dom - DOM-элемент
     * @param {string} name - Имя свойства
     * @returns {boolean} - true, если свойство существует
     */
    D3Api.hasProperty = function(dom, name) {
        return (dom.attributes && dom.attributes[name] && dom.getAttribute(name) != undefined);
    };

    /**
     * Удаление свойства
     * @param {Element} dom - DOM-элемент
     * @param {string} name - Имя свойства
     */
    D3Api.removeProperty = function(dom, name) {
        return dom.removeAttribute(name);
    };

    /**
     * Получение текстового содержимого элемента
     * @param {Element} dom - DOM-элемент
     * @returns {string} - Текстовое содержимое
     */
    D3Api.getTextContent = function(dom) {
        function textContent(dom) {
            var _result = "";
            if (dom == null) return _result;
            var childrens = dom.childNodes;
            var i = 0;
            while (i < childrens.length) {
                var child = childrens.item(i);
                switch (child.nodeType) {
                    case 1: // ELEMENT_NODE
                    case 5: // ENTITY_REFERENCE_NODE
                        _result += textContent(child);
                        break;
                    case 3: // TEXT_NODE
                    case 2: // ATTRIBUTE_NODE
                    case 4: // CDATA_SECTION_NODE
                        _result += child.nodeValue;
                        break;
                }
                i++;
            }
            return _result;
        }
        return dom.text || dom.textContent || textContent(dom);
    };

    /**
     * Получение дочернего элемента по тегу
     * @param {Element} dom - DOM-элемент
     * @param {string} tagName - Имя тега
     * @param {number} index - Индекс
     * @returns {Element|null} - Найденный элемент
     */
    D3Api.getChildTag = function(dom, tagName, index) {
        if (dom.nodeName.toUpperCase() == tagName.toUpperCase()) {
            return dom;
        }
        return dom.getElementsByTagName(tagName)[index];
    };

    /**
     * Получение элемента по селектору
     * @param {Element} dom - DOM-элемент
     * @param {string} selector - CSS-селектор
     * @returns {Element|null} - Найденный элемент
     */
    D3Api.getDomBy = function(dom, selector) {
        return dom.querySelector(selector);
    };

    /**
     * Получение всех элементов по селектору
     * @param {Element} dom - DOM-элемент
     * @param {string} selector - CSS-селектор
     * @returns {NodeList} - Список найденных элементов
     */
    D3Api.getAllDomBy = function(dom, selector) {
        return dom.querySelectorAll(selector);
    };

    /**
     * Получение элемента по атрибуту
     * @param {Element} dom - DOM-элемент
     * @param {string} attr - Имя атрибута
     * @param {string} value - Значение атрибута
     * @returns {Element|null} - Найденный элемент
     */
    D3Api.getDomByAttr = function(dom, attr, value) {
        if (dom.getAttribute(attr) == value) {
            return dom;
        }
        return D3Api.getDomBy(dom, '[' + attr + '="' + value + '"]');
    };

    /**
     * Получение элемента по имени
     * @param {Element} dom - DOM-элемент
     * @param {string} name - Значение атрибута name
     * @returns {Element|null} - Найденный элемент
     */
    D3Api.getDomByName = function(dom, name) {
        if (dom.getAttribute('name') == name) {
            return dom;
        }
        return D3Api.getDomBy(dom, '[name="' + name + '"]');
    };

    /**
     * Прокрутка к элементу
     * @param {Element} dom - DOM-элемент
     */
    D3Api.scrollTo = function(dom) {
        if (dom.scrollIntoView) {
            dom.scrollIntoView();
        }
    };

    /**
     * Установка стиля элемента
     * @param {Element} dom - DOM-элемент
     * @param {string} property - Свойство CSS
     * @param {string} value - Значение
     */
    D3Api.setStyle = function(dom, property, value) {
        dom.style[property] = value;
    };

    /**
     * Получение вычисленного стиля
     * @param {Element} oElm - DOM-элемент
     * @param {string} strCssRule - CSS-правило
     * @returns {string} - Значение стиля
     */
    D3Api.getStyle = function(oElm, strCssRule) {
        var strValue = "";
        if (document.defaultView && document.defaultView.getComputedStyle) {
            strValue = document.defaultView.getComputedStyle(oElm, "").getPropertyValue(strCssRule);
        } else if (oElm.currentStyle) {
            strCssRule = strCssRule.replace(/\\-(\\w)/g, function(strMatch, p1) {
                return p1.toUpperCase();
            });
            strValue = oElm.currentStyle[strCssRule];
        }
        return strValue;
    };

    /**
     * Привязка контекста к функции
     * @param {Function} func - Функция
     * @param {Object} thisObj - Контекст
     * @returns {Function} - Функция с привязанным контекстом
     */
    D3Api.bindThis = function(func, thisObj) {
        return function() {
            return func.apply(thisObj, arguments);
        };
    };

    /**
     * Смешивание объектов
     * @param {Object} dst - Целевой объект
     * @returns {Object} - Результирующий объект
     */
    D3Api.mixin = function(dst) {
        for (var i = 1, c = arguments.length; i < c; i++) {
            if (!arguments[i]) continue;
            var obj = arguments[i];
            for (var key in obj) {
                if (obj.hasOwnProperty(key)) {
                    if (obj[key] instanceof Array) {
                        dst[key] = D3Api.mixin([], obj[key]);
                    } else if (obj[key] instanceof Function) {
                        dst[key] = obj[key];
                    } else if (obj[key] instanceof Object) {
                        var isInstanceOf = false;
                        for (var func in D3Api) {
                            if (D3Api[func] instanceof Function) {
                                if (obj[key] instanceof D3Api[func]) {
                                    isInstanceOf = true;
                                    break;
                                }
                            }
                        }
                        if (isInstanceOf) {
                            dst[key] = obj[key];
                        } else {
                            dst[key] = D3Api.mixin({}, obj[key]);
                        }
                    } else {
                        dst[key] = obj[key];
                    }
                }
            }
        }
        return dst;
    };

    // Добавляем вспомогательные функции
    if (typeof D3Api.debug_msg === 'undefined') {
        D3Api.debug_msg = function() {
            if (D3Api.getOption && D3Api.getOption('debug', 0) > 0) {
                console.log.apply(console, arguments);
            }
        };
    }

    console.log('D3Api расширен DOM-методами из _d3api.js');
})();
            """;

    private static String getMd5Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return String.valueOf(input.hashCode());
        }
    }

    private static byte[] getCompiledJs() {
        String currentHash = getMd5Hash(JS_SOURCE_CODE);

        if (cachedHash != null && cachedHash.equals(currentHash) && JS_CACHE.containsKey(currentHash)) {
            return JS_CACHE.get(currentHash);
        }

        synchronized (LOCK) {
            currentHash = getMd5Hash(JS_SOURCE_CODE);
            if (cachedHash != null && cachedHash.equals(currentHash) && JS_CACHE.containsKey(currentHash)) {
                return JS_CACHE.get(currentHash);
            }

            byte[] compiledJs = JS_SOURCE_CODE.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            JS_CACHE.put(currentHash, compiledJs);
            cachedHash = currentHash;
            lastModified = System.currentTimeMillis();

            System.out.println("d3api_js: библиотека скомпилирована и закэширована (hash: " + currentHash + ")");

            return compiledJs;
        }
    }

    public static byte[] onPage(ru.miacomsoft.EasyWebServer.HttpExchange query) {
        query.mimeType = "application/javascript";

        query.responseHeaders.put("Cache-Control", "public, max-age=86400");
        query.responseHeaders.put("ETag", "\"" + cachedHash + "\"");

        if (query.headers.containsKey("If-None-Match")) {
            String ifNoneMatch = (String) query.headers.get("If-None-Match");
            if (ifNoneMatch.replace("\"", "").equals(cachedHash)) {
                query.responseHeaders.put("Status", "304 Not Modified");
                return new byte[0];
            }
        }

        return getCompiledJs();
    }
}