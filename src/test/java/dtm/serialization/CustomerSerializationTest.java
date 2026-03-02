package dtm.serialization;

import dtm.serialization.mapper.BinaryObjectDecoderMapper;
import dtm.serialization.mapper.BinaryObjectEncoderMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

// ===== TESTES JUNIT =====
public class CustomerSerializationTest {

    public static class Product {
        public String name;
        public double price;
        public int quantity;
        public Set<String> tags;

        public Product() {}
        public Product(String name, double price, int quantity, Set<String> tags) {
            this.name = name;
            this.price = price;
            this.quantity = quantity;
            this.tags = tags;
        }

        @Override
        public String toString() {
            return "Product{" +
                    "name='" + name + '\'' +
                    ", price=" + price +
                    ", quantity=" + quantity +
                    ", tags=" + tags +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Product)) return false;
            Product p = (Product) o;
            return Double.compare(p.price, price) == 0 &&
                    quantity == p.quantity &&
                    Objects.equals(name, p.name) &&
                    Objects.equals(tags == null ? null : new HashSet<>(tags),
                            p.tags == null ? null : new HashSet<>(p.tags));
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, price, quantity,
                    tags == null ? null : new HashSet<>(tags));
        }
    }

    public static class Address {
        public String city;
        public int zip;
        public int[] apartmentNumbers;

        public Address() {}
        public Address(String city, int zip, int[] apartmentNumbers) {
            this.city = city;
            this.zip = zip;
            this.apartmentNumbers = apartmentNumbers;
        }

        @Override
        public String toString() {
            return "Address{" +
                    "city='" + city + '\'' +
                    ", zip=" + zip +
                    ", apartmentNumbers=" + Arrays.toString(apartmentNumbers) +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Address)) return false;
            Address a = (Address) o;
            return zip == a.zip &&
                    Objects.equals(city, a.city) &&
                    Arrays.equals(apartmentNumbers, a.apartmentNumbers);
        }

        @Override
        public int hashCode() {
            return Objects.hash(city, zip, Arrays.hashCode(apartmentNumbers));
        }
    }

    public static class Customer {
        public String username;
        public List<Product> cart;
        public Address shippingAddress;

        public Customer() {}
        public Customer(String username, List<Product> cart, Address shippingAddress) {
            this.username = username;
            this.cart = cart;
            this.shippingAddress = shippingAddress;
        }

        @Override
        public String toString() {
            return "Customer{" +
                    "username='" + username + '\'' +
                    ", cart=" + cart +
                    ", shippingAddress=" + shippingAddress +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Customer)) return false;
            Customer c = (Customer) o;
            return Objects.equals(username, c.username) &&
                    Objects.equals(cart, c.cart) &&
                    Objects.equals(shippingAddress, c.shippingAddress);
        }

        @Override
        public int hashCode() {
            return Objects.hash(username, cart, shippingAddress);
        }
    }

    public static class ByteArrayWrapper {
        public byte[] data;

        public ByteArrayWrapper() {}
        public ByteArrayWrapper(byte[] data) {
            this.data = data;
        }
    }

    public static class ObjectWithArray {
        public String name;
        public int[] numbers;

        public ObjectWithArray() {}
        public ObjectWithArray(String name, int[] numbers) {
            this.name = name;
            this.numbers = numbers;
        }
    }

    public static enum TesteEnum{
        ENUM_1,
        ENUM_2,
        ENUM_3
    }


    private static BinaryObjectEncoderMapper encoder;
    private static BinaryObjectDecoderMapper decoder;

    @BeforeAll
    public static void setup() {
        encoder = new BinaryObjectEncoderMapper();
        decoder = new BinaryObjectDecoderMapper();
    }

    private Customer serializeAndDeserialize(Customer customer) throws Exception {
        byte[] encoded = encoder.encodeToByteArray(customer);
        return decoder.readAsTree(encoded).getAsObject(Customer.class);
    }

    @Test
    public void testNormalCustomer() throws Exception {
        Address addr = new Address("São Paulo", 12345, new int[]{101, 102});
        Product p1 = new Product("Notebook", 3500.75, 2, Set.of("eletrônicos","computador"));
        Product p2 = new Product("Smartphone", 1999.90, 1, Set.of("eletrônicos","celular"));
        Customer customer = new Customer("tech_guy", List.of(p1,p2), addr);

        Customer decoded = serializeAndDeserialize(customer);
        assertEquals(customer, decoded);
    }

    @Test
    public void testEmptyCart() throws Exception {
        Address addr = new Address("Curitiba", 80010, new int[]{});
        Customer customer = new Customer("empty_cart", new ArrayList<>(), addr);

        Customer decoded = serializeAndDeserialize(customer);
        assertEquals(customer, decoded);
    }

    @Test
    public void testNullAddress() throws Exception {
        Product p = new Product("Notebook", 3500.75, 2, Set.of("eletrônicos","computador"));
        Customer customer = new Customer("no_address", List.of(p), null);

        Customer decoded = serializeAndDeserialize(customer);
        assertEquals(customer, decoded);
    }

    @Test
    public void testNullTags() throws Exception {
        Address addr = new Address("São Paulo", 12345, new int[]{101, 102});
        Product p = new Product("Mouse", 149.99, 3, null);
        Customer customer = new Customer("null_tags", List.of(p), addr);

        Customer decoded = serializeAndDeserialize(customer);
        assertEquals(customer, decoded);
    }

    @Test
    public void testEmptyTags() throws Exception {
        Address addr = new Address("São Paulo", 12345, new int[]{101, 102});
        Product p = new Product("Teclado", 299.90, 1, new HashSet<>());
        Customer customer = new Customer("empty_tags", List.of(p), addr);

        Customer decoded = serializeAndDeserialize(customer);
        assertEquals(customer, decoded);
    }

    @Test
    public void testExtremeValues() throws Exception {
        Address addr = new Address("São Paulo", 12345, new int[]{101,102});
        Product p1 = new Product("Server", Double.MAX_VALUE, Integer.MAX_VALUE, Set.of("hardware"));
        Product p2 = new Product("Cheap Item", Double.MIN_VALUE, Integer.MIN_VALUE, Set.of("misc"));
        Customer customer = new Customer("extremes", List.of(p1,p2), addr);

        Customer decoded = serializeAndDeserialize(customer);
        assertEquals(customer, decoded);
    }

    @Test
    public void testLongStrings() throws Exception {
        Address addr = new Address("São Paulo", 12345, new int[]{101,102});
        Product p1 = new Product("", 0.0, 0, new HashSet<>());
        Product p2 = new Product("L".repeat(1000), 9999.99, 10, Set.of("longname"));
        Customer customer = new Customer("string_edge", List.of(p1,p2), addr);

        Customer decoded = serializeAndDeserialize(customer);
        assertEquals(customer, decoded);
    }

    @Test
    public void testLargeDouble() throws Exception {
        Address addr = new Address("São Paulo", 12345, new int[]{101,102});
        Product p = new Product("CryptoCoin", 1234567890.1234567, 1000, Set.of("finance"));
        Customer customer = new Customer("big_numbers", List.of(p), addr);

        Customer decoded = serializeAndDeserialize(customer);
        assertEquals(customer, decoded);
    }

    @Test
    void testRawByteArrayRoundtrip() throws Exception {
        byte[] originalBytes = new byte[]{1, 2, 3, 4, 5, 10, 20, 30};

        ByteArrayWrapper wrapper = new ByteArrayWrapper(originalBytes);
        byte[] serialized = encoder.encodeToByteArray(wrapper);

        ByteArrayWrapper deserialized = decoder.readAsTree(serialized).getAsObject(ByteArrayWrapper.class);

        assertArrayEquals(originalBytes, deserialized.data, "O array de bytes não deve ser alterado");
    }

    @Test
    void testObjectWithInternalArray() throws Exception {
        int[] numbers = new int[]{100, 200, 300, 400};
        ObjectWithArray original = new ObjectWithArray("testArray", numbers);

        byte[] serialized = encoder.encodeToByteArray(original);

        ObjectWithArray deserialized = decoder.readAsTree(serialized).getAsObject(ObjectWithArray.class);

        assertEquals(original.name, deserialized.name);
        assertArrayEquals(original.numbers, deserialized.numbers, "O array interno não deve ser alterado");

        byte[] reSerialized = encoder.encodeToByteArray(deserialized);
        assertArrayEquals(serialized, reSerialized, "O array de bytes serializado deve ser consistente");
    }

    @Test
    void testByteArray() throws Exception {
        byte[] bytesArray = new byte[]{10, 20, 30, 40, 50};

        byte[] serialized = encoder.encodeToByteArray(bytesArray);

        byte[] deserialized= decoder.readAsTree(serialized).getAsObject(byte[].class);

        byte[] reSerialized = encoder.encodeToByteArray(deserialized);
        assertArrayEquals(serialized, reSerialized, "O array de bytes serializado deve ser consistente");
    }

    @Test
    void testCollectionString() throws Exception {
       List<String> strings = new ArrayList<>(){{
           add("Teste1");
           add("Teste2");
       }};

        byte[] serialized = encoder.encodeToByteArray(strings);

        List<String> deserialized = decoder.readAsTree(serialized).getAsCollection(new CollectionReference<List<String>>(){});

        byte[] reSerialized = encoder.encodeToByteArray(deserialized);
        assertArrayEquals(serialized, reSerialized, "O array de String serializado deve ser consistente");
    }

    @Test
    void testEnum() throws Exception {
        List<TesteEnum> enums = new ArrayList<>(){{
            add(TesteEnum.ENUM_1);
            add(TesteEnum.ENUM_2);
            add(TesteEnum.ENUM_3);
        }};

        byte[] serialized = encoder.encodeToByteArray(enums);

        List<TesteEnum> deserialized = decoder.readAsTree(serialized).getAsCollection(new CollectionReference<List<TesteEnum>>(){});

        byte[] reSerialized = encoder.encodeToByteArray(deserialized);
        assertArrayEquals(serialized, reSerialized, "O array de TesteEnum serializado deve ser consistente");
    }

    @Test
    void testMapStringString() throws Exception {
        Map<String, String> map = new HashMap<>();
        map.put("key1", "value1");
        map.put("key2", "value2");

        byte[] serialized = encoder.encodeToByteArray(map);

        Map<String, Object> mapDecode = decoder.readAsTree(serialized).getAsMap();

        assertEquals(map.size(), mapDecode.size());

        map.forEach((key, value) -> {
            assertTrue(mapDecode.containsKey(key), "Chave ausente: " + key);
            assertEquals(value, mapDecode.get(key),
                    "Valor diferente para chave: " + key);
        });
    }

    @Test
    void testMapOfMapString() throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put("key1", new HashMap<>(){{
            put("key1.2", "value1.2");
        }});
        map.put("key2", new  HashMap<>(){{
            put("key2.2", "value2.2");
        }});

        byte[] serialized = encoder.encodeToByteArray(map);

        Map<String, Object> mapDecode = decoder.readAsTree(serialized).getAsMap();

        assertEquals(map.size(), mapDecode.size());

    }

    @Test
    void testMapOfListString() throws Exception {
        Map<String, List<String>> map = new HashMap<>();
        map.put("key1", new  ArrayList<>(){{
            add("value1.1");
            add("value1.2");
            add("value1.3");
        }});

        map.put("key2", new  ArrayList<>(){{
            add("value2.1");
            add("value2.2");
            add("value2.3");
        }});



        byte[] serialized = encoder.encodeToByteArray(map);

        Map<String, Object> mapDecode = decoder.readAsTree(serialized).getAsMap();

        assertEquals(map.size(), mapDecode.size());

    }

}
