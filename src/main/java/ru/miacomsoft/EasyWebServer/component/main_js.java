package ru.miacomsoft.EasyWebServer.component;

import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.ServerConstant;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Полная версия main_js библиотеки со всем функционалом
 * Добавлена поддержка последовательной загрузки расширений
 */
public class main_js {

    protected static final ConcurrentHashMap<String, byte[]> JS_CACHE = new ConcurrentHashMap<>();
    private static final Object LOCK = new Object();
    protected static String cachedHash = null;
    private static long lastModified = 0;

    // Добавляем флаг для отслеживания загрузки расширений
    private static boolean extensionsLoaded = false;

    private static final String JS_SOURCE_CODE = """
// Полифиллы для старых браузеров
if (!Element.prototype.matches) {
    Element.prototype.matches = Element.prototype.msMatchesSelector || Element.prototype.webkitMatchesSelector;
}

if (!Element.prototype.closest) {
    Element.prototype.closest = function(s) {
        var el = this;
        do {
            if (el.matches(s)) return el;
            el = el.parentElement || el.parentNode;
        } while (el !== null && el.nodeType === 1);
        return null;
    };
}

D3Api = new function () {
    // Внутренние хранилища данных
    var GLOBAL_VARS = {};
    var GLOBAL_SESSION = {};
    var GLOBAL_CTRL = {};

    // Хранилища для callback-функций отслеживания изменений
    var VAR_WATCHERS = {};      // для переменных (srctype="var")
    var VALUE_WATCHERS = {};    // для значений контролов (srctype="ctrl")
    var CAPTION_WATCHERS = {};  // для подписей (srctype="caption")
    var SESSION_WATCHERS = {};  // для сессионных переменных (srctype="session")

    this.Form = {}
    this.forms = {};
    this.GLOBAL_ACTION = {};
    this.GLOBAL_DATA_SET = {};
    this.platform = "windows";

    /**
     * @property {Function} Инициализация проекта
     * @returns void
     */
    this.init = function(body) {
        D3Api.MainDom = body || document.body;
        D3Api.D3MainContainer = D3Api.MainDom;
        if (!D3Api.D3MainContainer || D3Api.D3MainContainer.length == 0) {
            let tagArr = document.getElementsByTagName('html');
            if (tagArr.length > 0) {
                D3Api.D3MainContainer = tagArr[0];
            }
        }
        
        // Загружаем расширения после инициализации
        D3Api.loadExtensions();
    }

    /**
     * Загрузка расширений функционала D3Api
     */
    this.loadExtensions = function() {
        if (D3Api.extensionsLoaded) return;
        
        // Загружаем d3api.js (расширение с DOM-методами)
        var script = document.createElement('script');
        script.src = '/{component}/d3api_js';
        script.type = 'text/javascript';
        script.async = false;
        script.defer = false;
        
        script.onload = function() {
            D3Api.extensionsLoaded = true;
            
            // Генерируем событие о загрузке расширений
            var event = new CustomEvent('d3apiExtensionsLoaded', {
                detail: { loaded: true }
            });
            document.dispatchEvent(event);
        };
        
        script.onerror = function(error) {
            console.error('Ошибка загрузки расширений D3Api:', error);
        };
        
        document.head.appendChild(script);
    }

    // Флаг загрузки расширений
    this.extensionsLoaded = false;

    /**
     * Работа с переменными страницы (srctype="var")
     */
    this.setVar = function(name, value) {
        var oldValue = GLOBAL_VARS[name];
        var newValue = value;

        // Вызываем watchers перед изменением
        if (VAR_WATCHERS[name]) {
            for (var i = 0; i < VAR_WATCHERS[name].length; i++) {
                var watcher = VAR_WATCHERS[name][i];
                var result = watcher.callback(newValue, oldValue);
                // Если callback вернул значение (не undefined), используем его
                if (result !== undefined) {
                    newValue = result;
                }
            }
        }

        GLOBAL_VARS[name] = newValue;

        // Генерируем событие изменения
        var event = new CustomEvent('varChanged', {
            detail: { name: name, newValue: newValue, oldValue: oldValue },
            bubbles: true
        });
        document.dispatchEvent(event);
    }

    this.getVar = function(name, defValue) {
        return GLOBAL_VARS[name] !== undefined ? GLOBAL_VARS[name] : defValue;
    }

    /**
     * Добавление отслеживания изменения переменной
     * @param {string} name - Имя переменной
     * @param {function} callback - Функция обратного вызова (newValue, oldValue)
     * @returns {string} ID подписки для возможного удаления
     */
    this.onChangeVar = function(name, callback) {
        if (typeof callback !== 'function') return null;

        if (!VAR_WATCHERS[name]) {
            VAR_WATCHERS[name] = [];
        }

        var watcherId = 'var_' + name + '_' + Date.now() + '_' + Math.random();
        VAR_WATCHERS[name].push({
            id: watcherId,
            callback: callback
        });

        return watcherId;
    }

    /**
     * Удаление отслеживания изменения переменной
     * @param {string|function} nameOrId - Имя переменной или ID подписки
     * @param {function} [callback] - Опционально, конкретный callback для удаления
     */
    this.offChangeVar = function(nameOrId, callback) {
        // Если передан ID подписки (строка, начинающаяся с 'var_')
        if (typeof nameOrId === 'string' && nameOrId.indexOf('var_') === 0) {
            for (var name in VAR_WATCHERS) {
                VAR_WATCHERS[name] = VAR_WATCHERS[name].filter(function(w) {
                    return w.id !== nameOrId;
                });
                if (VAR_WATCHERS[name].length === 0) {
                    delete VAR_WATCHERS[name];
                }
            }
        }
        // Если передано имя переменной
        else if (typeof nameOrId === 'string') {
            if (callback && typeof callback === 'function') {
                // Удаляем конкретный callback по имени
                if (VAR_WATCHERS[nameOrId]) {
                    VAR_WATCHERS[nameOrId] = VAR_WATCHERS[nameOrId].filter(function(w) {
                        return w.callback !== callback;
                    });
                    if (VAR_WATCHERS[nameOrId].length === 0) {
                        delete VAR_WATCHERS[nameOrId];
                    }
                }
            } else {
                // Удаляем все callback для указанной переменной
                delete VAR_WATCHERS[nameOrId];
            }
        }
    }

    /**
     * Работа с сессионными переменными (srctype="session")
     */
    this.setSession = function(name, value) {
        var oldValue = GLOBAL_SESSION[name];
        var newValue = value;

        // Вызываем watchers перед изменением
        if (SESSION_WATCHERS[name]) {
            for (var i = 0; i < SESSION_WATCHERS[name].length; i++) {
                var watcher = SESSION_WATCHERS[name][i];
                var result = watcher.callback(newValue, oldValue);
                // Если callback вернул значение (не undefined), используем его
                if (result !== undefined) {
                    newValue = result;
                }
            }
        }

        GLOBAL_SESSION[name] = newValue;

        fetch('/{component}/session', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                action: 'set',
                name: name,
                value: newValue
            })
        })
            .then(response => response.json())
            .then(function(response) {
                var event = new CustomEvent('sessionSaved', {
                    detail: { name: name, value: newValue },
                    bubbles: true
                });
                document.dispatchEvent(event);
            })
            .catch(error => console.error('Error saving session:', error));

        var event = new CustomEvent('sessionChanged', {
            detail: { name: name, newValue: newValue, oldValue: oldValue },
            bubbles: true
        });
        document.dispatchEvent(event);
    }

    this.getSession = function(name, defValue) {
        return GLOBAL_SESSION[name] !== undefined ? GLOBAL_SESSION[name] : defValue;
    }

    /**
     * Добавление отслеживания изменения сессионной переменной
     * @param {string} name - Имя переменной
     * @param {function} callback - Функция обратного вызова (newValue, oldValue)
     * @returns {string} ID подписки
     */
    this.onChangeSession = function(name, callback) {
        if (typeof callback !== 'function') return null;

        if (!SESSION_WATCHERS[name]) {
            SESSION_WATCHERS[name] = [];
        }

        var watcherId = 'sess_' + name + '_' + Date.now() + '_' + Math.random();
        SESSION_WATCHERS[name].push({
            id: watcherId,
            callback: callback
        });

        return watcherId;
    }

    /**
     * Удаление отслеживания изменения сессионной переменной
     * @param {string|function} nameOrId - Имя переменной или ID подписки
     * @param {function} [callback] - Опционально, конкретный callback для удаления
     */
    this.offChangeSession = function(nameOrId, callback) {
        // Если передан ID подписки
        if (typeof nameOrId === 'string' && nameOrId.indexOf('sess_') === 0) {
            for (var name in SESSION_WATCHERS) {
                SESSION_WATCHERS[name] = SESSION_WATCHERS[name].filter(function(w) {
                    return w.id !== nameOrId;
                });
                if (SESSION_WATCHERS[name].length === 0) {
                    delete SESSION_WATCHERS[name];
                }
            }
        }
        // Если передано имя переменной
        else if (typeof nameOrId === 'string') {
            if (callback && typeof callback === 'function') {
                // Удаляем конкретный callback по имени
                if (SESSION_WATCHERS[nameOrId]) {
                    SESSION_WATCHERS[nameOrId] = SESSION_WATCHERS[nameOrId].filter(function(w) {
                        return w.callback !== callback;
                    });
                    if (SESSION_WATCHERS[nameOrId].length === 0) {
                        delete SESSION_WATCHERS[nameOrId];
                    }
                }
            } else {
                // Удаляем все callback для указанной переменной
                delete SESSION_WATCHERS[nameOrId];
            }
        }
    }

    /**
     * Работа с подписями контролов (srctype="caption")
     */
    this.setCaption = function(name, text) {
        var ctrl = this.getControl(name);
        if (ctrl) {
            var oldText = this.getCaption(name);
            var newText = text;

            // Вызываем watchers перед изменением
            if (CAPTION_WATCHERS[name]) {
                for (var i = 0; i < CAPTION_WATCHERS[name].length; i++) {
                    var watcher = CAPTION_WATCHERS[name][i];
                    var result = watcher.callback(newText, oldText);
                    // Если callback вернул значение (не undefined), используем его
                    if (result !== undefined) {
                        newText = result;
                    }
                }
            }

            var captionEl = ctrl.querySelector('[block="caption"]');
            if (captionEl) {
                captionEl.textContent = newText;
            } else {
                ctrl.textContent = newText;
            }

            var event = new CustomEvent('captionChanged', {
                detail: { name: name, newText: newText, oldText: oldText },
                bubbles: true
            });
            document.dispatchEvent(event);

            return true;
        }
        return false;
    }

    this.getCaption = function(name) {
        var ctrl = this.getControl(name);
        if (ctrl) {
            var captionEl = ctrl.querySelector('[block="caption"]');
            if (captionEl) {
                return captionEl.textContent;
            } else {
                return ctrl.textContent;
            }
        }
        return null;
    }

    /**
     * Добавление отслеживания изменения подписи
     * @param {string} name - Имя контрола
     * @param {function} callback - Функция обратного вызова (newText, oldText)
     * @returns {string} ID подписки
     */
    this.onChangeCaption = function(name, callback) {
        if (typeof callback !== 'function') return null;

        if (!CAPTION_WATCHERS[name]) {
            CAPTION_WATCHERS[name] = [];
        }

        var watcherId = 'cap_' + name + '_' + Date.now() + '_' + Math.random();
        CAPTION_WATCHERS[name].push({
            id: watcherId,
            callback: callback
        });

        return watcherId;
    }

    /**
     * Удаление отслеживания изменения подписи
     * @param {string|function} nameOrId - Имя контрола или ID подписки
     * @param {function} [callback] - Опционально, конкретный callback для удаления
     */
    this.offChangeCaption = function(nameOrId, callback) {
        // Если передан ID подписки
        if (typeof nameOrId === 'string' && nameOrId.indexOf('cap_') === 0) {
            for (var name in CAPTION_WATCHERS) {
                CAPTION_WATCHERS[name] = CAPTION_WATCHERS[name].filter(function(w) {
                    return w.id !== nameOrId;
                });
                if (CAPTION_WATCHERS[name].length === 0) {
                    delete CAPTION_WATCHERS[name];
                }
            }
        }
        // Если передано имя контрола
        else if (typeof nameOrId === 'string') {
            if (callback && typeof callback === 'function') {
                // Удаляем конкретный callback по имени
                if (CAPTION_WATCHERS[nameOrId]) {
                    CAPTION_WATCHERS[nameOrId] = CAPTION_WATCHERS[nameOrId].filter(function(w) {
                        return w.callback !== callback;
                    });
                    if (CAPTION_WATCHERS[nameOrId].length === 0) {
                        delete CAPTION_WATCHERS[nameOrId];
                    }
                }
            } else {
                // Удаляем все callback для указанного контрола
                delete CAPTION_WATCHERS[nameOrId];
            }
        }
    }

    /**
     * Работа со значениями контролов (srctype="ctrl")
     */
    this.setValue = function(name, value) {
        var ctrlObj = document.querySelector('[name="' + name + '"]');
        if (!ctrlObj) return false;

        var oldValue = this.getValue(name);
        var newValue = value;

        // Вызываем watchers перед изменением
        if (VALUE_WATCHERS[name]) {
            for (var i = 0; i < VALUE_WATCHERS[name].length; i++) {
                var watcher = VALUE_WATCHERS[name][i];
                var result = watcher.callback(newValue, oldValue);
                // Если callback вернул значение (не undefined), используем его
                if (result !== undefined) {
                    newValue = result;
                }
            }
        }

        var tagName = ctrlObj.tagName.toLowerCase();

        if (tagName === 'input') {
            var type = ctrlObj.type.toLowerCase();
            if (type === 'checkbox') {
                ctrlObj.checked = (newValue === true || newValue === 'on' || newValue === 'true');
            } else if (type === 'radio') {
                var radioName = ctrlObj.getAttribute('name');
                var radios = document.querySelectorAll('input[name="' + radioName + '"]');
                for (var i = 0; i < radios.length; i++) {
                    if (radios[i].value == newValue) {
                        radios[i].checked = true;
                        break;
                    }
                }
            } else {
                ctrlObj.value = newValue;
            }
        } else if (tagName === 'select' || tagName === 'textarea') {
            ctrlObj.value = newValue;
        } else {
            ctrlObj.textContent = newValue;
        }

        // Генерируем событие change
        var changeEvent = new Event('change', { bubbles: true });
        ctrlObj.dispatchEvent(changeEvent);

        var event = new CustomEvent('valueChanged', {
            detail: { name: name, newValue: newValue, oldValue: oldValue },
            bubbles: true
        });
        document.dispatchEvent(event);

        return true;
    }

    this.getValue = function(name, defValue) {
        var ctrlObj = document.querySelector('[name="' + name + '"]');
        if (!ctrlObj) return defValue;

        var tagName = ctrlObj.tagName.toLowerCase();

        if (tagName === 'input') {
            var type = ctrlObj.type.toLowerCase();
            if (type === 'checkbox') {
                return ctrlObj.checked;
            } else if (type === 'radio') {
                var radioName = ctrlObj.getAttribute('name');
                var checkedRadio = document.querySelector('input[name="' + radioName + '"]:checked');
                return checkedRadio ? checkedRadio.value : defValue;
            } else {
                var val = ctrlObj.value;
                return val !== undefined && val !== null ? val : defValue;
            }
        } else if (tagName === 'select' || tagName === 'textarea') {
            var val = ctrlObj.value;
            return val !== undefined && val !== null ? val : defValue;
        } else {
            return ctrlObj.textContent || defValue;
        }
    }

    /**
     * Добавление отслеживания изменения значения контрола
     * @param {string} name - Имя контрола
     * @param {function} callback - Функция обратного вызова (newValue, oldValue)
     * @returns {string} ID подписки
     */
    this.onChangeValue = function(name, callback) {
        if (typeof callback !== 'function') return null;

        if (!VALUE_WATCHERS[name]) {
            VALUE_WATCHERS[name] = [];
        }

        var watcherId = 'val_' + name + '_' + Date.now() + '_' + Math.random();
        VALUE_WATCHERS[name].push({
            id: watcherId,
            callback: callback
        });

        return watcherId;
    }

    /**
     * Удаление отслеживания изменения значения контрола
     * @param {string|function} nameOrId - Имя контрола или ID подписки
     * @param {function} [callback] - Опционально, конкретный callback для удаления
     */
    this.offChangeValue = function(nameOrId, callback) {
        // Если передан ID подписки
        if (typeof nameOrId === 'string' && nameOrId.indexOf('val_') === 0) {
            for (var name in VALUE_WATCHERS) {
                VALUE_WATCHERS[name] = VALUE_WATCHERS[name].filter(function(w) {
                    return w.id !== nameOrId;
                });
                if (VALUE_WATCHERS[name].length === 0) {
                    delete VALUE_WATCHERS[name];
                }
            }
        }
        // Если передано имя контрола
        else if (typeof nameOrId === 'string') {
            if (callback && typeof callback === 'function') {
                // Удаляем конкретный callback по имени
                if (VALUE_WATCHERS[nameOrId]) {
                    VALUE_WATCHERS[nameOrId] = VALUE_WATCHERS[nameOrId].filter(function(w) {
                        return w.callback !== callback;
                    });
                    if (VALUE_WATCHERS[nameOrId].length === 0) {
                        delete VALUE_WATCHERS[nameOrId];
                    }
                }
            } else {
                // Удаляем все callback для указанного контрола
                delete VALUE_WATCHERS[nameOrId];
            }
        }
    }

    /**
     * Получение контрола по имени
     */
    this.getControl = function(name) {
        if (GLOBAL_CTRL[name]) {
            return GLOBAL_CTRL[name];
        }
        var ctrl = document.querySelector('[name="' + name + '"]');
        if (ctrl) {
            GLOBAL_CTRL[name] = ctrl;
        }
        return ctrl;
    }

    /**
     * Инициализация сессии при загрузке страницы
     * @param {Function} callback - Опциональная функция обратного вызова
     */
    this.initSession = function(callback) {
        fetch('/{component}/session?action=getAll', {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json'
            }
        })
            .then(response => {
                if (!response.ok) {
                    throw new Error('HTTP error ' + response.status);
                }
                return response.json();
            })
            .then(data => {
                // Обновляем GLOBAL_SESSION
                for (var key in data) {
                    GLOBAL_SESSION[key] = data[key];
                }

                // Генерируем событие загрузки сессии
                var event = new CustomEvent('sessionLoaded', {
                    detail: { data: data },
                    bubbles: true
                });
                document.dispatchEvent(event);

                // Вызываем callback только если он существует и является функцией
                if (callback && typeof callback === 'function') {
                    callback(null, data);
                }
            })
            .catch(error => {
                console.error('Error loading session:', error);

                // Генерируем событие ошибки
                var errorEvent = new CustomEvent('sessionError', {
                    detail: { error: error.message },
                    bubbles: true
                });
                document.dispatchEvent(event);

                // Вызываем callback только если он существует и является функцией
                if (callback && typeof callback === 'function') {
                    callback(error);
                }
            });
    };


    /**
     * Получение всех данных сессии
     * @param {Function} callback - Функция обратного вызова
     */
    this.getAllSession = function(callback) {
        fetch('/{component}/session?action=getAll', {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json'
            }
        })
            .then(response => {
                if (!response.ok) {
                    throw new Error('HTTP error ' + response.status);
                }
                return response.json();
            })
            .then(data => {
                callback(null, data);
            })
            .catch(error => {
                console.error('Error getting all session:', error);
                callback(error);
            });
    };
    /**
     * Удаление данных из сессии
     * @param {string} name - Имя сессионной переменной
     * @param {Function} callback - Функция обратного вызова
     */
    this.removeSession = function(name, callback) {
        fetch('/{component}/session?remove_session=' + encodeURIComponent(name), {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: '{}'
        })
            .then(response => response.json())
            .then(result => {
                if (callback) callback(null, result);
            })
            .catch(error => {
                console.error('Error removing session:', error);
                if (callback) callback(error);
            });
    };


    // ============== Методы для обратной совместимости ==============

    this.setAction = function(name, obj) {
        this.GLOBAL_ACTION[name] = obj;
    }

    this.setActionAuto = function(name) {
        this.GLOBAL_ACTION[name] = {};
    }

    this.setDatasetAuto = function(name) {
        this.GLOBAL_DATA_SET[name] = {"data": []};
    }

    this.setDataset = function(name, obj) {
        this.GLOBAL_DATA_SET[name] = obj;
        Object.defineProperty(this.GLOBAL_DATA_SET[name], 'data', {
            get: function() {
                return this._data || [];
            },
            set: function(value) {
                this._data = value;
                var event = new CustomEvent('datasetChanged', {
                    detail: { name: name, value: value },
                    bubbles: true
                });
                document.dispatchEvent(event);
            }
        });
        this.GLOBAL_DATA_SET[name]._data = obj.data || [];
    }

    this.getDataset = function(name) {
        return this.GLOBAL_DATA_SET[name];
    }

    this.setControlAuto = function(name, obj) {
        GLOBAL_CTRL[name] = obj;
    }

    this.setLabel = function(name, text) {
        if (GLOBAL_CTRL[name]) {
            var labelElement = GLOBAL_CTRL[name].querySelector('[block="label"]');
            if (labelElement) {
                labelElement.textContent = text;
                return true;
            }
        } else {
            var ctrlObj;
            if (typeof name === 'object') {
                ctrlObj = name;
            } else {
                ctrlObj = document.querySelector('[name="' + name + '"]');
            }

            if (ctrlObj) {
                var ctrl = ctrlObj.querySelector('[name="' + name + '_ctrl"]');
                if (!ctrl) {
                    ctrl = this.getCtrl(name);
                }
                if (ctrl) {
                    ctrl.innerText = text;
                    return true;
                }
            }
        }
        return false;
    }

    this.getLabel = function(name) {
        if (GLOBAL_CTRL[name]) {
            var labelElement = GLOBAL_CTRL[name].querySelector('[block="label"]');
            return labelElement ? labelElement.textContent : null;
        }
        return null;
    }

    this.setLabels = function(obj) {
        for (const name in obj) {
            this.setLabel(name, obj[name]);
        }
    }

    this.getLabels = function() {
        var ctrlList = document.querySelectorAll('[schema]');
        var res = {};
        for (var i = 0; i < ctrlList.length; i++) {
            var name = ctrlList[i].getAttribute('name');
            res[name] = this.getLabel(name);
        }
        return res;
    }

    this.getCtrl = function(name) {
        var ctrlElement = document.querySelector('[name="' + name + '"]');
        if (!ctrlElement) return null;

        var ctrlName = ctrlElement.getAttribute('ctrl');
        return ctrlName ? document.querySelector('[name="' + ctrlName + '"]') : null;
    }

    this.getValues = function() {
        var ctrlList = document.querySelectorAll('[schema]');
        var res = {};
        if (!ctrlList) return res;

        for (var i = 0; i < ctrlList.length; i++) {
            var name = ctrlList[i].getAttribute('name');
            res[name] = this.getValue(name);
        }
        return res;
    }

    this.setValues = function(obj) {
        for (const name in obj) {
            this.setValue(name, obj[name]);
        }
    }

    this.setDisabled = function(name, bool) {
        bool = (bool == true);
        var ctrlObj = document.querySelector('[name="' + name + '"]');
        if (!ctrlObj) return;

        var ctrl = this.getCtrl(name);
        if (!ctrl) return;

        var schema = ctrlObj.getAttribute('schema');
        var type = ctrlObj.getAttribute('type');

        if (type === 'accordion' || type === 'tabs') {
            // Для компонентов easyui
            if (bool) {
                ctrl.setAttribute('disabled', 'disabled');
                ctrl.classList.add('ui-state-disabled');
            } else {
                ctrl.removeAttribute('disabled');
                ctrl.classList.remove('ui-state-disabled', 'ui-button-disabled');
            }
        } else {
            if (bool) {
                ctrl.setAttribute('disabled', 'disabled');
            } else {
                ctrl.removeAttribute('disabled');
                ctrl.classList.remove('ui-state-disabled', 'ui-button-disabled');
            }
        }
    }

    this.setDisableds = function(obj) {
        for (const name in obj) {
            this.setDisabled(name, obj[name]);
        }
    }

    this.setDisabledArr = function(arr, val) {
        for (var ind = 0; ind < arr.length; ind++) {
            var ctrlName = arr[ind].trim();
            if (ctrlName.length > 0) this.setDisabled(ctrlName, val);
        }
    }

    this.setVisible = function(name, bool) {
        bool = (bool == true);
        var ctrl = D3Api.getControl(name);
        if (ctrl) {
            ctrl.style.visibility = bool ? 'visible' : 'hidden';
        }
    }

    this.setVisibles = function(obj) {
        for (const name in obj) {
            this.setVisible(name, obj[name]);
        }
    }

    this.setStyle = function(name, propObject) {
        var ctrl = D3Api.getControl(name);
        if (!ctrl) return;

        for (var key in propObject) {
            ctrl.style[key] = propObject[key];
        }
    }

    this.move = function(name, bool) {
        var ctrlObj = document.querySelector('[name="' + name + '"]');
        if (!ctrlObj) return;

        if (bool) {
            ctrlObj.setAttribute('draggable', 'true');
            ctrlObj.style.resize = 'both';
            ctrlObj.style.overflow = 'auto';
            this.setDisabled(name, true);
        } else {
            ctrlObj.setAttribute('draggable', 'false');
            ctrlObj.style.resize = 'none';
            this.setDisabled(name, false);
        }
    }

    this.draggable = function(name, bool) {
        var ctrlObj = document.querySelector('[name="' + name + '"]');
        if (ctrlObj) {
            ctrlObj.setAttribute('draggable', bool ? 'true' : 'false');
        }
    }

    this.resizable = function(name, bool) {
        var ctrlObj = document.querySelector('[name="' + name + '"]');
        if (!ctrlObj) return;

        if (bool) {
            ctrlObj.style.resize = 'both';
            ctrlObj.style.overflow = 'auto';
            this.setDisabled(name, true);
        } else {
            ctrlObj.style.resize = 'none';
            this.setDisabled(name, false);
        }
    }

    this.msgbox = function(text, buttontext, callback) {
        buttontext = buttontext || "OK";

        var dialog = document.createElement('div');
        dialog.textContent = text;
        dialog.style.cssText = 'position:fixed; top:50%; left:50%; transform:translate(-50%,-50%); background:white; border:1px solid #ccc; padding:20px; z-index:10000; box-shadow:0 2px 10px rgba(0,0,0,0.1);';

        var button = document.createElement('button');
        button.textContent = buttontext;
        button.style.cssText = 'margin-top:15px; padding:5px 15px; background:#007bff; color:white; border:none; cursor:pointer;';

        button.addEventListener('click', function() {
            document.body.removeChild(dialog);
            if (callback) callback();
        });

        dialog.appendChild(button);
        document.body.appendChild(dialog);
    }

    // ============== Глобальные функции (теперь внутри D3Api) ==============

    /**
     * Получение всех глобальных переменных
     * @returns {Object} Объект со всеми переменными
     */
    this.getVars = function() {
        return GLOBAL_VARS;
    };

    /**
     * Установка нескольких переменных
     * @param {Object} obj - Объект с парами ключ-значение
     */
    this.setVars = function(obj) {
        for (var key in obj) {
            this.setVar(key, obj[key]);
        }
    };

    /**
     * Выход из системы
     */
    this.logout = function() {
        fetch('/{component}/loginDataBase?logoff=1', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            }
        })
            .then(response => response.json())
            .then(dataObj => {
                if (!dataObj['connect']) {
                    this.setLabel('ctrlErrorInfo', dataObj['error']);
                }
                if ('redirect' in dataObj) {
                    window.location.href = dataObj['redirect'];
                }
            })
            .catch(error => console.error('Error:', error));
    }.bind(this);

    /**
     * Сохранение данных в сессию
     * @param {string} name - Имя сессионной переменной
     * @param {Object} objJson - Данные для сохранения
     */
    this.setSession = function(name, data, callback) {
        // Если data не объект, преобразуем в объект
        var dataToSend = typeof data === 'object' ? data : { value: data };

        fetch('/{component}/session?set_session=' + encodeURIComponent(name), {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(dataToSend)
        })
            .then(response => {
                if (!response.ok) {
                    throw new Error('HTTP error ' + response.status);
                }
                return response.json();
            })
            .then(result => {
                if (callback) callback(null, result);
            })
            .catch(error => {
                console.error('Error saving session:', error);
                if (callback) callback(error);
            });
    };

    /**
     * Получение данных из сессии
     * @param {string} name - Имя сессионной переменной
     * @param {Function} callback - Функция обратного вызова (опционально)
     * @returns {Object|string|number|boolean|null} - Данные из сессии (если callback не указан)
     */
    this.getSession = function(name, callback) {
        if (callback) {
            // Асинхронный режим
            fetch('/{component}/session?get_session=' + encodeURIComponent(name), {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: '{}'
            })
                .then(response => {
                    if (!response.ok) {
                        throw new Error('HTTP error ' + response.status);
                    }
                    return response.json();
                })
                .then(data => {
                    // Проверяем, содержит ли ответ только одно поле "value"
                    if (data && typeof data === 'object') {
                        var keys = Object.keys(data);
                        if (keys.length === 1 && keys[0] === 'value') {
                            // Возвращаем только значение поля value
                            callback(null, data.value);
                        } else {
                            // Возвращаем весь объект
                            callback(null, data);
                        }
                    } else {
                        callback(null, data);
                    }
                })
                .catch(error => {
                    console.error('Error getting session:', error);
                    callback(error);
                });
            return null;
        } else {
            // Синхронный режим для обратной совместимости
            var xhr = new XMLHttpRequest();
            xhr.open('POST', '/{component}/session?get_session=' + encodeURIComponent(name), false);
            xhr.setRequestHeader('Content-Type', 'application/json');
            xhr.send('{}');

            if (xhr.status === 200) {
                try {
                    var data = JSON.parse(xhr.responseText);

                    // Проверяем, содержит ли ответ только одно поле "value"
                    if (data && typeof data === 'object') {
                        var keys = Object.keys(data);
                        if (keys.length === 1 && keys[0] === 'value') {
                            // Возвращаем только значение поля value
                            return data.value;
                        }
                    }

                    return data;
                } catch (e) {
                    console.error('JSON parse error:', e);
                    return {};
                }
            }
            return {};
        }
    };

    /**
     * Сохранение текущего URL для возврата
     * @param {string} name - Имя закладки
     */
    this.saveDirect = function(name) {
        if (typeof name === 'undefined') {
            name = 'local';
        }

        fetch('/{component}/sessionDirect?set_direct=' + name, {
            method: 'POST',
            body: window.location.href
        })
            .catch(error => console.error('Error:', error));
    };

    /**
     * Загрузка сохраненного URL
     * @param {string} name - Имя закладки
     */
    this.loadDirect = function(name) {
        if (typeof name === 'undefined') {
            name = 'local';
        }

        fetch('/{component}/sessionDirect?get_direct=' + name, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: '{}'
        })
            .then(response => response.json())
            .then(dataObj => {
                if ('redirect' in dataObj) {
                    window.location.href = dataObj['redirect'];
                }
            })
            .catch(error => console.error('Error:', error));
    };

    // ============== НОВЫЙ ФУНКЦИОНАЛ ДЛЯ РАБОТЫ СО СКРИПТАМИ ==============

    /**
     * Хранилище для загруженных скриптов
     * @private
     */
    var _loadedScripts = {};
    
    /**
     * Промисы для загружаемых скриптов
     * @private
     */
    var _scriptPromises = {};

    /**
     * Загрузка внешнего скрипта
     * @param {string} name - Имя скрипта (для идентификации)
     * @param {string} src - URL скрипта
     * @param {boolean} async - Асинхронная загрузка
     * @param {boolean} defer - Отложенная загрузка
     * @returns {Promise} Промис загрузки скрипта
     */
    this.loadScript = function(name, src, async, defer) {
        return new Promise(function(resolve, reject) {
            if (_loadedScripts[src]) {
                resolve();
                return;
            }

            var script = document.createElement('script');
            script.src = src;
            script.type = 'text/javascript';
            
            if (async) script.async = true;
            if (defer) script.defer = true;

            script.onload = function() {
                _loadedScripts[src] = true;
                // Генерируем событие
                var event = new CustomEvent('scriptLoaded', {
                    detail: { name: name, src: src }
                });
                document.dispatchEvent(event);
                
                resolve();
            };

            script.onerror = function(error) {
                console.error('Script load error:', src, error);
                
                var event = new CustomEvent('scriptError', {
                    detail: { name: name, src: src, error: error }
                });
                document.dispatchEvent(event);
                
                reject(error);
            };

            document.head.appendChild(script);
        });
    };

    /**
     * Выполнение встроенного скрипта
     * @param {string} name - Имя скрипта
     * @param {string} content - Содержимое скрипта
     * @returns {boolean} Успешность выполнения
     */
    this.executeScript = function(name, content) {
        try {
            if (!content) return false;
            
            // Используем Function для создания функции в глобальной области видимости
            var scriptFunction = new Function(content);
            scriptFunction.call(window);
            var event = new CustomEvent('scriptExecuted', {
                detail: { name: name }
            });
            document.dispatchEvent(event);
            
            return true;
        } catch (e) {
            console.error('Script execution error:', name, e);
            
            var event = new CustomEvent('scriptError', {
                detail: { name: name, error: e.message }
            });
            document.dispatchEvent(event);
            
            return false;
        }
    };

    /**
     * Получение статуса скрипта
     * @param {string} name - Имя скрипта
     * @returns {Object} Статус скрипта
     */
    this.getScriptStatus = function(name) {
        var scriptElement = document.querySelector('[cmptype="Script"][name="' + name + '"]');
        if (!scriptElement) {
            return { exists: false, loaded: false, error: 'Script not found' };
        }
        
        var src = scriptElement.getAttribute('src');
        return {
            exists: true,
            loaded: src ? !!_loadedScripts[src] : true,
            error: scriptElement.D3Store ? scriptElement.D3Store.error : null,
            src: src,
            type: scriptElement.getAttribute('type') || 'text/javascript'
        };
    };

    /**
     * Ожидание загрузки скрипта
     * @param {string} name - Имя скрипта
     * @returns {Promise} Промис загрузки
     */
    this.waitForScript = function(name) {
        var scriptElement = document.querySelector('[cmptype="Script"][name="' + name + '"]');
        if (!scriptElement) {
            return Promise.reject('Script not found: ' + name);
        }
        
        var src = scriptElement.getAttribute('src');
        if (!src) {
            // Встроенный скрипт
            return Promise.resolve();
        }
        
        if (_loadedScripts[src]) {
            return Promise.resolve();
        }
        
        if (_scriptPromises[src]) {
            return _scriptPromises[src];
        }
        
        _scriptPromises[src] = new Promise(function(resolve, reject) {
            var checkInterval = setInterval(function() {
                if (_loadedScripts[src]) {
                    clearInterval(checkInterval);
                    delete _scriptPromises[src];
                    resolve();
                }
            }, 100);
            
            // Таймаут через 30 секунд
            setTimeout(function() {
                clearInterval(checkInterval);
                delete _scriptPromises[src];
                reject('Timeout waiting for script: ' + name);
            }, 30000);
        });
        
        return _scriptPromises[src];
    };

    /**
     * Создание нового скрипта динамически
     * @param {string} name - Имя скрипта
     * @param {string} content - Содержимое скрипта
     * @param {Object} options - Опции (src, type, async, defer)
     * @returns {Promise} Промис выполнения
     */
    this.createScript = function(name, content, options) {
        options = options || {};
        
        // Удаляем старый скрипт если есть
        var oldScript = document.querySelector('[cmptype="Script"][name="' + name + '"]');
        if (oldScript) {
            oldScript.remove();
        }
        
        // Создаем новый элемент
        var script = document.createElement('script');
        script.setAttribute('cmptype', 'Script');
        script.setAttribute('name', name);
        
        if (options.src) {
            script.setAttribute('src', options.src);
        }
        
        if (options.type) {
            script.setAttribute('type', options.type);
        }
        
        if (options.async) {
            script.setAttribute('async', 'async');
        }
        
        if (options.defer) {
            script.setAttribute('defer', 'defer');
        }
        
        if (options.charset) {
            script.setAttribute('charset', options.charset);
        }
        
        script.textContent = content || '';
        
        // Инициализируем D3Store
        script.D3Store = {
            loaded: false,
            error: null
        };
        
        document.head.appendChild(script);
        
        // Загружаем или выполняем
        if (options.src) {
            return this.loadScript(name, options.src, options.async, options.defer);
        } else if (content) {
            var result = this.executeScript(name, content);
            return result ? Promise.resolve() : Promise.reject('Script execution failed');
        }
        
        return Promise.resolve();
    };
}

// Инициализация
window.d3 = new D3Api.init(document.getElementsByTagName("body")[0]);

// Подключаем базовые классы (BaseCtrl и ControlBaseProperties)
(function() {
    // Функция для загрузки скрипта
    function loadScriptSync(src) {
        return new Promise((resolve, reject) => {
            const script = document.createElement('script');
            script.src = src;
            script.type = 'text/javascript';
            script.setAttribute('cmp', 'jslib');

            script.onload = () => {
                resolve();
            };

            script.onerror = () => {
                console.error(`Failed to load base script: ${src}`);
                reject(new Error(`Failed to load base script: ${src}`));
            };

            document.head.appendChild(script);
        });
    }

    // Загружаем базовые классы
    loadScriptSync('/{component}/cmpBase_js').then(() => {
        
        // Продолжаем инициализацию после загрузки базовых классов
        if (typeof D3Api.BaseCtrl !== 'undefined') {
            console.log('D3Api.BaseCtrl is now available');
        }
        
        // Инициализация универсальных методов контролов
        (function extendD3Api() {
            if (typeof D3Api === 'undefined') return;
            // Сохраняем оригинальные методы
            var originalSetCaption = D3Api.setCaption;
            var originalGetCaption = D3Api.getCaption;
            var originalSetDisabled = D3Api.setDisabled;
            var originalSetValue = D3Api.setValue;
            var originalGetValue = D3Api.getValue;

            /**
             * Универсальный метод установки подписи контрола
             * Поддерживает: кнопки, label, и другие элементы с подписями
             */
            D3Api.setCaption = function(name, text) {
                var ctrl = this.getControl(name);
                if (!ctrl) return false;

                // Проверяем наличие блока caption
                var captionEl = ctrl.querySelector('[block="caption"]');
                if (captionEl) {
                    captionEl.textContent = text;
                } else {
                    // Для кнопок и простых элементов
                    ctrl.textContent = text;
                }

                // Генерируем событие изменения
                var event = new CustomEvent('captionChanged', {
                    detail: { name: name, newText: text },
                    bubbles: true
                });
                ctrl.dispatchEvent(event);

                return true;
            };

            /**
             * Универсальный метод получения подписи контрола
             */
            D3Api.getCaption = function(name) {
                var ctrl = this.getControl(name);
                if (!ctrl) return null;

                var captionEl = ctrl.querySelector('[block="caption"]');
                if (captionEl) {
                    return captionEl.textContent;
                }
                return ctrl.textContent || null;
            };

            /**
             * Универсальный метод установки значения контрола
             * Поддерживает: input, select, textarea, checkbox, radio
             */
            D3Api.setValue = function(name, value) {
                var ctrlObj = document.querySelector('[name="' + name + '"]');
                if (!ctrlObj)  {
                    const bodyTmp = document.body;
                    const iframe = bodyTmp.querySelector('iframe');
                    const documentTmp = iframe.contentDocument || iframe.contentWindow.document;
                    if (documentTmp) {
                        ctrlObj = documentTmp.body.querySelector('[name="' + name + '"]');
                    }
                }
                if (!ctrlObj) return false;
                var oldValue = this.getValue(name);
                var tagName = ctrlObj.tagName.toLowerCase();
                var type = ctrlObj.type ? ctrlObj.type.toLowerCase() : '';

                // Обработка различных типов контролов
                if (tagName === 'input') {
                    if (type === 'checkbox') {
                        ctrlObj.checked = (value === true || value === 'on' || value === 'true');
                    } else if (type === 'radio') {
                        var radioName = ctrlObj.getAttribute('name');
                        var radios = document.querySelectorAll('input[name="' + radioName + '"]');
                        for (var i = 0; i < radios.length; i++) {
                            if (radios[i].value == value) {
                                radios[i].checked = true;
                                break;
                            }
                        }
                    } else {
                        ctrlObj.value = value;
                    }
                } else if (tagName === 'select' || tagName === 'textarea') {
                    ctrlObj.value = value;
                } else {
                    ctrlObj.textContent = value;
                }

                // Генерируем события
                var changeEvent = new Event('change', { bubbles: true });
                ctrlObj.dispatchEvent(changeEvent);

                var valueEvent = new CustomEvent('valueChanged', {
                    detail: { name: name, newValue: value, oldValue: oldValue },
                    bubbles: true
                });
                document.dispatchEvent(valueEvent);

                return true;
            };

            /**
             * Универсальный метод получения значения контрола
             */
            D3Api.getValue = function(name, defValue) {
                var ctrlObj = document.querySelector('[name="' + name + '"]');
                if (!ctrlObj)  {
                    const bodyTmp = document.body;
                    const iframe = bodyTmp.querySelector('iframe');
                    const documentTmp = iframe.contentDocument || iframe.contentWindow.document;
                    if (documentTmp) {
                        ctrlObj = documentTmp.body.querySelector('[name="' + name + '"]');
                    }
                }
                if (!ctrlObj) return defValue;
                var tagName = ctrlObj.tagName.toLowerCase();
                var type = ctrlObj.type ? ctrlObj.type.toLowerCase() : '';

                if (tagName === 'input') {
                    if (type === 'checkbox') {
                        return ctrlObj.checked;
                    } else if (type === 'radio') {
                        var radioName = ctrlObj.getAttribute('name');
                        var checkedRadio = document.querySelector('input[name="' + radioName + '"]:checked');
                        return checkedRadio ? checkedRadio.value : defValue;
                    } else {
                        return ctrlObj.value !== undefined ? ctrlObj.value : defValue;
                    }
                } else if (tagName === 'select' || tagName === 'textarea') {
                    return ctrlObj.value !== undefined ? ctrlObj.value : defValue;
                } else {
                    return ctrlObj.textContent || defValue;
                }
            };

            /**
             * Универсальный метод включения/отключения контрола
             * @param {string} name - Имя контрола
             * @param {boolean} disabled - true - отключить, false - включить
             */
            D3Api.setDisabled = function(name, disabled) {
                disabled = (disabled == true);
                var ctrlObj = document.querySelector('[name="' + name + '"]');
                if (!ctrlObj) return false;

                var tagName = ctrlObj.tagName.toLowerCase();
                var type = ctrlObj.getAttribute('type');

                // Для easyui компонентов
                if (type === 'accordion' || type === 'tabs' || ctrlObj.classList.contains('easyui-linkbutton')) {
                    if (disabled) {
                        ctrlObj.setAttribute('disabled', 'disabled');
                        ctrlObj.classList.add('ui-state-disabled');
                    } else {
                        ctrlObj.removeAttribute('disabled');
                        ctrlObj.classList.remove('ui-state-disabled', 'ui-button-disabled');
                    }
                    return true;
                }

                // Для стандартных контролов
                if (disabled) {
                    ctrlObj.setAttribute('disabled', 'disabled');
                } else {
                    ctrlObj.removeAttribute('disabled');
                }

                return true;
            };

            /**
             * Проверка, отключен ли контрол
             * @param {string} name - Имя контрола
             * @returns {boolean} - true если отключен
             */
            D3Api.isDisabled = function(name) {
                var ctrlObj = document.querySelector('[name="' + name + '"]');
                if (!ctrlObj) return false;

                return ctrlObj.hasAttribute('disabled') ||
                    ctrlObj.classList.contains('ui-state-disabled');
            };

            /**
             * Установка обработчика события на контрол
             * @param {string} name - Имя контрола
             * @param {string} event - Название события (click, change, etc.)
             * @param {Function} handler - Функция обработчик
             */
            D3Api.on = function(name, event, handler) {
                if (typeof handler !== 'function') return false;

                var ctrlObj = document.querySelector('[name="' + name + '"]');
                if (!ctrlObj) return false;

                ctrlObj.addEventListener(event, function(e) {
                    handler(e, ctrlObj);
                });

                return true;
            };

            /**
             * Установка обработчика клика (упрощенный метод)
             * @param {string} name - Имя контрола
             * @param {Function} handler - Функция обработчик
             */
            D3Api.onClick = function(name, handler) {
                return this.on(name, 'click', handler);
            };

            /**
             * Установка обработчика изменения (упрощенный метод)
             * @param {string} name - Имя контрола
             * @param {Function} handler - Функция обработчик
             */
            D3Api.onChange = function(name, handler) {
                return this.on(name, 'change', handler);
            };

            /**
             * Установка CSS класса для контрола
             * @param {string} name - Имя контрола
             * @param {string} className - Имя класса
             */
            D3Api.addClass = function(name, className) {
                var ctrlObj = document.querySelector('[name="' + name + '"]');
                if (!ctrlObj) return false;

                ctrlObj.classList.add(className);
                return true;
            };

            /**
             * Удаление CSS класса у контрола
             * @param {string} name - Имя контрола
             * @param {string} className - Имя класса
             */
            D3Api.removeClass = function(name, className) {
                var ctrlObj = document.querySelector('[name="' + name + '"]');
                if (!ctrlObj) return false;

                ctrlObj.classList.remove(className);
                return true;
            };

            /**
             * Проверка наличия CSS класса у контрола
             * @param {string} name - Имя контрола
             * @param {string} className - Имя класса
             */
            D3Api.hasClass = function(name, className) {
                var ctrlObj = document.querySelector('[name="' + name + '"]');
                if (!ctrlObj) return false;

                return ctrlObj.classList.contains(className);
            };
        })();

        // Инициализация сессии и контролов
        document.addEventListener("DOMContentLoaded", function() {
            D3Api.initSession(function() {
                var elementsWithNameAttribute = D3Api.D3MainContainer.querySelectorAll('[name][cmptype]');
                for (var i = 0; i < elementsWithNameAttribute.length; i++) {
                    var ctrlObj = elementsWithNameAttribute[i];
                    if (ctrlObj.getAttribute('cmptype')) {
                        var cmptype = ctrlObj.getAttribute('cmptype').toLowerCase();
                        if ((cmptype === 'action') || (cmptype === 'dataset')) continue;
                    }
                    var nameCtrl = ctrlObj.getAttribute('name');
                    D3Api.setControlAuto(nameCtrl, ctrlObj);
                }
            });
        });
    });
})();

// ============== Глобальные функции-обертки для обратной совместимости ==============

function getVars() {
    return D3Api.getVars();
}

function setVars(obj) {
    D3Api.setVars(obj);
}

function setVar(name, value) {
    D3Api.setVar(name, value);
}

function getVar(name, defaultValue) {
    return D3Api.getVar(name, defaultValue);
}

function logout() {
    D3Api.logout();
}

function setSession(name, objJson) {
    return D3Api.setSession(name, objJson);
}

function getSession(name) {
    return D3Api.getSession(name);
}

function saveDirect(name) {
    D3Api.saveDirect(name);
}

function loadDirect(name) {
    D3Api.loadDirect(name);
}            
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

            System.out.println("main_js: библиотека скомпилирована и закэширована (hash: " + currentHash + ")");

            return compiledJs;
        }
    }

    public static byte[] onPage(HttpExchange query) {
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