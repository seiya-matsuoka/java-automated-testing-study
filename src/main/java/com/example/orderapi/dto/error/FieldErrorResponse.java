package com.example.orderapi.dto.error;

/**
 * Request validation の 1 項目分のエラーを表す Response DTO。
 *
 * @param field エラー対象の field / parameter 名
 * @param message validation message
 */
public record FieldErrorResponse(String field, String message) {}
