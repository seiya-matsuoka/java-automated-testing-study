package com.example.orderapi.dto.product;

import com.example.orderapi.domain.Product;
import java.math.BigDecimal;

/**
 * Product API の Response DTO。
 *
 * <p>Domain Model をそのまま HTTP response として公開せず、 Web API Contract として返却する項目を明示する。
 *
 * @param id 商品 ID
 * @param name 商品名
 * @param price 商品単価
 * @param stockQuantity 現在在庫数
 * @param active 販売可能状態
 */
public record ProductResponse(
    Long id, String name, BigDecimal price, int stockQuantity, boolean active) {

  /**
   * Product を ProductResponse へ変換する。
   *
   * @param product 変換対象の Product
   * @return Product API 用 Response DTO
   */
  public static ProductResponse from(Product product) {
    return new ProductResponse(
        product.getId(),
        product.getName(),
        product.getPrice(),
        product.getStockQuantity(),
        product.isActive());
  }
}
