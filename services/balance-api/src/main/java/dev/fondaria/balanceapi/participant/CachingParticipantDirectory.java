package dev.fondaria.balanceapi.participant;

import dev.fondaria.common.ParticipantId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A bounded, least-recently-used cache in front of {@link JdbcParticipantDirectory}.
 *
 * <p><b>This is what keeps PostgreSQL off the balance path.</b> Task 6.2's acceptance criterion is
 * that the balance endpoint still works with PostgreSQL stopped, and the resolution from an opaque
 * {@code participantId} to {@code (organizationId, participantInternalRef)} is the one database
 * read the path would otherwise make on every request. Cached, it is made once per participant per
 * process; afterwards a balance is ledger and memory only, and a database outage is invisible to
 * every participant already resolved. A participant never yet resolved during an outage is a
 * genuine {@code 503} — the honest answer, since the platform cannot know which accounts to read.
 *
 * <p><b>Sound because the mapping is immutable, not because it is refreshed.</b> {@code
 * participant_id} is assigned once by a column default and never reassigned, and {@code
 * participant_internal_ref} is "stable, never reused and never reassigned" ({@code
 * V6__participant.sql}). There is therefore no invalidation problem here and no TTL: a cached entry
 * cannot go out of date, only out of memory. <b>Absence is deliberately not cached</b> — a
 * participant enrolled a moment ago must not be denied for the life of the process.
 *
 * <p>{@link ReentrantLock}, never {@code synchronized} (docs/ENGINEERING-RULES.md §4). The lock is
 * held only across map operations and never across the delegate's database call, so a slow query
 * cannot block readers of already-cached entries.
 */
public class CachingParticipantDirectory implements ParticipantDirectory {

  private final ParticipantDirectory delegate;
  private final Map<ParticipantId, ParticipantScope> cache;
  private final ReentrantLock lock = new ReentrantLock();

  /**
   * @param maximumEntries hard bound, so a service holding millions of participants cannot exhaust
   *     the heap by being asked about all of them. The eldest entry is evicted rather than the
   *     cache growing; a re-resolved participant costs one indexed lookup.
   */
  public CachingParticipantDirectory(ParticipantDirectory delegate, int maximumEntries) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    if (maximumEntries <= 0) {
      throw new IllegalArgumentException("maximumEntries must be positive");
    }
    this.cache =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<ParticipantId, ParticipantScope> eldest) {
            return size() > maximumEntries;
          }
        };
  }

  @Override
  public Optional<ParticipantScope> find(ParticipantId participantId) {
    Objects.requireNonNull(participantId, "participantId must not be null");
    ParticipantScope hit = get(participantId);
    if (hit != null) {
      return Optional.of(hit);
    }
    // Outside the lock on purpose: this is the database call.
    Optional<ParticipantScope> resolved = delegate.find(participantId);
    resolved.ifPresent(scope -> put(participantId, scope));
    return resolved;
  }

  /** Entries currently held. For tests, and for asserting the bound is honoured. */
  public int size() {
    lock.lock();
    try {
      return cache.size();
    } finally {
      lock.unlock();
    }
  }

  private ParticipantScope get(ParticipantId participantId) {
    lock.lock();
    try {
      // A read through an access-ordered LinkedHashMap mutates it, so it takes the write lock too.
      return cache.get(participantId);
    } finally {
      lock.unlock();
    }
  }

  private void put(ParticipantId participantId, ParticipantScope scope) {
    lock.lock();
    try {
      cache.put(participantId, scope);
    } finally {
      lock.unlock();
    }
  }
}
