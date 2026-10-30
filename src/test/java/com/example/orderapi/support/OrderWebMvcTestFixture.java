package com.example.orderapi.support;

import com.example.orderapi.domain.Order;
import com.example.orderapi.domain.OrderItem;
import com.example.orderapi.domain.OrderStatus;
import com.example.orderapi.domain.PriceSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Unit 05 の Web 層テストで共通利用する、決定的な Order Fixture。
 *
 * <p>DB や Service を経由せず、Controller が Response DTO に変換するための Domain Object を組み立てる。 Fixture の準備と Web
 * 層の HTTP 検証を分離し、各 Test の期待条件を読み取りやすくする。
 */
public final class OrderWebMvcTestFixture {

  public static final long ORDER_ID = 100L;
  public static final long PRODUCT_ID = 10L;
  public static final String POSTAL_CODE = "1234567";
  public static final Instant CREATED_AT = Instant.parse("2026-10-01T00:00:00Z");
  public static final Instant CANCELLED_AT = Instant.parse("2026-10-02T12:00:00Z");

  private OrderWebMvcTestFixture() {
    // Test Fixture の生成は static factory へ集約し、インスタンス化を許可しない。
  }

  /**
   * 指定状態の永続化済み Order を生成する。
   *
   * <p>キャンセル日時と状態の整合性を維持する。状態遷移処理は呼ばず、Web Test に必要な Response 用データだけを作るため、ここでは Domain の restore()
   * を利用する。
   *
   * @param status HTTP Response に反映する注文状態
   * @return 注文明細 1 件を含む Order
   */
  public static Order order(OrderStatus status) {
    OrderItem item =
        OrderItem.restore(
            201L,
            ORDER_ID,
            PRODUCT_ID,
            "Keyboard",
            BigDecimal.valueOf(3_000),
            2,
            BigDecimal.valueOf(6_000));
    PriceSummary price =
        new PriceSummary(
            BigDecimal.valueOf(6_000),
            BigDecimal.ZERO,
            BigDecimal.valueOf(800),
            BigDecimal.valueOf(6_800));

    Instant cancelledAt = status == OrderStatus.CANCELLED ? CANCELLED_AT : null;

    return Order.restore(
        ORDER_ID, status, POSTAL_CODE, List.of(item), price, CREATED_AT, cancelledAt);
  }

  /** Bean Validation を通る、注文作成 API 用 Request JSON。 */
  public static String validCreateRequestJson() {
    return """
    {
      "shippingPostalCode": "1234567",
      "items": [{ "productId": 10, "quantity": 2 }]
    }
    """;
  }
}
