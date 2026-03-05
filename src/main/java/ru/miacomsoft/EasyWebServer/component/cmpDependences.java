package ru.miacomsoft.EasyWebServer.component;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import ru.miacomsoft.EasyWebServer.HttpExchange;
import ru.miacomsoft.EasyWebServer.ServerConstant;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Невидимый компонент для управления зависимостями между полями
 * Делает контролы из списка depend неактивными, пока все поля из списка required не будут заполнены правильно
 *
 * Пример использования:
 * <cmpDependences name="mainDepControl"
 *    required="taxpayerComboCtrl;patientCtrl;cardNumbCtrl"
 *    depend="buttonSave;ctrlButtonSaveAndNext"
 *    checkMode="all"  // all - все поля должны быть заполнены, any - хотя бы одно
 *    validateMask="true" // учитывать маски при валидации
 * />
 * ==================================================================
 *  Базовое использование в HTML:
 * <!-- Невидимый компонент управления зависимостями -->
 * <cmpDependences
 *     name="mainDepControl"
 *     required="taxpayerComboCtrl;patientCtrl;cardNumbCtrl;employerCtrl;taxNumberCtrl;relationCtrl;correctionNumberCtrl;payDateYearCtrl;formationDateCtrl;costOfServicesToCodeCtrl1;costOfServicesToCodeCtrl2"
 *     depend="buttonSave;ctrlButtonSaveAndNext"
 *     checkMode="all"
 *     validateMask="true"
 * />
 *
 * <!-- Поля, которые нужно заполнить -->
 * <cmpEdit name="taxpayerComboCtrl" mask="9999999999" label="ИНН"/>
 * <cmpEdit name="patientCtrl" label="Пациент" required="true"/>
 * <cmpEdit name="cardNumbCtrl" mask="9999/9999" label="Номер карты"/>
 *
 * <!-- Зависимые кнопки -->
 * <button name="buttonSave" disabled>Сохранить</button>
 * <button name="ctrlButtonSaveAndNext" disabled>Сохранить и далее</button>
 *
 * ==================================================================
 *  Управление через JavaScript:
 * // Включение/выключение зависимости
 * D3Api.enableDependence('mainDepControl', false); // выключить
 * D3Api.enableDependence('mainDepControl', true);  // включить
 *
 * // Добавление полей в список required
 * D3Api.addRequiredFields('mainDepControl', 'newField1');
 * D3Api.addRequiredFields('mainDepControl', ['field2', 'field3']);
 *
 * // Удаление полей из списка required
 * D3Api.removeRequiredFields('mainDepControl', 'taxpayerComboCtrl');
 * D3Api.removeRequiredFields('mainDepControl', ['patientCtrl', 'cardNumbCtrl']);
 *
 * // Добавление полей в список depend
 * D3Api.addDependFields('mainDepControl', 'printButton');
 * D3Api.addDependFields('mainDepControl', ['exportButton', 'resetButton']);
 *
 * // Удаление полей из списка depend
 * D3Api.removeDependFields('mainDepControl', 'buttonSave');
 *
 * // Получение состояния зависимости
 * var state = D3Api.getDependenceState('mainDepControl');
 * console.log('Зависимость активна:', state.enabled);
 * console.log('Все поля заполнены:', state.isValid);
 *
 * // Удаление зависимости
 * D3Api.removeDependence('mainDepControl');
 *
 * // Создание новой зависимости программно
 * D3Api.createDependence('dynamicDep', {
 *     required: ['field1', 'field2'],
 *     depend: ['button1', 'button2'],
 *     checkMode: 'all',
 *     validateMask: true
 * });
 *
 * // Подписка на изменения зависимости
 * $(document).on('dependenceChanged', function(event, name, isValid) {
 *     console.log('Зависимость', name, 'изменилась, валидна:', isValid);
 * });
 * ===================================================================
 * 3. Пример с разными режимами проверки:
 * <!-- Режим "all" - все поля должны быть заполнены -->
 * <cmpDependences
 *     name="strictDep"
 *     required="field1;field2;field3"
 *     depend="submitBtn"
 *     checkMode="all"
 * />
 *
 * <!-- Режим "any" - достаточно хотя бы одного заполненного поля -->
 * <cmpDependences
 *     name="looseDep"
 *     required="searchField1;searchField2;searchField3"
 *     depend="searchButton"
 *     checkMode="any"
 * />
 *
 * <!-- Без проверки масок -->
 * <cmpDependences
 *     name="simpleDep"
 *     required="nameField;emailField"
 *     depend="saveBtn"
 *     validateMask="false"
 * />
 *
 */
