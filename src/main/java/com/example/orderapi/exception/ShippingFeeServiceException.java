package com.example.orderapi.exception;

/**
 * Shipping Fee API から正常な送料を取得できなかった場合の業務例外。
 *
 * <p>HTTP Client 固有の例外を Service 層より上へ直接公開せず、 外部送料サービスの利用失敗という業務上の意味へ変換する。
 */
public class ShippingFeeServiceException extends RuntimeException {

  public ShippingFeeServiceException(String message, Throwable cause) {
    super(message, cause);
  }
}
