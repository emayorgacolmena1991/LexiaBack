package com.lexia.api.modules.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class SecretCipher {

  private static final String DEV_FALLBACK = "lexia-local-dev-only-aes-key";
  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  public SecretCipher(AuthProperties properties) {
    this.key = new SecretKeySpec(deriveKey(properties.getCryptoKey()), "AES");
  }

  public String encrypt(String plaintext) {
    try {
      byte[] iv = new byte[12];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
      byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherText.length);
      buffer.put(iv);
      buffer.put(cipherText);
      return Base64.getEncoder().encodeToString(buffer.array());
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("No fue posible cifrar el secreto MFA", ex);
    }
  }

  public String decrypt(String payload) {
    try {
      byte[] raw = Base64.getDecoder().decode(payload);
      ByteBuffer buffer = ByteBuffer.wrap(raw);
      byte[] iv = new byte[12];
      buffer.get(iv);
      byte[] cipherText = new byte[buffer.remaining()];
      buffer.get(cipherText);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
      return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException ex) {
      throw new IllegalStateException("No fue posible leer el secreto MFA", ex);
    }
  }

  private static byte[] deriveKey(String configured) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      String material = configured == null || configured.isBlank() ? DEV_FALLBACK : configured;
      return digest.digest(material.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
