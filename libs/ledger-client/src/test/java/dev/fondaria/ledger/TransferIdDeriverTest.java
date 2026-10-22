package dev.fondaria.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferIdDeriverTest {

  // Fixed test vector, cross-checked against Python's stdlib uuid.uuid5 (a trusted RFC 4122
  // reference implementation) so this stays a hardcoded-expected-value test, not a
  // tautological "does the code agree with itself" test.
  private static final UUID TENANT_NAMESPACE =
      UUID.fromString("c3b1a5b0-2f0a-4e0a-9c3e-1f2a3b4c5d6e");
  private static final String CLIENT_ID = "client-acme-01";
  private static final String IDEMPOTENCY_KEY = "8f14e45f-ceea-467e-bcbe-6c6f14eb8b17";
  private static final TransferId EXPECTED =
      TransferId.of(5578367244136700075L, -7177138550118050112L);

  private final TransferIdDeriver deriver = new TransferIdDeriver(TENANT_NAMESPACE);

  @Test
  void matchesFixedVectorFromReferenceImplementation() {
    assertThat(deriver.derive(CLIENT_ID, IDEMPOTENCY_KEY)).isEqualTo(EXPECTED);
  }

  @Test
  void isStableAcrossRepeatedCallsAndInstances() {
    TransferId first = deriver.derive(CLIENT_ID, IDEMPOTENCY_KEY);
    TransferId second = new TransferIdDeriver(TENANT_NAMESPACE).derive(CLIENT_ID, IDEMPOTENCY_KEY);

    assertThat(first).isEqualTo(second).isEqualTo(EXPECTED);
  }

  @Test
  void differentClientIdSameKeyProducesDifferentId() {
    TransferId original = deriver.derive(CLIENT_ID, IDEMPOTENCY_KEY);
    TransferId otherClient = deriver.derive("different-client", IDEMPOTENCY_KEY);

    assertThat(otherClient).isNotEqualTo(original);
  }

  @Test
  void differentKeySameClientIdProducesDifferentId() {
    TransferId original = deriver.derive(CLIENT_ID, IDEMPOTENCY_KEY);
    TransferId otherKey = deriver.derive(CLIENT_ID, "a-different-idempotency-key");

    assertThat(otherKey).isNotEqualTo(original);
  }

  @Test
  void differentTenantNamespaceSameClientAndKeyProducesDifferentId() {
    TransferId original = deriver.derive(CLIENT_ID, IDEMPOTENCY_KEY);
    TransferId otherTenant =
        new TransferIdDeriver(UUID.randomUUID()).derive(CLIENT_ID, IDEMPOTENCY_KEY);

    assertThat(otherTenant).isNotEqualTo(original);
  }

  @Test
  void rejectsNullConstructorArgument() {
    assertThatThrownBy(() -> new TransferIdDeriver(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void rejectsNullDeriveArguments() {
    assertThatThrownBy(() -> deriver.derive(null, IDEMPOTENCY_KEY))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> deriver.derive(CLIENT_ID, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void uuidRoundTripsThroughTransferIdLosslessOverManyRandomValues() {
    Random random = new Random(42);
    for (int i = 0; i < 10_000; i++) {
      UUID original = new UUID(random.nextLong(), random.nextLong());

      TransferId transferId = TransferIdDeriver.toTransferId(original);
      UUID roundTripped = TransferIdDeriver.toUuid(transferId);

      assertThat(roundTripped).isEqualTo(original);
      assertThat(transferId.mostSignificantBits()).isEqualTo(original.getMostSignificantBits());
      assertThat(transferId.leastSignificantBits()).isEqualTo(original.getLeastSignificantBits());
    }
  }

  @Test
  void derivedIdsAreVersion5AndVariantRfc4122() {
    UUID uuid = TransferIdDeriver.toUuid(deriver.derive(CLIENT_ID, IDEMPOTENCY_KEY));

    assertThat(uuid.version()).isEqualTo(5);
    assertThat(uuid.variant()).isEqualTo(2);
  }

  @Test
  void manyDistinctKeysProduceManyDistinctIds() {
    Set<TransferId> ids = new HashSet<>();
    for (int i = 0; i < 1_000; i++) {
      ids.add(deriver.derive(CLIENT_ID, "key-" + i));
    }

    assertThat(ids).hasSize(1_000);
  }
}
