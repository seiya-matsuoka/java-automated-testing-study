package com.example.orderapi.exception;

/** 商品在庫が注文要求数量を満たさない場合の業務例外。 */
public class InsufficientStockException extends RuntimeException {

  private final long productId;
  private final int requestedQuantity;
  private final int availableQuantity;

  public InsufficientStockException(long productId, int requestedQuantity, int availableQuantity) {
    super(
        "insufficient stock: productId="
            + productId
            + ", requested="
            + requestedQuantity
            + ", available="
            + availableQuantity);
    this.productId = productId;
    this.requestedQuantity = requestedQuantity;
    this.availableQuantity = availableQuantity;
  }

  public long getProductId() {
    return productId;
  }

  public int getRequestedQuantity() {
    return requestedQuantity;
  }

  public int getAvailableQuantity() {
    return availableQuantity;
  }
}
