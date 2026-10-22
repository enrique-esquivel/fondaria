package dev.fondaria.contribution.plan.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.FundId;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Field-level parsing and validation for {@link PublishPlanVersionRequest}. */
class PublishPlanVersionRequestTest {

  @Test
  @DisplayName("a well-formed request parses every field")
  void parsesEveryField() {
    FundId fundId = FundId.of(UUID.randomUUID());
    PublishPlanVersionRequest request =
        new PublishPlanVersionRequest(
            " PLAN-1 ", "2026-01-01", 1, 1, 2, 5, 3, fundId.value().toString());

    assertThat(request.parsedPlanId()).isEqualTo("PLAN-1");
    assertThat(request.parsedEffectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(request.parsedVersion()).isEqualTo(1);
    assertThat(request.parsedEmployerMatchRatioNumerator()).isEqualTo(1);
    assertThat(request.parsedEmployerMatchRatioDenominator()).isEqualTo(2);
    assertThat(request.parsedVestingFullYears()).isEqualTo(5);
    assertThat(request.parsedDealingLagBusinessDays()).isEqualTo(3);
    assertThat(request.parsedFundId()).isEqualTo(fundId);
  }

  @Test
  @DisplayName("a blank planId is rejected")
  void blankPlanIdIsRejected() {
    PublishPlanVersionRequest request =
        new PublishPlanVersionRequest(
            "  ", "2026-01-01", 1, 1, 2, 5, 0, UUID.randomUUID().toString());

    assertThatThrownBy(request::parsedPlanId)
        .isInstanceOf(InvalidPlanVersionRequestException.class);
  }

  @Test
  @DisplayName("a missing fundId is rejected, naming the field")
  void missingFundIdIsRejected() {
    PublishPlanVersionRequest request =
        new PublishPlanVersionRequest("PLAN-1", "2026-01-01", 1, 1, 2, 5, 0, null);

    assertThatThrownBy(request::parsedFundId)
        .isInstanceOf(InvalidPlanVersionRequestException.class)
        .hasMessageContaining("fundId");
  }

  @Test
  @DisplayName("a malformed fundId is rejected")
  void malformedFundIdIsRejected() {
    PublishPlanVersionRequest request =
        new PublishPlanVersionRequest("PLAN-1", "2026-01-01", 1, 1, 2, 5, 0, "not-a-uuid");

    assertThatThrownBy(request::parsedFundId)
        .isInstanceOf(InvalidPlanVersionRequestException.class);
  }
}
