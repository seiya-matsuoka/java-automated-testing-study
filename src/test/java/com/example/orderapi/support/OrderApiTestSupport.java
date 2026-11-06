package com.example.orderapi.support;

import static com.example.orderapi.support.OrderApiTestFixture.KEYBOARD_ID;
import static com.example.orderapi.support.OrderApiTestFixture.MOUSE_ID;
import static com.example.orderapi.support.OrderApiTestFixture.databaseState;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.orderapi.OrderManagementApplication;
import com.example.orderapi.client.HttpShippingFeeClient;
import com.example.orderapi.client.ShippingFeeClient;
import com.example.orderapi.service.OrderService;
import com.example.orderapi.support.OrderApiTestFixture.DatabaseState;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.context.jdbc.SqlMergeMode;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 実 HTTP を入口にする Unit 09 の起動設定と、通信・保存結果の観測を共有する。
 *
 * <p>Unit 05 の MockMvc は Servlet 内の模擬通信、Unit 07 の NONE は Service が入口、 Unit 08 の Slice は外向き Client
 * が入口だった。今回は Server、MVC、実 Service Proxy、 JDBC、PostgreSQL、実 Client まで通す API Test / 広い Integration
 * Test とする。 Shipping Fee API だけが HTTP-level Test Double であり、実外部サービス・ブラウザ・デプロイ環境 までを含む System / E2E
 * Test や人による受入完了を保証するものではない。
 *
 * <p>Test と Server は別スレッドである。Test 自身に @Transactional は付けず、Fixture は ISOLATED Transaction で Commit
 * する。Response 受信後の直接 SELECT で最終状態を観測し、 AFTER_TEST_METHOD の DELETE は Assertion の後に行う。削除結果を業務
 * Rollback の証拠にしない。
 *
 * <p>同じ構成の 4 クラスは Context / Web Server / DB / WireMock を再利用できる。 共有するのは起動リソースで、行データ・Stub・Request 記録は
 * invocation ごとに初期化する。 ResourceLock は共有状態への同時操作を防ぐ保護であり、Suite の並列実行設定は導入しない。 初回 Context
 * 構築と再利用時では時間が異なるため、速度や時間上限の Assertion は設けない。
 */
