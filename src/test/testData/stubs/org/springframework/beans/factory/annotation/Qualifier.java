package org.springframework.beans.factory.annotation;

// 注入位置保留明确的Bean限定名称。
public @interface Qualifier {
    String value() default "";
}
