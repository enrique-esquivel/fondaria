package dev.fondaria.statementworker.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.statementworker.ParticipantKey;
import dev.fondaria.statementworker.config.StatementProperties;
import dev.fondaria.statementworker.contribution.ContributionSource;
import dev.fondaria.statementworker.contribution.ContributionTotals;
import dev.fondaria.statementworker.render.RabbitMetrics;
import dev.fondaria.statementworker.snapshot.OpeningSnapshot;
import dev.fondaria.statementworker.snapshot.OpeningSnapshotRepository;
import dev.fondaria.statementworker.snapshot.PositionFileReader;
import dev.fondaria.statementworker.snapshot.SnapshotObjectReader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** {@link StatementScheduler} against fakes — no Kafka, Postgres or object store is real here. */
class StatementSchedulerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Instant SIGNED_OFF_AT = Instant.parse("2026-08-31T23:30:00.000000Z");
  private static final Instant CLOSING_RUN_STARTED_AT =
      Instant.parse("2026-08-31T20:00:00.000000Z");
  private static final UUID CLOSING_RUN_ID = UUID.randomUUID();
  private static final Clock CLOCK = Clock.fixed(SIGNED_OFF_AT, ZoneOffset.UTC);

  private final Map<String, byte[]> objects = new HashMap<>();
  private final Map<LocalDate, OpeningSnapshot> openingByFirstOfMonth = new HashMap<>();
  private final Map<UUID, Instant> startedAtByRunId =
      new HashMap<>(Map.of(CLOSING_RUN_ID, CLOSING_RUN_STARTED_AT));
  private final Map<ParticipantKey, ContributionTotals> contributionsByParticipant =
      new HashMap<>();
  private final FakeRunRepository runs = new FakeRunRepository();
  private Instant lastPostedBeforeSeen;

  private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final RabbitMetrics rabbitMetrics = new RabbitMetrics(meterRegistry);

  private StatementScheduler scheduler;

  @BeforeEach
  void setUp() {
    doAnswer(
            invocation -> {
              CorrelationData cd = invocation.getArgument(3);
              if (cd != null) {
                cd.getFuture().complete(new CorrelationData.Confirm(true, null));
              }
              return null;
            })
        .when(rabbitTemplate)
        .convertAndSend(anyString(), anyString(), anyString(), any(CorrelationData.class));

    OpeningSnapshotRepository openingSnapshots =
        new OpeningSnapshotRepository() {
          @Override
          public Optional<OpeningSnapshot> latestCompletedBefore(LocalDate firstDayOfMonth) {
            return Optional.ofNullable(openingByFirstOfMonth.get(firstDayOfMonth));
          }

          @Override
          public Instant startedAtOf(UUID runId) {
            Instant startedAt = startedAtByRunId.get(runId);
            if (startedAt == null) {
              throw new IllegalStateException("no fixture snapshot_run for " + runId);
            }
            return startedAt;
          }
        };
    SnapshotObjectReader snapshotObjects =
        objectKey -> {
          byte[] bytes = objects.get(objectKey);
          if (bytes == null) {
            throw new IllegalStateException("no fixture object for " + objectKey);
          }
          return new ByteArrayInputStream(bytes);
        };
    ContributionSource contributions =
        (opening, closing, postedBefore) -> {
          lastPostedBeforeSeen = postedBefore;
          return Map.copyOf(contributionsByParticipant);
        };

    StatementProperties properties = new StatementProperties("localhost", 1, Duration.ofSeconds(5));
    scheduler =
        new StatementScheduler(
            openingSnapshots,
            snapshotObjects,
            new PositionFileReader(MAPPER),
            contributions,
            runs,
            rabbitTemplate,
            CLOCK,
            properties,
            rabbitMetrics);
  }

  @Test
  @DisplayName("an event that is not a month end triggers no statement run")
  void notAMonthEndIsANoOp() {
    scheduler.handle(closeSignedOffEvent(LocalDate.of(2026, 8, 15), "closing-key", 0, false));

    verify(rabbitTemplate, never())
        .convertAndSend(anyString(), anyString(), anyString(), any(CorrelationData.class));
    assertThat(runs.opened).isEmpty();
  }

  @Test
  @DisplayName("a closing snapshot whose own count disagrees with the event fails loudly")
  void positionCountMismatchFailsLoudly() {
    OrganizationId orgId = new OrganizationId(UUID.randomUUID());
    objects.put("closing-key", gzip(positionLine(orgId, "ref-1", UUID.randomUUID(), 1_000L)));

    assertThatThrownBy(
            () ->
                scheduler.handle(
                    closeSignedOffEvent(LocalDate.of(2026, 8, 31), "closing-key", 2, true)))
        .isInstanceOf(StatementFanOutException.class)
        .hasMessageContaining("does not match");
  }

  @Test
  @DisplayName(
      "a participant holding more than one fund fails loudly rather than dropping a fund silently")
  void multiFundParticipantFailsLoudly() {
    OrganizationId orgId = new OrganizationId(UUID.randomUUID());
    objects.put(
        "closing-key",
        gzip(
            positionLine(orgId, "ref-1", UUID.randomUUID(), 1_000L),
            positionLine(orgId, "ref-1", UUID.randomUUID(), 2_000L)));

    assertThatThrownBy(
            () ->
                scheduler.handle(
                    closeSignedOffEvent(LocalDate.of(2026, 8, 31), "closing-key", 2, true)))
        .isInstanceOf(StatementFanOutException.class)
        .hasMessageContaining("collapse");
  }

  @Test
  @DisplayName("genesis: no prior snapshot means opening is zero for every participant")
  void genesisHasZeroOpening() {
    OrganizationId orgId = new OrganizationId(UUID.randomUUID());
    objects.put("closing-key", gzip(positionLine(orgId, "ref-1", UUID.randomUUID(), 47_382_15L)));
    // No entry in openingByFirstOfMonth: latestCompletedBefore returns empty.

    scheduler.handle(closeSignedOffEvent(LocalDate.of(2026, 8, 31), "closing-key", 1, true));

    StatementJob job = capturedJob();
    assertThat(job.openingCents()).isZero();
    assertThat(job.closingCents()).isEqualTo(47_382_15L);
  }

  @Test
  @DisplayName("opening, contributions and closing are exactly what the fixtures name")
  void computesTheWaterfallInputsExactly() {
    OrganizationId orgId = new OrganizationId(UUID.randomUUID());
    String ref = "ref-1";
    UUID fundId = UUID.randomUUID();

    openingByFirstOfMonth.put(
        LocalDate.of(2026, 8, 1), new OpeningSnapshot(LocalDate.of(2026, 7, 31), "opening-key"));
    objects.put("opening-key", gzip(positionLine(orgId, ref, fundId, 45_100_00L)));
    objects.put("closing-key", gzip(positionLine(orgId, ref, fundId, 47_382_15L)));
    contributionsByParticipant.put(
        new ParticipantKey(orgId, ref),
        ContributionTotals.zero(CurrencyCode.MXN)
            .plus("EMPLOYEE", Money.of(90_000L, CurrencyCode.MXN))
            .plus("EMPLOYER", Money.of(90_000L, CurrencyCode.MXN)));

    scheduler.handle(closeSignedOffEvent(LocalDate.of(2026, 8, 31), "closing-key", 1, true));

    StatementJob job = capturedJob();
    assertThat(job.openingCents()).isEqualTo(45_100_00L);
    assertThat(job.closingCents()).isEqualTo(47_382_15L);
    assertThat(job.contributionsEmployeeCents()).isEqualTo(90_000L);
    assertThat(job.contributionsEmployerCents()).isEqualTo(90_000L);
    assertThat(job.contributionsVoluntaryCents()).isZero();
    assertThat(job.generatedAt()).isEqualTo(SIGNED_OFF_AT);
    assertThat(runs.opened).containsExactly("2026-08");
    assertThat(
            meterRegistry
                .get("rabbitmq_publish_duration_seconds")
                .tag("destination", StatementJobsTopic.NAME)
                .timer()
                .count())
        .isPositive();
  }

  @Test
  @DisplayName(
      "contributions are bounded by the closing snapshot's own started_at, not just the date range")
  void contributionsAreBoundedByTheClosingSnapshotsStartedAt() {
    OrganizationId orgId = new OrganizationId(UUID.randomUUID());
    objects.put("closing-key", gzip(positionLine(orgId, "ref-1", UUID.randomUUID(), 1_000L)));

    scheduler.handle(closeSignedOffEvent(LocalDate.of(2026, 8, 31), "closing-key", 1, true));

    assertThat(lastPostedBeforeSeen)
        .as("the postedBefore cutoff passed to ContributionSource")
        .isEqualTo(CLOSING_RUN_STARTED_AT);
  }

  @Test
  @DisplayName(
      "close.signed-off.v1 naming a snapshot run with no workflow.snapshot_run row fails loudly")
  void missingSnapshotRunFailsLoudly() {
    UUID unknownRunId = UUID.randomUUID();
    CloseSignedOffEvent event =
        new CloseSignedOffEvent(
            "v1", LocalDate.of(2026, 8, 31), SIGNED_OFF_AT, unknownRunId, "closing-key", 1, true);

    assertThatThrownBy(() -> scheduler.handle(event)).isInstanceOf(StatementFanOutException.class);
    verify(rabbitTemplate, never())
        .convertAndSend(anyString(), anyString(), anyString(), any(CorrelationData.class));
  }

  @Test
  @DisplayName(
      "an opening snapshot from the wrong month fails loudly rather than misstating the opening balance")
  void openingSnapshotFromTheWrongMonthFailsLoudly() {
    OrganizationId orgId = new OrganizationId(UUID.randomUUID());
    objects.put("closing-key", gzip(positionLine(orgId, "ref-1", UUID.randomUUID(), 1_000L)));
    // Two months back instead of the immediately prior one — a missed pass fell back further
    // than one month.
    openingByFirstOfMonth.put(
        LocalDate.of(2026, 8, 1), new OpeningSnapshot(LocalDate.of(2026, 6, 30), "opening-key"));
    objects.put("opening-key", gzip(positionLine(orgId, "ref-1", UUID.randomUUID(), 500L)));

    assertThatThrownBy(
            () ->
                scheduler.handle(
                    closeSignedOffEvent(LocalDate.of(2026, 8, 31), "closing-key", 1, true)))
        .isInstanceOf(StatementFanOutException.class)
        .hasMessageContaining("2026-06-30");
  }

  @Test
  @DisplayName("an unknown allotment source is reclassified as permanent, not retried forever")
  void unknownAllotmentSourceIsReclassifiedPermanent() {
    OrganizationId orgId = new OrganizationId(UUID.randomUUID());
    objects.put("closing-key", gzip(positionLine(orgId, "ref-1", UUID.randomUUID(), 1_000L)));
    ContributionSource throwing =
        (opening, closing, postedBefore) -> {
          throw new IllegalArgumentException("unknown allotment source: BONUS");
        };
    StatementScheduler withThrowingSource =
        new StatementScheduler(
            new OpeningSnapshotRepository() {
              @Override
              public Optional<OpeningSnapshot> latestCompletedBefore(LocalDate firstDayOfMonth) {
                return Optional.empty();
              }

              @Override
              public Instant startedAtOf(UUID runId) {
                return CLOSING_RUN_STARTED_AT;
              }
            },
            objectKey -> new ByteArrayInputStream(objects.get(objectKey)),
            new PositionFileReader(MAPPER),
            throwing,
            runs,
            rabbitTemplate,
            CLOCK,
            new StatementProperties("localhost", 1, Duration.ofSeconds(5)),
            rabbitMetrics);

    assertThatThrownBy(
            () ->
                withThrowingSource.handle(
                    closeSignedOffEvent(LocalDate.of(2026, 8, 31), "closing-key", 1, true)))
        .isInstanceOf(StatementFanOutException.class);
  }

  @Test
  @DisplayName("a period already COMPLETED is skipped, not redundantly reprocessed")
  void completedPeriodIsSkipped() {
    runs.existing.put(
        "2026-08",
        new StatementRun(
            "2026-08",
            LocalDate.of(2026, 8, 31),
            StatementRunStatus.COMPLETED,
            SIGNED_OFF_AT,
            Optional.of(SIGNED_OFF_AT),
            Optional.of(1L),
            1L,
            Optional.of(1L),
            Optional.empty()));

    scheduler.handle(closeSignedOffEvent(LocalDate.of(2026, 8, 31), "closing-key", 1, true));

    verify(rabbitTemplate, never())
        .convertAndSend(anyString(), anyString(), anyString(), any(CorrelationData.class));
    assertThat(runs.opened).isEmpty();
  }

  @Test
  @DisplayName(
      "a period still RUNNING is reprocessed, never skipped on the strength of an existing row")
  void runningPeriodIsStillReprocessed() {
    OrganizationId orgId = new OrganizationId(UUID.randomUUID());
    objects.put("closing-key", gzip(positionLine(orgId, "ref-1", UUID.randomUUID(), 1_000L)));
    runs.existing.put(
        "2026-08",
        new StatementRun(
            "2026-08",
            LocalDate.of(2026, 8, 31),
            StatementRunStatus.RUNNING,
            SIGNED_OFF_AT,
            Optional.empty(),
            Optional.empty(),
            1L,
            Optional.empty(),
            Optional.empty()));

    scheduler.handle(closeSignedOffEvent(LocalDate.of(2026, 8, 31), "closing-key", 1, true));

    verify(rabbitTemplate, times(1))
        .convertAndSend(anyString(), anyString(), anyString(), any(CorrelationData.class));
  }

  private StatementJob capturedJob() {
    ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
    verify(rabbitTemplate, times(1))
        .convertAndSend(
            anyString(), anyString(), valueCaptor.capture(), any(CorrelationData.class));
    return StatementJob.fromJson(MAPPER, valueCaptor.getValue());
  }

  private static CloseSignedOffEvent closeSignedOffEvent(
      LocalDate businessDate,
      String snapshotObjectKey,
      long snapshotPositionCount,
      boolean monthEnd) {
    return new CloseSignedOffEvent(
        "v1",
        businessDate,
        SIGNED_OFF_AT,
        CLOSING_RUN_ID,
        snapshotObjectKey,
        snapshotPositionCount,
        monthEnd);
  }

  private static String positionLine(
      OrganizationId orgId, String participantRef, UUID fundId, long valueMinorUnits) {
    return """
        {"organizationId":"%s","participantInternalRef":"%s","fundId":"%s","unitLedgerId":1,\
        "unitsScaled":100000000,"navScaled":100000000,"navVersionId":1,"currency":"MXN",\
        "valueMinorUnits":%d,"costBasisMinorUnits":0,"unrealisedGainMinorUnits":0}"""
        .formatted(orgId.value(), participantRef, fundId, valueMinorUnits);
  }

  private static byte[] gzip(String... lines) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
      for (String line : lines) {
        gzip.write(line.getBytes(StandardCharsets.UTF_8));
        gzip.write('\n');
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  private static final class FakeRunRepository implements StatementRunRepository {
    private final List<String> opened = new ArrayList<>();
    private final Map<String, StatementRun> existing = new HashMap<>();

    @Override
    public void openIfAbsent(
        String period, LocalDate closeBusinessDate, Instant startedAt, long expectedCount) {
      opened.add(period);
    }

    @Override
    public List<StatementRun> findRunning() {
      return List.of();
    }

    @Override
    public void complete(
        String period, Instant completedAt, long durationMillis, long completedCount) {}

    @Override
    public Optional<StatementRun> find(String period) {
      return Optional.ofNullable(existing.get(period));
    }
  }
}
