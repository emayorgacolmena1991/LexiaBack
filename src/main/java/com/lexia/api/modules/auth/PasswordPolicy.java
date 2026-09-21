package com.lexia.api.modules.auth;

import org.springframework.http.HttpStatus;

public final class PasswordPolicy {

  private PasswordPolicy() {}

  public static void validate(String password) {
    if (password == null || password.length() < 8) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST,
          "WEAK_PASSWORD",
          "La contraseña debe tener al menos 8 caracteres.");
    }
    if (!password.chars().anyMatch(Character::isUpperCase)) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST,
          "WEAK_PASSWORD",
          "La contraseña debe incluir al menos una mayúscula.");
    }
    if (!password.chars().anyMatch(Character::isLowerCase)) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST,
          "WEAK_PASSWORD",
          "La contraseña debe incluir al menos una minúscula.");
    }
    if (!password.matches(".*[\\d\\W_].*")) {
      throw new AuthException(
          HttpStatus.BAD_REQUEST,
          "WEAK_PASSWORD",
          "La contraseña debe incluir un número o carácter especial.");
    }
  }
}
