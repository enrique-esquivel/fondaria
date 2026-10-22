package dev.fondaria.eodjob.signoff;

/**
 * Publishes one {@code close.signed-off.v1} record and blocks until Redpanda confirms it.
 *
 * <p>An interface with one implementation ({@link AmqpCloseSignedOffPublisher}), the same
 * repository-style seam the rest of this service uses, so {@link SignoffService}'s tests do not
 * need a broker.
 */
public interface CloseSignedOffPublisher {

  /**
   * Produces the event and blocks until it is confirmed.
   *
   * @throws PublishFailedException if the broker does not confirm within the configured timeout
   */
  void publish(CloseSignedOffEvent event);
}
