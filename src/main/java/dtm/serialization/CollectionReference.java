package dtm.serialization;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;

public abstract class CollectionReference<R extends Collection<?>> {
    private final Type type;

    public CollectionReference() {
        Type superClass = getClass().getGenericSuperclass();
        if (superClass instanceof ParameterizedType pt) {
            type = pt.getActualTypeArguments()[0];
        } else {
            throw new IllegalArgumentException("Invalid type reference, must provide generic type.");
        }
    }

    public final Type getType() {
        return type;
    }
}
