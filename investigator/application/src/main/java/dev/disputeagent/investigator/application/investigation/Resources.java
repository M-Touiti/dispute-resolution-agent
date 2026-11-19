package dev.disputeagent.investigator.application.investigation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads prompts and schemas from the classpath. Line endings are normalised so the prompt bytes never vary. */
public final class Resources {

    private Resources() {
    }

    public static String text(Class<?> anchor, String name) {
        try (InputStream in = anchor.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + name + " next to " + anchor.getName());
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
