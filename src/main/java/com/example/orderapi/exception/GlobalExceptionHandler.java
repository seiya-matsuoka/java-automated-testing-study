package com.example.orderapi.exception;

import com.example.orderapi.dto.error.ApiErrorResponse;
import com.example.orderapi.dto.error.FieldErrorResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Controller から発生した例外を共通 Error Response へ変換する Global Exception Handler。
 *
 * <p>Service 層の業務例外を HTTP Status へ対応付け、 Web 層以外へ HTTP の関心事を持ち込まない構造とする。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** Request Body の Bean Validation error を HTTP 400 へ変換する。 */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(
      MethodArgumentNotValidException exception) {
    List<FieldErrorResponse> fieldErrors =
        exception.getBindingResult().getFieldErrors().stream()
            .map(this::toFieldErrorResponse)
            .toList();

    return ResponseEntity.badRequest()
        .body(new ApiErrorResponse("VALIDATION_ERROR", "request validation failed", fieldErrors));
  }

  /** JSON 自体を読み取れない Request を HTTP 400 へ変換する。 */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiErrorResponse> handleHttpMessageNotReadable(
      HttpMessageNotReadableException exception) {
    return ResponseEntity.badRequest()
        .body(
            new ApiErrorResponse(
                "VALIDATION_ERROR",
                "request body is invalid",
                List.of(new FieldErrorResponse("request", "request body is invalid"))));
  }

  /**
   * query parameter などを要求された Java 型へ変換できない場合を HTTP 400 へ変換する。
   *
   * <p>OrderStatus の未定義値や ISO-8601 として解釈できない日時などが対象となる。
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiErrorResponse> handleMethodArgumentTypeMismatch(
      MethodArgumentTypeMismatchException exception) {
    return ResponseEntity.badRequest()
        .body(
            new ApiErrorResponse(
                "VALIDATION_ERROR",
                "request parameter is invalid",
                List.of(new FieldErrorResponse(exception.getName(), "invalid value"))));
  }

  /**
   * Service / Domain へ渡す入力条件が成立しない場合を HTTP 400 へ変換する。
   *
   * <p>例えば createdFrom が createdTo より後の場合など、 Bean Validation だけでは表現しない request 全体の整合性エラーを扱う。
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiErrorResponse> handleIllegalArgument(
      IllegalArgumentException exception) {
    return ResponseEntity.badRequest()
        .body(
            new ApiErrorResponse(
                "VALIDATION_ERROR",
                "request validation failed",
                List.of(new FieldErrorResponse("request", exception.getMessage()))));
  }

  @ExceptionHandler(DuplicateOrderItemException.class)
  public ResponseEntity<ApiErrorResponse> handleDuplicateOrderItem(
      DuplicateOrderItemException exception) {
    return error(HttpStatus.BAD_REQUEST, "DUPLICATE_ORDER_ITEM", exception.getMessage());
  }

  @ExceptionHandler(ProductNotFoundException.class)
  public ResponseEntity<ApiErrorResponse> handleProductNotFound(
      ProductNotFoundException exception) {
    return error(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", exception.getMessage());
  }

  @ExceptionHandler(OrderNotFoundException.class)
  public ResponseEntity<ApiErrorResponse> handleOrderNotFound(OrderNotFoundException exception) {
    return error(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", exception.getMessage());
  }

  @ExceptionHandler(ProductInactiveException.class)
  public ResponseEntity<ApiErrorResponse> handleProductInactive(
      ProductInactiveException exception) {
    return error(HttpStatus.CONFLICT, "PRODUCT_INACTIVE", exception.getMessage());
  }

  @ExceptionHandler(InsufficientStockException.class)
  public ResponseEntity<ApiErrorResponse> handleInsufficientStock(
      InsufficientStockException exception) {
    return error(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", exception.getMessage());
  }

  @ExceptionHandler(InvalidOrderStatusTransitionException.class)
  public ResponseEntity<ApiErrorResponse> handleInvalidOrderStatusTransition(
      InvalidOrderStatusTransitionException exception) {
    return error(HttpStatus.CONFLICT, "INVALID_ORDER_STATUS_TRANSITION", exception.getMessage());
  }

  /**
   * Shipping Fee API 利用失敗を HTTP 502 へ変換する。
   *
   * <p>Client には HTTP Client の詳細を返さず、 upstream service の利用失敗という意味だけを公開する。
   */
  @ExceptionHandler(ShippingFeeServiceException.class)
  public ResponseEntity<ApiErrorResponse> handleShippingFeeService(
      ShippingFeeServiceException exception) {
    return error(
        HttpStatus.BAD_GATEWAY,
        "SHIPPING_FEE_SERVICE_ERROR",
        "shipping fee service is unavailable");
  }

  /**
   * 明示的に扱っていない例外を HTTP 500 へ変換する。
   *
   * <p>予期しない内部情報を response message として公開せず、 詳細は server log に残す。
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception) {
    logger.error("Unexpected application error", exception);

    return error(
        HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "unexpected server error");
  }

  /** Spring Validation の FieldError を API 共通 DTO へ変換する。 */
  private FieldErrorResponse toFieldErrorResponse(FieldError fieldError) {
    String message =
        fieldError.getDefaultMessage() == null ? "invalid value" : fieldError.getDefaultMessage();

    return new FieldErrorResponse(fieldError.getField(), message);
  }

  /** field error を持たない共通 Error Response を生成する。 */
  private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
    return ResponseEntity.status(status).body(ApiErrorResponse.of(code, message));
  }
}
