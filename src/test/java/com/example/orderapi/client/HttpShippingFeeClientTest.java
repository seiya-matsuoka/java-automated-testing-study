package com.example.orderapi.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.example.orderapi.config.RestClientConfig;
import com.example.orderapi.service.OrderService;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.math.BigDecimal;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.AutoConfigureMockRestServiceServer;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 実 HTTP Client と WireMock の間で、外部 Shipping Fee API の HTTP 境界を確認する Test。
 *
 * <p>SUT は HttpShippingFeeClient であり、RestClient、Request / Response DTO、JSON 変換、HTTP 通信は 本物を使う。Unit
 * 03・07 のように ShippingFeeClient の Java method を Mockito で置き換えず、 Client の接続先 Server を HTTP-level Test
 * Double にする。実外部サービスの稼働状態やデータには依存しない。
 *
 * <p>@RestClientTest は HTTP Client 用の Spring Context に範囲を限定する。実 RestClientConfig を import し、Boot
 * が構成した Builder と JSON 変換を通す。通常の MockRestServiceServer は無効にし、 Request Factory 内で応答を差し替えず、WireMock
 * の動的ポートまで実 HTTP request を送る。 Service、Repository、PostgreSQL、アプリ本体の Web Server は起動しない。
 *
 * <p>Server は static Extension がクラス開始前に起動し、終了時に停止する。PER_METHOD で Test Instance を 各 invocation
 * ごとに生成するため、Server の起動後に Spring の field injection が行われる。 Context もクラス終了時に破棄し、停止した Server の URL
 * を別クラスへ持ち越さない。
 */
