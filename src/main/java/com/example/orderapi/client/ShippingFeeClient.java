package com.example.orderapi.client;

import java.math.BigDecimal;

/**
 * 外部 Shipping Fee API との通信境界を表す interface。
 *
 * <p>Service 層から HTTP 通信の実装詳細を隠し、送料計算という業務上必要な操作だけを公開する。 HTTP Client、URL、JSON 形式などの外部 API
 * 固有の要素は実装クラス側へ閉じ込める。
 */
public interface ShippingFeeClient {

  /**
   * 配送先・商品小計・総数量を基に送料を取得する。
   *
   * @param postalCode 配送先郵便番号
   * @param subtotal 商品小計
   * @param totalQuantity 注文商品の総数量
   * @return 外部 API が算出した送料
   */
  BigDecimal calculateShippingFee(String postalCode, BigDecimal subtotal, int totalQuantity);
}
