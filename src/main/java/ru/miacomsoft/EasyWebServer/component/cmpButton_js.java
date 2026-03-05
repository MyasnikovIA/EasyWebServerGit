package ru.miacomsoft.EasyWebServer.component;

import ru.miacomsoft.EasyWebServer.HttpExchange;

/**
 * JavaScript библиотека для компонента cmpButton
 * Расширяет функционал D3Api для работы с кнопками
 * Подключается автоматически при наличии cmpButton на странице
 */
public class cmpButton_js {

    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/javascript";

        StringBuilder js = new StringBuilder();
        js.append("""
                (function() {
                    if (window.cmpButtonInitialized) return;
                    
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
                        if (window.cmpButtonInitialized) return;
                        window.cmpButtonInitialized = true;
                        
                        console.log('cmpButton: JavaScript library initialized');
                        
                        // Проверяем наличие ControlBaseProperties и определяем его если нет
                        if (typeof D3Api.ControlBaseProperties !== 'function') {
                            console.log('cmpButton: Defining D3Api.ControlBaseProperties');
                            D3Api.ControlBaseProperties = function(controlAPI) {
                                this._API_ = controlAPI || D3Api.BaseCtrl;
                                this.name = {get: D3Api.BaseCtrl.getName, set: D3Api.BaseCtrl.setName, type: 'string'};
                                this.value = {get: D3Api.BaseCtrl.getValue, set: D3Api.BaseCtrl.setValue, type: 'string'};
                                this.caption = {get: D3Api.BaseCtrl.getCaption, set: D3Api.BaseCtrl.setCaption, type: 'string'};
                                this.width = {get: D3Api.BaseCtrl.getWidth, set: D3Api.BaseCtrl.setWidth, type: 'string'};
                                this.height = {get: D3Api.BaseCtrl.getHeight, set: D3Api.BaseCtrl.setHeight, type: 'string'};
                                this.real_width = {get: D3Api.BaseCtrl.getRealWidth, type: 'number'};
                                this.real_height = {get: D3Api.BaseCtrl.getRealHeight, type: 'number'};
                                this.enabled = {get: D3Api.BaseCtrl.getEnabled, set: D3Api.BaseCtrl.setEnabled, type: 'boolean'};
                                this.visible = {get: D3Api.BaseCtrl.getVisible, set: D3Api.BaseCtrl.setVisible, type: 'boolean'};
                                this.hint = {get: D3Api.BaseCtrl.getHint, set: D3Api.BaseCtrl.setHint, type: 'string'};
                                this.focus = {set: D3Api.BaseCtrl.setFocus, type: 'boolean'};
                                this.warning = {set: D3Api.BaseCtrl.setWarning, get: D3Api.BaseCtrl.getWarning, type: 'boolean'};
                                this.error = {set: D3Api.BaseCtrl.setError, get: D3Api.BaseCtrl.getError, type: 'boolean'};
                                this.html = {get: D3Api.BaseCtrl.getHtml, set: D3Api.BaseCtrl.setHtml, type: 'string'};
                                this.input = {get: D3Api.BaseCtrl.getInput, type: 'dom'};
                            };
                        }
                        
                        // Хранилище для обработчиков событий
                        var buttonClickHandlers = {};
                        
                        /**
                         * Инициализация всех кнопок на странице
                         */
                        function initButtons() {
                            var buttons = document.querySelectorAll('[cmptype="Button"]');
                            for (var i = 0; i < buttons.length; i++) {
                                var button = buttons[i];
                                var name = button.getAttribute('name');
                                var popupmenu = button.getAttribute('data-popupmenu');
                                if (name) {
                                    // Добавляем обработчик клика если его нет
                                    if (!button.hasAttribute('data-handler-initialized')) {
                                        button.addEventListener('click', function(e) {
                                            var btn = e.currentTarget;
                                            var name = btn.getAttribute('name');
                                            var actionName = btn.getAttribute('action_name');
                                            // Генерируем событие клика
                                            var event = new CustomEvent('buttonClick', {
                                                detail: { 
                                                    name: name,
                                                    actionName: actionName,
                                                    button: btn
                                                },
                                                bubbles: true
                                            });
                                            btn.dispatchEvent(event);
                                            
                                            // Вызываем пользовательский обработчик если есть
                                            if (buttonClickHandlers[name]) {
                                                buttonClickHandlers[name](btn);
                                            }
                                        });
                                        
                                        // Добавляем обработчик наведения
                                        button.addEventListener('mouseenter', function(e) {
                                            var btn = e.currentTarget;
                                            var name = btn.getAttribute('name');
                                            
                                            var event = new CustomEvent('buttonHover', {
                                                detail: { name: name },
                                                bubbles: true
                                            });
                                            btn.dispatchEvent(event);
                                        });
                                        
                                        // Добавляем обработчик ухода мыши
                                        button.addEventListener('mouseleave', function(e) {
                                            var btn = e.currentTarget;
                                            var name = btn.getAttribute('name');
                                            
                                            var event = new CustomEvent('buttonLeave', {
                                                detail: { name: name },
                                                bubbles: true
                                            });
                                            btn.dispatchEvent(event);
                                        });
                                        
                                        // Добавляем обработчик фокуса
                                        button.addEventListener('focus', function(e) {
                                            var btn = e.currentTarget;
                                            var name = btn.getAttribute('name');
                                            
                                            var event = new CustomEvent('buttonFocus', {
                                                detail: { name: name },
                                                bubbles: true
                                            });
                                            btn.dispatchEvent(event);
                                        });
                                        
                                        // Добавляем обработчик потери фокуса
                                        button.addEventListener('blur', function(e) {
                                            var btn = e.currentTarget;
                                            var name = btn.getAttribute('name');
                                            
                                            var event = new CustomEvent('buttonBlur', {
                                                detail: { name: name },
                                                bubbles: true
                                            });
                                            btn.dispatchEvent(event);
                                        });
                                        
                                        // Обработка клавиш Enter и Space
                                        button.addEventListener('keydown', function(e) {
                                            if (e.keyCode === 13 || e.keyCode === 32) {
                                                e.preventDefault();
                                                this.click();
                                            }
                                        });
                                        
                                        button.setAttribute('data-handler-initialized', 'true');
                                    }
                                }
                            }
                        }
                        
                        /**
                         * Показ popup меню
                         * @param {Event} e - Событие
                         * @param {HTMLElement} dom - DOM элемент кнопки
                         * @param {string} menuName - Имя меню
                         */
                        function showPopupMenu(e, dom, menuName) {
                            e.preventDefault();
                            e.stopPropagation();
                            
                            var ctrl = getControlByDom(dom);
                            if (!ctrl || !ctrl.D3Form) return;
                            
                            var menu = ctrl.D3Form.getControl(menuName);
                            if (menu && typeof D3Api.PopupMenuCtrl !== 'undefined') {
                                var coords = {
                                    left: ctrl.getBoundingClientRect().left + 6,
                                    top: ctrl.getBoundingClientRect().bottom + 6
                                };
                                menu.D3Store.popupObject = ctrl || menu.D3Store.popupObject;
                                D3Api.PopupMenuCtrl.show(menu, coords);
                            }
                        }
                        
                        /**
                         * Получение контрола по DOM элементу
                         * @param {HTMLElement} dom - DOM элемент
                         * @returns {Object} - Контрол
                         */
                        function getControlByDom(dom) {
                            while (dom) {
                                if (dom.D3Api) return dom.D3Api;
                                dom = dom.parentNode;
                            }
                            return null;
                        }
                        
                        /**
                         * Установка подписи кнопки
                         * @param {HTMLElement} dom - DOM элемент кнопки
                         * @param {string} value - Новый текст
                         * @returns {boolean}
                         */
                        function setCaption(dom, value) {
                            var c = dom.querySelector('.btn_caption');
                            if (c) {
                                c.innerHTML = value;
                                
                                // Генерируем событие изменения
                                var event = new CustomEvent('captionChanged', {
                                    detail: { name: dom.getAttribute('name'), newText: value },
                                    bubbles: true
                                });
                                dom.dispatchEvent(event);
                                
                                return true;
                            }
                            return false;
                        }
                        
                        /**
                         * Получение подписи кнопки
                         * @param {HTMLElement} dom - DOM элемент кнопки
                         * @returns {string}
                         */
                        function getCaption(dom) {
                            var c = dom.querySelector('.btn_caption');
                            return c ? c.innerHTML : '';
                        }
                        
                        /**
                         * Установка состояния disabled
                         * @param {HTMLElement} dom - DOM элемент кнопки
                         * @param {boolean} bool - true - disabled, false - enabled
                         * @returns {boolean}
                         */
                        function setDisabled(dom, bool) {
                            if (!dom) return false;
                            
                            bool = (bool === true);
                            
                            if (bool) {
                                dom.setAttribute('disabled', 'disabled');
                                dom.classList.add('ctrl_disable');
                            } else {
                                dom.removeAttribute('disabled');
                                dom.classList.remove('ctrl_disable');
                            }
                            
                            return true;
                        }
                        
                        /**
                         * Получение состояния disabled
                         * @param {HTMLElement} dom - DOM элемент кнопки
                         * @returns {boolean}
                         */
                        function isDisabled(dom) {
                            return dom.hasAttribute('disabled') || dom.classList.contains('ctrl_disable');
                        }
                        
                        /**
                         * Установка enabled состояния
                         * @param {HTMLElement} dom - DOM элемент кнопки
                         * @param {boolean} bool - true - enabled, false - disabled
                         * @returns {boolean}
                         */
                        function setEnabled(dom, bool) {
                            return setDisabled(dom, !bool);
                        }
                        
                        // Расширение D3Api
                        if (typeof D3Api !== 'undefined') {
                            
                            // Создаем объект ButtonCtrl
                            D3Api.ButtonCtrl = {
                                init: function(dom) {
                                    // Инициализация фокуса
                                    if (dom && !dom.hasAttribute('tabindex')) {
                                        dom.setAttribute('tabindex', '0');
                                    }
                                },
                                
                                setCaption: setCaption,
                                getCaption: getCaption,
                                setDisabled: setDisabled,
                                setEnabled: setEnabled,
                                isDisabled: isDisabled,
                                showPopupMenu: showPopupMenu,
                                
                                CtrlKeyDown: function(dom, e) {
                                    switch (e.keyCode) {
                                        case 32: // Space
                                        case 13: // Enter
                                            dom.click();
                                            D3Api.stopEvent(e);
                                            break;
                                    }
                                }
                            };
                            
                            // Регистрируем API для кнопок
                            D3Api.controlsApi = D3Api.controlsApi || {};
                            D3Api.controlsApi['Button'] = new D3Api.ControlBaseProperties(D3Api.ButtonCtrl);
                            D3Api.controlsApi['Button']['caption'] = {
                                get: D3Api.ButtonCtrl.getCaption,
                                set: D3Api.ButtonCtrl.setCaption
                            };
                            
                            /**
                             * Установка обработчика клика на кнопку
                             * @param {string} name - Имя кнопки
                             * @param {Function} handler - Функция обработчик
                             */
                            D3Api.onClick = function(name, handler) {
                                if (typeof handler !== 'function') return;
                                
                                var button = document.querySelector('[name="' + name + '"]');
                                if (button) {
                                    buttonClickHandlers[name] = handler;
                                    console.log('cmpButton: Click handler set for', name);
                                }
                            };
                            
                            /**
                             * Удаление обработчика клика с кнопки
                             * @param {string} name - Имя кнопки
                             */
                            D3Api.offClick = function(name) {
                                if (buttonClickHandlers[name]) {
                                    delete buttonClickHandlers[name];
                                    console.log('cmpButton: Click handler removed for', name);
                                }
                            };
                            
                            /**
                             * Получение кнопки по имени
                             * @param {string} name - Имя кнопки
                             * @returns {HTMLElement}
                             */
                            D3Api.getButton = function(name) {
                                return document.querySelector('[name="' + name + '"]');
                            };
                            
                            // Алиас для обратной совместимости
                            D3Api.onButtonClick = D3Api.onClick;
                            
                            console.log('cmpButton: D3Api extended with button functionality');
                        }
                        
                        // Инициализация после загрузки DOM
                        if (document.readyState === 'loading') {
                            document.addEventListener('DOMContentLoaded', function() {
                                initButtons();
                            });
                        } else {
                            initButtons();
                        }
                    }
                    
                    waitForD3Api(initialize);
                })();
                """);

        return js.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}