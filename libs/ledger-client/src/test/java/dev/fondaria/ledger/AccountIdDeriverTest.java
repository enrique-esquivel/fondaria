package dev.fondaria.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.ledger.LedgerCodes.AccountCode;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every guarantee here is one an account id must keep for the life of the ledger. A derivation that
 * is not stable strands balances under ids nothing looks up again; one that is not collision-free
 * merges two employers' money into one account. Neither failure announces itself.
 */
class AccountIdDeriverTest {

  private static final UUID NAMESPACE = UUID.fromString("6f9619ff-8b86-d011-b42d-00c04fc964ff");
  private static final UUID OTHER_NAMESPACE =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private final AccountIdDeriver deriver = new AccountIdDeriver(NAMESPACE);

  @Test
  @DisplayName("the same scoped account derives the same id every time")
  void scopedDerivationIsStable() {
    AccountId first = deriver.derive(AccountCode.EMPLOYER_COLLECTIONS, "MX:RFC:ACM010101AB1");
    AccountId second = deriver.derive(AccountCode.EMPLOYER_COLLECTIONS, "MX:RFC:ACM010101AB1");
    assertThat(first).isEqualTo(second);
  }

  @Test
  @DisplayName("a singleton account derives the same id every time")
  void singletonDerivationIsStable() {
    assertThat(deriver.derive(AccountCode.BANK_CLEARING))
        .isEqualTo(deriver.derive(AccountCode.BANK_CLEARING));
  }

  @Test
  @DisplayName("derivation is stable across instances, so a restart resolves the same account")
  void derivationIsStableAcrossInstances() {
    assertThat(new AccountIdDeriver(NAMESPACE).derive(AccountCode.BANK_CLEARING))
        .isEqualTo(new AccountIdDeriver(NAMESPACE).derive(AccountCode.BANK_CLEARING));
  }

  @Test
  @DisplayName("two organizations never share an EMPLOYER_COLLECTIONS account")
  void differentScopesDeriveDifferentAccounts() {
    assertThat(deriver.derive(AccountCode.EMPLOYER_COLLECTIONS, "MX:RFC:ACM010101AB1"))
        .isNotEqualTo(deriver.derive(AccountCode.EMPLOYER_COLLECTIONS, "MX:RFC:BCM010101AB1"));
  }

  @Test
  @DisplayName("one organization's accounts differ by account code")
  void differentCodesDeriveDifferentAccounts() {
    String scope = "MX:RFC:ACM010101AB1";
    assertThat(deriver.derive(AccountCode.EMPLOYER_COLLECTIONS, scope))
        .isNotEqualTo(deriver.derive(AccountCode.ALLOTMENT_SUSPENSE, scope));
  }

  @Test
  @DisplayName("a singleton and a same-code scoped account never collide")
  void singletonNeverCollidesWithScoped() {
    assertThat(deriver.derive(AccountCode.BANK_CLEARING))
        .isNotEqualTo(deriver.derive(AccountCode.BANK_CLEARING, "MX:RFC:ACM010101AB1"));
  }

  @Test
  @DisplayName("a different namespace derives a different id")
  void namespaceSeparatesDerivations() {
    assertThat(deriver.derive(AccountCode.BANK_CLEARING))
        .isNotEqualTo(new AccountIdDeriver(OTHER_NAMESPACE).derive(AccountCode.BANK_CLEARING));
  }

  @Test
  @DisplayName("an account id never collides with a transfer id derived from the same namespace")
  void accountIdsDoNotCollideWithTransferIds() {
    AccountId account = deriver.derive(AccountCode.EMPLOYER_COLLECTIONS, "ACME:key-1");
    TransferId transfer = new TransferIdDeriver(NAMESPACE).derive("ACME", "key-1");
    assertThat(AccountIdDeriver.toUuid(account)).isNotEqualTo(TransferIdDeriver.toUuid(transfer));
  }

  @Test
  @DisplayName("a blank scope is rejected rather than silently derived as a singleton")
  void blankScopeIsRejected() {
    assertThatThrownBy(() -> deriver.derive(AccountCode.EMPLOYER_COLLECTIONS, "  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scope");
  }

  @Test
  @DisplayName("UUID conversion round-trips without losing bits")
  void uuidConversionRoundTrips() {
    AccountId id = deriver.derive(AccountCode.EMPLOYER_COLLECTIONS, "MX:RFC:ACM010101AB1");
    assertThat(AccountIdDeriver.toAccountId(AccountIdDeriver.toUuid(id))).isEqualTo(id);
  }

  @Test
  @DisplayName("the derived id is a name-based (version 5) UUID")
  void derivesVersion5Uuid() {
    UUID uuid = AccountIdDeriver.toUuid(deriver.derive(AccountCode.BANK_CLEARING));
    assertThat(uuid.version()).isEqualTo(5);
    assertThat(uuid.variant()).isEqualTo(2);
  }
}
