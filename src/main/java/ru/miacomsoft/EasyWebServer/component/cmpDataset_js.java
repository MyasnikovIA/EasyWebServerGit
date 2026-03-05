package ru.miacomsoft.EasyWebServer.component;

import ru.miacomsoft.EasyWebServer.HttpExchange;

public class cmpDataset_js {
    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/javascript";
        return """
            /**
             * JavaScript библиотека для компонента cmpDataset
             * Предоставляет методы для работы с датасетами на клиенте
             * Использует новый механизм хранения данных D3Api
             */
            (function() {
                // Предотвращаем повторную инициализацию
                if (window.cmpDatasetInitialized) return;
                window.cmpDatasetInitialized = true;
            
                /**
                 * Расширение D3Api для работы с датасетами
                 */
                if (typeof D3Api !== 'undefined') {
                    
                    /**
                     * Обновление датасета
                     * @param {string} nameDataset - Имя датасета
                     * @param {Function} callBack - Функция обратного вызова
                     */
                    D3Api.refreshDataSet = function(nameDataset, callBack) {
                        return refreshDataSet(nameDataset, callBack);
                    };
                    
                    /**
                     * Получение данных датасета
                     * @param {string} nameDataset - Имя датасета
                     * @returns {Array} - Массив данных
                     */
                    D3Api.getDatasetData = function(nameDataset) {
                        if (this.GLOBAL_DATA_SET && this.GLOBAL_DATA_SET[nameDataset]) {
                            return this.GLOBAL_DATA_SET[nameDataset].data || [];
                        }
                        return [];
                    };
                    
                    /**
                     * Привязка датасета к элементу (например, для select)
                     * @param {string} nameDataset - Имя датасета
                     * @param {string} elementName - Имя элемента
                     * @param {Object} options - Опции {valueField: 'id', textField: 'name'}
                     */
                    D3Api.bindDatasetToElement = function(nameDataset, elementName, options) {
                        var data = this.getDatasetData(nameDataset);
                        var element = document.querySelector('[name="' + elementName + '"]');
                        if (!element) return false;
                        
                        options = options || { valueField: 'id', textField: 'name' };
                        
                        // Очищаем текущие опции
                        while (element.firstChild) {
                            element.removeChild(element.firstChild);
                        }
                        
                        // Добавляем опцию по умолчанию
                        var defaultOption = document.createElement('option');
                        defaultOption.value = '';
                        defaultOption.textContent = '-- Выберите --';
                        element.appendChild(defaultOption);
                        
                        // Добавляем опции из датасета
                        for (var i = 0; i < data.length; i++) {
                            var item = data[i];
                            var option = document.createElement('option');
                            option.value = item[options.valueField] || '';
                            option.textContent = item[options.textField] || '';
                            element.appendChild(option);
                        }
                        
                        return true;
                    };
                    
                    /**
                     * Фильтрация данных датасета
                     * @param {string} nameDataset - Имя датасета
                     * @param {Function} filterFn - Функция фильтрации
                     * @returns {Array} - Отфильтрованные данные
                     */
                    D3Api.filterDataset = function(nameDataset, filterFn) {
                        var data = this.getDatasetData(nameDataset);
                        return data.filter(filterFn);
                    };
                }
            
                /**
                 * Глобальная функция обновления датасета
                 * @param {string} nameDataset - Имя датасета
                 * @param {Function} callBack - Функция обратного вызова
                 */
                window.refreshDataSet = function(nameDataset, callBack) {
                    var ctrlObj = document.querySelector('[name="' + nameDataset + '"]');
                    if (!ctrlObj) {
                        console.error('Dataset not found:', nameDataset);
                        return;
                    }
            
                    // Получаем строку с атрибутом vars
                    var varsString = ctrlObj.getAttribute('vars');
                    var jsonVars;
            
                    function parseVarsString(str) {
                        var result = {};
                        str = str.trim();
                        if (str.startsWith('{') && str.endsWith('}')) {
                            str = str.substring(1, str.length - 1);
                        }
            
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
                        if (current.trim()) {
                            pairs.push(current);
                        }
            
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
            
                    try {
                        jsonVars = parseVarsString(varsString);
                    } catch (e) {
                        console.error('Failed to parse vars attribute:', e);
                        return;
                    }
            
                    var query_type = ctrlObj.getAttribute('query_type');
                    var db = ctrlObj.getAttribute('db');
                    var dataset_name = ctrlObj.getAttribute('dataset_name');
                    var database_name = ctrlObj.getAttribute('db');
                    var db_type = ctrlObj.getAttribute('db_type');
                    
                    // Формируем данные для отправки
                    var requestData = {};
            
                    for (var key in jsonVars) {
                        var varInfo = jsonVars[key];
                        var value = '';
            
                        // Используем новый механизм D3Api для получения значений
                        if (varInfo['srctype'] === 'var') {
                            if (window.D3Api && D3Api.getVar) {
                                value = D3Api.getVar(varInfo['src']) || varInfo['defaultVal'] || '';
                            } else {
                                value = window.getVar ? window.getVar(varInfo['src']) || varInfo['defaultVal'] || '' : '';
                            }
                        } else if (varInfo['srctype'] === 'ctrl') {
                            if (window.D3Api && D3Api.getValue) {
                                value = D3Api.getValue(varInfo['src']) || varInfo['defaultVal'] || '';
                            } else {
                                var ctrlElement = document.querySelector('[name="' + varInfo['src'] + '"]');
                                value = ctrlElement ? ctrlElement.value : (varInfo['defaultVal'] || '');
                            }
                        } else if (varInfo['srctype'] === 'session') {
                            if (window.D3Api && D3Api.getSession) {
                                value = D3Api.getSession(varInfo['src']) || varInfo['defaultVal'] || '';
                            } else {
                                value = varInfo['defaultVal'] || '';
                            }
                        } else {
                            value = varInfo['defaultVal'] || '';
                        }
            
                        requestData[key] = {
                            'srctype': varInfo['srctype'],
                            'src': varInfo['src'],
                            'value': String(value),
                            'defaultVal': varInfo['defaultVal'] || ''
                        };
            
                        if (varInfo['len']) {
                            requestData[key]['len'] = varInfo['len'];
                        }
                    }
                    fetch('/{component}/cmpDataset?query_type=' + query_type + '&dataset_name=' + dataset_name + '&pg_schema=' + ((ctrlObj.getAttribute('pg_schema') || 'public') + '&database_name='+database_name+'&db_type='+db_type), {
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
                        if (dataObj['redirect']) {
                            if (window.saveDirect) {
                                window.saveDirect('loginDirect');
                            }
                            window.location.href = dataObj['redirect'];
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
                        if (dataObj['vars_out']) {
                            var outVars = dataObj['vars_out'];
                            for (var key in outVars) {
                                var varInfo = outVars[key];
                                var value = varInfo['value'];
            
                                if (value === 'null') value = null;
                                else if (value === 'true') value = true;
                                else if (value === 'false') value = false;
            
                                if (varInfo['srctype'] === 'var') {
                                    if (window.D3Api && D3Api.setVar) {
                                        D3Api.setVar(varInfo['src'], value);
                                    } else if (window.setVar) {
                                        window.setVar(varInfo['src'], value);
                                    }
                                } else if (varInfo['srctype'] === 'ctrl') {
                                    if (value === null) value = '';
                                    if (window.D3Api && D3Api.setValue) {
                                        D3Api.setValue(varInfo['src'], value);
                                    } else {
                                        var targetElement = document.querySelector('[name="' + varInfo['src'] + '"]');
                                        if (targetElement) targetElement.value = value;
                                    }
                                } else if (varInfo['srctype'] === 'session') {
                                    if (window.D3Api && D3Api.setSession) {
                                        D3Api.setSession(varInfo['src'], value);
                                    }
                                }
                            }
                        }
            
                        if (!window.D3Api || !D3Api.GLOBAL_DATA_SET) {
                            window.D3Api = window.D3Api || {};
                            D3Api.GLOBAL_DATA_SET = D3Api.GLOBAL_DATA_SET || {};
                        }
                        
                        if (!D3Api.GLOBAL_DATA_SET[nameDataset]) {
                            D3Api.GLOBAL_DATA_SET[nameDataset] = { data: [] };
                        }
                        D3Api.GLOBAL_DATA_SET[nameDataset].data = dataObj['data'] || [];
            
                        if (callBack && typeof callBack === 'function') {
                            callBack(dataObj['data']);
                        }
                    })
                    .catch(function(error) {
                        console.error('Fetch error:', error);
                    });
                };
            })();
            """.getBytes();
    }
}