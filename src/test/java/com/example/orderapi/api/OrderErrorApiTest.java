package com.example.orderapi.api;

import static com.example.orderapi.support.OrderApiTestFixture.KEYBOARD_ID;
import static com.example.orderapi.support.OrderApiTestFixture.POSTAL_CODE;
import static com.example.orderapi.support.OrderApiTestFixture.databaseState;
import static com.example.orderapi.support.OrderApiTestFixture.singleItemRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.orderapi.support.OrderApiTestSupport;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.context.jdbc.Sql;

/**
 * 入力拒否・業務拒否・送料障害を実経路から共通 Error Response へ変換する。
 *
 * <p>SQL で既存注文も準備し、拒否後に既存行の消失・変更もないことを全テーブルで観測する。 Validation / 事前業務チェック / 送料取得は書き込み前に失敗するため、ここでの DB
 * 不変を Unit 07 の「先行する実更新が Rollback された証拠」と混同しない。
 */
@DisplayName("Unit 09 - Validation・Business Error・外部 API 障害")
class OrderErrorApiTest extends OrderApiTestSupport {

  /**
   * Request DTO の制約を本物の MVC / Bean Validation に通す。
   *
   * <p>field と message は同じ要素内で検証し、複数制約の列挙順に依存しない。 Service を Mock にした未呼び出し検証とは異なり、今回直接保証するのは公開エラー、
   * DB 最終状態不変、外部 HTTP 受信 0 件である。
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidRequests")
  @Sql("/sql/unit09/query-orders.sql")
  @DisplayName("Bean Validation の違反は 400 と field error を返し、副作用を残さない")
  void create_invalidRequest_returnsFieldErrorWithoutSideEffects(
      String scenario, String requestJson, String field, String message) {
    var before = databaseState(jdbcTemplate);

    var response = createOrder(requestJson);

    var error = assertError(response, 400, "VALIDATION_ERROR", "request validation failed");
    assertFieldError(error, field, message);
    assertThat(databaseState(jdbcTemplate)).isEqualTo(before);
    verifyNoShippingRequest();
  }

  static Stream<Arguments> invalidRequests() {
    return Stream.of(
        Arguments.of(
            "郵便番号が未指定",
            """
            {"items":[{"productId":9001,"quantity":2}]}
            """,
            "shippingPostalCode",
            "shippingPostalCode is required"),
        Arguments.of(
            "郵便番号の形式違反",
            singleItemRequest("123-4567", KEYBOARD_ID, 2),
            "shippingPostalCode",
            "shippingPostalCode must be 7 digits"),
        Arguments.of(
            "items が未指定",
            "{\"shippingPostalCode\":\"1000001\"}",
            "items",
            "items must not be empty"),
        Arguments.of(
            "items が空",
            "{\"shippingPostalCode\":\"1000001\",\"items\":[]}",
            "items",
            "items must not be empty"),
        Arguments.of(
            "items に null 要素",
            "{\"shippingPostalCode\":\"1000001\",\"items\":[null]}",
            "items[0]",
            "item must not be null"),
        Arguments.of(
            "productId が未指定",
            """
            {"shippingPostalCode":"1000001","items":[{"quantity":2}]}
            """,
            "items[0].productId",
            "productId is required"),
        Arguments.of(
            "productId が 0",
            singleItemRequest(POSTAL_CODE, 0, 2),
            "items[0].productId",
            "productId must be positive"),
        Arguments.of(
            "quantity が未指定",
            """
            {"shippingPostalCode":"1000001","items":[{"productId":9001}]}
            """,
            "items[0].quantity",
            "quantity is required"),
        Arguments.of(
            "quantity が 0",
            singleItemRequest(POSTAL_CODE, KEYBOARD_ID, 0),
            "items[0].quantity",
            "quantity must be at least 1"),
        Arguments.of(
            "quantity が 100",
            singleItemRequest(POSTAL_CODE, KEYBOARD_ID, 100),
            "items[0].quantity",
            "quantity must be at most 99"));
  }

  /** JSON 読み取り失敗は Bean Validation より前に起き、request 単位のエラーとして公開される。 */
  @ParameterizedTest(name = "{0}")
  @MethodSource("unreadableRequests")
  @Sql("/sql/unit09/query-orders.sql")
  @DisplayName("不正 JSON・型の違反は 400 となり、DB と外部 HTTP に副作用を残さない")
  void create_unreadableJson_returnsRequestError(String scenario, String requestJson) {
    var before = databaseState(jdbcTemplate);
    var error =
        assertError(createOrder(requestJson), 400, "VALIDATION_ERROR", "request body is invalid");
    assertFieldError(error, "request", "request body is invalid");
    assertThat(databaseState(jdbcTemplate)).isEqualTo(before);
    verifyNoShippingRequest();
  }

