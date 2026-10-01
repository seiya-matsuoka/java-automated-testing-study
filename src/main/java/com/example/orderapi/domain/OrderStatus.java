package com.example.orderapi.domain;

/**
 * 注文のライフサイクル上の状態を表す enum。
 *
 * <p>状態遷移の可否を enum 自身に持たせ、状態ごとのルールを一か所へ集約する。 状態と操作の組み合わせを明示できるため、Decision Table や Test Matrix
 * と対応しやすい構造となる。
 */
public enum OrderStatus {
  CREATED,
  CONFIRMED,
  SHIPPED,
  CANCELLED;

  /**
   * 注文確定が可能かを判定する。
   *
   * @return {@code CREATED} の場合 {@code true}
   */
  public boolean canConfirm() {
    return this == CREATED;
  }

  /**
   * 発送済みへの変更が可能かを判定する。
   *
   * @return {@code CONFIRMED} の場合 {@code true}
   */
  public boolean canShip() {
    return this == CONFIRMED;
  }

  /**
   * 注文キャンセルが可能かを判定する。
   *
   * @return {@code CREATED} または {@code CONFIRMED} の場合 {@code true}
   */
  public boolean canCancel() {
    return this == CREATED || this == CONFIRMED;
  }
}
