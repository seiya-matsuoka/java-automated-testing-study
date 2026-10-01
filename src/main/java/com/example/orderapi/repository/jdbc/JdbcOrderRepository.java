package com.example.orderapi.repository.jdbc;

import com.example.orderapi.domain.Order;
import com.example.orderapi.domain.OrderItem;
import com.example.orderapi.domain.OrderStatus;
import com.example.orderapi.domain.PriceSummary;
import com.example.orderapi.repository.OrderRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * {@link OrderRepository} の Spring JDBC 実装。
 *
 * <p>{@code orders} と {@code order_items} を明示 SQL で操作する。 取得時は JOIN 結果を注文 ID ごとに集約し、1 件の Order と複数の
 * OrderItem からなる Domain Model へ復元する。
 */
@Repository
public class JdbcOrderRepository implements OrderRepository {

  private static final String INSERT_ORDER_SQL =
      """
      INSERT INTO orders (
          status,
          shipping_postal_code,
          subtotal,
          discount_amount,
          shipping_fee,
          total_amount,
          created_at,
          cancelled_at
      )
      VALUES (
          :status,
          :shippingPostalCode,
          :subtotal,
          :discountAmount,
          :shippingFee,
          :totalAmount,
          :createdAt,
          :cancelledAt
      )
      """;

  private static final String INSERT_ORDER_ITEM_SQL =
      """
      INSERT INTO order_items (
          order_id,
          product_id,
          product_name,
          unit_price,
          quantity,
          line_amount
      )
      VALUES (
          :orderId,
          :productId,
          :productName,
          :unitPrice,
          :quantity,
          :lineAmount
      )
      """;

  /**
   * 注文ヘッダと注文明細を 1 回の SELECT で取得する共通部分。
   *
   * <p>Column 名の衝突を避けるため、Order / OrderItem の ID には alias を付ける。
   */
  private static final String ORDER_WITH_ITEMS_SELECT =
      """
      SELECT
          o.id AS order_id,
          o.status,
          o.shipping_postal_code,
          o.subtotal,
          o.discount_amount,
          o.shipping_fee,
          o.total_amount,
          o.created_at,
          o.cancelled_at,
          oi.id AS order_item_id,
          oi.product_id,
          oi.product_name,
          oi.unit_price,
          oi.quantity,
          oi.line_amount
      FROM orders o
      JOIN order_items oi
        ON oi.order_id = o.id
      """;

  /**
   * 状態遷移前として想定した状態が現在も維持されている場合だけ更新する SQL。
   *
   * <p>Service で状態を確認した後に別 Transaction が状態を変更しても、 古い状態を前提とした UPDATE が成功しないよう DB 更新時にも状態を条件へ含める。
   */
  private static final String UPDATE_STATUS_SQL =
      """
      UPDATE orders
      SET
          status = :newStatus,
          cancelled_at = :cancelledAt
      WHERE id = :id
        AND status = :expectedStatus
      """;

  private final NamedParameterJdbcTemplate jdbcTemplate;

