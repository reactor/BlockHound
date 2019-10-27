package reactor.blockhound;

public class BlockingOperationError extends Error {

    private static final long serialVersionUID = 4980196508457280342L;

    public BlockingOperationError(String message) {
        super(message);
    }

}

