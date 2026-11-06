package com.example.orderapi.api;

import static com.example.orderapi.support.OrderApiTestFixture.CANCELLED_ID;
import static com.example.orderapi.support.OrderApiTestFixture.CONFIRMED_ID;
import static com.example.orderapi.support.OrderApiTestFixture.CREATED_ID;
import static com.example.orderapi.support.OrderApiTestFixture.KEYBOARD_ID;
import static com.example.orderapi.support.OrderApiTestFixture.MISSING_ID;
import static com.example.orderapi.support.OrderApiTestFixture.MOUSE_ID;
import static com.example.orderapi.support.OrderApiTestFixture.NOW;
import static com.example.orderapi.support.OrderApiTestFixture.POSTAL_CODE;
import static com.example.orderapi.support.OrderApiTestFixture.SEEDED_AT;
import static com.example.orderapi.support.OrderApiTestFixture.SHIPPED_ID;
import static com.example.orderapi.support.OrderApiTestFixture.databaseState;
import static com.example.orderapi.support.OrderApiTestFixture.multiItemRequest;
import static com.example.orderapi.support.OrderApiTestFixture.stockOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.example.orderapi.support.OrderApiTestSupport;
import io.restassured.http.ContentType;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.jdbc.Sql;
import tools.jackson.databind.JsonNode;

/**
 * PATCH の HTTP 入口から状態変更・在庫復元・日時保存までを接続する。
 *
 * <p>業務シナリオを横断する 1 ケースは API 経由で準備し、個別のキャンセル・拒否は SQL で準備する。 Test Method 間の順番では状態を引き継がず、連続操作は 1
 * Method 内に閉じ込める。
 */
@DisplayName("Unit 09 - 注文状態遷移 API")
class OrderLifecycleApiTest extends OrderApiTestSupport {

  /**
   * 作成 → 取得 → 確定 → 取得 → 発送 → 取得を、生成 ID でつなぐ代表シナリオ。
   *
   * <p>各 HTTP 処理が別々に Commit する。確定・発送では在庫を再減算せず、送料も再取得しない。 これは制御した外部依存を持つ API の業務シナリオであり、実外部サービスまでの
   * E2E ではない。
   */
  @Test
  @DisplayName("作成した注文を HTTP で取得・確定・発送し、各 Commit を確認する")
  void lifecycle_createConfirmShip_preservesItemsAmountsAndStocks() {
    var initial = databaseState(jdbcTemplate);
    assertThat(initial.orders()).isEmpty();
    stubShippingFee(POSTAL_CODE, 11000, 4, 800);

    var created = createOrder(multiItemRequest());
    created.then().statusCode(201).contentType(ContentType.JSON);
    long id = created.jsonPath().getLong("id");
    var createdOrder =
        assertOrder(created, id, "CREATED", POSTAL_CODE, 11000, 1100, 800, 10700, NOW, null);
    assertThat(createdOrder.get("items").size()).isEqualTo(2);
    assertItem(createdOrder, KEYBOARD_ID, "Keyboard", 3000, 2, 6000);
    assertItem(createdOrder, MOUSE_ID, "Mouse", 2500, 2, 5000);
    assertLocation(created, id);
    assertSavedOrder(createdOrder);
    assertStocks(initial, 8, 18);
    var afterCreate = databaseState(jdbcTemplate);
    assertThat(afterCreate.orders()).hasSize(1);
    assertThat(afterCreate.orderItems()).hasSize(2);
    assertReadableAs(id, createdOrder);

    var confirmed = transition(id, "confirm");
    confirmed.then().statusCode(200).contentType(ContentType.JSON);
    var confirmedOrder =
        assertOrder(confirmed, id, "CONFIRMED", POSTAL_CODE, 11000, 1100, 800, 10700, NOW, null);
    assertSavedOrder(confirmedOrder);
    assertOrderTransition(afterCreate, id, "CONFIRMED", null);
    assertThat(databaseState(jdbcTemplate).products()).isEqualTo(afterCreate.products());
    var afterConfirm = databaseState(jdbcTemplate);
    assertReadableAs(id, confirmedOrder);

    var shipped = transition(id, "ship");
    shipped.then().statusCode(200).contentType(ContentType.JSON);
    var shippedOrder =
        assertOrder(shipped, id, "SHIPPED", POSTAL_CODE, 11000, 1100, 800, 10700, NOW, null);
    assertSavedOrder(shippedOrder);
    assertOrderTransition(afterConfirm, id, "SHIPPED", null);
    assertThat(databaseState(jdbcTemplate).products()).isEqualTo(afterCreate.products());
    assertReadableAs(id, shippedOrder);
    verifyShippingRequest(POSTAL_CODE, 11000, 4);
  }

  /** HTTP で再取得した表現が更新 Response と一致し、GET が保存済み状態を変更しない。 */
  private void assertReadableAs(long id, JsonNode expected) {
    var before = databaseState(jdbcTemplate);
    var response = getOrder(id);
    response.then().statusCode(200).contentType(ContentType.JSON);
    assertThat(body(response)).isEqualTo(expected);
    assertThat(databaseState(jdbcTemplate)).isEqualTo(before);
  }

