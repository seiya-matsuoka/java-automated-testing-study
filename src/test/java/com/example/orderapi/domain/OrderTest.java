package com.example.orderapi.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link Order} の初期状態と単純な状態判定を確認する Unit Test。
 *
 * <p>Order 自体を直接生成するため Spring Context や Repository は不要となる。 Unit 01 では boolean / null / equality の基本
 * Assertion を、 Domain Model の分かりやすい振る舞いと対応させて確認する。
 */
class OrderTest {

  /**
   * 新規注文が CREATED として生成され、永続化前の値を持たないことを確認する。
   *
   * <p>{@code assertEquals}、{@code assertNull}、{@code assertNotNull} を 1 つの Domain Model
   * の生成結果に対して読み分けるための基本ケース。
   */
  @Test
  void create_setsInitialStateForNewOrder() {
    // Arrange: Order 生成に必要な Domain Object を準備する。
    OrderItem item = OrderItem.create(1L, "Standard Product", BigDecimal.valueOf(3_000), 1);
    PriceSummary priceSummary =
        new PriceSummary(
            BigDecimal.valueOf(3_000),
            BigDecimal.ZERO,
            BigDecimal.valueOf(800),
            BigDecimal.valueOf(3_800));
    Instant createdAt = Instant.parse("2026-10-01T00:00:00Z");

    // Act: 新規注文を生成する。
    Order order = Order.create("1000001", List.of(item), priceSummary, createdAt);

    // Assert: 新規注文として保証したい初期状態を確認する。
    assertEquals(OrderStatus.CREATED, order.getStatus());
    assertNull(order.getId());
    assertNull(order.getCancelledAt());
    assertNotNull(order.getItems());
    assertEquals(createdAt, order.getCreatedAt());
  }

  /**
   * CREATED の注文で許可される操作と許可されない操作を boolean Assertion で確認する。
   *
   * <p>状態遷移表全体を網羅することが目的ではなく、 {@code assertTrue} / {@code assertFalse} が boolean の振る舞いを
   * どのように保証するかを理解するための代表ケース。
   */
  @Test
  void createdOrder_canBeConfirmedAndCancelledButCannotBeShipped() {
    // Given
    Order order = createOrder();

    // When: 判定メソッドの戻り値を取得する。
    boolean canConfirm = order.canConfirm();
    boolean canShip = order.canShip();
    boolean canCancel = order.canCancel();

    // Then
    assertTrue(canConfirm);
    assertFalse(canShip);
    assertTrue(canCancel);
  }

  /**
   * CONFIRMED の注文では発送とキャンセルが可能で、再度の確定はできないことを確認する。
   *
   * <p>同じ Assertion でも SUT の状態を変えると期待結果が変わることを示す。 状態と操作の全組み合わせを整理する Test Matrix は Unit 02 で扱う。
   */
  @Test
  void confirmedOrder_canBeShippedAndCancelledButCannotBeConfirmedAgain() {
    // Given
    Order order =
        Order.restore(
            1L,
            OrderStatus.CONFIRMED,
            "1000001",
            List.of(createOrderItem()),
            createPriceSummary(),
            Instant.parse("2026-10-01T00:00:00Z"),
            null);

    // When / Then
    assertFalse(order.canConfirm());
    assertTrue(order.canShip());
    assertTrue(order.canCancel());
  }

  /**
   * Test Case で繰り返し利用する CREATED の Order を生成する。
   *
   * <p>Unit 01 では {@code @BeforeEach} などの Lifecycle Annotation をまだ扱わず、 小さな helper method で Test
   * Data の準備を共有する。
   */
  private Order createOrder() {
    return Order.create(
        "1000001",
        List.of(createOrderItem()),
        createPriceSummary(),
        Instant.parse("2026-10-01T00:00:00Z"));
  }

  /** Order の Test Data として使用する 1 明細を生成する。 */
  private OrderItem createOrderItem() {
    return OrderItem.create(1L, "Standard Product", BigDecimal.valueOf(3_000), 1);
  }

  /** Order の Test Data として整合した金額情報を生成する。 */
  private PriceSummary createPriceSummary() {
    return new PriceSummary(
        BigDecimal.valueOf(3_000),
        BigDecimal.ZERO,
        BigDecimal.valueOf(800),
        BigDecimal.valueOf(3_800));
  }
}
