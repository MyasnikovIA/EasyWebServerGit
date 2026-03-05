package ru.miacomsoft.EasyWebServer.component;

import ru.miacomsoft.EasyWebServer.HttpExchange;

/**
 * JavaScript библиотека для компонента cmpEdit
 * Предоставляет функциональность для работы с полями ввода
 * Включает форматирование, маски, валидацию
 */
public class cmpEdit_js {

    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/javascript";

        StringBuilder js = new StringBuilder();
        js.append("""
                (function() {
                    if (window.cmpEditInitialized) return;
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
                        if (window.cmpEditInitialized) return;
                        window.cmpEditInitialized = true;
                        // Хранилище для масок
                        var maskInstances = {};
                        
                        /**
                         * Получение input элемента из контейнера Edit
                         */
                        function getInput(dom) {
                            return dom ? dom.querySelector('input') : null;
                        }
                        
                        /**
                         * Инициализация всех полей ввода
                         */
                        function initEdits() {
                            var edits = document.querySelectorAll('[cmptype="Edit"]');
                            for (var i = 0; i < edits.length; i++) {
                                var edit = edits[i];
                                var input = getInput(edit);
                                if (!input) continue;
                                var name = edit.getAttribute('name');
                                var format = edit.getAttribute('data-format');
                                var maskType = edit.getAttribute('data-mask-type');
                                var trim = edit.getAttribute('data-trim') === 'true';
                                var placeholder = edit.getAttribute('data-placeholder');
                                
                                // Сохраняем свойства
                                edit.D3Store = edit.D3Store || {};
                                edit.D3Store.trim = trim;
                                edit._internalValue = input.value;
                                
                                // Добавляем обработчики событий
                                input.addEventListener('focus', function(e) {
                                    var inp = e.target;
                                    var edit = findEditContainer(inp);
                                    if (edit) {
                                        handleFocus(edit, inp);
                                    }
                                });
                                
                                input.addEventListener('blur', function(e) {
                                    var inp = e.target;
                                    var edit = findEditContainer(inp);
                                    if (edit) {
                                        handleBlur(edit, inp);
                                    }
                                });
                                
                                input.addEventListener('change', function(e) {
                                    D3Api.stopEvent(e);
                                    var inp = e.target;
                                    var edit = findEditContainer(inp);
                                    if (edit) {
                                        handleChange(edit, inp);
                                    }
                                });
                                
                                input.addEventListener('keydown', function(e) {
                                    var inp = e.target;
                                    var edit = findEditContainer(inp);
                                    if (edit && edit.D3Store.D3MaskParams) {
                                        handleMaskKeyDown(edit, inp, e);
                                    }
                                });
                                
                                // Инициализируем маску если есть
                                if (maskType) {
                                    initMask(edit, input, maskType, format);
                                }
                                
                                // Устанавливаем placeholder
                                if (placeholder) {
                                    setPlaceholder(edit, placeholder);
                                }
                                
                                edit.setAttribute('data-handler-initialized', 'true');
                            }
                        }
                        
                        /**
                         * Поиск контейнера Edit по input элементу
                         */
                        function findEditContainer(input) {
                            var parent = input.parentNode;
                            while (parent) {
                                if (parent.getAttribute && parent.getAttribute('cmptype') === 'Edit') {
                                    return parent;
                                }
                                parent = parent.parentNode;
                            }
                            return null;
                        }
                        
                        /**
                         * Обработка фокуса
                         */
                        function handleFocus(edit, input) {
                            // Если есть форматирование, показываем сырое значение
                            if (edit.D3Base && edit.D3Base.events && edit.D3Base.events['onformat']) {
                                if (edit._internalValue !== undefined) {
                                    edit.D3Base.callEvent('onformat', edit._internalValue);
                                    if (edit._internalFormatted !== undefined) {
                                        input.value = edit._internalFormatted;
                                    }
                                }
                            }
                            
                            // Убираем placeholder если он есть
                            unsetPlaceholder(input);
                            
                            edit.classList.add('focus');
                        }
                        
                        /**
                         * Обработка потери фокуса
                         */
                        function handleBlur(edit, input) {
                            // Если есть форматирование
                            if (edit.D3Base && edit.D3Base.events && edit.D3Base.events['onformat']) {
                                // Проверяем валидность маски
                                if (edit.D3Store.D3MaskParams) {
                                    if (!edit.D3Store.D3MaskParams.valid()) {
                                        edit._internalValue = null;
                                        setControlProperty(edit, 'value', null);
                                        return;
                                    }
                                }
                                
                                // Обновляем внутреннее значение
                                edit.D3Base.callEvent('onformat', input.value);
                                if (edit._internalValue !== undefined) {
                                    setControlProperty(edit, 'value', edit._internalValue);
                                }
                            }
                            
                            // Если есть маска, событие обрабатывается там
                            if (edit.D3Store.D3MaskParams) return;
                            
                            // Устанавливаем значение
                            var value = edit._internalValue || input.value;
                            if (edit.D3Store.trim) {
                                value = value.trim();
                            }
                            setControlProperty(edit, 'caption', value);
                            
                            // Возвращаем placeholder если нужно
                            setPlaceholder(edit);
                            
                            edit.classList.remove('focus');
                        }
                        
                        /**
                         * Обработка изменения
                         */
                        function handleChange(edit, input) {
                            D3Api.stopEvent();
                            // Дополнительная обработка при необходимости
                        }
                        
                        /**
                         * Установка свойства контрола
                         */
                        function setControlProperty(edit, prop, value) {
                            if (!edit.D3Base) return;
                            
                            edit.D3Store._properties_ = edit.D3Store._properties_ || {};
                            edit.D3Store._properties_[prop] = value;
                            
                            // Генерируем событие
                            var event = new CustomEvent('propertyChange', {
                                detail: { property: prop, value: value },
                                bubbles: true
                            });
                            edit.dispatchEvent(event);
                        }
                        
                        /**
                         * Инициализация маски
                         */
                        function initMask(edit, input, maskType, format) {
                            var maskParams = {
                                type: maskType,
                                format: format ? JSON.parse(format) : null,
                                valid: function() {
                                    // Базовая проверка валидности
                                    return input.value.length > 0;
                                }
                            };
                            
                            edit.D3Store.D3MaskParams = maskParams;
                            maskInstances[edit.getAttribute('name')] = maskParams;
                        }
                        
                        /**
                         * Обработка нажатий клавиш для маски
                         */
                        function handleMaskKeyDown(edit, input, e) {
                            // Здесь будет логика маски
                        }
                        
                        /**
                         * Установка placeholder
                         */
                        function setPlaceholder(edit, value) {
                            var input = getInput(edit);
                            if (!input) return;
                            
                            if (value !== undefined) {
                                edit.setAttribute('data-placeholder', value);
                                input.setAttribute('placeholder', value);
                            }
                            
                            // Для старых браузеров
                            if (!("placeholder" in document.createElement("input"))) {
                                initLegacyPlaceholder(edit, input);
                            }
                        }
                        
                        /**
                         * Инициализация placeholder для старых браузеров
                         */
                        function initLegacyPlaceholder(edit, input) {
                            var placeholder = edit.getAttribute('data-placeholder');
                            if (!placeholder) return;
                            
                            input._placeholder = placeholder;
                            input._isPassword = input.type === 'password';
                            
                            // Устанавливаем начальное состояние
                            if (!input.value) {
                                input.value = placeholder;
                                input.classList.add('_placeholder');
                            }
                            
                            // Добавляем обработчики
                            input.addEventListener('focus', function() {
                                unsetPlaceholder(this);
                            });
                            
                            input.addEventListener('blur', function() {
                                setPlaceholder(edit);
                            });
                        }
                        
                        /**
                         * Убрать placeholder
                         */
                        function unsetPlaceholder(input) {
                            if (input._placeholder && input.value === input._placeholder) {
                                if (input._isPassword) {
                                    try {
                                        input.type = 'password';
                                    } catch (e) {
                                        // Игнорируем ошибки смены типа
                                    }
                                }
                                input.value = '';
                                input.classList.remove('_placeholder');
                            }
                        }
                        
                        // Расширение D3Api
                        if (typeof D3Api !== 'undefined') {
                            
                            // Создаем объект EditCtrl
                            D3Api.EditCtrl = {
                                decimalSeparator: (1.1).toLocaleString().substring(1, 2),
                                thousandSeparator: (1000).toLocaleString().substring(1, 2),
                                
                                init: function(dom) {
                                    var input = getInput(dom);
                                    this.init_focus(input);
                                },
                                
                                getInput: getInput,
                                
                                /**
                                 * Форматирование значения
                                 */
                                format: function(dom, settings, value) {
                                    var ev = D3Api.getEvent();
                                    var eventType = ev && ev.type ? ev.type : 'other';
                                    
                                    if (settings.toType === 'number') {
                                        this.formatNumber(dom, settings, value, eventType);
                                    } else if (settings.toType === 'date') {
                                        this.formatDate(dom, settings, value, eventType);
                                    } else if (settings.toType === 'hours') {
                                        this.formatHours(dom, settings, value, eventType);
                                    }
                                },
                                
                                formatNumber: function(dom, settings, value, eventType) {
                                    if (value) {
                                        // Преобразуем строку к числу
                                        value = String(value).replace(/\\s*/g, '');
                                        value = String(value).replace(new RegExp('\\\\' + this.thousandSeparator, 'g'), '');
                                        value = String(value).replace(new RegExp('\\\\' + this.decimalSeparator, 'g'), '.');
                                        
                                        if (settings.hideZero && Number(value) === 0) {
                                            dom._internalValue = 0;
                                            dom._formattedValue = '';
                                            dom._internalFormatted = String(Number(value)).replace(/\\./g, this.decimalSeparator);
                                        } else {
                                            dom._internalValue = Number.isFinite(Number(value)) ? value : null;
                                            dom._formattedValue = Number.isFinite(Number(value)) ? 
                                                Number(value).toLocaleString(settings.locales, settings.options) : undefined;
                                            dom._internalFormatted = Number.isFinite(Number(value)) ? 
                                                value.replace(/\\./g, this.decimalSeparator) : undefined;
                                        }
                                    } else {
                                        dom._internalValue = Number.isFinite(value) ? value : null;
                                        if (!Number.isFinite(value) && settings.showNull || Number.isFinite(value) && !settings.hideZero) {
                                            dom._formattedValue = Number(0).toLocaleString(settings.locales, settings.options);
                                        } else {
                                            dom._formattedValue = '';
                                        }
                                        dom._internalFormatted = Number.isFinite(value) ? 
                                            String(Number(value)).replace(/\\./g, this.decimalSeparator) : undefined;
                                    }
                                },
                                
                                formatDate: function(dom, settings, value, eventType) {
                                    // Реализация форматирования даты
                                    if (value) {
                                        value = String(value).trim();
                                        var regex = /^(\\d{2})\\.(\\d{2})\\.(\\d{4})(?:\\s(\\d{2})(?::(\\d{2})(?::(\\d{2})(?:\\.(\\d{6}))?)?)?)?$/;
                                        
                                        if (regex.test(value)) {
                                            var dateMatch = value.match(/^(\\d{2})\\.(\\d{2})\\.(\\d{4})(?:\\s(\\d{2}):(\\d{2}):(\\d{2})|)/);
                                            var valueDate = new Date(dateMatch[3], dateMatch[2] - 1, dateMatch[1], 
                                                dateMatch[4] || 0, dateMatch[5] || 0, dateMatch[6] || 0);
                                            
                                            dom._internalValue = value;
                                            
                                            if (settings.mask) {
                                                dom._formattedValue = this.parseDate(settings.mask, valueDate / 1000);
                                            } else {
                                                dom._formattedValue = valueDate.toLocaleString(settings.locales, settings.options);
                                            }
                                            dom._internalFormatted = value;
                                        }
                                    } else {
                                        dom._internalValue = undefined;
                                        dom._formattedValue = '';
                                        dom._internalFormatted = '';
                                    }
                                },
                                
                                formatHours: function(dom, settings, value, eventType) {
                                    if (value) {
                                        if (eventType === 'focus') {
                                            dom._internalFormatted = this.hours2time(dom._internalValue, settings.withSeconds);
                                            return;
                                        } else if (eventType === 'blur') {
                                            value = String(value).trim();
                                            var regex = /^(\\d{1,})(?::(\\d{1,})(?::(\\d{1,}))?)?$/;
                                            
                                            if (regex.test(value)) {
                                                var match = value.match(/^(\\d{1,})(?::(\\d{1,})(?::(\\d{1,}))?)?/);
                                                dom._internalValue = +(match[1] | 0) + (match[2] | 0) / 60 + (match[3] | 0) / 3600;
                                                dom._formattedValue = this.hours2time(dom._internalValue, settings.withSeconds);
                                                dom._internalFormatted = dom._formattedValue;
                                            }
                                        } else {
                                            dom._internalValue = value;
                                            dom._formattedValue = this.hours2time(value, settings.withSeconds);
                                            dom._internalFormatted = dom._formattedValue;
                                        }
                                    } else {
                                        dom._internalValue = undefined;
                                        dom._formattedValue = '';
                                        dom._internalFormatted = '';
                                    }
                                },
                                
                                hours2time: function(hours, withSeconds) {
                                    if (hours === undefined || hours === null) return '';
                                    var h = Math.floor(hours);
                                    var m = Math.floor((hours - h) * 60);
                                    var s = Math.floor(((hours - h) * 60 - m) * 60);
                                    
                                    var result = h.toString().padStart(2, '0') + ':' + 
                                                 m.toString().padStart(2, '0');
                                    if (withSeconds) {
                                        result += ':' + s.toString().padStart(2, '0');
                                    }
                                    return result;
                                },
                                
                                parseDate: function(mask, timestamp) {
                                    var date = new Date(timestamp * 1000);
                                    return mask.replace(/[dmYHis]/g, function(match) {
                                        switch (match) {
                                            case 'd': return date.getDate().toString().padStart(2, '0');
                                            case 'm': return (date.getMonth() + 1).toString().padStart(2, '0');
                                            case 'Y': return date.getFullYear();
                                            case 'H': return date.getHours().toString().padStart(2, '0');
                                            case 'i': return date.getMinutes().toString().padStart(2, '0');
                                            case 's': return date.getSeconds().toString().padStart(2, '0');
                                            default: return match;
                                        }
                                    });
                                },
                                
                                setPlaceholder: setPlaceholder,
                                
                                getValue: function(dom) {
                                    var input = getInput(dom);
                                    var res = input.value;
                                    
                                    if (dom.D3Base && dom.D3Base.events && dom.D3Base.events['onformat'] && 
                                        dom.D3Store && dom.D3Store._properties_) {
                                        res = dom.D3Store._properties_.value;
                                    }
                                    
                                    if (dom.D3Store && dom.D3Store.trim) {
                                        res = res.trim();
                                    }
                                    
                                    return res;
                                },
                                
                                setValue: function(dom, value) {
                                    if (value === undefined) value = null;
                                    
                                    dom.D3Store = dom.D3Store || {};
                                    dom.D3Store._properties_ = dom.D3Store._properties_ || {};
                                    dom.D3Store._properties_.value = value;
                                    
                                    this.setCaption(dom, value);
                                },
                                
                                getCaption: function(dom) {
                                    var input = getInput(dom);
                                    if (!input) return '';
                                    
                                    if (input._placeholder && input.value === input._placeholder) {
                                        return '';
                                    }
                                    return input.value || '';
                                },
                                
                                setCaption: function(dom, value) {
                                    var input = getInput(dom);
                                    if (!input) return;
                                    
                                    unsetPlaceholder(input);
                                    
                                    if (dom.D3Base && dom.D3Base.events && dom.D3Base.events['onformat']) {
                                        dom.D3Base.callEvent('onformat', value);
                                        
                                        dom.D3Store = dom.D3Store || {};
                                        dom.D3Store._properties_ = dom.D3Store._properties_ || {};
                                        if (dom._internalValue !== undefined) {
                                            dom.D3Store._properties_.value = dom._internalValue;
                                        } else {
                                            dom.D3Store._properties_.value = value;
                                        }
                                        
                                        if (dom._formattedValue !== undefined) {
                                            value = dom._formattedValue;
                                        }
                                    }
                                    
                                    input.value = value || '';
                                    setPlaceholder(dom);
                                },
                                
                                setEnabled: function(dom, value) {
                                    var input = getInput(dom);
                                    if (value) {
                                        input.removeAttribute('disabled');
                                    } else {
                                        input.setAttribute('disabled', 'disabled');
                                    }
                                },
                                
                                getReadonly: function(dom) {
                                    var input = getInput(dom);
                                    return input.hasAttribute('readonly');
                                },
                                
                                setReadonly: function(dom, value) {
                                    var input = getInput(dom);
                                    if (value) {
                                        input.setAttribute('readonly', 'readonly');
                                    } else {
                                        input.removeAttribute('readonly');
                                    }
                                }
                            };
                            
                            // Регистрируем API для Edit
                            D3Api.controlsApi = D3Api.controlsApi || {};
                            D3Api.controlsApi['Edit'] = new D3Api.ControlBaseProperties(D3Api.EditCtrl);
                            D3Api.controlsApi['Edit']['height'] = undefined;
                            D3Api.controlsApi['Edit']['value'] = {
                                get: D3Api.EditCtrl.getValue,
                                set: D3Api.EditCtrl.setValue
                            };
                            D3Api.controlsApi['Edit']['caption'] = {
                                get: D3Api.EditCtrl.getCaption,
                                set: D3Api.EditCtrl.setCaption
                            };
                            D3Api.controlsApi['Edit']['enabled'] = {
                                set: D3Api.EditCtrl.setEnabled
                            };
                            D3Api.controlsApi['Edit']['input'] = {
                                get: D3Api.EditCtrl.getInput,
                                type: 'dom'
                            };
                            D3Api.controlsApi['Edit']['readonly'] = {
                                get: D3Api.EditCtrl.getReadonly,
                                set: D3Api.EditCtrl.setReadonly
                            };
                            D3Api.controlsApi['Edit']['placeholder'] = {
                                set: D3Api.EditCtrl.setPlaceholder
                            };
                        }
                        
                        // Инициализация после загрузки DOM
                        if (document.readyState === 'loading') {
                            document.addEventListener('DOMContentLoaded', function() {
                                initEdits();
                            });
                        } else {
                            initEdits();
                        }
                    }
                    
                    waitForD3Api(initialize);
                })();
                """);

        return js.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}