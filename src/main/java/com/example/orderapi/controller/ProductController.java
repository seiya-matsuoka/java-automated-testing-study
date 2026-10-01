package com.example.orderapi.controller;

import com.example.orderapi.dto.product.ProductResponse;
import com.example.orderapi.service.ProductService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Product REST API の HTTP endpoint を提供する Controller。
 *
 * <p>HTTP request を Service 呼び出しへ変換し、 Domain Model を Web API 用 Response DTO へ変換して返却する。
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

  private final ProductService productService;

  public ProductController(ProductService productService) {
    this.productService = productService;
  }

  /**
   * 全商品を取得する。
   *
   * @return 商品一覧
   */
  @GetMapping
  public List<ProductResponse> findAll() {
    return productService.findAll().stream().map(ProductResponse::from).toList();
  }

  /**
   * 商品 ID を指定して 1 商品を取得する。
   *
   * @param id 商品 ID
   * @return 商品情報
   */
  @GetMapping("/{id}")
  public ProductResponse findById(@PathVariable long id) {
    return ProductResponse.from(productService.findById(id));
  }
}
