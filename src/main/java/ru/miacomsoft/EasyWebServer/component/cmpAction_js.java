package ru.miacomsoft.EasyWebServer.component;

import ru.miacomsoft.EasyWebServer.HttpExchange;

public class cmpAction_js {
    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/javascript";
        return """
            /**
             * Упрощённая JavaScript библиотека для компонента cmpAction
             * Отправляет на сервер только имя действия и значения переменных
             */
            (function() {
                if (window.cmpActionInitialized) return;
                window.cmpActionInitialized = true;
            
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
            
                    // Получаем vars из атрибута элемента
                    var varsString = ctrlObj.getAttribute('vars');
                    var jsonVars = {};
            
                    // Парсим vars
                    try {
                        jsonVars = parseVarsString(varsString);
                    } catch (e) {
                        console.error('Failed to parse vars attribute:', e);
                        jsonVars = {};
                    }
            
                    // Формируем данные для отправки - ТОЛЬКО ПЕРЕМЕННЫЕ
                    var requestData = {};
                    for (var key in jsonVars) {
                        var varInfo = jsonVars[key];
                        if (!varInfo) continue;
            
                        var value = '';
                        var src = varInfo.src || key;
                        var srctype = varInfo.srctype || 'var';
                        var defaultVal = varInfo.defaultVal || '';
            
                        // Получаем значение переменной
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
            
                        if (varInfo.len) {
                            requestData[key]['len'] = varInfo.len;
                        }
                    }
            
                    // Отправляем только имя действия и переменные
                    var url = '/{component}/cmpAction?action_name=' + encodeURIComponent(nameAction);
            
                    fetch(url, {
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
                        
                        if (dataObj.ERROR) {
                            console.error('Action error:', dataObj.ERROR);
                            var errorMsg = typeof dataObj.ERROR === 'object' ? 
                                          dataObj.ERROR.message : String(dataObj.ERROR);
                            if (window.D3Api && D3Api.msgbox) {
                                D3Api.msgbox('Ошибка: ' + errorMsg, 'OK');
                            } else {
                                alert('Ошибка: ' + errorMsg);
                            }
                        }
            
                        // Обрабатываем выходные переменные
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
                            if (dataObj.ERROR) {
                                callBack({ error: dataObj.ERROR, vars: dataObj.vars });
                            } else {
                                callBack(dataObj.vars || {});
                            }
                        }
                    })
                    .catch(function(error) {
                        console.error('Fetch error:', error);
                        if (callBack && typeof callBack === 'function') {
                            callBack({ error: error });
                        }
                    });
                };
            
                /**
                 * Парсинг строки vars
                 */
                function parseVarsString(str) {
                    var result = {};
                    if (!str || typeof str !== 'string') return result;
                    
                    str = str.trim();
                    if (str.startsWith('{') && str.endsWith('}')) {
                        str = str.substring(1, str.length - 1);
                    }
                    if (!str) return result;
            
                    var pairs = [];
                    var depth = 0;
                    var current = '';
                    var inString = false;
            
                    for (var i = 0; i < str.length; i++) {
                        var c = str[i];
            
                        if (c === '{') depth++;
                        else if (c === '}') depth--;
                        else if (c === "'" && (i === 0 || str[i-1] !== '\\\\')) inString = !inString;
            
                        if (c === ',' && depth === 0 && !inString) {
                            pairs.push(current);
                            current = '';
                        } else {
                            current += c;
                        }
                    }
                    if (current.trim()) pairs.push(current);
            
                    for (var p = 0; p < pairs.length; p++) {
                        var pair = pairs[p];
                        var colonIndex = pair.indexOf(':');
                        if (colonIndex === -1) continue;
            
                        var key = pair.substring(0, colonIndex).trim().replace(/^'|'$/g, '');
                        var valueStr = pair.substring(colonIndex + 1).trim();
            
                        if (valueStr.startsWith('{') && valueStr.endsWith('}')) {
                            var obj = {};
                            var innerStr = valueStr.substring(1, valueStr.length - 1);
                            var innerPairs = innerStr.split(',');
            
                            for (var inner = 0; inner < innerPairs.length; inner++) {
                                var innerPair = innerPairs[inner];
                                var innerColon = innerPair.indexOf(':');
                                if (innerColon === -1) continue;
            
                                var innerKey = innerPair.substring(0, innerColon).trim().replace(/^'|'$/g, '');
                                var innerValue = innerPair.substring(innerColon + 1).trim().replace(/^'|'$/g, '');
                                obj[innerKey] = innerValue;
                            }
                            result[key] = obj;
                        }
                    }
                    return result;
                }
            
                // Расширение D3Api
                if (typeof D3Api !== 'undefined') {
                    D3Api.executeAction = window.executeAction;
                }
            })();
            """.getBytes();
    }
}