package com.example.orderapi.repository.jdbc;

import com.example.orderapi.domain.Product;
import com.example.orderapi.repository.ProductRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@link ProductRepository} の Spring JDBC 実装。
 *
 * <p>{@link NamedParameterJdbcTemplate} を利用し、SQL と名前付きパラメータを明示して {@code products} table へアクセスする。
 */
@Repository
public class JdbcProductRepository implements ProductRepository {

  private static final String SELECT_ALL_SQL =
      """
      SELECT
          id,
          name,
          price,
          stock_quantity,
          active
      FROM products
      ORDER BY id
      """;

  private static final String SELECT_BY_ID_SQL =
      """
      SELECT
          id,
          name,
          price,
          stock_quantity,
          active
      FROM products
      WHERE id = :id
      """;

  /**
   * 在庫確認と減算を 1 つの UPDATE として実行する SQL。
   *
   * <p>事前に取得した在庫数を Java 側で再計算して上書きせず、 UPDATE 実行時点の DB 在庫が十分な場合だけ減算する。
   * 複数注文が同時に同じ在庫を参照した場合でも、在庫を超えた減算を防ぐ。
   */
  private static final String DECREASE_STOCK_SQL =
      """
      UPDATE products
      SET stock_quantity = stock_quantity - :quantity
      WHERE id = :id
        AND stock_quantity >= :quantity
      """;

  /** キャンセル時の在庫復元を DB 上の現在値に対する加算として実行する SQL。 */
  private static final String INCREASE_STOCK_SQL =
      """
      UPDATE products
      SET stock_quantity = stock_quantity + :quantity
      WHERE id = :id
      """;

  private final NamedParameterJdbcTemplate jdbcTemplate;

  /**
   * Product Repository で使用する JDBC 操作を受け取る。
   *
   * @param jdbcTemplate 名前付きパラメータ対応の JDBC Template
   */
  public JdbcProductRepository(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public List<Product> findAll() {
    return jdbcTemplate.query(SELECT_ALL_SQL, this::mapProduct);
  }

  @Override
  public Optional<Product> findById(long id) {
    List<Product> products =
        jdbcTemplate.query(SELECT_BY_ID_SQL, Map.of("id", id), this::mapProduct);

    // ID は Primary Key のため、0 件または 1 件だけを想定する。
    return products.stream().findFirst();
  }

  @Override
  public int decreaseStock(long productId, int quantity) {
    validatePositiveQuantity(quantity);

    return jdbcTemplate.update(
        DECREASE_STOCK_SQL,
        Map.of(
            "id", productId,
            "quantity", quantity));
  }

  @Override
  public int increaseStock(long productId, int quantity) {
    validatePositiveQuantity(quantity);

    return jdbcTemplate.update(
        INCREASE_STOCK_SQL,
        Map.of(
            "id", productId,
            "quantity", quantity));
  }

  /** 在庫増減に使用する数量が正数であることを確認する。 */
  private void validatePositiveQuantity(int quantity) {
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be positive");
    }
  }

  /**
   * products table の 1 行を Product へ変換する。
   *
   * <p>DB column 名と Domain Model の field の対応を Repository 実装へ閉じ込め、 JDBC 固有の {@link ResultSet}
   * を上位層へ持ち出さない。
   */
  private Product mapProduct(ResultSet resultSet, int rowNumber) throws SQLException {
    return new Product(
        resultSet.getLong("id"),
        resultSet.getString("name"),
        resultSet.getBigDecimal("price"),
        resultSet.getInt("stock_quantity"),
        resultSet.getBoolean("active"));
  }
}
