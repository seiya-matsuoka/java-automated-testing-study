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
   * 在庫が注文数量以上ある場合だけ、DB 上の現在在庫から指定数量を減算する。
   *
   * @param productId 商品 ID
   * @param quantity 減算する数量
   * @return 更新された行数。在庫不足または商品未存在の場合は 0
   */
  int decreaseStock(long productId, int quantity);

  /**
   * DB 上の現在在庫へ指定数量を加算する。
   *
   * @param productId 商品 ID
   * @param quantity 加算する数量
   * @return 更新された行数
   */
  int increaseStock(long productId, int quantity);
}
