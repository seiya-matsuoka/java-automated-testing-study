package com.example.orderapi.support;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Unit 09 の実 Application Context に、専用 DB・HTTP Stub・固定時刻を追加する。
 *
 * <p>実 Controller / Service / Repository / Client は実アプリの Component Scan で組み立てる。 Unit 07 の送料
 * Mock、Unit 08 の Client Slice は import しない。通常用 Compose と永続 Volume、 固定ホストポートには依存せず、各リソースを Spring
 * Context と同じ期間だけ保持する。
 */
@TestConfiguration(proxyBeanMethods = false)
public class OrderApiTestConfiguration {

  /**
   * Spring Boot が起動・停止する使い捨て PostgreSQL。接続失敗時に通常用 DB へ切り替えない。
   *
   * <p>@ServiceConnection の情報を DataSource へ渡し、Production の schema.sql だけを初期適用する。 通常用 data.sql は
   * Test の spring.sql.init.mode=never で止め、行データは各 @Sql で準備する。
   */
  @Bean
  @ServiceConnection
  public PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer("postgres:18.6")
        .withDatabaseName("unit09_api_test")
        .withUsername("test")
        .withPassword("test")
        .withInitScript("schema.sql");
  }

  /**
   * 外部サービスの HTTP 境界だけを置き換える、JVM 内の WireMock。
   *
   * <p>Unit 08 のクラス所有 Extension と異なり、Context Cache が存続する間は停止しない。 他クラスが再利用する実 Client に停止済み URL
   * を残さず、Context 終了時に停止する。 instance DSL を使うため、WireMock.configureFor のような static な接続先設定は不要である。
   */
  @Bean(initMethod = "start", destroyMethod = "stop")
  public WireMockServer shippingFeeServer() {
    return new WireMockServer(
        wireMockConfig().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
  }

  /**
   * 起動済み Server の URL を、実 RestClientConfig の Bean 生成より前に登録する。
   *
   * <p>DynamicPropertyRegistrar は通常の Singleton の事前生成に先行する。引数の Server は initMethod
   * による起動まで完了してから渡されるため、動的ポートを確定して公開できる。 @BeforeEach で URL を変更しても生成済み RestClient には反映されない点と区別する。
   */
  @Bean
  public DynamicPropertyRegistrar shippingFeeProperties(WireMockServer shippingFeeServer) {
    return registry ->
        registry.add(
            "external.shipping-fee.base-url", () -> "http://127.0.0.1:" + shippingFeeServer.port());
  }

  /** system clock は残し、Service への注入候補を固定 Clock にする。可変 Clock は共有しない。 */
  @Bean
  @Primary
  public Clock unit09Clock() {
    return Clock.fixed(OrderApiTestFixture.NOW, ZoneOffset.UTC);
  }
}
