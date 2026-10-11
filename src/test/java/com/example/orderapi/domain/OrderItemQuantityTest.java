package com.example.orderapi.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 注文数量 1～99 の仕様を、境界値分析と同値分割の観点から確認する Unit Test。
 *
 * <p>仕様で代表値として定義されている 0 / 1 / 2 / 98 / 99 / 100 を使い、 有効範囲の内側と外側を Parameterized Test でまとめて表現する。
 */
@DisplayName("OrderItem 注文数量の境界値")
class OrderItemQuantityTest {

  @Nested
  @DisplayName("有効な数量")
  class ValidQuantity {

    /**
     * 下限・上限と、そのすぐ内側の値が注文数量として受け入れられることを確認する。
     *
     * <p>1 と 99 は境界値そのもの、2 と 98 は境界のすぐ内側を表す。 同じ期待結果となる値を {@link ValueSource} へまとめ、同型の Test Method
     * を増やさない。
     *
     * @param quantity 確認する注文数量
     */
    @ParameterizedTest(name = "quantity={0} は有効")
    @ValueSource(ints = {1, 2, 98, 99})
    void create_acceptsQuantityInsideBoundary(int quantity) {
      OrderItem actual =
          OrderItem.create(1L, "Boundary Product", BigDecimal.valueOf(100), quantity);

      // Test Oracle は「生成された OrderItem が入力した有効数量を保持すること」とする。
      assertThat(actual.getQuantity()).isEqualTo(quantity);
    }
  }

  @Nested
  @DisplayName("無効な数量")
  class InvalidQuantity {

    /**
     * 許容範囲のすぐ外側にある 0 と 100 が拒否されることを確認する。
     *
     * <p>下限未満・上限超過という 2 つの無効同値クラスを、境界に最も近い値で代表させる。 AssertJ の exception assertion を利用し、例外型と message
     * を fluent に検証する。
     *
     * @param quantity 確認する注文数量
     */
    @ParameterizedTest(name = "quantity={0} は無効")
    @ValueSource(ints = {0, 100})
    void create_rejectsQuantityOutsideBoundary(int quantity) {
      assertThatThrownBy(
              () -> OrderItem.create(1L, "Boundary Product", BigDecimal.valueOf(100), quantity))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("quantity must be between 1 and 99");
    }
  }
}
