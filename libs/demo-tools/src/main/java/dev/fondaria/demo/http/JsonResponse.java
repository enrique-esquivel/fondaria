package dev.fondaria.demo.http;

import java.util.Map;

/**
 * A successful (status &lt; 400) JSON response: the status actually returned, and the parsed body.
 */
public record JsonResponse(int status, Map<String, Object> body) {}
