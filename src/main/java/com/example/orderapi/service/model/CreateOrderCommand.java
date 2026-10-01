package com.example.orderapi.service.model;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 注文作成 Service へ渡す入力値を表す Service Model。
 *
 * <p>配送先郵便番号と注文明細を 1 つの Command としてまとめ、 Web 層の Request DTO から Service 層へ渡す入力境界として利用する。
 *
 * @param shippingPostalCode 配送先郵便番号
 * @param items 注文対象商品と数量
 */
public record CreateOrderCommand(String shippingPostalCode, List<CreateOrderItemCommand> items) {

  private static final Pattern POSTAL_CODE_PATTERN = Pattern.compile("^[0-9]{7}$");

  /** 注文作成 Command として成立する最低限の入力値を検証する。 */
  public CreateOrderCommand {
    if (shippingPostalCode == null || !POSTAL_CODE_PATTERN.matcher(shippingPostalCode).matches()) {
      throw new IllegalArgumentException("shippingPostalCode must be 7 digits");
    }
    if (items == null || items.isEmpty()) {
      throw new IllegalArgumentException("items must not be empty");
    }
    if (items.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("items must not contain null");
    }

    // 呼び出し元が保持する List の変更によって Command の内容が変化しないよう不変コピーを保持する。
    items = List.copyOf(items);
  }
}
