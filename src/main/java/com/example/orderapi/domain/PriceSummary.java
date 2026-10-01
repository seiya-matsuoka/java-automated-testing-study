package com.example.orderapi.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 注文金額計算の結果をまとめて保持する Value Object。
 *
 * <p>商品小計、割引額、送料、最終合計を 1 つの値として扱い、 金額計算結果の受け渡しで項目間の対応を崩しにくい構造とする。
 */
public class PriceSummary {

  private final BigDecimal subtotal;
  private final BigDecimal discountAmount;
  private final BigDecimal shippingFee;
  private final BigDecimal totalAmount;

  /**
   * PriceSummary を生成する。
   *
   * @param subtotal 商品小計
   * @param discountAmount 割引額
   * @param shippingFee 送料
   * @param totalAmount 最終合計
   */
  public PriceSummary(
      BigDecimal subtotal,
      BigDecimal discountAmount,
      BigDecimal shippingFee,
      BigDecimal totalAmount) {
    this.subtotal = requireNonNegative(subtotal, "subtotal");
    this.discountAmount = requireNonNegative(discountAmount, "discountAmount");
    this.shippingFee = requireNonNegative(shippingFee, "shippingFee");
    this.totalAmount = requireNonNegative(totalAmount, "totalAmount");

    // 4 つの金額を独立した値として受け取るため、不整合な組み合わせを Value Object として成立させない。
    BigDecimal expectedTotal = this.subtotal.subtract(this.discountAmount).add(this.shippingFee);
    if (this.totalAmount.compareTo(expectedTotal) != 0) {
      throw new IllegalArgumentException(
          "totalAmount must match subtotal - discountAmount + shippingFee");
    }
  }

  private static BigDecimal requireNonNegative(BigDecimal value, String fieldName) {
    Objects.requireNonNull(value, fieldName + " must not be null");
    if (value.signum() < 0) {
      throw new IllegalArgumentException(fieldName + " must not be negative");
    }
    return value;
  }

  public BigDecimal getSubtotal() {
    return subtotal;
  }

  public BigDecimal getDiscountAmount() {
    return discountAmount;
  }

  public BigDecimal getShippingFee() {
    return shippingFee;
  }

  public BigDecimal getTotalAmount() {
    return totalAmount;
  }
}