  /**
   * 2 状態それぞれを独立した Commit 済み Fixture からキャンセルする。
   *
   * <p>初期在庫 4 / 14 には他の有効注文の予約も含む。対象注文の 2 個ずつだけを戻し、 6 / 16 とする。ヘッダ・全明細・他注文・無関係商品も合わせて観測する。
   */
  @ParameterizedTest(name = "{0} → CANCELLED")
  @MethodSource("cancellableOrders")
  @Sql("/sql/unit09/lifecycle-orders.sql")
  @DisplayName("作成済み・確定済みのキャンセルは日時と全明細分の在庫復元を Commit する")
  void cancel_allowedStatus_commitsTimestampAndRestoresOnlyTargetStock(
      String initialStatus, long id) {
    var before = databaseState(jdbcTemplate);
    assertThat(before.orders()).hasSize(4);
    assertThat(before.orderItems()).hasSize(8);
    assertThat(before.orders())
        .filteredOn(row -> row.get("id").equals(id))
        .singleElement()
        .satisfies(row -> assertThat(row).containsEntry("status", initialStatus));
    assertThat(stockOf(jdbcTemplate, KEYBOARD_ID)).isEqualTo(4);
    assertThat(stockOf(jdbcTemplate, MOUSE_ID)).isEqualTo(14);

    var response = transition(id, "cancel");

    response.then().statusCode(200).contentType(ContentType.JSON).body("items.size()", equalTo(2));
    var order =
        assertOrder(
            response, id, "CANCELLED", POSTAL_CODE, 11000, 1100, 800, 10700, SEEDED_AT, NOW);
    assertItem(order, KEYBOARD_ID, "Keyboard", 3000, 2, 6000);
    assertItem(order, MOUSE_ID, "Mouse", 2500, 2, 5000);
    assertSavedOrder(order);
    assertOrderTransition(before, id, "CANCELLED", NOW);
    assertStocks(before, 6, 16);
    assertReadableAs(id, order);
    verifyNoShippingRequest();
  }

  static Stream<Arguments> cancellableOrders() {
    return Stream.of(Arguments.of("CREATED", CREATED_ID), Arguments.of("CONFIRMED", CONFIRMED_ID));
  }

  /** 2 回目の失敗後は、最初の Fixture ではなく 1 回目の正当な Commit 後と比較する。 在庫の二重復元やキャンセル日時の再更新、先行 Commit の消失を検出する。 */
  @Test
  @Sql("/sql/unit09/lifecycle-orders.sql")
  @DisplayName("2 回目のキャンセルは 409 で、1 回目の在庫・日時を維持する")
  void cancel_twice_preservesFirstCommittedCancellation() {
    var before = databaseState(jdbcTemplate);
    var first = transition(CREATED_ID, "cancel");
    first.then().statusCode(200).contentType(ContentType.JSON).body("items.size()", equalTo(2));
    var order =
        assertOrder(
            first, CREATED_ID, "CANCELLED", POSTAL_CODE, 11000, 1100, 800, 10700, SEEDED_AT, NOW);
    assertSavedOrder(order);
    assertOrderTransition(before, CREATED_ID, "CANCELLED", NOW);
    assertStocks(before, 6, 16);
    var afterFirst = databaseState(jdbcTemplate);

    var second = transition(CREATED_ID, "cancel");

    var error =
        assertError(
            second,
            409,
            "INVALID_ORDER_STATUS_TRANSITION",
            "invalid order status transition: orderId=92001, current=CANCELLED, target=CANCELLED");
    assertThat(error.get("fieldErrors").isEmpty()).isTrue();
    assertThat(databaseState(jdbcTemplate)).isEqualTo(afterFirst);
    verifyNoShippingRequest();
  }

  /** 全状態表は Domain Test に任せ、HTTP と DB をつなぐ代表的な拒否を選ぶ。 */
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidTransitions")
  @Sql("/sql/unit09/lifecycle-orders.sql")
  @DisplayName("禁止された状態遷移は 409 となり、全テーブルを保持する")
  void transition_invalidStatus_preservesDatabase(
      String scenario, long id, String operation, String current, String target) {
    var before = databaseState(jdbcTemplate);
    var response = transition(id, operation);
    var error =
        assertError(
            response,
            409,
            "INVALID_ORDER_STATUS_TRANSITION",
            "invalid order status transition: orderId=%d, current=%s, target=%s"
                .formatted(id, current, target));
    assertThat(error.get("fieldErrors").isEmpty()).isTrue();
    assertThat(databaseState(jdbcTemplate)).isEqualTo(before);
    verifyNoShippingRequest();
  }

  static Stream<Arguments> invalidTransitions() {
    return Stream.of(
        Arguments.of("確定済みの再確定", CONFIRMED_ID, "confirm", "CONFIRMED", "CONFIRMED"),
        Arguments.of("作成済みから直接発送", CREATED_ID, "ship", "CREATED", "SHIPPED"),
        Arguments.of("発送済みのキャンセル", SHIPPED_ID, "cancel", "SHIPPED", "CANCELLED"),
        Arguments.of("キャンセル済みの再キャンセル", CANCELLED_ID, "cancel", "CANCELLED", "CANCELLED"));
  }

  /** どの更新入口でも、存在しない注文を成功扱いせず同じ業務エラーへ対応付ける。 */
  @ParameterizedTest(name = "不存在注文の {0}")
  @ValueSource(strings = {"confirm", "ship", "cancel"})
  @Sql("/sql/unit09/lifecycle-orders.sql")
  @DisplayName("不存在注文への更新は 404 / ORDER_NOT_FOUND")
  void transition_missingOrder_returns404WithoutSideEffects(String operation) {
    var before = databaseState(jdbcTemplate);
    var error =
        assertError(
            transition(MISSING_ID, operation), 404, "ORDER_NOT_FOUND", "order not found: 999999");
    assertThat(error.get("fieldErrors").isEmpty()).isTrue();
    assertThat(databaseState(jdbcTemplate)).isEqualTo(before);
    verifyNoShippingRequest();
  }
}
