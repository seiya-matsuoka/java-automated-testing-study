package com.example.orderapi.service;

import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.INACTIVE_PRODUCT_ID;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.KEYBOARD_ID;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.MISSING_ID;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.MOUSE_ID;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.NOW;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.OUT_OF_STOCK_PRODUCT_ID;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.POSTAL_CODE;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.SHIPPING_FEE;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.databaseState;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.multiItemCommand;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.singleItemCommand;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.stockOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.orderapi.OrderManagementApplication;
import com.example.orderapi.client.ShippingFeeClient;
import com.example.orderapi.domain.Order;
import com.example.orderapi.domain.OrderStatus;
import com.example.orderapi.exception.DuplicateOrderItemException;
import com.example.orderapi.exception.InsufficientStockException;
import com.example.orderapi.exception.ProductInactiveException;
import com.example.orderapi.exception.ProductNotFoundException;
import com.example.orderapi.exception.ShippingFeeServiceException;
import com.example.orderapi.service.model.CreateOrderCommand;
import com.example.orderapi.service.model.CreateOrderItemCommand;
import com.example.orderapi.support.PostgresServiceIntegrationTestConfiguration;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 注文作成を、実 Service + JDBC Repository + PostgreSQL の境界で確認する Test。
 *
 * <p>Unit 03 の ArgumentCaptor が観測した「保存しようとした内容」を、この Unit では Commit 後の 実テーブルから確認する。Unit 05 の MVC
 * Binding / Validation / JSON は通さず、Command を入口にする。 ShippingFeeClient だけを Spring の Mock Bean
 * に置き換え、HTTP 契約の検証は Unit 08 に分ける。
 *
 * <p>@SpringBootTest は実 Application の DI / Auto Configuration を使う。NONE は実 Web Server を
 * 起動しない指定であり、Service や DB を Mock にする指定ではない。テスト自身には @Transactional を 付けず、Service の業務 Transaction
 * が正常に Commit した後の状態を読む。
 *
 * <p>Unit 03 は Spring / DB を起動せず、Unit 05 は Web Slice、Unit 06 は JDBC Slice を使う。 今回はより広い Context と
 * Container 起動、実 SQL、明示的 Cleanup のコストを伴う。 Surefire のクラス別 Time elapsed は初回の Context
 * 構築を含む場合があり、Context を再利用する 後続クラスと単純には比較できない。Maven の Total time はコンパイルなども含む別の計測範囲であり、 Unit
 * ごとの速度を断定する Assertion や、環境依存の時間上限は設けない。
 */
