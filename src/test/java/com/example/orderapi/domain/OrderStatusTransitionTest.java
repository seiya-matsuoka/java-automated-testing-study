package com.example.orderapi.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * OrderStatus の状態と操作可否を Decision Table / Test Matrix として確認する Unit Test。
 *
 * <p>4 状態 × confirm / ship / cancel の組み合わせを先に表として整理したうえで、 1 行を 1 Parameterized Test Case として表現する。
 */
@DisplayName("OrderStatus の状態遷移 Test Matrix")
class OrderStatusTransitionTest {

  /**
   * 各状態で許可される操作を状態遷移表と一致させる。
   *
   * <p>個別の Test Method を 12 個作るのではなく、状態ごとの期待値を 1 行にまとめることで、 条件の全体像を保ちながら重複した Test Code を避ける。
   *
   * @param status 現在の注文状態
   * @param canConfirm 注文確定が可能か
   * @param canShip 発送が可能か
   * @param canCancel キャンセルが可能か
   */
  @ParameterizedTest(name = "{0}: confirm={1}, ship={2}, cancel={3}")
  @CsvSource({
    "CREATED,   true,  false, true",
    "CONFIRMED, false, true,  true",
    "SHIPPED,   false, false, false",
    "CANCELLED, false, false, false"
  })
  void operationAvailability_matchesDecisionTable(
      OrderStatus status, boolean canConfirm, boolean canShip, boolean canCancel) {
    assertAll(
        "operation availability for " + status,
        () -> assertEquals(canConfirm, status.canConfirm()),
        () -> assertEquals(canShip, status.canShip()),
        () -> assertEquals(canCancel, status.canCancel()));
  }
}
