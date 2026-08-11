package dtm.serialization;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Destination used by the decoder and the value assigned after a successful copy. */
public final class LargeContentDestination {
    private final OutputStream output;
    private final IOSupplier<StreamContent> completedContent;
    private final AutoCloseable abortAction;
    private StreamContent completed;

    private LargeContentDestination(OutputStream output, IOSupplier<StreamContent> completedContent,
                                    AutoCloseable abortAction) {
        this.output = Objects.requireNonNull(output, "output");
        this.completedContent = Objects.requireNonNull(completedContent, "completedContent");
        this.abortAction = Objects.requireNonNull(abortAction, "abortAction");
    }

    public static LargeContentDestination of(OutputStream output, IOSupplier<StreamContent> completedContent) {
        return new LargeContentDestination(output, completedContent, () -> { });
    }

    public static LargeContentDestination to(Path path) throws IOException {
        Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        return new LargeContentDestination(
                Files.newOutputStream(normalized),
                () -> StreamContent.from(normalized),
                () -> { }
        );
    }

    public static LargeContentDestination temporary(Class<? extends StreamContent> contentType) throws IOException {
        Objects.requireNonNull(contentType, "contentType");
        Path path = Files.createTempFile("binary-object-content-", ".bin");
        try {
            return new LargeContentDestination(
                    Files.newOutputStream(path),
                    () -> StreamContent.temporary(path, contentType),
                    () -> Files.deleteIfExists(path)
            );
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(path);
            throw e;
        }
    }

    public OutputStream output() {
        return output;
    }

    public synchronized StreamContent completedContent() throws IOException {
        if (completed == null) completed = completedContent.get();
        return completed;
    }

    public synchronized void abort() {
        if (completed != null) return;
        try {
            abortAction.close();
        } catch (Exception ignored) {
            // Preserve the original decode failure.
        }
    }
}
