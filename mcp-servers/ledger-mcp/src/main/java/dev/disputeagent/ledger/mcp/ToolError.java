package dev.disputeagent.ledger.mcp;

/**
 * A tool call that cannot be answered. Returned to the client as {@code isError: true} with the text
 * {@code CODE: message}, so the model can correct its arguments. Messages never carry SQL or stack traces.
 */
final class ToolError extends RuntimeException {

    enum Code { INVALID_ARGUMENT, NOT_FOUND, UNAVAILABLE }

    private final Code code;

    ToolError(Code code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    static ToolError invalid(String message) {
        return new ToolError(Code.INVALID_ARGUMENT, message);
    }

    static ToolError notFound(String message) {
        return new ToolError(Code.NOT_FOUND, message);
    }

    String text() {
        return code + ": " + getMessage();
    }
}
