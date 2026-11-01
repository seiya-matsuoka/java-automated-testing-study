package com.example.orderapi.service;

import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.CANCELLED_ORDER_ID;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.CONFIRMED_ORDER_ID;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.CREATED_ORDER_ID;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.KEYBOARD_ID;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.MISSING_ID;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.MOUSE_ID;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.NOW;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.SEEDED_CANCELLED_AT;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.SEEDED_CREATED_AT;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.SHIPPED_ORDER_ID;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.databaseState;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.stockOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.orderapi.OrderManagementApplication;
import com.example.orderapi.client.ShippingFeeClient;
import com.example.orderapi.domain.Order;
import com.example.orderapi.domain.OrderStatus;
import com.example.orderapi.exception.InvalidOrderStatusTransitionException;
import com.example.orderapi.exception.OrderNotFoundException;
import com.example.orderapi.support.OrderServiceIntegrationTestFixture.DatabaseState;
import com.example.orderapi.support.PostgresServiceIntegrationTestConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 実 DB に保存済みの注文へ、確定・発送・キャンセルを適用する Integration Test。
 *
 * <p>Domain の状態遷移表だけでなく、Service が実 Repository で状態を更新し、全明細の在庫を復元し、 更新後の注文を取得する連携を確認する。Fixture は SQL
 * で Commit 済みとし、別 Test の実行結果や Service の注文作成を準備処理に使わない。HTTP や Response DTO はこの境界に含めない。
 *
 * <p>Test に @Transactional は付けない。成功結果は Service の Commit 後に直接 SELECT し、 拒否結果は Cleanup
 * 前の全テーブル比較で確認する。前後の @Sql は独立した Transaction で実行する。
 */
