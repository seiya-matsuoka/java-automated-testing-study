package com.example.orderapi.dto.order;

import com.example.orderapi.service.model.CreateOrderItemCommand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 注文作成 API の 1 商品分の Request DTO。
 *
 * @param productId 商品 ID
 * @param quantity 注文数量
 */
public record CreateOrderItemRequest(
    @NotNull(message = "productId is required") @Positive(message = "productId must be positive")
        Long productId,
    @NotNull(message = "quantity is required")
        @Min(value = 1, message = "quantity must be at least 1")
        @Max(value = 99, message = "quantity must be at most 99")
        Integer quantity) {

  /**
   * Web API の入力値を Service 層の Command へ変換する。
   *
   * <p>Bean Validation 成功後に呼び出すことを前提とし、 Web DTO を Service 層へ直接持ち込まない。
   *
   * @return 注文作成用 Service Model
   */
  public CreateOrderItemCommand toCommand() {
    return new CreateOrderItemCommand(productId, quantity);
  }
}
