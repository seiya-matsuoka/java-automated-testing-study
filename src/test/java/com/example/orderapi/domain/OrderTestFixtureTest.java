package com.example.orderapi.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Test Fixture と AssertJ による Object / Collection の検証を確認する Unit Test。
 *
 * <p>同じ Order Fixture を複数 Test Method で利用し、Test の前後でどの状態を準備・破棄するかを Lifecycle Annotation と対応させて読む。
 */
@DisplayName("Order Fixture と AssertJ")
class OrderTestFixtureTest {

  private static Instant fixedCreatedAt;

  private Order order;

  /** Test Class 全体で共通となる不変の作成日時を 1 回だけ準備する。 */
  @BeforeAll
  static void setUpClassFixture() {
    fixedCreatedAt = Instant.parse("2026-10-01T00:00:00Z");
  }

  /** 各 Test Method が独立した Order Fixture を利用できるよう、毎回新しく生成する。 */
  @BeforeEach
  void setUp() {
    List<OrderItem> items =
        List.of(
            OrderItem.create(1L, "Product A", BigDecimal.valueOf(3_000), 2),
            OrderItem.create(2L, "Product B", BigDecimal.valueOf(2_500), 2));

    PriceSummary priceSummary =
        new PriceSummary(
            BigDecimal.valueOf(11_000),
            BigDecimal.valueOf(1_100),
            BigDecimal.valueOf(800),
            BigDecimal.valueOf(10_700));

    order = Order.create("1000001", items, priceSummary, fixedCreatedAt);
  }

  /** 各 Test Method で使用した Fixture への参照を破棄する。 */
  @AfterEach
  void tearDown() {
    order = null;
  }

  /** Test Class 全体で共有した Fixture への参照を破棄する。 */
  @AfterAll
  static void tearDownClassFixture() {
    fixedCreatedAt = null;
  }

  /** AssertJ の fluent assertion を使い、Order Object の複数の性質を読みやすく確認する。 */
  @Test
  @DisplayName("Order Object の主要な値を fluent に検証できる")
  void orderObject_canBeVerifiedFluently() {
    assertThat(order)
        .isNotNull()
        .satisfies(
            actual -> {
              assertThat(actual.getStatus()).isEqualTo(OrderStatus.CREATED);
              assertThat(actual.getShippingPostalCode()).isEqualTo("1000001");
              assertThat(actual.getSubtotal()).isEqualByComparingTo(BigDecimal.valueOf(11_000));
              assertThat(actual.getTotalAmount()).isEqualByComparingTo(BigDecimal.valueOf(10_700));
              assertThat(actual.getCreatedAt()).isEqualTo(fixedCreatedAt);
            });
  }

  /** AssertJ の Collection Assertion を使い、明細数・順序・主要 property を確認する。 */
  @Test
  @DisplayName("OrderItem Collection の内容を fluent に検証できる")
  void orderItems_canBeVerifiedAsCollection() {
    assertThat(order.getItems())
        .hasSize(2)
        .extracting(OrderItem::getProductId)
        .containsExactly(1L, 2L);

    assertThat(order.getItems()).extracting(OrderItem::getQuantity).containsExactly(2, 2);
  }
}