  /**
   * Order Repository で使用する JDBC 操作を受け取る。
   *
   * @param jdbcTemplate 名前付きパラメータ対応の JDBC Template
   */
  public JdbcOrderRepository(NamedParameterJdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public long insertOrder(Order order) {
    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("status", order.getStatus().name())
            .addValue("shippingPostalCode", order.getShippingPostalCode())
            .addValue("subtotal", order.getSubtotal())
            .addValue("discountAmount", order.getDiscountAmount())
            .addValue("shippingFee", order.getShippingFee())
            .addValue("totalAmount", order.getTotalAmount())
            .addValue("createdAt", toOffsetDateTime(order.getCreatedAt()))
            .addValue(
                "cancelledAt",
                toOffsetDateTime(order.getCancelledAt()),
                Types.TIMESTAMP_WITH_TIMEZONE);

    KeyHolder keyHolder = new GeneratedKeyHolder();

    jdbcTemplate.update(INSERT_ORDER_SQL, parameters, keyHolder, new String[] {"id"});

    Number generatedId = keyHolder.getKey();
    if (generatedId == null) {
      throw new IllegalStateException("generated order id was not returned");
    }

    return generatedId.longValue();
  }

  @Override
  public void insertOrderItems(long orderId, List<OrderItem> items) {
    if (orderId <= 0) {
      throw new IllegalArgumentException("orderId must be positive");
    }
    if (items == null || items.isEmpty()) {
      throw new IllegalArgumentException("items must not be empty");
    }

    SqlParameterSource[] batchParameters =
        items.stream()
            .map(item -> toOrderItemParameters(orderId, item))
            .toArray(SqlParameterSource[]::new);

    int[] updateCounts = jdbcTemplate.batchUpdate(INSERT_ORDER_ITEM_SQL, batchParameters);

    // 全明細が 1 行ずつ INSERT されたことを確認し、不完全な登録を Repository 内で検出する。
    for (int updateCount : updateCounts) {
      if (updateCount != 1) {
        throw new IllegalStateException("order item insert did not affect exactly one row");
      }
    }
  }

  @Override
  public Optional<Order> findById(long id) {
    if (id <= 0) {
      throw new IllegalArgumentException("id must be positive");
    }

    String sql =
        ORDER_WITH_ITEMS_SELECT
            + """
            WHERE o.id = :id
            ORDER BY oi.id
            """;

    List<Order> orders = queryOrders(sql, new MapSqlParameterSource("id", id));
    return orders.stream().findFirst();
  }

  @Override
  public List<Order> search(OrderStatus status, Instant createdFrom, Instant createdTo) {
    if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
      throw new IllegalArgumentException("createdFrom must not be after createdTo");
    }

    StringBuilder sql = new StringBuilder(ORDER_WITH_ITEMS_SELECT);
    MapSqlParameterSource parameters = new MapSqlParameterSource();
    List<String> conditions = new ArrayList<>();

    // null の検索条件は WHERE 句へ含めず、指定された条件だけを SQL に追加する。
    if (status != null) {
      conditions.add("o.status = :status");
      parameters.addValue("status", status.name());
    }
    if (createdFrom != null) {
      conditions.add("o.created_at >= :createdFrom");
      parameters.addValue("createdFrom", toOffsetDateTime(createdFrom));
    }
    if (createdTo != null) {
      conditions.add("o.created_at <= :createdTo");
      parameters.addValue("createdTo", toOffsetDateTime(createdTo));
    }

    if (!conditions.isEmpty()) {
      sql.append(" WHERE ").append(String.join(" AND ", conditions));
    }

    // Order の並び順を確定したうえで、同一 Order 内の明細も ID 順で安定させる。
    sql.append(" ORDER BY o.created_at DESC, o.id DESC, oi.id");

    return queryOrders(sql.toString(), parameters);
  }

  @Override
  public int updateStatus(
      long id, OrderStatus expectedStatus, OrderStatus newStatus, Instant cancelledAt) {
    if (id <= 0) {
      throw new IllegalArgumentException("id must be positive");
    }
    if (expectedStatus == null) {
      throw new IllegalArgumentException("expectedStatus must not be null");
    }
    if (newStatus == null) {
      throw new IllegalArgumentException("newStatus must not be null");
    }

    MapSqlParameterSource parameters =
        new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("expectedStatus", expectedStatus.name())
            .addValue("newStatus", newStatus.name())
            .addValue("cancelledAt", toOffsetDateTime(cancelledAt), Types.TIMESTAMP_WITH_TIMEZONE);

    return jdbcTemplate.update(UPDATE_STATUS_SQL, parameters);
  }

  /**
   * OrderItem 1 件を INSERT 用の名前付きパラメータへ変換する。
   *
   * <p>OrderItem 自体が保持する {@code orderId} ではなく、 注文ヘッダ INSERT 後に採番された ID を明示的に使用する。
   */
  private SqlParameterSource toOrderItemParameters(long orderId, OrderItem item) {
    if (item == null) {
      throw new IllegalArgumentException("item must not be null");
    }

    return new MapSqlParameterSource()
        .addValue("orderId", orderId)
        .addValue("productId", item.getProductId())
        .addValue("productName", item.getProductName())
        .addValue("unitPrice", item.getUnitPrice())
        .addValue("quantity", item.getQuantity())
        .addValue("lineAmount", item.getLineAmount());
  }

