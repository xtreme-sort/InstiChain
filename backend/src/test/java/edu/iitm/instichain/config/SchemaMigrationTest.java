package edu.iitm.instichain.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.util.Set;
import java.util.TreeSet;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class SchemaMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("instichain")
            .withUsername("instichain")
            .withPassword("instichain");

    @Test
    void createsTheCompleteSchemaOnAnEmptyDatabase() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
        assertEquals(2, flyway.migrate().migrationsExecuted);

        Set<String> expected = Set.of("users", "email_verifications", "sessions", "administrator",
                "authority_keys", "clubs", "appointments", "competitions", "competition_results",
                "result_submissions", "result_recipients", "credentials", "fabric_transactions",
                "ledger_entries", "user_signing_keys", "flyway_schema_history");
        Set<String> actual = new TreeSet<>();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT table_name FROM information_schema.tables
                     WHERE table_schema = 'public'
                     """);
             var rows = statement.executeQuery()) {
            while (rows.next()) actual.add(rows.getString(1));
        }
        assertTrue(actual.containsAll(expected), () -> "Missing tables: " + difference(expected, actual));
    }

    private static Set<String> difference(Set<String> expected, Set<String> actual) {
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(actual);
        return missing;
    }
}
