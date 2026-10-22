package dev.fondaria.balanceapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How many {@code participantId → (organizationId, participantInternalRef)} resolutions this
 * instance holds in memory.
 *
 * <p>Operational tuning, not a secret: it has a working default and a deployment that sets nothing
 * behaves correctly. The bound trades heap against how many participants keep being served while
 * PostgreSQL is unreachable — entries are tiny (two identifiers), so the default is generous, and
 * eviction costs one indexed lookup on the next request rather than a wrong answer.
 *
 * @param maximumCachedParticipants hard upper bound on cached entries; the least recently used is
 *     evicted past it
 */
@ConfigurationProperties(prefix = "fondaria.participant-directory")
public record ParticipantDirectoryProperties(int maximumCachedParticipants) {

  public ParticipantDirectoryProperties {
    maximumCachedParticipants =
        maximumCachedParticipants <= 0 ? 100_000 : maximumCachedParticipants;
  }
}
