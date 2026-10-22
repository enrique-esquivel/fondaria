package dev.fondaria.balanceapi.config;

import dev.fondaria.balanceapi.asof.AsOfBalanceService;
import dev.fondaria.balanceapi.asof.HistoricUnitsRepository;
import dev.fondaria.balanceapi.fund.FundDirectory;
import dev.fondaria.balanceapi.participant.ParticipantDirectory;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.TenantRefDeriver;
import dev.fondaria.rules.FundNavRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the balance-as-of read (Task 6.4). Constructor injection only (docs/ENGINEERING-RULES.md
 * §4).
 *
 * <p>No properties of its own: the endpoint introduces no environment variable. It reuses the
 * database pool and its {@code POSTGRES_QUERY_TIMEOUT}, the identity namespace behind {@code
 * AccountIdDeriver} and {@code TenantRefDeriver}, and the participant directory — every one of them
 * already required by the two endpoints this service ships.
 *
 * <p>{@code TenantRefDeriver} is the bean {@code MovementConfiguration} defines, deliberately
 * shared rather than duplicated: the two endpoints must derive the same {@code tenant_id} for the
 * same organization, and a second bean built from a second reading of the same property is a way
 * for them to stop doing so.
 */
@Configuration(proxyBeanMethods = false)
public class AsOfConfiguration {

  @Bean
  public AsOfBalanceService asOfBalanceService(
      ParticipantDirectory participants,
      FundDirectory funds,
      HistoricUnitsRepository holdings,
      FundNavRepository navs,
      AccountIdDeriver accountIds,
      TenantRefDeriver tenantRefs) {
    return new AsOfBalanceService(participants, funds, holdings, navs, accountIds, tenantRefs);
  }
}
