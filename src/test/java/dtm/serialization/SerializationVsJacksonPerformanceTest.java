package dtm.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import dtm.serialization.mapper.BinaryObjectDecoderMapper;
import dtm.serialization.mapper.BinaryObjectEncoderMapper;
import org.junit.jupiter.api.Test;

import java.util.*;

public class SerializationVsJacksonPerformanceTest {

    private static final int SMALL_WARMUP = 10_000;
    private static final int SMALL_ITERATIONS = 100_000;

    private static final int LARGE_WARMUP = 100;
    private static final int LARGE_ITERATIONS = 1_000;

    private final BinaryObjectEncoderMapper encoder = new BinaryObjectEncoderMapper();
    private final BinaryObjectDecoderMapper decoder = new BinaryObjectDecoderMapper();
    private final ObjectMapper jackson = new ObjectMapper();

    @Test
    void compareCustomerWithJackson() throws Exception {
        CustomerSerializationTest.Customer customer = createCustomer();

        benchmark(
                "CUSTOMER",
                customer,
                CustomerSerializationTest.Customer.class,
                SMALL_WARMUP,
                SMALL_ITERATIONS
        );
    }

    @Test
    void compareLargeDocumentWithJackson() throws Exception {
        DocumentPayload document = createLargeDocumentPayload(5 * 1024 * 1024);

        benchmark(
                "LARGE DOCUMENT 5MB",
                document,
                DocumentPayload.class,
                LARGE_WARMUP,
                LARGE_ITERATIONS
        );
    }

    @Test
    void compareVeryLargeDocumentWithJackson() throws Exception {
        DocumentPayload document = createLargeDocumentPayload(20 * 1024 * 1024);

        benchmark(
                "LARGE DOCUMENT 20MB",
                document,
                DocumentPayload.class,
                20,
                100
        );
    }

    private <T> void benchmark(
            String name,
            T source,
            Class<T> type,
            int warmup,
            int iterations
    ) throws Exception {
        for (int i = 0; i < warmup; i++) {
            byte[] binary = encoder.encodeToByteArray(source);
            decoder.readAsObject(binary, type);

            byte[] json = jackson.writeValueAsBytes(source);
            jackson.readValue(json, type);
        }

        long binaryEncodeNs = 0;
        long binaryDecodeNs = 0;
        long binaryBytes = 0;

        long jacksonEncodeNs = 0;
        long jacksonDecodeNs = 0;
        long jacksonBytes = 0;

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            byte[] binary = encoder.encodeToByteArray(source);
            binaryEncodeNs += System.nanoTime() - start;
            binaryBytes += binary.length;

            start = System.nanoTime();
            decoder.readAsObject(binary, type);
            binaryDecodeNs += System.nanoTime() - start;

            start = System.nanoTime();
            byte[] json = jackson.writeValueAsBytes(source);
            jacksonEncodeNs += System.nanoTime() - start;
            jacksonBytes += json.length;

            start = System.nanoTime();
            jackson.readValue(json, type);
            jacksonDecodeNs += System.nanoTime() - start;
        }

