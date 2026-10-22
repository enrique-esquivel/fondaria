package dev.fondaria.demo;

import java.nio.file.Path;

/**
 * Where every command reads and writes its generated files -- `demo/out/`, created on demand, never
 * committed.
 */
public final class DemoPaths {

  private final Path outDir;

  public DemoPaths(Path outDir) {
    this.outDir = outDir;
  }

  public static DemoPaths defaultLocation() {
    return new DemoPaths(Path.of("demo", "out"));
  }

  public Path manifest() {
    return outDir.resolve("manifest.json");
  }

  public Path state() {
    return outDir.resolve("state.json");
  }

  public Path fundFlows() {
    return outDir.resolve("fund-flows.json");
  }

  public Path navSeries() {
    return outDir.resolve("nav-series.json");
  }

  public Path outDir() {
    return outDir;
  }
}
