package com.example.orderapi.exception;

/** 指定した商品が存在しない場合の業務例外。 */
public class ProductNotFoundException extends RuntimeException {

  private final long productId;

  public ProductNotFoundException(long productId) {
    super("product not found: " + productId);
    this.productId = productId;
  }

  public long getProductId() {
    return productId;
  }
}
