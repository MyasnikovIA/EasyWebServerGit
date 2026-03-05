package ru.miacomsoft.EasyWebServer.component;

import ru.miacomsoft.EasyWebServer.HttpExchange;

/**
 * JavaScript библиотека для компонента модального окна (DIV-based версия)
 * Предоставляет полную функциональность для создания и управления модальными окнами
 * Использует современную DIV верстку вместо таблиц
 */
public class cmpWindow_js {

    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/javascript";

        StringBuilder js = new StringBuilder();

        // Часть 1: Начало скрипта до класса DWindow
        js.append("""
                (function() {
                    if (window.cmpWindowInitialized) return;
                
                    // Функция ожидания инициализации D3Api
                    function waitForD3Api(callback) {
                        function checkD3Api() {
                            if (typeof window.D3Api !== 'undefined' && 
                                window.D3Api !== null) {
                                callback();
                                return;
                            }
                            requestAnimationFrame(checkD3Api);
                        }
                        checkD3Api();
                    }
                
                    function initialize() {
                        if (window.cmpWindowInitialized) return;
                        window.cmpWindowInitialized = true;
                        // Хранилище для всех созданных окон
                        if (!window.__d3Windows) window.__d3Windows = {};
                
                        // Текущее активное окно
                        var _activeWindow = null;
                
                        // Хранилище для колбэков при переходе на новую страницу
                        if (!window.__pageCallbacks) window.__pageCallbacks = {};
                
                        // ID текущей навигации (для страниц)
                        var _currentNavId = null;
                
                        // Хранилище для IFrame на всю страницу
                        var _fullPageIframe = null;
                
                        function removeElement(el) {
                            if (el && el.parentNode) {
                                el.parentNode.removeChild(el);
                            }
                        }
                
                        function addClass(el, className) {
                            if (el && className) {
                                el.classList.add(className);
                            }
                        }
                
                        function removeClass(el, className) {
                            if (el && className) {
                                el.classList.remove(className);
                            }
                        }
                
                        function getDocumentSize() {
                            return {
                                width: Math.max(document.documentElement.clientWidth, window.innerWidth || 0),
                                height: Math.max(document.documentElement.clientHeight, window.innerHeight || 0)
                            };
                        }
                
                        function getElementSize(el) {
                            return {
                                width: el.offsetWidth,
                                height: el.offsetHeight
                            };
                        }
                
                        function setPosition(el, left, top) {
                            if (el) {
                                el.style.left = left + 'px';
                                el.style.top = top + 'px';
                                el.style.transform = 'none';
                            }
                        }
                
                        /**
                         * Функция для получения размеров родительского элемента с учетом его положения на странице
                         */
                        function getParentElementSize(domParent) {
                            if (!domParent) return null;
                            
                            let rect = domParent.getBoundingClientRect();
                            return {
                                width: rect.width,
                                height: rect.height,
                                left: rect.left,
                                top: rect.top
                            };
                        }
                
                        /**
                         * Функция для создания наблюдателя за изменениями размеров родительского элемента
                         */
                        function createResizeObserver(domParent, callback) {
                            if (typeof ResizeObserver !== 'undefined') {
                                const observer = new ResizeObserver(() => {
                                    callback();
                                });
                                observer.observe(domParent);
                                return observer;
                            }
                            return null;
                        }
                
                        /**
                         * Функция для получения активного окна
                         */
                        window.getPage = function() {
                            return _activeWindow ? _activeWindow.D3Api : null;
                        };
                
                        /**
                         * Глобальная функция для закрытия текущего окна
                         * Эта функция вызывается из дочернего окна
                         */
                        window.close = function(result) {
                            // Проверяем, есть ли активное окно (модальное)
                            if (_activeWindow) {
                                _activeWindow.close(result);
                                return;
                            }
                
                            // Проверяем, есть ли IFrame на всю страницу
                            if (_fullPageIframe) {
                                // Получаем navId из IFrame
                                var navId = _fullPageIframe.getAttribute('data-nav-id');
                
                                // Удаляем IFrame
                                if (_fullPageIframe.parentNode) {
                                    _fullPageIframe.parentNode.removeChild(_fullPageIframe);
                                }
                
                                // Сохраняем результат для родительской страницы
                                if (navId) {
                                    sessionStorage.setItem('pageResult_' + navId, JSON.stringify(result));
                
                                    // Очищаем связанные данные
                                    sessionStorage.removeItem('currentNavId');
                                    sessionStorage.removeItem('returnUrl_' + navId);
                
                                    var callbacks = window.__pageCallbacks[navId];
                                    if (callbacks && callbacks.onclose) {
                                        callbacks.onclose(result);
                                    }
                
                                    // Очищаем колбэки
                                    sessionStorage.removeItem('pageCallbacks_' + navId);
                                    delete window.__pageCallbacks[navId];
                                }
                
                                _fullPageIframe = null;
                                _activeWindow = null;
                
                                // Показываем основной контент страницы
                                document.body.style.overflow = '';
                
                                return;
                            }
                
                            // Проверяем, есть ли сохраненный navId для возврата
                            var navId = sessionStorage.getItem('currentNavId');
                            if (navId) {
                                var returnUrl = sessionStorage.getItem('returnUrl_' + navId);
                                if (returnUrl) {
                                    // Сохраняем результат для родительской страницы
                                    sessionStorage.setItem('pageResult_' + navId, JSON.stringify(result));
                                    // Возвращаемся на родительскую страницу
                                    window.location.href = returnUrl;
                                    return;
                                }
                            }
                
                            // Если ничего не нашли, просто идем назад
                            window.history.back();
                            if (result) {
                                sessionStorage.setItem('pageResult', JSON.stringify(result));
                            }
                        };
                
                        /**
                         * Функция для создания IFrame на всю страницу
                         */
                        function createFullPageIframe(url, data, navigationId) {
                            // Скрываем основной контент страницы (опционально)
                            document.body.style.overflow = 'hidden';
                
                            // Создаем контейнер для IFrame
                            var iframeContainer = document.createElement('div');
                            iframeContainer.style.cssText = `
                                position: fixed;
                                top: 0;
                                left: 0;
                                width: 100%;
                                height: 100%;
                                z-index: 10000;
                                background: white;
                            `;
                
                            // Создаем IFrame
                            var iframe = document.createElement('iframe');
                            iframe.style.cssText = `
                                width: 100%;
                                height: 100%;
                                border: none;
                                display: block;
                            `;
                
                            // Сохраняем navigationId в атрибуте IFrame
                            if (navigationId) {
                                iframe.setAttribute('data-nav-id', navigationId);
                            }
                
                            iframeContainer.appendChild(iframe);
                            document.body.appendChild(iframeContainer);
                
                            _fullPageIframe = iframeContainer;
                
                            return { container: iframeContainer, iframe: iframe };
                        }
                
                        /**
                         * Универсальная функция для создания IFrame-окна
                         */
                        function createIframeWindow(options) {
                            return createDWindow({
                                modal: false,
                                url: options.url,
                                isFullPageIframe: options.isFullPageIframe || false,
                                isIframeInElement: options.isIframeInElement || false,
                                iframeContainer: options.iframeContainer,
                                domParent: options.domParent,
                                iframe: options.iframe,
                                navigationId: options.navigationId,
                                onshow: options.onshow,
                                oncreate: options.oncreate,
                                onclose: options.onclose
                            });
                        }
                
                        /**
                         * Функция для загрузки страницы в IFrame
                         */
                        function loadPageInIframe(url, data, navigationId) {
                            var iframeElements = createFullPageIframe(url, data, navigationId);
                            var container = iframeElements.container;
                            var iframe = iframeElements.iframe;
                
                            // Создаем объект окна для IFrame
                            var win = createIframeWindow({
                                url: url,
                                isFullPageIframe: true,
                                iframeContainer: container,
                                iframe: iframe,
                                navigationId: navigationId,
                                onshow: data.onshow,
                                oncreate: data.oncreate,
                                onclose: data.onclose
                            });
                
                            // Сохраняем ссылку на активное окно
                            _activeWindow = win;
                
                            // Добавляем обработчик загрузки IFrame
                            iframe.addEventListener('load', function() {
                                initializeIframeContent(win, iframe, url, data, navigationId, null);
                            });
                
                            iframe.addEventListener('error', function() {
                                console.error('Failed to load iframe content:', url);
                                container.innerHTML = 
                                    '<div style="color: red; padding: 20px; text-align: center;">' +
                                    '<h3>Ошибка загрузки</h3>' +
                                    '<p>Не удалось загрузить: ' + url + '</p>' +
                                    '<button onclick="window.close()">Закрыть</button>' +
                                    '</div>';
                
                                if (data.onshow) {
                                    data.onshow.call(win.D3Api, win);
                                }
                            });
                
                            iframe.src = url;
                
                            return win.D3Api;
                        }
                
                        /**
                         * Функция для создания экземпляра DWindow
                         */
                        function createDWindow(options) {
                            return new DWindow(options);
                        }
                
                        /**
                         * Функция для обновления размеров IFrame под размеры родительского элемента
                         */
                        function updateIframeSize(win, domParent) {
                            if (!win || !win.iframe) return;
                            
                            let parentSize = getParentElementSize(domParent);
                            if (parentSize) {
                                win.iframe.style.width = parentSize.width + 'px';
                                win.iframe.style.height = parentSize.height + 'px';
                            }
                        }
                
                        /**
                          * Единая функция для инициализации Form объекта в окне iframe
                          */
                         function initializeFormObject(iframeWindow, d3Api) {
                             if (typeof iframeWindow.Form === 'undefined') {
                                 iframeWindow.Form = {};
                             }
                
                             // Базовые методы Form
                             iframeWindow.Form.getVar = function(name, defValue) {
                                 return d3Api && d3Api.getVar ? d3Api.getVar(name, defValue) : defValue;
                             };
                
                             iframeWindow.Form.setVar = function(name, value) {
                                 if (d3Api && d3Api.setVar) d3Api.setVar(name, value);
                             };
                
                             iframeWindow.Form.getValue = function(name, defValue) {
                                 return d3Api && d3Api.getValue ? d3Api.getValue(name, defValue) : defValue;
                             };
                
                             iframeWindow.Form.setValue = function(name, value) {
                                 if (d3Api && d3Api.setValue) d3Api.setValue(name, value);
                             };
                
                             iframeWindow.Form.getCaption = function(name) {
                                 return d3Api && d3Api.getCaption ? d3Api.getCaption(name) : '';
                             };
                
                             iframeWindow.Form.setCaption = function(name, value) {
                                 if (d3Api && d3Api.setCaption) d3Api.setCaption(name, value);
                             };
                
                             iframeWindow.Form.close = function(result) {
                                 if (d3Api && d3Api.close) {
                                     d3Api.close(result);
                                 } else {
                                     window.parent.close(result);
                                 }
                             };
                
                             // Добавляем метод для получения D3Api
                             iframeWindow.Form.getD3Api = function() {
                                 return d3Api;
                             };
                         }
                
                        /**
                         * Единая функция для компиляции скриптов в документе
                         */
                        function compileScriptsInDocument(doc, formObject) {
                            const scripts = doc.querySelectorAll('[cmptype="Script"]');
                            scripts.forEach(function(script) {
                                if (script.text && script.text.trim()) {
                                    try {
                                        const scriptFunction = new Function('Form', script.text);
                                        scriptFunction.call(doc.defaultView, formObject);
                                    } catch (e) {
                                        console.error('Error compiling script:', e);
                                    }
                                }
                            });
                        }
                
                        /**
                         * Единая функция для инъекции базового D3Api в iframe
                         */
                        function injectBaseD3ApiIntoIframe(iframeDoc) {
                            return new Promise((resolve, reject) => {
                                try {
                                    const baseScript = iframeDoc.createElement('script');
                                    baseScript.type = 'text/javascript';
                                    baseScript.textContent = `
                                        if (typeof window.D3Api === 'undefined') {
                                            window.D3Api = {};
                                        }
                
                                        if (window.parent.D3Api && window.parent.D3Api.ControlBaseProperties) {
                                            window.D3Api.ControlBaseProperties = window.parent.D3Api.ControlBaseProperties;
                                        }
                
                                        if (window.parent.D3Api && window.parent.D3Api.BaseCtrl) {
                                            window.D3Api.BaseCtrl = window.parent.D3Api.BaseCtrl;
                                        }
                
                                        var methodsToCopy = [
                                            'stopEvent', 'getEvent', 'addEvent', 'removeEvent',
                                            'getControl', 'setValue', 'getValue', 'setVar', 'getVar',
                                            'setCaption', 'getCaption', 'setDisabled', 'getBoolean',
                                            'hasProperty', 'getProperty', 'setProperty',
                                            'getChildTag', 'hideDom', 'showDom', 'createDom',
                                            'stringTrim', 'parseDate', 'hours2time', 'debug_msg'
                                        ];
                
                                        methodsToCopy.forEach(function(method) {
                                            if (window.parent.D3Api && window.parent.D3Api[method]) {
                                                window.D3Api[method] = window.parent.D3Api[method];
                                            }
                                        });
                                    `;
                
                                    iframeDoc.head.appendChild(baseScript);
                                    resolve();
                                } catch (e) {
                                    console.error('Failed to inject base D3Api:', e);
                                    reject(e);
                                }
                            });
                        }
                
                        /**
                         * Единая функция для загрузки ресурса в iframe
                         */
                        function loadResourceInIframe(iframeDoc, url, type) {
                            return new Promise((resolve, reject) => {
                                if (type === 'script') {
                                    const existingScript = iframeDoc.querySelector(`script[src="${url}"]`);
                                    if (existingScript) {
                                        resolve();
                                        return;
                                    }
                                } else if (type === 'link') {
                                    const existingLink = iframeDoc.querySelector(`link[href="${url}"]`);
                                    if (existingLink) {
                                        resolve();
                                        return;
                                    }
                                }
                
                                const element = iframeDoc.createElement(type);
                
                                if (type === 'script') {
                                    element.src = url;
                                    element.type = 'text/javascript';
                                } else {
                                    element.href = url;
                                    element.rel = 'stylesheet';
                                    element.type = 'text/css';
                                }
                
                                element.setAttribute('cmp', 'jslib');
                
                                element.onload = () => {
                                    resolve();
                                };
                
                                element.onerror = () => {
                                    console.error(`Failed to load ${type}: ${url}`);
                                    reject(new Error(`Failed to load ${type}: ${url}`));
                                };
                
                                iframeDoc.head.appendChild(element);
                            });
                        }
                
                        /**
                         * Единая функция для загрузки всех библиотек в iframe
                         */
                        async function loadLibrariesInIframe(iframeDoc) {
                            try {
                                await injectBaseD3ApiIntoIframe(iframeDoc);
                                const scriptTags = iframeDoc.querySelectorAll('script[cmp="jslib"]');
                                const linkTags = iframeDoc.querySelectorAll('link[cmp="jslib"]');
                                
                                for (const link of linkTags) {
                                    const href = link.getAttribute('href');
                                    if (href && link.getAttribute('rel') === 'stylesheet') {
                                        await loadResourceInIframe(iframeDoc, href, 'link');
                                    }
                                }
                
                                for (const script of scriptTags) {
                                    const src = script.getAttribute('src');
                                    if (src) {
                                        await loadResourceInIframe(iframeDoc, src, 'script');
                                    }
                                }
                                return true;
                            } catch (error) {
                                console.error('Error loading libraries:', error);
                                return false;
                            }
                        }
                
                        /**
                          * Единая функция для инициализации контента в IFrame
                          */
                         function initializeIframeContent(win, iframe, url, data, navigationId, domParent) {
                             // Защита от повторной инициализации
                             if (win._initialized) {
                                 console.log('Iframe already initialized, skipping...');
                                 return;
                             }
                             win._initialized = true;
                
                             var iframeDoc = iframe.contentDocument || iframe.contentWindow.document;
                             win.document = iframeDoc;
                
                             // Помечаем документ как уже инициализированный, чтобы избежать повторной компиляции скриптов
                             if (iframeDoc._d3Initialized) {
                                 console.log('Document already initialized, skipping...');
                                 win.show();
                                 return;
                             }
                             iframeDoc._d3Initialized = true;
                
                             // Если есть родительский элемент, устанавливаем начальные размеры
                             if (domParent) {
                                 updateIframeSize(win, domParent);
                
                                 // Создаем наблюдатель за изменениями размеров родительского элемента
                                 win.resizeObserver = createResizeObserver(domParent, function() {
                                     updateIframeSize(win, domParent);
                                 });
                             }
                
                             // Инициализируем Form объект с временными методами (до загрузки библиотек)
                             initializeFormObject(iframe.contentWindow, null);
                
                             // Компилируем скрипты из DOM только один раз
                             compileScriptsInDocument(iframeDoc, iframe.contentWindow.Form);
                
                             // Загружаем библиотеки и продолжаем инициализацию
                             loadLibrariesInIframe(iframeDoc).then((success) => {
                                 if (success && iframe.contentWindow.D3Api) {
                                     // Дожидаемся загрузки всех скриптов и инициализации D3Api
                                     setTimeout(function() {
                                         try {
                                             // Перенастраиваем Form объект с полноценным D3Api
                                             initializeFormObject(iframe.contentWindow, win.D3Api);
                
                                             // Добавляем прямые ссылки на методы D3Api в глобальную область видимости iframe
                                             var iframeWindow = iframe.contentWindow;
                
                                             // Копируем все методы D3Api в глобальную область видимости iframe
                                             var methodsToCopy = [
                                                 'getValue', 'setValue', 'getVar', 'setVar',\s
                                                 'getCaption', 'setCaption', 'setDisabled', 'getBoolean',
                                                 'hasProperty', 'getProperty', 'setProperty',
                                                 'getChildTag', 'hideDom', 'showDom', 'createDom',
                                                 'stringTrim', 'parseDate', 'hours2time', 'debug_msg',
                                                 'stopEvent', 'getEvent', 'addEvent', 'removeEvent',
                                                 'getControl', 'onChangeVar', 'offChangeVar',
                                                 'onChangeValue', 'offChangeValue', 'onChangeCaption', 'offChangeCaption',
                                                 'onChangeSession', 'offChangeSession'
                                             ];
                
                                             methodsToCopy.forEach(function(method) {
                                                 if (typeof win.D3Api[method] === 'function') {
                                                     iframeWindow[method] = function() {
                                                         return win.D3Api[method].apply(win.D3Api, arguments);
                                                     };
                                                 }
                                             });
                
                                             // Добавляем ссылку на сам D3Api
                                             iframeWindow.D3Api = win.D3Api;
                
                                             // Добавляем ссылку на Form
                                             if (!iframeWindow.Form) {
                                                 iframeWindow.Form = {};
                                             }
                
                                             // Переопределяем close в Form
                                             iframeWindow.Form.close = function(result) {
                                                 win.close(result);
                                             };
                
                                             const form = iframeDoc.querySelector('[cmptype="Form"]');
                                             if (form) {
                                                 win.caption = form.getAttribute('caption');
                                                 if (win.title) {
                                                     win.title.textContent = win.caption;
                                                 }
                                                 iframe.contentWindow.Form._DOM_ = form;
                                             }
                
                                             // Отправляем сообщение о готовности
                                             if (iframe.contentWindow.parent && iframe.contentWindow.parent.postMessage) {
                                                 iframe.contentWindow.parent.postMessage({ command: 'init', data: data.vars || {} }, '*');
                                             }
                
                                             win.show();
                
                                             // Вызываем onCreate если он есть в Form
                                             if (typeof iframe.contentWindow.Form.onCreate === 'function') {
                                                 try {
                                                     iframe.contentWindow.Form.onCreate.call(win.D3Api, win);
                                                 } catch (e) {
                                                     console.error('Error in Form.onCreate:', e);
                                                 }
                                             }
                
                                             // Вызываем oncreate callback если он есть в data
                                             if (data.oncreate && typeof data.oncreate === 'function') {
                                                 data.oncreate.call(win.D3Api, win);
                                             }
                
                                             // Вызываем onshow если он есть в Form
                                             if (typeof iframe.contentWindow.Form.onShow === 'function') {
                                                 try {
                                                     iframe.contentWindow.Form.onShow.call(win.D3Api, win);
                                                 } catch (e) {
                                                     console.error('Error in Form.onShow:', e);
                                                 }
                                             }
                
                                             console.log('Iframe content initialized successfully');
                                         } catch (e) {
                                             console.error('Error during iframe initialization:', e);
                                         }
                                     }, 100); // Небольшая задержка для полной загрузки
                                 } else {
                                     console.error('Failed to load required libraries');
                                 }
                             });
                         }
                
                        /**
                         * Функция для перехода на новую страницу (модифицированная)
                         */
                        function navigateToPage(url, data, domParent) {
                            var navigationId = 'nav_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
                
                            // Сохраняем все колбэки
                            var callbacks = {
                                onclose: data.onclose ? data.onclose.toString() : null,
                                oncreate: data.oncreate ? data.oncreate.toString() : null,
                                onshow: data.onshow ? data.onshow.toString() : null,
                                vars: data.vars || {},
                                navigationId: navigationId,
                                returnUrl: window.location.href
                            };
                
                            sessionStorage.setItem('pageCallbacks_' + navigationId, JSON.stringify(callbacks));
                            window.__pageCallbacks[navigationId] = data;
                
                            // Если указан domParent, создаем IFrame внутри этого элемента
                            if (domParent) {
                                // Получаем размеры родительского элемента
                                let parentSize = getParentElementSize(domParent);
                                if (!parentSize) {
                                    console.error('Parent element has no size or is not visible');
                                    return null;
                                }
                
                                // Проверяем, не был ли уже создан iframe в этом родителе
                                if (domParent._d3IframeInitialized) {
                                    console.warn('Iframe already initialized in this parent element');
                                    return null;
                                }
                                
                                // Помечаем родительский элемент как инициализированный
                                domParent._d3IframeInitialized = true;
                
                                // Создаем IFrame внутри указанного элемента
                                var iframe = document.createElement('iframe');
                                iframe.style.cssText = `
                                    width: ${parentSize.width}px;
                                    height: ${parentSize.height}px;
                                    border: none;
                                    display: block;
                                `;
                                iframe.setAttribute('data-nav-id', navigationId);
                
                                // Очищаем родительский элемент и добавляем IFrame
                                domParent.innerHTML = '';
                                domParent.appendChild(iframe);
                
                                // Создаем объект окна для этого IFrame
                                var win = createIframeWindow({
                                    url: url,
                                    isIframeInElement: true,
                                    domParent: domParent,
                                    iframe: iframe,
                                    navigationId: navigationId,
                                    onshow: data.onshow,
                                    oncreate: data.oncreate,
                                    onclose: data.onclose
                                });
                
                                _activeWindow = win;
                
                                // Добавляем обработчик загрузки
                                iframe.addEventListener('load', function() {
                                    initializeIframeContent(win, iframe, url, data, navigationId, domParent);
                                });
                
                                iframe.addEventListener('error', function() {
                                    console.error('Failed to load iframe content:', url);
                                    domParent.innerHTML = 
                                        '<div style="color: red; padding: 20px; text-align: center;">' +
                                        '<h3>Ошибка загрузки</h3>' +
                                        '<p>Не удалось загрузить: ' + url + '</p>' +
                                        '<button onclick="window.close()">Закрыть</button>' +
                                        '</div>';
                                    // Сбрасываем флаг при ошибке
                                    domParent._d3IframeInitialized = false;
                                });
                
                                iframe.src = url;
                
                                return win.D3Api;
                            } else {
                                // Если domParent не указан, создаем IFrame на всю страницу
                                return loadPageInIframe(url, data, navigationId);
                            }
                        }
                
                        /**
                         * Функция для восстановления колбэков при загрузке страницы
                         */
                        function restorePageCallbacks() {
                            var urlParams = new URLSearchParams(window.location.search);
                            var navId = urlParams.get('_navId');
                
                            if (navId) {
                                _currentNavId = navId;
                
                                var callbacksJson = sessionStorage.getItem('pageCallbacks_' + navId);
                                if (callbacksJson) {
                                    try {
                                        var callbacks = JSON.parse(callbacksJson);
                
                                        var restoredData = {
                                            vars: callbacks.vars || {},
                                            returnUrl: callbacks.returnUrl
                                        };
                
                                        if (callbacks.onclose) {
                                            restoredData.onclose = new Function('return ' + callbacks.onclose)();
                                        }
                
                                        if (callbacks.oncreate) {
                                            restoredData.oncreate = new Function('return ' + callbacks.oncreate)();
                                        }
                
                                        if (callbacks.onshow) {
                                            restoredData.onshow = new Function('return ' + callbacks.onshow)();
                                        }
                
                                        window.__pageCallbacks[navId] = restoredData;
                
                                        // Создаем объект страницы с правильным методом close
                                        var pageObject = {
                                            D3Api: Object.create(window.D3Api)
                                        };
                
                                        // Добавляем методы для работы с этой страницей
                                        pageObject.D3Api.close = function(result) {
                                            if (restoredData.returnUrl) {
                                                sessionStorage.setItem('pageResult_' + navId, JSON.stringify(result));
                                                window.location.href = restoredData.returnUrl;
                                            }
                                        };
                
                                        pageObject.D3Api.getVar = function(name, defValue) {
                                            return restoredData.vars ? restoredData.vars[name] : defValue;
                                        };
                
                                        pageObject.D3Api.setVar = function(name, value) {
                                            if (!restoredData.vars) restoredData.vars = {};
                                            restoredData.vars[name] = value;
                                        };
                
                                        _activeWindow = pageObject;
                
                                        // Вызываем oncreate если есть
                                        if (restoredData.oncreate) {
                                            restoredData.oncreate.call(pageObject.D3Api, pageObject.D3Api);
                                        }
                
                                        // Вызываем onshow если есть
                                        if (restoredData.onshow) {
                                            requestAnimationFrame(function() {
                                                restoredData.onshow.call(pageObject.D3Api, pageObject.D3Api);
                                            });
                                        }
                
                                    } catch (e) {
                                        console.error('Failed to restore page callbacks:', e);
                                    }
                                }
                            } else {
                                // Проверяем, нет ли результата от дочерней страницы
                                checkForPageResult();
                            }
                        }
                
                        /**
                         * Проверяет наличие результата от дочерней страницы
                         */
                        function checkForPageResult() {
                            // Ищем все ключи pageResult_ в sessionStorage
                            var resultKeys = [];
                            for (var i = 0; i < sessionStorage.length; i++) {
                                var key = sessionStorage.key(i);
                                if (key && key.startsWith('pageResult_')) {
                                    resultKeys.push(key);
                                }
                            }
                
                            if (resultKeys.length > 0) {
                                // Берем первый найденный результат
                                var resultKey = resultKeys[0];
                                try {
                                    var resultJson = sessionStorage.getItem(resultKey);
                                    var result = JSON.parse(resultJson);
                                    sessionStorage.removeItem(resultKey);
                
                                    var navId = resultKey.replace('pageResult_', '');
                
                                    // Очищаем связанные данные
                                    sessionStorage.removeItem('currentNavId');
                                    sessionStorage.removeItem('returnUrl_' + navId);
                
                                    var callbacks = window.__pageCallbacks[navId];
                                    if (callbacks && callbacks.onclose) {
                                        callbacks.onclose(result);
                                    }
                
                                    // Очищаем колбэки
                                    sessionStorage.removeItem('pageCallbacks_' + navId);
                                    delete window.__pageCallbacks[navId];
                
                                } catch (e) {
                                    console.error('Failed to process page result:', e);
                                }
                            }
                        }
                
                        /**
                         * Генерация HTML для окна
                         */
                        function getWindowXml(options) {
                            options = options || {};
                            let modal = options.modal !== false;
                            let width = options.width || 500;
                            let height = options.height || 400;
                            let caption = options.caption || '';
                            let theme = options.theme || 'modern';
                            let url = options.url || '';
                
                            let overlay = '';
                            if (modal) {
                                overlay = '<div class="win_overlow"></div>';
                            }
                
                            let windowHtml = overlay + `
                                <div class="window ${theme}" style="width: ${width}px; height: ${height}px; left: 50%; top: 50%; transform: translate(-50%, -50%); display: none; position: fixed; z-index: 9999; background: white; border: 1px solid #ccc; border-radius: 8px; box-shadow: 0 4px 20px rgba(0,0,0,0.2);">
                                    <div class="window-header" data-role="title-row" style="color: rgb(50,50,50);display: flex; justify-content: space-between; align-items: center; padding: 8px 12px; background: #f0f0f0; border-bottom: 1px solid #ccc; cursor: move; border-radius: 8px 8px 0 0;">
                                        <div class="window-title" data-role="title" style="font-weight: bold; font-size: 14px;">${caption}</div>
                                        <div class="window-controls" style="display: flex; gap: 8px;">
                                            <div class="window-control reload" data-role="reload" title="Обновить" style="color: rgb(50,50,50);cursor: pointer; width: 20px; height: 20px; display: flex; align-items: center; justify-content: center;">↻</div>
                                            <div class="window-control maximize" data-role="maximize" title="Развернуть" style="color: rgb(50,50,50);cursor: pointer; width: 20px; height: 20px; display: flex; align-items: center; justify-content: center;">🗖</div>
                                            <div class="window-control close" data-role="close" title="Закрыть" style="color: rgb(50,50,50);cursor: pointer; width: 20px; height: 20px; display: flex; align-items: center; justify-content: center;">✕</div>
                                        </div>
                                    </div>
                                    <div class="window-content" style="flex: 1; overflow: auto; height: calc(100% - 40px); position: relative;">
                                        <iframe class="window-iframe" data-role="iframe" src="${url}" frameborder="0" style="width: 100%; height: 100%; border: none; display: block;"></iframe>
                                    </div>
                
                                    <div class="window-resize-handle resize-n" data-role="resize-n" style="position: absolute; top: 0; left: 5px; right: 5px; height: 5px; cursor: n-resize;"></div>
                                    <div class="window-resize-handle resize-s" data-role="resize-s" style="position: absolute; bottom: 0; left: 5px; right: 5px; height: 5px; cursor: s-resize;"></div>
                                    <div class="window-resize-handle resize-e" data-role="resize-e" style="position: absolute; top: 5px; right: 0; bottom: 5px; width: 5px; cursor: e-resize;"></div>
                                    <div class="window-resize-handle resize-w" data-role="resize-w" style="position: absolute; top: 5px; left: 0; bottom: 5px; width: 5px; cursor: w-resize;"></div>
                                    <div class="window-resize-handle resize-ne" data-role="resize-ne" style="position: absolute; top: 0; right: 0; width: 10px; height: 10px; cursor: ne-resize;"></div>
                                    <div class="window-resize-handle resize-nw" data-role="resize-nw" style="position: absolute; top: 0; left: 0; width: 10px; height: 10px; cursor: nw-resize;"></div>
                                    <div class="window-resize-handle resize-se" data-role="resize-se" style="position: absolute; bottom: 0; right: 0; width: 15px; height: 15px; cursor: se-resize;"></div>
                                    <div class="window-resize-handle resize-sw" data-role="resize-sw" style="position: absolute; bottom: 0; left: 0; width: 10px; height: 10px; cursor: sw-resize;"></div>
                                </div>
                            `;
                
                            return windowHtml;
                        }
                """);

