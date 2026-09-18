package com.lexia.api.modules.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TotpServiceTest {

  @Test
  void verifiesCodeInCurrentWindow() {
    TotpService totp = new TotpService();
    String secret = totp.newSecret();
    long counter = System.currentTimeMillis() / 1000L / 30;
    String code = totp.generate(TotpService.fromBase32(secret), counter);
    assertTrue(totp.verify(secret, code));
  }
}
