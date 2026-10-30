package com.example.orderapi.controller;

import static com.example.orderapi.support.OrderWebMvcTestFixture.ORDER_ID;
import static com.example.orderapi.support.OrderWebMvcTestFixture.order;
import static com.example.orderapi.support.OrderWebMvcTestFixture.validCreateRequestJson;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orderapi.domain.Order;
import com.example.orderapi.domain.OrderStatus;
import com.example.orderapi.service.OrderService;
import com.example.orderapi.service.model.CreateOrderCommand;
import com.example.orderapi.service.model.CreateOrderItemCommand;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OrderController の HTTP 正常系を Spring MVC Test Slice で検証する。
 *
 * <p>MockMvc は Mock HTTP request を DispatcherServlet へ渡し、mapping・request body 変換・ response
 * serialization・HTTP status と header を Web 層として通す。 OrderService は Spring Test Context に登録する
 * MockitoBean に置き換える。 Repository / DB / 外部 HTTP API は起動しない。
 */
@WebMvcTest(controllers = OrderController.class)
@DisplayName("Unit 05 - OrderController Web 層の正常系")
class OrderControllerWebMvcTest {

  @Autowired private MockMvc mockMvc;

  // Spring Context で Controller に注入される Service Bean を置き換える。
  // Unit 03 の @Mock と異なり、Spring の DI に組み込まれる点に注目する。
  @MockitoBean private OrderService orderService;

  /**
   * POST で作成が成功した場合、HTTP 201・Location header・Response JSON を確認する。 Service に渡す Command が Request DTO
   * から正しく変換されたことも保証する。
   */
  @Test
  @DisplayName("POST /api/orders は 201 と Location と注文 JSON を返す")
  void create_returnsCreatedLocationAndOrderJson() throws Exception {
    // Arrange: Web 層の外側にある Service の結果だけを固定する。
    Order createdOrder = order(OrderStatus.CREATED);
    CreateOrderCommand expectedCommand =
        new CreateOrderCommand("1234567", List.of(new CreateOrderItemCommand(10L, 2)));
    when(orderService.createOrder(expectedCommand)).thenReturn(createdOrder);

    // Act / Assert: MVC の Request Mapping、Validation、JSON 変換、Status、Header を通す。
    var result =
        mockMvc
            .perform(
                post("/api/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .content(validCreateRequestJson()))
            .andExpect(status().isCreated())
            .andExpect(header().string(HttpHeaders.LOCATION, endsWith("/api/orders/100")))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id").value(100))
            .andExpect(jsonPath("$.status").value("CREATED"))
            .andExpect(jsonPath("$.shippingPostalCode").value("1234567"))
            .andExpect(jsonPath("$.items[0].productName").value("Keyboard"))
            .andExpect(jsonPath("$.subtotal").value(6000))
            .andExpect(jsonPath("$.shippingFee").value(800))
            .andExpect(jsonPath("$.totalAmount").value(6800))
            .andReturn();

    // JSONassert は JSON document 全体を比較する補助的手段。
    // LENIENT は追加の field を許容するため、重要な公開 field に絞った確認として使う。
    JSONAssert.assertEquals(
        """
        {"id":100,"status":"CREATED","items":[{"productId":10,"quantity":2}]}
        """,
        result.getResponse().getContentAsString(),
        JSONCompareMode.LENIENT);
    verify(orderService).createOrder(expectedCommand);
  }

  /** 注文 ID の PathVariable と、Domain からの Response DTO 変換を確認する。 */
  @Test
  @DisplayName("GET /api/orders/{id} は 200 と注文詳細 JSON を返す")
  void findById_returnsOrderDetails() throws Exception {
    when(orderService.findById(ORDER_ID)).thenReturn(order(OrderStatus.CONFIRMED));

    mockMvc
        .perform(get("/api/orders/{id}", ORDER_ID).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(100))
        .andExpect(jsonPath("$.status").value("CONFIRMED"))
        .andExpect(jsonPath("$.createdAt").value("2026-10-01T00:00:00Z"))
        .andExpect(jsonPath("$.items[0].lineAmount").value(6000))
        .andExpect(jsonPath("$.cancelledAt").isEmpty());

    verify(orderService).findById(ORDER_ID);
  }

  /** confirm の endpoint、PATCH method と返却状態を検証する。業務上の状態遷移自体は再検証しない。 */
  @Test
  @DisplayName("PATCH /api/orders/{id}/confirm は CONFIRMED を返す")
  void confirm_returnsConfirmedOrder() throws Exception {
    when(orderService.confirm(ORDER_ID)).thenReturn(order(OrderStatus.CONFIRMED));

    mockMvc
        .perform(patch("/api/orders/{id}/confirm", ORDER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(100))
        .andExpect(jsonPath("$.status").value("CONFIRMED"));

    verify(orderService).confirm(ORDER_ID);
  }

  /** ship の endpoint と、成功した Service 応答の JSON への変換を検証する。 */
  @Test
  @DisplayName("PATCH /api/orders/{id}/ship は SHIPPED を返す")
  void ship_returnsShippedOrder() throws Exception {
    when(orderService.ship(ORDER_ID)).thenReturn(order(OrderStatus.SHIPPED));

    mockMvc
        .perform(patch("/api/orders/{id}/ship", ORDER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SHIPPED"))
        .andExpect(jsonPath("$.cancelledAt").isEmpty());

    verify(orderService).ship(ORDER_ID);
  }

  /** cancel の endpoint と、キャンセル日時を含む JSON response を検証する。 */
  @Test
  @DisplayName("PATCH /api/orders/{id}/cancel は CANCELLED と cancelledAt を返す")
  void cancel_returnsCancelledOrder() throws Exception {
    when(orderService.cancel(ORDER_ID)).thenReturn(order(OrderStatus.CANCELLED));

    mockMvc
        .perform(patch("/api/orders/{id}/cancel", ORDER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.cancelledAt").value("2026-10-02T12:00:00Z"));

    verify(orderService).cancel(ORDER_ID);
  }
}
