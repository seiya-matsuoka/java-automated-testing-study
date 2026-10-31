package com.example.orderapi.repository.jdbc;

import static com.example.orderapi.support.JdbcRepositoryTestFixture.INACTIVE_PRODUCT_ID;
import static com.example.orderapi.support.JdbcRepositoryTestFixture.KEYBOARD_ID;
import static com.example.orderapi.support.JdbcRepositoryTestFixture.MISSING_ID;
import static com.example.orderapi.support.JdbcRepositoryTestFixture.MOUSE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.orderapi.domain.Product;
import com.example.orderapi.support.PostgresJdbcTestConfiguration;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/**
 * JdbcProductRepository の明示 SQL と Row Mapping を、実 PostgreSQL に通す Integration Test。
 *
 * <p>Unit 03 の Repository Mock / Fake と異なり、SUT は JDBC 実装自身となる。 NamedParameterJdbcTemplate、JDBC
 * Driver、DataSource、PostgreSQL は実物を使用する。 Controller / Service / HTTP はこの Test Boundary に含めない。
 *
 * <p>@JdbcTest が JDBC 用の Spring Context と Test Transaction を構成する。 @Sql の Fixture と各 Test の UPDATE
 * は同じ Transaction に参加し、終了時に Rollback される。 通常起動用 data.sql は読み込まず、テストの前提を products.sql に明示する。
 */
@JdbcTest(properties = "spring.sql.init.mode=never")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresJdbcTestConfiguration.class)
@Sql("/sql/unit06/products.sql")
@DisplayName("Unit 06 - JdbcProductRepository と PostgreSQL")
class JdbcProductRepositoryTest {

  @Autowired private JdbcProductRepository repository;

  // SUT の検索を使わず更新後の DB を観測するため、検証側では単純な JdbcTemplate を利用する。
  @Autowired private JdbcTemplate jdbcTemplate;

  /**
   * 商品を投入した順序ではなく、ID 昇順で一覧が返ることを確認する。
   *
   * <p>Fixture は 1003 → 1001 → 1002 の順で INSERT している。 順序に加えて、販売停止・在庫 0 の商品も一覧から勝手に除外されないことを確認する。
   */
  @Test
  @DisplayName("商品一覧は全商品を ID 昇順で返す")
  void findAll_returnsEveryProductInIdOrder() {
    List<Product> actual = repository.findAll();

    assertThat(actual)
        .extracting(Product::getId)
        .containsExactly(KEYBOARD_ID, MOUSE_ID, INACTIVE_PRODUCT_ID);
    assertThat(actual)
        .extracting(Product::getName)
        .containsExactly("Keyboard", "Mouse", "Archived Hub");
  }

  /** 空一覧も通常の検索結果であり、null や例外にしない。Fixture の削除も Test の Rollback 対象となる。 */
  @Test
  @DisplayName("商品が 0 件なら空 List を返す")
  void findAll_whenTableIsEmpty_returnsEmptyList() {
    // この Class は商品だけを準備するため、参照元となる注文・明細は存在しない。
    jdbcTemplate.update("DELETE FROM products");

    assertThat(repository.findAll()).isEmpty();
  }

  /**
   * NUMERIC / INTEGER / BOOLEAN を含む 1 行が、Product の各 property に正しく対応することを確認する。
   *
   * <p>通常商品だけでなく FALSE・在庫 0 の商品も読み、boolean の固定値化や column の取り違えを検出する。 BigDecimal は DB から取得した scale
   * に依存せず、金額の数値として比較する。
   */
  @ParameterizedTest(name = "商品 {0}: active={4}, stock={3}")
  @CsvSource({"1001, Keyboard, 3000, 10, true", "1003, Archived Hub, 1000, 0, false"})
  void findById_mapsEveryColumn(
      long id, String name, long price, int stockQuantity, boolean active) {
    assertThat(repository.findById(id))
        .hasValueSatisfying(product -> {
          assertThat(product.getId()).isEqualTo(id);
          assertThat(product.getName()).isEqualTo(name);
          assertThat(product.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(price));
          assertThat(product.getStockQuantity()).isEqualTo(stockQuantity);
          assertThat(product.isActive()).isEqualTo(active);
        });
  }

  /** 商品未存在の Repository 契約は Optional.empty() とし、Service の業務例外への変換はここでは扱わない。 */
  @Test
  @DisplayName("存在しない商品 ID は Optional.empty を返す")
  void findById_whenMissing_returnsEmptyOptional() {
    assertThat(repository.findById(MISSING_ID)).isEmpty();
  }

  /**
   * 在庫に余裕がある場合と、在庫を使い切る境界を同じ検証構造で確認する。
   *
   * <p>更新件数だけでは UPDATE 内容の誤りを検出できないため、SUT と別の SELECT で保存後の在庫も確認する。 どの invocation も初期在庫 10 から始まり、前の
   * Test の更新を引き継がない。
   */
  @ParameterizedTest(name = "在庫 10 から {0} 個減算すると {1} 個")
  @CsvSource({"2, 8", "10, 0"})
  void decreaseStock_whenSufficient_updatesOneRow(int quantity, int expectedStock) {
    int updatedRows = repository.decreaseStock(KEYBOARD_ID, quantity);

    assertThat(updatedRows).isEqualTo(1);
    assertThat(stockOf(KEYBOARD_ID)).isEqualTo(expectedStock);
    assertThat(stockOf(MOUSE_ID)).isEqualTo(20);
  }

