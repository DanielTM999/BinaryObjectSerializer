package dtm.serialization;

import java.io.IOException;

@FunctionalInterface
public interface LargeContentResolver {
    LargeContentDestination resolve(LargeContentContext context) throws IOException;
}
