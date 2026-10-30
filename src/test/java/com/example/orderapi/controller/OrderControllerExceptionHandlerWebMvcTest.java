package com.example.orderapi.controller;

import static com.example.orderapi.support.OrderWebMvcTestFixture.ORDER_ID;
import static com.example.orderapi.support.OrderWebMvcTestFixture.validCreateRequestJson;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orderapi.domain.OrderStatus;
import com.example.orderapi.exception.DuplicateOrderItemException;
import com.example.orderapi.exception.InsufficientStockException;
import com.example.orderapi.exception.InvalidOrderStatusTransitionException;
import com.example.orderapi.exception.OrderNotFoundException;
import com.example.orderapi.exception.ProductInactiveException;
import com.example.orderapi.exception.ProductNotFoundException;
import com.example.orderapi.exception.ShippingFeeServiceException;
import com.example.orderapi.service.OrderService;
import com.example.orderapi.service.model.CreateOrderCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Service の業務例外を GlobalExceptionHandler が HTTP 契約に変換することを確認する。
 *
 * <p>Service の失敗を MockitoBean から再現し、HTTP status、公開 API error code、message、 fieldErrors
 * を検証する。業務例外の発生条件・DB の状態変化は Unit 05 の SUT ではない。
 */
@WebMvcTest(controllers = OrderController.class)
@DisplayName("Unit 05 - GlobalExceptionHandler と API Error Response")
class OrderControllerExceptionHandlerWebMvcTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private OrderService orderService;

  /** 注文 ID が存在しないという業務上の結果を HTTP 404 に変換する。 */
  @Test
  @DisplayName("OrderNotFoundException は 404 / ORDER_NOT_FOUND")
  void findById_whenOrderNotFound_returns404() throws Exception {
    when(orderService.findById(ORDER_ID)).thenThrow(new OrderNotFoundException(ORDER_ID));

    mockMvc
        .perform(get("/api/orders/{id}", ORDER_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("order not found: 100"))
        .andExpect(jsonPath("$.fieldErrors").isEmpty());

    verify(orderService).findById(ORDER_ID);
  }

  /** 同じ 404 でも商品不存在を注文不存在と混同しないことを確認する。 */
  @Test
  @DisplayName("ProductNotFoundException は 404 / PRODUCT_NOT_FOUND")
  void create_whenProductNotFound_returns404() throws Exception {
    when(orderService.createOrder(any(CreateOrderCommand.class)))
        .thenThrow(new ProductNotFoundException(10L));

    mockMvc
        .perform(
            post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateRequestJson()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("product not found: 10"));
  }

  /** 注文明細の重複という業務入力の問題を、Validation と同じ 400 でも別の code で表す。 */
  @Test
  @DisplayName("DuplicateOrderItemException は 400 / DUPLICATE_ORDER_ITEM")
  void create_whenDuplicateOrderItem_returns400() throws Exception {
    when(orderService.createOrder(any(CreateOrderCommand.class)))
        .thenThrow(new DuplicateOrderItemException(10L));

    mockMvc
        .perform(
            post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateRequestJson()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("DUPLICATE_ORDER_ITEM"))
        .andExpect(jsonPath("$.message").value("duplicate product in order: 10"))
        .andExpect(jsonPath("$.fieldErrors").isEmpty());
  }

  /** 注文確定を許可しない状態では、状態遷移の業務例外を HTTP 409 にする。 */
  @Test
  @DisplayName("InvalidOrderStatusTransitionException は 409 / INVALID_ORDER_STATUS_TRANSITION")
  void confirm_whenInvalidTransition_returns409() throws Exception {
    when(orderService.confirm(ORDER_ID))
        .thenThrow(
            new InvalidOrderStatusTransitionException(
                ORDER_ID, OrderStatus.SHIPPED, OrderStatus.CONFIRMED));

    mockMvc
        .perform(patch("/api/orders/{id}/confirm", ORDER_ID))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_ORDER_STATUS_TRANSITION"))
        .andExpect(
            jsonPath("$.message")
                .value(
                    "invalid order status transition: orderId=100, current=SHIPPED,"
                        + " target=CONFIRMED"))
        .andExpect(jsonPath("$.fieldErrors").isEmpty());
  }

  /** 在庫不足は 409。Service の在庫判断を再計算するのではなく Web 変換のみを見る。 */
  @Test
  @DisplayName("InsufficientStockException は 409 / INSUFFICIENT_STOCK")
  void create_whenStockInsufficient_returns409() throws Exception {
    when(orderService.createOrder(any(CreateOrderCommand.class)))
        .thenThrow(new InsufficientStockException(10L, 2, 1));

    mockMvc
        .perform(
            post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateRequestJson()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
        .andExpect(
            jsonPath("$.message")
                .value("insufficient stock: productId=10, requested=2, available=1"));
  }

  /** 販売停止商品も 409 だが、在庫不足とは異なる API error code になる。 */
  @Test
  @DisplayName("ProductInactiveException は 409 / PRODUCT_INACTIVE")
  void create_whenProductInactive_returns409() throws Exception {
    when(orderService.createOrder(any(CreateOrderCommand.class)))
        .thenThrow(new ProductInactiveException(10L));

    mockMvc
        .perform(
            post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validCreateRequestJson()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PRODUCT_INACTIVE"))
        .andExpect(jsonPath("$.message").value("product is inactive: 10"));
  }

  /**
   * 外部送料サービスの失敗は HTTP 502。下位の技術的な Exception や秘匿すべき詳細を 公開せず、既定の安全な message に変換することを JSONassert
   * で検証する。
   */
  @Test
  @DisplayName("ShippingFeeServiceException は 502 / SHIPPING_FEE_SERVICE_ERROR")
  void create_whenShippingFeeUnavailable_returns502WithoutInternalDetails() throws Exception {
    when(orderService.createOrder(any(CreateOrderCommand.class)))
        .thenThrow(
            new ShippingFeeServiceException(
                "internal upstream failure", new IllegalStateException("secret endpoint")));

    var result =
        mockMvc
            .perform(
                post("/api/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validCreateRequestJson()))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("SHIPPING_FEE_SERVICE_ERROR"))
            .andExpect(jsonPath("$.message").value("shipping fee service is unavailable"))
            .andExpect(jsonPath("$.fieldErrors").isEmpty())
            .andReturn();

    JSONAssert.assertEquals(
        """
        {
          "code":"SHIPPING_FEE_SERVICE_ERROR",
          "message":"shipping fee service is unavailable",
          "fieldErrors":[]
        }
        """,
        result.getResponse().getContentAsString(),
        JSONCompareMode.STRICT);
  }

  /** 未定義の内部例外は情報を隠した 500 を返し、通常の業務エラーと区別する。 */
  @Test
  @DisplayName("予期しない Exception は 500 / INTERNAL_SERVER_ERROR")
  void ship_whenUnexpectedFailure_returns500WithoutInternalDetails() throws Exception {
    when(orderService.ship(ORDER_ID)).thenThrow(new IllegalStateException("secret SQL detail"));

    mockMvc
        .perform(patch("/api/orders/{id}/ship", ORDER_ID))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
        .andExpect(jsonPath("$.message").value("unexpected server error"))
        .andExpect(jsonPath("$.fieldErrors").isEmpty());
  }
}