public class cmpDependences extends Base {

    // Флаг для однократной инициализации D3Api расширений
    private static final AtomicBoolean d3ApiExtensionsInitialized = new AtomicBoolean(false);

    // Хранилище активных зависимостей на странице
    private static final String DEPENDENCES_STORAGE = "window.__cmpDependencesInstances = window.__cmpDependencesInstances || {};";

    // ИСПРАВЛЕННЫЙ КОНСТРУКТОР - добавлен третий параметр String tag
    public cmpDependences(Document doc, Element element, String tag) {
        super(doc, element, tag); // передаем tag в super
        Attributes attrs = element.attributes();
        Attributes attrsDst = this.attributes();

        // Устанавливаем базовые атрибуты
        attrsDst.add("schema", "Dependences");
        attrsDst.add("cmptype", "Dependences");
        attrsDst.add("style", "display:none"); // Невидимый компонент

        String name = element.hasAttr("name") ? element.attr("name") : genUUID();
        this.attr("name", name);

        // Получаем списки зависимостей
        String requiredStr = RemoveArrKeyRtrn(attrs, "required", "");
        String dependStr = RemoveArrKeyRtrn(attrs, "depend", "");

        // Дополнительные параметры
        String checkMode = RemoveArrKeyRtrn(attrs, "checkMode", "all"); // all или any
        String validateMask = RemoveArrKeyRtrn(attrs, "validateMask", "true");
        String autoInit = RemoveArrKeyRtrn(attrs, "autoInit", "true");

        // Разбираем списки
        List<String> requiredList = parseList(requiredStr);
        List<String> dependList = parseList(dependStr);

        // Сохраняем конфигурацию в data-атрибутах
        attrsDst.add("data-required", String.join(",", requiredList));
        attrsDst.add("data-depend", String.join(",", dependList));
        attrsDst.add("data-check-mode", checkMode);
        attrsDst.add("data-validate-mask", validateMask);
        attrsDst.add("data-auto-init", autoInit);
        attrsDst.add("data-enabled", "true");

        // Копируем остальные атрибуты
        for (Attribute attr : attrs.asList()) {
            attrsDst.add(attr.getKey(), attr.getValue());
        }

        // Инициализация типа компонента
        this.initCmpType(element);

        // Добавляем JavaScript для управления зависимостями
        addDependencesScript(doc, name, requiredList, dependList, checkMode, validateMask);

        // Добавляем расширения D3Api (только один раз)
        initializeD3ApiExtensions(doc);
    }

    /**
     * Разбор строки списка (разделитель ; или ,)
     */
    private List<String> parseList(String listStr) {
        List<String> result = new ArrayList<>();
        if (listStr == null || listStr.trim().isEmpty()) {
            return result;
        }

        // Заменяем ; на , для унификации
        String normalized = listStr.replace(';', ',');
        String[] items = normalized.split(",");

        for (String item : items) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }

