package com.lexia.api.modules.auth;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {

  private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  private final String dummyHash;

  public PasswordHasher() {
    this.dummyHash = encoder.encode("lexia-timing-dummy");
  }

  public String hash(String rawPassword) {
    return encoder.encode(rawPassword);
  }

  public boolean matches(String rawPassword, String storedHash) {
    if (storedHash == null || storedHash.isBlank() || !storedHash.startsWith("$argon2")) {
      encoder.matches(rawPassword, dummyHash);
      return false;
    }
    return encoder.matches(rawPassword, storedHash);
  }

  public void consumeDummy() {
    encoder.matches("lexia-timing-dummy", dummyHash);
  }
}
