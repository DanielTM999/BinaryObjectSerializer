package dtm.serialization.exceptions;

public class DecodeSerializationException extends SerializationException {
    public DecodeSerializationException() {
        super();
    }

    public DecodeSerializationException(String message) {
        super(message);
    }

    public DecodeSerializationException(String message, Throwable cause) {
        super(message, cause);
    }

    public DecodeSerializationException(Throwable cause) {
        super(cause);
    }
}
