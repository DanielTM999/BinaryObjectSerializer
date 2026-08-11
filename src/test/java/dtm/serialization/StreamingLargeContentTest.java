package dtm.serialization;

import dtm.serialization.annotations.LargeContent;
import dtm.serialization.exceptions.DecodeSerializationException;
import dtm.serialization.mapper.BinaryObjectDecoderMapper;
import dtm.serialization.mapper.BinaryObjectEncoderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class StreamingLargeContentTest {

    @TempDir
    Path tempDir;

    @Test
    void streamsAnnotatedContentInBoundedChunks() throws Exception {
        byte[] content = patternedBytes(2 * 1024 * 1024 + 17);
        CloseTrackingInputStream contentInput = new CloseTrackingInputStream(content, 64 * 1024);
        Attachment source = new Attachment("before", StreamContent.of(content.length, () -> contentInput), "after");

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        new BinaryObjectEncoderMapper().encode(source, encoded);

        assertTrue(contentInput.closed, "the encoder owns and closes field streams");
        assertEquals(Constants.VERSION_BYTE, encoded.toByteArray()[1]);

        Path decodedPath = tempDir.resolve("decoded.bin");
        CloseTrackingInputStream frameInput = new CloseTrackingInputStream(encoded.toByteArray(), 64 * 1024);
        DecodeOptions options = DecodeOptions.DEFAULT.withLargeContentResolver(
                context -> LargeContentDestination.to(decodedPath)
        );

        Attachment decoded = new BinaryObjectDecoderMapper().readAsObjectWithOptions(frameInput, Attachment.class, options);

        assertFalse(frameInput.closed, "caller-owned frame stream must remain open");
        assertEquals("before", decoded.before);
        assertEquals("after", decoded.after);
        assertArrayEquals(content, Files.readAllBytes(decodedPath));
        assertArrayEquals(content, decoded.content.openStream().readAllBytes());
    }

    @Test
    void consumesExactlyOneFrameFromAConcatenatedStream() throws Exception {
        BinaryObjectEncoderMapper encoder = new BinaryObjectEncoderMapper();
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        encoder.encode("first", frames);
        encoder.encode("second", frames);

        ByteArrayInputStream input = new ByteArrayInputStream(frames.toByteArray());
        BinaryObjectDecoderMapper decoder = new BinaryObjectDecoderMapper();

        assertEquals("first", decoder.readAsObject(input, String.class));
        assertTrue(input.available() > 0);
        assertEquals("second", decoder.readAsObject(input, String.class));
        assertEquals(0, input.available());
    }

    @Test
    void treeStoresLargeContentExternally() throws Exception {
        byte[] content = patternedBytes(150_000);
        Attachment source = new Attachment("a", StreamContent.of(content.length,
                () -> new ByteArrayInputStream(content)), "b");
        byte[] encoded = new BinaryObjectEncoderMapper().encodeToByteArray(source);
        Path destination = tempDir.resolve("tree.bin");

        BinaryObjectNode tree = new BinaryObjectDecoderMapper().readAsTreeWithOptions(
                new ByteArrayInputStream(encoded),
                DecodeOptions.DEFAULT.withLargeContentResolver(context -> LargeContentDestination.to(destination))
        );

        BinaryObjectNode contentNode = tree.getChild("content");
        assertNotNull(contentNode);
        assertEquals(content.length, contentNode.getAsStreamContent().length());
        assertArrayEquals(content, Files.readAllBytes(destination));
        assertThrows(DecodeSerializationException.class, contentNode::getAsBytes);
    }

    @Test
    void usesTemporaryLazyContentWhenResolverIsAbsent() throws Exception {
        byte[] content = patternedBytes(220_000);
        Attachment source = new Attachment("before", StreamContent.of(content.length,
                () -> new ByteArrayInputStream(content)), "after");
        byte[] encoded = new BinaryObjectEncoderMapper().encodeToByteArray(source);

        Attachment decoded = new BinaryObjectDecoderMapper().readAsObject(
                new ByteArrayInputStream(encoded), Attachment.class
        );

        assertEquals("after", decoded.after);
        try (InputStream lazy = decoded.content.openStream()) {
            assertArrayEquals(content, lazy.readAllBytes());
        }
        decoded.content.close();
        assertThrows(IOException.class, decoded.content::openStream);
    }

    @Test
    void keepsStreamLazyInMemoryWithoutLargeContentAnnotation() throws Exception {
        byte[] content = patternedBytes(220_000);
        LazyAttachment source = new LazyAttachment();
        source.before = "before";
        source.content = StreamLazy.of(content);
        source.zAfterContent = "after";

        byte[] encoded = new BinaryObjectEncoderMapper().encodeToByteArray(source);
        LazyAttachment decoded = new BinaryObjectDecoderMapper().readAsObject(encoded, LazyAttachment.class);

        assertEquals("before", decoded.before);
        assertEquals("after", decoded.zAfterContent);
        assertInstanceOf(StreamLazy.class, decoded.content);
        try (InputStream first = decoded.content.openStream();
             InputStream second = decoded.content.openStream()) {
            assertArrayEquals(content, first.readAllBytes());
            assertArrayEquals(content, second.readAllBytes());
        }

        decoded.content.close();
        assertThrows(IOException.class, decoded.content::openStream);
    }

    @Test
    void rejectsStreamLazyLargerThanAnInMemoryJavaArray() {
        long declaredLength = (long) Integer.MAX_VALUE + 1L;
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(Constants.VALIDATOR_BYTE);
        frame.write(Constants.VERSION_BYTE);
        long descriptorLength = 1L + 1L + 4L + varLongLength(declaredLength) + declaredLength;
        writeVarLong(frame, descriptorLength);
        frame.write(0x13);
        frame.write(4);
        frame.writeBytes("root".getBytes(StandardCharsets.UTF_8));
        writeVarLong(frame, declaredLength);

        DecodeSerializationException error = assertThrows(
                DecodeSerializationException.class,
                () -> new BinaryObjectDecoderMapper().readAsObject(
                        new ByteArrayInputStream(frame.toByteArray()), StreamLazy.class)
        );

        assertTrue(error.getMessage().contains("too large for an in-memory Java value"));
    }

    @Test
    void largeContentAnnotationKeepsExternalPolicyForStreamLazy() throws Exception {
        byte[] content = patternedBytes(180_000);
        LazyLargeAttachment source = new LazyLargeAttachment();
        source.content = StreamLazy.of(content);

        byte[] encoded = new BinaryObjectEncoderMapper().encodeToByteArray(source);
        LazyLargeAttachment decoded = new BinaryObjectDecoderMapper().readAsObject(
                encoded, LazyLargeAttachment.class
        );

        assertInstanceOf(StreamLazy.class, decoded.content);
        assertArrayEquals(content, decoded.content.openStream().readAllBytes());
        decoded.content.close();
    }

    @Test
    void handlesLongContentLengthWithoutAllocatingItsBody() throws Exception {
        long declaredLength = (long) Integer.MAX_VALUE + 1L;
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(Constants.VALIDATOR_BYTE);
        frame.write(Constants.VERSION_BYTE);
        long descriptorLength = 1L + 1L + 4L + varLongLength(declaredLength) + declaredLength;
        writeVarLong(frame, descriptorLength);
        frame.write(0x13);
        frame.write(4);
        frame.write("root".getBytes(StandardCharsets.UTF_8));
        writeVarLong(frame, declaredLength);

        DecodeSerializationException error = assertThrows(
                DecodeSerializationException.class,
                () -> new BinaryObjectDecoderMapper().readAsObject(
                        new ByteArrayInputStream(frame.toByteArray()), StreamContent.class)
        );
        assertTrue(error.getMessage().contains("Failed to store large content"));
    }

    @Test
    void readsVersionThreeFrames() {
        byte[] value = "legacy-v3".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(0x01);
        payload.write(4);
        payload.writeBytes("root".getBytes(StandardCharsets.UTF_8));
        payload.write(value.length);
        payload.writeBytes(value);

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(Constants.VALIDATOR_BYTE);
        frame.write(Constants.PREVIOUS_VERSION_BYTE);
        writeVarLong(frame, payload.size());
        frame.writeBytes(payload.toByteArray());

        assertEquals("legacy-v3", new BinaryObjectDecoderMapper().readAsObject(frame.toByteArray(), String.class));
    }

    @Test
    void validatesLargeContentAnnotationType() {
        assertThrows(RuntimeException.class,
                () -> new BinaryObjectEncoderMapper().encodeToByteArray(new InvalidAttachment()));
    }

    @Test
    void supportsStreamContentSubclassesAndContinuesWithFollowingFields() throws Exception {
        byte[] content = patternedBytes(180_000);
        CustomAttachment source = new CustomAttachment();
        source.content = CustomContent.fromBytes(content);
        source.zAfterContent = "decoded-after-stream";

        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        new BinaryObjectEncoderMapper().encode(source, encoded);
        CustomAttachment decoded = new BinaryObjectDecoderMapper().readAsObject(
                new ByteArrayInputStream(encoded.toByteArray()), CustomAttachment.class
        );

        assertInstanceOf(CustomContent.class, decoded.content);
        assertEquals("decoded-after-stream", decoded.zAfterContent);
        assertArrayEquals(content, decoded.content.openStream().readAllBytes());
        decoded.content.close();
    }

    @Test
    void usesLengthAndSourceConstructorWhenStreamContentSubclassHasNoNoArgsConstructor() throws Exception {
        MissingConstructorAttachment source = new MissingConstructorAttachment();
        source.content = new MissingNoArgsContent(3, () -> new ByteArrayInputStream(new byte[]{1, 2, 3}));
        byte[] encoded = new BinaryObjectEncoderMapper().encodeToByteArray(source);

        MissingConstructorAttachment decoded = new BinaryObjectDecoderMapper().readAsObject(
                encoded, MissingConstructorAttachment.class
        );

        assertInstanceOf(MissingNoArgsContent.class, decoded.content);
        assertArrayEquals(new byte[]{1, 2, 3}, decoded.content.openStream().readAllBytes());
        decoded.content.close();
    }

    @Test
    void attachesTemporaryFileCleanupWhenUsingLengthAndSourceConstructor() throws Exception {
        Path temporaryContent = tempDir.resolve("subclass-content.bin");
        Files.write(temporaryContent, new byte[]{4, 5, 6});

        StreamContent content = StreamContent.temporary(temporaryContent, MissingNoArgsContent.class);

        assertInstanceOf(MissingNoArgsContent.class, content);
        content.close();
        assertFalse(Files.exists(temporaryContent));
    }

    private static byte[] patternedBytes(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) (i * 31);
        return bytes;
    }

    private static void writeVarLong(OutputStream output, long value) {
        try {
            while ((value & ~0x7FL) != 0) {
                output.write(((int) value & 0x7F) | 0x80);
                value >>>= 7;
            }
            output.write((int) value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int varLongLength(long value) {
        int length = 1;
        while ((value & ~0x7FL) != 0) { length++; value >>>= 7; }
        return length;
    }

    public static class Attachment {
        public String after;
        public String before;
        @LargeContent
        public StreamContent content;

        public Attachment() {
        }

        Attachment(String before, StreamContent content, String after) {
            this.before = before;
            this.content = content;
            this.after = after;
        }
    }

    public static class InvalidAttachment {
        @LargeContent
        public byte[] content = new byte[0];
    }

    public static class LazyAttachment {
        public String before;
        public StreamLazy content;
        public String zAfterContent;
    }

    public static class LazyLargeAttachment {
        @LargeContent
        public StreamLazy content;
    }

    public static class CustomAttachment {
        @LargeContent
        public CustomContent content;
        public String zAfterContent;
    }

    public static class CustomContent extends StreamContent {
        protected CustomContent() {
            super();
        }

        protected CustomContent(long length, IOSupplier<InputStream> source) {
            super(length, source);
        }

        static CustomContent fromBytes(byte[] bytes) {
            return new CustomContent(bytes.length, () -> new ByteArrayInputStream(bytes));
        }

        static CustomContent fromPath(Path path) throws IOException {
            return new CustomContent(Files.size(path), () -> Files.newInputStream(path));
        }
    }

    public static class MissingConstructorAttachment {
        @LargeContent
        public MissingNoArgsContent content;
    }

    public static class MissingNoArgsContent extends StreamContent {
        protected MissingNoArgsContent(long length, IOSupplier<InputStream> source) {
            super(length, source);
        }
    }

    private static final class CloseTrackingInputStream extends ByteArrayInputStream {
        private final int maxReadLength;
        private boolean closed;

        private CloseTrackingInputStream(byte[] bytes, int maxReadLength) {
            super(Arrays.copyOf(bytes, bytes.length));
            this.maxReadLength = maxReadLength;
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            if (length > maxReadLength) fail("read request exceeded chunk size: " + length);
            return super.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
