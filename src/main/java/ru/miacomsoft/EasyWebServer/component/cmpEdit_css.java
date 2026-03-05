package ru.miacomsoft.EasyWebServer.component;

import ru.miacomsoft.EasyWebServer.HttpExchange;

/**
 * CSS библиотека для компонента cmpEdit
 * Предоставляет стили для отображения полей ввода
 */
public class cmpEdit_css {

    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "text/css";

        StringBuilder css = new StringBuilder();
        css.append("""
    /* Базовые стили поля ввода - ФИКСИРОВАННАЯ ВЫСОТА */
    .ctrl_edit,
    [cmptype="Edit"] {
        display: inline-block;
        position: relative;
        border-radius: 3px;
        border: 1px solid #ccc;
        background: white;
        height: 28px !important;        /* Фиксированная высота */
        min-height: 28px !important;     /* Минимальная высота */
        max-height: 28px !important;     /* Максимальная высота */
        min-width: 100px;
        box-sizing: border-box;
        vertical-align: middle;
        flex-shrink: 0;                  /* Запрещаем сжатие */
        flex-grow: 0;                     /* Запрещаем растягивание */
    }
    
                
    /* Input внутри Edit занимает всю высоту родителя */
    .ctrl_edit input,
    [cmptype="Edit"] input {
        height: 100% !important;
        width: 100%;
        border: none;
        background: transparent;
        padding: 4px 8px;
        font-size: 14px;
        color: #333;
        outline: none;
        box-sizing: border-box;
        border-radius: 3px;
        line-height: normal;               /* Фиксируем line-height */
    }
    
    /* Специально для textarea - можно изменять размер */
    .ctrl_edit textarea,
    [cmptype="Edit"] textarea {
        min-height: 60px;
        resize: vertical;
        height: auto !important;           /* Отменяем фиксацию для textarea */
    }
        
                
        /* Для режима полной ширины */
        .ctrl_edit.full-width {
            width: 100%;
            display: block;
        }                
                
                /* Для встраивания в строку с другими компонентами */
                .ctrl_edit.inline {
                    width: auto;
                    display: inline-block;
                }
                                                
                .ctrl_edit input:focus {
                    border-color: #66afe9;
                    box-shadow: inset 0 1px 1px rgba(0,0,0,.075), 0 0 8px rgba(102,175,233,.6);
                }
                
                .ctrl_edit input[disabled] {
                    background-color: #f5f5f5;
                    cursor: not-allowed;
                    opacity: 0.6;
                }
                
                .ctrl_edit input[readonly] {
                    background-color: #f9f9f9;
                    cursor: default;
                }
                
                /* Тема: modern */
                .ctrl_edit[data-theme="modern"] {
                    border: 1px solid #ddd;
                    border-radius: 4px;
                    transition: border-color 0.15s ease-in-out, box-shadow 0.15s ease-in-out;
                }
                
                .ctrl_edit[data-theme="modern"] input {
                    padding: 6px 10px;
                }
                
                .ctrl_edit[data-theme="modern"] input:focus {
                    border-color: #4a90e2;
                }
                
                /* Тема: compact */
                .ctrl_edit[data-theme="compact"] {
                    min-height: 22px;
                    border-radius: 2px;
                }
                
                .ctrl_edit[data-theme="compact"] input {
                    padding: 2px 4px;
                    font-size: 12px;
                }
                
                /* Тема: material */
                .ctrl_edit[data-theme="material"] {
                    border: none;
                    border-bottom: 2px solid #ddd;
                    border-radius: 0;
                    background: transparent;
                }
                
                .ctrl_edit[data-theme="material"] input {
                    padding: 8px 0;
                }
                
                .ctrl_edit[data-theme="material"] input:focus {
                    border-bottom-color: #4caf50;
                    box-shadow: none;
                }
                
                /* Состояние ошибки */
                .ctrl_edit.error {
                    border-color: #d9534f;
                }
                
                .ctrl_edit.error input {
                    color: #d9534f;
                }
                
                /* Состояние успеха */
                .ctrl_edit.success {
                    border-color: #5cb85c;
                }
                
                /* Иконки внутри поля */
                .ctrl_edit.with-icon {
                    position: relative;
                }
                
                .ctrl_edit.with-icon input {
                    padding-left: 30px;
                }
                
                .ctrl_edit.with-icon .edit-icon {
                    position: absolute;
                    left: 8px;
                    top: 50%;
                    transform: translateY(-50%);
                    color: #999;
                    font-size: 14px;
                }
                
                /* Placeholder стили для старых браузеров */
                .ctrl_edit input._placeholder {
                    color: #aaa;
                    font-style: italic;
                }
                
                /* Маска ввода */
                .ctrl_edit .mask-hint {
                    position: absolute;
                    right: 8px;
                    top: 50%;
                    transform: translateY(-50%);
                    color: #999;
                    font-size: 12px;
                    pointer-events: none;
                }
                
                /* Стили для разных типов полей */
                .ctrl_edit input[type="number"] {
                    -moz-appearance: textfield;
                }
                
                .ctrl_edit input[type="number"]::-webkit-inner-spin-button,
                .ctrl_edit input[type="number"]::-webkit-outer-spin-button {
                    -webkit-appearance: none;
                    margin: 0;
                }
                
                /* Адаптивность */
                @media (max-width: 768px) {
                    .ctrl_edit {
                        width: 100%;
                    }
                }
                
                /* Темная тема */
                .ctrl_edit[data-theme="dark"] {
                    background: #333;
                    border-color: #555;
                }
                
                .ctrl_edit[data-theme="dark"] input {
                    color: #fff;
                    background: #333;
                }
                
                .ctrl_edit[data-theme="dark"] input::placeholder {
                    color: #aaa;
                }
                
                .ctrl_edit[data-theme="dark"] input:focus {
                    border-color: #66afe9;
                }
                """);

        return css.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}