package com.example.orderapi.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 注文に含まれる 1 商品分の明細を表す Domain Model。
 *
 * <p>商品名と単価は Product の現在値を参照し続けるのではなく、 注文時点の値を Snapshot として保持する。
 * 注文後に商品情報が変更されても、過去の注文内容へ影響しない構造とする。
 */
public class OrderItem {

  public static final int MIN_QUANTITY = 1;
  public static final int MAX_QUANTITY = 99;

  private final Long id;
  private final Long orderId;
  private final Long productId;
  private final String productName;
  private final BigDecimal unitPrice;
  private final int quantity;
  private final BigDecimal lineAmount;

  /**
   * 新規注文用の OrderItem を生成する。
   *
   * <p>永続化前のため {@code id} と {@code orderId} は保持せず、 商品単価と数量から明細金額を算出する。
   *
   * @param productId 商品 ID
   * @param productName 注文時点の商品名
   * @param unitPrice 注文時点の商品単価
   * @param quantity 注文数量
   * @return 新規注文用の OrderItem
   */
  public static OrderItem create(
      Long productId, String productName, BigDecimal unitPrice, int quantity) {
    validateProductId(productId);
    validateProductName(productName);
    validateUnitPrice(unitPrice);
    validateQuantity(quantity);

    BigDecimal lineAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
    return new OrderItem(null, null, productId, productName, unitPrice, quantity, lineAmount);
  }

  /**
   * 永続化済みデータから OrderItem を復元する。
   *
   * <p>DB に保存された明細を Domain Model へ戻す用途を想定し、 ID、Order ID、保存済み明細金額を受け取る。
   *
   * @param id 注文明細 ID
   * @param orderId 注文 ID
   * @param productId 商品 ID
   * @param productName 注文時点の商品名
   * @param unitPrice 注文時点の商品単価
   * @param quantity 注文数量
   * @param lineAmount 保存済み明細金額
   * @return 永続化済みデータを表す OrderItem
   */
  public static OrderItem restore(
      Long id,
      Long orderId,
      Long productId,
      String productName,
      BigDecimal unitPrice,
      int quantity,
      BigDecimal lineAmount) {
    if (id == null || id <= 0) {
      throw new IllegalArgumentException("id must be positive");
    }
    if (orderId == null || orderId <= 0) {
      throw new IllegalArgumentException("orderId must be positive");
    }
    validateProductId(productId);
    validateProductName(productName);
    validateUnitPrice(unitPrice);
    validateQuantity(quantity);
    Objects.requireNonNull(lineAmount, "lineAmount must not be null");
    if (lineAmount.signum() < 0) {
      throw new IllegalArgumentException("lineAmount must not be negative");
    }

    BigDecimal expectedLineAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
    if (lineAmount.compareTo(expectedLineAmount) != 0) {
      throw new IllegalArgumentException("lineAmount must match unitPrice * quantity");
    }

    return new OrderItem(id, orderId, productId, productName, unitPrice, quantity, lineAmount);
  }

  private OrderItem(
      Long id,
      Long orderId,
      Long productId,
      String productName,
      BigDecimal unitPrice,
      int quantity,
      BigDecimal lineAmount) {
    this.id = id;
    this.orderId = orderId;
    this.productId = productId;
    this.productName = productName;
    this.unitPrice = unitPrice;
    this.quantity = quantity;
    this.lineAmount = lineAmount;
  }

  /**
   * 注文数量が仕様上の許容範囲かを判定する。
   *
   * @param quantity 注文数量
   * @return 1～99 の場合 {@code true}
   */
  public static boolean isValidQuantity(int quantity) {
    return quantity >= MIN_QUANTITY && quantity <= MAX_QUANTITY;
  }

  private static void validateProductId(Long productId) {
    if (productId == null || productId <= 0) {
      throw new IllegalArgumentException("productId must be positive");
    }
  }

  private static void validateProductName(String productName) {
    if (productName == null || productName.isBlank()) {
      throw new IllegalArgumentException("productName must not be blank");
    }
  }

  private static void validateUnitPrice(BigDecimal unitPrice) {
    Objects.requireNonNull(unitPrice, "unitPrice must not be null");
    if (unitPrice.signum() <= 0) {
      throw new IllegalArgumentException("unitPrice must be greater than zero");
    }
  }

  private static void validateQuantity(int quantity) {
    if (!isValidQuantity(quantity)) {
      throw new IllegalArgumentException("quantity must be between 1 and 99");
    }
  }

  public Long getId() {
    return id;
  }

  public Long getOrderId() {
    return orderId;
  }

  public Long getProductId() {
    return productId;
  }

  public String getProductName() {
    return productName;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  public int getQuantity() {
    return quantity;
  }

  public BigDecimal getLineAmount() {
    return lineAmount;
  }
}