@SpringBootTest(
    classes = OrderManagementApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.sql.init.mode=never", "server.address=127.0.0.1"})
@Import(OrderApiTestConfiguration.class)
@SqlConfig(transactionMode = SqlConfig.TransactionMode.ISOLATED)
@SqlMergeMode(SqlMergeMode.MergeMode.MERGE)
@Sql({"/sql/unit09/cleanup.sql", "/sql/unit09/products.sql"})
@Sql(scripts = "/sql/unit09/cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("unit09-api-resources")
public abstract class OrderApiTestSupport {

  protected static final String ORDERS_PATH = "/api/orders";
  private static final String SHIPPING_PATH = "/shipping-fees/calculate";
  private static final String JSON_CONTENT_TYPE = "(?i)application/json(?:\\s*;.*)?";

  @LocalServerPort protected int port;
  @Autowired protected JdbcTemplate jdbcTemplate;
  @Autowired protected JsonMapper jsonMapper;
  @Autowired protected WireMockServer shippingFeeServer;
  @Autowired private PostgreSQLContainer postgresContainer;
  @Autowired private DataSource dataSource;
  @Autowired private Environment environment;
  @Autowired private OrderService orderService;
  @Autowired private ShippingFeeClient shippingFeeClient;

  /**
   * 起動リソースは保持し、HTTP Fixture だけを明示的に初期化する。
   *
   * <p>Unit 08 は Extension が reset を担当したが、今回は Spring Bean の Server を所有する Test 側が担当する。通常用 URL / DB
   * へ接続していないことと、実 Service Proxy / Client を 使用していることも観測し、単に @SpringBootTest が付いているだけで境界を推測しない。
   */
  @BeforeEach
  void prepareIsolatedApiBoundary() throws Exception {
    shippingFeeServer.resetAll();
    assertThat(shippingFeeServer.listAllStubMappings().getMappings()).isEmpty();
    assertThat(shippingFeeServer.getAllServeEvents()).isEmpty();
    assertThat(TestTransaction.isActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(port).isPositive();
    assertThat(AopUtils.isAopProxy(orderService)).isTrue();
    assertThat(shippingFeeClient).isInstanceOf(HttpShippingFeeClient.class);
    assertThat(environment.getProperty("external.shipping-fee.base-url"))
        .isEqualTo("http://127.0.0.1:" + shippingFeeServer.port());
    try (var connection = dataSource.getConnection()) {
      assertThat(connection.getMetaData().getURL()).isEqualTo(postgresContainer.getJdbcUrl());
      assertThat(connection.getCatalog()).isEqualTo("unit09_api_test");
    }
    assertThat(databaseState(jdbcTemplate).products()).hasSize(4);
  }

  /** この検査の後も Spring の @Sql Cleanup は実行される。Test 本体の失敗時にも適用される。 */
  @AfterEach
  void verifyNoUnmatchedShippingRequests() {
    assertThat(shippingFeeServer.findAllUnmatchedRequests()).isEmpty();
  }

  /**
   * 最後の AFTER_TEST_METHOD 後にも行データが空であることを観測する。
   *
   * <p>次クラスの開始時 DELETE によって前クラスの Cleanup 漏れが隠れないようにする。 Context / Server / Container はここで停止せず、同じ
   * Context を使う次クラスへ引き継ぐ。
   */
  @AfterAll
  static void verifyCleanupAfterClass(@Autowired JdbcTemplate jdbcTemplate) {
    var afterCleanup = databaseState(jdbcTemplate);
    assertThat(afterCleanup.products()).isEmpty();
    assertThat(afterCleanup.orders()).isEmpty();
    assertThat(afterCleanup.orderItems()).isEmpty();
  }

  /**
   * Request ごとに新しい Specification を作り、query / body / 接続先を持ち越さない。
   *
   * <p>RestAssured.baseURI / port / requestSpecification / config には代入しない。 通信 timeout
   * は異常時の待機を制限する設定であり、Performance Test の時間 Assertion ではない。
   */
  protected RequestSpecification request() {
    return RestAssured.given()
        .spec(
            new RequestSpecBuilder()
                .setBaseUri("http://127.0.0.1")
                .setPort(port)
                .setAccept(ContentType.JSON)
                .setContentType(ContentType.JSON)
                .setConfig(
                    RestAssuredConfig.config()
                        .httpClient(
                            HttpClientConfig.httpClientConfig()
                                .connectTimeout(Duration.ofSeconds(5))
                                .readTimeout(Duration.ofSeconds(10))))
                .build());
  }

  protected Response createOrder(String requestJson) {
    return request().body(requestJson).when().post(ORDERS_PATH);
  }

  protected Response getOrder(long id) {
    return request().pathParam("id", id).when().get(ORDERS_PATH + "/{id}");
  }

  protected Response transition(long id, String operation) {
    return request()
        .pathParam("id", id)
        .pathParam("operation", operation)
        .when()
        .patch(ORDERS_PATH + "/{id}/{operation}");
  }

  /** Response の JSON を観測する補助。Production の DTO / 金額計算は期待値に利用しない。 */
  protected JsonNode body(Response response) {
    return jsonMapper.readTree(response.asString());
  }

  /** JSON 数値の型を確認し、float / double の丸めや BigDecimal の scale 差に依存せず比較する。 */
  protected void assertMoney(JsonNode actual, long expected) {
    assertThat(actual.isNumber()).isTrue();
    assertThat(actual.decimalValue()).isEqualByComparingTo(BigDecimal.valueOf(expected));
  }

  /** Status / Content-Type は呼び出し元で確認し、この補助は公開される注文 field を確認する。 */
  protected JsonNode assertOrder(
      Response response,
      long id,
      String status,
      String postalCode,
      long subtotal,
      long discount,
      long fee,
      long total,
      Instant createdAt,
      Instant cancelledAt) {
    JsonNode actual = body(response);
    assertThat(actual.size()).isEqualTo(10);
    assertThat(actual.get("id").isIntegralNumber()).isTrue();
    assertThat(actual.get("id").longValue()).isEqualTo(id).isPositive();
    assertThat(actual.get("status").stringValue()).isEqualTo(status);
    assertThat(actual.get("shippingPostalCode").stringValue()).isEqualTo(postalCode);
    assertMoney(actual.get("subtotal"), subtotal);
    assertMoney(actual.get("discountAmount"), discount);
    assertMoney(actual.get("shippingFee"), fee);
    assertMoney(actual.get("totalAmount"), total);
    assertThat(Instant.parse(actual.get("createdAt").stringValue())).isEqualTo(createdAt);
    if (cancelledAt == null) {
      // field の省略と JSON null を区別する。
      assertThat(actual.has("cancelledAt")).isTrue();
      assertThat(actual.get("cancelledAt").isNull()).isTrue();
    } else {
      assertThat(Instant.parse(actual.get("cancelledAt").stringValue())).isEqualTo(cancelledAt);
    }
    return actual;
  }

  /** 明細の順番を新たな API 仕様にせず、商品 ID ごとの値と重複のない対応を確認する。 */
  protected void assertItem(
      JsonNode order, long productId, String name, long unitPrice, int quantity, long lineAmount) {
    int found = 0;
    for (JsonNode item : order.get("items")) {
      if (item.get("productId").longValue() == productId) {
        found++;
        assertThat(item.size()).isEqualTo(6);
        assertThat(item.get("id").longValue()).isPositive();
        assertThat(item.get("productName").stringValue()).isEqualTo(name);
        assertThat(item.get("quantity").intValue()).isEqualTo(quantity);
        assertMoney(item.get("unitPrice"), unitPrice);
        assertMoney(item.get("lineAmount"), lineAmount);
      }
    }
    assertThat(found).as("商品ごとの明細は正確に 1 件").isEqualTo(1);
  }

  /**
   * 公開 JSON と Commit 済みの保存結果を全 field で対応付ける。
   *
   * <p>仕様からの独立した期待値は assertOrder / assertItem で先に確認する。この比較だけでは Response と DB
   * が同じ誤った値を持つ不具合を検出できないため、両者を組み合わせる。
   */
  protected void assertSavedOrder(JsonNode order) {
    long id = order.get("id").longValue();
    var header = jdbcTemplate.queryForMap("SELECT * FROM orders WHERE id = ?", id);
    assertThat(header)
        .containsEntry("id", id)
        .containsEntry("status", order.get("status").stringValue())
        .containsEntry("shipping_postal_code", order.get("shippingPostalCode").stringValue())
        .containsEntry(
            "created_at", Timestamp.from(Instant.parse(order.get("createdAt").stringValue())));
    for (String[] field :
        new String[][] {
          {"subtotal", "subtotal"}, {"discount_amount", "discountAmount"},
          {"shipping_fee", "shippingFee"}, {"total_amount", "totalAmount"}
        }) {
      assertThat((BigDecimal) header.get(field[0]))
          .isEqualByComparingTo(order.get(field[1]).decimalValue());
    }
    var cancelled = order.get("cancelledAt");
    assertThat(header.get("cancelled_at"))
        .isEqualTo(
            cancelled.isNull() ? null : Timestamp.from(Instant.parse(cancelled.stringValue())));
    var rows =
        jdbcTemplate.queryForList("SELECT * FROM order_items WHERE order_id = ? ORDER BY id", id);
    assertThat(rows).hasSize(order.get("items").size());
    for (JsonNode item : order.get("items")) {
      var row =
          rows.stream()
              .filter(r -> r.get("id").equals(item.get("id").longValue()))
              .findFirst()
              .orElseThrow();
      assertThat(row)
          .containsEntry("order_id", id)
          .containsEntry("product_id", item.get("productId").longValue())
          .containsEntry("product_name", item.get("productName").stringValue())
          .containsEntry("quantity", item.get("quantity").intValue());
      assertThat((BigDecimal) row.get("unit_price"))
          .isEqualByComparingTo(item.get("unitPrice").decimalValue());
      assertThat((BigDecimal) row.get("line_amount"))
          .isEqualByComparingTo(item.get("lineAmount").decimalValue());
    }
  }

  /** Location の例示を固定文字列として扱わず、実 Request の origin と生成 ID を照合する。 */
  protected URI assertLocation(Response response, long id) {
    URI location = URI.create(response.header("Location"));
    assertThat(location.getScheme()).isEqualTo("http");
    assertThat(location.getHost()).isEqualTo("127.0.0.1");
    assertThat(location.getPort()).isEqualTo(port);
    assertThat(location.getPath()).isEqualTo(ORDERS_PATH + "/" + id);
    assertThat(location.getQuery()).isNull();
    assertThat(location.getFragment()).isNull();
    return location;
  }

  /** 金額・明細・他注文を保存し、対象ヘッダの状態とキャンセル日時だけが変わったことを確認する。 */
  protected void assertOrderTransition(
      DatabaseState before, long id, String status, Instant cancelledAt) {
    var expected =
        before.orders().stream()
            .map(row -> {
              Map<String, Object> copy = new LinkedHashMap<>(row);
              if (row.get("id").equals(id)) {
                copy.put("status", status);
                copy.put("cancelled_at", cancelledAt == null ? null : Timestamp.from(cancelledAt));
              }
              return copy;
            })
            .toList();
    var after = databaseState(jdbcTemplate);
    assertThat(after.orders()).isEqualTo(expected);
    assertThat(after.orderItems()).isEqualTo(before.orderItems());
  }

  /** 指定在庫以外の商品列・無関係な商品の不変も確認する。期待在庫は Test が仕様から明示する。 */
  protected void assertStocks(DatabaseState before, int keyboardStock, int mouseStock) {
    var expected =
        before.products().stream()
            .map(row -> {
              Map<String, Object> copy = new LinkedHashMap<>(row);
              if (row.get("id").equals(KEYBOARD_ID)) {
                copy.put("stock_quantity", keyboardStock);
              } else if (row.get("id").equals(MOUSE_ID)) {
                copy.put("stock_quantity", mouseStock);
              }
              return copy;
            })
            .toList();
    assertThat(databaseState(jdbcTemplate).products()).isEqualTo(expected);
  }

  /** 正常時は厳しい matching で応答を選ぶ。登録だけで送信の保証が済んだとは扱わない。 */
  protected void stubShippingFee(String postalCode, int subtotal, int quantity, int fee) {
    shippingFeeServer.stubFor(
        post(urlEqualTo(SHIPPING_PATH))
            .withHeader("Content-Type", matching(JSON_CONTENT_TYPE))
            .withRequestBody(equalToJson(shippingRequest(postalCode, subtotal, quantity)))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"shippingFee\":%d}".formatted(fee))));
  }

  /** 異常時は応答を再現する条件と、Act 後に実送信を確認する条件を分ける。 */
  protected void stubShippingResponse(int status, String responseBody) {
    shippingFeeServer.stubFor(
        post(urlEqualTo(SHIPPING_PATH))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(responseBody)));
  }

  /** 送料 Contract を文字列で明示し、Production の Request DTO を期待側で再利用しない。 */
  private String shippingRequest(String postalCode, int subtotal, int quantity) {
    return """
    {"postalCode":"%s","subtotal":%d,"totalQuantity":%d}
    """
        .formatted(postalCode, subtotal, quantity);
  }

  /** 期待する 1 回と全受信件数を確認し、余分な通信や JSON の数値・文字列の混同も検出する。 */
  protected void verifyShippingRequest(String postalCode, int subtotal, int quantity) {
    shippingFeeServer.verify(
        1,
        postRequestedFor(urlEqualTo(SHIPPING_PATH))
            .withHeader("Content-Type", matching(JSON_CONTENT_TYPE))
            .withRequestBody(equalToJson(shippingRequest(postalCode, subtotal, quantity))));
    assertThat(shippingFeeServer.getAllServeEvents()).hasSize(1);
    var received =
        jsonMapper.readTree(
            shippingFeeServer.getAllServeEvents().getFirst().getRequest().getBodyAsString());
    assertThat(received.get("postalCode").isString()).isTrue();
    assertThat(received.get("postalCode").stringValue()).isEqualTo(postalCode);
    assertThat(received.get("subtotal").isNumber()).isTrue();
    assertMoney(received.get("subtotal"), subtotal);
    assertThat(received.get("totalQuantity").isIntegralNumber()).isTrue();
    assertThat(received.get("totalQuantity").intValue()).isEqualTo(quantity);
  }

  protected void verifyNoShippingRequest() {
    shippingFeeServer.verify(0, anyRequestedFor(anyUrl()));
    assertThat(shippingFeeServer.getAllServeEvents()).isEmpty();
  }

  /** 共通 Error DTO の全 field を検証する。HTTP Status と Error Code は別々の保証である。 */
  protected JsonNode assertError(Response response, int status, String code, String message) {
    response
        .then()
        .statusCode(status)
        .contentType(ContentType.JSON)
        .body("code", org.hamcrest.Matchers.equalTo(code))
        .body("message", org.hamcrest.Matchers.equalTo(message));
    JsonNode actual = body(response);
    assertThat(actual.size()).isEqualTo(3);
    assertThat(actual.get("fieldErrors").isArray()).isTrue();
    return actual;
  }

  /** 同じ要素内の field と message を照合し、Bean Validation の列挙順には依存しない。 */
  protected void assertFieldError(JsonNode error, String field, String message) {
    boolean found = false;
    for (JsonNode entry : error.get("fieldErrors")) {
      if (entry.get("field").stringValue().equals(field)
          && entry.get("message").stringValue().equals(message)) {
        found = true;
      }
    }
    assertThat(found).as("field=%s, message=%s", field, message).isTrue();
  }
}
