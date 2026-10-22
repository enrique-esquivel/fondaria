package dev.fondaria.contribution.batch;

/**
 * Which contribution source a row funds, matching {@code docs/DOMAIN.md}'s product-level {@code
 * sources} vocabulary and {@code contribution-file-v2.md} §3.
 *
 * <p>Whether a plan <i>permits</i> a given source is a plan rule, not a file-format question — Task
 * 3.5. This enum only says which sources the format can express, and it is deliberately open to
 * growth: the file keys every amount by source rather than reserving a column per source, so a new
 * value here needs no format change.
 */
public enum ContributionSource {
  EMPLOYEE,
  EMPLOYER,
  VOLUNTARY
}
