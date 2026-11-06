package com.example.orderapi.support;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit 09 の公開 JSON 入力、SQL の識別子、DB の直接観測をまとめる。
 *
 * <p>Request は Production DTO の serialization から作らず、HTTP の field 名を明示する。 金額の期待値は各 Test
 * が業務仕様から定める。固定 ID は SQL の既存行を識別する値であり、 新規注文の採番値、連番、実行順を表さない。
 */
public final class OrderApiTestFixture {

  public static final long KEYBOARD_ID = 9001L;
  public static final long MOUSE_ID = 9002L;
  public static final long INACTIVE_ID = 9003L;
  public static final long OUT_OF_STOCK_ID = 9004L;
  public static final long CREATED_ID = 92001L;
  public static final long CONFIRMED_ID = 92002L;
  public static final long SHIPPED_ID = 92003L;
  public static final long CANCELLED_ID = 92004L;
  public static final long MISSING_ID = 999999L;
  public static final String POSTAL_CODE = "1000001";
  public static final Instant NOW = Instant.parse("2026-11-01T09:00:00.123456Z");
  public static final Instant SEEDED_AT = Instant.parse("2026-10-01T00:00:00.123456Z");
  public static final Instant CANCELLED_AT = Instant.parse("2026-10-05T00:00:00.654321Z");

  private OrderApiTestFixture() {}

  /** 単価 3,000 × 2 と 2,500 × 2 の 2 明細。小計 11,000、総数量 4 を仕様から期待する。 */
  public static String multiItemRequest() {
    return """
    {
      "shippingPostalCode": "1000001",
      "items": [
        {"productId": 9001, "quantity": 2},
        {"productId": 9002, "quantity": 2}
      ]
    }
    """;
  }

  /** 正常・異常の差を指定し、毎回独立した JSON 文字列を返す。 */
  public static String singleItemRequest(String postalCode, long productId, int quantity) {
    return """
    {"shippingPostalCode":"%s","items":[{"productId":%d,"quantity":%d}]}
    """
        .formatted(postalCode, productId, quantity);
  }

  /**
   * 全列を ID 順で直接 SELECT し、API の JOIN では隠れるヘッダ単独の残存も観測する。
   *
   * <p>HTTP Response の受信後、Server の業務 Transaction が終了した状態で呼び出す。 この処理には DELETE・在庫の補償更新・Rollback
   * を含めず、Cleanup 前の証拠だけを採取する。
   */
  public static DatabaseState databaseState(JdbcTemplate jdbcTemplate) {
    return new DatabaseState(
        jdbcTemplate.queryForList("SELECT * FROM products ORDER BY id"),
        jdbcTemplate.queryForList("SELECT * FROM orders ORDER BY id"),
        jdbcTemplate.queryForList("SELECT * FROM order_items ORDER BY id"));
  }

  /** Repository の Domain 復元を再利用せず、現在在庫の保存値を読む。 */
  public static int stockOf(JdbcTemplate jdbcTemplate, long id) {
    return jdbcTemplate.queryForObject(
        "SELECT stock_quantity FROM products WHERE id = ?", Integer.class, id);
  }

  /** JDBC の金額は BigDecimal、日時は Timestamp として保持し、全テーブルの不変比較にも使う。 */
  public record DatabaseState(
      List<Map<String, Object>> products,
      List<Map<String, Object>> orders,
      List<Map<String, Object>> orderItems) {}
}
