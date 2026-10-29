package com.lexia.api.modules.auth;

import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class DemoCredentialBootstrap implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(DemoCredentialBootstrap.class);
  private final AppUserRepository users;
  private final UserCredentialRepository credentials;
  private final PasswordHasher hasher;
  private final AuthProperties properties;

  public DemoCredentialBootstrap(
      AppUserRepository users,
      UserCredentialRepository credentials,
      PasswordHasher hasher,
      AuthProperties properties) {
    this.users = users;
    this.credentials = credentials;
    this.hasher = hasher;
    this.properties = properties;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    for (String email : resolveBootstrapEmails()) {
      if (email == null || email.isBlank()) {
        continue;
      }
      String normalized = email.trim().toLowerCase();
      Optional<AppUser> user = users.findByEmail(normalized);
      if (user.isEmpty()) {
        continue;
      }
      credentials
          .findByUserId(user.get().getId())
          .ifPresent(
              credential -> {
                if (credential.getPasswordHash() != null
                    && credential.getPasswordHash().startsWith("$argon2")) {
                  return;
                }
                credential.setPasswordHash(hasher.hash(properties.getDemoPassword()));
                credential.setAlgorithm("argon2id");
                credential.setLastChangedAt(Instant.now());
                credential.setMustChange(false);
                LOG.info("Contraseña bootstrap aplicada para {}", email);
              });
    }
  }

  private java.util.List<String> resolveBootstrapEmails() {
    java.util.List<String> configured = properties.getBootstrapEmails();
    if (configured == null || configured.isEmpty()) {
      return java.util.List.of();
    }
    if (configured.size() == 1 && configured.get(0).contains(",")) {
      return java.util.Arrays.stream(configured.get(0).split(","))
          .map(String::trim)
          .filter(s -> !s.isEmpty())
          .map(String::toLowerCase)
          .toList();
    }
    return configured.stream().map(String::trim).filter(s -> !s.isEmpty()).map(String::toLowerCase).toList();
  }
}