        // Часть 2: Класс DWindow и остальная логика
        js.append("""
                
                        class DWindow {
                            constructor(options) {
                                this.options = options || {};
                                this.modal = this.options.modal !== false;
                                this.width = this.options.width || 500;
                                this.height = this.options.height || 400;
                                this.caption = this.options.caption || 'Окно';
                                this.theme = this.options.theme || 'modern';
                                this.url = this.options.url || '';
                                this.navigationId = this.options.navigationId || null;
                                this._initialized = false;
                
                                this.element = null;
                                this.overlay = null;
                                this.iframe = this.options.iframe || null;
                                this.iframeOverlay = null;
                                this.title = null;
                                this.titleRow = null;
                                this.resizeObserver = null;
                
                                this.dragging = false;
                                this.resizing = false;
                                this.resizeType = null;
                                this.dragOffset = { x: 0, y: 0 };
                
                                this.minWidth = 250;
                                this.minHeight = 150;
                                this.maximized = false;
                                this.closed = false;
                
                                this.listeners = {};
                                this.originalPosition = null;
                                this.originalSize = null;
                                this.messageHandlers = {};
                
                                // Флаги для специальных режимов
                                this.isFullPageIframe = options.isFullPageIframe || false;
                                this.isIframeInElement = options.isIframeInElement || false;
                
                                // Локальные переменные окна
                                this._vars = {};
                
                                // Создаем объект D3Api для этого окна на основе глобального D3Api
                                this.D3Api = Object.create(window.D3Api);
                
                                var self = this;
                
                                // Переопределяем методы для работы с локальными переменными
                                this.D3Api.setVar = function(name, value) {
                                    self._vars[name] = value;
                                };
                
                                this.D3Api.getVar = function(name, defValue) {
                                    return self._vars[name] !== undefined ? self._vars[name] : defValue;
                                };
                
                                // Переопределяем методы для работы с контролами окна
                                this.D3Api.setValue = function(name, value) {
                                    if (self.element) {
                                        var ctrl = self.element.querySelector('[name="' + name + '"]');
                                        if (ctrl) {
                                            if (ctrl.tagName.toLowerCase() === 'input') {
                                                if (ctrl.type === 'checkbox') {
                                                    ctrl.checked = (value === true || value === 'on' || value === 'true');
                                                } else {
                                                    ctrl.value = value;
                                                }
                                            } else if (ctrl.tagName.toLowerCase() === 'select' || ctrl.tagName.toLowerCase() === 'textarea') {
                                                ctrl.value = value;
                                            } else {
                                                ctrl.textContent = value;
                                            }
                                        }
                                    }
                                };
                
                                this.D3Api.getValue = function(name, defValue) {
                                    if (self.element) {
                                        var ctrl = self.element.querySelector('[name="' + name + '"]');
                                        if (ctrl) {
                                            if (ctrl.tagName.toLowerCase() === 'input') {
                                                if (ctrl.type === 'checkbox') {
                                                    return ctrl.checked;
                                                } else {
                                                    return ctrl.value || defValue;
                                                }
                                            } else if (ctrl.tagName.toLowerCase() === 'select' || ctrl.tagName.toLowerCase() === 'textarea') {
                                                return ctrl.value || defValue;
                                            } else {
                                                return ctrl.textContent || defValue;
                                            }
                                        }
                                    }
                                    return defValue;
                                };
                
                                // Переопределяем методы для работы с подписями
                                this.D3Api.setCaption = function(text) {
                                    self.setCaption(text);
                                };
                
                                this.D3Api.getCaption = function() {
                                    return self.title ? self.title.textContent : '';
                                };
                
                                // Добавляем метод закрытия окна
                                this.D3Api.close = function(result) {
                                    self.close(result);
                                };
                
                                // Добавляем методы для работы со скриптами в окне
                                this.D3Api.loadScript = function(name, src, async, defer) {
                                    if (self.iframe && self.iframe.contentWindow && self.iframe.contentWindow.D3Api) {
                                        return self.iframe.contentWindow.D3Api.loadScript(name, src, async, defer);
                                    }
                                    return Promise.reject('Iframe not ready');
                                };
                
                                this.D3Api.executeScript = function(name, content) {
                                    if (self.iframe && self.iframe.contentWindow && self.iframe.contentWindow.D3Api) {
                                        return self.iframe.contentWindow.D3Api.executeScript(name, content);
                                    }
                                    return false;
                                };
                
                                this.D3Api.getScriptStatus = function(name) {
                                    if (self.iframe && self.iframe.contentWindow && self.iframe.contentWindow.D3Api) {
                                        return self.iframe.contentWindow.D3Api.getScriptStatus(name);
                                    }
                                    return { exists: false, error: 'Iframe not ready' };
                                };
                
                                this.D3Api.waitForScript = function(name) {
                                    if (self.iframe && self.iframe.contentWindow && self.iframe.contentWindow.D3Api) {
                                        return self.iframe.contentWindow.D3Api.waitForScript(name);
                                    }
                                    return Promise.reject('Iframe not ready');
                                };
                
                                // Сохраняем ссылку на окно
                                this.windowId = 'win_' + Date.now() + '_' + Math.random().toString(36).substr(2, 9);
                                window.__d3Windows[this.windowId] = this;
                
                                // Если это не специальный режим с готовым iframe, инициализируем обычное окно
                                if (!this.isFullPageIframe && !this.isIframeInElement) {
                                    this.init();
                                }
                            }
                
                            init() {
                                var temp = document.createElement('div');
                                temp.innerHTML = getWindowXml(this.options);
                
                                if (this.modal) {
                                    this.overlay = temp.querySelector('.win_overlow');
                                }
                
                                this.element = temp.querySelector('.window');
                
                                if (!this.element) {
                                    console.error('Failed to create window element');
                                    return;
                                }
                
                                this.iframe = this.element.querySelector('[data-role="iframe"]');
                                this.title = this.element.querySelector('[data-role="title"]');
                                this.titleRow = this.element.querySelector('[data-role="title-row"]');
                
                                if (this.overlay) {
                                    document.body.appendChild(this.overlay);
                                    this.overlay.style.display = 'none';
                                }
                                document.body.appendChild(this.element);
                
                                this.element.style.display = 'none';
                
                                this.initEventHandlers();
                                this.originalSize = { width: this.width, height: this.height };
                                this.setupIframeMessaging();
                            }
                
                            createIframeOverlay() {
                                if (!this.iframe || this.iframeOverlay) return;
                
                                let cursor = 'default';
                                if (this.dragging) {
                                    cursor = 'move';
                                } else if (this.resizing) {
                                    switch (this.resizeType) {
                                        case 'resize-n': cursor = 'n-resize'; break;
                                        case 'resize-s': cursor = 's-resize'; break;
                                        case 'resize-e': cursor = 'e-resize'; break;
                                        case 'resize-w': cursor = 'w-resize'; break;
                                        case 'resize-ne': cursor = 'ne-resize'; break;
                                        case 'resize-nw': cursor = 'nw-resize'; break;
                                        case 'resize-se': cursor = 'se-resize'; break;
                                        case 'resize-sw': cursor = 'sw-resize'; break;
                                    }
                                }
                
                                this.iframeOverlay = document.createElement('div');
                                this.iframeOverlay.className = 'window-iframe-overlay';
                                this.iframeOverlay.style.cssText = `
                                    position: absolute;
                                    top: 0;
                                    left: 0;
                                    width: 100%;
                                    height: 100%;
                                    background: transparent;
                                    z-index: 10000;
                                    cursor: ${cursor};
                                `;
                
                                let contentDiv = this.element.querySelector('.window-content');
                                if (contentDiv) {
                                    contentDiv.appendChild(this.iframeOverlay);
                                }
                            }
                
                            removeIframeOverlay() {
                                if (this.iframeOverlay && this.iframeOverlay.parentNode) {
                                    this.iframeOverlay.parentNode.removeChild(this.iframeOverlay);
                                    this.iframeOverlay = null;
                                }
                            }
                
                            setupIframeMessaging() {
                                if (!this.iframe) return;
                
                                window.addEventListener('message', (event) => {
                                    if (event.source === this.iframe.contentWindow) {
                                        if (event.data && event.data.command) {
                                            switch (event.data.command) {
                                                case 'close':
                                                    this.close(event.data.result);
                                                    break;
                                                case 'resize':
                                                    if (event.data.width && event.data.height) {
                                                        this.setSize(event.data.width, event.data.height);
                                                    }
                                                    break;
                                                case 'setCaption':
                                                    this.setCaption(event.data.caption);
                                                    break;
                                                case 'scriptLoaded':
                                                    this.dispatchEvent('scriptLoaded', event.data);
                                                    break;
                                                case 'scriptError':
                                                    console.error('Script error in iframe:', event.data.name, event.data.error);
                                                    this.dispatchEvent('scriptError', event.data);
                                                    break;
                                                default:
                                                    if (this.messageHandlers[event.data.command]) {
                                                        this.messageHandlers[event.data.command](event.data);
                                                    }
                                            }
                                        }
                                        this.dispatchEvent('message', event.data);
                                    }
                                });
                            }
                
                            sendToIframe(message) {
                                if (this.iframe && this.iframe.contentWindow) {
                                    this.iframe.contentWindow.postMessage(message, '*');
                                }
                            }
                
                            onMessage(command, handler) {
                                this.messageHandlers[command] = handler;
                            }
                
                            initEventHandlers() {
                                if (!this.element) return;
                
                                this.handleMouseMove = (e) => {
                                    if (this.dragging) {
                                        this.onDrag(e);
                                    } else if (this.resizing) {
                                        this.onResize(e);
                                    }
                                };
                
                                this.handleMouseUp = (e) => {
                                    if (this.dragging) {
                                        this.stopDrag(e);
                                    } else if (this.resizing) {
                                        this.stopResize(e);
                                    }
                                    this.removeIframeOverlay();
                                };
                
                                if (this.titleRow) {
                                    this.titleRow.addEventListener('mousedown', (e) => {
                                        if (e.button !== 0) return;
                                        this.startDrag(e);
                                    });
                                    this.titleRow.addEventListener('dblclick', (e) => this.toggleMaximize(e));
                                }
                
                                let resizeHandles = this.element.querySelectorAll('[data-role^="resize-"]');
                                resizeHandles.forEach(handle => {
                                    handle.addEventListener('mousedown', (e) => {
                                        if (e.button !== 0) return;
                                        let role = handle.getAttribute('data-role');
                                        this.startResize(e, role);
                                    });
                                });
                
                                let closeBtn = this.element.querySelector('[data-role="close"]');
                                if (closeBtn) {
                                    closeBtn.addEventListener('click', (e) => this.close());
                                }
                
                                let maximizeBtn = this.element.querySelector('[data-role="maximize"]');
                                if (maximizeBtn) {
                                    maximizeBtn.addEventListener('click', (e) => this.toggleMaximize(e));
                                }
                
                                let reloadBtn = this.element.querySelector('[data-role="reload"]');
                                if (reloadBtn) {
                                    reloadBtn.addEventListener('click', (e) => this.reload());
                                }
                
                                this.element.addEventListener('selectstart', (e) => e.preventDefault());
                
                                document.addEventListener('mousemove', this.handleMouseMove);
                                document.addEventListener('mouseup', this.handleMouseUp);
                            }
                
                            startDrag(e) {
                                if (this.maximized) return;
                
                                e.preventDefault();
                
                                let rect = this.element.getBoundingClientRect();
                                this.dragOffset = {
                                    x: e.clientX - rect.left,
                                    y: e.clientY - rect.top
                                };
                
                                this.dragging = true;
                                this.resizing = false;
                                document.body.classList.add('noselect');
                                if (this.titleRow) {
                                    this.titleRow.classList.add('dragging');
                                }
                
                                this.originalPosition = {
                                    left: rect.left,
                                    top: rect.top
                                };
                
                                this.createIframeOverlay();
                            }
                
                            onDrag(e) {
                                if (!this.dragging) return;
                
                                e.preventDefault();
                
                                let left = e.clientX - this.dragOffset.x;
                                let top = e.clientY - this.dragOffset.y;
                
                                let maxLeft = window.innerWidth - this.element.offsetWidth;
                                let maxTop = window.innerHeight - this.element.offsetHeight;
                
                                left = Math.max(0, Math.min(left, maxLeft));
                                top = Math.max(0, Math.min(top, maxTop));
                
                                this.element.style.left = left + 'px';
                                this.element.style.top = top + 'px';
                                this.element.style.transform = 'none';
                            }
                
                            stopDrag(e) {
                                if (!this.dragging) return;
                
                                this.dragging = false;
                                document.body.classList.remove('noselect');
                                if (this.titleRow) {
                                    this.titleRow.classList.remove('dragging');
                                }
                
                                this.dispatchEvent('move', {
                                    left: parseInt(this.element.style.left),
                                    top: parseInt(this.element.style.top)
                                });
                            }
                
                            startResize(e, type) {
                                e.preventDefault();
                
                                this.resizing = true;
                                this.dragging = false;
                                this.resizeType = type;
                
                                let rect = this.element.getBoundingClientRect();
                                this.startResizeData = {
                                    x: e.clientX,
                                    y: e.clientY,
                                    width: rect.width,
                                    height: rect.height,
                                    left: rect.left,
                                    top: rect.top,
                                    right: rect.right,
                                    bottom: rect.bottom
                                };
                
                                document.body.classList.add('noselect');
                                this.createIframeOverlay();
                            }
                
                            onResize(e) {
                                if (!this.resizing) return;
                                e.preventDefault();
                
                                let dx = e.clientX - this.startResizeData.x;
                                let dy = e.clientY - this.startResizeData.y;
                
                                let newWidth = this.startResizeData.width;
                                let newHeight = this.startResizeData.height;
                                let newLeft = this.startResizeData.left;
                                let newTop = this.startResizeData.top;
                
                                switch (this.resizeType) {
                                    case 'resize-se':
                                        newWidth = Math.max(this.minWidth, this.startResizeData.width + dx);
                                        newHeight = Math.max(this.minHeight, this.startResizeData.height + dy);
                                        break;
                                    case 'resize-e':
                                        newWidth = Math.max(this.minWidth, this.startResizeData.width + dx);
                                        break;
                                    case 'resize-s':
                                        newHeight = Math.max(this.minHeight, this.startResizeData.height + dy);
                                        break;
                                    case 'resize-w':
                                        newWidth = Math.max(this.minWidth, this.startResizeData.width - dx);
                                        newLeft = this.startResizeData.right - newWidth;
                                        break;
                                    case 'resize-n':
                                        newHeight = Math.max(this.minHeight, this.startResizeData.height - dy);
                                        newTop = this.startResizeData.bottom - newHeight;
                                        break;
                                    case 'resize-ne':
                                        newWidth = Math.max(this.minWidth, this.startResizeData.width + dx);
                                        newHeight = Math.max(this.minHeight, this.startResizeData.height - dy);
                                        newTop = this.startResizeData.bottom - newHeight;
                                        break;
                                    case 'resize-nw':
                                        newWidth = Math.max(this.minWidth, this.startResizeData.width - dx);
                                        newHeight = Math.max(this.minHeight, this.startResizeData.height - dy);
                                        newLeft = this.startResizeData.right - newWidth;
                                        newTop = this.startResizeData.bottom - newHeight;
                                        break;
                                    case 'resize-sw':
                                        newWidth = Math.max(this.minWidth, this.startResizeData.width - dx);
                                        newHeight = Math.max(this.minHeight, this.startResizeData.height + dy);
                                        newLeft = this.startResizeData.right - newWidth;
                                        break;
                                }
                
                                this.element.style.width = newWidth + 'px';
                                this.element.style.height = newHeight + 'px';
                                this.element.style.left = newLeft + 'px';
                                this.element.style.top = newTop + 'px';
                                this.element.style.transform = 'none';
                
                                this.sendToIframe({
                                    command: 'resized',
                                    width: newWidth,
                                    height: newHeight
                                });
                            }
                
                            stopResize(e) {
                                if (!this.resizing) return;
                
                                this.resizing = false;
                                document.body.classList.remove('noselect');
                
                                this.dispatchEvent('resize', {
                                    width: this.element.offsetWidth,
                                    height: this.element.offsetHeight
                                });
                            }
                
                            toggleMaximize(e) {
                                if (this.maximized) {
                                    this.element.style.width = this.originalSize.width + 'px';
                                    this.element.style.height = this.originalSize.height + 'px';
                                    if (this.originalPosition) {
                                        this.element.style.left = this.originalPosition.left + 'px';
                                        this.element.style.top = this.originalPosition.top + 'px';
                                    } else {
                                        this.element.style.left = '50%';
                                        this.element.style.top = '50%';
                                        this.element.style.transform = 'translate(-50%, -50%)';
                                    }
                                    this.element.classList.remove('maximized');
                                    if (this.overlay && this.modal) {
                                        this.overlay.style.display = 'block';
                                    }
                                } else {
                                    let rect = this.element.getBoundingClientRect();
                                    this.originalPosition = { left: rect.left, top: rect.top };
                                    this.originalSize = { width: rect.width, height: rect.height };
                
                                    this.element.style.left = '0';
                                    this.element.style.top = '0';
                                    this.element.style.width = '100%';
                                    this.element.style.height = '100%';
                                    this.element.style.transform = 'none';
                                    this.element.classList.add('maximized');
                                }
                
                                this.maximized = !this.maximized;
                                this.dispatchEvent('maximize', { maximized: this.maximized });
                
                                this.sendToIframe({
                                    command: 'maximize',
                                    maximized: this.maximized
                                });
                            }
                
                            reload() {
                                if (this.iframe) {
                                    this.iframe.src = this.iframe.src;
                                    this.sendToIframe({ command: 'reloaded' });
                                }
                            }
                
                            setCaption(text) {
                                if (this.title) {
                                    this.title.textContent = text;
                                }
                                this.dispatchEvent('captionChange', { caption: text });
                                this.sendToIframe({ command: 'captionChanged', caption: text });
                            }
                
                            setUrl(url) {
                                if (this.iframe) {
                                    this.url = url;
                                    this.iframe.src = url;
                                }
                            }
                
                            setSize(width, height) {
                                if (this.element) {
                                    this.element.style.width = width + 'px';
                                    this.element.style.height = height + 'px';
                                }
                                this.width = width;
                                this.height = height;
                            }
                
                            show() {
                                if (this.closed) return;
                
                                _activeWindow = this;
                
                                if (this.element) {
                                    this.element.style.display = 'flex';
                                }
                
                                if (this.overlay && this.modal) {
                                    this.overlay.style.display = 'block';
                                }
                
                                if (this.element) {
                                    this.element.classList.add('animate');
                
                                    var self = this;
                                    requestAnimationFrame(function() {
                                        self.element.classList.remove('animate');
                                        if (self.options.onshow && typeof self.options.onshow === 'function') {
                                            self.options.onshow.call(self.D3Api, self);
                                        }
                                        self.dispatchEvent('show');
                                    });
                                }
                
                                this.sendToIframe({ command: 'show' });
                            }
                
                            hide() {
                                if (this.element) {
                                    this.element.style.display = 'none';
                                }
                                if (this.overlay) {
                                    this.overlay.style.display = 'none';
                                }
                                this.dispatchEvent('hide');
                                this.sendToIframe({ command: 'hide' });
                            }
                
                            close(result) {
                                if (this.closed) return;
                
                                this.closed = true;
                
                                this.dispatchEvent('beforeClose', result);
                                
                                // Вызываем onclose callback если он есть
                                if (this.options.onclose && typeof this.options.onclose === 'function') {
                                    this.options.onclose(result);
                                }
                                
                                // Также проверяем сохраненные колбэки для iframe режимов
                                if (this.navigationId) {
                                    var callbacks = window.__pageCallbacks[this.navigationId];
                                    if (callbacks && callbacks.onclose && typeof callbacks.onclose === 'function') {
                                        callbacks.onclose(result);
                                    }
                                }
                
                                this.sendToIframe({ command: 'close', result: result });
                
                                // Останавливаем наблюдатель за размерами, если он есть
                                if (this.resizeObserver) {
                                    this.resizeObserver.disconnect();
                                    this.resizeObserver = null;
                                }
                
                                // Для полноэкранного IFrame или IFrame в элементе
                                if (this.isFullPageIframe && this.options.iframeContainer) {
                                    if (this.options.iframeContainer.parentNode) {
                                        this.options.iframeContainer.parentNode.removeChild(this.options.iframeContainer);
                                    }
                                    document.body.style.overflow = '';
                                } else if (this.isIframeInElement && this.options.domParent && this.iframe) {
                                    // Очищаем родительский элемент и сбрасываем флаги
                                    this.options.domParent.innerHTML = '';
                                    // Сбрасываем флаг инициализации родительского элемента
                                    if (this.options.domParent._d3IframeInitialized) {
                                        this.options.domParent._d3IframeInitialized = false;
                                    }
                                } else {
                                    // Для обычного модального окна
                                    if (this.element) {
                                        removeElement(this.element);
                                    }
                                    if (this.overlay) {
                                        removeElement(this.overlay);
                                    }
                                }
                
                                delete window.__d3Windows[this.windowId];
                
                                this.dispatchEvent('close', result);
                
                                if (_activeWindow === this) {
                                    _activeWindow = null;
                                }
                            }
                
                            center() {
                                if (this.element) {
                                    let size = getDocumentSize();
                                    let winSize = getElementSize(this.element);
                                    setPosition(
                                        this.element,
                                        (size.width - winSize.width) / 2,
                                        (size.height - winSize.height) / 2
                                    );
                                }
                            }
                
                            addListener(event, callback) {
                                if (!this.listeners[event]) {
                                    this.listeners[event] = [];
                                }
                                this.listeners[event].push(callback);
                            }
                
                            removeListener(event, callback) {
                                if (this.listeners[event]) {
                                    this.listeners[event] = this.listeners[event].filter(cb => cb !== callback);
                                }
                            }
                
                            dispatchEvent(event, data) {
                                if (this.listeners[event]) {
                                    this.listeners[event].forEach(callback => {
                                        try {
                                            callback(data, this);
                                        } catch (e) {
                                            console.error('Error in window event handler:', e);
                                        }
                                    });
                                }
                            }
                        }
                
                        /**
                         * Основная функция открытия формы
                         * @param {string} name - URL или имя формы
                         * @param {boolean|object} modeOrDomParent - true(модальное), false/null/undefined(полноэкранный iframe) или DOM элемент
                         * @param {object} data - параметры окна
                         */
                        window.openD3Form = function(name, modeOrDomParent, data) {
                            data = data || {};
                            
                            // Определяем тип вызова на основе второго аргумента
                            let modal = false;
                            let domParent = null;
                            
                            if (modeOrDomParent === true) {
                                modal = true;
                            } else if (modeOrDomParent === false || modeOrDomParent === null || modeOrDomParent === undefined) {
                                modal = false;
                                domParent = null;
                            } else if (typeof modeOrDomParent === 'object' && modeOrDomParent.nodeType === 1) {
                                // Это DOM элемент
                                modal = false;
                                domParent = modeOrDomParent;
                            }
                            
                            data.modal = modal;
                
                            let url = name;
                            if (name.indexOf('.') === -1) {
                                url = name + '.html';
                            }
                            
                            if (modal === true) {
                                // Модальное окно
                                let win = createDWindow({
                                    modal: true,
                                    width: data.width || 500,
                                    height: data.height || 400,
                                    caption: data.caption || 'Окно',
                                    theme: data.theme || 'modern',
                                    url: url,
                                    onshow: data.onshow,
                                    oncreate: data.oncreate,
                                    onclose: data.onclose
                                });
                
                                if (!win.element || !win.iframe) {
                                    console.error('Failed to create window properly');
                                    return null;
                                }
                
                                win.iframe.addEventListener('load', function() {
                                    initializeIframeContent(win, win.iframe, url, data, null, null);
                                });
                
                                win.iframe.addEventListener('error', function() {
                                    console.error('Failed to load iframe content:', url);
                                    let contentDiv = win.element.querySelector('.window-content');
                                    if (contentDiv) {
                                        contentDiv.innerHTML = 
                                            '<div style="color: red; padding: 20px; text-align: center;">' +
                                            '<h3>Ошибка загрузки</h3>' +
                                            '<p>Не удалось загрузить: ' + url + '</p>' +
                                            '<button onclick="this.closest(\\'.window\\').__win.close()">Закрыть</button>' +
                                            '</div>';
                                    }
                                    win.show();
                                });
                
                                win.addListener('message', (msg) => {
                                    if (msg.command === 'ready') {
                                    } else if (msg.command === 'close') {
                                        win.close(msg.result);
                                    } else if (msg.command === 'resize') {
                                        if (msg.width && msg.height) {
                                            win.setSize(msg.width, msg.height);
                                        }
                                    } else if (msg.command === 'setCaption') {
                                        win.setCaption(msg.caption);
                                    } else if (msg.command === 'scriptLoaded') {
                                    } else if (msg.command === 'scriptError') {
                                    }
                                });
                
                                win.element.__win = win;
                
                                return win.D3Api;
                            } else {
                                // Используем механизм с IFrame (либо в элементе, либо на всю страницу)
                                return navigateToPage(url, data, domParent);
                            }
                        };
                
                        // Расширяем глобальный D3Api методами для работы с окнами
                        window.D3Api.openD3Form = function(name, modeOrDomParent, data) {
                            return window.openD3Form(name, modeOrDomParent, data);
                        };
                
                        window.D3Api.getPage = function() {
                            return _activeWindow ? _activeWindow.D3Api : null;
                        };
                
                        window.D3Api.close = function(result) {
                            window.close(result);
                        };
                        
                        // Восстанавливаем колбэки при загрузке страницы
                        if (document.readyState === 'loading') {
                            document.addEventListener('DOMContentLoaded', restorePageCallbacks);
                        } else {
                            restorePageCallbacks();
                        }
                    }
                
                    waitForD3Api(initialize);
                })();
                """);

        return js.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}