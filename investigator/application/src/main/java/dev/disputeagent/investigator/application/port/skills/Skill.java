package dev.disputeagent.investigator.application.port.skills;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An Agent Skill ({@code skills/<name>/SKILL.md}): procedural knowledge the model loads on demand. The system prompt
 * carries only {@code name} and {@code description}; {@code body} is returned by the {@code load_skill} tool.
 * Skills are written in this repository, so their text is trusted, unlike tool results.
 */
public record Skill(String name, String description, String body) {

    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    public Skill {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(body, "body");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("skill name must be lowercase words and hyphens: " + name);
        }
        if (description.isBlank() || body.isBlank()) {
            throw new IllegalArgumentException("skill " + name + " needs a description and a body");
        }
        if (description.contains("\n")) {
            throw new IllegalArgumentException("skill " + name + ": the description is one line");
        }
    }
}
