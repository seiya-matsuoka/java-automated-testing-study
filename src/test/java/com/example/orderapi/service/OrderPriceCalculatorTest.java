package com.example.orderapi.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.orderapi.domain.OrderItem;
import com.example.orderapi.domain.PriceSummary;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link OrderPriceCalculator} の基本的な Unit Test。
 *
 * <p>Spring Context、DB、HTTP などを起動せず、計算クラスを直接生成して検証する。 Unit 01 では JUnit の基本構造と Assertion
 * の読み方を学ぶことを目的とし、 境界値分析や Parameterized Test などの体系的な Test Case 設計は後続 Unit で扱う。
 */
class OrderPriceCalculatorTest {

  /**
   * 割引対象外の注文について、計算結果の主要な金額が仕様どおりになることを確認する。
   *
   * <p>Arrange-Act-Assert の 3 段階をコメントで明示し、 Test Code が「準備」「実行」「期待結果の検証」で構成されることを読み取れるようにする。
   */
  @Test
  void calculate_returnsPriceSummaryWithoutDiscount() {
    // Arrange: SUT と入力値を準備する。
    OrderPriceCalculator calculator = new OrderPriceCalculator();
    List<OrderItem> items =
        List.of(OrderItem.create(1L, "Standard Product", BigDecimal.valueOf(3_000), 2));
    BigDecimal shippingFee = BigDecimal.valueOf(800);

    // Act: 今回確認したい SUT の振る舞いを 1 回実行する。
    PriceSummary actual = calculator.calculate(items, shippingFee);

    // Assert: 戻り値の存在と、業務仕様上重要な金額を期待値と比較する。
    assertNotNull(actual);
    assertEquals(BigDecimal.valueOf(6_000), actual.getSubtotal());
    assertEquals(BigDecimal.ZERO, actual.getDiscountAmount());
    assertEquals(BigDecimal.valueOf(800), actual.getShippingFee());
    assertEquals(BigDecimal.valueOf(6_800), actual.getTotalAmount());
  }

  /**
   * 商品小計が割引開始額に達した場合、10% の割引が適用されることを確認する。
   *
   * <p>Unit 01 では 1 つの代表ケースとして扱う。 9,999 / 10,000 / 10,001 のような境界値を体系的に比較する方法は Unit 02 で扱う。
   */
  @Test
  void calculate_appliesDiscountWhenSubtotalReachesThreshold() {
    // Given: 割引開始額ちょうど 10,000 円となる明細を準備する。
    OrderPriceCalculator calculator = new OrderPriceCalculator();
    List<OrderItem> items =
        List.of(OrderItem.create(2L, "Discount Boundary Product", BigDecimal.valueOf(10_000), 1));
    BigDecimal shippingFee = BigDecimal.valueOf(800);

    // When: 注文全体の金額を計算する。
    PriceSummary actual = calculator.calculate(items, shippingFee);

    // Then: 10% の割引と、送料を加えた最終合計を確認する。
    assertEquals(BigDecimal.valueOf(10_000), actual.getSubtotal());
    assertEquals(BigDecimal.valueOf(1_000), actual.getDiscountAmount());
    assertEquals(BigDecimal.valueOf(9_800), actual.getTotalAmount());
  }

  /**
   * 注文明細が空の場合、計算結果を返さず例外となることを確認する。
   *
   * <p>{@code assertThrows} は「例外が発生したらテスト失敗」ではなく、 指定した例外が発生すること自体を期待する Test Case で利用する。
   */
  @Test
  void calculate_throwsIllegalArgumentExceptionWhenItemsAreEmpty() {
    // Arrange
    OrderPriceCalculator calculator = new OrderPriceCalculator();
    List<OrderItem> items = List.of();
    BigDecimal shippingFee = BigDecimal.valueOf(800);

    // Act + Assert: 例外発生そのものと、今回の入力エラーを示す message を確認する。
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class, () -> calculator.calculate(items, shippingFee));

    assertEquals("items must not be empty", exception.getMessage());
  }
}
