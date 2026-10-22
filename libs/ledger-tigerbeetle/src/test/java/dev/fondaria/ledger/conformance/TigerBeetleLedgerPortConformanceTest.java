package dev.fondaria.ledger.conformance;

import com.tigerbeetle.Client;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.tigerbeetle.TigerBeetleLedgerAdapter;
import dev.fondaria.testing.TigerBeetleFixture;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * Task 2.6: runs {@link LedgerPortConformanceSuite} directly against a bare {@link
 * TigerBeetleLedgerAdapter} (Task 2.4) — the only {@code LedgerPort} implementation (ADR-0008).
 */
class TigerBeetleLedgerPortConformanceTest extends LedgerPortConformanceSuite {

  private static final AtomicLong FUND_LEDGER_SEQ = new AtomicLong(6_000);

  private static Client client;
  private static TigerBeetleLedgerAdapter adapter;

  @BeforeAll
  static void setUpAll() {
    client = TigerBeetleFixture.getInstance().getClient();
    adapter =
        new TigerBeetleLedgerAdapter(client, Duration.ofSeconds(5), ObservationRegistry.create());
  }

  @AfterAll
  static void tearDownAll() {
    client.close();
  }

  @Override
  protected LedgerPort port() {
    return adapter;
  }

  @Override
  protected long freshFundLedgerId() {
    return FUND_LEDGER_SEQ.incrementAndGet();
  }
}
