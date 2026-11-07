package edu.iitm.instichain.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectionRebuildTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID admin = UUID.randomUUID();
    private final UUID advisor = UUID.randomUUID();
    private final UUID club = UUID.randomUUID();
    private final UUID appointment = UUID.randomUUID();
    private final Set<UUID> users = new HashSet<>(Set.of(admin, advisor));
    private final Set<UUID> receipts = new HashSet<>();
    private final Set<UUID> clubs = new HashSet<>();
    private final Map<UUID, Boolean> appointments = new HashMap<>();

    private final ProjectionRebuild.State state = new ProjectionRebuild.State() {
        @Override public boolean receiptExists(UUID id) { return receipts.contains(id); }
        @Override public boolean userExists(UUID id) { return users.contains(id); }
        @Override public boolean clubExists(UUID id) { return clubs.contains(id); }
        @Override public Boolean appointmentRevoked(UUID id) { return appointments.get(id); }
    };

    private JsonNode event(String type, UUID actor, Map<String, String> objects) {
        ObjectNode event = mapper.createObjectNode();
        event.put("request_id", UUID.randomUUID().toString());
        event.put("event_type", type);
        ObjectNode ids = event.putObject("object_ids");
        objects.forEach(ids::put);
        event.putObject("payload");
        event.put("actor_id", actor.toString());
        event.put("actor_key_id", UUID.randomUUID().toString());
        return event;
    }

    private List<JsonNode> history() {
        return List.of(
                event("CLUB_CREATED", admin, Map.of("club_id", club.toString())),
                event("ADVISOR_APPOINTED", admin, Map.of("club_id", club.toString(),
                        "user_id", advisor.toString(), "appointment_id", appointment.toString())),
                event("ROLE_REVOKED", advisor, Map.of("club_id", club.toString(),
                        "user_id", advisor.toString(), "appointment_id", appointment.toString())));
    }

    private List<ProjectionRebuild.Operation> operations(ProjectionRebuild.Plan plan) {
        return plan.actions().stream().map(ProjectionRebuild.Action::operation).toList();
    }

    @Test
    void restoresReceiptsBeforeTheProjectionsThatReferenceThem() {
        ProjectionRebuild.Plan plan = ProjectionRebuild.plan(history(), state);
        assertEquals(List.of(
                ProjectionRebuild.Operation.INSERT_RECEIPT, ProjectionRebuild.Operation.INSERT_CLUB,
                ProjectionRebuild.Operation.INSERT_RECEIPT, ProjectionRebuild.Operation.INSERT_APPOINTMENT,
                ProjectionRebuild.Operation.INSERT_RECEIPT, ProjectionRebuild.Operation.RECORD_REVOCATION),
                operations(plan));
        assertTrue(plan.skipped().isEmpty());
    }

    @Test
    void completeDatabaseNeedsNothing() {
        List<JsonNode> events = history();
        events.forEach(event -> receipts.add(UUID.fromString(event.path("request_id").asText())));
        clubs.add(club);
        appointments.put(appointment, true);
        ProjectionRebuild.Plan plan = ProjectionRebuild.plan(events, state);
        assertTrue(plan.actions().isEmpty());
        assertTrue(plan.skipped().isEmpty());
    }

    @Test
    void missingAccountsAreReportedNotInvented() {
        users.remove(advisor);
        ProjectionRebuild.Plan plan = ProjectionRebuild.plan(history(), state);
        assertEquals(List.of(ProjectionRebuild.Operation.INSERT_RECEIPT, ProjectionRebuild.Operation.INSERT_CLUB,
                ProjectionRebuild.Operation.INSERT_RECEIPT), operations(plan));
        // The appointment and the revocation signed by the missing advisor are both reported.
        assertEquals(2, plan.skipped().size());
        assertTrue(plan.skipped().stream().allMatch(skip -> skip.reason().contains("not on Fabric")));
    }

    @Test
    void credentialsAreNeverReconstructedFromTheLedger() {
        JsonNode issued = event("ACHIEVEMENT_ISSUED", advisor, Map.of());
        ProjectionRebuild.Plan plan = ProjectionRebuild.plan(List.of(issued), state);
        // The receipt is fully on Fabric, so it is restored; the credential rows are not.
        assertEquals(List.of(ProjectionRebuild.Operation.INSERT_RECEIPT), operations(plan));
        assertTrue(plan.skipped().getFirst().reason().contains("competitions"));
    }

    @Test
    void revocationOfAnUnknownAppointmentIsReported() {
        JsonNode revoked = event("ROLE_REVOKED", advisor, Map.of("appointment_id", UUID.randomUUID().toString()));
        ProjectionRebuild.Plan plan = ProjectionRebuild.plan(List.of(revoked), state);
        assertEquals(List.of(ProjectionRebuild.Operation.INSERT_RECEIPT), operations(plan));
        assertEquals(1, plan.skipped().size());
    }
}
