package ru.miacomsoft.EasyWebServer;

import ru.miacomsoft.EasyWebServer.util.onPage;
import ru.miacomsoft.EasyWebServer.util.onTerminal;
import ru.miacomsoft.EasyWebServer.util.queryType.*;
import ru.miacomsoft.EasyWebServer.util.structObject.JavaInnerClassObject;
import ru.miacomsoft.EasyWebServer.util.structObject.JavaTerminalClassObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static ru.miacomsoft.EasyWebServer.ServerResource.ANNOTATION_TYPES;

public class PacketManager {
    private static final String[] EXCLUDED_PACKAGES = {
            "org.", "com.ctc.wstx.osgi", "net.sf.",
            "com.lowagie.bouncycastle.", "com.lowagie.text.",
            "net.sourceforge.barbecue."
    };

    /**
     * Собираем список классов у которых есть анотация WebServerLite.onPage.class на методе
     * Универсальный метод для классов и Jar файлов
     *
     * @param mainClass основной класс для определения пути
     * @return список классов с аннотациями
     */
    public static List<Class<?>> getWebPage(Class<?> mainClass) {
        String className = mainClass.getName().replace('.', '/') + ".class";
        String classPath = mainClass.getClassLoader().getResource(className).toString();

        return classPath.startsWith("jar")
                ? getPageJar(classPath.substring(0, classPath.indexOf('!')))
                : getPageClasses(mainClass.getPackage().getName());
    }

