package com.example.orderapi.dto.order;

import com.example.orderapi.service.model.CreateOrderCommand;
import com.example.orderapi.service.model.CreateOrderItemCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/**
 * 注文作成 API の Request DTO。
 *
 * @param shippingPostalCode 配送先郵便番号
 * @param items 注文商品一覧
 */
public record CreateOrderRequest(
    @NotBlank(message = "shippingPostalCode is required")
        @Pattern(regexp = "^[0-9]{7}$", message = "shippingPostalCode must be 7 digits")
        String shippingPostalCode,
    @NotEmpty(message = "items must not be empty") @Valid List<CreateOrderItemRequest> items) {

  /**
   * Web API の Request DTO を Service 層の Command へ変換する。
   *
   * @return 注文作成用 Command
   */
  public CreateOrderCommand toCommand() {
    List<CreateOrderItemCommand> commandItems =
        items.stream().map(CreateOrderItemRequest::toCommand).toList();

    return new CreateOrderCommand(shippingPostalCode, commandItems);
  }
}