        print(name + " / BINARY", binaryEncodeNs, binaryDecodeNs, binaryBytes, iterations);
        print(name + " / JACKSON", jacksonEncodeNs, jacksonDecodeNs, jacksonBytes, iterations);
        printComparison(name, binaryEncodeNs, binaryDecodeNs, binaryBytes, jacksonEncodeNs, jacksonDecodeNs, jacksonBytes, iterations);
    }

    private static CustomerSerializationTest.Customer createCustomer() {
        CustomerSerializationTest.Address addr =
                new CustomerSerializationTest.Address("São Paulo", 12345, new int[]{101, 102, 103, 104});

        CustomerSerializationTest.Product p1 =
                new CustomerSerializationTest.Product(
                        "Notebook",
                        3500.75,
                        2,
                        Set.of("eletrônicos", "computador", "hardware")
                );

        CustomerSerializationTest.Product p2 =
                new CustomerSerializationTest.Product(
                        "Smartphone",
                        1999.90,
                        1,
                        Set.of("eletrônicos", "celular", "mobile")
                );

        return new CustomerSerializationTest.Customer(
                "tech_guy",
                List.of(p1, p2),
                addr
        );
    }

    private static DocumentPayload createLargeDocumentPayload(int size) {
        byte[] content = new byte[size];
        new Random(123).nextBytes(content);

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("author", "Daniel");
        metadata.put("department", "documents");
        metadata.put("source", "scanner");
        metadata.put("category", "contract");
        metadata.put("filename", "large-document.pdf");
        metadata.put("description", "Documento grande para teste de serialização binária vs JSON/Jackson");

        return new DocumentPayload(
                UUID.randomUUID().toString(),
                "large-document.pdf",
                "application/pdf",
                System.currentTimeMillis(),
                metadata,
                content
        );
    }

    private static void print(
            String name,
            long encodeNs,
            long decodeNs,
            long totalBytes,
            int iterations
    ) {
        double encodeAvg = encodeNs / (double) iterations;
        double decodeAvg = decodeNs / (double) iterations;
        long avgSize = totalBytes / iterations;

        System.out.println();
        System.out.println("========== " + name + " ==========");
        System.out.println("Iterations: " + iterations);
        System.out.println("Avg size: " + formatBytes(avgSize));
        System.out.printf(Locale.US, "Encode avg: %.2f ns/op%n", encodeAvg);
        System.out.printf(Locale.US, "Decode avg: %.2f ns/op%n", decodeAvg);
        System.out.printf(Locale.US, "Encode avg: %.3f ms/op%n", encodeAvg / 1_000_000.0);
        System.out.printf(Locale.US, "Decode avg: %.3f ms/op%n", decodeAvg / 1_000_000.0);
        System.out.printf(Locale.US, "Encode ops/s: %.2f%n", 1_000_000_000.0 / encodeAvg);
        System.out.printf(Locale.US, "Decode ops/s: %.2f%n", 1_000_000_000.0 / decodeAvg);
    }

    private static void printComparison(
            String name,
            long binaryEncodeNs,
            long binaryDecodeNs,
            long binaryBytes,
            long jacksonEncodeNs,
            long jacksonDecodeNs,
            long jacksonBytes,
            int iterations
    ) {
        double binaryEncodeAvg = binaryEncodeNs / (double) iterations;
        double binaryDecodeAvg = binaryDecodeNs / (double) iterations;
        double jacksonEncodeAvg = jacksonEncodeNs / (double) iterations;
        double jacksonDecodeAvg = jacksonDecodeNs / (double) iterations;

        double binaryAvgSize = binaryBytes / (double) iterations;
        double jacksonAvgSize = jacksonBytes / (double) iterations;

        System.out.println();
        System.out.println("========== " + name + " / COMPARISON ==========");
        System.out.printf(Locale.US, "Payload binary/json: %.2fx%n", binaryAvgSize / jacksonAvgSize);
        System.out.printf(Locale.US, "Payload diff: %s%n", formatBytes((long) (binaryAvgSize - jacksonAvgSize)));
        System.out.printf(Locale.US, "Encode binary/jackson: %.2fx%n", binaryEncodeAvg / jacksonEncodeAvg);
        System.out.printf(Locale.US, "Decode binary/jackson: %.2fx%n", binaryDecodeAvg / jacksonDecodeAvg);
    }

    private static String formatBytes(long bytes) {
        if (Math.abs(bytes) < 1024) {
            return bytes + " bytes";
        }

        double kb = bytes / 1024.0;
        if (Math.abs(kb) < 1024) {
            return String.format(Locale.US, "%.2f KB", kb);
        }

        double mb = kb / 1024.0;
        return String.format(Locale.US, "%.2f MB", mb);
    }

    public static class DocumentPayload {
        public String id;
        public String name;
        public String contentType;
        public long createdAt;
        public Map<String, String> metadata;
        public byte[] content;

        public DocumentPayload() {
        }

        public DocumentPayload(
                String id,
                String name,
                String contentType,
                long createdAt,
                Map<String, String> metadata,
                byte[] content
        ) {
            this.id = id;
            this.name = name;
            this.contentType = contentType;
            this.createdAt = createdAt;
            this.metadata = metadata;
            this.content = content;
        }
    }
}