  /**
   * 在庫不足は条件付き UPDATE の対象外となり、0 行更新と元の在庫を返す。
   *
   * <p>Java 側の事前確認だけではなく、SQL の stock_quantity >= quantity が効いていることを確かめる。 この逐次実行だけで複数 Transaction
   * の競合全体を検証したとは扱わない。
   */
  @Test
  @DisplayName("在庫不足では 0 行更新となり、在庫を変更しない")
  void decreaseStock_whenInsufficient_keepsDatabaseUnchanged() {
    int updatedRows = repository.decreaseStock(KEYBOARD_ID, 11);

    assertThat(updatedRows).isZero();
    assertThat(stockOf(KEYBOARD_ID)).isEqualTo(10);
  }

  /** 商品がない場合も更新件数 0 とし、別の商品を更新したり新しい商品を作成したりしない。 */
  @Test
  @DisplayName("存在しない商品の在庫減算は 0 行更新")
  void decreaseStock_whenProductMissing_updatesNoRows() {
    assertThat(repository.decreaseStock(MISSING_ID, 1)).isZero();
    assertThat(stockOf(KEYBOARD_ID)).isEqualTo(10);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM products", Long.class))
        .isEqualTo(3L);
  }

  /** 在庫 0 からの復元も許容し、販売状態を変更せず在庫だけを増加させる。 */
  @ParameterizedTest(name = "商品 {0} に 2 個を復元すると {1} 個")
  @CsvSource({"1001, 12", "1003, 2"})
  void increaseStock_addsQuantityToCurrentStock(long productId, int expectedStock) {
    Boolean originalActive =
        jdbcTemplate.queryForObject(
            "SELECT active FROM products WHERE id = ?", Boolean.class, productId);

    assertThat(repository.increaseStock(productId, 2)).isEqualTo(1);
    assertThat(stockOf(productId)).isEqualTo(expectedStock);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT active FROM products WHERE id = ?", Boolean.class, productId))
        .isEqualTo(originalActive);
    assertThat(stockOf(MOUSE_ID)).isEqualTo(20);
  }

  /** 複数回の復元は、その時点の DB 在庫へ累積して加算する。古い取得値による上書きではないことを確認する。 */
  @Test
  @DisplayName("在庫復元は DB の現在値へ累積して加算する")
  void increaseStock_accumulatesChangesAgainstCurrentDatabaseValue() {
    assertThat(repository.increaseStock(KEYBOARD_ID, 2)).isEqualTo(1);
    assertThat(repository.increaseStock(KEYBOARD_ID, 3)).isEqualTo(1);

    assertThat(stockOf(KEYBOARD_ID)).isEqualTo(15);
  }

  /** 復元対象がなくても成功件数を偽らず、呼び出し側が検出できる更新件数を返す。 */
  @Test
  @DisplayName("存在しない商品の在庫復元は 0 行更新")
  void increaseStock_whenProductMissing_updatesNoRows() {
    assertThat(repository.increaseStock(MISSING_ID, 2)).isZero();
    assertThat(stockOf(KEYBOARD_ID)).isEqualTo(10);
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM products", Long.class))
        .isEqualTo(3L);
  }

  /** 0・負数の減算を許すと在庫を減らさない操作や逆方向の更新になるため、SQL 実行前に拒否する。 */
  @ParameterizedTest(name = "減算数量 {0} は拒否")
  @ValueSource(ints = {0, -1})
  void decreaseStock_whenQuantityNotPositive_rejectsBeforeUpdate(int quantity) {
    assertThatThrownBy(() -> repository.decreaseStock(KEYBOARD_ID, quantity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("quantity must be positive");

    // Java 側の入力拒否なので DB Transaction は継続可能であり、未更新を照会できる。
    assertThat(stockOf(KEYBOARD_ID)).isEqualTo(10);
  }

  /** 加算側にも同じ入力境界が必要となる。DB constraint の違反とは分けて検証する。 */
  @ParameterizedTest(name = "加算数量 {0} は拒否")
  @ValueSource(ints = {0, -1})
  void increaseStock_whenQuantityNotPositive_rejectsBeforeUpdate(int quantity) {
    assertThatThrownBy(() -> repository.increaseStock(KEYBOARD_ID, quantity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("quantity must be positive");

    assertThat(stockOf(KEYBOARD_ID)).isEqualTo(10);
  }

  /** SUT の Row Mapping に依存せず、DB に保存された在庫だけを直接観測する。 */
  private int stockOf(long productId) {
    return jdbcTemplate.queryForObject(
        "SELECT stock_quantity FROM products WHERE id = ?", Integer.class, productId);
  }
}
