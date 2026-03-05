package ru.miacomsoft.EasyWebServer.util;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)  // Specifies that the annotation should be retained at runtime
@Target({ElementType.METHOD, ElementType.TYPE})  // Specifies that the annotation can only be applied to classes
public @interface onPage {
    String url() default "/";
    String ext() default "html";
    String mime() default "text/html";
}
