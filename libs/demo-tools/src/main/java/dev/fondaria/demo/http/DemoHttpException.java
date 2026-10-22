package dev.fondaria.demo.http;

/**
 * A call to the platform failed or was refused. Carries exactly what {@code phase-D-demo.md}
 * requires every command to report: the call that failed and the problem detail body it returned
 * (or the transport failure, when the platform was never reached at all). No retry is ever
 * attempted here -- a retry would hide a platform fault rather than report it.
 */
public class DemoHttpException extends RuntimeException {

  private final String method;
  private final String uri;
  private final int status;
  private final String body;

  public DemoHttpException(String method, String uri, int status, String body) {
    super(method + " " + uri + " -> HTTP " + status + ": " + body);
    this.method = method;
    this.uri = uri;
    this.status = status;
    this.body = body;
  }

  public DemoHttpException(String method, String uri, Throwable cause) {
    super(method + " " + uri + " failed: " + cause.getMessage(), cause);
    this.method = method;
    this.uri = uri;
    this.status = -1;
    this.body = null;
  }

  public String method() {
    return method;
  }

  public String uri() {
    return uri;
  }

  public int status() {
    return status;
  }

  public String body() {
    return body;
  }
}
