package com.example.orderapi.dto.order;

import com.example.orderapi.domain.Order;
import com.example.orderapi.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 注文詳細 API と注文更新 API の Response DTO。
 *
 * @param id 注文 ID
 * @param status 注文状態
 * @param shippingPostalCode 配送先郵便番号
 * @param items 注文明細
 * @param subtotal 商品小計
 * @param discountAmount 割引額
 * @param shippingFee 送料
 * @param totalAmount 最終合計
 * @param createdAt 注文作成日時
 * @param cancelledAt キャンセル日時
 */
public record OrderResponse(
    Long id,
    OrderStatus status,
    String shippingPostalCode,
    List<OrderItemResponse> items,
    BigDecimal subtotal,
    BigDecimal discountAmount,
    BigDecimal shippingFee,
    BigDecimal totalAmount,
    Instant createdAt,
    Instant cancelledAt) {

  /**
   * Order を注文詳細用 Response DTO へ変換する。
   *
   * @param order 変換対象の Order
   * @return Order API 用 Response DTO
   */
  public static OrderResponse from(Order order) {
    List<OrderItemResponse> itemResponses =
        order.getItems().stream().map(OrderItemResponse::from).toList();

    return new OrderResponse(
        order.getId(),
        order.getStatus(),
        order.getShippingPostalCode(),
        itemResponses,
        order.getSubtotal(),
        order.getDiscountAmount(),
        order.getShippingFee(),
        order.getTotalAmount(),
        order.getCreatedAt(),
        order.getCancelledAt());
  }
}
