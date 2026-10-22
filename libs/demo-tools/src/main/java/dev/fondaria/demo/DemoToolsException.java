package dev.fondaria.demo;

/**
 * A command could not complete. {@code DemoTools.main} catches this, prints the message, and exits
 * non-zero.
 */
public class DemoToolsException extends RuntimeException {

  public DemoToolsException(String message) {
    super(message);
  }

  public DemoToolsException(String message, Throwable cause) {
    super(message, cause);
  }
}
