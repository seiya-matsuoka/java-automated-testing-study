package com.example.orderapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 外部 REST API 呼び出しで使用する {@link RestClient} を構成する設定クラス。
 *
 * <p>Shipping Fee API の Base URL を外部設定から受け取り、 Shipping Fee API 専用の RestClient Bean を生成する。 URL を
 * Production Code に直接埋め込まず、実行環境ごとに差し替え可能な構造とする。
 */
@Configuration
public class RestClientConfig {

  /**
   * Shipping Fee API 専用の RestClient を生成する。
   *
   * <p>Spring Boot が Auto Configuration した {@link RestClient.Builder} を利用することで、 HTTP Message
   * Conversion や ClientHttpRequestFactory などの標準設定を引き継ぐ。
   *
   * @param restClientBuilder Spring Boot が提供する RestClient Builder
   * @param baseUrl Shipping Fee API の Base URL
   * @return Shipping Fee API 専用 RestClient
   */
  @Bean
  public RestClient shippingFeeRestClient(
      RestClient.Builder restClientBuilder,
      @Value("${external.shipping-fee.base-url}") String baseUrl) {
    return restClientBuilder.baseUrl(baseUrl).build();
  }
}
