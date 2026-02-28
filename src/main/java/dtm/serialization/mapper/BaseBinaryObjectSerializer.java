package dtm.serialization.mapper;

import dtm.serialization.annotations.ElementRef;
import dtm.serialization.annotations.IgnoreSuperClass;
import dtm.serialization.enums.SerializationType;
import dtm.serialization.exceptions.EncodeSerializationException;
import dtm.serialization.exceptions.SerializationException;
import dtm.serialization.extension.SerializationModule;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public abstract class BaseBinaryObjectSerializer {

    private static final Map<FieldCacheKey, List<FieldCacheProps>> FIELD_CACHE = new ConcurrentHashMap<>();

    private final Set<SerializationModule> serializationModules = ConcurrentHashMap.newKeySet();
    private final Map<Class<?>, SerializationModule> moduleCache = new ConcurrentHashMap<>();

    protected void addModule(SerializationModule module) {
        if(module == null) throw new SerializationException("SerializationModule is null");
        serializationModules.add(module);
    }

    protected SerializationModule resolveModule(Class<?> type) {
        return moduleCache.computeIfAbsent(type, this::findModuleForType);
    }

    protected List<FieldCacheProps> resolveFields(Class<?> type, SerializationType serializationType) {
        try {
            if(isSimpleType(type)) { return List.of(); }
            FieldCacheKey key = new FieldCacheKey(type, serializationType);
            return FIELD_CACHE.computeIfAbsent(
                    key,
                    k -> extractFields(k.type(), k.serializationType())
            );
        } catch (Exception e) {
            throw new SerializationException(
                    "Failed to resolve fields for class: " + type.getName()
                            + " (" + serializationType + ")",
                    e
            );
        }
    }

    protected List<FieldCacheProps> extractFields(Class<?> type, SerializationType serializationType) {
        return serializationType == SerializationType.ENCODE
                ? extractFieldsEncode(type)
                : extractFieldsDecode(type);
    }

    protected List<FieldCacheProps> extractFieldsEncode(Class<?> type){
        return collectFields(type, SerializationType.ENCODE);
    }

    protected List<FieldCacheProps> extractFieldsDecode(Class<?> type){
        return collectFields(type, SerializationType.DECODE);
    }

    protected boolean isSimpleType(Class<?> type) {
        return type.isPrimitive()
                || type == String.class
                || Number.class.isAssignableFrom(type)
                || type == Boolean.class
                || type == Character.class
                || type.isEnum();
    }

    protected boolean classIs(Class<?> comparation, Class<?>... type) {
        return Arrays.asList(type).contains(comparation);
    }

    protected boolean isJavaPackage(Class<?> type) {
        Package pkg = type.getPackage();
        if (pkg == null) return false;

        String name = pkg.getName();
        return name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jdk.");
    }


    private List<FieldCacheProps> collectFields(Class<?> type, SerializationType phase) {
        List<FieldCacheProps> fields = new ArrayList<>();

        if (isJavaPackage(type)) {
            return List.of();
        }

        IgnoreSuperClass ignoreSuperClass = type.getAnnotation(IgnoreSuperClass.class);

        boolean ignoreSuper = ignoreSuperClass != null && Arrays.asList(ignoreSuperClass.value()).contains(phase);

        Class<?> current = type;

        while (current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                field.setAccessible(true);
                fields.add(new FieldCacheProps(
                        current,
                        phase,
                        field,
                        getNameByElement(field)
                ));
            }

            if (ignoreSuper) {
                break;
            }

            Class<?> superClass = current.getSuperclass();
            if (superClass == null || isJavaPackage(superClass)) {
                break;
            }

            current = superClass;
        }

        fields.sort(Comparator
                .comparing((FieldCacheProps f) -> f.field.getDeclaringClass().getName())
                .thenComparing(f -> f.field.getName())
        );

        return fields;
    }

    private SerializationModule findModuleForType(Class<?> type) {
        for (SerializationModule module : serializationModules) {
            if (module.supports(type)) {
                return module;
            }
        }
        return null;
    }

    private String getNameByElement(Field field) {
        if(field.isAnnotationPresent(ElementRef.class)){
            return field.getAnnotation(ElementRef.class).value();
        }

        return field.getName();
    }

    record FieldCacheKey(Class<?> type, SerializationType serializationType) {}

    protected record FieldCacheProps(Class<?> type, SerializationType serializationType, Field field, String elementName){}
}
