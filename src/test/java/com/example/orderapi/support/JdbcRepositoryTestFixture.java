package com.example.orderapi.support;

import com.example.orderapi.domain.Order;
import com.example.orderapi.domain.OrderItem;
import com.example.orderapi.domain.PriceSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Unit 06 の SQL Fixture と登録テストで共通利用する、固定値と Domain Object の factory。
 *
 * <p>products.sql / orders.sql の ID は、既存行を指すための Test Data として固定する。 Repository が新しく採番する ID
 * は戻り値から取得し、固定値や連番を期待しない。 PostgreSQL の sequence は Rollback で消費前へ戻らないため、採番の欠番はテスト失敗としない。
 *
 * <p>このクラスは DB へ書き込まない。検索用の前提は SQL で準備し、登録用の入力は Java で準備することで、 SUT の登録処理と検索処理を互いの Arrange
 * として使わずに検証する。
 *
 * <p>開始状態と期待状態を分ける考え方は、DBUnit の dataset / expected dataset とも共通する。 ここでは開始状態を SQL、期待状態を各 Test の
 * Assertion で表し、DBUnit 自体は導入しない。 Testcontainers は DB 実行環境を用意し、Fixture の投入・期待結果の比較は別の仕組みが担当する。
 */
public final class JdbcRepositoryTestFixture {

  public static final long KEYBOARD_ID = 1001L;
  public static final long MOUSE_ID = 1002L;
  public static final long INACTIVE_PRODUCT_ID = 1003L;
  public static final long CREATED_ORDER_ID = 10004L;
  public static final long MULTI_ITEM_ORDER_ID = 10003L;
  public static final long CANCELLED_ORDER_ID = 10006L;
  public static final long MISSING_ID = 999_999L;
  public static final String POSTAL_CODE = "1000001";

  // TIMESTAMPTZ の精度に合わせた固定時刻。実時刻や処理時間の許容誤差には依存しない。
  public static final Instant CREATED_AT = Instant.parse("2026-10-01T00:00:00.123456Z");
  public static final Instant CANCELLED_AT = Instant.parse("2026-10-05T00:00:00.654321Z");
  public static final Instant SEARCH_FROM = Instant.parse("2026-10-01T00:00:00Z");
  public static final Instant SEARCH_TO = Instant.parse("2026-10-03T00:00:00Z");

  private JdbcRepositoryTestFixture() {
    // immutable な定数と factory だけを公開し、Test 間で mutable な Fixture を共有しない。
  }

  /**
   * ヘッダ登録に使用する、2 明細と割引を含む新規注文を毎回生成する。
   *
   * <p>6,000 円 + 5,000 円 = 11,000 円、割引 1,100 円、送料 800 円、合計 10,700 円とする。 期待値を SUT
   * の計算結果から作らず、仕様から決めた値を明示する。 ここで金額計算や Service の業務フローを再検証するわけではない。
   */
  public static Order newOrder() {
    return Order.create(
        POSTAL_CODE,
        newOrderItems(),
        new PriceSummary(
            BigDecimal.valueOf(11_000),
            BigDecimal.valueOf(1_100),
            BigDecimal.valueOf(800),
            BigDecimal.valueOf(10_700)),
        CREATED_AT);
  }

  /**
   * 一括登録の内容・件数・順序を確認するため、異なる商品を含む未永続化の明細を生成する。
   *
   * <p>明細自身の id / orderId は null のままとする。 Repository は insertOrder() の戻り値を受け取り、その注文 ID
   * へ全明細を関連付ける必要がある。
   */
  public static List<OrderItem> newOrderItems() {
    return List.of(
        OrderItem.create(KEYBOARD_ID, "Keyboard", BigDecimal.valueOf(3_000), 2),
        OrderItem.create(MOUSE_ID, "Mouse", BigDecimal.valueOf(2_500), 2));
  }
}
