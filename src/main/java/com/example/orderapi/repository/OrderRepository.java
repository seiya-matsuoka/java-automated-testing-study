package com.example.orderapi.repository;

import com.example.orderapi.domain.Order;
import com.example.orderapi.domain.OrderItem;
import com.example.orderapi.domain.OrderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Order と OrderItem の永続化処理に対する抽象化。
 *
 * <p>注文ヘッダと注文明細を 1 つの注文 Aggregate として扱い、 Service 層から SQL や JDBC の詳細を隠す。
 */
public interface OrderRepository {

  /**
   * 注文ヘッダを登録する。
   *
   * @param order 登録対象の Order
   * @return DB で採番された注文 ID
   */
  long insertOrder(Order order);

  /**
   * 指定した注文 ID に紐づく注文明細を一括登録する。
   *
   * @param orderId 注文 ID
   * @param items 登録対象の注文明細
   */
  void insertOrderItems(long orderId, List<OrderItem> items);

  /**
   * 注文 ID を指定して、注文明細を含む Order を取得する。
   *
   * @param id 注文 ID
   * @return Order。存在しない場合は empty
   */
  Optional<Order> findById(long id);

  /**
   * 任意の条件で注文を検索する。
   *
   * <p>{@code null} の条件は検索条件へ含めない。 結果は注文作成日時の降順で返す。
   *
   * @param status 注文状態。指定しない場合は {@code null}
   * @param createdFrom 作成日時の下限。指定しない場合は {@code null}
   * @param createdTo 作成日時の上限。指定しない場合は {@code null}
   * @return 条件に一致する注文一覧
   */
  List<Order> search(OrderStatus status, Instant createdFrom, Instant createdTo);

  /**
   * 現在状態が想定した状態と一致する場合だけ、注文状態とキャンセル日時を更新する。
   *
   * @param id 注文 ID
   * @param expectedStatus 更新前として想定する注文状態
   * @param newStatus 更新後の注文状態
   * @param cancelledAt キャンセル日時。キャンセル以外では {@code null}
   * @return 更新された行数。状態が変化済みまたは注文未存在の場合は 0
   */
  int updateStatus(long id, OrderStatus expectedStatus, OrderStatus newStatus, Instant cancelledAt);
}
