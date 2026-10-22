package dev.fondaria.demo.valuation;

import dev.fondaria.demo.http.DemoHttpClient;
import dev.fondaria.demo.http.JsonResponse;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every call this module makes to `valuation-api` -- the upload/submit/status path for a valuation
 * file.
 */
public final class ValuationApiClient {

  private final DemoHttpClient http;
  private final String baseUrl;

  public ValuationApiClient(DemoHttpClient http, String baseUrl) {
    this.http = http;
    this.baseUrl = baseUrl;
  }

  public Map<String, Object> createUploadSlot(String source, String fileName) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("source", source);
    body.put("fileName", fileName);
    return http.postJson(URI.create(baseUrl + "/v1/valuation-batches/upload-url"), body).body();
  }

  public int uploadFile(String uploadUrl, byte[] content) {
    return http.putBytes(URI.create(uploadUrl), content);
  }

  public Map<String, Object> submitValuationBatch(
      String objectKey, String checksumSha256, String source, int declaredRowCount) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("objectKey", objectKey);
    body.put("checksumSha256", checksumSha256);
    body.put("source", source);
    body.put("declaredRowCount", declaredRowCount);
    return http.postJson(URI.create(baseUrl + "/v1/valuation-batches"), body).body();
  }

  public JsonResponse getValuationBatch(String batchId) {
    return http.getJson(URI.create(baseUrl + "/v1/valuation-batches/" + batchId));
  }
}
