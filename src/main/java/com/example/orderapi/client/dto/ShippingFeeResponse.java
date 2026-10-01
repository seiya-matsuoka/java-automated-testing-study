package com.example.orderapi.client.dto;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Shipping Fee API から受信する Response DTO。
 *
 * <p>外部 API の JSON response を Java の値へ変換するための境界 DTO。 不正な負数送料や必須値欠落を受け入れず、Client 層から不整合な送料を返さない。
 *
 * @param shippingFee Shipping Fee API が算出した送料
 */
public record ShippingFeeResponse(BigDecimal shippingFee) {

  /** Shipping Fee API の Response として成立する値かを検証する。 */
  public ShippingFeeResponse {
    Objects.requireNonNull(shippingFee, "shippingFee must not be null");
    if (shippingFee.signum() < 0) {
      throw new IllegalArgumentException("shippingFee must not be negative");
    }
  }
}
