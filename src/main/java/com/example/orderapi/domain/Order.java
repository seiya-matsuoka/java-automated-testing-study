package com.example.orderapi.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 注文全体を表す Domain Model。
 *
 * <p>注文状態、配送先、注文明細、金額、作成日時、キャンセル日時を保持する。 状態遷移の可否は {@link OrderStatus} のルールへ委譲し、 Order
 * からも操作単位で判定できるようにする。
 */
public class Order {

  private static final Pattern POSTAL_CODE_PATTERN = Pattern.compile("^[0-9]{7}$");

  private final Long id;
  private final OrderStatus status;
  private final String shippingPostalCode;
  private final List<OrderItem> items;
  private final PriceSummary priceSummary;
  private final Instant createdAt;
  private final Instant cancelledAt;

  /**
   * 新規注文を生成する。
   *
   * <p>新規注文の初期状態は必ず {@link OrderStatus#CREATED} とし、 永続化前のため Order ID は保持しない。
   *
   * @param shippingPostalCode 配送先郵便番号
   * @param items 注文明細
   * @param priceSummary 金額計算結果
   * @param createdAt 注文作成日時
   * @return 新規注文
   */
  public static Order create(
      String shippingPostalCode,
      List<OrderItem> items,
      PriceSummary priceSummary,
      Instant createdAt) {
    return new Order(
        null, OrderStatus.CREATED, shippingPostalCode, items, priceSummary, createdAt, null);
  }

  /**
   * 永続化済みデータから Order を復元する。
   *
   * @param id 注文 ID
   * @param status 注文状態
   * @param shippingPostalCode 配送先郵便番号
   * @param items 注文明細
   * @param priceSummary 金額計算結果
   * @param createdAt 注文作成日時
   * @param cancelledAt キャンセル日時
   * @return 永続化済みデータを表す Order
   */
  public static Order restore(
      Long id,
      OrderStatus status,
      String shippingPostalCode,
      List<OrderItem> items,
      PriceSummary priceSummary,
      Instant createdAt,
      Instant cancelledAt) {
    if (id == null || id <= 0) {
      throw new IllegalArgumentException("id must be positive");
    }
    return new Order(id, status, shippingPostalCode, items, priceSummary, createdAt, cancelledAt);
  }

  /**
   * Order の共通生成処理。
   *
   * <p>新規生成と永続化済みデータの復元の両方から利用し、 Order として成立するための Domain invariant を一か所で保証する。
   */
  private Order(
      Long id,
      OrderStatus status,
      String shippingPostalCode,
      List<OrderItem> items,
      PriceSummary priceSummary,
      Instant createdAt,
      Instant cancelledAt) {
    Objects.requireNonNull(status, "status must not be null");

    // 配送先郵便番号は API 入力だけでなく Domain Model 自体でも 7 桁数字に限定する。
    if (shippingPostalCode == null || !POSTAL_CODE_PATTERN.matcher(shippingPostalCode).matches()) {
      throw new IllegalArgumentException("shippingPostalCode must be 7 digits");
    }

    // 注文は最低 1 明細を持つという業務上の前提を Domain Model 側でも保証する。
    if (items == null || items.isEmpty()) {
      throw new IllegalArgumentException("items must not be empty");
    }
    if (items.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("items must not contain null");
    }

    Objects.requireNonNull(priceSummary, "priceSummary must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");

    // CANCELLED と cancelledAt の組み合わせを固定し、状態と日時の矛盾を持つ Order を生成させない。
    if (status == OrderStatus.CANCELLED && cancelledAt == null) {
      throw new IllegalArgumentException("cancelledAt is required for cancelled order");
    }
    if (status != OrderStatus.CANCELLED && cancelledAt != null) {
      throw new IllegalArgumentException("cancelledAt must be null when order is not cancelled");
    }

    this.id = id;
    this.status = status;
    this.shippingPostalCode = shippingPostalCode;

    // 呼び出し元が保持する List の変更によって Order 内部の明細が変化しないよう不変コピーを保持する。
    this.items = List.copyOf(items);

    this.priceSummary = priceSummary;
    this.createdAt = createdAt;
    this.cancelledAt = cancelledAt;
  }

  /**
   * 現在状態から注文確定が可能かを判定する。
   *
   * @return 注文確定可能な場合 {@code true}
   */
  public boolean canConfirm() {
    return status.canConfirm();
  }

  /**
   * 現在状態から発送済みへの変更が可能かを判定する。
   *
   * @return 発送済みへ変更可能な場合 {@code true}
   */
  public boolean canShip() {
    return status.canShip();
  }

  /**
   * 現在状態からキャンセルが可能かを判定する。
   *
   * @return キャンセル可能な場合 {@code true}
   */
  public boolean canCancel() {
    return status.canCancel();
  }

  public Long getId() {
    return id;
  }

  public OrderStatus getStatus() {
    return status;
  }

  public String getShippingPostalCode() {
    return shippingPostalCode;
  }

  public List<OrderItem> getItems() {
    return items;
  }

  public BigDecimal getSubtotal() {
    return priceSummary.getSubtotal();
  }

  public BigDecimal getDiscountAmount() {
    return priceSummary.getDiscountAmount();
  }

  public BigDecimal getShippingFee() {
    return priceSummary.getShippingFee();
  }

  public BigDecimal getTotalAmount() {
    return priceSummary.getTotalAmount();
  }

  public PriceSummary getPriceSummary() {
    return priceSummary;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getCancelledAt() {
    return cancelledAt;
  }
}
