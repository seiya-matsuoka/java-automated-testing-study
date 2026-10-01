package com.example.orderapi.repository;

import com.example.orderapi.domain.Product;
import java.util.List;
import java.util.Optional;

/**
 * Product の永続化処理に対する抽象化。
 *
 * <p>Service 層から具体的な JDBC 実装を隠し、商品データの取得・在庫更新という Repository の責務だけを公開する。
 */
public interface ProductRepository {

  /**
   * 全商品を ID 昇順で取得する。
   *
   * @return 商品一覧
   */
  List<Product> findAll();

  /**
   * 商品 ID を指定して Product を取得する。
   *
   * @param id 商品 ID
   * @return Product。存在しない場合は empty
   */
  Optional<Product> findById(long id);

  /**
   * 商品の現在在庫数を更新する。
   *
   * @param productId 商品 ID
   * @param newStockQuantity 更新後の在庫数
   * @return 更新された行数
   */
  int updateStock(long productId, int newStockQuantity);
}
