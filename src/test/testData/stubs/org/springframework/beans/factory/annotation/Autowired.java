package org.springframework.beans.factory.annotation;

// 注入字段声明是否必须存在候选Bean。
public @interface Autowired {
    boolean required() default true;
}
