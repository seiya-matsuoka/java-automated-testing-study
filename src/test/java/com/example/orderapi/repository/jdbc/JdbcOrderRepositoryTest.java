package com.example.orderapi.repository.jdbc;

import static com.example.orderapi.support.JdbcRepositoryTestFixture.CANCELLED_AT;
import static com.example.orderapi.support.JdbcRepositoryTestFixture.CREATED_AT;
import static com.example.orderapi.support.JdbcRepositoryTestFixture.CREATED_ORDER_ID;
import static com.example.orderapi.support.JdbcRepositoryTestFixture.KEYBOARD_ID;
import static com.example.orderapi.support.JdbcRepositoryTestFixture.MISSING_ID;
import static com.example.orderapi.support.JdbcRepositoryTestFixture.MOUSE_ID;
import static com.example.orderapi.support.JdbcRepositoryTestFixture.MULTI_ITEM_ORDER_ID;
import static com.example.orderapi.support.JdbcRepositoryTestFixture.POSTAL_CODE;
import static com.example.orderapi.support.JdbcRepositoryTestFixture.SEARCH_FROM;
import static com.example.orderapi.support.JdbcRepositoryTestFixture.SEARCH_TO;
import static com.example.orderapi.support.JdbcRepositoryTestFixture.newOrder;
import static com.example.orderapi.support.JdbcRepositoryTestFixture.newOrderItems;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.orderapi.domain.Order;
import com.example.orderapi.domain.OrderItem;
import com.example.orderapi.domain.OrderStatus;
import com.example.orderapi.support.PostgresJdbcTestConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/**
 * 注文 Repository の INSERT / JOIN / 条件検索 / UPDATE を実 PostgreSQL で確認する Test。
 *
 * <p>SQL Fixture が検索の前提を作り、登録テストは Java Fixture を入力とする。 登録結果の観測には独立した SELECT も利用し、SUT
 * の読み書きに同じ誤りがあっても成功する Test を避ける。 Controller、Service、送料 API は実行せず、Service の業務判断や Transaction
 * Boundary は保証しない。
 *
 * <p>Schema は共通の Container 起動時に作成済みとなる。@Sql の 2 Script は外部キーの親から順に実行し、 Fixture と Test の更新を Test
 * Transaction の Rollback で除去する。Test Class 間で DB を使い回してもデータは引き継がない。
 */
@JdbcTest(properties = "spring.sql.init.mode=never")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresJdbcTestConfiguration.class)
@Sql({"/sql/unit06/products.sql", "/sql/unit06/orders.sql"})
@DisplayName("Unit 06 - JdbcOrderRepository と PostgreSQL")
class JdbcOrderRepositoryTest {

  @Autowired private JdbcOrderRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  /**
   * 採番された ID と、ヘッダの全保存項目を直接 SQL で確認する。
   *
   * <p>insertOrder() の直後は明細が未登録なので、JOIN を使う findById() を検証用に呼ばない。 sequence の値は Test の Rollback
   * で戻らないため、「ID は 1」といった期待値を置かない。
   */
  @Test
  @DisplayName("注文ヘッダを登録し、採番 ID と全列を保存する")
  void insertOrder_persistsEveryHeaderColumnAndReturnsGeneratedId() {
    long id = repository.insertOrder(newOrder());

    assertThat(id).isPositive();
    Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM orders WHERE id = ?", id);
    assertThat(row.get("id")).isEqualTo(id);
    assertThat(row.get("status")).isEqualTo("CREATED");
    assertThat(row.get("shipping_postal_code")).isEqualTo(POSTAL_CODE);
    assertThat((BigDecimal) row.get("subtotal")).isEqualByComparingTo("11000");
    assertThat((BigDecimal) row.get("discount_amount")).isEqualByComparingTo("1100");
    assertThat((BigDecimal) row.get("shipping_fee")).isEqualByComparingTo("800");
    assertThat((BigDecimal) row.get("total_amount")).isEqualByComparingTo("10700");
    assertThat(instantColumn(id, "created_at")).isEqualTo(CREATED_AT);
    assertThat(row.get("cancelled_at")).isNull();
  }

