package com.example.orderapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.orderapi.client.ShippingFeeClient;
import com.example.orderapi.domain.Order;
import com.example.orderapi.domain.OrderItem;
import com.example.orderapi.domain.OrderStatus;
import com.example.orderapi.domain.PriceSummary;
import com.example.orderapi.domain.Product;
import com.example.orderapi.exception.InvalidOrderStatusTransitionException;
import com.example.orderapi.exception.ProductNotFoundException;
import com.example.orderapi.exception.ShippingFeeServiceException;
import com.example.orderapi.repository.OrderRepository;
import com.example.orderapi.repository.ProductRepository;
import com.example.orderapi.service.model.CreateOrderCommand;
import com.example.orderapi.service.model.CreateOrderItemCommand;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito を利用して {@link OrderService} の依存境界を置き換える Unit Test。
 *
 * <p>SUT は OrderService とし、ProductRepository、OrderRepository、ShippingFeeClient、Clock は Test
 * 対象外の依存境界として Mockito の Mock に置き換える。 Repository の SQL、PostgreSQL、外部 HTTP API、Spring Context は起動しない。
 *
 * <p>Unit 03 では「Mock にできるから Mock にする」のではなく、 どこまでを今回の Test で本物として確認し、どこから先を制御可能な Test Double にするかを
 * 意識しながら stubbing / verification を読む。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService と Mockito")
class OrderServiceMockitoTest {

  private static final long ORDER_ID = 100L;
  private static final long PRODUCT_ID = 1L;
  private static final String POSTAL_CODE = "1000001";
  private static final Instant CREATED_AT = Instant.parse("2026-10-01T00:00:00Z");
  private static final Instant CANCELLED_AT = Instant.parse("2026-10-02T00:00:00Z");

  @Mock private ProductRepository productRepository;

  @Mock private OrderRepository orderRepository;

  @Mock private ShippingFeeClient shippingFeeClient;

  @Mock private Clock clock;

  /**
   * Mockito が上記 Mock を constructor parameter へ注入し、SUT を生成する。
   *
   * <p>Production Code に Test 専用 constructor を追加せず、 もともとの Constructor Injection をそのまま Test でも利用する。
   */
  @InjectMocks private OrderService orderService;

  @Captor private ArgumentCaptor<Order> orderCaptor;

  @Captor private ArgumentCaptor<List<OrderItem>> orderItemsCaptor;

  /**
   * 注文作成の正常系について、state verification と interaction verification を組み合わせて確認する。
   *
   * <p>Repository / Client の戻り値を stubbing し、OrderService 自身の業務フローだけを Unit Test する。 ArgumentCaptor
   * では、Repository へ保存しようとした Order / OrderItem が Service 内で正しく組み立てられているかを観測する。
   */
  @Test
  @DisplayName("注文作成では依存先を Stub し、保存内容と依存先呼び出しを検証できる")
  void createOrder_stubsDependenciesAndCapturesPersistenceArguments() {
    Product product =
        new Product(PRODUCT_ID, "Standard Product", BigDecimal.valueOf(3_000), 10, true);
    CreateOrderCommand command = createCommand(PRODUCT_ID, 2);
    Order persistedOrder = createPersistedOrder(OrderStatus.CREATED, CREATED_AT, null);

    // Stub: DB / 外部 API / 現在時刻を Test が必要とする決定的な値へ置き換える。
    when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
    when(shippingFeeClient.calculateShippingFee(POSTAL_CODE, BigDecimal.valueOf(6_000), 2))
        .thenReturn(BigDecimal.valueOf(800));
    when(clock.instant()).thenReturn(CREATED_AT);
    when(orderRepository.insertOrder(any(Order.class))).thenReturn(ORDER_ID);
    when(productRepository.decreaseStock(PRODUCT_ID, 2)).thenReturn(1);
    when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(persistedOrder));

    Order actual = orderService.createOrder(command);

    // State verification: SUT が返す結果を確認する。
    assertThat(actual).isSameAs(persistedOrder);

    // Captor: Service が Repository に渡した保存対象を、呼び出し後に取り出して確認する。
    verify(orderRepository).insertOrder(orderCaptor.capture());
    Order capturedOrder = orderCaptor.getValue();

