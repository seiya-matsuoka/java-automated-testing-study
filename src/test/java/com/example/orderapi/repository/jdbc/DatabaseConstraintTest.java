package com.example.orderapi.repository.jdbc;

import static com.example.orderapi.support.JdbcRepositoryTestFixture.CREATED_ORDER_ID;
import static com.example.orderapi.support.JdbcRepositoryTestFixture.KEYBOARD_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.orderapi.support.PostgresJdbcTestConfiguration;
import java.sql.SQLException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/**
 * Production の schema.sql が定める制約を、実 PostgreSQL への SQL で確認する Test。
 *
 * <p>SUT は DB Schema と制約となる。Repository / Domain の入力検証を経由すると、DB に到達する前に 不正値が拒否される場合があるため、この Class
 * では意図して JdbcTemplate から直接 SQL を実行する。 Java の入力検証が成功したことと、DB constraint が有効であることを混同しない。
 *
 * <p>制約違反後の PostgreSQL Transaction は失敗状態となり、Rollback するまで追加 SQL を実行できない。 各 invocation では違反を 1
 * 件だけ発生させ、例外の情報を確認した後は Spring の Test Rollback に任せる。 同じ Test 内で続けて違反を起こしたり、DELETE で後始末しようとしたりしない。
 */
@JdbcTest(properties = "spring.sql.init.mode=never")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresJdbcTestConfiguration.class)
@Sql({"/sql/unit06/products.sql", "/sql/unit06/orders.sql"})
@DisplayName("Unit 06 - PostgreSQL の DB constraint")
class DatabaseConstraintTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  /**
   * 各制約が不正な書き込みを拒否し、Spring の共通例外へ変換されることを確認する。
   *
   * <p>DataIntegrityViolationException だけでは意図した理由で失敗したかを区別できない。 原因となる JDBC SQLException の SQLSTATE
   * も比較し、翻訳・バージョンで変わり得る message 全文は固定しない。 DB に再アクセスすることなく、既に受け取った例外の情報だけで確認する。
   */
  @ParameterizedTest(name = "{0}: SQLSTATE={1}")
  @MethodSource("invalidDatabaseWrites")
  void invalidWrite_isRejectedByExpectedDatabaseConstraint(
      String caseName, String expectedSqlState, String sql) {
    assertThatThrownBy(() -> jdbcTemplate.update(sql))
        .isInstanceOf(DataIntegrityViolationException.class)
        .satisfies(exception -> {
          Throwable cause = ((DataIntegrityViolationException) exception).getMostSpecificCause();
          assertThat(cause).isInstanceOf(SQLException.class);
          assertThat(((SQLException) cause).getSQLState()).isEqualTo(expectedSqlState);
        });
  }

  /**
   * PK / UNIQUE / FK / NOT NULL / CHECK の違いを、対象の列・値と一緒に並べる。
   *
   * <p>各 SQL は検証対象以外の制約を満たすように作る。 例えば不正郵便番号は 7 文字のままとし、VARCHAR の長さ制限ではなく正規表現 CHECK へ到達させる。 23505
   * は一意性、23503 は参照整合性、23502 は必須値、23514 は CHECK の違反を示す。
   */
  static Stream<Arguments> invalidDatabaseWrites() {
    return Stream.of(
        Arguments.of(
            "商品価格 0 の CHECK",
            "23514",
            """
            INSERT INTO products (name, price, stock_quantity, active)
            VALUES ('Invalid Price', 0, 10, TRUE)
            """),
        Arguments.of(
            "負数在庫の CHECK", "23514", "UPDATE products SET stock_quantity = -1 WHERE id = 1001"),
        Arguments.of(
            "未定義の注文状態の CHECK", "23514", "UPDATE orders SET status = 'UNKNOWN' WHERE id = 10004"),
        Arguments.of(
            "数字以外を含む郵便番号の CHECK",
            "23514",
            "UPDATE orders SET shipping_postal_code = 'ABC0001' WHERE id = 10004"),
        Arguments.of(
            "明細数量 0 の CHECK", "23514", "UPDATE order_items SET quantity = 0 WHERE id = 20003"),
        Arguments.of(
            "明細数量 100 の CHECK", "23514", "UPDATE order_items SET quantity = 100 WHERE id = 20003"),
        Arguments.of(
            "商品 ID の PRIMARY KEY",
            "23505",
            """
            INSERT INTO products (id, name, price, stock_quantity, active)
            VALUES (1001, 'Duplicate ID', 100, 1, TRUE)
            """),
        Arguments.of(
            "同一注文内の商品 UNIQUE",
            "23505",
            """
            INSERT INTO order_items
                (order_id, product_id, product_name, unit_price, quantity, line_amount)
            VALUES (10003, 1001, 'Keyboard', 3000, 1, 3000)
            """),
        Arguments.of(
            "存在しない注文への FOREIGN KEY",
            "23503",
            """
            INSERT INTO order_items
                (order_id, product_id, product_name, unit_price, quantity, line_amount)
            VALUES (999999, 1001, 'Keyboard', 3000, 1, 3000)
            """),
        Arguments.of(
            "存在しない商品への FOREIGN KEY",
            "23503",
            """
            INSERT INTO order_items
                (order_id, product_id, product_name, unit_price, quantity, line_amount)
            VALUES (10004, 999999, 'Missing Product', 100, 1, 100)
            """),
        Arguments.of(
            "商品名の NOT NULL",
            "23502",
            """
            INSERT INTO products (name, price, stock_quantity, active)
            VALUES (NULL, 100, 1, TRUE)
            """));
  }

  /**
   * DB 側の数量範囲も両端を含むことを確認する。
   *
   * <p>Unit 02 の Domain 数量テストと同じ値でも、ここでは Java の検証を迂回して DB の CHECK を確かめる。 元の注文にない商品を追加し、UNIQUE
   * 違反が数量境界の検証へ混ざらないようにする。
   */
  @ParameterizedTest(name = "DB の数量境界 {0} は有効")
  @ValueSource(ints = {1, 99})
  void orderItemQuantity_acceptsBothDatabaseBoundaries(int quantity) {
    int updatedRows =
        jdbcTemplate.update(
            """
            INSERT INTO order_items
                (order_id, product_id, product_name, unit_price, quantity, line_amount)
            VALUES (?, ?, 'Keyboard', 3000, ?, ?)
            """,
            CREATED_ORDER_ID,
            KEYBOARD_ID,
            quantity,
            3_000 * quantity);

    assertThat(updatedRows).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT quantity FROM order_items WHERE order_id = ? AND product_id = ?",
                Integer.class,
                CREATED_ORDER_ID,
                KEYBOARD_ID))
        .isEqualTo(quantity);
  }
}
