package dtm.serialization;

public record DecodeOptions(
        DescriptorObserver observer,
        LargeContentResolver largeContentResolver
) {
    public static final DecodeOptions DEFAULT = new DecodeOptions(null, null);

    public DecodeOptions withObserver(DescriptorObserver value) {
        return new DecodeOptions(value, largeContentResolver);
    }

    public DecodeOptions withLargeContentResolver(LargeContentResolver value) {
        return new DecodeOptions(observer, value);
    }
}
