package ru.miacomsoft.EasyWebServer.component;

import ru.miacomsoft.EasyWebServer.HttpExchange;

public class cmpAction_js {
    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/javascript";
        return """
            /**
             * JavaScript библиотека для компонента cmpAction
             * Предоставляет методы для работы с действиями на клиенте
             * Использует новый механизм хранения данных D3Api
             */
            (function() {
                // Предотвращаем повторную инициализацию
                if (window.cmpActionInitialized) return;
                window.cmpActionInitialized = true;
            
                /**
                 * Расширение D3Api для работы с действиями
                 */
                if (typeof D3Api !== 'undefined') {
                    
                    /**
                     * Установка автоматического действия
                     * @param {string} name - Имя действия
                     */
                    D3Api.setActionAuto = function(name) {
                        if (!this.GLOBAL_ACTION) this.GLOBAL_ACTION = {};
                        this.GLOBAL_ACTION[name] = {};
                    };
                    
                    /**
                     * Выполнение действия
                     * @param {string} nameAction - Имя действия
                     * @param {Function} callBack - Функция обратного вызова
                     */
                    D3Api.executeAction = function(nameAction, callBack) {
                        return executeAction(nameAction, callBack);
                    };
                    
                    /**
                     * Получение результата действия
                     * @param {string} nameAction - Имя действия
                     * @returns {Object} - Результат действия
                     */
                    D3Api.getActionResult = function(nameAction) {
                        if (this.GLOBAL_ACTION && this.GLOBAL_ACTION[nameAction]) {
                            return this.GLOBAL_ACTION[nameAction];
                        }
                        return null;
                    };
                }
            
                /**
                 * Инициализация всех действий на странице
                 */
                function initActions() {
                    var actionElements = document.querySelectorAll('[schema="Action"]');
                    for (var i = 0; i < actionElements.length; i++) {
                        var actionEl = actionElements[i];
                        var name = actionEl.getAttribute('name');
                        if (name && window.D3Api) {
                            D3Api.setActionAuto(name);
                        }
                    }
                }
            
                /**
                 * Глобальная функция выполнения действия
                 * @param {string} nameAction - Имя действия
                 * @param {Function} callBack - Функция обратного вызова
                 */
                window.executeAction = function(nameAction, callBack) {
                    var ctrlObj = document.querySelector('[name="' + nameAction + '"]');
                    if (!ctrlObj) {
                        console.error('Action not found:', nameAction);
                        return;
                    }
            
                    // Получаем атрибуты
                    var varsString = ctrlObj.getAttribute('vars');
                    // Парсим vars
                    var jsonVars = {};
            
                    try {
                        var fixedString = varsString
                            .replace(/'/g, '"')
                            .replace(/(\\w+):/g, '"$1":')
                            .replace(/,\\s*}/g, '}');
            
                        jsonVars = JSON.parse(fixedString);
                    } catch (e) {
                        try {
                            var cleanStr = varsString.trim();
                            if (cleanStr.startsWith('{') && cleanStr.endsWith('}')) {
                                cleanStr = cleanStr.substring(1, cleanStr.length - 1);
                            }
            
                            var pairs = [];
                            var depth = 0;
                            var current = '';
            
                            for (var i = 0; i < cleanStr.length; i++) {
                                var c = cleanStr[i];
            
                                if (c === '{') depth++;
                                else if (c === '}') depth--;
            
                                if (c === ',' && depth === 0) {
                                    pairs.push(current);
                                    current = '';
                                } else {
                                    current += c;
                                }
                            }
                            if (current.trim()) {
                                pairs.push(current);
                            }
            
                            for (var p = 0; p < pairs.length; p++) {
                                var pair = pairs[p];
                                var colonIndex = pair.indexOf(':');
                                if (colonIndex === -1) continue;
            
                                var key = pair.substring(0, colonIndex).trim().replace(/['"]/g, '');
                                var valueStr = pair.substring(colonIndex + 1).trim();
            
                                if (valueStr.startsWith('{') && valueStr.endsWith('}')) {
                                    var obj = {};
                                    var innerStr = valueStr.substring(1, valueStr.length - 1);
                                    var innerPairs = innerStr.split(',');
            
                                    for (var inner = 0; inner < innerPairs.length; inner++) {
                                        var innerPair = innerPairs[inner];
                                        var innerColon = innerPair.indexOf(':');
                                        if (innerColon === -1) continue;
            
                                        var innerKey = innerPair.substring(0, innerColon).trim().replace(/['"]/g, '');
                                        var innerValue = innerPair.substring(innerColon + 1).trim().replace(/['"]/g, '');
                                        obj[innerKey] = innerValue;
                                    }
                                    jsonVars[key] = obj;
                                }
                            }
                        } catch (e2) {
                            console.error('Manual parse failed:', e2);
                        }
                    }
            
                    var query_type = ctrlObj.getAttribute('query_type') || 'java';
                    var action_name = ctrlObj.getAttribute('action_name');
                    var pg_schema = ctrlObj.getAttribute('pg_schema') || 'public';
                    // Формируем данные для отправки
                    var requestData = {};
                    for (var key in jsonVars) {
                        var varInfo = jsonVars[key];
                        if (!varInfo) continue;
            
                        var value = '';
                        var src = varInfo.src || key;
                        var srctype = varInfo.srctype || 'var';
                        var defaultVal = varInfo.defaultVal || '';
                        var len = varInfo.len || '';
            
                        // Используем новый механизм D3Api для получения значений
                        if (srctype === 'var') {
                            if (window.D3Api && D3Api.getVar) {
                                value = D3Api.getVar(src) || defaultVal;
                            } else {
                                value = window.getVar ? window.getVar(src) || defaultVal : defaultVal;
                            }
                        } else if (srctype === 'ctrl') {
                            if (window.D3Api && D3Api.getValue) {
                                value = D3Api.getValue(src) || defaultVal;
                            } else {
                                var ctrlElement = document.querySelector('[name="' + src + '"]');
                                value = ctrlElement ? ctrlElement.value : defaultVal;
                            }
                        } else if (srctype === 'session') {
                            if (window.D3Api && D3Api.getSession) {
                                value = D3Api.getSession(src) || defaultVal;
                            } else {
                                value = defaultVal;
                            }
                        }
            
                        requestData[key] = {
                            'srctype': srctype,
                            'src': src,
                            'value': String(value),
                            'defaultVal': defaultVal
                        };
            
                        if (len) {
                            requestData[key].len = len;
                        }
                    }
                    fetch('/{component}/cmpAction?query_type=' + query_type + '&action_name=' + action_name + '&pg_schema=' + pg_schema, {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/json'
                        },
                        body: JSON.stringify(requestData)
                    })
                    .then(function(response) {
                        return response.json();
                    })
                    .then(function(dataObj) {
                        
                        if (dataObj.redirect) {
                            if (window.saveDirect) {
                                window.saveDirect('loginDirect');
                            }
                            window.location.href = dataObj.redirect;
                            return;
                        }
                        
                        // Улучшенная обработка ошибок
                        if (dataObj.ERROR) {
                            console.error('Action error:', dataObj.ERROR);
                            
                            // Если есть детальная информация об ошибке, показываем её
                            if (typeof dataObj.ERROR === 'object') {
                                var errorMsg = dataObj.ERROR.message || 'Unknown error';
                                var errorHint = dataObj.ERROR.hint || '';
                                var fieldValue = dataObj.ERROR.field_value || '';
                                
                                // Формируем сообщение для пользователя
                                var userMessage = 'Ошибка выполнения действия: ' + errorMsg;
                                if (errorHint) {
                                    userMessage += '\\n' + errorHint;
                                }
                                if (fieldValue) {
                                    userMessage += '\\nЗначение поля: "' + fieldValue + '"';
                                }
                                
                                // Показываем сообщение пользователю
                                if (window.D3Api && D3Api.msgbox) {
                                    D3Api.msgbox(userMessage, 'OK');
                                } else {
                                    alert(userMessage);
                                }
                            } else {
                                // Простая ошибка в виде строки
                                var errorStr = String(dataObj.ERROR);
                                if (window.D3Api && D3Api.msgbox) {
                                    D3Api.msgbox('Ошибка: ' + errorStr, 'OK');
                                } else {
                                    alert('Ошибка: ' + errorStr);
                                }
                            }
                        }
            
                        // Обрабатываем выходные переменные через новый механизм D3Api
                        if (dataObj.vars) {
                            var data = dataObj.vars;
                            for (var key in data) {
                                var varInfo = data[key];
                                if (typeof varInfo === 'object') {
                                    var value = varInfo.value;
                                    var srctype = varInfo.srctype || 'var';
                                    var src = varInfo.src || key;
            
                                    if (value === 'null') value = null;
                                    else if (value === 'true') value = true;
                                    else if (value === 'false') value = false;
                                    if (srctype === 'var') {
                                        if (window.D3Api && D3Api.setVar) {
                                            D3Api.setVar(src, value);
                                        } else if (window.setVar) {
                                            window.setVar(src, value);
                                        }
                                    } else if (srctype === 'ctrl') {
                                        if (value === null) value = '';
                                        if (window.D3Api && D3Api.setValue) {
                                            D3Api.setValue(src, value);
                                        } else {
                                            var targetElement = document.querySelector('[name="' + src + '"]');
                                            if (targetElement) targetElement.value = value;
                                        }
                                    } else if (srctype === 'session') {
                                        if (window.D3Api && D3Api.setSession) {
                                            D3Api.setSession(src, value);
                                        }
                                    }
                                }
                            }
                        }
            
                        if (!window.D3Api || !D3Api.GLOBAL_ACTION) {
                            window.D3Api = window.D3Api || {};
                            D3Api.GLOBAL_ACTION = D3Api.GLOBAL_ACTION || {};
                        }
                        
                        D3Api.GLOBAL_ACTION[nameAction] = dataObj;
            
                        if (callBack && typeof callBack === 'function') {
                            // Вызываем callback даже при ошибке, но передаем информацию об ошибке
                            if (dataObj.ERROR) {
                                // Если есть vars, передаем их вместе с ошибкой
                                if (dataObj.vars) {
                                    callBack({ error: dataObj.ERROR, vars: dataObj.vars });
                                } else {
                                    callBack({ error: dataObj.ERROR });
                                }
                            } else {
                                callBack(dataObj.vars || {});
                            }
                        }
                    })
                    .catch(function(error) {
                        console.error('Fetch error:', error);
                        
                        // Показываем ошибку сети
                        var errorMsg = 'Ошибка соединения с сервером: ' + error.message;
                        if (window.D3Api && D3Api.msgbox) {
                            D3Api.msgbox(errorMsg, 'OK');
                        } else {
                            alert(errorMsg);
                        }
                        
                        if (callBack && typeof callBack === 'function') {
                            callBack({ error: error });
                        }
                    });
                };
            
                // Инициализация после загрузки DOM
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', initActions);
                } else {
                    initActions();
                }
            })();
            """.getBytes();
    }
}