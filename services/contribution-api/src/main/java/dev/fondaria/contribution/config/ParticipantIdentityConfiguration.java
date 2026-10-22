package dev.fondaria.contribution.config;

import dev.fondaria.contribution.participant.identity.ParticipantIdentityMasterKey;
import java.util.Base64;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires Task 9.5's envelope-encryption master key. This is the only place {@code
 * PARTICIPANT_IDENTITY_MASTER_KEY} is read; every other class in {@code
 * dev.fondaria.contribution.participant.identity} depends on the decoded {@link
 * ParticipantIdentityMasterKey} instead.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ParticipantIdentityProperties.class)
public class ParticipantIdentityConfiguration {

  @Bean
  public ParticipantIdentityMasterKey participantIdentityMasterKey(
      ParticipantIdentityProperties properties) {
    properties.requireComplete();
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(properties.masterKeyBase64());
    } catch (IllegalArgumentException e) {
      throw new ConfigurationException(
          "Environment variable "
              + ParticipantIdentityProperties.MASTER_KEY_VARIABLE
              + " is not valid base64",
          ParticipantIdentityProperties.REMEDY);
    }
    try {
      return new ParticipantIdentityMasterKey(decoded);
    } catch (IllegalArgumentException e) {
      throw new ConfigurationException(
          "Environment variable "
              + ParticipantIdentityProperties.MASTER_KEY_VARIABLE
              + " does not decode to a 256-bit key",
          ParticipantIdentityProperties.REMEDY);
    }
  }
}
