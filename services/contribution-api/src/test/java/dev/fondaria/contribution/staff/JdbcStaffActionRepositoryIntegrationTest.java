package dev.fondaria.contribution.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.contribution.support.ServiceIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JdbcStaffActionRepositoryIntegrationTest extends ServiceIntegrationTest {

  @Autowired private StaffActionRepository repository;
  @Autowired private JdbcClient jdbc;
  @Autowired private Clock clock;

  @Test
  @DisplayName("Same person requesting and approving is rejected by database constraint")
  void samePersonRequestingAndApprovingRejectedByDatabaseConstraint() {
    UUID actionId = UUID.randomUUID();
    Instant now = clock.instant();
    String requester = "operator-alice";

    StaffAction action =
        new StaffAction(
            actionId,
            StaffActionType.KILL_SWITCH,
            "Halt incoming batches",
            "{\"affectedPlans\": [\"plan-1\"]}",
            requester,
            now,
            StaffActionStatus.PENDING_APPROVAL,
            Optional.empty(),
            Optional.empty(),
            false,
            Optional.empty(),
            Optional.empty());

    repository.create(action, "{\"action\":\"create\"}");

    // Attempting direct DB update or repository approve with the same actor must fail on constraint
    assertThatThrownBy(
            () -> repository.approve(actionId, requester, now.plusSeconds(60), "{\"test\":true}"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("staff_action_four_eyes");

    // Also assert direct SQL update attempting to bypass code checks fails on database constraint
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "UPDATE workflow.staff_action SET status = 'APPROVED', approved_by = ?, approved_at = now() WHERE action_id = ?")
                    .params(requester, actionId)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("staff_action_four_eyes");

    // Case variation (e.g. Alice vs alice) is rejected by lower() constraint
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "UPDATE workflow.staff_action SET status = 'APPROVED', approved_by = ?, approved_at = now() WHERE action_id = ?")
                    .params("OPERATOR-ALICE", actionId)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("staff_action_four_eyes");
  }

  @Test
  @DisplayName("Every privileged action is reconstructable from the audit trail")
  void privilegedActionIsReconstructableFromAuditTrail() {
    UUID actionId = UUID.randomUUID();
    Instant requestedAt = clock.instant();
    Instant approvedAt = requestedAt.plusSeconds(120);

    StaffAction action =
        new StaffAction(
            actionId,
            StaffActionType.NAV_SUPERSESSION,
            "Override incorrect NAV value for fund 123",
            "{\"fundId\":\"123\",\"oldNav\":100000000,\"newNav\":100500000}",
            "operator-bob",
            requestedAt,
            StaffActionStatus.PENDING_APPROVAL,
            Optional.empty(),
            Optional.empty(),
            false,
            Optional.empty(),
            Optional.empty());

    repository.create(action, "{\"initialNav\":100000000}");
    repository.approve(actionId, "approver-carol", approvedAt, "{\"reviewConfirmed\":true}");

    List<StaffActionAuditEntry> auditTrail = repository.getAuditTrail(actionId);
    assertThat(auditTrail).hasSize(2);

    StaffActionAuditEntry requestEntry = auditTrail.get(0);
    assertThat(requestEntry.actionId()).isEqualTo(actionId);
    assertThat(requestEntry.actionType()).isEqualTo(StaffActionType.NAV_SUPERSESSION);
    assertThat(requestEntry.eventType()).isEqualTo(StaffActionAuditEventType.REQUESTED);
    assertThat(requestEntry.actor()).isEqualTo("operator-bob");
    assertThat(requestEntry.payloadJson()).contains("100000000");

    StaffActionAuditEntry approveEntry = auditTrail.get(1);
    assertThat(approveEntry.actionId()).isEqualTo(actionId);
    assertThat(approveEntry.actionType()).isEqualTo(StaffActionType.NAV_SUPERSESSION);
    assertThat(approveEntry.eventType()).isEqualTo(StaffActionAuditEventType.APPROVED);
    assertThat(approveEntry.actor()).isEqualTo("approver-carol");
    assertThat(approveEntry.payloadJson()).contains("reviewConfirmed");

    // Sequence must be strictly monotonic
    assertThat(approveEntry.sequence()).isEqualTo(requestEntry.sequence() + 1);
    // Prev hash must match previous entry's entry hash
    assertThat(approveEntry.prevHash()).isEqualTo(requestEntry.entryHash());
  }

  @Test
  @DisplayName("Audit trail is append-only and hash-chained")
  void auditTrailIsAppendOnlyAndHashChained() {
    UUID actionId = UUID.randomUUID();
    Instant now = clock.instant();

    StaffAction action =
        new StaffAction(
            actionId,
            StaffActionType.CORRECTION,
            "Manual cash correction",
            "{\"deltaCashMinor\":-5000}",
            "operator-dave",
            now,
            StaffActionStatus.PENDING_APPROVAL,
            Optional.empty(),
            Optional.empty(),
            false,
            Optional.empty(),
            Optional.empty());

    repository.create(action, "{\"reason\":\"typo\"}");

    // Append-only check: direct SQL UPDATE or DELETE on workflow.staff_action_audit is blocked by
    // trigger
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        "UPDATE workflow.staff_action_audit SET actor = 'malicious' WHERE action_id = ?")
                    .params(actionId)
                    .update())
        .hasMessageContaining("workflow.staff_action_audit is append-only");

    assertThatThrownBy(
            () ->
                jdbc.sql("DELETE FROM workflow.staff_action_audit WHERE action_id = ?")
                    .params(actionId)
                    .update())
        .hasMessageContaining("workflow.staff_action_audit is append-only");

    assertThatThrownBy(() -> jdbc.sql("TRUNCATE TABLE workflow.staff_action_audit").update())
        .hasMessageContaining("workflow.staff_action_audit is append-only");

    // Hash chain verification over all rows
    boolean isValid = repository.verifyAuditChain();
    assertThat(isValid).isTrue();
  }

  @Test
  @DisplayName("Break-glass action records reason, expiration and is immediately approved")
  void breakGlassActionLifecycle() {
    UUID actionId = UUID.randomUUID();
    Instant now = clock.instant();
    Instant expiresAt = now.plusSeconds(900);

    StaffAction action =
        new StaffAction(
            actionId,
            StaffActionType.KILL_SWITCH,
            "Emergency batch processor halt",
            "{\"scope\":\"ALL_BATCHES\"}",
            "emergency-operator",
            now,
            StaffActionStatus.APPROVED,
            Optional.of("emergency-operator"),
            Optional.of(now),
            true,
            Optional.of("Critical production incident INC-1234"),
            Optional.of(expiresAt));

    StaffAction created = repository.create(action, "{\"incident\":\"INC-1234\"}");
    assertThat(created.isBreakGlass()).isTrue();
    assertThat(created.status()).isEqualTo(StaffActionStatus.APPROVED);
    assertThat(created.breakGlassReason()).contains("Critical production incident INC-1234");
    assertThat(created.isExpired(now.plusSeconds(1000))).isTrue();
    assertThat(created.isExpired(now.plusSeconds(300))).isFalse();

    List<StaffActionAuditEntry> auditTrail = repository.getAuditTrail(actionId);
    assertThat(auditTrail).hasSize(1);
    assertThat(auditTrail.get(0).eventType())
        .isEqualTo(StaffActionAuditEventType.BREAK_GLASS_TRIGGERED);

    // Active count before expiration
    assertThat(repository.countActiveBreakGlassActions(now.plusSeconds(300)))
        .isGreaterThanOrEqualTo(1);

    // Automatically expires and active count resolves to 0 after expiration timestamp
    assertThat(repository.countActiveBreakGlassActions(now.plusSeconds(1000))).isEqualTo(0);
  }
}
