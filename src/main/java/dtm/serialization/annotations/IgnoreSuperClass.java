package dtm.serialization.annotations;

import dtm.serialization.enums.SerializationType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface IgnoreSuperClass {
    SerializationType[] value() default {SerializationType.ENCODE, SerializationType.DECODE};
}
