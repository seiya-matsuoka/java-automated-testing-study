package com.example.orderapi.exception;

/** 1 注文内に同じ商品 ID が複数指定された場合の業務例外。 */
public class DuplicateOrderItemException extends RuntimeException {

  private final long productId;

  public DuplicateOrderItemException(long productId) {
    super("duplicate product in order: " + productId);
    this.productId = productId;
  }

  public long getProductId() {
    return productId;
  }
}