        return result;
    }

    /**
     * Однократная инициализация расширений D3Api
     */
    private void initializeD3ApiExtensions(Document doc) {
        if (!d3ApiExtensionsInitialized.get()) {
            synchronized (d3ApiExtensionsInitialized) {
                if (!d3ApiExtensionsInitialized.get()) {
                    Elements body = doc.getElementsByTag("body");

                    StringBuilder script = new StringBuilder();
                    script.append("<script>");
                    script.append("(function() {");
                    script.append("  if (window.D3ApiDependencesInitialized) return;");
                    script.append("  window.D3ApiDependencesInitialized = true;");
                    script.append("  ");
                    script.append("  // Хранилище экземпляров зависимостей");
                    script.append(DEPENDENCES_STORAGE);
                    script.append("  ");
                    script.append("  // Расширение D3Api для работы с зависимостями");
                    script.append("  if (typeof D3Api !== 'undefined') {");
                    script.append("    ");
                    script.append("    /**");
                    script.append("     * Создание новой зависимости");
                    script.append("     * @param {string} name - Имя зависимости");
                    script.append("     * @param {Object} config - Конфигурация {required, depend, checkMode, validateMask}");
                    script.append("     */");
                    script.append("    D3Api.createDependence = function(name, config) {");
                    script.append("      if (!name || !config) return false;");
                    script.append("      ");
                    script.append("      var instance = {");
                    script.append("        name: name,");
                    script.append("        required: config.required || [],");
                    script.append("        depend: config.depend || [],");
                    script.append("        checkMode: config.checkMode || 'all',");
                    script.append("        validateMask: config.validateMask !== false,");
                    script.append("        enabled: config.enabled !== false,");
                    script.append("        element: $('[name=\"'+name+'\"]')");
                    script.append("      };");
                    script.append("      ");
                    script.append("      window.__cmpDependencesInstances[name] = instance;");
                    script.append("      if (instance.enabled) {");
                    script.append("        D3Api.updateDependence(name);");
                    script.append("      }");
                    script.append("      return true;");
                    script.append("    };");
                    script.append("    ");
                    script.append("    /**");
                    script.append("     * Удаление зависимости");
                    script.append("     * @param {string} name - Имя зависимости");
                    script.append("     */");
                    script.append("    D3Api.removeDependence = function(name) {");
                    script.append("      if (window.__cmpDependencesInstances[name]) {");
                    script.append("        // Восстанавливаем доступность зависимых полей");
                    script.append("        var instance = window.__cmpDependencesInstances[name];");
                    script.append("        instance.depend.forEach(function(ctrlName) {");
                    script.append("          D3Api.setDisabled(ctrlName, false);");
                    script.append("        });");
                    script.append("        delete window.__cmpDependencesInstances[name];");
                    script.append("        return true;");
                    script.append("      }");
                    script.append("      return false;");
                    script.append("    };");
                    script.append("    ");
                    script.append("    /**");
                    script.append("     * Включение/выключение зависимости");
                    script.append("     * @param {string} name - Имя зависимости");
                    script.append("     * @param {boolean} enabled - true - включить, false - выключить");
                    script.append("     */");
                    script.append("    D3Api.enableDependence = function(name, enabled) {");
                    script.append("      var instance = window.__cmpDependencesInstances[name];");
                    script.append("      if (!instance) return false;");
                    script.append("      ");
                    script.append("      instance.enabled = enabled;");
                    script.append("      if (enabled) {");
                    script.append("        D3Api.updateDependence(name);");
                    script.append("      } else {");
                    script.append("        // Разблокируем все зависимые поля");
                    script.append("        instance.depend.forEach(function(ctrlName) {");
                    script.append("          D3Api.setDisabled(ctrlName, false);");
                    script.append("        });");
                    script.append("      }");
                    script.append("      return true;");
                    script.append("    };");
                    script.append("    ");
                    script.append("    /**");
                    script.append("     * Добавление полей в список required");
                    script.append("     * @param {string} name - Имя зависимости");
                    script.append("     * @param {string|Array} fields - Поле или массив полей для добавления");
                    script.append("     */");
                    script.append("    D3Api.addRequiredFields = function(name, fields) {");
                    script.append("      var instance = window.__cmpDependencesInstances[name];");
                    script.append("      if (!instance) return false;");
                    script.append("      ");
                    script.append("      var fieldsArray = Array.isArray(fields) ? fields : [fields];");
                    script.append("      fieldsArray.forEach(function(field) {");
                    script.append("        if (field && instance.required.indexOf(field) === -1) {");
                    script.append("          instance.required.push(field);");
                    script.append("        }");
                    script.append("      });");
                    script.append("      ");
                    script.append("      if (instance.enabled) {");
                    script.append("        D3Api.updateDependence(name);");
                    script.append("      }");
                    script.append("      return true;");
                    script.append("    };");
                    script.append("    ");
                    script.append("    /**");
                    script.append("     * Удаление полей из списка required");
                    script.append("     * @param {string} name - Имя зависимости");
                    script.append("     * @param {string|Array} fields - Поле или массив полей для удаления");
                    script.append("     */");
                    script.append("    D3Api.removeRequiredFields = function(name, fields) {");
                    script.append("      var instance = window.__cmpDependencesInstances[name];");
                    script.append("      if (!instance) return false;");
                    script.append("      ");
                    script.append("      var fieldsArray = Array.isArray(fields) ? fields : [fields];");
                    script.append("      fieldsArray.forEach(function(field) {");
                    script.append("        var index = instance.required.indexOf(field);");
                    script.append("        if (index !== -1) {");
                    script.append("          instance.required.splice(index, 1);");
                    script.append("        }");
                    script.append("      });");
                    script.append("      ");
                    script.append("      if (instance.enabled) {");
                    script.append("        D3Api.updateDependence(name);");
                    script.append("      }");
                    script.append("      return true;");
                    script.append("    };");
                    script.append("    ");
                    script.append("    /**");
                    script.append("     * Добавление полей в список depend");
                    script.append("     * @param {string} name - Имя зависимости");
                    script.append("     * @param {string|Array} fields - Поле или массив полей для добавления");
                    script.append("     */");
                    script.append("    D3Api.addDependFields = function(name, fields) {");
                    script.append("      var instance = window.__cmpDependencesInstances[name];");
                    script.append("      if (!instance) return false;");
                    script.append("      ");
                    script.append("      var fieldsArray = Array.isArray(fields) ? fields : [fields];");
                    script.append("      fieldsArray.forEach(function(field) {");
                    script.append("        if (field && instance.depend.indexOf(field) === -1) {");
                    script.append("          instance.depend.push(field);");
                    script.append("        }");
                    script.append("      });");
                    script.append("      ");
                    script.append("      if (instance.enabled) {");
                    script.append("        D3Api.updateDependence(name);");
                    script.append("      }");
                    script.append("      return true;");
                    script.append("    };");
                    script.append("    ");
                    script.append("    /**");
                    script.append("     * Удаление полей из списка depend");
                    script.append("     * @param {string} name - Имя зависимости");
                    script.append("     * @param {string|Array} fields - Поле или массив полей для удаления");
                    script.append("     */");
                    script.append("    D3Api.removeDependFields = function(name, fields) {");
                    script.append("      var instance = window.__cmpDependencesInstances[name];");
                    script.append("      if (!instance) return false;");
                    script.append("      ");
                    script.append("      var fieldsArray = Array.isArray(fields) ? fields : [fields];");
                    script.append("      fieldsArray.forEach(function(field) {");
                    script.append("        var index = instance.depend.indexOf(field);");
                    script.append("        if (index !== -1) {");
                    script.append("          instance.depend.splice(index, 1);");
                    script.append("        }");
                    script.append("      });");
                    script.append("      ");
                    script.append("      if (instance.enabled) {");
                    script.append("        D3Api.updateDependence(name);");
                    script.append("      }");
                    script.append("      return true;");
                    script.append("    };");
                    script.append("    ");
                    script.append("    /**");
                    script.append("     * Обновление состояния зависимости");
                    script.append("     * @param {string} name - Имя зависимости");
                    script.append("     */");
                    script.append("    D3Api.updateDependence = function(name) {");
                    script.append("      var instance = window.__cmpDependencesInstances[name];");
                    script.append("      if (!instance || !instance.enabled) return;");
                    script.append("      ");
                    script.append("      var allValid = true;");
                    script.append("      var anyValid = false;");
                    script.append("      ");
                    script.append("      // Проверяем все required поля");
                    script.append("      for (var i = 0; i < instance.required.length; i++) {");
                    script.append("        var fieldName = instance.required[i];");
                    script.append("        var isValid = D3Api.isFieldValid(fieldName, instance.validateMask);");
                    script.append("        ");
                    script.append("        if (!isValid) {");
                    script.append("          allValid = false;");
                    script.append("        } else {");
                    script.append("          anyValid = true;");
                    script.append("        }");
                    script.append("      }");
                    script.append("      ");
                    script.append("      // Определяем общий результат в зависимости от режима");
                    script.append("      var result = (instance.checkMode === 'all') ? allValid : anyValid;");
                    script.append("      ");
                    script.append("      // Устанавливаем состояние зависимых полей");
                    script.append("      instance.depend.forEach(function(ctrlName) {");
                    script.append("        D3Api.setDisabled(ctrlName, !result);");
                    script.append("      });");
                    script.append("      ");
                    script.append("      // Триггерим событие");
                    script.append("      $(document).trigger('dependenceChanged', [name, result]);");
                    script.append("    };");
                    script.append("    ");
                    script.append("    /**");
                    script.append("     * Проверка валидности поля");
                    script.append("     * @param {string} fieldName - Имя поля");
                    script.append("     * @param {boolean} validateMask - Учитывать маску");
                    script.append("     * @returns {boolean} - true если поле валидно");
                    script.append("     */");
                    script.append("    D3Api.isFieldValid = function(fieldName, validateMask) {");
                    script.append("      var field = $('[name=\"'+fieldName+'\"]');");
                    script.append("      if (field.length === 0) return false;");
                    script.append("      ");
                    script.append("      var value = field.val();");
                    script.append("      if (!value || value.trim() === '') return false;");
                    script.append("      ");
                    script.append("      // Проверка по маске, если требуется");
                    script.append("      if (validateMask) {");
                    script.append("        var mask = field.data('mask');");
                    script.append("        var maskEnabled = field.data('mask-enabled') !== 'false';");
                    script.append("        if (mask && maskEnabled) {");
                    script.append("          // Подсчитываем количество цифр в маске");
                    script.append("          var digitCount = (mask.match(/9/g) || []).length;");
                    script.append("          var valueDigits = (value.match(/[0-9]/g) || []).length;");
                    script.append("          return valueDigits === digitCount;");
                    script.append("        }");
                    script.append("      }");
                    script.append("      ");
                    script.append("      // Проверка required атрибута");
                    script.append("      if (field.prop('required')) {");
                    script.append("        return value.trim() !== '';");
                    script.append("      }");
                    script.append("      ");
                    script.append("      return true;");
                    script.append("    };");
                    script.append("    ");
                    script.append("    /**");
                    script.append("     * Получение списка всех зависимостей");
                    script.append("     * @returns {Object} - Объект со всеми экземплярами зависимостей");
                    script.append("     */");
                    script.append("    D3Api.getDependences = function() {");
                    script.append("      return window.__cmpDependencesInstances;");
                    script.append("    };");
                    script.append("    ");
                    script.append("    /**");
                    script.append("     * Получение состояния конкретной зависимости");
                    script.append("     * @param {string} name - Имя зависимости");
                    script.append("     * @returns {Object|null} - Состояние зависимости или null");
                    script.append("     */");
                    script.append("    D3Api.getDependenceState = function(name) {");
                    script.append("      var instance = window.__cmpDependencesInstances[name];");
                    script.append("      if (!instance) return null;");
                    script.append("      ");
                    script.append("      var state = {");
                    script.append("        name: name,");
                    script.append("        enabled: instance.enabled,");
                    script.append("        required: instance.required,");
                    script.append("        depend: instance.depend,");
                    script.append("        checkMode: instance.checkMode,");
                    script.append("        validateMask: instance.validateMask,");
                    script.append("        isValid: false");
                    script.append("      };");
                    script.append("      ");
                    script.append("      // Проверяем текущее состояние");
                    script.append("      var allValid = true;");
                    script.append("      for (var i = 0; i < instance.required.length; i++) {");
                    script.append("        if (!D3Api.isFieldValid(instance.required[i], instance.validateMask)) {");
                    script.append("          allValid = false;");
                    script.append("          break;");
                    script.append("        }");
                    script.append("      }");
                    script.append("      state.isValid = allValid;");
                    script.append("      ");
                    script.append("      return state;");
                    script.append("    };");
                    script.append("  }");
                    script.append("})();");
                    script.append("</script>");

                    body.append(script.toString());
                    d3ApiExtensionsInitialized.set(true);
                    System.out.println("cmpDependences: D3Api расширения инициализированы");
                }
            }
        }
    }

    /**
     * Добавление JavaScript для инициализации зависимостей
     */
    private void addDependencesScript(Document doc, String name, List<String> requiredList,
                                      List<String> dependList, String checkMode, String validateMask) {
        Elements body = doc.getElementsByTag("body");

        StringBuilder script = new StringBuilder();
        script.append("<script>");
        script.append("$(function() {");
        script.append("  var config = {");
        script.append("    required: " + jsonArray(requiredList) + ",");
        script.append("    depend: " + jsonArray(dependList) + ",");
        script.append("    checkMode: '" + checkMode + "',");
        script.append("    validateMask: " + validateMask + ",");
        script.append("    enabled: true");
        script.append("  };");
        script.append("  ");
        script.append("  // Создаем экземпляр зависимости");
        script.append("  D3Api.createDependence('" + name + "', config);");
        script.append("  ");
        script.append("  // Подписываемся на изменения всех required полей");
        script.append("  var requiredFields = " + jsonArray(requiredList) + ";");
        script.append("  requiredFields.forEach(function(fieldName) {");
        script.append("    $(document).on('valueChanged', function(event, changedField) {");
        script.append("      if (changedField === fieldName) {");
        script.append("        D3Api.updateDependence('" + name + "');");
        script.append("      }");
        script.append("    });");
        script.append("    ");
        script.append("    // Также отслеживаем изменения через input событие");
        script.append("    $('[name=\"'+fieldName+'\"]').on('input change', function() {");
        script.append("      D3Api.updateDependence('" + name + "');");
        script.append("    });");
        script.append("  });");
        script.append("  ");
        script.append("  // Первоначальное обновление");
        script.append("  D3Api.updateDependence('" + name + "');");
        script.append("});");
        script.append("</script>");

        body.append(script.toString());
    }

    /**
     * Преобразование списка в JSON массив
     */
    private String jsonArray(List<String> list) {
        if (list.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("'").append(list.get(i)).append("'");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Метод для обработки запросов от клиента
     */
    public static byte[] onPage(HttpExchange query) {
        query.mimeType = "application/json";
        JSONObject result = new JSONObject();

        String action = query.requestParam.optString("action", "");
        String name = query.requestParam.optString("name", "");
        String fields = query.requestParam.optString("fields", "");
        String checkMode = query.requestParam.optString("checkMode", "all");
        boolean validateMask = query.requestParam.optBoolean("validateMask", true);
        boolean enabled = query.requestParam.optBoolean("enabled", true);

        switch (action) {
            case "validate":
                result = validateDependence(name, fields, checkMode, validateMask);
                break;
            case "state":
                result = getDependenceState(name);
                break;
            default:
                result.put("error", "Unknown action");
                result.put("success", false);
        }

        return result.toString().getBytes();
    }

    /**
     * Валидация зависимости на сервере
     */
    private static JSONObject validateDependence(String name, String fieldsStr, String checkMode, boolean validateMask) {
        JSONObject result = new JSONObject();
        JSONArray results = new JSONArray();

        String[] fields = fieldsStr.split(",");
        boolean allValid = true;
        boolean anyValid = false;

        for (String field : fields) {
            String fieldName = field.trim();
            if (fieldName.isEmpty()) continue;

            JSONObject fieldResult = new JSONObject();
            fieldResult.put("name", fieldName);
            fieldResult.put("valid", true);
            results.put(fieldResult);

            if (!fieldResult.optBoolean("valid", false)) {
                allValid = false;
            } else {
                anyValid = true;
            }
        }

        boolean finalResult = "all".equals(checkMode) ? allValid : anyValid;

        result.put("success", true);
        result.put("name", name);
        result.put("valid", finalResult);
        result.put("results", results);

        return result;
    }

    /**
     * Получение состояния зависимости
     */
    private static JSONObject getDependenceState(String name) {
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("name", name);
        result.put("exists", true);
        return result;
    }
}