package com.example.orderapi.dto.order;

import com.example.orderapi.domain.OrderItem;
import java.math.BigDecimal;

/**
 * Order API で返却する注文明細 DTO。
 *
 * @param id 注文明細 ID
 * @param productId 商品 ID
 * @param productName 注文時点の商品名
 * @param unitPrice 注文時点の商品単価
 * @param quantity 注文数量
 * @param lineAmount 明細金額
 */
public record OrderItemResponse(
    Long id,
    Long productId,
    String productName,
    BigDecimal unitPrice,
    int quantity,
    BigDecimal lineAmount) {

  /**
   * OrderItem を Response DTO へ変換する。
   *
   * @param item 変換対象の OrderItem
   * @return 注文明細 Response DTO
   */
  public static OrderItemResponse from(OrderItem item) {
    return new OrderItemResponse(
        item.getId(),
        item.getProductId(),
        item.getProductName(),
        item.getUnitPrice(),
        item.getQuantity(),
        item.getLineAmount());
  }
}
