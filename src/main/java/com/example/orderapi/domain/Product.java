package com.example.orderapi.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 注文対象となる商品の現在状態を表す Domain Model。
 *
 * <p>商品 ID、商品名、現在価格、現在在庫、販売状態を保持する。 注文可否の判定では、販売状態と在庫数をそれぞれ独立して確認できる構造とする。
 */
public class Product {

  private final Long id;
  private final String name;
  private final BigDecimal price;
  private final int stockQuantity;
  private final boolean active;

  /**
   * Product を生成する。
   *
   * @param id 商品 ID
   * @param name 商品名
   * @param price 商品単価
   * @param stockQuantity 現在在庫数
   * @param active 販売可能状態
   */
  public Product(Long id, String name, BigDecimal price, int stockQuantity, boolean active) {
    if (id == null || id <= 0) {
      throw new IllegalArgumentException("id must be positive");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    Objects.requireNonNull(price, "price must not be null");
    if (price.signum() <= 0) {
      throw new IllegalArgumentException("price must be greater than zero");
    }
    if (stockQuantity < 0) {
      throw new IllegalArgumentException("stockQuantity must not be negative");
    }

    this.id = id;
    this.name = name;
    this.price = price;
    this.stockQuantity = stockQuantity;
    this.active = active;
  }

  /**
   * 指定数量を現在在庫で満たせるかを判定する。
   *
   * <p>販売状態はこの判定に含めず、在庫数だけを評価する。 「販売中か」と「在庫が足りるか」を分離することで、 注文不可となる理由を Service 側で個別に扱える構造とする。
   *
   * @param requestedQuantity 注文要求数量
   * @return 在庫数が注文要求数量以上の場合 {@code true}
   */
  public boolean hasSufficientStock(int requestedQuantity) {
    if (requestedQuantity <= 0) {
      throw new IllegalArgumentException("requestedQuantity must be positive");
    }
    return stockQuantity >= requestedQuantity;
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public BigDecimal getPrice() {
    return price;
  }

  public int getStockQuantity() {
    return stockQuantity;
  }

  public boolean isActive() {
    return active;
  }
}
