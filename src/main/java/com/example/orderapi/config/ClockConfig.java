package com.example.orderapi.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * アプリケーションで利用する現在時刻の取得方法を定義する設定クラス。
 *
 * <p>現在時刻を必要とする処理が {@link Clock} に依存する構造とすることで、 Production Code から {@code Instant.now()}
 * などを直接呼び出す箇所を分散させない。 UTC を基準とする system clock を Spring Bean として公開する。
 */
@Configuration
public class ClockConfig {

  /**
   * UTC を基準とする system clock を提供する。
   *
   * @return アプリケーション全体で共有する {@link Clock}
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