@RestClientTest(HttpShippingFeeClient.class)
@Import(RestClientConfig.class)
@AutoConfigureMockRestServiceServer(enabled = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Unit 08 - 外部 Shipping Fee API の HTTP Client")
class HttpShippingFeeClientTest {

  private static final String CALCULATE_PATH = "/shipping-fees/calculate";
  private static final String POSTAL_CODE = "1000001";
  private static final BigDecimal SUBTOTAL = BigDecimal.valueOf(12000);
  private static final int TOTAL_QUANTITY = 3;

  // 期待 JSON は API 仕様から明示する。Production の DTO を serialize して期待値に流用すると、
  // 同じ field 名の誤りを送信側・期待側の両方へ持ち込み、テストが見逃す可能性がある。
  private static final String EXPECTED_REQUEST =
      """
      {
        "postalCode": "1000001",
        "subtotal": 12000,
        "totalQuantity": 3
      }
      """;

  // charset の有無は JSON という表現形式の保証と分ける。Header 全文の一致には固定しない。
  private static final String JSON_CONTENT_TYPE = "(?i)application/json(?:\\s*;.*)?";

  /**
   * 通常確認用 Compose の 8081 とは別の、テスト専用 Server を JVM 内に用意する。
   *
   * <p>dynamicPort は OS に空きポートを割り当てさせ、固定ポートの競合を避ける。 plain HTTP の HTTP/2 は通常確認用設定と同様に無効にし、今回は
   * HTTP/1.1 の Contract を読む。 Stub・verification はこのインスタンスへ向け、グローバルな static DSL の接続先は設定しない。
   *
   * <p>resetOnEachTest は Stub と Request Journal を各 invocation の前にリセットする。 failOnUnmatchedRequests
   * は、想定した Stub に一致しない通信も Test Failure として検出する。 Container、ファイル mapping、proxy による実外部 API への転送は使用しない。
   */
  @RegisterExtension
  static final WireMockExtension wireMock =
      WireMockExtension.newInstance()
          .options(wireMockConfig().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true))
          .configureStaticDsl(false)
          .resetOnEachTest(true)
          .failOnUnmatchedRequests(true)
          .build();

  @Autowired private HttpShippingFeeClient shippingFeeClient;
  @Autowired private JsonMapper jsonMapper;
  @Autowired private ApplicationContext applicationContext;

  /**
   * 実 RestClientConfig が参照する property に、起動済み Server の URL を渡す。
   *
   * <p>supplier は property 解決時に実ポートを読み取る。application.yml の localhost:8081 を 使わず、各クラスが所有する Server
   * へ接続するための設定である。
   */
  @DynamicPropertySource
  static void shippingFeeBaseUrl(DynamicPropertyRegistry registry) {
    registry.add("external.shipping-fee.base-url", () -> "http://127.0.0.1:" + wireMock.getPort());
  }

  /**
   * Stub 登録前に初期状態を観測し、順序や前回の通信に依存していないことを確認する。
   *
   * <p>reset 自体は Extension に任せ、ここで手動 reset を重ねない。正常ケースの繰り返しと Parameterized Test の各 invocation
   * でも、同じ空の状態から開始する。
   */
  @BeforeEach
  void verifyIsolatedHttpClientBoundary() {
    assertThat(wireMock.listAllStubMappings().getMappings()).isEmpty();
    assertThat(wireMock.getAllServeEvents()).isEmpty();
    assertThat(applicationContext.getBeansOfType(DataSource.class)).isEmpty();
    assertThat(applicationContext.getBeansOfType(OrderService.class)).isEmpty();
  }

  /**
   * 正常な JSON を実 DTO へ変換し、送料を返す代表例。同じケースを 2 回独立して実行する。
   *
   * <p>Stub の matching は「どの request にこの response を返すか」を決める。 Response の戻り値だけでなく、Act 後の verification
   * で実際の送信内容・回数も確認する。
   */
  @RepeatedTest(value = 2, name = "独立した HTTP Fixture {currentRepetition}/{totalRepetitions}")
  @DisplayName("200 の正常 JSON から送料を取得し、正しい Request を毎回 1 回だけ送る")
  void calculateShippingFee_validResponse_returnsFeeAndSendsExpectedRequest() {
    wireMock.stubFor(
        post(urlEqualTo(CALCULATE_PATH))
            .withHeader("Content-Type", matching(JSON_CONTENT_TYPE))
            .withRequestBody(equalToJson(EXPECTED_REQUEST))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"shippingFee": 800}
                        """)));

    BigDecimal actual =
        shippingFeeClient.calculateShippingFee(POSTAL_CODE, SUBTOTAL, TOTAL_QUANTITY);

    // BigDecimal の scale ではなく、送料の数値としての一致を保証する。
    assertThat(actual).isEqualByComparingTo("800");
    verifySingleRequest(EXPECTED_REQUEST, POSTAL_CODE, SUBTOTAL, TOTAL_QUANTITY);
  }

  /** 送料 0 は有効な下限。異なる入力と先頭 0 の郵便番号により、送信値の固定化も検出する。 */
  @Test
  @DisplayName("送料 0 を受け入れ、別の送信値と郵便番号の先頭 0 を保持する")
  void calculateShippingFee_zeroFee_preservesDifferentRequestValues() {
    String expectedRequest =
        """
        {"postalCode": "0600001", "subtotal": 6000, "totalQuantity": 2}
        """;
    wireMock.stubFor(
        post(urlEqualTo(CALCULATE_PATH))
            .withHeader("Content-Type", matching(JSON_CONTENT_TYPE))
            .withRequestBody(equalToJson(expectedRequest))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"shippingFee\": 0}")));

    BigDecimal actual =
        shippingFeeClient.calculateShippingFee("0600001", BigDecimal.valueOf(6000), 2);

    assertThat(actual).isEqualByComparingTo(BigDecimal.ZERO);
    verifySingleRequest(expectedRequest, "0600001", BigDecimal.valueOf(6000), 2);
  }

  /** Client は HTTP Status の失敗を送出する。Service の業務例外へ変換する責務は持たない。 */
  @Test
  @DisplayName("500 Response は HTTP 例外となり、Status と Response Body を保持する")
  void calculateShippingFee_serverError_throwsHttpExceptionWithResponse() {
    String errorBody = "{\"error\":\"unit08-shipping-unavailable\"}";
    wireMock.stubFor(
        post(urlEqualTo(CALCULATE_PATH))
            .willReturn(
                aResponse()
                    .withStatus(500)
                    .withHeader("Content-Type", "application/json")
                    .withBody(errorBody)));

    HttpServerErrorException actual =
        catchThrowableOfType(
            HttpServerErrorException.class,
            () -> shippingFeeClient.calculateShippingFee(POSTAL_CODE, SUBTOTAL, TOTAL_QUANTITY));

    assertThat(actual).isNotNull();
    assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(actual.getResponseBodyAsString()).isEqualTo(errorBody);
    verifySingleRequest(EXPECTED_REQUEST, POSTAL_CODE, SUBTOTAL, TOTAL_QUANTITY);
  }

  /**
   * HTTP 200 だけでは成功としない。構文・必須 field・値制約を実 JSON 変換経路で確認する。
   *
   * <p>DTO constructor の例外も Jackson と Spring に包まれるため、HTTP 境界では DTO の例外が
   * 直接送出されるとは考えない。RestClientException の cause に JSON 読み取り失敗が残ることを 確認し、Jackson
   * 内部の細かな例外型や位置情報を含むメッセージ全文へは固定しない。
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidResponses")
  @DisplayName("200 でも不正 JSON・不完全 Response・負数送料は受け入れない")
  void calculateShippingFee_invalidResponse_throwsJsonConversionException(
      String scenario, String responseBody) {
    stubJsonResponse(responseBody);

    assertThatExceptionOfType(RestClientException.class)
        .isThrownBy(
            () -> shippingFeeClient.calculateShippingFee(POSTAL_CODE, SUBTOTAL, TOTAL_QUANTITY))
        .withCauseInstanceOf(HttpMessageNotReadableException.class);

    verifySingleRequest(EXPECTED_REQUEST, POSTAL_CODE, SUBTOTAL, TOTAL_QUANTITY);
  }

  /** 小さな Response Fixture をケース名と並べ、違いを Production DTO の生成に隠さない。 */
  static Stream<Arguments> invalidResponses() {
    return Stream.of(
        // raw な構文エラーを返す。事前に JSON として parse してしまうと、この障害を再現できない。
        Arguments.of("不正 JSON: 値と閉じ括弧が欠けている", "{\"shippingFee\":"),
        Arguments.of("不完全 Response: 必須 field が存在しない", "{}"),
        Arguments.of("必須 field は存在するが null", "{\"shippingFee\": null}"),
        Arguments.of("DTO の下限に違反する負数送料", "{\"shippingFee\": -1}"));
  }

  /** 空 Body は JSON の field 欠落とは異なり、Response DTO 自体を取得できないケースである。 */
  @Test
  @DisplayName("200 でも空 Body は Client 自身の null 検査で拒否する")
  void calculateShippingFee_emptyBody_throwsClientGuardException() {
    wireMock.stubFor(
        post(urlEqualTo(CALCULATE_PATH))
            .willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json")));

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(
            () -> shippingFeeClient.calculateShippingFee(POSTAL_CODE, SUBTOTAL, TOTAL_QUANTITY))
        .withMessage("shipping fee response body must not be null");

    verifySingleRequest(EXPECTED_REQUEST, POSTAL_CODE, SUBTOTAL, TOTAL_QUANTITY);
  }

  /** JSON の null には Body があるが DTO は null。空 Body と同じ Client の検査へ到達する。 */
  @Test
  @DisplayName("JSON 全体の null は有効な送料として返さない")
  void calculateShippingFee_jsonNull_throwsClientGuardException() {
    stubJsonResponse("null");

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(
            () -> shippingFeeClient.calculateShippingFee(POSTAL_CODE, SUBTOTAL, TOTAL_QUANTITY))
        .withMessage("shipping fee response body must not be null");

    verifySingleRequest(EXPECTED_REQUEST, POSTAL_CODE, SUBTOTAL, TOTAL_QUANTITY);
  }

  /** 送信前の Request DTO 検査を代表する 1 ケース。Server の拒否とは区別する。 */
  @Test
  @DisplayName("総数量 0 は HTTP 送信前に拒否し、WireMock へ Request を残さない")
  void calculateShippingFee_invalidQuantity_rejectsBeforeHttpRequest() {
    // Stub は不要。通信が発生すれば、下の記録確認と unmatched request の検査で失敗する。
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> shippingFeeClient.calculateShippingFee(POSTAL_CODE, SUBTOTAL, 0))
        .withMessage("totalQuantity must be positive");

    wireMock.verify(0, anyRequestedFor(anyUrl()));
    assertThat(wireMock.getAllServeEvents()).isEmpty();
  }

  /** 異常 Body を返すケースでは、Method・URL の Stub と、実行後の Request 検証を分ける。 */
  private void stubJsonResponse(String responseBody) {
    wireMock.stubFor(
        post(urlEqualTo(CALCULATE_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(responseBody)));
  }

  /**
   * Stub の登録とは別に、実際に受信した HTTP request の内容と回数を観測する。
   *
   * <p>対象に一致する 1 回に加えて全記録も 1 件とし、別 URL への余分な送信を見逃さない。 equalToJson は field 順序・整形差を無視し、余分な field
   * は許容しない。 続いて受信 Body の JSON 型と値を確認し、郵便番号を数値にしたり、小計を文字列にしたりする誤りも区別する。
   */
  private void verifySingleRequest(
      String expectedJson, String postalCode, BigDecimal subtotal, int totalQuantity) {
    wireMock.verify(
        1,
        postRequestedFor(urlEqualTo(CALCULATE_PATH))
            .withHeader("Content-Type", matching(JSON_CONTENT_TYPE))
            .withRequestBody(equalToJson(expectedJson)));
    assertThat(wireMock.getAllServeEvents()).hasSize(1);

    var requests = wireMock.findRequestsMatching(anyRequestedFor(anyUrl()).build()).getRequests();
    var body = jsonMapper.readTree(requests.getFirst().getBodyAsString());
    assertThat(body.get("postalCode").isString()).isTrue();
    assertThat(body.get("postalCode").stringValue()).isEqualTo(postalCode);
    assertThat(body.get("subtotal").isNumber()).isTrue();
    assertThat(body.get("subtotal").decimalValue()).isEqualByComparingTo(subtotal);
    assertThat(body.get("totalQuantity").isIntegralNumber()).isTrue();
    assertThat(body.get("totalQuantity").intValue()).isEqualTo(totalQuantity);
  }
}
