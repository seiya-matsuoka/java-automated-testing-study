package com.example.orderapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.orderapi.client.ShippingFeeClient;
import com.example.orderapi.domain.Order;
import com.example.orderapi.domain.OrderItem;
import com.example.orderapi.domain.OrderStatus;
import com.example.orderapi.domain.PriceSummary;
import com.example.orderapi.domain.Product;
import com.example.orderapi.repository.OrderRepository;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Fake と Spy を OrderService の依存境界へ適用し、Mockito Mock との違いを読むための Unit Test。
 *
 * <p>ProductRepository は実際の in-memory 動作を持つ Fake を利用し、 さらに Mockito の Spy で包むことで「本物の method を実行しながら
 * interaction も観測する」例とする。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService と Fake / Spy")
class OrderServiceFakeAndSpyTest {

  private static final long ORDER_ID = 100L;
  private static final long PRODUCT_ID = 1L;
  private static final String POSTAL_CODE = "1000001";
  private static final Instant CREATED_AT = Instant.parse("2026-10-01T00:00:00Z");

  @Mock private OrderRepository orderRepository;

  @Mock private ShippingFeeClient shippingFeeClient;

  /**
   * Fake Repository の実際の状態変化を利用しつつ、Spy で interaction も確認する。
   *
   * <p>Mock ProductRepository なら decreaseStock() の結果を Test ごとに stubbing する必要があるが、 Fake
   * は在庫を内部状態として持ち、自身の実装で 10 -> 8 へ更新する。 Spy はその real behavior を維持したまま method 呼び出しを verify できる。
   */
  @Test
  @DisplayName("Spy は Fake の実動作を維持したまま Repository interaction を観測できる")
  void createOrder_spyOnFakeRepository_preservesRealBehaviorAndRecordsInteraction() {
    Product initialProduct =
        new Product(PRODUCT_ID, "Standard Product", BigDecimal.valueOf(3_000), 10, true);

    // spy() は Fake の real method を利用しながら、呼び出し履歴も記録できる。
    FakeProductRepository productRepositorySpy =
        spy(new FakeProductRepository(List.of(initialProduct)));

    Clock fixedClock = Clock.fixed(CREATED_AT, ZoneOffset.UTC);
    OrderService orderService =
        new OrderService(productRepositorySpy, orderRepository, shippingFeeClient, fixedClock);

    CreateOrderCommand command =
        new CreateOrderCommand(POSTAL_CODE, List.of(new CreateOrderItemCommand(PRODUCT_ID, 2)));

    Order persistedOrder = createPersistedOrder();

    when(shippingFeeClient.calculateShippingFee(POSTAL_CODE, BigDecimal.valueOf(6_000), 2))
        .thenReturn(BigDecimal.valueOf(800));
    when(orderRepository.insertOrder(any(Order.class))).thenReturn(ORDER_ID);
    when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(persistedOrder));

    Order actual = orderService.createOrder(command);

    assertThat(actual).isSameAs(persistedOrder);

    // Fake の real method が実行されたため、内部在庫そのものが 10 -> 8 に変化する。
    assertThat(productRepositorySpy.currentStockQuantity(PRODUCT_ID)).isEqualTo(8);

    // Spy なので、real behavior に加えて interaction verification も利用できる。
    verify(productRepositorySpy).findById(PRODUCT_ID);
    verify(productRepositorySpy).decreaseStock(PRODUCT_ID, 2);
  }

  private Order createPersistedOrder() {
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
        OrderStatus.CREATED,
        POSTAL_CODE,
        List.of(item),
        new PriceSummary(
            BigDecimal.valueOf(6_000),
            BigDecimal.ZERO,
            BigDecimal.valueOf(800),
            BigDecimal.valueOf(6_800)),
        CREATED_AT,
        null);
  }
}