  /**
   * JOIN 結果を Order 単位へ集約して Domain Model を復元する。
   *
   * <p>1 件の Order に複数の OrderItem が紐づくため、 ResultSet の 1 行をそのまま 1 Order へ変換せず、Order ID ごとに一時集約する。
   */
  private List<Order> queryOrders(String sql, SqlParameterSource parameters) {
    return jdbcTemplate.query(
        sql,
        parameters,
        resultSet -> {
          Map<Long, OrderAccumulator> accumulators = new LinkedHashMap<>();

          while (resultSet.next()) {
            long orderId = resultSet.getLong("order_id");
            OrderAccumulator accumulator = accumulators.get(orderId);

            // JOIN によって同じ注文ヘッダが明細数だけ繰り返されるため、ヘッダは最初の 1 行だけ保持する。
            if (accumulator == null) {
              accumulator = createOrderAccumulator(resultSet);
              accumulators.put(orderId, accumulator);
            }

            accumulator.addItem(mapOrderItem(resultSet, orderId));
          }

          return accumulators.values().stream().map(OrderAccumulator::toOrder).toList();
        });
  }

  /** JOIN 結果の注文ヘッダ部分を一時集約用オブジェクトへ変換する。 */
  private OrderAccumulator createOrderAccumulator(ResultSet resultSet) throws SQLException {
    Instant cancelledAt = toInstant(resultSet.getObject("cancelled_at", OffsetDateTime.class));

    return new OrderAccumulator(
        resultSet.getLong("order_id"),
        OrderStatus.valueOf(resultSet.getString("status")),
        resultSet.getString("shipping_postal_code"),
        new PriceSummary(
            resultSet.getBigDecimal("subtotal"),
            resultSet.getBigDecimal("discount_amount"),
            resultSet.getBigDecimal("shipping_fee"),
            resultSet.getBigDecimal("total_amount")),
        toInstant(resultSet.getObject("created_at", OffsetDateTime.class)),
        cancelledAt);
  }

  /** JOIN 結果の注文明細部分を OrderItem へ変換する。 */
  private OrderItem mapOrderItem(ResultSet resultSet, long orderId) throws SQLException {
    return OrderItem.restore(
        resultSet.getLong("order_item_id"),
        orderId,
        resultSet.getLong("product_id"),
        resultSet.getString("product_name"),
        resultSet.getBigDecimal("unit_price"),
        resultSet.getInt("quantity"),
        resultSet.getBigDecimal("line_amount"));
  }

  /**
   * {@link Instant} を PostgreSQL の TIMESTAMPTZ と扱いやすい {@link OffsetDateTime} へ変換する。
   *
   * <p>アプリケーション内部では UTC の Instant を基準とし、 JDBC 境界で UTC offset を持つ値へ変換する。
   */
  private OffsetDateTime toOffsetDateTime(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  /** JDBC から取得した OffsetDateTime を Domain Model の Instant へ戻す。 */
  private Instant toInstant(OffsetDateTime offsetDateTime) {
    return offsetDateTime == null ? null : offsetDateTime.toInstant();
  }

  /**
   * JOIN 結果から 1 件の Order を組み立てるための一時集約オブジェクト。
   *
   * <p>ResultSet の読み取り中だけ mutable な明細 List を保持し、 最終的には {@link Order#restore(Long, OrderStatus,
   * String, List, PriceSummary, Instant, Instant)} で不変な Domain Model へ変換する。
   */
  private static final class OrderAccumulator {

    private final long id;
    private final OrderStatus status;
    private final String shippingPostalCode;
    private final PriceSummary priceSummary;
    private final Instant createdAt;
    private final Instant cancelledAt;
    private final List<OrderItem> items = new ArrayList<>();

    private OrderAccumulator(
        long id,
        OrderStatus status,
        String shippingPostalCode,
        PriceSummary priceSummary,
        Instant createdAt,
        Instant cancelledAt) {
      this.id = id;
      this.status = status;
      this.shippingPostalCode = shippingPostalCode;
      this.priceSummary = priceSummary;
      this.createdAt = createdAt;
      this.cancelledAt = cancelledAt;
    }

    private void addItem(OrderItem item) {
      items.add(item);
    }

    private Order toOrder() {
      return Order.restore(
          id, status, shippingPostalCode, items, priceSummary, createdAt, cancelledAt);
    }
  }
}
