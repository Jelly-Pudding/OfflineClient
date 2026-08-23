package com.jellypudding.offlineclient.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The method must take exactly one parameter whose type extends
 * {@link Event}. Higher priority runs first.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Subscribe {
    int priority() default 0;
}
