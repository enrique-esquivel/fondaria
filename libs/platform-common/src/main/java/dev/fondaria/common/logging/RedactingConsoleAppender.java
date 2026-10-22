package dev.fondaria.common.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import java.io.OutputStream;

/**
 * Console appender enforcing structured JSON output and redaction. Applied at the appender so
 * redaction cannot be bypassed by a careless log statement.
 */
public class RedactingConsoleAppender extends ConsoleAppender<ILoggingEvent> {

  private final RedactingJsonLayout layout = new RedactingJsonLayout();

  public RedactingConsoleAppender() {
    LayoutWrappingEncoder<ILoggingEvent> encoder = new LayoutWrappingEncoder<>();
    encoder.setLayout(layout);
    setEncoder(encoder);
  }

  public void setService(String service) {
    layout.setService(service);
  }

  public String getService() {
    return layout.getService();
  }

  public RedactingJsonLayout getJsonLayout() {
    return layout;
  }

  @Override
  public void setOutputStream(OutputStream outputStream) {
    super.setOutputStream(outputStream);
  }

  @Override
  public void start() {
    if (layout.getContext() == null) {
      layout.setContext(getContext());
    }
    layout.start();
    if (getEncoder() != null && getEncoder().getContext() == null) {
      getEncoder().setContext(getContext());
    }
    OutputStream customStream = this.getOutputStream();
    super.start();
    if (customStream != null) {
      setOutputStream(customStream);
    }
  }
}
