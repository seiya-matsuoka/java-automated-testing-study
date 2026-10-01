package com.example.orderapi.service;

import com.example.orderapi.client.ShippingFeeClient;
import com.example.orderapi.domain.Order;
import com.example.orderapi.domain.OrderItem;
import com.example.orderapi.domain.OrderStatus;
import com.example.orderapi.domain.PriceSummary;
import com.example.orderapi.domain.Product;
import com.example.orderapi.exception.DuplicateOrderItemException;
import com.example.orderapi.exception.InsufficientStockException;
import com.example.orderapi.exception.InvalidOrderStatusTransitionException;
import com.example.orderapi.exception.OrderNotFoundException;
import com.example.orderapi.exception.ProductInactiveException;
import com.example.orderapi.exception.ProductNotFoundException;
import com.example.orderapi.exception.ShippingFeeServiceException;
import com.example.orderapi.repository.OrderRepository;
import com.example.orderapi.repository.ProductRepository;
import com.example.orderapi.service.model.CreateOrderCommand;
import com.example.orderapi.service.model.CreateOrderItemCommand;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注文に関する Application Service。
 *
 * <p>注文作成、状態遷移、在庫更新、送料取得など複数の Domain / Repository / Client を 組み合わせる処理順序を管理する。 個々の金額計算や状態遷移ルールは
 * Domain 側へ委譲し、 Service はユースケース全体の調整と Transaction Boundary を担う。
 */
@Service
public class OrderService {

  private final ProductRepository productRepository;
  private final OrderRepository orderRepository;
  private final ShippingFeeClient shippingFeeClient;
  private final Clock clock;

  /**
   * Spring / DB / HTTP に依存しない純粋な金額計算ロジック。
   *
   * <p>外部状態を持たない stateless な計算クラスのため、 Service 内で生成して業務計算だけを委譲する。
   */
  private final OrderPriceCalculator orderPriceCalculator = new OrderPriceCalculator();

  public OrderService(
      ProductRepository productRepository,
      OrderRepository orderRepository,
      ShippingFeeClient shippingFeeClient,
      Clock clock) {
    this.productRepository = productRepository;
    this.orderRepository = orderRepository;
    this.shippingFeeClient = shippingFeeClient;
    this.clock = clock;
  }

  /**
   * 注文を作成する。
   *
   * <p>同一商品の重複、商品存在、販売状態、在庫を先に検証し、 外部 Shipping Fee API から送料を取得した後で DB 更新を開始する。 注文ヘッダ、注文明細、在庫減算は同一
   * Transaction 内で更新する。
   *
   * @param command 注文作成 Command
   * @return 永続化後の Order
   */
  @Transactional
  public Order createOrder(CreateOrderCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("command must not be null");
    }

    validateNoDuplicateProducts(command.items());

    List<PreparedOrderLine> preparedLines = prepareOrderLines(command.items());
    List<OrderItem> orderItems = preparedLines.stream().map(PreparedOrderLine::orderItem).toList();

    BigDecimal subtotal =
        orderItems.stream().map(OrderItem::getLineAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

    int totalQuantity = orderItems.stream().mapToInt(OrderItem::getQuantity).sum();

    // 外部 API 呼び出しを DB 書き込みより先に完了させ、送料取得失敗時は永続化処理へ進まない。
    BigDecimal shippingFee =
        requestShippingFee(command.shippingPostalCode(), subtotal, totalQuantity);

    PriceSummary priceSummary = orderPriceCalculator.calculate(orderItems, shippingFee);
    Order order =
        Order.create(command.shippingPostalCode(), orderItems, priceSummary, Instant.now(clock));

    long orderId = orderRepository.insertOrder(order);
    orderRepository.insertOrderItems(orderId, orderItems);

    // 全商品検証後に在庫を更新し、途中で DB 更新が失敗した場合は Transaction 全体を rollback する。
    for (PreparedOrderLine preparedLine : preparedLines) {
      Product product = preparedLine.product();
      int newStockQuantity = product.getStockQuantity() - preparedLine.orderItem().getQuantity();

      int updatedRows = productRepository.updateStock(product.getId(), newStockQuantity);
      assertSingleRowUpdated(updatedRows, "product stock update");
    }

    return findRequiredOrder(orderId);
  }

  /**
   * 注文 ID を指定して Order を取得する。
   *
   * @param orderId 注文 ID
   * @return Order
   */
  @Transactional(readOnly = true)
  public Order findById(long orderId) {
    return findRequiredOrder(orderId);
  }

  /**
   * 任意条件で注文を検索する。
   *
   * @param status 注文状態。未指定の場合は {@code null}
   * @param createdFrom 作成日時下限。未指定の場合は {@code null}
   * @param createdTo 作成日時上限。未指定の場合は {@code null}
   * @return 条件に一致する注文一覧
   */
  @Transactional(readOnly = true)
  public List<Order> search(OrderStatus status, Instant createdFrom, Instant createdTo) {
    return orderRepository.search(status, createdFrom, createdTo);
  }

  /**
   * CREATED の注文を CONFIRMED へ遷移させる。
   *
   * @param orderId 注文 ID
   * @return 更新後の Order
   */
  @Transactional
  public Order confirm(long orderId) {
    Order order = findRequiredOrder(orderId);
    if (!order.canConfirm()) {
      throw new InvalidOrderStatusTransitionException(
          orderId, order.getStatus(), OrderStatus.CONFIRMED);
    }

    int updatedRows = orderRepository.updateStatus(orderId, OrderStatus.CONFIRMED, null);
    assertSingleRowUpdated(updatedRows, "order confirm");

    return findRequiredOrder(orderId);
  }

