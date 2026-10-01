package com.example.orderapi.controller;

import com.example.orderapi.domain.Order;
import com.example.orderapi.domain.OrderStatus;
import com.example.orderapi.dto.order.CreateOrderRequest;
import com.example.orderapi.dto.order.OrderResponse;
import com.example.orderapi.dto.order.OrderSummaryResponse;
import com.example.orderapi.service.OrderService;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Order REST API の HTTP endpoint を提供する Controller。
 *
 * <p>Web 層では Request Validation、Service Command への変換、 HTTP Status / Header / Response DTO
 * の組み立てを担当する。 注文の業務判断や Transaction は {@link OrderService} へ委譲する。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

  private final OrderService orderService;

  public OrderController(OrderService orderService) {
    this.orderService = orderService;
  }

  /**
   * 新しい注文を作成する。
   *
   * <p>作成成功時は HTTP 201 を返し、 {@code Location} Header へ作成された注文詳細 URI を設定する。
   *
   * @param request 注文作成 Request
   * @return 作成済み注文と Location Header
   */
  @PostMapping
  public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
    Order createdOrder = orderService.createOrder(request.toCommand());

    // 現在の POST endpoint を基準に、作成された Resource の URI を組み立てる。
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(createdOrder.getId())
            .toUri();

    return ResponseEntity.created(location).body(OrderResponse.from(createdOrder));
  }

  /**
   * 注文 ID を指定して注文詳細を取得する。
   *
   * @param id 注文 ID
   * @return 注文詳細
   */
  @GetMapping("/{id}")
  public OrderResponse findById(@PathVariable long id) {
    return OrderResponse.from(orderService.findById(id));
  }

  /**
   * 任意条件で注文一覧を検索する。
   *
   * <p>query parameter が未指定の場合は対応する条件を適用しない。 日時は ISO-8601 形式を受け付ける。
   *
   * @param status 注文状態
   * @param createdFrom 作成日時の下限
   * @param createdTo 作成日時の上限
   * @return 条件に一致する注文一覧
   */
  @GetMapping
  public List<OrderSummaryResponse> search(
      @RequestParam(required = false) OrderStatus status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant createdFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant createdTo) {
    return orderService.search(status, createdFrom, createdTo).stream()
        .map(OrderSummaryResponse::from)
        .toList();
  }

  /**
   * CREATED の注文を CONFIRMED へ変更する。
   *
   * @param id 注文 ID
   * @return 更新後の注文
   */
  @PatchMapping("/{id}/confirm")
  public OrderResponse confirm(@PathVariable long id) {
    return OrderResponse.from(orderService.confirm(id));
  }

  /**
   * CONFIRMED の注文を SHIPPED へ変更する。
   *
   * @param id 注文 ID
   * @return 更新後の注文
   */
  @PatchMapping("/{id}/ship")
  public OrderResponse ship(@PathVariable long id) {
    return OrderResponse.from(orderService.ship(id));
  }

  /**
   * CREATED または CONFIRMED の注文を CANCELLED へ変更する。
   *
   * @param id 注文 ID
   * @return 更新後の注文
   */
  @PatchMapping("/{id}/cancel")
  public OrderResponse cancel(@PathVariable long id) {
    return OrderResponse.from(orderService.cancel(id));
  }
}
