package ru.miacomsoft.EasyWebServer.util.queryType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Delete {
    String url() default "/";
    String ext() default "html";
    String mime() default "text/html";
    String[] path() default {};
}
