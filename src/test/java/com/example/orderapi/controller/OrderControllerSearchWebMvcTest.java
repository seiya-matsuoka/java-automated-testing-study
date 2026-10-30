package com.example.orderapi.controller;

import static com.example.orderapi.support.OrderWebMvcTestFixture.order;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orderapi.domain.OrderStatus;
import com.example.orderapi.service.OrderService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 一覧 API の query parameter binding と HTTP error handling を Web 層で検証する。
 *
 * <p>カリキュラムの中心は注文作成・取得・状態更新の endpoint だが、既存 Controller の search() は enum / Instant の parameter 変換と
 * 400 を実際に学べる境界として利用する。
 */
@WebMvcTest(controllers = OrderController.class)
@DisplayName("Unit 05 - OrderController Query Parameter")
class OrderControllerSearchWebMvcTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private OrderService orderService;

  /**
   * OrderStatus と日時の文字列が Spring MVC によって enum / Instant に変換される。 一覧用 Response DTO は明細を含まないことも HTTP
   * JSON で確認する。
   */
  @Test
  @DisplayName("GET /api/orders は query parameter を Service に渡して一覧 JSON を返す")
  void search_bindsQueryParametersAndReturnsOrderSummary() throws Exception {
    Instant from = Instant.parse("2026-10-01T00:00:00Z");
    Instant to = Instant.parse("2026-10-31T23:59:59Z");
    when(orderService.search(OrderStatus.CONFIRMED, from, to))
        .thenReturn(List.of(order(OrderStatus.CONFIRMED)));

    mockMvc
        .perform(
            get("/api/orders")
                .param("status", "CONFIRMED")
                .param("createdFrom", "2026-10-01T00:00:00Z")
                .param("createdTo", "2026-10-31T23:59:59Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(100))
        .andExpect(jsonPath("$[0].status").value("CONFIRMED"))
        .andExpect(jsonPath("$[0].totalAmount").value(6800))
        .andExpect(jsonPath("$[0].items").doesNotExist());

    verify(orderService).search(OrderStatus.CONFIRMED, from, to);
  }

  /** 未指定の optional parameter は null として Service に伝わる。 */
  @Test
  @DisplayName("query parameter 未指定時は Service に null を渡す")
  void search_withoutParametersPassesNulls() throws Exception {
    when(orderService.search(null, null, null)).thenReturn(List.of());

    mockMvc
        .perform(get("/api/orders"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());

    verify(orderService).search(null, null, null);
  }

  /** enum conversion が失敗した場合、Controller から Service へ到達しない。 */
  @Test
  @DisplayName("未定義 status は 400 / VALIDATION_ERROR")
  void search_whenStatusInvalid_returns400() throws Exception {
    mockMvc
        .perform(get("/api/orders").param("status", "NOT_A_STATUS"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.message").value("request parameter is invalid"))
        .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("status")));

    verifyNoInteractions(orderService);
  }

  /** ISO-8601 へ変換できない日時も既存 Exception Handler で 400 に変換する。 */
  @Test
  @DisplayName("不正な createdFrom は 400 / VALIDATION_ERROR")
  void search_whenDateTimeInvalid_returns400() throws Exception {
    mockMvc
        .perform(get("/api/orders").param("createdFrom", "yesterday"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("createdFrom")));

    verifyNoInteractions(orderService);
  }

  /** 複数 parameter を合わせた業務整合性は Service 側が判定し、Advice が 400 に変換する。 */
  @Test
  @DisplayName("Service の IllegalArgumentException は 400 と request field error")
  void search_whenServiceRejectsDateRange_returns400() throws Exception {
    Instant from = Instant.parse("2026-10-31T00:00:00Z");
    Instant to = Instant.parse("2026-10-01T00:00:00Z");
    when(orderService.search(null, from, to))
        .thenThrow(
            new IllegalArgumentException("createdFrom must be before or equal to createdTo"));

    mockMvc
        .perform(
            get("/api/orders")
                .param("createdFrom", "2026-10-31T00:00:00Z")
                .param("createdTo", "2026-10-01T00:00:00Z"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.message").value("request validation failed"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("request"));

    verify(orderService).search(null, from, to);
  }
}
