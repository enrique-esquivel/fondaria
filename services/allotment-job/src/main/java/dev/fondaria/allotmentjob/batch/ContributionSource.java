package dev.fondaria.allotmentjob.batch;

/**
 * Where a contribution's money came from — matching contribution-api's enum of the same name
 * exactly ({@code contribution-file-v2.md} §1.1). Duplicated rather than shared: this service is a
 * separate deployable and does not depend on contribution-api's source tree.
 */
public enum ContributionSource {
  EMPLOYEE,
  EMPLOYER,
  VOLUNTARY
}