@SpringBootTest(
    classes = OrderManagementApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.sql.init.mode=never")
@Import(PostgresServiceIntegrationTestConfiguration.class)
@SqlConfig(transactionMode = SqlConfig.TransactionMode.ISOLATED)
@Sql({"/sql/unit07/cleanup.sql", "/sql/unit07/products.sql"})
@Sql(scripts = "/sql/unit07/cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
@DisplayName("Unit 07 - 注文作成と Commit 後の DB 状態")
class OrderServiceCreateIntegrationTest {

  @Autowired private OrderService orderService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;
  @Autowired private PostgreSQLContainer postgresContainer;

  // enforceOverride は、実 Client の Bean が存在する構成を置換していることを明示する。
  // 同じフィールド名・設定を Lifecycle Test と揃え、同じ Context を再利用できるようにする。
  @MockitoBean(enforceOverride = true)
  private ShippingFeeClient shippingFeeClient;

  /**
   * @Sql の Fixture は先に Commit 済みであり、Test Transaction は存在しない。
   *
   * <p>各 invocation で確認するため、Context を共有しても前回の注文・在庫を持ち越せない。 Test 終了時の自動 Rollback を Cleanup
   * として期待しない点が Unit 06 と異なる。
   */
  @BeforeEach
  void verifyCommittedFixtureWithoutTestTransaction() {
    assertThat(TestTransaction.isActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    var initial = databaseState(jdbcTemplate);
    assertThat(initial.products()).hasSize(4);
    assertThat(initial.orders()).isEmpty();
    assertThat(initial.orderItems()).isEmpty();
    assertThat(stockOf(jdbcTemplate, KEYBOARD_ID)).isEqualTo(10);
    assertThat(stockOf(jdbcTemplate, MOUSE_ID)).isEqualTo(20);
  }

  /** Proxy の存在と接続先を確認する。Proxy だけで業務 Rollback が正しいとは結論付けない。 */
  @Test
  @DisplayName("実 Service は Spring Proxy を通り、DataSource は専用 PostgreSQL へ接続する")
  void context_usesServiceProxyAndDedicatedPostgres() throws Exception {
    assertThat(AopUtils.isAopProxy(orderService)).isTrue();
    assertThat(postgresContainer.isRunning()).isTrue();
    try (Connection connection = dataSource.getConnection()) {
      assertThat(connection.getMetaData().getURL()).isEqualTo(postgresContainer.getJdbcUrl());
      assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
      assertThat(connection.getCatalog()).isEqualTo("unit07_service_test");
    }
    verifyNoInteractions(shippingFeeClient);
  }

  /**
   * 単一商品の正常系を 2 回実行し、毎回 10 → 8 と 0 → 1 注文が成立することを確認する。
   *
   * <p>再現性を作るのは @RepeatedTest 自体ではなく、前後の @Sql と各回の初期状態確認である。 実行順や採番値に依存せず、全体実行でも単独実行でも同じ仕様を確認する。
   */
  @RepeatedTest(value = 2, name = "独立した Fixture {currentRepetition}/{totalRepetitions}")
  @DisplayName("単一商品は注文・明細を Commit し、在庫を毎回同じ初期状態から減算する")
  void createOrder_singleItem_commitsAndIsRepeatable() {
    when(shippingFeeClient.calculateShippingFee(POSTAL_CODE, BigDecimal.valueOf(6000), 2))
        .thenReturn(SHIPPING_FEE);

    Order actual = orderService.createOrder(singleItemCommand(KEYBOARD_ID, 2));

    assertCommittedHeader(actual, 6000, 0, 6800);
    var state = databaseState(jdbcTemplate);
    assertThat(state.orderItems()).hasSize(1);
    assertSavedItem(
        state.orderItems().getFirst(), actual.getId(), KEYBOARD_ID, "Keyboard", 3000, 6000);
    assertThat(actual.getItems()).hasSize(1);
    assertThat(actual.getItems().getFirst().getOrderId()).isEqualTo(actual.getId());
    assertThat(stockOf(jdbcTemplate, KEYBOARD_ID)).isEqualTo(8);
    assertThat(stockOf(jdbcTemplate, MOUSE_ID)).isEqualTo(20);
    verify(shippingFeeClient).calculateShippingFee(POSTAL_CODE, BigDecimal.valueOf(6000), 2);
  }

  /** 2 明細・割引・送料を含む注文が、全テーブルへ一貫した値で保存される代表例。 */
  @Test
  @DisplayName("複数商品では注文と全明細を保存し、両商品の在庫を減算する")
  void createOrder_multipleItems_commitsHeaderItemsAndBothStocks() {
    when(shippingFeeClient.calculateShippingFee(POSTAL_CODE, BigDecimal.valueOf(11000), 4))
        .thenReturn(SHIPPING_FEE);

    Order actual = orderService.createOrder(multiItemCommand());

    // 6,000 + 5,000 = 11,000、10% 割引 1,100、送料 800、合計 10,700 は仕様から明示する。
    assertCommittedHeader(actual, 11000, 1100, 10700);
    var state = databaseState(jdbcTemplate);
    assertThat(state.orderItems()).hasSize(2);
    assertSavedItem(state.orderItems().get(0), actual.getId(), KEYBOARD_ID, "Keyboard", 3000, 6000);
    assertSavedItem(state.orderItems().get(1), actual.getId(), MOUSE_ID, "Mouse", 2500, 5000);
    assertThat(actual.getItems()).hasSize(2);
    assertThat(actual.getItems())
        .allSatisfy(item -> {
          assertThat(item.getId()).isPositive();
          assertThat(item.getOrderId()).isEqualTo(actual.getId());
        });
    assertThat(stockOf(jdbcTemplate, KEYBOARD_ID)).isEqualTo(8);
    assertThat(stockOf(jdbcTemplate, MOUSE_ID)).isEqualTo(18);
    verify(shippingFeeClient).calculateShippingFee(POSTAL_CODE, BigDecimal.valueOf(11000), 4);
  }

  /** 在庫が注文数と等しい境界は成功し、実 DB の在庫が負数でなく 0 になる。 */
  @Test
  @DisplayName("在庫と同数の注文は成功し、在庫 0 を Commit する")
  void createOrder_quantityEqualsStock_commitsZeroStock() {
    when(shippingFeeClient.calculateShippingFee(POSTAL_CODE, BigDecimal.valueOf(30000), 10))
        .thenReturn(SHIPPING_FEE);

    Order actual = orderService.createOrder(singleItemCommand(KEYBOARD_ID, 10));

    assertCommittedHeader(actual, 30000, 3000, 27800);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT quantity FROM order_items WHERE order_id = ?",
                Integer.class,
                actual.getId()))
        .isEqualTo(10);
    assertThat(stockOf(jdbcTemplate, KEYBOARD_ID)).isZero();
    assertThat(stockOf(jdbcTemplate, MOUSE_ID)).isEqualTo(20);
  }

  /**
   * 実 DB から得た商品条件と重複明細の業務チェックを確認する。
   *
   * <p>これらは DB 書き込み前の拒否であり、途中更新の Rollback を証明するケースではない。 SQL の最終状態と Client
   * 未呼び出しを組み合わせ、例外が出ただけで成功とはしない。
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidOrders")
  @DisplayName("事前の業務チェックに失敗した場合は DB と送料 Client に副作用を残さない")
  void createOrder_invalidBusinessCondition_leavesDatabaseUnchanged(
      String condition,
      CreateOrderCommand command,
      Class<? extends RuntimeException> exceptionType) {
    var before = databaseState(jdbcTemplate);

    assertThatThrownBy(() -> orderService.createOrder(command)).isInstanceOf(exceptionType);

    assertThat(databaseState(jdbcTemplate)).isEqualTo(before);
    verifyNoInteractions(shippingFeeClient);
  }

  static Stream<Arguments> invalidOrders() {
    return Stream.of(
        Arguments.of("商品不存在", singleItemCommand(MISSING_ID, 1), ProductNotFoundException.class),
        Arguments.of(
            "販売停止", singleItemCommand(INACTIVE_PRODUCT_ID, 1), ProductInactiveException.class),
        Arguments.of("在庫超過", singleItemCommand(KEYBOARD_ID, 11), InsufficientStockException.class),
        Arguments.of(
            "在庫 0",
            singleItemCommand(OUT_OF_STOCK_PRODUCT_ID, 1),
            InsufficientStockException.class),
        Arguments.of(
            "重複商品",
            new CreateOrderCommand(
                POSTAL_CODE,
                List.of(
                    new CreateOrderItemCommand(KEYBOARD_ID, 1),
                    new CreateOrderItemCommand(KEYBOARD_ID, 1))),
            DuplicateOrderItemException.class));
  }

  /** 送料照会は業務 Transaction 内だが DB 書き込み前にある。HTTP 障害そのものは再現しない。 */
  @Test
  @DisplayName("送料 Client の失敗は業務例外へ変換され、注文・明細・在庫を変更しない")
  void createOrder_shippingFailure_preservesDatabaseAndCause() {
    var before = databaseState(jdbcTemplate);
    RuntimeException clientFailure =
        new IllegalStateException("intentional shipping client failure");
    when(shippingFeeClient.calculateShippingFee(POSTAL_CODE, BigDecimal.valueOf(11000), 4))
        .thenThrow(clientFailure);

    assertThatThrownBy(() -> orderService.createOrder(multiItemCommand()))
        .isInstanceOf(ShippingFeeServiceException.class)
        .hasCause(clientFailure);

    assertThat(databaseState(jdbcTemplate)).isEqualTo(before);
    verify(shippingFeeClient).calculateShippingFee(POSTAL_CODE, BigDecimal.valueOf(11000), 4);
  }

  /** 戻り値と独立した SELECT の両方で、Commit 後の注文ヘッダを確認する。 */
  private void assertCommittedHeader(Order actual, long subtotal, long discount, long total) {
    assertThat(TestTransaction.isActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(actual.getId()).isPositive();
    assertThat(actual.getStatus()).isEqualTo(OrderStatus.CREATED);
    assertThat(actual.getShippingPostalCode()).isEqualTo(POSTAL_CODE);
    assertThat(actual.getCreatedAt()).isEqualTo(NOW);
    assertThat(actual.getCancelledAt()).isNull();
    assertThat(actual.getSubtotal()).isEqualByComparingTo(BigDecimal.valueOf(subtotal));
    assertThat(actual.getDiscountAmount()).isEqualByComparingTo(BigDecimal.valueOf(discount));
    assertThat(actual.getShippingFee()).isEqualByComparingTo(SHIPPING_FEE);
    assertThat(actual.getTotalAmount()).isEqualByComparingTo(BigDecimal.valueOf(total));

    var headers = databaseState(jdbcTemplate).orders();
    assertThat(headers).hasSize(1);
    var row = headers.getFirst();
    assertThat(row)
        .containsEntry("id", actual.getId())
        .containsEntry("status", "CREATED")
        .containsEntry("shipping_postal_code", POSTAL_CODE)
        .containsEntry("cancelled_at", null);
    assertThat((BigDecimal) row.get("subtotal")).isEqualByComparingTo(BigDecimal.valueOf(subtotal));
    assertThat((BigDecimal) row.get("discount_amount"))
        .isEqualByComparingTo(BigDecimal.valueOf(discount));
    assertThat((BigDecimal) row.get("shipping_fee")).isEqualByComparingTo(SHIPPING_FEE);
    assertThat((BigDecimal) row.get("total_amount"))
        .isEqualByComparingTo(BigDecimal.valueOf(total));
    assertThat(
            jdbcTemplate
                .queryForObject(
                    "SELECT created_at FROM orders WHERE id = ?",
                    OffsetDateTime.class,
                    actual.getId())
                .toInstant())
        .isEqualTo(NOW);
  }

  /** 明細 ID の値は固定せず、親注文・商品 Snapshot・数量・金額の保存を確認する。 */
  private void assertSavedItem(
      Map<String, Object> row,
      long orderId,
      long productId,
      String name,
      long price,
      long lineAmount) {
    assertThat((Long) row.get("id")).isPositive();
    assertThat(row)
        .containsEntry("order_id", orderId)
        .containsEntry("product_id", productId)
        .containsEntry("product_name", name)
        .containsEntry("quantity", 2);
    assertThat((BigDecimal) row.get("unit_price")).isEqualByComparingTo(BigDecimal.valueOf(price));
    assertThat((BigDecimal) row.get("line_amount"))
        .isEqualByComparingTo(BigDecimal.valueOf(lineAmount));
  }
}
