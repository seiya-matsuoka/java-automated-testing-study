package com.example.orderapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.orderapi.client.ShippingFeeClient;
import com.example.orderapi.domain.Order;
import com.example.orderapi.domain.OrderItem;
import com.example.orderapi.domain.OrderStatus;
import com.example.orderapi.domain.PriceSummary;
import com.example.orderapi.domain.Product;
import com.example.orderapi.repository.OrderRepository;
import com.example.orderapi.repository.ProductRepository;
import com.example.orderapi.service.model.CreateOrderCommand;
import com.example.orderapi.service.model.CreateOrderItemCommand;
import com.example.orderapi.support.FakeProductRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * Production Code の Testability を、既存の OrderService の依存関係と時刻制御から確認する Unit Test。
 *
 * <p>Repository / Client / Clock を constructor parameter として受け取るため、Spring Context、PostgreSQL、HTTP
 * Server を動かさずに SUT を組み立てられる。Unit 03 の Mockito API 自体ではなく、依存先を差し替えられる設計と Test の再現性を主題とする。
 *
 * <p>すべての Test invocation で Fixture と Test Double を新規生成し、Test Class 間・実行順序間で mutable state を共有しない。
 */
@DisplayName("OrderService の Testability と決定的な Test")
class OrderServiceTestabilityTest {

  private static final long ORDER_ID = 100L;
  private static final long PRODUCT_ID = 1L;
  private static final String POSTAL_CODE = "1000001";
  private static final BigDecimal PRICE = BigDecimal.valueOf(3_000);
  private static final BigDecimal SHIPPING_FEE = BigDecimal.valueOf(800);
  private static final Instant CREATED_AT = Instant.parse("2026-10-01T00:00:00Z");
  private static final Instant CANCELLED_AT = Instant.parse("2026-10-02T00:00:00Z");

  /**
   * 実行時刻に依存せず、引数として渡した Clock の値が注文作成時刻へ反映されることを確認する。
   *
   * <p>同一の Test Case を異なる固定時刻で実行することで、実時刻の経過や Thread.sleep() に頼る必要がないことを示す。 ProductRepository には
   * real behavior を持つ Fake を利用し、在庫状態を Test 内に閉じ込める。
   *
   * @param instantText 今回の Test invocation で使う固定時刻
   */
  @ParameterizedTest(name = "Clock.fixed({0}) で注文を作成できる")
  @ValueSource(strings = {"2026-10-01T00:00:00Z", "2026-12-31T23:59:59Z"})
  void createOrder_withInjectedClock_usesExactlyTheGivenInstant(String instantText) {
    Instant expectedCreatedAt = Instant.parse(instantText);
    FakeProductRepository productRepository = newProductRepository();
    OrderRepository orderRepository = mock(OrderRepository.class);
    ShippingFeeClient shippingFeeClient = mock(ShippingFeeClient.class);
    Clock fixedClock = Clock.fixed(expectedCreatedAt, ZoneOffset.UTC);

    // 時刻の取得方法を SUT 内部へ埋め込まず、Production Code の constructor から差し替える。
    OrderService orderService =
        new OrderService(productRepository, orderRepository, shippingFeeClient, fixedClock);
    Order persistedOrder = persistedOrder(OrderStatus.CREATED, expectedCreatedAt, null);

    // SUT の外側の応答だけを制御する。SQL と HTTP の細部は今回の検証対象に含めない。
    when(shippingFeeClient.calculateShippingFee(POSTAL_CODE, BigDecimal.valueOf(6_000), 2))
        .thenReturn(SHIPPING_FEE);
    when(orderRepository.insertOrder(any(Order.class))).thenReturn(ORDER_ID);
    when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(persistedOrder));

    Order actual = orderService.createOrder(command());

    // 戻り値だけでなく、Repository へ保存しようとした Order の日時と金額を確認する。
    // これにより Repository の応答を Stub しただけで成功する弱い Test にならないようにする。
    ArgumentCaptor<Order> savedOrder = ArgumentCaptor.forClass(Order.class);
    verify(orderRepository).insertOrder(savedOrder.capture());

