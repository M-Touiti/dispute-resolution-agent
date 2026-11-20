package dev.disputeagent.investigator.application.investigation;

import dev.disputeagent.investigator.application.port.llm.ToolDefinition;
import dev.disputeagent.investigator.application.port.skills.Skill;
import dev.disputeagent.investigator.application.port.skills.SkillLibrary;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Progressive disclosure, client-side: the system prompt lists each skill's name and description, and the model
 * loads a skill's procedure through the local {@code load_skill} tool when it needs it. Not the hosted Skills API.
 *
 * <p>The index and the tool schema are sorted by name, so they are part of the stable, cached prefix. Adding or
 * changing a skill changes the prompt and therefore re-records the fixtures (ADR 0001).
 */
final class Skills {

    static final String TOOL_NAME = "load_skill";

    private final Map<String, Skill> byName;

    Skills(SkillLibrary library) {
        Map<String, Skill> sorted = new LinkedHashMap<>();
        library.skills().stream().sorted(Comparator.comparing(Skill::name)).forEach(skill -> {
            if (sorted.putIfAbsent(skill.name(), skill) != null) {
                throw new IllegalStateException("duplicate skill " + skill.name());
            }
        });
        this.byName = sorted;
    }

    boolean isEmpty() {
        return byName.isEmpty();
    }

    /** The section appended to the system prompt; empty when there are no skills. */
    String index() {
        if (byName.isEmpty()) {
            return "";
        }
        return "\n\n## Skills\n\nProcedures you can load with the `" + TOOL_NAME + "` tool before you need them:\n\n"
                + byName.values().stream().map(s -> "- `" + s.name() + "`: " + s.description())
                        .collect(Collectors.joining("\n"))
                + "\n";
    }

    /** A strict tool whose only argument is the skill name, constrained to the known names. */
    ToolDefinition tool() {
        String names = byName.keySet().stream().map(n -> "\"" + n + "\"").collect(Collectors.joining(","));
        String schema = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\",\"enum\":[" + names
                + "],\"description\":\"The skill to load.\"}},\"required\":[\"name\"],\"additionalProperties\":false}";
        return new ToolDefinition(TOOL_NAME,
                "Load the full procedure of one skill listed in the system prompt. Load a skill before the step it "
                        + "covers; its text is part of your instructions.",
                schema, true);
    }

    /** The body of the named skill, wrapped so the model sees where it starts and ends. */
    Optional<String> load(String name) {
        return Optional.ofNullable(byName.get(name))
                .map(s -> "<skill name=\"" + s.name() + "\">\n" + s.body() + "\n</skill>");
    }

    List<String> names() {
        return List.copyOf(byName.keySet());
    }
}
