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

  // Fake が Repository の永続状態の代わりとして保持する in-memory state。
  private final Map<Long, Product> products = new LinkedHashMap<>();

  /**
   * Test で指定された Product 一覧を Fake の初期状態として保持する。
   *
   * <p>Mock のように method ごとの戻り値を設定するのではなく、 初期状態から find / stock update の結果が決まる実装として扱う。
   */
  public FakeProductRepository(List<Product> initialProducts) {
    for (Product product : initialProducts) {
      products.put(product.getId(), product);
    }
  }

  /**
   * Fake が現在保持している Product を一覧として返す。
   *
   * <p>Repository の検索動作を簡易的に再現し、呼び出し時点の in-memory state を結果へ反映する。
   */
  @Override
  public List<Product> findAll() {
    return List.copyOf(products.values());
  }

  /**
   * Product ID を key として Fake の現在状態を検索する。
   *
   * <p>事前に stubbing した戻り値ではなく、Map に存在するかどうかで結果が決まる点が Mock と異なる。
   */
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

  /**
   * Fake 内の在庫を実際に減算し、後続の検索でも更新後状態を参照できるようにする。
   *
   * <p>商品が存在しない場合や在庫不足の場合は更新件数 0、 更新できた場合は 1 を返し、Production Repository の契約を必要最小限で再現する。
   */
  @Override
  public int decreaseStock(long productId, int quantity) {
    Product current = products.get(productId);

    if (current == null || current.getStockQuantity() < quantity) {
      return 0;
    }

    // Product の現在値を基に、在庫だけを減らした新しい状態へ Fake の保持値を置き換える。
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

  /**
   * Fake 内の在庫を実際に復元し、状態変更を in-memory に保持する。
   *
   * <p>Mock の単純な戻り値指定とは異なり、呼び出し後の state verification が可能になる。 商品が存在しない場合は Production Repository
   * の契約に合わせて更新件数 0 を返す。
   */
  @Override
  public int increaseStock(long productId, int quantity) {
    Product current = products.get(productId);

    if (current == null) {
      return 0;
    }

    // Product の現在値を基に、在庫だけを増やした新しい状態へ Fake の保持値を置き換える。
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
