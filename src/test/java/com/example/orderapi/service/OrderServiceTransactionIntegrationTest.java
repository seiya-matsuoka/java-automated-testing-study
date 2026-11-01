package com.example.orderapi.service;

import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.CONFIRMED_ORDER_ID;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.KEYBOARD_ID;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.MOUSE_ID;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.NOW;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.POSTAL_CODE;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.SHIPPING_FEE;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.databaseState;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.multiItemCommand;
import static com.example.orderapi.support.OrderServiceIntegrationTestFixture.stockOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.orderapi.OrderManagementApplication;
import com.example.orderapi.client.ShippingFeeClient;
import com.example.orderapi.repository.jdbc.JdbcProductRepository;
import com.example.orderapi.support.OrderServiceIntegrationTestFixture.DatabaseState;
import com.example.orderapi.support.PostgresServiceIntegrationTestConfiguration;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.test.context.jdbc.SqlMergeMode;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 複数の実 DB 更新が途中失敗したとき、Service の業務 Transaction が先行更新も戻すことを確認する。
 *
 * <p>Unit 06 の JdbcTransactionTest は Test Transaction 自体の Rollback を確認した。 ここでは Test
 * に @Transactional を付けず、Fixture は先に Commit する。Spring から取得した OrderService の Proxy に開始・終了を任せ、例外が Test
 * へ戻った直後、Cleanup より前に DB を読む。 Test 側の TransactionTemplate、TestTransaction.end()、手動の補償 UPDATE
 * は使用しない。
 *
 * <p>Repository Spy は失敗させる 1 操作に限定し、それ以外の取得・登録・更新は本物を実行する。 注文
 * Repository、JDBC、PostgreSQL、Transaction Manager は置き換えない。失敗直前の SELECT は 業務 Transaction に参加し、未
 * Commit の先行更新を観測する。失敗後の SELECT はその外で行う。
 *
 * <p>制御するのは Repository 境界の例外・更新件数である。DB Server 停止、ネットワーク断、実際の 同時注文は再現していない。SQL の更新を verify
 * しただけでなく、途中状態と最終状態を根拠にする。
 */
