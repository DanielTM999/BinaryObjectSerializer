package dtm.serialization;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;


public final class StreamLazy extends StreamContent {

    private StreamLazy() {
        super();
    }

    private StreamLazy(InMemorySource source) {
        super(source.length(), source::openStream, source);
    }

    /** Creates in-memory content with a defensive copy of {@code bytes}. */
    public static StreamLazy of(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        byte[] copy = Arrays.copyOf(bytes, bytes.length);
        return new StreamLazy(new InMemorySource(copy, 0, copy.length));
    }

    /**
     * Creates in-memory content that takes ownership of {@code bytes} without
     * copying it. The caller must not modify the array afterwards.
     */
    public static StreamLazy wrap(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return new StreamLazy(new InMemorySource(bytes, 0, bytes.length));
    }

    /**
     * Creates an in-memory view over a region of {@code bytes} without copying
     * it. The caller must not modify the array while this content is in use.
     */
    public static StreamLazy wrap(byte[] bytes, int offset, int length) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.checkFromIndexSize(offset, length, bytes.length);
        return new StreamLazy(new InMemorySource(bytes, offset, length));
    }

    private static final class InMemorySource implements Closeable {
        private final int offset;
        private final int length;
        private final AtomicReference<byte[]> bytes;

        private InMemorySource(byte[] bytes, int offset, int length) {
            this.offset = offset;
            this.length = length;
            this.bytes = new AtomicReference<>(bytes);
        }

        private long length() {
            return length;
        }

        private InputStream openStream() throws IOException {
            byte[] current = bytes.get();
            if (current == null) throw new IOException("StreamLazy is closed");
            return new ByteArrayInputStream(current, offset, length);
        }

        @Override
        public void close() {
            bytes.set(null);
        }
    }
}
