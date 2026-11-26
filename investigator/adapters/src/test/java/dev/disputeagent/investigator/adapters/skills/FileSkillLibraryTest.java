package dev.disputeagent.investigator.adapters.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.disputeagent.investigator.application.port.skills.Skill;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSkillLibraryTest {

    private static final Path REPOSITORY_SKILLS = Path.of("../../skills");
    private static final Path POLICY_CORPUS = Path.of("../../data/policy-corpus");

    @Test
    void readsTheRepositorySkills() {
        List<Skill> skills = new FileSkillLibrary(REPOSITORY_SKILLS).skills();

        assertThat(skills).extracting(Skill::name)
                .containsExactly("amount-mismatch-checklist", "cancelled-recurring-checklist",
                        "duplicate-charge-checklist", "goods-not-received-checklist", "reading-an-authorisation-trail",
                        "reading-dispute-documents", "unauthorised-transaction-checklist");
        assertThat(skills).allSatisfy(s -> {
            assertThat(s.description()).hasSizeLessThan(300);
            assertThat(s.body()).startsWith("# ").doesNotContain("---\nname:");
        });
    }

    @Test
    void everyKebabCaseNameASkillCitesIsAPolicySectionOrASkill() throws IOException {
        Set<String> known = new HashSet<>(sectionIds());
        List<Skill> skills = new FileSkillLibrary(REPOSITORY_SKILLS).skills();
        skills.forEach(s -> known.add(s.name()));

        for (Skill skill : skills) {
            Matcher m = Pattern.compile("`([a-z0-9]+(?:-[a-z0-9]+)+)`").matcher(skill.body());
            while (m.find()) {
                assertThat(known).as("%s cites `%s`", skill.name(), m.group(1)).contains(m.group(1));
            }
        }
    }

    @Test
    void theNameMustMatchTheFolder(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("right-name"));
        Files.writeString(dir.resolve("right-name/SKILL.md"), "---\nname: wrong-name\ndescription: d\n---\n# Body\n");

        assertThatThrownBy(() -> new FileSkillLibrary(dir)).hasMessageContaining("must equal the folder name");
    }

    @Test
    void frontmatterIsRequiredAndCrlfIsAccepted(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("a-skill"));
        Files.writeString(dir.resolve("a-skill/SKILL.md"), "---\r\nname: a-skill\r\ndescription: Does a.\r\n---\r\n# A\r\n");

        assertThat(new FileSkillLibrary(dir).skills()).singleElement()
                .isEqualTo(new Skill("a-skill", "Does a.", "# A"));
        assertThatThrownBy(() -> FileSkillLibrary.parse("b", "# no frontmatter")).hasMessageContaining("frontmatter");
    }

    @Test
    void aMissingDirectoryFailsStartUp() {
        assertThatThrownBy(() -> new FileSkillLibrary(Path.of("no-such-dir"))).hasMessageContaining("not found");
    }

    private static Set<String> sectionIds() throws IOException {
        Set<String> ids = new HashSet<>();
        Pattern meta = Pattern.compile("^Section `([a-z0-9-]+)`;", Pattern.MULTILINE);
        try (Stream<Path> files = Files.list(POLICY_CORPUS)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".md")).toList()) {
                Matcher m = meta.matcher(Files.readString(f));
                while (m.find()) {
                    ids.add(m.group(1));
                }
            }
        }
        assertThat(ids).isNotEmpty();
        return ids;
    }
}
