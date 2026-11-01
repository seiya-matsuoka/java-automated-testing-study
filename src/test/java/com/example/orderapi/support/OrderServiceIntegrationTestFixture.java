package com.example.orderapi.support;

import com.example.orderapi.service.model.CreateOrderCommand;
import com.example.orderapi.service.model.CreateOrderItemCommand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit 07 の Command、SQL Fixture の識別子、および DB 状態の観測を支える補助クラス。
 *
 * <p>商品の初期在庫は Keyboard 10、Mouse 20 とする。orders.sql を追加するケースでは、 対象注文の 2 個ずつが減算済みの 8 / 18 から始める。SUT
 * による注文作成をキャンセルの Arrange に使わない。
 *
 * <p>金額の期待値は各 Test で仕様から明示し、Production の計算結果から生成しない。 固定 ID は既存行の識別用であり、新規注文の採番値・連番・Test
 * の実行順を表すものではない。
 */
public final class OrderServiceIntegrationTestFixture {

  public static final long KEYBOARD_ID = 7001L;
  public static final long MOUSE_ID = 7002L;
  public static final long INACTIVE_PRODUCT_ID = 7003L;
  public static final long OUT_OF_STOCK_PRODUCT_ID = 7004L;
  public static final long CREATED_ORDER_ID = 70001L;
  public static final long CONFIRMED_ORDER_ID = 70002L;
  public static final long SHIPPED_ORDER_ID = 70003L;
  public static final long CANCELLED_ORDER_ID = 70004L;
  public static final long MISSING_ID = 999999L;
  public static final String POSTAL_CODE = "1000001";
  public static final BigDecimal SHIPPING_FEE = BigDecimal.valueOf(800);

  // PostgreSQL の TIMESTAMPTZ の精度に合わせ、小数部を 6 桁に揃える。
  public static final Instant NOW = Instant.parse("2026-10-10T09:00:00.123456Z");
  public static final Instant SEEDED_CREATED_AT = Instant.parse("2026-10-01T00:00:00.123456Z");
  public static final Instant SEEDED_CANCELLED_AT = Instant.parse("2026-10-05T00:00:00.654321Z");

  private OrderServiceIntegrationTestFixture() {
    // Test 間で共有する mutable な状態は持たない。
  }

  /** 毎回新しい Command を生成し、呼び出し元の変更や前回の Test に依存させない。 */
  public static CreateOrderCommand singleItemCommand(long productId, int quantity) {
    return new CreateOrderCommand(
        POSTAL_CODE, List.of(new CreateOrderItemCommand(productId, quantity)));
  }

  /** 先頭商品の実更新後に、後続商品で失敗する順序も明確になる 2 明細の入力。 */
  public static CreateOrderCommand multiItemCommand() {
    return new CreateOrderCommand(
        POSTAL_CODE,
        List.of(
            new CreateOrderItemCommand(KEYBOARD_ID, 2), new CreateOrderItemCommand(MOUSE_ID, 2)));
  }

  /** Repository の戻り値を期待値へ流用せず、実 DB の現在在庫を直接読む。 */
  public static int stockOf(JdbcTemplate jdbcTemplate, long productId) {
    return jdbcTemplate.queryForObject(
        "SELECT stock_quantity FROM products WHERE id = ?", Integer.class, productId);
  }

  /**
   * 現在の 3 テーブルを全列・ID 順で観測し、処理前後の状態比較に使う。
   *
   * <p>呼び出す位置で意味が異なる。Service 呼び出し後なら業務 Transaction の外から確定状態を読み、 Spy の失敗直前なら同じ JdbcTemplate が業務
   * Transaction に参加して未 Commit の先行更新を読む。 SELECT 自身は Cleanup を行わず、Rollback を装う DELETE や在庫の再設定も行わない。
   */
  public static DatabaseState databaseState(JdbcTemplate jdbcTemplate) {
    return new DatabaseState(
        jdbcTemplate.queryForList("SELECT * FROM products ORDER BY id"),
        jdbcTemplate.queryForList("SELECT * FROM orders ORDER BY id"),
        jdbcTemplate.queryForList("SELECT * FROM order_items ORDER BY id"));
  }

  /**
   * DB の観測結果をまとめる値。期待状態の計算や Production の Domain 復元は行わない。
   *
   * <p>JDBC の行 Map を保持するため、金額は BigDecimal、TIMESTAMPTZ は JDBC の日時型として扱われる。
   * 初期状態との比較では、状態・金額・日時・明細・無関係な商品を含めた不変を確認できる。
   */
  public record DatabaseState(
      List<Map<String, Object>> products,
      List<Map<String, Object>> orders,
      List<Map<String, Object>> orderItems) {}
}
