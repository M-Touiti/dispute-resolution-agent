package dev.disputeagent.investigator.bootstrap;

import dev.disputeagent.investigator.application.port.llm.LlmPort;

/**
 * The live model, distinct from the {@link LlmPort} the use cases get (which may record around it or replay without
 * it). Created lazily: replay mode never builds a client and needs no key.
 */
@FunctionalInterface
public interface LiveLlm extends LlmPort {
}
