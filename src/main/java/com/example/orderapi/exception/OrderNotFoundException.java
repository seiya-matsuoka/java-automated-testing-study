package com.example.orderapi.exception;

/** 指定した注文が存在しない場合の業務例外。 */
public class OrderNotFoundException extends RuntimeException {

  private final long orderId;

  public OrderNotFoundException(long orderId) {
    super("order not found: " + orderId);
    this.orderId = orderId;
  }

  public long getOrderId() {
    return orderId;
  }
}
