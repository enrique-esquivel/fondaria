package dev.fondaria.balanceapi.participant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * {@link CachingParticipantDirectory} — the one thing standing between the balance path and a
 * PostgreSQL read per request.
 */
class CachingParticipantDirectoryTest {

  private static final OrganizationId ORGANIZATION = new OrganizationId(UUID.randomUUID());

  @Test
  void resolvesOncePerParticipantAndServesTheRestFromMemory() {
    RecordingDirectory delegate = new RecordingDirectory();
    ParticipantId participant = ParticipantId.of(UUID.randomUUID());
    CachingParticipantDirectory directory = new CachingParticipantDirectory(delegate, 100);

    for (int i = 0; i < 50; i++) {
      assertThat(directory.find(participant)).isPresent();
    }

    assertThat(delegate.calls()).containsExactly(participant);
  }

  /**
   * The acceptance criterion, in miniature: once resolved, a balance read never needs the database
   * again, so an outage is invisible to every participant already seen.
   */
  @Test
  void keepsServingAResolvedParticipantAfterTheDatabaseGoesAway() {
    AtomicBoolean databaseUp = new AtomicBoolean(true);
    ParticipantId participant = ParticipantId.of(UUID.randomUUID());
    ParticipantDirectory delegate =
        id -> {
          if (!databaseUp.get()) {
            throw new IllegalStateException("PostgreSQL is stopped");
          }
          return Optional.of(new ParticipantScope(ORGANIZATION, "EMP-1"));
        };
    CachingParticipantDirectory directory = new CachingParticipantDirectory(delegate, 100);

    assertThat(directory.find(participant)).isPresent();
    databaseUp.set(false);

    assertThat(directory.find(participant))
        .as("a participant resolved before the outage is still served")
        .contains(new ParticipantScope(ORGANIZATION, "EMP-1"));
  }

  /** A participant never yet seen during an outage is an honest failure, not a wrong answer. */
  @Test
  void anUnresolvedParticipantDuringAnOutageFails() {
    ParticipantDirectory delegate =
        id -> {
          throw new IllegalStateException("PostgreSQL is stopped");
        };
    CachingParticipantDirectory directory = new CachingParticipantDirectory(delegate, 100);

    assertThatThrownBy(() -> directory.find(ParticipantId.of(UUID.randomUUID())))
        .isInstanceOf(IllegalStateException.class);
  }

  /**
   * Absence is deliberately not cached: a participant enrolled a moment after a mistyped lookup
   * must not be denied for the life of the process.
   */
  @Test
  void doesNotCacheAbsence() {
    List<ParticipantId> asked = new ArrayList<>();
    ParticipantId participant = ParticipantId.of(UUID.randomUUID());
    AtomicBoolean enrolled = new AtomicBoolean(false);
    ParticipantDirectory delegate =
        id -> {
          asked.add(id);
          return enrolled.get()
              ? Optional.of(new ParticipantScope(ORGANIZATION, "EMP-1"))
              : Optional.empty();
        };
    CachingParticipantDirectory directory = new CachingParticipantDirectory(delegate, 100);

    assertThat(directory.find(participant)).isEmpty();
    enrolled.set(true);
    assertThat(directory.find(participant)).isPresent();
    assertThat(asked).hasSize(2);
  }

  @Test
  void evictsTheLeastRecentlyUsedPastItsBound() {
    RecordingDirectory delegate = new RecordingDirectory();
    CachingParticipantDirectory directory = new CachingParticipantDirectory(delegate, 3);

    ParticipantId first = ParticipantId.of(UUID.randomUUID());
    ParticipantId second = ParticipantId.of(UUID.randomUUID());
    ParticipantId third = ParticipantId.of(UUID.randomUUID());
    ParticipantId fourth = ParticipantId.of(UUID.randomUUID());

    directory.find(first);
    directory.find(second);
    directory.find(third);
    // Touch first so second becomes the least recently used.
    directory.find(first);
    directory.find(fourth);

    assertThat(directory.size()).isEqualTo(3);
    // second was evicted, so it costs another delegate call; first did not.
    delegate.reset();
    directory.find(first);
    assertThat(delegate.calls()).isEmpty();
    directory.find(second);
    assertThat(delegate.calls()).containsExactly(second);
  }

  @Test
  void rejectsANonPositiveBound() {
    assertThatThrownBy(() -> new CachingParticipantDirectory(new RecordingDirectory(), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * {@code participantInternalRef} identifies a person and must not reach a log
   * (docs/ENGINEERING-RULES.md §7).
   */
  @Test
  void aScopeNeverRendersTheInternalRef() {
    assertThat(new ParticipantScope(ORGANIZATION, "EMP-000123").toString())
        .doesNotContain("EMP-000123")
        .contains("REDACTED");
  }

  private static final class RecordingDirectory implements ParticipantDirectory {

    private final List<ParticipantId> calls = new ArrayList<>();

    @Override
    public Optional<ParticipantScope> find(ParticipantId participantId) {
      calls.add(participantId);
      return Optional.of(new ParticipantScope(ORGANIZATION, "EMP-1"));
    }

    List<ParticipantId> calls() {
      return List.copyOf(calls);
    }

    void reset() {
      calls.clear();
    }
  }
}
