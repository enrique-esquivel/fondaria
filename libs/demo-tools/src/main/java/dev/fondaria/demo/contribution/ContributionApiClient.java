package dev.fondaria.demo.contribution;

import dev.fondaria.common.Units;
import dev.fondaria.demo.http.DemoHttpClient;
import dev.fondaria.demo.http.JsonResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every call this module makes to `contribution-api` -- funds, plans, participant lookup,
 * organizations, cash receipts, and the contribution-batch upload/submit/status path (Task D.0's
 * endpoints, `contracts/openapi/contribution-api.yaml`).
 */
public final class ContributionApiClient {

  private final DemoHttpClient http;
  private final String baseUrl;

  public ContributionApiClient(DemoHttpClient http, String baseUrl) {
    this.http = http;
    this.baseUrl = baseUrl;
  }

  public Map<String, Object> registerFund(
      String fundId, String name, String vehicleType, LocalDate launchDate, long launchNavScaled) {
    Map<String, Object> body = new LinkedHashMap<>();
    if (fundId != null) {
      body.put("fundId", fundId);
    }
    body.put("effectiveFrom", launchDate.toString());
    body.put("version", 1);
    body.put("name", name);
    body.put("vehicleType", vehicleType);
    body.put("currency", "MXN");
    body.put("unitScale", Units.SCALE);
    body.put("launchDate", launchDate.toString());
    body.put("launchNavScaled", launchNavScaled);
    return post("/v1/funds", body);
  }

  public Map<String, Object> registerOrganization(String taxId, String legalName) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("taxIdScheme", "MX_RFC");
    body.put("taxId", taxId);
    body.put("legalName", legalName);
    return post("/v1/organizations", body);
  }

  public Map<String, Object> publishPlanVersion(
      String organizationId,
      String planId,
      LocalDate effectiveFrom,
      int employerMatchRatioNumerator,
      int employerMatchRatioDenominator,
      int vestingFullYears,
      int dealingLagBusinessDays,
      String fundId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("planId", planId);
    body.put("effectiveFrom", effectiveFrom.toString());
    body.put("version", 1);
    body.put("employerMatchRatioNumerator", employerMatchRatioNumerator);
    body.put("employerMatchRatioDenominator", employerMatchRatioDenominator);
    body.put("vestingFullYears", vestingFullYears);
    body.put("dealingLagBusinessDays", dealingLagBusinessDays);
    body.put("fundId", fundId);
    return post("/v1/organizations/" + organizationId + "/plans", body);
  }

  public Map<String, Object> recordCashReceipt(
      String organizationId,
      String idempotencyKey,
      long amountMinorUnits,
      LocalDate valueDate,
      String bankReference,
      String operatorId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("amountMinorUnits", amountMinorUnits);
    body.put("currency", "MXN");
    body.put("valueDate", valueDate.toString());
    body.put("bankReference", bankReference);
    body.put("operatorId", operatorId);
    URI uri = URI.create(baseUrl + "/v1/organizations/" + organizationId + "/cash-receipts");
    return http.postJson(uri, body, Map.of("Idempotency-Key", idempotencyKey)).body();
  }

  public Map<String, Object> createUploadSlot(
      String organizationId, String planId, String payrollPeriod, String fileName) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("planId", planId);
    body.put("payrollPeriod", payrollPeriod);
    body.put("fileName", fileName);
    return post("/v1/organizations/" + organizationId + "/contribution-batches/upload-url", body);
  }

  public int uploadFile(String uploadUrl, byte[] content) {
    return http.putBytes(URI.create(uploadUrl), content);
  }

  public Map<String, Object> submitContributionBatch(
      String organizationId,
      String objectKey,
      String checksumSha256,
      String planId,
      String payrollPeriod,
      LocalDate valueDate,
      long declaredTotalCents,
      int declaredRowCount,
      Map<String, Long> declaredSourceCents) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("objectKey", objectKey);
    body.put("checksumSha256", checksumSha256);
    body.put("planId", planId);
    body.put("payrollPeriod", payrollPeriod);
    body.put("valueDate", valueDate.toString());
    body.put("declaredTotalCents", declaredTotalCents);
    body.put("declaredRowCount", declaredRowCount);
    body.put("declaredSourceCents", declaredSourceCents);
    return post("/v1/organizations/" + organizationId + "/contribution-batches", body);
  }

  public JsonResponse getContributionBatch(String organizationId, String batchId) {
    URI uri =
        URI.create(
            baseUrl + "/v1/organizations/" + organizationId + "/contribution-batches/" + batchId);
    return http.getJson(uri);
  }

  public Map<String, Object> lookupParticipantByInternalRef(
      String organizationId, String internalRef) {
    String encoded = URLEncoder.encode(internalRef, StandardCharsets.UTF_8);
    URI uri =
        URI.create(
            baseUrl
                + "/v1/organizations/"
                + organizationId
                + "/participants?internalRef="
                + encoded);
    return http.getJson(uri).body();
  }

  private Map<String, Object> post(String path, Object body) {
    return http.postJson(URI.create(baseUrl + path), body).body();
  }
}
