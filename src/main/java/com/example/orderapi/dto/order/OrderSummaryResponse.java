package com.example.orderapi.dto.order;

import com.example.orderapi.domain.Order;
import com.example.orderapi.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 注文一覧 API の Response DTO。
 *
 * <p>一覧表示では注文明細を含めず、注文を識別・比較するための主要項目だけを返却する。
 *
 * @param id 注文 ID
 * @param status 注文状態
 * @param shippingPostalCode 配送先郵便番号
 * @param subtotal 商品小計
 * @param discountAmount 割引額
 * @param shippingFee 送料
 * @param totalAmount 最終合計
 * @param createdAt 注文作成日時
 * @param cancelledAt キャンセル日時
 */
public record OrderSummaryResponse(
    Long id,
    OrderStatus status,
    String shippingPostalCode,
    BigDecimal subtotal,
    BigDecimal discountAmount,
    BigDecimal shippingFee,
    BigDecimal totalAmount,
    Instant createdAt,
    Instant cancelledAt) {

  /**
   * Order を注文一覧用 Response DTO へ変換する。
   *
   * @param order 変換対象の Order
   * @return 注文一覧用 Response DTO
   */
  public static OrderSummaryResponse from(Order order) {
    return new OrderSummaryResponse(
        order.getId(),
        order.getStatus(),
        order.getShippingPostalCode(),
        order.getSubtotal(),
        order.getDiscountAmount(),
        order.getShippingFee(),
        order.getTotalAmount(),
        order.getCreatedAt(),
        order.getCancelledAt());
  }
}
