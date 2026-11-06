package com.example.orderapi.api;

import static com.example.orderapi.support.OrderApiTestFixture.CANCELLED_AT;
import static com.example.orderapi.support.OrderApiTestFixture.KEYBOARD_ID;
import static com.example.orderapi.support.OrderApiTestFixture.MISSING_ID;
import static com.example.orderapi.support.OrderApiTestFixture.MOUSE_ID;
import static com.example.orderapi.support.OrderApiTestFixture.POSTAL_CODE;
import static com.example.orderapi.support.OrderApiTestFixture.SEEDED_AT;
import static com.example.orderapi.support.OrderApiTestFixture.databaseState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.example.orderapi.support.OrderApiTestSupport;
import io.restassured.http.ContentType;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.context.jdbc.Sql;

/**
 * Query Parameter 変換から実 SQL、JOIN、Response DTO までを実 HTTP でつなぐ。
 *
 * <p>Unit 05 は Service に渡した引数を検証した。今回は WHERE / ORDER BY の実際の結果を 固定 SQL Fixture の ID
 * 列と比較する。検索結果を期待値の生成に再利用しない。
 */
@DisplayName("Unit 09 - 注文詳細・一覧・条件検索 API")
class OrderQueryApiTest extends OrderApiTestSupport {

  private static final String FROM = "2026-10-02T00:00:00.123456Z";
  private static final String TO = "2026-10-03T00:00:00.123456Z";

  /** 商品マスタと異なる履歴 Snapshot を返し、現在の商品情報へ置換しないことを確認する。 */
  @Test
  @Sql("/sql/unit09/query-orders.sql")
  @DisplayName("詳細は注文時点の商品名・単価と金額を返し、DB・送料に副作用を持たない")
  void findById_returnsHistoricalProductSnapshot() {
    var before = databaseState(jdbcTemplate);

    var response = getOrder(91001L);

    response.then().statusCode(200).contentType(ContentType.JSON).body("items.size()", equalTo(1));
    var order =
        assertOrder(response, 91001L, "CREATED", POSTAL_CODE, 2800, 0, 800, 3600, SEEDED_AT, null);
    assertItem(order, KEYBOARD_ID, "Keyboard at order time", 2800, 1, 2800);
    assertSavedOrder(order);
    assertThat(databaseState(jdbcTemplate)).isEqualTo(before);
    verifyNoShippingRequest();
  }

  /** 複数明細の JOIN 復元と、キャンセル日時がある注文の JSON 表現も確認する。 */
  @Test
  @Sql("/sql/unit09/query-orders.sql")
  @DisplayName("詳細は複数明細とキャンセル済み注文の日時を保持する")
  void findById_returnsAllItemsAndCancelledTimestamp() {
    var before = databaseState(jdbcTemplate);
    var response = getOrder(91003L);
    response.then().statusCode(200).contentType(ContentType.JSON).body("items.size()", equalTo(2));
    var order =
        assertOrder(
            response,
            91003L,
            "CREATED",
            POSTAL_CODE,
            11000,
            1100,
            800,
            10700,
            Instant.parse(FROM),
            null);
    assertItem(order, KEYBOARD_ID, "Keyboard", 3000, 2, 6000);
    assertItem(order, MOUSE_ID, "Mouse", 2500, 2, 5000);
    assertSavedOrder(order);

    var cancelled = getOrder(91005L);
    cancelled.then().statusCode(200).contentType(ContentType.JSON).body("items.size()", equalTo(1));
    var cancelledOrder =
        assertOrder(
            cancelled,
            91005L,
            "CANCELLED",
            POSTAL_CODE,
            3000,
            0,
            800,
            3800,
            Instant.parse("2026-10-04T00:00:00.123456Z"),
            CANCELLED_AT);
    assertItem(cancelledOrder, KEYBOARD_ID, "Keyboard", 3000, 1, 3000);
    assertSavedOrder(cancelledOrder);
    assertThat(databaseState(jdbcTemplate)).isEqualTo(before);
    verifyNoShippingRequest();
  }

