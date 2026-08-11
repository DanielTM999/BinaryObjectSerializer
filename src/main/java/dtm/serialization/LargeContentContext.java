package dtm.serialization;

import java.util.List;

public record LargeContentContext(
        List<String> fieldPath,
        String fieldName,
        long length,
        Class<?> ownerType
) {
    public LargeContentContext {
        fieldPath = List.copyOf(fieldPath);
    }
}
