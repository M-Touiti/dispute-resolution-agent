package dev.disputeagent.investigator.adapters.skills;

import dev.disputeagent.investigator.application.port.skills.Skill;
import dev.disputeagent.investigator.application.port.skills.SkillLibrary;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads {@code <dir>/<name>/SKILL.md}: a frontmatter block between {@code ---} lines with {@code name} and {@code
 * description}, then the procedure. The name must equal the folder name. Read once; a malformed skill fails start-up
 * rather than reaching the model.
 */
public final class FileSkillLibrary implements SkillLibrary {

    private final List<Skill> skills;

    public FileSkillLibrary(Path dir) {
        this.skills = read(dir);
    }

    @Override
    public List<Skill> skills() {
        return skills;
    }

    static List<Skill> read(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("skills directory not found: " + dir.toAbsolutePath().normalize());
        }
        List<Skill> out = new ArrayList<>();
        try (Stream<Path> folders = Files.list(dir)) {
            for (Path folder : folders.filter(Files::isDirectory).sorted().toList()) {
                Path file = folder.resolve("SKILL.md");
                if (Files.isRegularFile(file)) {
                    out.add(parse(folder.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(out);
    }

    static Skill parse(String folder, String text) {
        String normalised = text.replace("\r\n", "\n");
        if (!normalised.startsWith("---\n")) {
            throw new IllegalStateException(folder + "/SKILL.md must start with a --- frontmatter line");
        }
        int end = normalised.indexOf("\n---\n", 4);
        if (end < 0) {
            throw new IllegalStateException(folder + "/SKILL.md: the frontmatter is not closed");
        }
        Map<String, String> fields = new HashMap<>();
        for (String line : normalised.substring(4, end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                fields.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        String name = fields.getOrDefault("name", "");
        if (!name.equals(folder)) {
            throw new IllegalStateException(folder + "/SKILL.md: name '" + name + "' must equal the folder name");
        }
        return new Skill(name, fields.getOrDefault("description", ""), normalised.substring(end + 5).strip());
    }
}
