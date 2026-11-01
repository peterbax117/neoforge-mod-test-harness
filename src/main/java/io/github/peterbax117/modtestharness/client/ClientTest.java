package io.github.peterbax117.modtestharness.client;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A client test: a {@code public static void} method taking a {@link ClientTestContext}, inside a
 * {@link ClientTestHolder} class. Throw (for example via {@link ClientTestContext#check}) to fail.
 * Tests run in ascending {@link #order()}, then by method name.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ClientTest
{
    /** Display name in the report; defaults to Class.method. */
    String value() default "";

    int order() default 0;
}
