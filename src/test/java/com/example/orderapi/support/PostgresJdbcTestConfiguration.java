package com.example.orderapi.support;

import com.example.orderapi.repository.jdbc.JdbcOrderRepository;
import com.example.orderapi.repository.jdbc.JdbcProductRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Unit 06 の JDBC Test Slice に、実 Repository とテスト専用 PostgreSQL を追加する設定。
 *
 * <p>@JdbcTest は JDBC 基盤に対象を限定し、通常の @Repository を自動では読み込まない。 検証したい 2 つの JDBC 実装だけを明示的に import
 * し、Controller / Service / HTTP Client は起動しない。 Test Double は使用せず、SQL、JDBC Driver、DB constraint、Row
 * Mapping を本物として確認する。
 *
 * <p>H2 などの in-memory DB は高速な選択肢だが、この Unit では PostgreSQL の IDENTITY、 NUMERIC、TIMESTAMPTZ、正規表現 CHECK
 * を実 DB と組み合わせて読むため PostgreSQL を選ぶ。 @AutoConfigureTestDatabase(replace = NONE) は、組み込み DB
 * への接続先の差し替えを防ぐ指定となる。
 *
 * <p>この設定は各 Test Class から同じ形で import する。Container を Spring Bean にすることで、 キャッシュされた Test Context と
 * Container の存続期間を合わせ、利用中の接続先だけが先に停止することを防ぐ。 Context を共有しても、テストデータは各 Test の Transaction と Rollback
 * により独立させる。
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({JdbcProductRepository.class, JdbcOrderRepository.class})
public class PostgresJdbcTestConfiguration {

  /**
   * 通常確認用 Compose / 永続 Volume を使用しない、使い捨ての PostgreSQL を提供する。
   *
   * <p>@ServiceConnection は Container の動的な接続先を DataSource へ渡す。 application.yml
   * の通常用接続情報より優先されるため、localhost:5432 の開発用 DB へ接続しない。 ホスト側のポートは固定せず、Container の起動・停止は Spring Boot
   * に任せる。
   *
   * <p>Schema は Production Code と同じ schema.sql を Container 起動時に 1 回だけ実行する。 各 @JdbcTest では
   * spring.sql.init.mode=never を指定し、通常用 data.sql と Schema の二重実行を防ぐ。 商品・注文の Fixture は、この初期化とは別に @Sql
   * で Test ごとの Transaction 内へ投入する。
   *
   * @return 起動と破棄を Spring が管理するテスト用 Container
   */
  @Bean
  @ServiceConnection
  public PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer("postgres:18.6")
        .withDatabaseName("unit06_repository_test")
        .withUsername("test")
        .withPassword("test")
        .withInitScript("schema.sql");
  }
}
