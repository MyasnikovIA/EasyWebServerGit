package ru.miacomsoft.EasyWebServer.component;

import ru.miacomsoft.EasyWebServer.HttpExchange;

/**
 * CSS библиотека для компонента cmpScript
 * В основном скрывает textarea, используемую как контейнер для скрипта
 */
public class cmpScript_css {

    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "text/css";

        StringBuilder css = new StringBuilder();
        css.append("""
                /* Скрываем textarea, используемую для хранения скрипта */
                textarea[cmptype="Script"],
                [cmptype="Script"] {
                    display: none !important;
                    visibility: hidden !important;
                    width: 0 !important;
                    height: 0 !important;
                    position: absolute !important;
                    top: -9999px !important;
                    left: -9999px !important;
                    pointer-events: none !important;
                    opacity: 0 !important;
                }
                
                /* Индикатор загрузки внешнего скрипта (опционально) */
                .script-loading {
                    position: relative;
                    display: inline-block;
                    width: 16px;
                    height: 16px;
                    border: 2px solid #f3f3f3;
                    border-top: 2px solid #3498db;
                    border-radius: 50%;
                    animation: script-spin 1s linear infinite;
                    margin-left: 5px;
                    vertical-align: middle;
                }
                
                @keyframes script-spin {
                    0% { transform: rotate(0deg); }
                    100% { transform: rotate(360deg); }
                }
                
                /* Ошибка загрузки скрипта */
                .script-error {
                    color: #e74c3c;
                    font-size: 12px;
                    padding: 2px 5px;
                    border-left: 3px solid #e74c3c;
                    background: #fdf3f2;
                    margin: 5px 0;
                }
                """);

        return css.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}