package com.example.orderapi.service.model;

import com.example.orderapi.domain.OrderItem;

/**
 * 注文作成 Service へ渡す 1 商品分の入力値を表す Service Model。
 *
 * <p>Web 層の Request DTO とは分離し、Service 層が必要とする商品 ID と数量だけを保持する。
 *
 * @param productId 商品 ID
 * @param quantity 注文数量
 */
public record CreateOrderItemCommand(long productId, int quantity) {

  /** Service Model として成立する最低限の入力値を検証する。 */
  public CreateOrderItemCommand {
    if (productId <= 0) {
      throw new IllegalArgumentException("productId must be positive");
    }
    if (!OrderItem.isValidQuantity(quantity)) {
      throw new IllegalArgumentException("quantity must be between 1 and 99");
    }
  }
}