  /**
   * CONFIRMED の注文を SHIPPED へ遷移させる。
   *
   * @param orderId 注文 ID
   * @return 更新後の Order
   */
  @Transactional
  public Order ship(long orderId) {
    Order order = findRequiredOrder(orderId);
    if (!order.canShip()) {
      throw new InvalidOrderStatusTransitionException(
          orderId, order.getStatus(), OrderStatus.SHIPPED);
    }

    int updatedRows = orderRepository.updateStatus(orderId, OrderStatus.SHIPPED, null);
    assertSingleRowUpdated(updatedRows, "order ship");

    return findRequiredOrder(orderId);
  }

  /**
   * CREATED または CONFIRMED の注文をキャンセルする。
   *
   * <p>注文状態とキャンセル日時の更新、および全明細分の在庫復元を 同一 Transaction 内で実行する。 いずれかの DB 更新が失敗した場合は、途中までの在庫復元も含めて
   * rollback する。
   *
   * @param orderId 注文 ID
   * @return 更新後の Order
   */
  @Transactional
  public Order cancel(long orderId) {
    Order order = findRequiredOrder(orderId);
    if (!order.canCancel()) {
      throw new InvalidOrderStatusTransitionException(
          orderId, order.getStatus(), OrderStatus.CANCELLED);
    }

    // 注文時の各明細数量を現在在庫へ戻し、キャンセルによる在庫復元を行う。
    for (OrderItem item : order.getItems()) {
      Product product = findRequiredProduct(item.getProductId());
      int restoredStockQuantity = Math.addExact(product.getStockQuantity(), item.getQuantity());

      int updatedRows = productRepository.updateStock(product.getId(), restoredStockQuantity);
      assertSingleRowUpdated(updatedRows, "product stock restore");
    }

    Instant cancelledAt = Instant.now(clock);
    int updatedRows = orderRepository.updateStatus(orderId, OrderStatus.CANCELLED, cancelledAt);
    assertSingleRowUpdated(updatedRows, "order cancel");

    return findRequiredOrder(orderId);
  }

  /**
   * 1 注文内に同じ商品 ID が複数指定されていないことを検証する。
   *
   * <p>DB の UNIQUE constraint に到達する前に業務ルールとして検出し、 重複商品の意味を持つ業務例外へ変換する。
   */
  private void validateNoDuplicateProducts(List<CreateOrderItemCommand> items) {
    Set<Long> productIds = new HashSet<>();

    for (CreateOrderItemCommand item : items) {
      if (!productIds.add(item.productId())) {
        throw new DuplicateOrderItemException(item.productId());
      }
    }
  }

  /**
   * Command の各明細を検証し、在庫更新と注文登録に必要な情報を準備する。
   *
   * <p>Product の存在、販売状態、在庫を確認した後、 注文時点の商品名・単価を Snapshot とする OrderItem を生成する。
   */
  private List<PreparedOrderLine> prepareOrderLines(List<CreateOrderItemCommand> commandItems) {
    List<PreparedOrderLine> preparedLines = new ArrayList<>();

    for (CreateOrderItemCommand commandItem : commandItems) {
      Product product = findRequiredProduct(commandItem.productId());

      if (!product.isActive()) {
        throw new ProductInactiveException(product.getId());
      }
      if (!product.hasSufficientStock(commandItem.quantity())) {
        throw new InsufficientStockException(
            product.getId(), commandItem.quantity(), product.getStockQuantity());
      }

      OrderItem orderItem =
          OrderItem.create(
              product.getId(), product.getName(), product.getPrice(), commandItem.quantity());

      preparedLines.add(new PreparedOrderLine(product, orderItem));
    }

    return List.copyOf(preparedLines);
  }

  /** Shipping Fee API から送料を取得し、HTTP Client 側の失敗を業務例外へ変換する。 */
  private BigDecimal requestShippingFee(String postalCode, BigDecimal subtotal, int totalQuantity) {
    try {
      return shippingFeeClient.calculateShippingFee(postalCode, subtotal, totalQuantity);
    } catch (RuntimeException exception) {
      throw new ShippingFeeServiceException("failed to calculate shipping fee", exception);
    }
  }

  /** Product を取得し、未存在を Service 層の業務例外へ変換する。 */
  private Product findRequiredProduct(long productId) {
    return productRepository
        .findById(productId)
        .orElseThrow(() -> new ProductNotFoundException(productId));
  }

  /** Order を取得し、未存在を Service 層の業務例外へ変換する。 */
  private Order findRequiredOrder(long orderId) {
    return orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
  }

  /**
   * ID 指定の UPDATE が想定どおり 1 行だけを更新したことを検証する。
   *
   * <p>事前取得後に対象行が失われるなど、通常の業務フローでは成立しない Repository 更新結果を成功として扱わない。
   */
  private void assertSingleRowUpdated(int updatedRows, String operation) {
    if (updatedRows != 1) {
      throw new IllegalStateException(operation + " must affect exactly one row: " + updatedRows);
    }
  }

  /**
   * 注文作成前の検証結果を一時的に保持する内部 Service Model。
   *
   * <p>検証済み Product と、その Product から生成した OrderItem を対応付け、 DB 書き込み時の在庫減算で再取得を行わず同じ検証結果を利用する。
   */
  private record PreparedOrderLine(Product product, OrderItem orderItem) {}
}
