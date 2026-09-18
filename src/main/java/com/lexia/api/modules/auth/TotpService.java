package com.lexia.api.modules.auth;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class TotpService {

  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private static final int PERIOD = 30;
  private static final int DIGITS = 6;
  private static final int WINDOW = 1;
  private final SecureRandom random = new SecureRandom();

  public String newSecret() {
    byte[] raw = new byte[20];
    random.nextBytes(raw);
    return toBase32(raw);
  }

  public String otpauthUrl(String email, String secret) {
    String label = URLEncoder.encode("LEXIA:" + email, StandardCharsets.UTF_8).replace("+", "%20");
    String issuer = URLEncoder.encode("LEXIA", StandardCharsets.UTF_8);
    return "otpauth://totp/"
        + label
        + "?secret="
        + secret
        + "&issuer="
        + issuer
        + "&algorithm=SHA1&digits="
        + DIGITS
        + "&period="
        + PERIOD;
  }

  public boolean verify(String secret, String code) {
    if (code == null) {
      return false;
    }
    String normalized = code.replace(" ", "").trim();
    if (!normalized.matches("\\d{6}")) {
      return false;
    }
    long now = System.currentTimeMillis() / 1000L / PERIOD;
    byte[] key = fromBase32(secret);
    for (int offset = -WINDOW; offset <= WINDOW; offset++) {
      if (constantTimeEquals(normalized, generate(key, now + offset))) {
        return true;
      }
    }
    return false;
  }

  String generate(byte[] key, long counter) {
    try {
      byte[] data = ByteBuffer.allocate(8).putLong(counter).array();
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(key, "HmacSHA1"));
      byte[] hmac = mac.doFinal(data);
      int offset = hmac[hmac.length - 1] & 0x0f;
      int binary =
          ((hmac[offset] & 0x7f) << 24)
              | ((hmac[offset + 1] & 0xff) << 16)
              | ((hmac[offset + 2] & 0xff) << 8)
              | (hmac[offset + 3] & 0xff);
      int otp = binary % 1_000_000;
      return String.format(Locale.ROOT, "%06d", otp);
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("TOTP no disponible", ex);
    }
  }

  private static boolean constantTimeEquals(String left, String right) {
    if (left.length() != right.length()) {
      return false;
    }
    int result = 0;
    for (int i = 0; i < left.length(); i++) {
      result |= left.charAt(i) ^ right.charAt(i);
    }
    return result == 0;
  }

  private static String toBase32(byte[] data) {
    StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
    int buffer = 0;
    int bitsLeft = 0;
    for (byte b : data) {
      buffer = (buffer << 8) | (b & 0xff);
      bitsLeft += 8;
      while (bitsLeft >= 5) {
        out.append(ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 31));
        bitsLeft -= 5;
      }
    }
    if (bitsLeft > 0) {
      out.append(ALPHABET.charAt((buffer << (5 - bitsLeft)) & 31));
    }
    return out.toString();
  }

  static byte[] fromBase32(String secret) {
    String normalized = secret.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
    int buffer = 0;
    int bitsLeft = 0;
    byte[] out = new byte[normalized.length() * 5 / 8];
    int index = 0;
    for (int i = 0; i < normalized.length(); i++) {
      int val = ALPHABET.indexOf(normalized.charAt(i));
      if (val < 0) {
        throw new IllegalArgumentException("Secreto TOTP inválido");
      }
      buffer = (buffer << 5) | val;
      bitsLeft += 5;
      if (bitsLeft >= 8) {
        out[index++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xff);
        bitsLeft -= 8;
      }
    }
    return out;
  }
}
