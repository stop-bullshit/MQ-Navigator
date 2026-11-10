package org.springframework.context.annotation;

// Bean定义保留名称别名与类型注入标记。
public @interface Bean {
    String[] value() default {};
    String[] name() default {};
    boolean autowireCandidate() default true;
}
