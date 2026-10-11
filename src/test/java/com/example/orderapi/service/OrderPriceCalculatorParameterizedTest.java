package com.example.orderapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.orderapi.domain.OrderItem;
import com.example.orderapi.domain.PriceSummary;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 割引境界と注文金額計算を、Test Case 設計と JUnit の実践機能を使って確認する Unit Test。
 *
 * <p>Unit 01 の代表ケースを増殖させるのではなく、仕様から境界値や複数明細ケースを整理し、 Parameterized Test
 * によって「同じ検証構造で入力と期待値だけが変わるケース」をまとめる。
 */
@DisplayName("OrderPriceCalculator の Test Case 設計")
class OrderPriceCalculatorParameterizedTest {

  private static final BigDecimal STANDARD_SHIPPING_FEE = BigDecimal.valueOf(800);

  private final OrderPriceCalculator calculator = new OrderPriceCalculator();

  @Nested
  @DisplayName("10,000 円の割引境界")
  class DiscountBoundary {

    /**
     * 9,999 / 10,000 / 10,001 を使い、割引条件が切り替わる境界を確認する。
     *
     * <p>9,999 は境界直前、10,000 は境界そのもの、10,001 は境界直後となる。 入力と期待値を {@link CsvSource} で並べることで、仕様上の境界表と
     * Test Code を対応させる。
     *
     * @param subtotal 商品小計
     * @param expectedDiscount 期待する割引額
     */
    @ParameterizedTest(name = "subtotal={0} -> discount={1}")
    @CsvSource({"9999, 0", "10000, 1000", "10001, 1000"})
    void calculateDiscount_returnsExpectedAmountAroundThreshold(
        long subtotal, long expectedDiscount) {
      BigDecimal actual = calculator.calculateDiscount(BigDecimal.valueOf(subtotal));

      // 金額の数値比較を意図しているため、AssertJ の BigDecimal 用比較を利用する。
      assertThat(actual).isEqualByComparingTo(BigDecimal.valueOf(expectedDiscount));
    }
  }

  /**
   * 単一明細と複数明細を、同じ金額計算の Test Case として確認する。
   *
   * <p>複数の Object を含む入力は {@link MethodSource} から生成し、 primitive / String だけでは表しづらい Fixture を
   * Parameterized Test へ渡す。
   *
   * @param caseName Test Case 名
   * @param items 注文明細
   * @param expectedSubtotal 期待する商品小計
   * @param expectedDiscount 期待する割引額
   * @param expectedTotal 期待する最終合計
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("amountCases")
  @DisplayName("注文全体の金額計算")
  void calculate_returnsExpectedPriceSummary(
      String caseName,
      List<OrderItem> items,
      BigDecimal expectedSubtotal,
      BigDecimal expectedDiscount,
      BigDecimal expectedTotal) {
    PriceSummary actual = calculator.calculate(items, STANDARD_SHIPPING_FEE);

    // 1 回の計算結果に対する複数の関連 Assertion を assertAll でひとまとまりにする。
    assertAll(
        "PriceSummary",
        () -> assertEquals(expectedSubtotal, actual.getSubtotal()),
        () -> assertEquals(expectedDiscount, actual.getDiscountAmount()),
        () -> assertEquals(STANDARD_SHIPPING_FEE, actual.getShippingFee()),
        () -> assertEquals(expectedTotal, actual.getTotalAmount()));
  }

  /**
   * 注文金額計算の複雑な Fixture を Parameterized Test へ供給する。
   *
   * <p>1 ケース目は割引なしの単一明細、2 ケース目は複数明細の合算によって 10,000 円を超え、割引が適用されるケースとなる。
   *
   * @return Test Case の Stream
   */
  static Stream<Arguments> amountCases() {
    return Stream.of(
        Arguments.of(
            "単一明細・割引なし",
            List.of(OrderItem.create(1L, "Single Product", BigDecimal.valueOf(4_000), 2)),
            BigDecimal.valueOf(8_000),
            BigDecimal.ZERO,
            BigDecimal.valueOf(8_800)),
        Arguments.of(
            "複数明細・割引あり",
            List.of(
                OrderItem.create(1L, "Product A", BigDecimal.valueOf(3_000), 2),
                OrderItem.create(2L, "Product B", BigDecimal.valueOf(2_500), 2)),
            BigDecimal.valueOf(11_000),
            BigDecimal.valueOf(1_100),
            BigDecimal.valueOf(10_700)));
  }
}