  /**
   * TIMESTAMPTZ の nullable parameter は null だけでなく実時刻も保存できる必要がある。
   *
   * <p>キャンセル済み Order を Fixture として直接復元し、Service のキャンセル処理は経由しない。 入力の仮 ID は採番に使われず、INSERT
   * の戻り値で保存行を識別する。
   */
  @Test
  @DisplayName("キャンセル済みヘッダの日時も保存できる")
  void insertOrder_withCancelledOrder_persistsCancellationTimestamp() {
    Order input = newOrder();
    Order cancelled =
        Order.restore(
            MISSING_ID,
            OrderStatus.CANCELLED,
            input.getShippingPostalCode(),
            input.getItems(),
            input.getPriceSummary(),
            input.getCreatedAt(),
            CANCELLED_AT);

    long id = repository.insertOrder(cancelled);

    assertThat(id).isPositive().isNotEqualTo(MISSING_ID);
    assertThat(statusOf(id)).isEqualTo("CANCELLED");
    assertThat(instantColumn(id, "cancelled_at")).isEqualTo(CANCELLED_AT);
  }

  /**
   * 2 明細の全内容と、採番後の親 ID への関連付けを確認する。
   *
   * <p>入力明細は永続化前なので orderId を持たない。Repository に明示的に渡した ID が、 すべての明細の外部キーとなることを直接 SELECT で観測する。
   */
  @Test
  @DisplayName("複数明細を一括登録し、採番した注文 ID へ関連付ける")
  void insertOrderItems_persistsEveryLineUnderGeneratedOrderId() {
    long id = repository.insertOrder(newOrder());

    repository.insertOrderItems(id, newOrderItems());

    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList("SELECT * FROM order_items WHERE order_id = ? ORDER BY id", id);
    assertThat(rows).hasSize(2);
    assertThat(rows).extracting(row -> row.get("order_id")).containsExactly(id, id);
    assertThat(rows)
        .extracting(row -> row.get("product_id"))
        .containsExactly(KEYBOARD_ID, MOUSE_ID);
    assertThat(rows)
        .extracting(row -> row.get("product_name"))
        .containsExactly("Keyboard", "Mouse");
    assertThat(rows).extracting(row -> row.get("quantity")).containsExactly(2, 2);
    assertThat(rows)
        .allSatisfy(row -> assertThat(((Number) row.get("id")).longValue()).isPositive());
    assertThat((BigDecimal) rows.get(0).get("unit_price")).isEqualByComparingTo("3000");
    assertThat((BigDecimal) rows.get(0).get("line_amount")).isEqualByComparingTo("6000");
    assertThat((BigDecimal) rows.get(1).get("unit_price")).isEqualByComparingTo("2500");
    assertThat((BigDecimal) rows.get(1).get("line_amount")).isEqualByComparingTo("5000");
  }

