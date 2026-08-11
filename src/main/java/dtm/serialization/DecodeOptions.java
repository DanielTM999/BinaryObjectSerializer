package dtm.serialization;

public record DecodeOptions(
        DescriptorObserver observer,
        LargeContentResolver largeContentResolver,
        boolean deserializeOnDemand
) {
    public static final DecodeOptions DEFAULT = new DecodeOptions(null, null, false);

    public DecodeOptions(DescriptorObserver observer, LargeContentResolver largeContentResolver) {
        this(observer, largeContentResolver, false);
    }

    public DecodeOptions withObserver(DescriptorObserver value) {
        return new DecodeOptions(value, largeContentResolver, deserializeOnDemand);
    }

    public DecodeOptions withLargeContentResolver(LargeContentResolver value) {
        return new DecodeOptions(observer, value, deserializeOnDemand);
    }

    /**
     * Leaves a terminal BYTES or LARGE_CONTENT tree body connected to the input
     * stream until it is consumed or closed. This option only affects
     * readAsTree methods.
     */
    public DecodeOptions withDeserializeOnDemand(boolean value) {
        return new DecodeOptions(observer, largeContentResolver, value);
    }
}
