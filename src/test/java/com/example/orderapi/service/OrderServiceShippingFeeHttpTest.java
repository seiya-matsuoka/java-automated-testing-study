package com.example.orderapi.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.orderapi.client.HttpShippingFeeClient;
import com.example.orderapi.config.RestClientConfig;
import com.example.orderapi.domain.Product;
import com.example.orderapi.exception.ShippingFeeServiceException;
import com.example.orderapi.repository.OrderRepository;
import com.example.orderapi.repository.ProductRepository;
import com.example.orderapi.service.model.CreateOrderCommand;
import com.example.orderapi.service.model.CreateOrderItemCommand;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.autoconfigure.AutoConfigureMockRestServiceServer;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpServerErrorException;

/**
 * 実 HTTP の失敗が Service の業務例外へつながることを確認する、Unit 08 の代表 Test。
 *
 * <p>実 OrderService → 実 HttpShippingFeeClient → 実 RestClient / DTO → WireMock を通す。 Mockito に置き換えるのは
 * Repository の Java Object 境界だけであり、送料 Client の例外は thenThrow で作らず、WireMock の 500 Response
 * から実際に発生させる。
 *
 * <p>Spring Context は Client 用 Slice に限定する。Service は constructor で直接生成し、 固定 Clock と Repository Mock
 * を渡すため、Transaction Proxy、SQL、PostgreSQL は使わない。 書き込み method の未呼び出しを確認する Test であり、実 DB の未更新や業務
 * Rollback の証明は Unit 07 の担当となる。Controller、API の HTTP 502、実 HTTP 入口からの経路は Unit 09 と分ける。
 */
@RestClientTest(HttpShippingFeeClient.class)
@Import(RestClientConfig.class)
@AutoConfigureMockRestServiceServer(enabled = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Unit 08 - 実 HTTP 障害と Service の業務例外変換")
class OrderServiceShippingFeeHttpTest {

  private static final String CALCULATE_PATH = "/shipping-fees/calculate";

  /**
   * このクラス自身が Server と動的 URL を所有し、Client Test の Server・Context は共有しない。
   *
   * <p>static Extension が開始・停止を管理し、Test ごとの Stub・Request Journal をリセットする。 instance DSL と loopback
   * の動的ポートにより、Compose の 8081 や実外部 API から分離する。
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
  @Autowired private ApplicationContext applicationContext;

  /** Boot の Builder と実 RestClientConfig に、起動済みテスト Server の URL を渡す。 */
  @DynamicPropertySource
  static void shippingFeeBaseUrl(DynamicPropertyRegistry registry) {
    registry.add("external.shipping-fee.base-url", () -> "http://127.0.0.1:" + wireMock.getPort());
  }

  /** HTTP Fixture の初期状態と、DB / Service Bean を起動しない Slice の境界を確認する。 */
  @BeforeEach
  void verifyIsolatedHttpClientBoundary() {
    assertThat(wireMock.listAllStubMappings().getMappings()).isEmpty();
    assertThat(wireMock.getAllServeEvents()).isEmpty();
    assertThat(applicationContext.getBeansOfType(DataSource.class)).isEmpty();
    assertThat(applicationContext.getBeansOfType(OrderService.class)).isEmpty();
  }

  /**
   * 有効な商品・数量で事前の業務チェックを通し、送料取得の HTTP 障害だけを発生させる。
   *
   * <p>Unit 03・07 は既知の例外 instance を Stub し、cause の同一性も確認した。 今回は実 HTTP から生じた cause の型・Status・Body
   * を確認して接続部分を補う。 HTTP 例外メッセージ全体は URL や動的ポートを含み得るため、全文一致へ固定しない。
   */
  @Test
  @DisplayName("実 HTTP 500 は業務例外へ変換され、cause を保持して DB 書き込みへ進まない")
  void createOrder_httpServerError_preservesCauseAndDoesNotWriteRepositories() {
    ProductRepository productRepository = mock(ProductRepository.class);
    OrderRepository orderRepository = mock(OrderRepository.class);
    Clock clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);
    OrderService orderService =
        new OrderService(productRepository, orderRepository, shippingFeeClient, clock);

    // 単価 3,000 × 数量 2 = 小計 6,000。在庫 10・販売中とし、送料取得前の拒否を避ける。
    Product product = new Product(1L, "Standard Product", BigDecimal.valueOf(3000), 10, true);
    CreateOrderCommand command =
        new CreateOrderCommand("1000001", List.of(new CreateOrderItemCommand(1L, 2)));
    when(productRepository.findById(1L)).thenReturn(Optional.of(product));

    String expectedRequest =
        """
        {"postalCode": "1000001", "subtotal": 6000, "totalQuantity": 2}
        """;
    String errorBody = "{\"error\":\"unit08-shipping-unavailable\"}";
    wireMock.stubFor(
        post(urlEqualTo(CALCULATE_PATH))
            .willReturn(
                aResponse()
                    .withStatus(500)
                    .withHeader("Content-Type", "application/json")
                    .withBody(errorBody)));

    ShippingFeeServiceException actual =
        catchThrowableOfType(
            ShippingFeeServiceException.class, () -> orderService.createOrder(command));

    assertThat(actual).isNotNull().hasMessage("failed to calculate shipping fee");
    assertThat(actual.getCause()).isInstanceOf(HttpServerErrorException.class);
    HttpServerErrorException clientFailure = (HttpServerErrorException) actual.getCause();
    assertThat(clientFailure.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(clientFailure.getResponseBodyAsString()).isEqualTo(errorBody);

    // HTTP Stub の存在だけでは実送信を保証しない。記録された Request の内容と回数を別に確認する。
    wireMock.verify(
        1,
        postRequestedFor(urlEqualTo(CALCULATE_PATH))
            .withHeader("Content-Type", matching("(?i)application/json(?:\\s*;.*)?"))
            .withRequestBody(equalToJson(expectedRequest)));
    assertThat(wireMock.getAllServeEvents()).hasSize(1);

    // 送料取得は全書き込みより前にある。これは Mock の interaction の保証であり、DB の観測ではない。
    verify(productRepository).findById(1L);
    verify(productRepository, never()).decreaseStock(anyLong(), anyInt());
    verify(productRepository, never()).increaseStock(anyLong(), anyInt());
    verifyNoInteractions(orderRepository);
  }
}