    assertThat(savedOrder.getValue())
        .satisfies(
            order -> {
              assertThat(order.getCreatedAt()).isEqualTo(expectedCreatedAt);
              assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
              assertThat(order.getSubtotal()).isEqualByComparingTo(BigDecimal.valueOf(6_000));
              assertThat(order.getShippingFee()).isEqualByComparingTo(SHIPPING_FEE);
              assertThat(order.getTotalAmount()).isEqualByComparingTo(BigDecimal.valueOf(6_800));
            });
    assertThat(actual).isSameAs(persistedOrder);
    assertThat(productRepository.currentStockQuantity(PRODUCT_ID)).isEqualTo(8);
  }

  /**
   * 同じ Test を繰り返しても他の実行による在庫変更を引き継がないことを確認する。
   *
   * <p>Repeatable とは単に @RepeatedTest を付けることではない。各実行で同じ初期状態を準備し、同じ入力から同じ結果を得ることが重要となる。 この Test では
   * static mutable Fixture を使わず、新しい Fake / Mock / SUT を毎回組み立てる。
   */
  @RepeatedTest(value = 3, name = "独立した Fixture での実行 {currentRepetition}/{totalRepetitions}")
  void createOrder_withFreshFixtures_isRepeatableWithoutSharedMutableState() {
    FakeProductRepository productRepository = newProductRepository();
    OrderRepository orderRepository = mock(OrderRepository.class);
    ShippingFeeClient shippingFeeClient = mock(ShippingFeeClient.class);
    Clock fixedClock = Clock.fixed(CREATED_AT, ZoneOffset.UTC);
    OrderService orderService =
        new OrderService(productRepository, orderRepository, shippingFeeClient, fixedClock);

    when(shippingFeeClient.calculateShippingFee(POSTAL_CODE, BigDecimal.valueOf(6_000), 2))
        .thenReturn(SHIPPING_FEE);
    when(orderRepository.insertOrder(any(Order.class))).thenReturn(ORDER_ID);
    when(orderRepository.findById(ORDER_ID))
        .thenReturn(Optional.of(persistedOrder(OrderStatus.CREATED, CREATED_AT, null)));

    Order actual = orderService.createOrder(command());

    // 同じ Fixture の初期在庫 10 から毎回 2 個減り、8 になることが成立条件となる。
    assertThat(actual.getCreatedAt()).isEqualTo(CREATED_AT);
    assertThat(actual.getStatus()).isEqualTo(OrderStatus.CREATED);
    assertThat(productRepository.currentStockQuantity(PRODUCT_ID)).isEqualTo(8);
  }

  /**
   * キャンセル日時を Test 自身で決定し、時刻経過を待たずに状態遷移の仕様を確認する。
   *
   * <p>Clock.fixed() は JDK の本物の Object であり、Mockito Mock にする必要はない。 Repository の更新結果だけ Mock
   * にすれば、キャンセル時刻と在庫復元の業務上の振る舞いを小さな Test Boundary で確認できる。
   */
  @Test
  @DisplayName("キャンセル時刻と在庫復元を固定 Clock で決定的に確認する")
  void cancel_withFixedClock_updatesTimestampAndRestoresStock() {
    FakeProductRepository productRepository = newProductRepository(8);
    OrderRepository orderRepository = mock(OrderRepository.class);
    ShippingFeeClient shippingFeeClient = mock(ShippingFeeClient.class);
    OrderService orderService =
        new OrderService(
            productRepository,
            orderRepository,
            shippingFeeClient,
            Clock.fixed(CANCELLED_AT, ZoneOffset.UTC));

    Order confirmedOrder = persistedOrder(OrderStatus.CONFIRMED, CREATED_AT, null);
    Order cancelledOrder = persistedOrder(OrderStatus.CANCELLED, CREATED_AT, CANCELLED_AT);
    when(orderRepository.findById(ORDER_ID))
        .thenReturn(Optional.of(confirmedOrder))
        .thenReturn(Optional.of(cancelledOrder));
    when(orderRepository.updateStatus(
            ORDER_ID, OrderStatus.CONFIRMED, OrderStatus.CANCELLED, CANCELLED_AT))
        .thenReturn(1);

    Order actual = orderService.cancel(ORDER_ID);

    // 時刻は Test に注入した固定値と一致する。実行時刻や sleep の許容誤差は不要となる。
    assertThat(actual.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(actual.getCancelledAt()).isEqualTo(CANCELLED_AT);
    verify(orderRepository)
        .updateStatus(ORDER_ID, OrderStatus.CONFIRMED, OrderStatus.CANCELLED, CANCELLED_AT);

    // 注文前在庫 10 → 確定注文後 8 の状態から、キャンセルで 2 個が復元される。
    // Fake は real behavior で在庫を更新するため、副作用を状態として確認できる。
    assertThat(productRepository.currentStockQuantity(PRODUCT_ID)).isEqualTo(10);
    verifyNoInteractions(shippingFeeClient);
  }

  /**
   * 不正な Command について、依存先の偶然の応答ではなく、Service 自身の入力検証に絞って確認する。
   *
   * <p>この Test に stubbing を大量に用意する必要はない。SUT が入力を即時に拒否する場合、外部境界へ進まないことも仕様となる。
   */
  @Test
  @DisplayName("null Command は依存先にアクセスする前に拒否する")
  void createOrder_withNullCommand_failsBeforeTouchingAnyDependency() {
    ProductRepository productRepository = mock(ProductRepository.class);
    OrderRepository orderRepository = mock(OrderRepository.class);
    ShippingFeeClient shippingFeeClient = mock(ShippingFeeClient.class);
    Clock clock = mock(Clock.class);
    OrderService orderService =
        new OrderService(productRepository, orderRepository, shippingFeeClient, clock);

    assertThatThrownBy(() -> orderService.createOrder(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("command must not be null");

    // 仕様上重要な「処理を先へ進めない」ことだけを確認し、実装の細部は拘束しない。
    verifyNoInteractions(productRepository, orderRepository, shippingFeeClient, clock);
  }

  /**
   * 各 Test invocation の初期状態を生成する。共有してよいのは immutable な定数だけとする。
   *
   * @return 在庫 10 個の商品を 1 件持つ新規 Fake Repository
   */
  private FakeProductRepository newProductRepository() {
    return newProductRepository(10);
  }

  /** Test Case に応じた在庫数で新規 Fake を用意し、変更可能な状態を共有しない。 */
  private FakeProductRepository newProductRepository(int stockQuantity) {
    Product product = new Product(PRODUCT_ID, "Standard Product", PRICE, stockQuantity, true);
    return new FakeProductRepository(List.of(product));
  }

  /** 注文作成という Act に渡す値を Test ごとに作成する。 */
  private CreateOrderCommand command() {
    return new CreateOrderCommand(POSTAL_CODE, List.of(new CreateOrderItemCommand(PRODUCT_ID, 2)));
  }

  /**
   * Repository の「更新済み Order を取得する」という境界で返す Fixture を生成する。
   *
   * <p>OrderService の Date/Time の設定を確認する Test と、Repository の永続化結果を返す Stub の役割を分離する。 実際の INSERT や
   * Transaction の正しさは、後続の DB Test で扱う。
   */
  private Order persistedOrder(OrderStatus status, Instant createdAt, Instant cancelledAt) {
    OrderItem item =
        OrderItem.restore(
            1L, ORDER_ID, PRODUCT_ID, "Standard Product", PRICE, 2, BigDecimal.valueOf(6_000));

    return Order.restore(
        ORDER_ID,
        status,
        POSTAL_CODE,
        List.of(item),
        new PriceSummary(
            BigDecimal.valueOf(6_000), BigDecimal.ZERO, SHIPPING_FEE, BigDecimal.valueOf(6_800)),
        createdAt,
        cancelledAt);
  }
}
