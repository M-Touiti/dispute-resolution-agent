package dev.disputeagent.ledger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * One PostgreSQL per test JVM, built exactly like the compose database: the same image, the same init scripts
 * ({@code deploy/compose/postgres/initdb}) and the generated seed. The application connects as the read-only
 * {@code ledger_reader} role, as it does in compose.
 *
 * <p>The seed must exist ({@code uv run python generate.py} in {@code data/}). A missing seed fails the tests; it
 * never skips them.
 */
public final class LedgerDatabase {

    public static final Path DATA = Path.of("../../data/generated");
    private static final Path INITDB = Path.of("../../deploy/compose/postgres/initdb");
    private static final String IMAGE = "pgvector/pgvector:0.8.7-pg18-trixie"; // same as docker-compose.yml

    private static PostgreSQLContainer container;

    private LedgerDatabase() {
    }

    public static synchronized PostgreSQLContainer container() {
        if (container == null) {
            Path seed = DATA.resolve("seed.sql");
            if (!Files.isRegularFile(seed)) {
                throw new IllegalStateException(seed.toAbsolutePath().normalize()
                        + " is missing: run `uv run python generate.py` in data/ first");
            }
            var c = new PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("disputes")
                    .withUsername("disputes")
                    .withPassword("disputes")
                    .withCopyFileToContainer(MountableFile.forHostPath(seed), "/seed/seed.sql");
            try (Stream<Path> scripts = Files.list(INITDB)) {
                for (Path script : scripts.sorted().toList()) {
                    c.withCopyFileToContainer(MountableFile.forHostPath(script, 0755),
                            "/docker-entrypoint-initdb.d/" + script.getFileName());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            c.start();
            container = c;
        }
        return container;
    }

    /** Points the application at the container as {@code ledger_reader}. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> container().getJdbcUrl());
        registry.add("spring.datasource.username", () -> "ledger_reader");
        registry.add("spring.datasource.password", () -> "ledger_reader");
    }
}