  /**
   * JOIN の行数と注文数を区別し、ヘッダ 1 件と明細 2 件を正しく復元する。
   *
   * <p>Fixture の明細 INSERT 順は ID 順と異なる。Order / OrderItem の同名 ID column の alias、 金額、関連
   * ID、明細順序まで確認し、単に「取得できた」だけで終わらせない。
   */
  @Test
  @DisplayName("JOIN の複数行を 1 注文と順序付き明細へ復元する")
  void findById_aggregatesJoinedRowsAndMapsEveryColumn() {
    Order actual = repository.findById(MULTI_ITEM_ORDER_ID).orElseThrow();

    assertThat(actual.getId()).isEqualTo(MULTI_ITEM_ORDER_ID);
    assertThat(actual.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    assertThat(actual.getShippingPostalCode()).isEqualTo(POSTAL_CODE);
    assertThat(actual.getSubtotal()).isEqualByComparingTo("11000");
    assertThat(actual.getDiscountAmount()).isEqualByComparingTo("1100");
    assertThat(actual.getShippingFee()).isEqualByComparingTo("800");
    assertThat(actual.getTotalAmount()).isEqualByComparingTo("10700");
    assertThat(actual.getCreatedAt()).isEqualTo(Instant.parse("2026-10-02T00:00:00Z"));
    assertThat(actual.getCancelledAt()).isNull();
    assertThat(actual.getItems()).extracting(OrderItem::getId).containsExactly(20003L, 20004L);
    assertThat(actual.getItems())
        .extracting(OrderItem::getOrderId)
        .containsExactly(MULTI_ITEM_ORDER_ID, MULTI_ITEM_ORDER_ID);
    assertThat(actual.getItems())
        .extracting(OrderItem::getProductId)
        .containsExactly(KEYBOARD_ID, MOUSE_ID);
    assertThat(actual.getItems())
        .extracting(OrderItem::getProductName)
        .containsExactly("Keyboard", "Mouse");
    assertThat(actual.getItems()).extracting(OrderItem::getQuantity).containsExactly(2, 2);
    assertThat(actual.getItems().get(0).getUnitPrice()).isEqualByComparingTo("3000");
    assertThat(actual.getItems().get(0).getLineAmount()).isEqualByComparingTo("6000");
    assertThat(actual.getItems().get(1).getUnitPrice()).isEqualByComparingTo("2500");
    assertThat(actual.getItems().get(1).getLineAmount()).isEqualByComparingTo("5000");
  }

  /**
   * 4 状態の文字列を enum へ変換し、nullable な日時と UTC の Instant を復元する。
   *
   * <p>CANCELLED の Fixture は +09:00 で投入しているが、同じ瞬間を表す UTC 値を期待する。 この Test
   * は状態遷移の許可条件ではなく、保存済み状態の読み取りを確認する。
   */
  @ParameterizedTest(name = "注文 {0} の状態 {1} と日時を復元")
  @CsvSource({
    "10004, CREATED, 2026-10-02T00:00:00Z",
    "10003, CONFIRMED, 2026-10-02T00:00:00Z",
    "10005, SHIPPED, 2026-10-03T00:00:00Z",
    "10006, CANCELLED, 2026-10-04T00:00:00Z"
  })
  void findById_restoresStatusAndTimestamps(long id, OrderStatus status, Instant createdAt) {
    Order actual = repository.findById(id).orElseThrow();

    assertThat(actual.getStatus()).isEqualTo(status);
    assertThat(actual.getCreatedAt()).isEqualTo(createdAt);
    if (status == OrderStatus.CANCELLED) {
      assertThat(actual.getCancelledAt()).isEqualTo(CANCELLED_AT);
    } else {
      assertThat(actual.getCancelledAt()).isNull();
    }
  }

  /** 現在の商品情報を変更しても、保存済み明細の商品名と単価は注文時点の Snapshot のままとする。 */
  @Test
  @DisplayName("明細は商品の現在値ではなく注文時点の商品名・単価を返す")
  void findById_preservesOrderedProductSnapshot() {
    jdbcTemplate.update(
        "UPDATE products SET name = ?, price = ? WHERE id = ?",
        "Renamed Keyboard",
        BigDecimal.valueOf(9_000),
        KEYBOARD_ID);

    OrderItem actual = repository.findById(MULTI_ITEM_ORDER_ID).orElseThrow().getItems().get(0);

    assertThat(actual.getProductName()).isEqualTo("Keyboard");
    assertThat(actual.getUnitPrice()).isEqualByComparingTo("3000");
    assertThat(actual.getLineAmount()).isEqualByComparingTo("6000");
  }

  /** 未存在の注文は空 Optional。HTTP 404 への変換はこの Repository の責務ではない。 */
  @Test
  @DisplayName("存在しない注文 ID は Optional.empty を返す")
  void findById_whenMissing_returnsEmptyOptional() {
    assertThat(repository.findById(MISSING_ID)).isEmpty();
  }

  /** SQL を実行する前の ID 検証も、実 DB を使用する契約の入口として代表値で確認する。 */
  @ParameterizedTest(name = "注文 ID {0} は拒否")
  @ValueSource(longs = {0, -1})
  void findById_whenIdNotPositive_rejectsInput(long id) {
    assertThatThrownBy(() -> repository.findById(id))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("id must be positive");
  }

  /**
   * 3 つの任意条件の指定有無を Test Matrix として確認する。
   *
   * <p>期待する ID 一覧を Fixture と仕様から明示し、SUT の検索結果から期待値を生成しない。 無条件ケースでは作成日時降順・同時刻の ID
   * 降順を確認し、複数明細による注文の重複も検出する。
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("searchConditions")
  void search_appliesOnlySpecifiedConditionsAndKeepsOrder(
      String caseName, OrderStatus status, Instant from, Instant to, List<Long> expectedIds) {
    List<Order> actual = repository.search(status, from, to);

    assertThat(actual).extracting(Order::getId).containsExactlyElementsOf(expectedIds);
    // JOIN 集約は search() の経路でも必要となる。該当注文の明細が欠落していないことを確認する。
    actual.stream()
        .filter(order -> order.getId() == MULTI_ITEM_ORDER_ID)
        .forEach(order -> assertThat(order.getItems()).hasSize(2));
  }

  /**
   * CONFIRMED が範囲の前・中・後にある Fixture に対し、8 通りの条件を供給する。
   *
   * <p>status の有無だけでなく、下限や上限を付け忘れた場合も期待一覧が変わる構成とする。 null は「SQL parameter に null
   * を比較させる」のではなく、「条件自体を付けない」という入力となる。
   */
  static Stream<Arguments> searchConditions() {
    return Stream.of(
        Arguments.of(
            "条件なし",
            null,
            null,
            null,
            List.of(10007L, 10006L, 10005L, 10004L, 10003L, 10002L, 10001L)),
        Arguments.of(
            "status のみ",
            OrderStatus.CONFIRMED,
            null,
            null,
            List.of(10007L, 10003L, 10002L, 10001L)),
        Arguments.of(
            "下限のみ",
            null,
            SEARCH_FROM,
            null,
            List.of(10007L, 10006L, 10005L, 10004L, 10003L, 10002L)),
        Arguments.of(
            "上限のみ", null, null, SEARCH_TO, List.of(10005L, 10004L, 10003L, 10002L, 10001L)),
        Arguments.of(
            "status と下限",
            OrderStatus.CONFIRMED,
            SEARCH_FROM,
            null,
            List.of(10007L, 10003L, 10002L)),
        Arguments.of(
            "status と上限", OrderStatus.CONFIRMED, null, SEARCH_TO, List.of(10003L, 10002L, 10001L)),
        Arguments.of(
            "下限と上限", null, SEARCH_FROM, SEARCH_TO, List.of(10005L, 10004L, 10003L, 10002L)),
        Arguments.of(
            "全条件", OrderStatus.CONFIRMED, SEARCH_FROM, SEARCH_TO, List.of(10003L, 10002L)));
  }

  /** 下限・上限はともにその瞬間を含む。両端が等しい範囲も有効として確認する。 */
  @ParameterizedTest(name = "日時 {0} を両端に指定すると注文 {1}")
  @CsvSource({"2026-10-01T00:00:00Z, 10002", "2026-10-03T00:00:00Z, 10005"})
  void search_includesBothDateBoundaries(Instant boundary, long expectedId) {
    assertThat(repository.search(null, boundary, boundary))
        .extracting(Order::getId)
        .containsExactly(expectedId);
  }

  /** 有効な検索条件でも該当注文がない場合は、空の結果となる。 */
  @Test
  @DisplayName("指定した状態と期間に該当しない場合は空 List")
  void search_whenNoOrderMatches_returnsEmptyList() {
    assertThat(repository.search(OrderStatus.CANCELLED, SEARCH_FROM, SEARCH_TO)).isEmpty();
  }

  /**
   * 元データがない場合も有効な検索となる。
   *
   * <p>method の @Sql は class の Script 指定を置き換えるため、この Test では商品だけを準備する。 別 Test の DELETE
   * や実行順序に頼らず、注文のない前提を Test 自身で決定する。
   */
  @Test
  @Sql("/sql/unit06/products.sql")
  @DisplayName("注文が 0 件なら無条件検索も空 List")
  void search_whenOrderTableIsEmpty_returnsEmptyList() {
    assertThat(repository.search(null, null, null)).isEmpty();
  }

  /** 日時範囲が逆転している場合は、誤った条件の SQL を実行せず入力エラーとする。 */
  @Test
  @DisplayName("検索下限が上限より後なら拒否する")
  void search_whenFromIsAfterTo_rejectsInput() {
    assertThatThrownBy(() -> repository.search(null, SEARCH_TO, SEARCH_FROM))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("createdFrom must not be after createdTo");
  }

  /**
   * 更新前状態が一致する行だけを更新し、キャンセル以外では日時を null にする。
   *
   * <p>この Repository は Service が決定した更新を保存する。状態遷移表全体の業務判断は再検証しない。
   */
  @ParameterizedTest(name = "注文 {0}: {1} → {2}")
  @CsvSource({"10004, CREATED, CONFIRMED", "10003, CONFIRMED, SHIPPED"})
  void updateStatus_whenExpectedStatusMatches_updatesOneRow(
      long id, OrderStatus expectedStatus, OrderStatus newStatus) {
    int updatedRows = repository.updateStatus(id, expectedStatus, newStatus, null);

    assertThat(updatedRows).isEqualTo(1);
    assertThat(statusOf(id)).isEqualTo(newStatus.name());
    assertThat(jdbcTemplate.queryForMap("SELECT cancelled_at FROM orders WHERE id = ?", id))
        .containsEntry("cancelled_at", null);
    assertThat(statusOf(10005L)).isEqualTo("SHIPPED");
  }

  /** キャンセル時は状態と日時を同じ UPDATE で保存する。時刻は固定値と完全一致させる。 */
  @Test
  @DisplayName("キャンセル状態と cancelled_at を保存する")
  void updateStatus_whenCancelled_persistsTimestamp() {
    int updatedRows =
        repository.updateStatus(
            MULTI_ITEM_ORDER_ID, OrderStatus.CONFIRMED, OrderStatus.CANCELLED, CANCELLED_AT);

    assertThat(updatedRows).isEqualTo(1);
    assertThat(statusOf(MULTI_ITEM_ORDER_ID)).isEqualTo("CANCELLED");
    assertThat(instantColumn(MULTI_ITEM_ORDER_ID, "cancelled_at")).isEqualTo(CANCELLED_AT);
    assertThat(instantColumn(MULTI_ITEM_ORDER_ID, "created_at"))
        .isEqualTo(Instant.parse("2026-10-02T00:00:00Z"));
  }

  /** 想定した状態が古い場合は上書きしない。SQL の expectedStatus 条件と 0 行更新を確認する。 */
  @Test
  @DisplayName("更新前状態が一致しなければ状態と日時を変更しない")
  void updateStatus_whenExpectedStatusDiffers_keepsDatabaseUnchanged() {
    int updatedRows =
        repository.updateStatus(
            CREATED_ORDER_ID, OrderStatus.CONFIRMED, OrderStatus.CANCELLED, CANCELLED_AT);

    assertThat(updatedRows).isZero();
    assertThat(statusOf(CREATED_ORDER_ID)).isEqualTo("CREATED");
    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT cancelled_at FROM orders WHERE id = ?", CREATED_ORDER_ID))
        .containsEntry("cancelled_at", null);
  }

  /** 未存在の注文も 0 行更新とし、INSERT や別行の変更として扱わない。 */
  @Test
  @DisplayName("存在しない注文の状態更新は 0 行更新")
  void updateStatus_whenOrderMissing_updatesNoRows() {
    int updatedRows =
        repository.updateStatus(MISSING_ID, OrderStatus.CREATED, OrderStatus.CONFIRMED, null);

    assertThat(updatedRows).isZero();
    assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Long.class))
        .isEqualTo(7L);
  }

  /** Repository の Row Mapping を利用せず、状態更新の実結果を確認する。 */
  private String statusOf(long id) {
    return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, id);
  }

  /**
   * JDBC 境界で TIMESTAMPTZ を読み、同じ瞬間を表す Instant として検証する。
   *
   * <p>column 名はこの Class 内の固定文字列だけを渡し、値の ID は SQL parameter として束縛する。
   */
  private Instant instantColumn(long id, String column) {
    return jdbcTemplate.queryForObject(
        "SELECT " + column + " FROM orders WHERE id = ?",
        (resultSet, rowNumber) -> resultSet.getObject(1, OffsetDateTime.class).toInstant(),
        id);
  }
}
