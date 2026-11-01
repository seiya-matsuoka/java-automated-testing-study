package com.example.orderapi.support;

import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Unit 07 の実 Application Context に、専用 PostgreSQL と固定 Clock を追加する設定。
 *
 * <p>Unit 06 の Container 管理方法を踏襲するが、Slice 用の Repository import は行わない。 Service / Repository /
 * Transaction Manager は実 Application の Component Scan と Auto Configuration で組み立てる。通常の Compose、永続
 * Volume、固定ホストポートには依存しない。
 *
 * <p>Container は Spring Bean として Context と同じ期間だけ保持する。Mock / Spy の構成が異なる場合は 別 Context と別 Container
 * が作られ得るため、全クラスで必ず 1 個になるとは仮定しない。
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresServiceIntegrationTestConfiguration {

  /**
   * 本番と同じ Schema を持つ、使い捨てのテスト用 DB を提供する。
   *
   * <p>@ServiceConnection の接続情報を通常用 application.yml より優先させる。 各 Test の spring.sql.init.mode=never
   * と組み合わせ、通常用 data.sql の投入を防ぐ。 Schema は起動時だけ、行データは各 Test の @Sql で準備する。
   */
  @Bean
  @ServiceConnection
  public PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer("postgres:18.6")
        .withDatabaseName("unit07_service_test")
        .withUsername("test")
        .withPassword("test")
        .withInitScript("schema.sql");
  }

  /**
   * 実時刻を待たず、作成・キャンセル日時を決定的に確認するための Clock。
   *
   * <p>Production の system clock Bean は変更せず、@Primary で Service へ注入する候補を選ぶ。 Clock の API
   * は実物を使い、時刻だけを固定する。SQL の既存注文はこの時刻より前に作成している。
   */
  @Bean
  @Primary
  public Clock unit07Clock() {
    return Clock.fixed(OrderServiceIntegrationTestFixture.NOW, ZoneOffset.UTC);
  }
}