    assertThat(capturedOrder)
        .satisfies(
            order -> {
              assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
              assertThat(order.getCreatedAt()).isEqualTo(CREATED_AT);
              assertThat(order.getSubtotal()).isEqualByComparingTo(BigDecimal.valueOf(6_000));
              assertThat(order.getShippingFee()).isEqualByComparingTo(BigDecimal.valueOf(800));
              assertThat(order.getTotalAmount()).isEqualByComparingTo(BigDecimal.valueOf(6_800));
            });

    // capture() は Argument Matcher として扱われるため、同じ呼び出し内の ID は eq() で揃える。
    verify(orderRepository).insertOrderItems(eq(ORDER_ID), orderItemsCaptor.capture());

    assertThat(orderItemsCaptor.getValue())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.getProductId()).isEqualTo(PRODUCT_ID);
              assertThat(item.getQuantity()).isEqualTo(2);
              assertThat(item.getLineAmount()).isEqualByComparingTo(BigDecimal.valueOf(6_000));
            });

    // Interaction verification: SUT が外部境界をどの引数で利用したかを確認する。
    verify(shippingFeeClient)
        .calculateShippingFee(eq(POSTAL_CODE), eq(BigDecimal.valueOf(6_000)), eq(2));
    verify(productRepository).decreaseStock(PRODUCT_ID, 2);
    verify(orderRepository).findById(ORDER_ID);
    verify(clock).instant();
  }

  /**
   * Product が存在しない場合は、その時点で処理を打ち切り、 Shipping Fee API や永続化処理へ進まないことを確認する。
   *
   * <p>「呼ばれたこと」だけでなく「呼ばれていないこと」も、 Service の処理順序という仕様を表す interaction verification となる。
   */
  @Test
  @DisplayName("商品が存在しない場合は外部 API と注文 Repository を呼ばない")
  void createOrder_whenProductDoesNotExist_stopsBeforeExternalAndPersistenceBoundaries() {
    CreateOrderCommand command = createCommand(PRODUCT_ID, 2);

    when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> orderService.createOrder(command))
        .isInstanceOf(ProductNotFoundException.class)
        .hasMessage("product not found: " + PRODUCT_ID);

    verify(productRepository).findById(PRODUCT_ID);
    verify(productRepository, never()).decreaseStock(anyLong(), anyInt());

    // Product 検証より後ろの境界には一切到達しない。
    verifyNoInteractions(shippingFeeClient, orderRepository, clock);
  }

  /**
   * Shipping Fee Client が RuntimeException を送出した場合、 Service が ShippingFeeServiceException へ変換し、DB
   * 書き込みへ進まないことを確認する。
   *
   * <p>例外の stubbing によって、実際の HTTP 障害を起こさずに依存先失敗を再現する。
   */
  @Test
  @DisplayName("送料取得失敗を Stub し、業務例外への変換と DB 未更新を確認する")
  void createOrder_whenShippingFeeClientFails_wrapsExceptionAndDoesNotWriteDatabase() {
    Product product =
        new Product(PRODUCT_ID, "Standard Product", BigDecimal.valueOf(3_000), 10, true);
    CreateOrderCommand command = createCommand(PRODUCT_ID, 2);
    RuntimeException clientFailure = new RuntimeException("shipping service unavailable");

    when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
    when(shippingFeeClient.calculateShippingFee(POSTAL_CODE, BigDecimal.valueOf(6_000), 2))
        .thenThrow(clientFailure);

    // 依存先で発生した同じ例外 instance が、Service が変換した例外の cause に保持されることを確認する。
    assertThatThrownBy(() -> orderService.createOrder(command))
        .isInstanceOf(ShippingFeeServiceException.class)
        .hasMessage("failed to calculate shipping fee")
        .cause()
        .isSameAs(clientFailure);

    verify(shippingFeeClient).calculateShippingFee(POSTAL_CODE, BigDecimal.valueOf(6_000), 2);
    verify(orderRepository, never()).insertOrder(any(Order.class));
    verify(orderRepository, never()).insertOrderItems(anyLong(), any());
    verify(productRepository, never()).decreaseStock(anyLong(), anyInt());
    verifyNoInteractions(clock);
  }

  /**
   * CREATED -> CONFIRMED の正常な状態変更について、 Repository の取得結果を順番に Stub し、更新 interaction を確認する。
   *
   * <p>同じ findById() に「更新前」「更新後」の順で異なる値を返させることで、 DB を使わず状態変更前後の Repository 応答を再現する。
   */
  @Test
  @DisplayName("注文確定では状態更新を1回実行し、更新後 Order を返す")
  void confirm_whenCreated_updatesStatusOnceAndReturnsUpdatedOrder() {
    Order createdOrder = createPersistedOrder(OrderStatus.CREATED, CREATED_AT, null);
    Order confirmedOrder = createPersistedOrder(OrderStatus.CONFIRMED, CREATED_AT, null);

    // 1 回目は更新前、2 回目は更新後の Repository 応答として返す。
    when(orderRepository.findById(ORDER_ID))
        .thenReturn(Optional.of(createdOrder))
        .thenReturn(Optional.of(confirmedOrder));
    when(orderRepository.updateStatus(ORDER_ID, OrderStatus.CREATED, OrderStatus.CONFIRMED, null))
        .thenReturn(1);

    Order actual = orderService.confirm(ORDER_ID);

    assertThat(actual).isSameAs(confirmedOrder);

    verify(orderRepository, times(2)).findById(ORDER_ID);
    verify(orderRepository)
        .updateStatus(ORDER_ID, OrderStatus.CREATED, OrderStatus.CONFIRMED, null);
    verifyNoInteractions(productRepository, shippingFeeClient, clock);
  }

  /**
   * キャンセル時に、状態更新と全明細分の在庫復元が行われることを確認する。
   *
   * <p>anyLong() / anyInt() で全体の呼び出し回数を確認しつつ、 具体的な商品 ID / 数量の verification も行う。 Clock を Stub することで
   * cancelledAt を非決定値にしない。
   */
  @Test
  @DisplayName("注文キャンセルでは全明細の在庫を復元し、固定時刻を cancelledAt に利用する")
  void cancel_whenConfirmed_restoresEveryItemAndUsesStubbedClock() {
    Order confirmedOrder = createConfirmedOrderWithTwoItems();
    Order cancelledOrder = createCancelledOrderWithTwoItems(CANCELLED_AT);

    // 1 回目はキャンセル前、2 回目はキャンセル後の Repository 応答として返す。
    when(orderRepository.findById(ORDER_ID))
        .thenReturn(Optional.of(confirmedOrder))
        .thenReturn(Optional.of(cancelledOrder));
    when(clock.instant()).thenReturn(CANCELLED_AT);
    when(orderRepository.updateStatus(
            ORDER_ID, OrderStatus.CONFIRMED, OrderStatus.CANCELLED, CANCELLED_AT))
        .thenReturn(1);
    when(productRepository.increaseStock(anyLong(), anyInt())).thenReturn(1);

    Order actual = orderService.cancel(ORDER_ID);

    assertThat(actual)
        .isSameAs(cancelledOrder)
        .satisfies(order -> assertThat(order.getCancelledAt()).isEqualTo(CANCELLED_AT));

    verify(orderRepository)
        .updateStatus(ORDER_ID, OrderStatus.CONFIRMED, OrderStatus.CANCELLED, CANCELLED_AT);

    verify(productRepository).increaseStock(1L, 2);
    verify(productRepository).increaseStock(2L, 1);
    verify(productRepository, times(2)).increaseStock(anyLong(), anyInt());

    verify(clock).instant();
    verify(orderRepository, times(2)).findById(ORDER_ID);
    verifyNoInteractions(shippingFeeClient);
  }

  /**
   * SHIPPED はキャンセル不可のため、状態更新や在庫復元を実行しないことを確認する。
   *
   * <p>不正状態で副作用を持つ Repository method が呼ばれないことは、 戻り値だけでは確認できないため interaction verification を利用する。
   */
  @Test
  @DisplayName("発送済み注文のキャンセルでは更新系 Repository を呼ばない")
  void cancel_whenShipped_rejectsBeforeUpdateAndStockRestore() {
    Order shippedOrder = createPersistedOrder(OrderStatus.SHIPPED, CREATED_AT, null);

    when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(shippedOrder));

    assertThatThrownBy(() -> orderService.cancel(ORDER_ID))
        .isInstanceOf(InvalidOrderStatusTransitionException.class)
        .satisfies(
            exception -> {
              InvalidOrderStatusTransitionException actual =
                  (InvalidOrderStatusTransitionException) exception;
              assertThat(actual.getOrderId()).isEqualTo(ORDER_ID);
              assertThat(actual.getCurrentStatus()).isEqualTo(OrderStatus.SHIPPED);
              assertThat(actual.getTargetStatus()).isEqualTo(OrderStatus.CANCELLED);
            });

    verify(orderRepository).findById(ORDER_ID);
    verify(orderRepository, never())
        .updateStatus(anyLong(), any(OrderStatus.class), any(OrderStatus.class), any());
    verify(productRepository, never()).increaseStock(anyLong(), anyInt());
    verifyNoInteractions(shippingFeeClient, clock);
  }

  /**
   * 注文作成 Test で使用する最小構成の Command を生成する。
   *
   * <p>Test Case ごとに productId / quantity だけを変えられるようにし、 配送先など今回の確認対象ではない値は共通 Fixture として固定する。
   */
  private CreateOrderCommand createCommand(long productId, int quantity) {
    return new CreateOrderCommand(
        POSTAL_CODE, List.of(new CreateOrderItemCommand(productId, quantity)));
  }

  /**
   * Repository から取得済みという前提の単一明細 Order を生成する。
   *
   * <p>状態遷移 Test では遷移処理そのものを使って前提状態を作らず、 {@code Order.restore(...)} で必要な status / timestamp
   * を直接与えることで、 SUT の確認対象と Fixture 準備を分離する。
   */
  private Order createPersistedOrder(OrderStatus status, Instant createdAt, Instant cancelledAt) {
    OrderItem item =
        OrderItem.restore(
            1L,
            ORDER_ID,
            PRODUCT_ID,
            "Standard Product",
            BigDecimal.valueOf(3_000),
            2,
            BigDecimal.valueOf(6_000));

    return Order.restore(
        ORDER_ID,
        status,
        POSTAL_CODE,
        List.of(item),
        new PriceSummary(
            BigDecimal.valueOf(6_000),
            BigDecimal.ZERO,
            BigDecimal.valueOf(800),
            BigDecimal.valueOf(6_800)),
        createdAt,
        cancelledAt);
  }

  /**
   * キャンセル正常系の事前状態として、在庫復元対象が 2 明細ある CONFIRMED Order を生成する。
   *
   * <p>1 明細だけでは「全明細分を復元する」という interaction を確認できないため、 異なる商品 ID / 数量を持つ 2 明細を Fixture として利用する。
   */
  private Order createConfirmedOrderWithTwoItems() {
    return Order.restore(
        ORDER_ID,
        OrderStatus.CONFIRMED,
        POSTAL_CODE,
        createTwoPersistedItems(),
        new PriceSummary(
            BigDecimal.valueOf(8_500),
            BigDecimal.ZERO,
            BigDecimal.valueOf(800),
            BigDecimal.valueOf(9_300)),
        CREATED_AT,
        null);
  }

  /**
   * キャンセル処理後に Repository から再取得される CANCELLED Order を生成する。
   *
   * <p>更新後の戻り値 Stub として利用し、固定した cancelledAt が 最終的な Order に反映されていることを state verification できるようにする。
   */
  private Order createCancelledOrderWithTwoItems(Instant cancelledAt) {
    return Order.restore(
        ORDER_ID,
        OrderStatus.CANCELLED,
        POSTAL_CODE,
        createTwoPersistedItems(),
        new PriceSummary(
            BigDecimal.valueOf(8_500),
            BigDecimal.ZERO,
            BigDecimal.valueOf(800),
            BigDecimal.valueOf(9_300)),
        CREATED_AT,
        cancelledAt);
  }

  /**
   * キャンセル時の在庫復元 interaction を確認するための 2 明細 Fixture を生成する。
   *
   * <p>Product 1 は 2 個、Product 2 は 1 個として、 {@code increaseStock(1L, 2)} / {@code increaseStock(2L,
   * 1)} の期待値と対応させる。
   */
  private List<OrderItem> createTwoPersistedItems() {
    return List.of(
        OrderItem.restore(
            1L, ORDER_ID, 1L, "Product A", BigDecimal.valueOf(3_000), 2, BigDecimal.valueOf(6_000)),
        OrderItem.restore(
            2L,
            ORDER_ID,
            2L,
            "Product B",
            BigDecimal.valueOf(2_500),
            1,
            BigDecimal.valueOf(2_500)));
  }
}
