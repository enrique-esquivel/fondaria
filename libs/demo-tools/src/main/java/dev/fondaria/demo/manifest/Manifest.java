package dev.fondaria.demo.manifest;

import dev.fondaria.demo.json.Json;
import dev.fondaria.demo.json.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * `demo/out/manifest.json` -- the funds, organizations, plans and hero participants `seed` created,
 * that every later command (`contributions`, `valuations`, `onboard`) reads rather than re-deriving
 * or re-registering (`phase-D-demo.md` Task D.2).
 */
public record Manifest(List<FundRecord> funds, List<OrganizationRecord> organizations) {

  public static Manifest empty() {
    return new Manifest(new ArrayList<>(), new ArrayList<>());
  }

  public FundRecord fundByVehicleType(String vehicleType) {
    return funds.stream()
        .filter(f -> f.vehicleType().equals(vehicleType))
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("manifest has no fund of vehicleType " + vehicleType));
  }

  public void writeTo(Path path) {
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("funds", funds.stream().map(FundRecord::toJson).collect(Collectors.toList()));
    json.put(
        "organizations",
        organizations.stream().map(OrganizationRecord::toJson).collect(Collectors.toList()));
    try {
      Files.createDirectories(path.getParent());
      Files.writeString(path, Json.write(json), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not write " + path, e);
    }
  }

  @SuppressWarnings("unchecked")
  public static Manifest readFrom(Path path) {
    String content;
    try {
      content = Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + path, e);
    }
    Map<String, Object> root = JsonParser.parseObject(content);
    List<FundRecord> funds = new ArrayList<>();
    for (Object fund : (List<Object>) root.get("funds")) {
      funds.add(FundRecord.fromJson((Map<String, Object>) fund));
    }
    List<OrganizationRecord> organizations = new ArrayList<>();
    for (Object org : (List<Object>) root.get("organizations")) {
      organizations.add(OrganizationRecord.fromJson((Map<String, Object>) org));
    }
    return new Manifest(funds, organizations);
  }
}
