package ru.miacomsoft.EasyWebServer.component;

import ru.miacomsoft.EasyWebServer.HttpExchange;

/**
 * CSS библиотека для компонента cmpButton
 * Предоставляет стили для отображения кнопок в разных темах
 */
public class cmpButton_css {

    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "text/css";

        StringBuilder css = new StringBuilder();
        css.append("""
                /* Базовые стили кнопки */
                .ctrl_button {
                    cursor: pointer;
                    height: 28px;
                    -moz-user-select: none;
                    user-select: none;
                    display: inline-table;
                    white-space: nowrap;
                    outline: none;
                    padding-right: 8px;
                    position: relative;
                    line-height: 28px;
                    
                    background-image: none;
                    filter: progid:DXImageTransform.Microsoft.gradient(startColorstr='#F5F7F9', 
                            endColorstr='#E0E5E9',GradientType=0);
                    background-image: -webkit-gradient(linear, 0 0, 0 100%, from(#F5F7F9), to(#E0E5E9));
                    background-image: -webkit-linear-gradient(top, #F5F7F9, #E0E5E9);
                    background-image: linear-gradient(top, #F5F7F9, #E0E5E9);
                    background-image: -moz-linear-gradient(center top , #F5F7F9, #E0E5E9);
                    background-repeat: repeat-x;
                    border-spacing: 0;
                    border-color: #DDDDDD #BBBBBB #999999;
                    border-radius: 4px 4px 4px 4px;
                    border-style: solid;
                    border-width: 1px;
                    box-shadow: 0 1px 0 #FFFFFF inset;
                    color: #000000;
                    text-shadow: 0 1px 0 #FFFFFF;
                    text-indent: 0px;
                    border-collapse: separate;
                    text-align: center;
                }
                
                /* Иконка кнопки */
                .ctrl_button .btn_icon {
                    display: table-cell;
                    vertical-align: middle;
                    padding-left: 8px;
                }
                
                .ctrl_button .btn_icon_img {
                    max-width: 16px;
                    max-height: 16px;
                }
                
                /* Заголовок кнопки */
                .ctrl_button .btn_caption {
                    height: 100%;
                    line-height: inherit;
                    text-align: center;
                    padding-left: 8px;   
                }
                
                .ctrl_button .minwidth {
                    min-width: 80px; 
                }
                
                /* Тема: primary */
                .ctrl_button.primary {
                    filter: progid:DXImageTransform.Microsoft.gradient(startColorstr='#0088CC', 
                            endColorstr='#0077BB',GradientType=0);
                    background-image: -webkit-gradient(linear, 0 0, 0 100%, from(#0088CC), to(#0077BB));
                    background-image: -webkit-linear-gradient(top, #0088CC, #0077BB);
                    background-image: linear-gradient(top, #0088CC, #0077BB);
                    background-image: -moz-linear-gradient(top , #0088CC, #0077BB);
                    background-color: #006DCC;
                    background-repeat: repeat-x;
                    border-color: #40A5D7 #0077BB #003889;
                    box-shadow: 0 1px 0 #7FC2E4 inset;
                    color: #FFFFFF;
                    text-shadow: 0 -1px 0 rgba(0, 0, 0, 0.4);
                }
                
                .ctrl_button:hover.primary, .ctrl_button:active.primary, .ctrl_button.active.primary {
                    filter: none;
                    background-color: #0077BB;
                    border-color: #0077BB #0077BB #003889;
                }
                
                /* Состояния кнопки */
                .ctrl_button:hover, .ctrl_button:active, .ctrl_button.active {
                    filter: none;
                    background-color: #E0E5E9;
                    background-position: 0 -28px;
                    border-color: #BBBBBB;
                    transition: background-position 0.1s linear 0s;
                }
                
                .ctrl_button:hover .btn_caption, 
                .ctrl_button:active .btn_caption, 
                .ctrl_button.active .btn_caption {
                    color: #333333;
                }
                
                .ctrl_button.primary .btn_caption {
                    color: #FFFFFF;
                }
                
                .ctrl_button:active, .ctrl_button.active {
                    background-image: none;
                    box-shadow: 0 2px 4px rgba(0, 0, 0, 0.15) inset, 0 1px 2px rgba(0, 0, 0, 0.05);
                }
                
                /* Отключенная кнопка */
                .ctrl_button.ctrl_disable,
                .ctrl_button[disabled] {
                    filter: none;
                    border-color: #DDDDDD #BBBBBB #999999;
                    background-image: none;
                    background-color: #EDEDED !important;  
                    box-shadow: none;
                    cursor: default;
                    opacity: 0.6;
                    pointer-events: none;
                }
                
                .ctrl_button.ctrl_disable .btn_caption,
                .ctrl_button[disabled] .btn_caption {
                    color: #AAAAAA;
                }
                
                /* Только иконка */
                .ctrl_button.onlyicon .btn_caption {
                    min-width: 0;
                    width: 0;
                    padding: 0;
                }
                
                .ctrl_button.onlyicon .btn_caption:after {
                    content: "_";
                    display: block;
                    width: 0;
                    overflow: hidden;
                }
                
                /* Тема: tfoms */
                .ctrl_button[data-theme="tfoms"] {
                    cursor: pointer;
                    height: 24px;
                    display: inline-block;
                    padding-top: 2px;
                    padding-bottom: 1px;
                    
                    -webkit-border-radius: 3px;
                    -moz-border-radius: 3px;
                    border-radius: 3px;
                    background: #f4f4f4;
                    background: -moz-linear-gradient(top, #f4f4f4 67%, #f4f4f4 80%, #e5e5e5 99%);
                    background: -webkit-gradient(linear, left top, left bottom, color-stop(67%,#f4f4f4), color-stop(80%,#f4f4f4), color-stop(99%,#e5e5e5));
                    background: -webkit-linear-gradient(top, #f4f4f4 67%,#f4f4f4 80%,#e5e5e5 99%);
                    background: -o-linear-gradient(top, #f4f4f4 67%,#f4f4f4 80%,#e5e5e5 99%);
                    background: -ms-linear-gradient(top, #f4f4f4 67%,#f4f4f4 80%,#e5e5e5 99%);
                    background: linear-gradient(to bottom, #f4f4f4 67%,#f4f4f4 80%,#e5e5e5 99%);
                    filter: progid:DXImageTransform.Microsoft.gradient( startColorstr='#f4f4f4', endColorstr='#e5e5e5',GradientType=0 );
                }
                
                .ctrl_button[data-theme="tfoms"] .btn_caption {
                    color: #000000;
                    font-size: 11px;
                    line-height: 24px;
                }
                
                /* Стрелка для popup меню */
                .btn-arrow {
                    font-family: 'Font Awesome 5 Free';
                    font-weight: 900;
                    font-size: 12px;
                }
                
                /* Загрузка иконок из каталога */
                .ctrl_button .btn_icon_img[src^="default"] {
                    background: url('/lib/Components/Button/images/default/button-default-icon.png') no-repeat center;
                }
                """);

        return css.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}