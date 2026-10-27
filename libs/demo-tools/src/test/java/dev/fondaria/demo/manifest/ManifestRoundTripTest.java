package dev.fondaria.demo.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestRoundTripTest {

  @TempDir Path tempDir;

  @Test
  void writesAndReadsBackFundsAndOrganizations() {
    Manifest manifest = Manifest.empty();
    manifest
        .funds()
        .add(
            new FundRecord(
                "fund-1", 1001L, "CAJA_DE_AHORRO", "Caja Uno", LocalDate.of(2020, 1, 1)));
    manifest
        .organizations()
        .add(
            new OrganizationRecord(
                "org-1",
                "Acme S.A.",
                "PLAN-1",
                "fund-1",
                250,
                List.of(new HeroParticipant("P-0-0", "PAYROLL-0-0", "participant-1")),
                Optional.of(
                    new SeedBatch("batch-1", Optional.of(LocalDate.of(2020, 1, 1)), 12_345L))));

    Path file = tempDir.resolve("manifest.json");
    manifest.writeTo(file);
    Manifest reloaded = Manifest.readFrom(file);

    assertThat(reloaded.funds())
        .containsExactly(
            new FundRecord(
                "fund-1", 1001L, "CAJA_DE_AHORRO", "Caja Uno", LocalDate.of(2020, 1, 1)));
    assertThat(reloaded.organizations()).hasSize(1);
    OrganizationRecord organization = reloaded.organizations().get(0);
    assertThat(organization.organizationId()).isEqualTo("org-1");
    assertThat(organization.employeeCount()).isEqualTo(250);
    assertThat(organization.heroes())
        .containsExactly(new HeroParticipant("P-0-0", "PAYROLL-0-0", "participant-1"));
    assertThat(organization.seedBatch())
        .contains(new SeedBatch("batch-1", Optional.of(LocalDate.of(2020, 1, 1)), 12_345L));
  }

  @Test
  void aRejectedSeedBatchRoundTripsWithNoDealingDate() {
    Manifest manifest = Manifest.empty();
    SeedBatch rejected = new SeedBatch("batch-1", Optional.empty(), 500L);
    manifest
        .organizations()
        .add(
            new OrganizationRecord(
                "org-1", "Acme", "PLAN-1", "fund-1", 10, List.of(), Optional.of(rejected)));
    Path file = tempDir.resolve("manifest.json");
    manifest.writeTo(file);

    SeedBatch reloaded = Manifest.readFrom(file).organizations().get(0).seedBatch().orElseThrow();
    assertThat(reloaded).isEqualTo(rejected);
    assertThat(reloaded.dealsOn(LocalDate.of(2020, 1, 1))).isFalse();
  }

  @Test
  void aManifestWrittenBeforeSeedBatchesWereRecordedStillReads() {
    Path file = tempDir.resolve("manifest.json");
    manifest(file);

    Manifest reloaded = Manifest.readFrom(file);
    assertThat(reloaded.organizations().get(0).seedBatch()).isEmpty();
    assertThat(reloaded.organizations().get(0).employeeCount()).isEqualTo(10);
  }

  private static void manifest(Path file) {
    try {
      Files.writeString(
          file,
          """
          {"funds":[],"organizations":[{"organizationId":"org-1","legalName":"Acme",\
          "planId":"PLAN-1","fundId":"fund-1","employeeCount":10,"heroes":[]}]}
          """);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void fundByVehicleTypeFindsTheMatchingFund() {
    Manifest manifest = Manifest.empty();
    manifest
        .funds()
        .add(
            new FundRecord(
                "fund-1", 1001L, "CAJA_DE_AHORRO", "Caja Uno", LocalDate.of(2020, 1, 1)));
    manifest
        .funds()
        .add(
            new FundRecord("fund-2", 1002L, "RETIREMENT_FUND", "Retiro", LocalDate.of(2020, 1, 1)));

    assertThat(manifest.fundByVehicleType("RETIREMENT_FUND").fundId()).isEqualTo("fund-2");
  }

  @Test
  void resumingFromAnExistingManifestPreservesEarlierOrganizations() {
    Manifest manifest = Manifest.empty();
    manifest
        .organizations()
        .add(
            new OrganizationRecord(
                "org-1", "Acme", "PLAN-1", "fund-1", 10, List.of(), Optional.empty()));
    Path file = tempDir.resolve("manifest.json");
    manifest.writeTo(file);

    Manifest resumed = Manifest.readFrom(file);
    resumed
        .organizations()
        .add(
            new OrganizationRecord(
                "org-2", "Beta", "PLAN-2", "fund-1", 20, List.of(), Optional.empty()));

    assertThat(resumed.organizations()).hasSize(2);
  }
}
