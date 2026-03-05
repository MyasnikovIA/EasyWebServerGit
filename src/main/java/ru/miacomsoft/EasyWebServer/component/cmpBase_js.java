package ru.miacomsoft.EasyWebServer.component;

import ru.miacomsoft.EasyWebServer.HttpExchange;

/**
 * JavaScript библиотека для базовых классов D3Api
 * Содержит BaseCtrl и ControlBaseProperties
 * Подключается автоматически вместе с main_js
 */
public class cmpBase_js {

    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/javascript";

        StringBuilder js = new StringBuilder();
        js.append("""
                (function() {
                    if (window.cmpBaseInitialized) return;
                    
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
                        if (window.cmpBaseInitialized) return;
                        window.cmpBaseInitialized = true;
                        // ============== Базовый класс для событий ==============
                        
                        /**
                         * Базовый объект D3Base
                         * @class
                         * @param thisObj {Object}
                         * @constructor
                         */
                        if (!D3Api.D3Base) {
                            D3Api.D3Base = function (thisObj) {
                                this.events = {};
                                this.currentEventName = '';
                                this.removedEvents = [];

                                var eventResolves = {};

                                /**
                                 * Проверить инициализирована ли событие
                                 * @param eventName
                                 * @returns {Promise<unknown>}
                                 */
                                this.isInitEvent = function (eventName) {
                                    var that = this;
                                    return new Promise(function (_resolve, _reject) {
                                        if (eventName in that.events) {
                                            _resolve();
                                        } else {
                                            if (!(eventName in eventResolves)) {
                                                eventResolves[eventName] = [];
                                            }
                                            eventResolves[eventName].push(_resolve);
                                        }
                                    });
                                }

                                /**
                                 * Добавить событие
                                 * @param eventName {string} - Имя события
                                 * @param listener {Function} - колбэк вызова.
                                 * @returns {string}
                                 */
                                this.addEvent = function (eventName, listener) {
                                    if (!(listener instanceof Function))
                                        return null;
                                    if (!this.events[eventName])
                                        this.events[eventName] = {};
                                    var uniqid = D3Api.getUniqId('event_');
                                    this.events[eventName][uniqid] = {func: listener, once: false};
                                    if (eventName in eventResolves) {
                                        eventResolves[eventName].forEach(function (_resolve) {
                                            _resolve();
                                        });
                                        delete eventResolves[eventName];
                                    }
                                    return uniqid;
                                }
                                
                                /**
                                 * Добавить событие
                                 * @param eventName {string} - Имя события
                                 * @param listener {Function} - колбэк вызова.
                                 * @returns {string}
                                 */
                                this.addEventOnce = function (eventName, listener) {
                                    var uniqid = this.addEvent(eventName, listener);
                                    if (!uniqid)
                                        return null;
                                    this.events[eventName][uniqid].once = true;
                                    return uniqid;
                                }
                                
                                /**
                                 * Вызвать событие
                                 * @param eventName {string} - Имя события
                                 * @returns {boolean}
                                 */
                                this.callEvent = function (eventName) {
                                    if (!this.events[eventName])
                                        return;
                                    this.currentEventName = eventName;
                                    var args = Array.prototype.slice.call(arguments, 1);
                                    var onces = new Array();
                                    var res = true;
                                    for (var e in this.events[eventName]) {
                                        if (!('events' in this)) {
                                            continue;
                                        }
                                        if (!this.events[eventName].hasOwnProperty(e)) {
                                            continue;
                                        }
                                        if (this.events[eventName][e].func.apply(thisObj || this, args) === false)
                                            res = false;
                                        if (this.events && this.events[eventName][e] && this.events[eventName][e].once)
                                            onces.push(e);
                                    }
                                    this.currentEventName = '';
                                    onces = onces.concat(this.removedEvents);
                                    for (var i = 0, c = onces.length; i < c; i++) {
                                        this.removeEvent(eventName, onces[i]);
                                    }
                                    this.removedEvents = [];
                                    onces = null;
                                    return res;
                                }
                                
                                /**
                                 * Очистить Событие по имени
                                 * @param eventName {string} - Имя события
                                 */
                                this.clearEvents = function (eventName) {
                                    this.events[eventName] = {};
                                }
                                
                                /**
                                 * Удалить событие по uid
                                 * @param eventName {string} - Имя события
                                 * @uniqid uniqid {string} - уникальный идентификатор события
                                 * @returns {boolean}
                                 */
                                this.removeEvent = function (eventName, uniqid) {
                                    if (!this.events[eventName] || !this.events[eventName][uniqid])
                                        return false;
                                    if (eventName == this.currentEventName) {
                                        this.removedEvents.push(uniqid);
                                        return true;
                                    }
                                    this.events[eventName][uniqid] = null;
                                    delete this.events[eventName][uniqid];
                                    return true;
                                }
                                
                                /**
                                 * Удалить событие
                                 * @param eventName {string} - Имя события
                                 * @returns {boolean}
                                 */
                                this.removeEvents = function (eventName) {
                                    if (!this.events[eventName])
                                        return false;

                                    if (eventName == this.currentEventName) {
                                        for (var uid in this.events[eventName]) {
                                            if (this.events[eventName].hasOwnProperty(uid)) {
                                                this.removedEvents.push(uid);
                                            }
                                        }
                                        return true;
                                    }
                                    this.events[eventName] = null;
                                    delete this.events[eventName];
                                    return true;
                                }
                                
                                /**
                                 * Удалить все событие
                                 * @returns {boolean}
                                 */
                                this.removeAllEvents = function () {
                                    this.events = [];
                                    return true;
                                }
                                
                                /**
                                 * Деструктор
                                 */
                                this.destructorBase = function () {
                                    this.destroyed = true;
                                    this.events = null;
                                    delete this.events;
                                }
                            }
                        }
                        
                        // ============== BaseCtrl ==============
                        
                        /**
                         *
                         * @component
                         */
                        if (!D3Api.BaseCtrl) {
                            D3Api.BaseCtrl = new function()
                            {
                                var baseMethods = {};
                                var self = this;
                                
                                /**
                                 *
                                 * @param _dom
                                 * @returns {boolean}
                                 */
                                this.init = function(_dom)
                                {
                                    this.init_focus(_dom);
                                    return true;
                                }

                                /**
                                 *
                                 * @param _dom
                                 */
                                this.init_focus = function(_dom)
                                {
                                    D3Api.addEvent(_dom,'focus',this.ctrl_focus, true);
                                    D3Api.addEvent(_dom,'blur',this.ctrl_blur, true);
                                }

                                /**
                                 *
                                 * @param e
                                 */
                                this.ctrl_focus = function(e)
                                {
                                    var inp = D3Api.getEventTarget(e);
                                    var focus_control = D3Api.getControlByDom(inp);

                                    if (focus_control) {
                                        if (D3Api.getProperty(focus_control,'name') == "lastControl" && !D3Api.getVar('KeyDown_shiftKey')) {
                                            D3Api.stopEvent(e);
                                            D3Api.BaseCtrl.focusNextElement(focus_control, 2);
                                            D3Api.setVar('KeyDown_shiftKey', null);
                                            return;
                                        } else if (D3Api.getProperty(focus_control,'name') == "firstControl") {
                                            D3Api.stopEvent(e);
                                            if (D3Api.getVar('KeyDown_shiftKey') === true) {
                                                D3Api.BaseCtrl.focusNextElement(focus_control, -2);
                                            } else {
                                                D3Api.BaseCtrl.focusNextElement(focus_control, 1);
                                            }
                                            D3Api.setVar('KeyDown_shiftKey', null);
                                            return;
                                        }

                                        D3Api.setVar('focus_control', focus_control);
                                        D3Api.addClass(focus_control, 'focus');
                                    }
                                }

                                /**
                                 *
                                 * @param e
                                 */
                                this.ctrl_blur = function(e)
                                {
                                    var focus_control = D3Api.getVar('focus_control');
                                    if (!focus_control) return;

                                    D3Api.removeClass(focus_control, 'focus');
                                    D3Api.setVar('focus_control', null);
                                }

                                /**
                                 *
                                 * @param dom
                                 * @param delta
                                 */
                                this.focusNextElement = function(dom, delta) {
                                    var focussableElements = 'input:not([disabled]), [tabindex]:not([disabled]):not([tabindex="-1"])';
                                    if (!dom) return;

                                    var focussable = Array.prototype.filter.call(dom.D3Form.DOM.querySelectorAll(focussableElements),
                                        function(element) {
                                            return element.offsetWidth > 0 || element.offsetHeight > 0
                                        });

                                    var index = focussable.indexOf(dom);

                                    if ((index + delta) < 0) {
                                        var inp = focussable[focussable.length + delta];
                                    } else if ((index + delta) > (focussable.length - 1)) {
                                        var inp = focussable[index + delta - focussable.length];
                                    } else {
                                        var inp = focussable[index + delta];
                                    }

                                    var focus_control = D3Api.getControlByDom(inp);

                                    if (inp == focus_control) {
                                        focus_control.focus();
                                    } else {
                                        D3Api.setControlPropertyByDom(focus_control, 'focus', true);
                                    }
                                }
                                
                                /**
                                 *
                                 * @param _dom
                                 * @returns {boolean}
                                 */
                                this.startWait = function(_dom)
                                {
                                    return true;
                                }

                                /**
                                 *
                                 * @param _dom
                                 * @returns {boolean}
                                 */
                                this.stopWait = function(_dom)
                                {
                                    return true;
                                }

                                /**
                                 *
                                 * @param _dom
                                 * @param method
                                 * @returns {*}
                                 */
                                this.callMethod = function(_dom, method)
                                {
                                    var ct = D3Api.getProperty(_dom,'cmptype');
                                    if (!ct) {
                                        return;
                                    }
                                    var baseMethod = false;
                                    if (!D3Api.controlsApi[ct] || !D3Api.controlsApi[ct]._API_ || !D3Api.controlsApi[ct]._API_[method]) {
                                        if (!baseMethods[method] || !D3Api.BaseCtrl[method]) {    
                                            return;
                                        }
                                        baseMethod = true;
                                    }
                                    var args = Array.prototype.slice.call(arguments);
                                    args.splice(1,1);
                                    return (baseMethod) ? D3Api.BaseCtrl[method].apply(this, args) : D3Api.controlsApi[ct]._API_[method].apply(this, args);
                                }

                                /**
                                 *
                                 * @param domSrc
                                 * @param eventName
                                 * @param argsName
                                 * @param defaultEventFunc
                                 * @param domDest
                                 */
                                this.initEvent = function Base_InitEvent(domSrc, eventName, argsName, defaultEventFunc, domDest)
                                {
                                    domDest = domDest || domSrc;
                                    var ev = D3Api.getProperty(domSrc, eventName, defaultEventFunc);
                                    if (ev) {
                                        domDest.D3Base.addEvent(eventName, domDest.D3Form.execDomEventFunc(domDest, (argsName) ? {func: ev, args: argsName} : ev, undefined, domDest.D3Form.currentContext));
                                    }
                                }

                                /**
                                 *
                                 * @param _dom
                                 * @returns {*}
                                 */
                                this.getName = function Base_getName(_dom)
                                {
                                    return D3Api.getProperty(_dom,'name',null);
                                }

                                /**
                                 *
                                 * @param _dom
                                 * @param _value
                                 */
                                this.setName = function Base_setName(_dom, _value)
                                {
                                    D3Api.setProperty(_dom,'name',_value);
                                }

                                /**
                                 *
                                 * @param _dom
                                 * @returns {*}
                                 */
                                this.getWidth = function Base_getWidth(_dom)
                                {
                                    return _dom.style.width;
                                }

                                /**
                                 *
                                 * @param _dom
                                 * @param _value
                                 */
                                this.setWidth = function Base_setWidth(_dom, _value)
                                {
                                    var v = +_value;
                                    if (isNaN(v))
                                        _dom.style.width = _value;
                                    else
                                        _dom.style.width = _value + 'px';
                                }

                                /**
                                 *
                                 * @param _dom
                                 * @returns {*}
                                 */
                                this.getHeight = function Base_getHeight(_dom)
                                {
                                    return _dom.style.height;
                                }

                                /**
                                 *
                                 * @param _dom
                                 * @param _value
                                 */
                                this.setHeight = function Base_setHeight(_dom, _value)
                                {
                                    var v = +_value;
                                    if (isNaN(v))
                                        _dom.style.height = _value;
                                    else
                                        _dom.style.height = _value + 'px';
                                }

                                /**
                                 *
                                 * @param _dom
                                 * @returns {number}
                                 */
                                this.getRealWidth = function Base_getRealWidth(_dom)
                                {
                                    return _dom.offsetWidth;
                                }

                                /**
                                 *
                                 * @param _dom
                                 * @returns {number}
                                 */
                                this.getRealHeight = function Base_getRealHeight(_dom)
                                {
                                    return _dom.offsetHeight;
                                }

                                /**
                                 *
                                 * @param _dom
                                 * @returns {boolean}
                                 */
                                this.getEnabled = function Base_getEnabled(_dom)
                                {
                                    return !D3Api.hasClass(_dom, 'ctrl_disable');
                                }

                                /**
                                 *
                                 * @param _dom
                                 * @param _value
                                 */
                                this.setEnabled = function Base_setEnabled(_dom, _value)
                                {
                                    if (D3Api.getBoolean(_value))
                                        D3Api.removeClass(_dom, 'ctrl_disable');
                                    else
                                        D3Api.addClass(_dom, 'ctrl_disable');
                                }

                                /**
                                 *
                                 * @param _dom
                                 * @returns {boolean}
                                 */
                                this.getVisible = function Base_getVisible(_dom)
                                {
                                    return !D3Api.hasClass(_dom, 'ctrl_hidden');
                                }

                                /**
                                 *
                                 * @param _dom
                                 * @param _value
                                 */
                                this.setVisible = function Base_setVisible(_dom, _value)
                                {
                                    if (D3Api.getBoolean(_value))
                                        D3Api.removeClass(_dom, 'ctrl_hidden');
                                    else
                                        D3Api.addClass(_dom, 'ctrl_hidden');
                                    
                                    var form = _dom.D3Form;
                                    if (!_dom.D3Form) {
                                        form = D3Api.getControlByDom(_dom).D3Form;
                                    }
                                    form.resize();
                                }

                                /**
                                 *
                                 * @param _dom
                                 * @returns {*}
                                 */
                                this.getHint = function Base_getHint(_dom)
                                {
                                    return _dom.title;
                                }

                                /**
                                 *
                                 * @param _dom
                                 * @param _value
                                 * @returns {*}
                                 */
                                this.setHint = function Base_setHint(_dom, _value)
                                {
                                    return _dom.title = _value;
                                }

                                /**
                                 *
                                 * @param _dom
                                 * @param _value
                                 */
                                this.setFocus = function Base_setFocus(_dom, _value)
                                {
                                    var inpEl = D3Api.getChildTag(_dom, 'input', 0);
                                    if (!inpEl)
                                        inpEl = D3Api.getChildTag(_dom, 'textarea', 0);
                                    if (!inpEl) {
                                        _dom.focus();
                                        return;
                                    }
                                    
                                    if (D3Api.getBoolean(_value))
                                        setTimeout(function(){inpEl.focus();},10);
                                    else
                                        setTimeout(function(){inpEl.blur();},10);
                                }

                                /**
                                 *
                                 * @param _dom
                                 * @param _value
                                 * @returns {*|boolean}
                                 */
                                this.getWarning = function(_dom, _value)
                                {
                                    return D3Api.hasClass(_dom, 'ctrl_warning');
                                }

                                /**
                                 *
                                 * @param _dom
                                 * @param _value
                                 */
                                this.setWarning = function(_dom, _value)
                                {
                                    if (D3Api.getBoolean(_value))
                                        D3Api.addClass(_dom, 'ctrl_warning');
                                    else
                                        D3Api.removeClass(_dom, 'ctrl_warning');
                                }    
                                
                                /**
                                 *
                                 * @param _dom
                                 * @param _value
                                 * @returns {*|boolean}
                                 */
                                this.getError = function(_dom, _value)
                                {
                                    return D3Api.hasClass(_dom, 'ctrl_error');
                                }

                                /**
                                 *
                                 * @param _dom
                                 * @param _value
                                 */
                                this.setError = function(_dom, _value)
                                {
                                    if (D3Api.getBoolean(_value))
                                        D3Api.addClass(_dom, 'ctrl_error');
                                    else
                                        D3Api.removeClass(_dom, 'ctrl_error');
                                }   

                                /**
                                 *
                                 * @param dom
                                 * @returns {*}
                                 */
                                this.getValue = function(dom)
                                {
                                    if (D3Api.getProperty(dom, 'cmptype') != 'Base') return;
                                    var inp = D3Api.getChildTag(dom, 'input', 0) || D3Api.getChildTag(dom, 'textarea', 0);
                                    if (inp)
                                        return (inp.value == null) ? '' : inp.value;
                                    else
                                        return D3Api.getProperty(dom, 'keyvalue', '');
                                }

                                /***
                                 *
                                 * @param dom
                                 * @param value
                                 */
                                this.setValue = function(dom, value)
                                {
                                    if (D3Api.getProperty(dom, 'cmptype') != 'Base') return;
                                    var inp = D3Api.getChildTag(dom, 'input', 0) || D3Api.getChildTag(dom, 'textarea', 0);
                                    if (inp)
                                        inp.value = value;
                                    else
                                        D3Api.setProperty(dom, 'keyvalue', value);
                                }

                                /**
                                 *
                                 * @param dom
                                 * @returns {*}
                                 */
                                this.getInput = function(dom)
                                {
                                    if (D3Api.getProperty(dom, 'cmptype') != 'Base') return;
                                    var inp = D3Api.getChildTag(dom, 'input', 0) || D3Api.getChildTag(dom, 'textarea', 0);
                                    return inp;
                                }

                                /**
                                 *
                                 * @param dom
                                 * @returns {*}
                                 */
                                this.getCaption = function(dom)
                                {
                                    if (D3Api.getProperty(dom, 'cmptype') != 'Base') return;
                                    var inp = D3Api.getChildTag(dom, 'input', 0) || D3Api.getChildTag(dom, 'textarea', 0);
                                    if (inp)
                                        return (inp.value == null) ? '' : inp.value;
                                    else
                                        return D3Api.getTextContent(dom);
                                }

                                /**
                                 *
                                 * @param dom
                                 * @param value
                                 */
                                this.setCaption = function(dom, value)
                                {
                                    if (D3Api.getProperty(dom, 'cmptype') != 'Base') return;
                                    var inp = D3Api.getChildTag(dom, 'input', 0) || D3Api.getChildTag(dom, 'textarea', 0);
                                    if (inp)
                                        inp.value = value;
                                    else
                                        dom.innerHTML = D3Api.htmlSpecialChars(value);
                                }

                                /**
                                 *
                                 * @param dom
                                 * @returns {*}
                                 */
                                this.getHtml = function(dom)
                                {
                                    return dom.innerHTML;
                                }

                                /**
                                 *
                                 * @param dom
                                 * @param value
                                 */
                                this.setHtml = function(dom, value)
                                {
                                    dom.innerHTML = value;
                                }
                            }
                        }
                        
                        // ============== ControlBaseProperties ==============
                        
                        /**
                         * Свойство
                         * get - функция получения значения свойства, если null то берется атрибут
                         * set - функция установки значения свойства, если null то устанавливается атрибут
                         * type - тип свойства: property, event
                         * value_type - тип значения свойства: string(поумолчанию),number,boolean,list
                         * value_list - массив значений, если value_type: list
                         * value_default - значение по умолчанию
                         */
                        if (!D3Api.ControlBaseProperties) {
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
                            }
                        }
                        
                        // ============== Расширение D3Api ==============
                        
                        // Добавляем ссылку на Base в D3Api
                        if (!D3Api.Base) {
                            D3Api.Base = {};
                            D3Api.D3Base.call(D3Api.Base);
                        }
                        
                        // Добавляем controlsApi если его нет
                        if (!D3Api.controlsApi) {
                            D3Api.controlsApi = {};
                        }
                        
                        // Регистрируем Base контрол
                        if (!D3Api.controlsApi['Base']) {
                            D3Api.controlsApi['Base'] = new D3Api.ControlBaseProperties();
                        }
                    }
                    
                    waitForD3Api(initialize);
                })();
                """);

        return js.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}