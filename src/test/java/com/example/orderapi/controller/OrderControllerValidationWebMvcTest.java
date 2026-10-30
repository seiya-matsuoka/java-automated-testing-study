package com.example.orderapi.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orderapi.service.OrderService;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Request DTO の Bean Validation と、不正な JSON の HTTP 400 応答を確認する。
 *
 * <p>Controller の @Valid と nested @Valid / type-use @NotNull を実際の MVC binding に通し、
 * GlobalExceptionHandler が fieldErrors を生成することを Test する。 Validation で拒否された場合は業務 Service
 * を呼び出さないことも確認する。
 */
@WebMvcTest(controllers = OrderController.class)
@DisplayName("Unit 05 - OrderController Request Validation")
class OrderControllerValidationWebMvcTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private OrderService orderService;

  /**
   * 異なる入力不備がすべて同じ Web 契約（400・VALIDATION_ERROR・fieldErrors）に変換される。 JSON 内容だけを切り替え、同じ HTTP
   * 処理経路を通して確認する。
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidOrderRequests")
  @DisplayName("Bean Validation の違反は fieldErrors を伴う HTTP 400")
  void create_whenBeanValidationFails_returnsFieldError(
      String caseName, String requestJson, String expectedField) throws Exception {
    mockMvc
        .perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(requestJson))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.message").value("request validation failed"))
        .andExpect(jsonPath("$.fieldErrors[*].field", hasItem(expectedField)));

    // Validation は Service より手前の境界なので、エラー時には実行されない。
    verifyNoInteractions(orderService);
  }

  /**
   * 単純な field だけでなく List 要素内部の制約・null 要素も対象にする。 Property Path は Spring Validation の実際の field
   * 名に対応させる。
   */
  private static Stream<Arguments> invalidOrderRequests() {
    return Stream.of(
        Arguments.of(
            "郵便番号が未指定",
            """
            {"items":[{"productId":10,"quantity":2}]}
            """,
            "shippingPostalCode"),
        Arguments.of(
            "郵便番号が 7 桁の数字ではない",
            """
            {"shippingPostalCode":"123-4567","items":[{"productId":10,"quantity":2}]}
            """,
            "shippingPostalCode"),
        Arguments.of(
            "注文明細が空",
            """
            {"shippingPostalCode":"1234567","items":[]}
            """,
            "items"),
        Arguments.of(
            "注文明細 List が未指定",
            """
            {"shippingPostalCode":"1234567"}
            """,
            "items"),
        Arguments.of(
            "注文明細に null 要素が混在",
            """
            {"shippingPostalCode":"1234567","items":[null]}
            """,
            "items[0]"),
        Arguments.of(
            "明細の Product ID が未指定",
            """
            {"shippingPostalCode":"1234567","items":[{"quantity":2}]}
            """,
            "items[0].productId"),
        Arguments.of(
            "明細の数量が 0",
            """
            {"shippingPostalCode":"1234567","items":[{"productId":10,"quantity":0}]}
            """,
            "items[0].quantity"),
        Arguments.of(
            "明細の数量が 100",
            """
            {"shippingPostalCode":"1234567","items":[{"productId":10,"quantity":100}]}
            """,
            "items[0].quantity"));
  }

  /** JSON として解析不能な body は Bean Validation より前に失敗する。 */
  @Test
  @DisplayName("壊れた JSON は request body is invalid として HTTP 400")
  void create_whenJsonMalformed_returnsBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"shippingPostalCode\": \"1234567\", \"items\": ["))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.message").value("request body is invalid"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("request"));

    verifyNoInteractions(orderService);
  }

  /** JSON の型が DTO と合わない場合も deserialization error として扱う。 */
  @Test
  @DisplayName("数量が文字列になっている Request は HTTP 400")
  void create_whenJsonFieldHasWrongType_returnsBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"shippingPostalCode":"1234567","items":[{"productId":10,"quantity":"abc"}]}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("request"));

    verifyNoInteractions(orderService);
  }
}