    /**
     * Собираем список классов у которых есть анотация WebServerLite.onPage.class на методе
     *
     * @param pathJarFile путь к jar-файлу
     * @return список классов с аннотациями
     */
    public static List<Class<?>> getPageJar(String pathJarFile) {
        if (pathJarFile.contains("jar:file:/")) {
            pathJarFile = pathJarFile.substring("jar:file:/".length());
        }
        if (System.getProperty("os.name").toLowerCase().contains("linux")) {
            pathJarFile = "/" + pathJarFile;
        }

        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(pathJarFile))) {
            processJarEntries(zip);
        } catch (IOException e) {
            System.err.println("getPageJar error: " + e);
        }

        return ServerResource.classes;
    }

    private static void processJarEntries(ZipInputStream zip) throws IOException {
        for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
            if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                processJarClassEntry(entry);
            }
        }
    }

    private static void processJarClassEntry(ZipEntry entry) {
        String className = entry.getName().replace('/', '.'); // including ".class"
        String classNameShot = className.substring(0, className.length() - ".class".length());

        if (shouldSkipClass(classNameShot)) {
            return;
        }

        try {
            Class<?> clazz = Class.forName(classNameShot);
            parseResourceClass(clazz);
        } catch (ClassNotFoundException e) {
            System.err.println("ClassNotFoundException error: " + e);
        }
    }

    private static boolean shouldSkipClass(String className) {
        if (className.contains("$") || className.contains("META-INF") ||
                className.contains("module-info")) {
            return true;
        }

        for (String excluded : EXCLUDED_PACKAGES) {
            if (className.startsWith(excluded)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Создаем страницы из JAVA класса (по аннотации)
     * @param clazz класс для обработки
     */
    private static void parseResourceClass(Class<?> clazz) {
        for (Method method : clazz.getMethods()) {
            processMethodAnnotations(clazz, method);
        }
    }

    private static void processMethodAnnotations(Class<?> clazz, Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            for (Class<?> annotationType : ANNOTATION_TYPES) {
                if (annotation.annotationType().equals(annotationType)) {
                    handleAnnotation(clazz, method, annotation);
                    break;
                }
            }
        }
    }

    private static void handleAnnotation(Class<?> clazz, Method method, Annotation annotation) {
        if (annotation instanceof onTerminal) {
            processTerminalAnnotation(clazz, method, (onTerminal) annotation);
            return;
        }

        JavaInnerClassObject page = createPageFromAnnotation(annotation);
        if (page == null) return;

        page.method = method;
        try {
            page.ObjectInstance = clazz.getDeclaredConstructor().newInstance();
            page.classNat = clazz;

            ServerResource.pagesJavaInnerClass.put(page.url, page);
            ServerResource.classes.add(clazz);
        } catch (Exception e) {
            System.err.println("Error creating class instance: " + e.getMessage());
        }
    }

    private static JavaInnerClassObject createPageFromAnnotation(Annotation annotation) {
        JavaInnerClassObject page = new JavaInnerClassObject();

        if (annotation instanceof onPage) {
            onPage an = (onPage) annotation;
            setPageProperties(page, an.ext(), an.mime(), an.url(), null);
        } else if (annotation instanceof Get) {
            Get an = (Get) annotation;
            setPageProperties(page, an.ext(), an.mime(), an.url(), "Get");
        } else if (annotation instanceof Put) {
            Put an = (Put) annotation;
            setPageProperties(page, an.ext(), an.mime(), an.url(), "Put");
        } else if (annotation instanceof Post) {
            Post an = (Post) annotation;
            setPageProperties(page, an.ext(), an.mime(), an.url(), "Post");
        } else if (annotation instanceof Delete) {
            Delete an = (Delete) annotation;
            setPageProperties(page, an.ext(), an.mime(), an.url(), "Delete");
        } else if (annotation instanceof Head) {
            Head an = (Head) annotation;
            setPageProperties(page, an.ext(), an.mime(), an.url(), "Head");
        } else if (annotation instanceof Patch) {
            Patch an = (Patch) annotation;
            setPageProperties(page, an.ext(), an.mime(), an.url(), "Patch");
        } else if (annotation instanceof Options) {
            Options an = (Options) annotation;
            setPageProperties(page, an.ext(), an.mime(), an.url(), "Options");
        } else if (annotation instanceof Trace) {
            Trace an = (Trace) annotation;
            setPageProperties(page, an.ext(), an.mime(), an.url(), "Trace");
        } else {
            return null;
        }

        return page;
    }

    private static void setPageProperties(JavaInnerClassObject page, String ext, String mime, String url, String queryType) {
        page.ext = ext;
        page.mime = mime;
        page.url = url;
        page.queryType = queryType;
    }

    private static void processTerminalAnnotation(Class<?> clazz, Method method, onTerminal annotation) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length == 1 && parameterTypes[0].equals(HttpExchange.class)) {
            JavaTerminalClassObject term = new JavaTerminalClassObject();
            term.method = method;
            try {
                // Исправляем устаревший newInstance()
                term.ObjectInstance = clazz.getDeclaredConstructor().newInstance();
                term.classNat = clazz;
                term.url = annotation.url();
                ServerResource.pagesJavaTerminalClass.put(annotation.url(), term);
                ServerResource.classes.add(clazz);
            } catch (Exception e) {
                System.err.println("Error create class " + e.getMessage());
            }
        }
    }

    /**
     * Получение списка классов в директории
     *
     * @param directory директория для поиска
     * @param startDir начальная директория
     * @return список имен классов
     */
    public static List<String> searchFilesClass(File directory, File startDir) {
        List<String> strClassesList = new ArrayList<>();
        File[] files = directory.listFiles();

        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    if (!startDir.getName().equals("EasyWebServer")) {
                        strClassesList.addAll(searchFilesClass(file, startDir));
                    }
                } else if (file.getName().endsWith(".class")) {
                    String fileStr = file.getAbsolutePath()
                            .substring(startDir.getAbsolutePath().length() + 1)
                            .replace("\\", ".")
                            .replace(".class", "");
                    strClassesList.add(fileStr);
                }
            }
        }

        return strClassesList;
    }

    /**
     * Получение(регистрация) страниц из JAVA классов имеющие аннотацию WebServerLite.onPage
     *
     * @param packageName имя пакета для поиска
     * @return список классов с аннотациями
     */
    public static List<Class<?>> getPageClasses(String packageName) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        String path = packageName.replace('.', '/');

        try {
            Enumeration<URL> resources = classLoader.getResources(path);
            while (resources.hasMoreElements()) {
                processResource(packageName, resources.nextElement());
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to get resources for path: " + path, e);
        }

        return ServerResource.classes;
    }

    private static void processResource(String packageName, URL resource) {
        File directory = new File(resource.getFile());
        if (directory.exists()) {
            for (String className : searchFilesClass(directory, directory)) {
                if (className.contains("$")) continue;

                try {
                    Class<?> clazz = loadClass(packageName, className);
                    if (clazz != null) {
                        parseResourceClass(clazz);
                    }
                } catch (ClassNotFoundException e) {
                    System.err.println("ClassNotFoundException ERROR: " + e);
                }
            }
        }
    }

    private static Class<?> loadClass(String packageName, String className) throws ClassNotFoundException {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            return Class.forName(packageName + "." + className);
        }
    }
}