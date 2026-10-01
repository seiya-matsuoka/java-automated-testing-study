package com.example.orderapi.service;

import com.example.orderapi.domain.OrderItem;
import com.example.orderapi.domain.PriceSummary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * 注文金額に関する業務ルールを集約する計算クラス。
 *
 * <p>Spring、DB、HTTP などの外部要素へ依存せず、 注文明細と送料だけから商品小計、割引額、最終合計を決定する。
 * 純粋な計算処理として切り出すことで、業務ルールを独立して確認できる構造とする。
 */
public class OrderPriceCalculator {

  /** 割引適用を開始する商品小計。 */
  public static final BigDecimal DISCOUNT_THRESHOLD = BigDecimal.valueOf(10_000);

  /** 商品小計が割引境界以上の場合に適用する割引率。 */
  public static final BigDecimal DISCOUNT_RATE = new BigDecimal("0.10");

  /**
   * 注文明細と送料から注文全体の金額を計算する。
   *
   * <p>商品小計が 10,000 円以上の場合は 10% を割引し、 割引計算で 1 円未満が発生した場合は切り捨てる。 最終合計は {@code subtotal -
   * discountAmount + shippingFee} で求める。
   *
   * @param items 注文明細
   * @param shippingFee 送料
   * @return 商品小計、割引額、送料、最終合計
   */
  public PriceSummary calculate(List<OrderItem> items, BigDecimal shippingFee) {
    if (items == null || items.isEmpty()) {
      throw new IllegalArgumentException("items must not be empty");
    }
    if (items.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("items must not contain null");
    }
    Objects.requireNonNull(shippingFee, "shippingFee must not be null");
    if (shippingFee.signum() < 0) {
      throw new IllegalArgumentException("shippingFee must not be negative");
    }

    BigDecimal subtotal =
        items.stream().map(OrderItem::getLineAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

    BigDecimal discountAmount = calculateDiscount(subtotal);
    BigDecimal totalAmount = subtotal.subtract(discountAmount).add(shippingFee);

    return new PriceSummary(subtotal, discountAmount, shippingFee, totalAmount);
  }

  /**
   * 商品小計から割引額を計算する。
   *
   * @param subtotal 商品小計
   * @return 割引額
   */
  public BigDecimal calculateDiscount(BigDecimal subtotal) {
    Objects.requireNonNull(subtotal, "subtotal must not be null");
    if (subtotal.signum() < 0) {
      throw new IllegalArgumentException("subtotal must not be negative");
    }

    if (subtotal.compareTo(DISCOUNT_THRESHOLD) < 0) {
      return BigDecimal.ZERO;
    }

    return subtotal.multiply(DISCOUNT_RATE).setScale(0, RoundingMode.DOWN);
  }
}
