package com.example.orderapi.api;

import static com.example.orderapi.support.OrderApiTestFixture.KEYBOARD_ID;
import static com.example.orderapi.support.OrderApiTestFixture.MOUSE_ID;
import static com.example.orderapi.support.OrderApiTestFixture.NOW;
import static com.example.orderapi.support.OrderApiTestFixture.POSTAL_CODE;
import static com.example.orderapi.support.OrderApiTestFixture.databaseState;
import static com.example.orderapi.support.OrderApiTestFixture.multiItemRequest;
import static com.example.orderapi.support.OrderApiTestFixture.singleItemRequest;
import static com.example.orderapi.support.OrderApiTestFixture.stockOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.example.orderapi.support.OrderApiTestSupport;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * 実 HTTP で注文を作成し、Response・Commit 済み DB・外部 Request の 3 方向から観測する。
 *
 * <p>Unit 05 の Service Stub や Unit 07 の送料 Mock と異なり、実 Service / JDBC / Client を通す。 201
 * だけで成功とせず、金額・全明細・在庫・生成 ID・Location の対応を確認する。
 */
@DisplayName("Unit 09 - 注文作成 API と保存結果")
class OrderCreateApiTest extends OrderApiTestSupport {

  /**
   * 2 回の invocation はそれぞれ商品 4 件、注文・明細 0 件から開始する。
   *
   * <p>再現性を作るのは繰り返し Annotation だけではなく、共通の SQL / reset である。 明細合計 6,000 + 5,000、割引 1,100、送料 800 から合計
   * 10,700 を独立に期待する。
   */
  @RepeatedTest(value = 2, name = "独立した API Fixture {currentRepetition}/{totalRepetitions}")
  @DisplayName("2 商品の注文は 201・Location・保存内容・在庫減算・送料 Request が対応する")
  void create_multipleItems_commitsAndCanBeReadFromLocation() {
    var before = databaseState(jdbcTemplate);
    assertThat(before.orders()).isEmpty();
    assertThat(before.orderItems()).isEmpty();
    assertThat(stockOf(jdbcTemplate, KEYBOARD_ID)).isEqualTo(10);
    assertThat(stockOf(jdbcTemplate, MOUSE_ID)).isEqualTo(20);
    stubShippingFee(POSTAL_CODE, 11000, 4, 800);

    var response = createOrder(multiItemRequest());

    response
        .then()
        .statusCode(201)
        .contentType(ContentType.JSON)
        .header("Location", notNullValue())
        .body("status", equalTo("CREATED"))
        .body("items.size()", equalTo(2));
    long id = response.jsonPath().getLong("id");
    var order =
        assertOrder(response, id, "CREATED", POSTAL_CODE, 11000, 1100, 800, 10700, NOW, null);
    assertItem(order, KEYBOARD_ID, "Keyboard", 3000, 2, 6000);
    assertItem(order, MOUSE_ID, "Mouse", 2500, 2, 5000);
    assertSavedOrder(order);
    var afterCreate = databaseState(jdbcTemplate);
    assertThat(afterCreate.orders()).hasSize(1);
    assertThat(afterCreate.orderItems()).hasSize(2);
    assertStocks(before, 8, 18);
    verifyShippingRequest(POSTAL_CODE, 11000, 4);

    // 現 Controller は絶対 URI を生成する。origin と ID を検証してから、その Location へ送る。
    var location = assertLocation(response, id);
    var detail = request().when().get(location);
    detail.then().statusCode(200).contentType(ContentType.JSON);
    assertThat(body(detail)).isEqualTo(order);
    assertSavedOrder(body(detail));
    assertThat(databaseState(jdbcTemplate)).isEqualTo(afterCreate);
    // 送料は作成時だけ。GET で再照会していても 2 件目を見逃さない。
    verifyShippingRequest(POSTAL_CODE, 11000, 4);
  }

  /** 郵便番号を数値化せず、外部 API の送料 0 と割引なしを最終 JSON / DB まで保持する。 */
  @Test
  @DisplayName("先頭 0 の郵便番号と送料 0 を保持し、割引なしの単一商品を保存する")
  void create_zeroShippingFee_preservesPostalCodeAndUndiscountedAmount() {
    var before = databaseState(jdbcTemplate);
    stubShippingFee("0600001", 6000, 2, 0);

    var response = createOrder(singleItemRequest("0600001", KEYBOARD_ID, 2));

    response.then().statusCode(201).contentType(ContentType.JSON).body("items.size()", equalTo(1));
    long id = response.jsonPath().getLong("id");
    var order = assertOrder(response, id, "CREATED", "0600001", 6000, 0, 0, 6000, NOW, null);
    assertItem(order, KEYBOARD_ID, "Keyboard", 3000, 2, 6000);
    assertLocation(response, id);
    assertSavedOrder(order);
    assertThat(databaseState(jdbcTemplate).orders()).hasSize(1);
    assertThat(databaseState(jdbcTemplate).orderItems()).hasSize(1);
    assertStocks(before, 8, 20);
    verifyShippingRequest("0600001", 6000, 2);
  }

  /** 在庫 = 数量の境界も、実 HTTP / DB で成功する。境界値の全網羅は既存 Unit Test が担う。 */
  @Test
  @DisplayName("在庫と同数の注文は成功し、在庫 0 を Commit する")
  void create_quantityEqualsStock_commitsZeroStock() {
    var before = databaseState(jdbcTemplate);
    stubShippingFee(POSTAL_CODE, 30000, 10, 800);

    var response = createOrder(singleItemRequest(POSTAL_CODE, KEYBOARD_ID, 10));

    response.then().statusCode(201).contentType(ContentType.JSON).body("items.size()", equalTo(1));
    long id = response.jsonPath().getLong("id");
    var order =
        assertOrder(response, id, "CREATED", POSTAL_CODE, 30000, 3000, 800, 27800, NOW, null);
    assertItem(order, KEYBOARD_ID, "Keyboard", 3000, 10, 30000);
    assertLocation(response, id);
    assertSavedOrder(order);
    assertThat(databaseState(jdbcTemplate).orders()).hasSize(1);
    assertThat(databaseState(jdbcTemplate).orderItems()).hasSize(1);
    assertStocks(before, 0, 20);
    verifyShippingRequest(POSTAL_CODE, 30000, 10);
  }
}
