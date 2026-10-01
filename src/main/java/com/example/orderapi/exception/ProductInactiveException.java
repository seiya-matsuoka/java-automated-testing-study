package com.example.orderapi.exception;

/** 販売停止中の商品を注文しようとした場合の業務例外。 */
public class ProductInactiveException extends RuntimeException {

  private final long productId;

  public ProductInactiveException(long productId) {
    super("product is inactive: " + productId);
    this.productId = productId;
  }

  public long getProductId() {
    return productId;
  }
}
