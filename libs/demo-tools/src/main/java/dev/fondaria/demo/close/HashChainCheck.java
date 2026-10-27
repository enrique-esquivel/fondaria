package dev.fondaria.demo.close;

import dev.fondaria.demo.Display;
import java.util.List;

/**
 * The snapshot manifest hash chain up to one business date, as eod-job's {@code
 * EodHashChainVerifier} defines it: the first completed manifest links to nothing, and every later
 * one's {@code previous_manifest_sha256} is the manifest hash of the completed snapshot before it.
 * Checked over the recorded hashes in {@code workflow.snapshot_run}, the same scope the verifier
 * itself checks -- a read, so the demo shows the chain rather than a gauge's say-so.
 *
 * @param manifests completed snapshots up to and including the date
 * @param broken links that do not match; zero means intact
 * @param firstBrokenDate the earliest date whose link does not match, or empty
 * @param previousDate the date the given date's manifest links to, or empty for the genesis one
 * @param previousSha256 that manifest's hash, or empty
 */
record HashChainCheck(
    long manifests,
    long broken,
    String firstBrokenDate,
    String previousDate,
    String previousSha256) {

  /** {@code %1$s} is the business date. */
  static final String QUERY =
      """
      WITH chain AS (
        SELECT business_date, manifest_sha256, previous_manifest_sha256,
               lag(manifest_sha256) OVER (ORDER BY business_date) AS prior_sha256,
               lag(business_date) OVER (ORDER BY business_date) AS prior_date
          FROM workflow.snapshot_run
         WHERE status = 'COMPLETED' AND business_date <= DATE '%1$s')
      SELECT count(*),
             count(*) FILTER (WHERE previous_manifest_sha256 IS DISTINCT FROM prior_sha256),
             coalesce(min(business_date::text)
                        FILTER (WHERE previous_manifest_sha256 IS DISTINCT FROM prior_sha256), ''),
             coalesce(max(prior_date::text) FILTER (WHERE business_date = DATE '%1$s'), ''),
             coalesce(max(prior_sha256) FILTER (WHERE business_date = DATE '%1$s'), '')
        FROM chain
      """;

  static HashChainCheck fromRow(List<String> row) {
    if (row.size() != 5) {
      throw new IllegalArgumentException("expected 5 columns, got " + row.size());
    }
    return new HashChainCheck(
        Long.parseLong(row.get(0)), Long.parseLong(row.get(1)), row.get(2), row.get(3), row.get(4));
  }

  boolean intact() {
    return broken == 0;
  }

  String describe() {
    if (!intact()) {
      return "BROKEN    "
          + Display.count(broken)
          + " of "
          + Display.count(manifests)
          + " manifests do not link to the one before; first at "
          + firstBrokenDate
          + " (eod_hash_chain_valid pages on this)";
    }
    String link =
        previousDate.isEmpty()
            ? "this is the genesis manifest, linked to nothing"
            : "this one links to "
                + previousDate
                + "'s ("
                + Display.shortHash(previousSha256)
                + ")";
    return "INTACT    " + Display.count(manifests) + " manifests chained; " + link;
  }
}
