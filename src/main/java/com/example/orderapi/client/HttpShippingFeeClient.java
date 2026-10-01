package com.example.orderapi.client;

import com.example.orderapi.client.dto.ShippingFeeRequest;
import com.example.orderapi.client.dto.ShippingFeeResponse;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link ShippingFeeClient} の HTTP 実装。
 *
 * <p>Spring の {@link RestClient} を利用して Shipping Fee API へ同期 HTTP request を送信し、 JSON response を
 * {@link ShippingFeeResponse} へ変換して送料だけを上位層へ返す。
 */
@Component
public class HttpShippingFeeClient implements ShippingFeeClient {

  private static final String CALCULATE_PATH = "/shipping-fees/calculate";

  private final RestClient restClient;

  /**
   * Shipping Fee API 専用 RestClient を受け取る。
   *
   * @param restClient Shipping Fee API の Base URL が設定された RestClient
   */
  public HttpShippingFeeClient(@Qualifier("shippingFeeRestClient") RestClient restClient) {
    this.restClient = restClient;
  }

  @Override
  public BigDecimal calculateShippingFee(
      String postalCode, BigDecimal subtotal, int totalQuantity) {
    ShippingFeeRequest request = new ShippingFeeRequest(postalCode, subtotal, totalQuantity);

    ShippingFeeResponse response =
        restClient
            .post()
            .uri(CALCULATE_PATH)
            .body(request)
            .retrieve()
            .body(ShippingFeeResponse.class);

    // HTTP 200 でも body が存在しない場合は、送料として扱える値を取得できていないため異常とする。
    if (response == null) {
      throw new IllegalStateException("shipping fee response body must not be null");
    }

    return response.shippingFee();
  }
}
