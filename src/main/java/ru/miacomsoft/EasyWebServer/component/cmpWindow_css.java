package ru.miacomsoft.EasyWebServer.component;

import ru.miacomsoft.EasyWebServer.HttpExchange;

/**
 * CSS библиотека для компонента модального окна (DIV-based версия)
 * Предоставляет стили для отображения и управления окнами
 * Использует современную flexbox верстку вместо таблиц
 */
public class cmpWindow_css {

    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "text/css";

        StringBuilder css = new StringBuilder();
        css.append("""
                /* Overlay (затемнение фона) */
                .win_overlow {
                    position: fixed;
                    top: 0;
                    left: 0;
                    right: 0;
                    bottom: 0;
                    background-color: rgba(0, 0, 0, 0.5);
                    backdrop-filter: blur(3px);
                    z-index: 9998;
                }
                
                /* Базовый контейнер окна */
                .window {
                    position: fixed;
                    display: flex;
                    flex-direction: column;
                    background: #f0f0f0;
                    border-radius: 8px;
                    box-shadow: 0 10px 30px rgba(0, 0, 0, 0.3);
                    z-index: 9999;
                    min-width: 250px;
                    min-height: 150px;
                    transition: opacity 0.2s ease-in-out;
                    opacity: 1;
                    overflow: hidden;
                    border: 1px solid #ccc;
                }
                
                .window.hidden {
                    opacity: 0;
                    pointer-events: none;
                }
                
                .window.maximized {
                    border-radius: 0;
                }
                
                /* Заголовок окна */
                .window-header {
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    background: linear-gradient(to bottom, #4a6b8f, #2c4a6f);
                    color: white;
                    padding: 0 10px;
                    height: 32px;
                    cursor: move;
                    user-select: none;
                    border-bottom: 1px solid #1a3a5a;
                    flex-shrink: 0;
                }
                
                .window-header.dragging {
                    opacity: 0.8;
                }
                
                .window-title {
                    font-weight: bold;
                    font-size: 14px;
                    white-space: nowrap;
                    overflow: hidden;
                    text-overflow: ellipsis;
                    flex: 1;
                    padding-right: 10px;
                }
                
                /* Контейнер кнопок управления */
                .window-controls {
                    display: flex;
                    gap: 5px;
                    align-items: center;
                    flex-shrink: 0;
                }
                
                /* Кнопки управления */
                .window-control {
                    width: 20px;
                    height: 20px;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    cursor: pointer;
                    border-radius: 3px;
                    color: white;
                    font-size: 16px;
                    line-height: 1;
                    transition: background-color 0.2s;
                }
                
                .window-control:hover {
                    background-color: rgba(255, 255, 255, 0.2);
                }
                
                .window-control.close:hover {
                    background-color: #c75050;
                }
                
                .window-control.maximize:hover {
                    background-color: #5a8f5a;
                }
                
                .window-control.reload:hover {
                    background-color: #5a7f9f;
                }
                
                /* Контент окна */
                .window-content {
                    flex: 1;
                    background: white;
                    overflow: auto;
                    position: relative;
                    min-height: 0;
                    padding: 0;
                }
                
                /* IFrame внутри окна */
                .window-iframe {
                    width: 100%;
                    height: 100%;
                    border: none;
                    display: block;
                    background: white;
                }
                
                /* Рамка для классической темы */
                .window.classic {
                    border-radius: 5px;
                    background: #e8e8e8;
                }
                
                .window.classic .window-header {
                    background: linear-gradient(to bottom, #5a7a9f, #3a5a7f);
                    border-radius: 5px 5px 0 0;
                    height: 28px;
                }
                
                .window.classic .window-title {
                    font-size: 13px;
                }
                
                .window.classic .window-content {
                    background: #f5f5f5;
                    border: 1px solid #ccc;
                    border-top: none;
                    margin: 0 1px 1px 1px;
                }
                
                /* Handle для изменения размера */
                .window-resize-handle {
                    position: absolute;
                    z-index: 10000;
                }
                
                .resize-n {
                    top: 0;
                    left: 5px;
                    right: 5px;
                    height: 5px;
                    cursor: n-resize;
                }
                
                .resize-s {
                    bottom: 0;
                    left: 5px;
                    right: 5px;
                    height: 5px;
                    cursor: s-resize;
                }
                
                .resize-e {
                    top: 5px;
                    right: 0;
                    bottom: 5px;
                    width: 5px;
                    cursor: e-resize;
                }
                
                .resize-w {
                    top: 5px;
                    left: 0;
                    bottom: 5px;
                    width: 5px;
                    cursor: w-resize;
                }
                
                .resize-ne {
                    top: 0;
                    right: 0;
                    width: 10px;
                    height: 10px;
                    cursor: ne-resize;
                }
                
                .resize-nw {
                    top: 0;
                    left: 0;
                    width: 10px;
                    height: 10px;
                    cursor: nw-resize;
                }
                
                .resize-se {
                    bottom: 0;
                    right: 0;
                    width: 15px;
                    height: 15px;
                    cursor: se-resize;
                }
                
                .resize-sw {
                    bottom: 0;
                    left: 0;
                    width: 10px;
                    height: 10px;
                    cursor: sw-resize;
                }
                
                /* Анимация */
                .window.animate {
                    transition: all 0.2s ease-in-out;
                }
                
                /* Состояние загрузки */
                .window-loading {
                    position: absolute;
                    top: 0;
                    left: 0;
                    right: 0;
                    bottom: 0;
                    background: rgba(255, 255, 255, 0.8);
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    z-index: 10;
                }
                
                .window-loading-spinner {
                    width: 40px;
                    height: 40px;
                    border: 4px solid #f3f3f3;
                    border-top: 4px solid #4a6b8f;
                    border-radius: 50%;
                    animation: spin 1s linear infinite;
                }
                
                @keyframes spin {
                    0% { transform: rotate(0deg); }
                    100% { transform: rotate(360deg); }
                }
                
                /* Запрет выделения при перетаскивании */
                .noselect {
                    -webkit-touch-callout: none;
                    -webkit-user-select: none;
                    -khtml-user-select: none;
                    -moz-user-select: none;
                    -ms-user-select: none;
                    user-select: none;
                }
                
                /* Legacy support - для обратной совместимости */
                .WinContent, .WinContentLeft, .WinContentRight, .WinContentTop, .WinContentBottom {
                    /* Стили для совместимости с классической версией */
                    display: none;
                }
                """);

        return css.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}