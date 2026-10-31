package com.example.orderapi.repository.jdbc;

import static com.example.orderapi.support.JdbcRepositoryTestFixture.INACTIVE_PRODUCT_ID;
import static com.example.orderapi.support.JdbcRepositoryTestFixture.KEYBOARD_ID;
import static com.example.orderapi.support.JdbcRepositoryTestFixture.MOUSE_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.orderapi.support.PostgresJdbcTestConfiguration;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.transaction.TestTransaction;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 接続先、Test Transaction、Rollback による Cleanup と Fixture の独立性を確認する Test。
 *
 * <p>Unit 06 で確認するのは、テスト自身が管理する Transaction の範囲と終了後の DB 状態となる。 Service
 * の @Transactional、Proxy、業務処理途中の失敗による Rollback は実行していない。 それらは Unit 07 で Service + Repository +
 * PostgreSQL の境界を広げて確認する。
 *
 * <p>Schema は Container と同じ期間だけ保持する一方、Fixture は @Sql で各 Test に投入し、Rollback で除去する。 データの Cleanup
 * と、Context 終了時に行う Container の停止・破棄を別の役割として読む。
 */
@JdbcTest(properties = "spring.sql.init.mode=never")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresJdbcTestConfiguration.class)
@Sql("/sql/unit06/products.sql")
@DisplayName("Unit 06 - Test Transaction と DB Test Isolation")
class JdbcTransactionTest {

  @Autowired private JdbcProductRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;
  @Autowired private PostgreSQLContainer postgresContainer;

  /**
   * Test の前提は他の Test の実行順序でなく、新しく投入した Fixture だけで成立する。
   *
   * <p>@BeforeEach は Test Transaction 内で実行され、@Sql が準備したデータを観測できる。 別 Class
   * が登録した注文や変更した在庫が残っていないことも、同じ Context の再利用時に確認する。
   */
  @BeforeEach
  void verifyFreshFixtureInsideTestTransaction() {
    assertThat(TestTransaction.isActive()).isTrue();
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM products", Long.class))
        .isEqualTo(3L);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Long.class)).isZero();
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_items", Long.class))
        .isZero();
    assertThat(stockOfKeyboard()).isEqualTo(10);
  }

  /**
   * DataSource が通常確認用 DB でなく、実際に起動した Container の接続先を使うことを確認する。
   *
   * <p>Connection は try-with-resources で pool へ返す。接続情報の password などは出力しない。 Metadata
   * だけを確認するため、この直接取得した Connection では Fixture の読み書きを行わない。
   */
  @Test
  @DisplayName("DataSource はテスト専用 PostgreSQL Container に接続する")
  void dataSource_connectsToTestContainerInsteadOfApplicationDatabase() throws Exception {
    assertThat(postgresContainer.isRunning()).isTrue();
    try (Connection connection = dataSource.getConnection()) {
      assertThat(connection.getMetaData().getURL()).isEqualTo(postgresContainer.getJdbcUrl());
      assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
      assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(18);
      assertThat(connection.getCatalog()).isEqualTo("unit06_repository_test");
    }

    // 通常起動用 data.sql の商品 ID 1～6 が混入せず、専用の SQL Fixture だけが存在する。
    assertThat(jdbcTemplate.queryForList("SELECT id FROM products ORDER BY id", Long.class))
        .containsExactly(KEYBOARD_ID, MOUSE_ID, INACTIVE_PRODUCT_ID);
  }

  /**
   * 更新を観測した後、同じ Test の中で Rollback を完了させ、DB 最終状態を直接確認する。
   *
   * <p>「次の Test が元の値を見たから Rollback された」とは判断しない。 TestTransaction.end() で現在の Test Transaction
   * を終了し、その外側の SELECT で消去を確認する。 @Sql の初期データ自体も同じ Transaction に参加するため、更新だけでなく商品行もすべて消える。
   */
  @Test
  @DisplayName("Test Transaction の Rollback は更新と Fixture を除去する")
  void rollback_removesChangesAndTransactionalFixture() {
    assertThat(repository.decreaseStock(KEYBOARD_ID, 2)).isEqualTo(1);
    assertThat(stockOfKeyboard()).isEqualTo(8);

    TestTransaction.flagForRollback();
    TestTransaction.end();

    assertThat(TestTransaction.isActive()).isFalse();
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM products", Long.class)).isZero();
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Long.class)).isZero();
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_items", Long.class))
        .isZero();
  }

  /**
   * 各 invocation が同じ初期データから始まり、前回の減算結果を引き継がないことを確認する。
   *
   * <p>再現性を作るのは @RepeatedTest そのものではなく、@Sql と Test ごとの Rollback となる。 一定時間の sleep、実行順の固定、在庫の手動 reset
   * を使わず、毎回 10 → 8 が成立する構成を読む。
   */
  @RepeatedTest(value = 2, name = "独立した DB Fixture {currentRepetition}/{totalRepetitions}")
  @DisplayName("繰り返し実行しても毎回同じ在庫から始まる")
  void repeatedExecution_startsWithIndependentDatabaseFixture() {
    assertThat(repository.decreaseStock(KEYBOARD_ID, 2)).isEqualTo(1);
    assertThat(stockOfKeyboard()).isEqualTo(8);
  }

  /** Repository の読み取りに依存せず、同じ Test Transaction の DB 在庫を直接観測する。 */
  private int stockOfKeyboard() {
    return jdbcTemplate.queryForObject(
        "SELECT stock_quantity FROM products WHERE id = ?", Integer.class, KEYBOARD_ID);
  }
}
