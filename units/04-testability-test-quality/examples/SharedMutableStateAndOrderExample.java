package com.example.orderapi.unit04.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Test Isolation、Independence、実行順依存、static mutable state を比較する補助教材。
 *
 * <p>BadExamples は意図的な Test Smell を示す。順序を固定すれば一見成功しても、個別実行や再実行で壊れる。 このファイルは通常の Maven Test Discovery
 * 対象となる src/test/java には置かない。
 */
public class SharedMutableStateAndOrderExample {

  /** 小さな在庫 Fixture。どちらの例でも同じ業務上の値の変化を観測する。 */
  static final class Stock {
    private int quantity;

    Stock(int initialQuantity) {
      this.quantity = initialQuantity;
    }

    void reserve(int amount) {
      quantity -= amount;
    }

    int quantity() {
      return quantity;
    }
  }

  /**
   * 悪い例: 2 Test が 1 つの mutable Fixture を共有し、先行 Test の副作用を前提としている。
   *
   * <p>@Order は見かけ上の成功順序を固定するだけで、Test を独立させない。 2 番目の Test だけを IDE から実行すると初期在庫 10 のままなので失敗する。
   * クラス全体を繰り返す際にも static state を reset しなければ結果が変わる。
   */
  @Nested
  @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
  @DisplayName("悪い例: Test 実行順依存")
  class BadExamples {
    private static final Stock SHARED_STOCK = new Stock(10);

    @Test
    @Order(1)
    void firstTest_modifiesSharedState() {
      SHARED_STOCK.reserve(2);
      assertEquals(8, SHARED_STOCK.quantity());
    }

    @Test
    @Order(2)
    void secondTest_requiresTheFirstTestToRun() {
      // この Test の Arrange が Test 内で完結していないため、単体実行に失敗する。
      assertEquals(8, SHARED_STOCK.quantity());
    }
  }

  /**
   * 改善例: それぞれの Test Method 実行前に新しい Fixture を用意する。
   *
   * <p>結果はほかの Test を実行したかどうかに依存せず、任意の順序・単体実行で成立する。 @BeforeEach は Test 同士の共有 mutable state を reset
   * するのではなく、個々に新しい Object を与える。
   */
  @Nested
  @DisplayName("改善例: Test ごとに独立した Fixture")
  class IndependentExamples {
    private Stock stock;

    @BeforeEach
    void setUp() {
      stock = new Stock(10);
    }

    @Test
    void reserveTwo_hasEightRemaining() {
      stock.reserve(2);
      assertEquals(8, stock.quantity());
    }

    @Test
    void reserveThree_hasSevenRemaining() {
      stock.reserve(3);
      assertEquals(7, stock.quantity());
    }
  }
}
