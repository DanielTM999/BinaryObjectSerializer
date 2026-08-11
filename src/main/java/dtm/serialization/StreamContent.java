package dtm.serialization;

import java.io.IOException;
import java.io.InputStream;
import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class StreamContent implements Closeable {
    private long length;
    private IOSupplier<InputStream> source;
    private Closeable cleanup;
    private boolean initialized;
    private final AtomicBoolean closed = new AtomicBoolean();

    protected StreamContent() {
        cleanup = () -> { };
    }

    protected StreamContent(long length, IOSupplier<InputStream> source) {
        this(length, source, () -> { });
    }

    protected StreamContent(long length, IOSupplier<InputStream> source, Closeable cleanup) {
        if (length < 0) throw new IllegalArgumentException("length must be >= 0");
        this.length = length;
        this.source = Objects.requireNonNull(source, "source");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        this.initialized = true;
    }

    public static StreamContent of(long length, IOSupplier<InputStream> source) {
        return new StreamContent(length, source);
    }

    public static StreamContent from(Path path) throws IOException {
        Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        return new StreamContent(Files.size(normalized), () -> Files.newInputStream(normalized));
    }

    public long length() {
        requireInitialized();
        return length;
    }

    public InputStream openStream() throws IOException {
        requireInitialized();
        if (closed.get()) throw new IOException("StreamContent is closed");
        InputStream stream = source.get();
        if (stream == null) throw new IOException("StreamContent source returned null");
        return stream;
    }

    static StreamContent temporary(Path path, Class<? extends StreamContent> contentType) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        long size = Files.size(normalized);
        IOSupplier<InputStream> source = () -> Files.newInputStream(normalized);
        Closeable cleanup = () -> Files.deleteIfExists(normalized);

        if (contentType == StreamContent.class) {
            return new StreamContent(size, source, cleanup);
        }

        try {
            Constructor<? extends StreamContent> constructor = contentType.getDeclaredConstructor();
            constructor.setAccessible(true);
            StreamContent content = constructor.newInstance();
            content.initializeDecoded(size, source, cleanup);
            return content;
        } catch (NoSuchMethodException noArgsError) {
            try {
                Constructor<? extends StreamContent> constructor = contentType.getDeclaredConstructor(
                        long.class, IOSupplier.class
                );
                constructor.setAccessible(true);
                StreamContent content = constructor.newInstance(size, source);
                content.attachDecodedCleanup(cleanup);
                return content;
            } catch (NoSuchMethodException lengthAndSourceError) {
                lengthAndSourceError.addSuppressed(noArgsError);
                throw new IOException("StreamContent subclass " + contentType.getName()
                        + " must declare either a no-args constructor or a (long, IOSupplier<InputStream>) "
                        + "constructor when no LargeContentResolver is configured", lengthAndSourceError);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException | RuntimeException e) {
                throw new IOException("Failed to instantiate StreamContent subclass " + contentType.getName()
                        + " using its (long, IOSupplier<InputStream>) constructor", e);
            }
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | RuntimeException e) {
            throw new IOException("Failed to instantiate StreamContent subclass " + contentType.getName()
                    + " using its no-args constructor", e);
        }
    }

    private synchronized void attachDecodedCleanup(Closeable decodedCleanup) {
        if (!initialized) throw new IllegalStateException("StreamContent is not initialized");
        if (closed.get()) throw new IllegalStateException("StreamContent is already closed");

        Closeable existingCleanup = cleanup;
        Closeable attachedCleanup = Objects.requireNonNull(decodedCleanup, "decodedCleanup");
        cleanup = () -> {
            IOException failure = null;
            try {
                existingCleanup.close();
            } catch (IOException e) {
                failure = e;
            }
            try {
                attachedCleanup.close();
            } catch (IOException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
            if (failure != null) throw failure;
        };
    }

    private synchronized void initializeDecoded(long length, IOSupplier<InputStream> source, Closeable cleanup) {
        if (initialized) throw new IllegalStateException("StreamContent is already initialized");
        if (closed.get()) throw new IllegalStateException("StreamContent is already closed");
        this.length = length;
        this.source = Objects.requireNonNull(source, "source");
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        this.initialized = true;
    }

    private void requireInitialized() {
        if (!initialized) throw new IllegalStateException(
                "StreamContent created with the no-args constructor has not been initialized by the decoder"
        );
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed.compareAndSet(false, true)) cleanup.close();
    }
}
