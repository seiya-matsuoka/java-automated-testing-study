package com.example.orderapi.service;

import com.example.orderapi.domain.Product;
import com.example.orderapi.exception.ProductNotFoundException;
import com.example.orderapi.repository.ProductRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商品参照に関する Application Service。
 *
 * <p>Repository から取得した Product を上位層へ提供し、 商品未存在を業務例外へ変換する。
 */
@Service
public class ProductService {

  private final ProductRepository productRepository;

  public ProductService(ProductRepository productRepository) {
    this.productRepository = productRepository;
  }

  /**
   * 全商品を取得する。
   *
   * @return 商品一覧
   */
  @Transactional(readOnly = true)
  public List<Product> findAll() {
    return productRepository.findAll();
  }

  /**
   * 商品 ID を指定して Product を取得する。
   *
   * @param productId 商品 ID
   * @return Product
   * @throws ProductNotFoundException 商品が存在しない場合
   */
  @Transactional(readOnly = true)
  public Product findById(long productId) {
    return productRepository
        .findById(productId)
        .orElseThrow(() -> new ProductNotFoundException(productId));
  }
}
