package dev.disputeagent.investigator.application.port.mcp;

/** The three MCP servers. Only {@link #ACTIONS} writes, and only with an approval token. */
public enum McpServer {
    LEDGER(false),
    POLICY(false),
    ACTIONS(true);

    private final boolean writes;

    McpServer(boolean writes) {
        this.writes = writes;
    }

    public boolean writes() {
        return writes;
    }
}
