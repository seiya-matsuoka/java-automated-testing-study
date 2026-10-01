package com.example.orderapi.client.dto;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Shipping Fee API へ送信する Request DTO。
 *
 * <p>External HTTP Client 層の DTO として、外部 API Contract に必要な値だけを保持する。 Domain Model をそのまま外部 API
 * へ渡さず、外部仕様との境界を明示する。
 *
 * @param postalCode 配送先郵便番号
 * @param subtotal 商品小計
 * @param totalQuantity 注文商品の総数量
 */
public record ShippingFeeRequest(String postalCode, BigDecimal subtotal, int totalQuantity) {

  private static final Pattern POSTAL_CODE_PATTERN = Pattern.compile("^[0-9]{7}$");

  /** Shipping Fee API の Request として成立する値かを検証する。 */
  public ShippingFeeRequest {
    if (postalCode == null || !POSTAL_CODE_PATTERN.matcher(postalCode).matches()) {
      throw new IllegalArgumentException("postalCode must be 7 digits");
    }

    Objects.requireNonNull(subtotal, "subtotal must not be null");
    if (subtotal.signum() < 0) {
      throw new IllegalArgumentException("subtotal must not be negative");
    }

    if (totalQuantity <= 0) {
      throw new IllegalArgumentException("totalQuantity must be positive");
    }
  }
}