@SpringBootTest(
    classes = OrderManagementApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.sql.init.mode=never")
@Import(PostgresServiceIntegrationTestConfiguration.class)
@SqlConfig(transactionMode = SqlConfig.TransactionMode.ISOLATED)
@Sql({"/sql/unit07/cleanup.sql", "/sql/unit07/products.sql", "/sql/unit07/orders.sql"})
@Sql(scripts = "/sql/unit07/cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
@DisplayName("Unit 07 - 注文状態の更新と在庫復元")
class OrderServiceLifecycleIntegrationTest {

  @Autowired private OrderService orderService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean(enforceOverride = true)
  private ShippingFeeClient shippingFeeClient;

  /** 同じ Context を使っても、各 Test の開始状態は専用 SQL の値だけで決まる。 */
  @BeforeEach
  void verifyCommittedLifecycleFixture() {
    assertThat(TestTransaction.isActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    var initial = databaseState(jdbcTemplate);
    assertThat(initial.orders()).hasSize(4);
    assertThat(initial.orderItems()).hasSize(8);
    assertThat(stockOf(jdbcTemplate, KEYBOARD_ID)).isEqualTo(8);
    assertThat(stockOf(jdbcTemplate, MOUSE_ID)).isEqualTo(18);
    // キャンセル済みの代表行は過去の日時を持ち、再操作で NOW に上書きされないことを後で比較する。
    assertThat(initial.orders())
        .filteredOn(row -> row.get("id").equals(CANCELLED_ORDER_ID))
        .singleElement()
        .satisfies(row ->
            assertThat(row)
                .containsEntry("status", "CANCELLED")
                .containsEntry("cancelled_at", Timestamp.from(SEEDED_CANCELLED_AT)));
  }

  /** 送料の再照会は状態遷移に不要であり、意図しない外部境界へのアクセスも検出する。 */
  @AfterEach
  void verifyNoShippingCallAndNoRemainingTransaction() {
    verifyNoInteractions(shippingFeeClient);
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
  }

  /** CREATED → CONFIRMED は状態だけを変更し、作成時の金額・明細・在庫を維持する。 */
  @Test
  @DisplayName("注文確定は状態を Commit し、明細・金額・在庫を変更しない")
  void confirm_createdOrder_commitsStatusWithoutChangingInventory() {
    var before = databaseState(jdbcTemplate);

    Order actual = orderService.confirm(CREATED_ORDER_ID);

    assertReturnedOrder(actual, CREATED_ORDER_ID, OrderStatus.CONFIRMED, null);
    assertHeaderUpdateAndPreservedItems(before, CREATED_ORDER_ID, OrderStatus.CONFIRMED, null);
    assertThat(databaseState(jdbcTemplate).products()).isEqualTo(before.products());
  }

  /** CONFIRMED → SHIPPED でも在庫を再減算しない。注文作成時の減算と責務を分ける。 */
  @Test
  @DisplayName("注文発送は状態を Commit し、在庫を再減算しない")
  void ship_confirmedOrder_commitsStatusWithoutDecreasingStockAgain() {
    var before = databaseState(jdbcTemplate);

    Order actual = orderService.ship(CONFIRMED_ORDER_ID);

    assertReturnedOrder(actual, CONFIRMED_ORDER_ID, OrderStatus.SHIPPED, null);
    assertHeaderUpdateAndPreservedItems(before, CONFIRMED_ORDER_ID, OrderStatus.SHIPPED, null);
    assertThat(databaseState(jdbcTemplate).products()).isEqualTo(before.products());
  }

  /**
   * キャンセル可能な 2 状態を同じ DB 条件で比較する。
   *
   * <p>各 invocation は新しい Fixture の 8 / 18 から始める。対象注文の 2 個ずつを復元し、 状態・キャンセル日時と両商品の在庫が一緒に Commit
   * されたことを確認する。
   */
  @ParameterizedTest(name = "{0} → CANCELLED")
  @MethodSource("cancellableOrders")
  @DisplayName("作成済み・確定済み注文のキャンセルは全明細分の在庫を復元する")
  void cancel_allowedStatus_commitsTimestampAndRestoresAllStocks(
      OrderStatus initialStatus, long orderId) {
    var before = databaseState(jdbcTemplate);
    assertThat(before.orders())
        .filteredOn(row -> row.get("id").equals(orderId))
        .singleElement()
        .satisfies(row -> assertThat(row).containsEntry("status", initialStatus.name()));

    Order actual = orderService.cancel(orderId);

    assertReturnedOrder(actual, orderId, OrderStatus.CANCELLED, NOW);
    assertHeaderUpdateAndPreservedItems(before, orderId, OrderStatus.CANCELLED, NOW);
    assertRestoredProducts(before);
  }

  static Stream<Arguments> cancellableOrders() {
    return Stream.of(
        Arguments.of(OrderStatus.CREATED, CREATED_ORDER_ID),
        Arguments.of(OrderStatus.CONFIRMED, CONFIRMED_ORDER_ID));
  }

  /**
   * 同じ注文への連続操作を 1 Test で観測し、二重キャンセルで在庫を増やし続けないことを確認する。
   *
   * <p>2 回目の拒否後は初期 Fixture ではなく、1 回目の正常 Commit 後の状態と比較する。 例外時に正当な先行 Commit まで取り消す、という誤った期待を持たせない。
   */
  @Test
  @DisplayName("2 回目のキャンセルは拒否され、在庫とキャンセル日時を再更新しない")
  void cancel_twice_preservesFirstCommittedCancellation() {
    Order first = orderService.cancel(CREATED_ORDER_ID);
    assertReturnedOrder(first, CREATED_ORDER_ID, OrderStatus.CANCELLED, NOW);
    assertThat(stockOf(jdbcTemplate, KEYBOARD_ID)).isEqualTo(10);
    assertThat(stockOf(jdbcTemplate, MOUSE_ID)).isEqualTo(20);
    var afterFirstCommit = databaseState(jdbcTemplate);

    assertThatThrownBy(() -> orderService.cancel(CREATED_ORDER_ID))
        .isInstanceOf(InvalidOrderStatusTransitionException.class);

    assertThat(databaseState(jdbcTemplate)).isEqualTo(afterFirstCommit);
  }

  /** 状態遷移の全組み合わせは既存 Domain Test に任せ、実 DB との連携では代表的な拒否を確認する。 */
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidTransitions")
  @DisplayName("許可されない状態更新は注文・明細・在庫を一切変更しない")
  void transition_invalidStatus_preservesAllTables(
      String condition, long orderId, OrderStatus targetStatus) {
    var before = databaseState(jdbcTemplate);

    assertThatThrownBy(
            () -> {
              // 目標状態に対応する実 Service の公開ユースケースを呼び、Domain の判定だけで済ませない。
              switch (targetStatus) {
                case CONFIRMED -> orderService.confirm(orderId);
                case SHIPPED -> orderService.ship(orderId);
                case CANCELLED -> orderService.cancel(orderId);
                default -> throw new IllegalArgumentException("unsupported test operation");
              }
            })
        .isInstanceOf(InvalidOrderStatusTransitionException.class);

    assertThat(databaseState(jdbcTemplate)).isEqualTo(before);
  }

  static Stream<Arguments> invalidTransitions() {
    return Stream.of(
        Arguments.of("確定済みを再確定", CONFIRMED_ORDER_ID, OrderStatus.CONFIRMED),
        Arguments.of("作成済みを直接発送", CREATED_ORDER_ID, OrderStatus.SHIPPED),
        Arguments.of("発送済みをキャンセル", SHIPPED_ORDER_ID, OrderStatus.CANCELLED),
        Arguments.of("キャンセル済みを再キャンセル", CANCELLED_ORDER_ID, OrderStatus.CANCELLED));
  }

  /** 実 Repository の空結果が Service の業務例外へ変換され、既存行を巻き込まないことを確認する。 */
  @Test
  @DisplayName("不存在注文のキャンセルは OrderNotFoundException となり、既存データを維持する")
  void cancel_missingOrder_preservesExistingOrdersAndProducts() {
    var before = databaseState(jdbcTemplate);

    assertThatThrownBy(() -> orderService.cancel(MISSING_ID))
        .isInstanceOf(OrderNotFoundException.class);

    assertThat(databaseState(jdbcTemplate)).isEqualTo(before);
  }

  /** DB から再取得した戻り値でも、状態・日時と既存の明細・金額の保持を確認する。 */
  private void assertReturnedOrder(
      Order actual, long orderId, OrderStatus status, Instant cancelledAt) {
    assertThat(actual.getId()).isEqualTo(orderId);
    assertThat(actual.getStatus()).isEqualTo(status);
    assertThat(actual.getCreatedAt()).isEqualTo(SEEDED_CREATED_AT);
    assertThat(actual.getCancelledAt()).isEqualTo(cancelledAt);
    assertThat(actual.getItems()).hasSize(2);
    assertThat(actual.getTotalAmount()).isEqualByComparingTo("10700");
  }

  /**
   * 対象ヘッダの状態・キャンセル日時だけを期待値へ反映し、全ヘッダと全明細を比較する。
   *
   * <p>これにより、金額や作成日時の誤更新、別注文への影響、キャンセルによる明細削除も検出する。 JdbcTemplate の行 Map の日時は JDBC の Timestamp
   * なので、期待する Instant を同じ型へ変換する。
   */
  private void assertHeaderUpdateAndPreservedItems(
      DatabaseState before, long orderId, OrderStatus status, Instant cancelledAt) {
    var expectedOrders =
        before.orders().stream()
            .map(row -> {
              Map<String, Object> expected = new LinkedHashMap<>(row);
              if (row.get("id").equals(orderId)) {
                expected.put("status", status.name());
                expected.put(
                    "cancelled_at", cancelledAt == null ? null : Timestamp.from(cancelledAt));
              }
              return expected;
            })
            .toList();

    var after = databaseState(jdbcTemplate);
    assertThat(after.orders()).isEqualTo(expectedOrders);
    assertThat(after.orderItems()).isEqualTo(before.orderItems());
  }

  /** 対象商品の在庫だけが 10 / 20 に戻り、商品属性と他の商品は保持されることを確認する。 */
  private void assertRestoredProducts(DatabaseState before) {
    var expectedProducts =
        before.products().stream()
            .map(row -> {
              Map<String, Object> expected = new LinkedHashMap<>(row);
              if (row.get("id").equals(KEYBOARD_ID)) {
                expected.put("stock_quantity", 10);
              } else if (row.get("id").equals(MOUSE_ID)) {
                expected.put("stock_quantity", 20);
              }
              return expected;
            })
            .toList();

    assertThat(databaseState(jdbcTemplate).products()).isEqualTo(expectedProducts);
  }
}
