package edu.iitm.instichain.audit;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Plans which institutional projections can be restored from Fabric alone. Only rows whose
 * every column is on the ledger are planned; the plan only ever inserts missing rows or
 * records a missing revocation, and never overwrites or deletes.
 */
public final class ProjectionRebuild {
    private ProjectionRebuild() {}

    /** Events whose projections need data that was never written to Fabric. */
    static final Map<String, String> NOT_REBUILDABLE = Map.of(
            "ADMIN_BOOTSTRAPPED", "the administrator key's verification reference is private",
            "ACHIEVEMENT_ISSUED", "credentials reference competitions, which are not on Fabric",
            "ACHIEVEMENT_REVOKED", "credentials reference competitions, which are not on Fabric",
            "KEY_ROTATED", "key rows carry private verification references",
            "KEY_RECOVERED", "key rows carry private verification references",
            "KEY_REVOKED", "key rows carry private verification references");

    public enum Operation { INSERT_RECEIPT, INSERT_CLUB, INSERT_APPOINTMENT, RECORD_REVOCATION }
    public record Action(Operation operation, UUID requestId, UUID subjectId, JsonNode event) {}
    public record Skip(UUID requestId, String eventType, String reason) {}
    public record Plan(List<Action> actions, List<Skip> skipped) {}

    /** Read-only view of what PostgreSQL already holds. */
    public interface State {
        boolean receiptExists(UUID requestId);
        boolean userExists(UUID userId);
        boolean clubExists(UUID clubId);
        /** null if absent, otherwise whether the appointment is already revoked. */
        Boolean appointmentRevoked(UUID appointmentId);
    }

    public static Plan plan(List<JsonNode> events, State state) {
        List<Action> actions = new ArrayList<>();
        List<Skip> skipped = new ArrayList<>();
        Set<UUID> receipts = new HashSet<>();
        Set<UUID> clubs = new HashSet<>();
        Map<UUID, Boolean> appointments = new LinkedHashMap<>();
        for (JsonNode event : events) {
            UUID id = UUID.fromString(event.path("request_id").asText());
            String type = event.path("event_type").asText();
            JsonNode objects = event.path("object_ids");
            UUID actor = UUID.fromString(event.path("actor_id").asText());
            // Users are private and never on Fabric, so a missing account blocks the whole event.
            if (!state.userExists(actor)) {
                skipped.add(new Skip(id, type, "the signing account is missing; users are not on Fabric"));
                continue;
            }
            boolean hasReceipt = receipts.contains(id) || state.receiptExists(id);
            if (!hasReceipt) {
                actions.add(new Action(Operation.INSERT_RECEIPT, id, id, event));
                receipts.add(id);
            }
            switch (type) {
                case "CLUB_CREATED" -> {
                    UUID club = UUID.fromString(objects.path("club_id").asText());
                    if (!clubs.contains(club) && !state.clubExists(club)) {
                        actions.add(new Action(Operation.INSERT_CLUB, id, club, event));
                        clubs.add(club);
                    }
                }
                case "ADVISOR_APPOINTED", "ROLE_GRANTED" -> {
                    UUID appointment = UUID.fromString(objects.path("appointment_id").asText());
                    UUID club = UUID.fromString(objects.path("club_id").asText());
                    UUID holder = UUID.fromString(objects.path("user_id").asText());
                    if (appointments.containsKey(appointment) || state.appointmentRevoked(appointment) != null) break;
                    if (!clubs.contains(club) && !state.clubExists(club)) {
                        skipped.add(new Skip(id, type, "the club is missing and cannot be restored"));
                    } else if (!state.userExists(holder)) {
                        skipped.add(new Skip(id, type, "the appointed account is missing; users are not on Fabric"));
                    } else {
                        actions.add(new Action(Operation.INSERT_APPOINTMENT, id, appointment, event));
                        appointments.put(appointment, false);
                    }
                }
                case "ROLE_REVOKED" -> {
                    UUID appointment = UUID.fromString(objects.path("appointment_id").asText());
                    Boolean revoked = appointments.containsKey(appointment)
                            ? appointments.get(appointment) : state.appointmentRevoked(appointment);
                    if (revoked == null) {
                        skipped.add(new Skip(id, type, "the revoked appointment is missing and cannot be restored"));
                    } else if (!revoked) {
                        actions.add(new Action(Operation.RECORD_REVOCATION, id, appointment, event));
                        appointments.put(appointment, true);
                    }
                }
                default -> {
                    String reason = NOT_REBUILDABLE.get(type);
                    if (reason != null) skipped.add(new Skip(id, type, "projection not restored: " + reason));
                }
            }
        }
        return new Plan(actions, skipped);
    }

    static OffsetDateTime time(JsonNode node, String field) {
        return OffsetDateTime.parse(node.path(field).asText());
    }
}
