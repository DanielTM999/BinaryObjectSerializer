package dtm.serialization.exceptions;

public class StreamEndException extends DecodeSerializationException {
    public StreamEndException() {
        super();
    }

    public StreamEndException(String message) {
        super(message);
    }

    public StreamEndException(String message, Throwable cause) {
        super(message, cause);
    }

    public StreamEndException(Throwable cause) {
        super(cause);
    }
}
