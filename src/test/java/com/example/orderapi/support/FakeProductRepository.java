package com.example.orderapi.support;

import com.example.orderapi.domain.Product;
import com.example.orderapi.repository.ProductRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Unit 03 で Fake の性質を確認するための in-memory ProductRepository。
 *
 * <p>Mockito の Mock のように呼び出しごとの戻り値を stubbing するのではなく、 Product を Map に保持し、find / stock update という
 * Repository の基本動作を実際に実装する。 JDBC、SQL、Transaction、DB constraint、同時実行制御は再現しないため、 Production
 * Repository の正しさを保証するものではない。
 */
public class FakeProductRepository implements ProductRepository {

  private final Map<Long, Product> products = new LinkedHashMap<>();

  public FakeProductRepository(List<Product> initialProducts) {
    for (Product product : initialProducts) {
      products.put(product.getId(), product);
    }
  }

  @Override
  public List<Product> findAll() {
    return List.copyOf(products.values());
  }

  @Override
  public Optional<Product> findById(long id) {
    return Optional.ofNullable(products.get(id));
  }

  /**
   * Test から Fake 自身の現在在庫を state verification するための観測用 method。
   *
   * <p>Production Repository interface には存在しない Test Support 用 API であり、 Fake 内部で real state
   * が変化したことだけを確認する。
   *
   * @param productId 商品 ID
   * @return Fake が現在保持している在庫数
   */
  public int currentStockQuantity(long productId) {
    Product product = products.get(productId);
    if (product == null) {
      throw new IllegalArgumentException("product not found: " + productId);
    }
    return product.getStockQuantity();
  }

  @Override
  public int decreaseStock(long productId, int quantity) {
    Product current = products.get(productId);

    if (current == null || current.getStockQuantity() < quantity) {
      return 0;
    }

    products.put(
        productId,
        new Product(
            current.getId(),
            current.getName(),
            current.getPrice(),
            current.getStockQuantity() - quantity,
            current.isActive()));
    return 1;
  }

  @Override
  public int increaseStock(long productId, int quantity) {
    Product current = products.get(productId);

    if (current == null) {
      return 0;
    }

    products.put(
        productId,
        new Product(
            current.getId(),
            current.getName(),
            current.getPrice(),
            current.getStockQuantity() + quantity,
            current.isActive()));
    return 1;
  }
}
