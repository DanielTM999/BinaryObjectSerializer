package dtm.serialization.exceptions;

public class EncodeSerializationException extends SerializationException {
    public EncodeSerializationException() {
        super();
    }

    public EncodeSerializationException(String message) {
        super(message);
    }

    public EncodeSerializationException(String message, Throwable cause) {
        super(message, cause);
    }

    public EncodeSerializationException(Throwable cause) {
        super(cause);
    }
}
