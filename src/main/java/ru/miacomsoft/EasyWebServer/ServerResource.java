package ru.miacomsoft.EasyWebServer;

import ru.miacomsoft.EasyWebServer.util.callbackType.CallbackPage;
import ru.miacomsoft.EasyWebServer.util.onPage;
import ru.miacomsoft.EasyWebServer.util.onTerminal;
import ru.miacomsoft.EasyWebServer.util.queryType.*;
import ru.miacomsoft.EasyWebServer.util.structObject.JavaInnerClassObject;
import ru.miacomsoft.EasyWebServer.util.structObject.JavaTerminalClassObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class ServerResource {
    public static HashMap<String, JavaInnerClassObject> pagesJavaInnerClass = new HashMap<String, JavaInnerClassObject>(10, (float) 0.5);
    public static HashMap<String, JavaTerminalClassObject> pagesJavaTerminalClass = new HashMap<String, JavaTerminalClassObject>(10, (float) 0.5);
    public static HashMap<String, StringBuffer> pagesListContent = new HashMap<String, StringBuffer>(10, (float) 0.5);
    public static HashMap<String, CallbackPage> pagesList = new HashMap<String, CallbackPage>(10, (float) 0.5);
    public static HashMap<String, File> pagesListFile = new HashMap<String, File>(10, (float) 0.5);
    public static HashMap<String, Class> componentListClass = new HashMap<String, Class>(10, (float) 0.5);
    public static List<Class<?>> classes = new ArrayList<>();

    ///Массив с интерфейсами аннотаций
    public static final Class<?>[] ANNOTATION_TYPES = {
            onPage.class,
            Get.class,
            Put.class,
            Post.class,
            Delete.class,
            onTerminal.class,
            Head.class,
            Patch.class,
            Options.class,
            Trace.class
    };
}