  /**
   * 指定有無の 8 組み合わせに、同時刻の上下限と該当なしを加える。
   *
   * <p>明細が 2 件の 91003 も一覧では 1 注文となる。日時は両端を含み、同時刻なら ID 降順。 Summary
   * は明細を含まない。条件・順序・形の確認を、単なる件数確認で済ませない。
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("searchConditions")
  @Sql("/sql/unit09/query-orders.sql")
  @DisplayName("一覧は任意条件を AND で適用し、安定した順序の Summary を返す")
  void search_returnsFilteredOrderedSummaries(
      String scenario, Map<String, String> query, List<Long> expectedIds) {
    var before = databaseState(jdbcTemplate);

    var response = request().queryParams(query).when().get(ORDERS_PATH);

    response
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("size()", equalTo(expectedIds.size()));
    assertThat(response.jsonPath().getList("id", Long.class))
        .containsExactlyElementsOf(expectedIds);
    for (var summary : body(response)) {
      assertThat(summary.size()).isEqualTo(9);
      assertThat(summary.has("items")).isFalse();
      var header =
          before.orders().stream()
              .filter(row -> row.get("id").equals(summary.get("id").longValue()))
              .findFirst()
              .orElseThrow();
      assertThat(summary.get("status").stringValue()).isEqualTo(header.get("status"));
      assertThat(summary.get("shippingPostalCode").stringValue())
          .isEqualTo(header.get("shipping_postal_code"));
      for (var field :
          new String[][] {
            {"subtotal", "subtotal"}, {"discountAmount", "discount_amount"},
            {"shippingFee", "shipping_fee"}, {"totalAmount", "total_amount"}
          }) {
        assertThat(summary.get(field[0]).isNumber()).isTrue();
        assertThat(summary.get(field[0]).decimalValue())
            .isEqualByComparingTo((BigDecimal) header.get(field[1]));
      }
      assertThat(Instant.parse(summary.get("createdAt").stringValue()))
          .isEqualTo(((Timestamp) header.get("created_at")).toInstant());
      if (header.get("cancelled_at") == null) {
        assertThat(summary.get("cancelledAt").isNull()).isTrue();
      } else {
        assertThat(Instant.parse(summary.get("cancelledAt").stringValue())).isEqualTo(CANCELLED_AT);
      }
    }
    assertThat(databaseState(jdbcTemplate)).isEqualTo(before);
    verifyNoShippingRequest();
  }

  static Stream<Arguments> searchConditions() {
    return Stream.of(
        Arguments.of("条件なし・同時刻は ID 降順", Map.of(), List.of(91005L, 91004L, 91003L, 91002L, 91001L)),
        Arguments.of("status のみ", Map.of("status", "CREATED"), List.of(91003L, 91001L)),
        Arguments.of(
            "下限のみ・境界を含む", Map.of("createdFrom", FROM), List.of(91005L, 91004L, 91003L, 91002L)),
        Arguments.of(
            "上限のみ・境界を含む", Map.of("createdTo", TO), List.of(91004L, 91003L, 91002L, 91001L)),
        Arguments.of(
            "status と下限", Map.of("status", "CREATED", "createdFrom", FROM), List.of(91003L)),
        Arguments.of(
            "status と上限", Map.of("status", "CREATED", "createdTo", TO), List.of(91003L, 91001L)),
        Arguments.of(
            "日時範囲", Map.of("createdFrom", FROM, "createdTo", TO), List.of(91004L, 91003L, 91002L)),
        Arguments.of(
            "全条件・範囲内でも別状態を除外",
            Map.of("status", "CREATED", "createdFrom", FROM, "createdTo", TO),
            List.of(91003L)),
        Arguments.of(
            "上下限が同時刻", Map.of("createdFrom", FROM, "createdTo", FROM), List.of(91003L, 91002L)),
        Arguments.of("該当なし", Map.of("status", "SHIPPED", "createdTo", FROM), List.of()));
  }

  /** 未指定条件の結果が空の場合も、404 や null ではなく正常な JSON 配列を返す。 */
  @Test
  @DisplayName("注文 0 件の一覧は 200 と空配列を返す")
  void search_emptyDatabase_returnsEmptyArray() {
    var before = databaseState(jdbcTemplate);
    var response = request().when().get(ORDERS_PATH);
    response.then().statusCode(200).contentType(ContentType.JSON).body("size()", equalTo(0));
    assertThat(body(response).isArray()).isTrue();
    assertThat(databaseState(jdbcTemplate)).isEqualTo(before);
    verifyNoShippingRequest();
  }

  /** 変換エラーと実 Repository の期間整合性エラーを、それぞれ実経路から 400 へ変換する。 */
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidQueries")
  @Sql("/sql/unit09/query-orders.sql")
  @DisplayName("不正な検索条件は field error を返し、既存データを変更しない")
  void search_invalidCondition_returnsValidationError(
      String scenario,
      Map<String, String> query,
      String field,
      String fieldMessage,
      String responseMessage) {
    var before = databaseState(jdbcTemplate);
    var response = request().queryParams(query).when().get(ORDERS_PATH);
    var error = assertError(response, 400, "VALIDATION_ERROR", responseMessage);
    assertFieldError(error, field, fieldMessage);
    assertThat(databaseState(jdbcTemplate)).isEqualTo(before);
    verifyNoShippingRequest();
  }

  static Stream<Arguments> invalidQueries() {
    return Stream.of(
        Arguments.of(
            "未定義 status",
            Map.of("status", "UNKNOWN"),
            "status",
            "invalid value",
            "request parameter is invalid"),
        Arguments.of(
            "不正な下限日時",
            Map.of("createdFrom", "yesterday"),
            "createdFrom",
            "invalid value",
            "request parameter is invalid"),
        Arguments.of(
            "不正な上限日時",
            Map.of("createdTo", "tomorrow"),
            "createdTo",
            "invalid value",
            "request parameter is invalid"),
        Arguments.of(
            "上下限の逆転",
            Map.of("createdFrom", TO, "createdTo", FROM),
            "request",
            "createdFrom must not be after createdTo",
            "request validation failed"));
  }

  /** 実 SQL の未検出から業務例外・404 までを接続し、既存行も保持する。 */
  @Test
  @Sql("/sql/unit09/query-orders.sql")
  @DisplayName("不存在の注文詳細は 404 / ORDER_NOT_FOUND")
  void findById_missingOrder_returns404WithoutSideEffects() {
    var before = databaseState(jdbcTemplate);
    var error =
        assertError(getOrder(MISSING_ID), 404, "ORDER_NOT_FOUND", "order not found: 999999");
    assertThat(error.get("fieldErrors").isEmpty()).isTrue();
    assertThat(databaseState(jdbcTemplate)).isEqualTo(before);
    verifyNoShippingRequest();
  }
}
