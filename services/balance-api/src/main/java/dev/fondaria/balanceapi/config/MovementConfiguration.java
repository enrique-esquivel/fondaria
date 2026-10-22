package dev.fondaria.balanceapi.config;

import dev.fondaria.balanceapi.fund.FundDirectory;
import dev.fondaria.balanceapi.movement.CursorCodec;
import dev.fondaria.balanceapi.movement.MovementRepository;
import dev.fondaria.balanceapi.movement.MovementService;
import dev.fondaria.balanceapi.participant.ParticipantDirectory;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.TenantRefDeriver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the movement history (Task 6.3). Constructor injection only (AGENTS.md §4). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CursorProperties.class)
public class MovementConfiguration {

  /**
   * The same {@code LEDGER_ID_NAMESPACE} {@code AccountIdDeriver} uses, and it must be: a tenant
   * ref derived under a different namespace would not match the {@code user_data_64} the ledger
   * already carries, and the query would prune to a partition holding none of this organization's
   * rows — an empty history for a participant who has one.
   */
  @Bean
  public TenantRefDeriver tenantRefDeriver(IdentityProperties identityProperties) {
    identityProperties.requireComplete();
    return new TenantRefDeriver(identityProperties.ledgerNamespace());
  }

  @Bean
  public CursorCodec cursorCodec(CursorProperties properties) {
    properties.requireComplete();
    return new CursorCodec(properties.cursorSecret());
  }

  @Bean
  public MovementService movementService(
      ParticipantDirectory participants,
      FundDirectory funds,
      MovementRepository movements,
      AccountIdDeriver accountIds,
      TenantRefDeriver tenantRefs,
      CursorCodec cursors) {
    return new MovementService(participants, funds, movements, accountIds, tenantRefs, cursors);
  }
}