@SpringBootTest(
    classes = OrderManagementApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.sql.init.mode=never")
@Import(PostgresServiceIntegrationTestConfiguration.class)
@SqlConfig(transactionMode = SqlConfig.TransactionMode.ISOLATED)
@SqlMergeMode(SqlMergeMode.MergeMode.MERGE)
@Sql({"/sql/unit07/cleanup.sql", "/sql/unit07/products.sql"})
@Sql(scripts = "/sql/unit07/cleanup.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
@DisplayName("Unit 07 - Service の業務 Transaction と Rollback")
class OrderServiceTransactionIntegrationTest {

  @Autowired private OrderService orderService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoBean(enforceOverride = true)
  private ShippingFeeClient shippingFeeClient;

  // 既存 Bean を Spy で包む。正常系クラスには追加せず、失敗注入の目的をこのクラスへ閉じ込める。
  // Spring Test の既定の AFTER reset により、各 Test 後に stubbing と呼び出し記録が除去される。
  @MockitoSpyBean private JdbcProductRepository productRepository;

  /** Test 自身の Transaction が業務処理を囲んでいないことを、各ケースで確認する。 */
  @BeforeEach
  void verifyNoTestManagedTransaction() {
    assertThat(TestTransaction.isActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
  }

  /**
   * 2 商品目の減算で RuntimeException が伝播し、先行する 3 種類の書き込みが戻ることを確認する。
   *
   * <p>最初から不正な商品を渡すと事前チェックで終了してしまうため、両商品とも注文可能とする。 doAnswer
   * は設定時に実更新を呼ばず、実際に後続商品の減算へ到達したときだけ失敗を注入する。
   */
  @Test
  @DisplayName("後続商品の減算失敗は注文・全明細・先行商品の減算を Rollback する")
  void createOrder_secondStockUpdateFails_rollsBackAllEarlierWrites() {
    var before = databaseState(jdbcTemplate);
    assertThat(before.orders()).isEmpty();
    assertThat(before.orderItems()).isEmpty();
    assertThat(stockOf(jdbcTemplate, KEYBOARD_ID)).isEqualTo(10);
    assertThat(stockOf(jdbcTemplate, MOUSE_ID)).isEqualTo(20);
    when(shippingFeeClient.calculateShippingFee(POSTAL_CODE, BigDecimal.valueOf(11000), 4))
        .thenReturn(SHIPPING_FEE);

    var failure = new DataAccessResourceFailureException("intentional second stock update failure");
    List<FailureObservation> observations = new ArrayList<>();
    doAnswer(
            invocation -> {
              // 同じスレッド・同じ DataSource の JdbcTemplate で、失敗前の未 Commit 状態を保存する。
              observations.add(observeBeforeFailure());
              throw failure;
            })
        .when(productRepository)
        .decreaseStock(MOUSE_ID, 2);

    // 指定した例外そのものを確認し、観測用 SQL や Assertion の別例外を成功と取り違えない。
    assertThatThrownBy(() -> orderService.createOrder(multiItemCommand())).isSameAs(failure);

    assertThat(observations)
        .singleElement()
        .satisfies(observation -> {
          assertThat(observation.businessTransactionActive()).isTrue();
          assertThat(observation.testTransactionActive()).isFalse();
          assertThat(observation.database().orders()).hasSize(1);
          assertThat(observation.database().orderItems()).hasSize(2);
          assertThat(observation.keyboardStock()).isEqualTo(8);
          assertThat(observation.mouseStock()).isEqualTo(20);
          var header = observation.database().orders().getFirst();
          assertThat(header).containsEntry("status", "CREATED").containsEntry("cancelled_at", null);
          assertThat(observation.database().orderItems())
              .allSatisfy(row -> assertThat(row).containsEntry("order_id", header.get("id")));
        });

    // この時点では AFTER_TEST_METHOD の DELETE はまだ実行されていない。
    // ヘッダだけ残る不具合も INNER JOIN の検索で隠さず、3 テーブルの直接比較で検出する。
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(databaseState(jdbcTemplate)).isEqualTo(before);
    verify(productRepository).decreaseStock(KEYBOARD_ID, 2);
    verify(productRepository).decreaseStock(MOUSE_ID, 2);
    verify(shippingFeeClient).calculateShippingFee(POSTAL_CODE, BigDecimal.valueOf(11000), 4);
  }

  /**
   * SQL 例外以外でも、Service が更新件数 0 を失敗にすれば一連の変更が Rollback される。
   *
   * <p>Method の @Sql は Class の前後 Script を置き換えないよう MERGE する。 orders.sql の既存注文・明細・減算済み在庫は別
   * Transaction で Commit 済みなので、 業務 Rollback 後も消えてはならない。戻るべき状態は「空 DB」ではなく処理前の既存データである。
   */
  @Test
  @Sql("/sql/unit07/orders.sql")
  @DisplayName("後続商品の復元が 0 件なら状態・日時・先行商品の復元を Rollback する")
  void cancel_secondStockRestoreReturnsZero_rollsBackStatusTimestampAndFirstRestore() {
    var before = databaseState(jdbcTemplate);
    assertThat(before.orders()).hasSize(4);
    assertThat(before.orderItems()).hasSize(8);
    assertThat(stockOf(jdbcTemplate, KEYBOARD_ID)).isEqualTo(8);
    assertThat(stockOf(jdbcTemplate, MOUSE_ID)).isEqualTo(18);
    assertThat(before.orders())
        .filteredOn(row -> row.get("id").equals(CONFIRMED_ORDER_ID))
        .singleElement()
        .satisfies(row ->
            assertThat(row)
                .containsEntry("status", "CONFIRMED")
                .containsEntry("cancelled_at", null));

    List<FailureObservation> observations = new ArrayList<>();
    doAnswer(
            invocation -> {
              observations.add(observeBeforeFailure());
              // この 1 操作だけ実 SQL を実行せず、Service の更新件数検査に失敗を検出させる。
              return 0;
            })
        .when(productRepository)
        .increaseStock(MOUSE_ID, 2);

    assertThatThrownBy(() -> orderService.cancel(CONFIRMED_ORDER_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("product stock restore must affect exactly one row: 0");

    assertThat(observations)
        .singleElement()
        .satisfies(observation -> {
          assertThat(observation.businessTransactionActive()).isTrue();
          assertThat(observation.testTransactionActive()).isFalse();
          assertThat(observation.keyboardStock()).isEqualTo(10);
          assertThat(observation.mouseStock()).isEqualTo(18);
          assertThat(observation.database().orderItems()).isEqualTo(before.orderItems());
          assertThat(observation.database().orders())
              .filteredOn(row -> row.get("id").equals(CONFIRMED_ORDER_ID))
              .singleElement()
              .satisfies(row ->
                  assertThat(row)
                      .containsEntry("status", "CANCELLED")
                      .containsEntry("cancelled_at", Timestamp.from(NOW)));
        });

    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(databaseState(jdbcTemplate)).isEqualTo(before);
    verify(productRepository).increaseStock(KEYBOARD_ID, 2);
    verify(productRepository).increaseStock(MOUSE_ID, 2);
    verifyNoInteractions(shippingFeeClient);
  }

  /** 観測は SELECT だけとし、Rollback や在庫の手動復元を補助処理へ隠さない。 */
  private FailureObservation observeBeforeFailure() {
    return new FailureObservation(
        TransactionSynchronizationManager.isActualTransactionActive(),
        TestTransaction.isActive(),
        databaseState(jdbcTemplate),
        stockOf(jdbcTemplate, KEYBOARD_ID),
        stockOf(jdbcTemplate, MOUSE_ID));
  }

  /**
   * Test ローカルの List に保存する失敗直前の観測値。
   *
   * <p>Callback 内では Assertion を行わず、Service が返した例外を確認してから別々に検証する。 mutable な観測値を static field や他の
   * Test と共有しない。
   */
  private record FailureObservation(
      boolean businessTransactionActive,
      boolean testTransactionActive,
      DatabaseState database,
      int keyboardStock,
      int mouseStock) {}
}
