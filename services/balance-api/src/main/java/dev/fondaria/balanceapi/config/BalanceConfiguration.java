package dev.fondaria.balanceapi.config;

import dev.fondaria.balanceapi.balance.BalanceService;
import dev.fondaria.balanceapi.participant.CachingParticipantDirectory;
import dev.fondaria.balanceapi.participant.JdbcParticipantDirectory;
import dev.fondaria.balanceapi.participant.ParticipantDirectory;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.valuation.NavCache;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Wires the balance read (Task 6.2). Constructor injection only (docs/ENGINEERING-RULES.md §4). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ParticipantDirectoryProperties.class)
public class BalanceConfiguration {

  /**
   * The JDBC directory behind its cache, as one bean. The pair is built here rather than each half
   * being a component, so there is exactly one {@link ParticipantDirectory} to inject and no way to
   * reach the uncached one by accident — which would put a database read back on the balance path.
   */
  @Bean
  public ParticipantDirectory participantDirectory(
      JdbcClient jdbc, ParticipantDirectoryProperties properties) {
    return new CachingParticipantDirectory(
        new JdbcParticipantDirectory(jdbc), properties.maximumCachedParticipants());
  }

  @Bean
  public BalanceService balanceService(
      ParticipantDirectory participants,
      LedgerPort ledger,
      NavCache navCache,
      AccountIdDeriver accountIds,
      Clock clock) {
    return new BalanceService(participants, ledger, navCache, accountIds, clock);
  }
}
