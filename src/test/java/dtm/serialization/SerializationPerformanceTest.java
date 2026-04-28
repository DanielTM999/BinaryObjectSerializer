package dtm.serialization;

import dtm.serialization.mapper.BinaryObjectDecoderMapper;
import dtm.serialization.mapper.BinaryObjectEncoderMapper;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class SerializationPerformanceTest {

    private static final int WARMUP = 10_000;
    private static final int ITERATIONS = 100_000;

    private final BinaryObjectEncoderMapper encoder = new BinaryObjectEncoderMapper();
    private final BinaryObjectDecoderMapper decoder = new BinaryObjectDecoderMapper();

    @Test
    void performanceCustomerEncodeDecode() throws Exception {
        CustomerSerializationTest.Customer customer = createCustomer();

        for (int i = 0; i < WARMUP; i++) {
            byte[] bytes = encoder.encodeToByteArray(customer);
            decoder.readAsTree(bytes).getAsObject(CustomerSerializationTest.Customer.class);
        }

        long encodeTotalNs = 0;
        long decodeTotalNs = 0;
        long totalBytes = 0;

        for (int i = 0; i < ITERATIONS; i++) {
            long startEncode = System.nanoTime();
            byte[] bytes = encoder.encodeToByteArray(customer);
            long endEncode = System.nanoTime();

            long startDecode = System.nanoTime();
            CustomerSerializationTest.Customer decoded =
                    decoder.readAsTree(bytes).getAsObject(CustomerSerializationTest.Customer.class);
            long endDecode = System.nanoTime();

            assertEquals(customer, decoded);

            encodeTotalNs += endEncode - startEncode;
            decodeTotalNs += endDecode - startDecode;
            totalBytes += bytes.length;
        }

        printResult("Customer encode/decode", encodeTotalNs, decodeTotalNs, totalBytes);
    }

    @Test
    void performanceByteArrayEncodeDecode() throws Exception {
        byte[] source = new byte[1024 * 64];
        new Random(123).nextBytes(source);

        for (int i = 0; i < WARMUP; i++) {
            byte[] bytes = encoder.encodeToByteArray(source);
            decoder.readAsTree(bytes).getAsObject(byte[].class);
        }

        long encodeTotalNs = 0;
        long decodeTotalNs = 0;
        long totalBytes = 0;

        for (int i = 0; i < ITERATIONS; i++) {
            long startEncode = System.nanoTime();
            byte[] bytes = encoder.encodeToByteArray(source);
            long endEncode = System.nanoTime();

            long startDecode = System.nanoTime();
            byte[] decoded = decoder.readAsTree(bytes).getAsObject(byte[].class);
            long endDecode = System.nanoTime();

            assertArrayEquals(source, decoded);

            encodeTotalNs += endEncode - startEncode;
            decodeTotalNs += endDecode - startDecode;
            totalBytes += bytes.length;
        }

        printResult("byte[64KB] encode/decode", encodeTotalNs, decodeTotalNs, totalBytes);
    }

    @Test
    void performanceMapEncodeDecode() throws Exception {
        Map<String, Object> map = createMap();

        for (int i = 0; i < WARMUP; i++) {
            byte[] bytes = encoder.encodeToByteArray(map);
            decoder.readAsTree(bytes).getAsMap();
        }

        long encodeTotalNs = 0;
        long decodeTotalNs = 0;
        long totalBytes = 0;

        for (int i = 0; i < ITERATIONS; i++) {
            long startEncode = System.nanoTime();
            byte[] bytes = encoder.encodeToByteArray(map);
            long endEncode = System.nanoTime();

            long startDecode = System.nanoTime();
            Map<String, Object> decoded = decoder.readAsTree(bytes).getAsMap();
            long endDecode = System.nanoTime();

            assertEquals(map.size(), decoded.size());

            encodeTotalNs += endEncode - startEncode;
            decodeTotalNs += endDecode - startDecode;
            totalBytes += bytes.length;
        }

        printResult("Map encode/decode", encodeTotalNs, decodeTotalNs, totalBytes);
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

    private static Map<String, Object> createMap() {
        Map<String, Object> map = new HashMap<>();

        map.put("username", "tech_guy");
        map.put("active", true);
        map.put("age", 30);
        map.put("score", 99999L);
        map.put("balance", 12345.67);
        map.put("roles", List.of("ADMIN", "USER", "DEV"));

        Map<String, Object> address = new HashMap<>();
        address.put("city", "São Paulo");
        address.put("zip", 12345);
        address.put("numbers", List.of(101, 102, 103));

        map.put("address", address);

        return map;
    }

    private static void printResult(
            String name,
            long encodeTotalNs,
            long decodeTotalNs,
            long totalBytes
    ) {
        double encodeMs = encodeTotalNs / 1_000_000.0;
        double decodeMs = decodeTotalNs / 1_000_000.0;

        double encodeAvgNs = encodeTotalNs / (double) ITERATIONS;
        double decodeAvgNs = decodeTotalNs / (double) ITERATIONS;

        double encodeOps = 1_000_000_000.0 / encodeAvgNs;
        double decodeOps = 1_000_000_000.0 / decodeAvgNs;

        long avgSize = totalBytes / ITERATIONS;

        System.out.println();
        System.out.println("========== " + name + " ==========");
        System.out.println("Iterations: " + ITERATIONS);
        System.out.println("Avg size: " + avgSize + " bytes");
        System.out.printf("Encode total: %.3f ms%n", encodeMs);
        System.out.printf("Decode total: %.3f ms%n", decodeMs);
        System.out.printf("Encode avg: %.2f ns/op%n", encodeAvgNs);
        System.out.printf("Decode avg: %.2f ns/op%n", decodeAvgNs);
        System.out.printf("Encode ops/s: %.2f%n", encodeOps);
        System.out.printf("Decode ops/s: %.2f%n", decodeOps);
    }
}