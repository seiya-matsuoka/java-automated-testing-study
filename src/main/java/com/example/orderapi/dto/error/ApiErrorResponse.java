package com.example.orderapi.dto.error;

import java.util.List;

/**
 * REST API 全体で共通利用する Error Response。
 *
 * <p>HTTP Status とは別に、Client がエラー種別を識別するための code と message を返却する。 Validation error の場合のみ
 * fieldErrors に詳細を格納し、それ以外では空 List とする。
 *
 * @param code API 内部で定義するエラーコード
 * @param message エラー内容
 * @param fieldErrors field 単位の validation error
 */
public record ApiErrorResponse(String code, String message, List<FieldErrorResponse> fieldErrors) {

  /**
   * Error Response を生成する。
   *
   * <p>呼び出し元の List 変更が response 内容へ影響しないよう不変コピーを保持する。
   */
  public ApiErrorResponse {
    fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
  }

  /**
   * field error を持たない Error Response を生成する。
   *
   * @param code エラーコード
   * @param message エラー内容
   * @return fieldErrors が空の Error Response
   */
  public static ApiErrorResponse of(String code, String message) {
    return new ApiErrorResponse(code, message, List.of());
  }
}
