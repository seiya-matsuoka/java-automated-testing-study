package com.example.orderapi.exception;

import com.example.orderapi.domain.OrderStatus;

/** 現在の注文状態から許可されていない状態遷移を要求した場合の業務例外。 */
public class InvalidOrderStatusTransitionException extends RuntimeException {

  private final long orderId;
  private final OrderStatus currentStatus;
  private final OrderStatus targetStatus;

  public InvalidOrderStatusTransitionException(
      long orderId, OrderStatus currentStatus, OrderStatus targetStatus) {
    super(
        "invalid order status transition: orderId="
            + orderId
            + ", current="
            + currentStatus
            + ", target="
            + targetStatus);
    this.orderId = orderId;
    this.currentStatus = currentStatus;
    this.targetStatus = targetStatus;
  }

  public long getOrderId() {
    return orderId;
  }

  public OrderStatus getCurrentStatus() {
    return currentStatus;
  }

  public OrderStatus getTargetStatus() {
    return targetStatus;
  }
}