  static Stream<Arguments> unreadableRequests() {
    return Stream.of(
        Arguments.of("途中で切れた JSON", "{\"shippingPostalCode\":\"1000001\",\"items\":["),
        Arguments.of(
            "quantity を数値に変換できない",
            """
            {"shippingPostalCode":"1000001","items":[{"productId":9001,"quantity":"abc"}]}
            """));
  }

  /**
   * 実 DB の商品条件に対する Service の拒否を観測する。
   *
   * <p>重複以外は有効な Keyboard を先に配置し、後続明細で失敗しても先の商品を減算しない。 送料 API の Stub は登録しない。誤って送信すれば受信 0 件と
   * unmatched の検査で失敗する。
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidBusinessOrders")
  @Sql("/sql/unit09/query-orders.sql")
  @DisplayName("注文の業務拒否は仕様の HTTP Error と DB 不変・送料未呼び出しが対応する")
  void create_businessError_preservesDatabaseAndSkipsShipping(
      String scenario, String requestJson, int status, String code, String message) {
    var before = databaseState(jdbcTemplate);
    var error = assertError(createOrder(requestJson), status, code, message);
    assertThat(error.get("fieldErrors").isEmpty()).isTrue();
    assertThat(databaseState(jdbcTemplate)).isEqualTo(before);
    verifyNoShippingRequest();
  }

  static Stream<Arguments> invalidBusinessOrders() {
    return Stream.of(
        Arguments.of(
            "同一商品が重複",
            twoItemRequest(9001, 1),
            400,
            "DUPLICATE_ORDER_ITEM",
            "duplicate product in order: 9001"),
        Arguments.of(
            "後続商品が存在しない",
            twoItemRequest(999999, 1),
            404,
            "PRODUCT_NOT_FOUND",
            "product not found: 999999"),
        Arguments.of(
            "後続商品が販売停止",
            twoItemRequest(9003, 1),
            409,
            "PRODUCT_INACTIVE",
            "product is inactive: 9003"),
        Arguments.of(
            "後続商品の在庫不足",
            twoItemRequest(9002, 21),
            409,
            "INSUFFICIENT_STOCK",
            "insufficient stock: productId=9002, requested=21, available=20"),
        Arguments.of(
            "後続商品の在庫 0",
            twoItemRequest(9004, 1),
            409,
            "INSUFFICIENT_STOCK",
            "insufficient stock: productId=9004, requested=1, available=0"));
  }

  /** 誤りのある後続商品だけを切り替え、先頭商品は数量 1 の有効な明細に固定する。 */
  private static String twoItemRequest(long secondProductId, int secondQuantity) {
    return """
    {"shippingPostalCode":"1000001","items":[
      {"productId":9001,"quantity":1},
      {"productId":%d,"quantity":%d}
    ]}
    """
        .formatted(secondProductId, secondQuantity);
  }

  /**
   * WireMock の異常 → 実 Client → Service の業務例外 → Advice の 502 をつなぐ。
   *
   * <p>Unit 08 の全 Client 異常ケースを重複させず、HTTP Status・JSON 構文・必須 field の 代表経路を選ぶ。API が公開しない cause の詳細は既存
   * Client Test の観測に任せる。 書き込み前の失敗なので、途中更新の Rollback の証明とは説明しない。
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("shippingFailures")
  @Sql("/sql/unit09/query-orders.sql")
  @DisplayName("送料 API の異常は 502 と公開メッセージになり、DB を変更しない")
  void create_shippingFailure_returns502AndPreservesDatabase(
      String scenario, int upstreamStatus, String upstreamBody) {
    var before = databaseState(jdbcTemplate);
    stubShippingResponse(upstreamStatus, upstreamBody);

    var response = createOrder(singleItemRequest(POSTAL_CODE, KEYBOARD_ID, 2));

    var error =
        assertError(
            response, 502, "SHIPPING_FEE_SERVICE_ERROR", "shipping fee service is unavailable");
    assertThat(error.get("fieldErrors").isEmpty()).isTrue();
    assertThat(response.asString())
        .doesNotContain("unit09-upstream-private-detail", "stackTrace", "cause");
    assertThat(databaseState(jdbcTemplate)).isEqualTo(before);
    verifyShippingRequest(POSTAL_CODE, 6000, 2);
  }

  static Stream<Arguments> shippingFailures() {
    return Stream.of(
        Arguments.of("upstream HTTP 500", 500, "{\"error\":\"unit09-upstream-private-detail\"}"),
        Arguments.of("HTTP 200 だが JSON 構文不正", 200, "{\"shippingFee\":"),
        Arguments.of("HTTP 200 だが必須 field 欠落", 200, "{}"));
  }
}
