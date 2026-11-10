package javax.annotation;

// 资源注入保留指定名称和默认字段名语义。
public @interface Resource {
    String name() default "";
}
