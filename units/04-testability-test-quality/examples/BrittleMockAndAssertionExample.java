package com.example.orderapi.unit04.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Brittle Test、過剰な Mock、実装詳細への依存、弱い Assertion を比較する補助教材。
 *
 * <p>実装が正しくても誤検知する Test（欠陥検出における False Positive）や、 実装が間違っていても通過する Test（欠陥検出における False
 * Negative）を読み比べる。 用語の真偽の基準が「Test の成否」か「欠陥の検出」かによって表現が揺れ得るため、具体的な挙動で理解する。
 */
public class BrittleMockAndAssertionExample {

  /**
   * 商品名の取得を外部境界に委ねる小さな Service。
   *
   * <p>Service が公開する仕様は「存在する商品名を返す」「未存在は unknown」であり、 Repository を何回呼ぶかという内部的な呼び出し構造は、この例の仕様に含めない。
   */
  interface ProductLookup {
    Optional<String> findName(long id);
  }

  static final class ProductLabelService {
    private final ProductLookup productLookup;

    ProductLabelService(ProductLookup productLookup) {
      this.productLookup = productLookup;
    }

    String label(long id) {
      return productLookup.findName(id).orElse("unknown");
    }
  }

  /**
   * 悪い例: 実際の Service を動かさず、SUT 自体を Mock にして期待値を Stub している。
   *
   * <p>Test が成功しても ProductLabelService の分岐や責務を一切確認できない。 Test Double を使う境界は、保証したい SUT の外側で決める必要がある。
   */
  @Nested
  @DisplayName("悪い例: SUT 自体を Mock")
  class OverMockedExamples {
    @Test
    void mockingTheSutOnlyConfirmsTheStub() {
      ProductLabelService service = mock(ProductLabelService.class);
      when(service.label(1L)).thenReturn("Standard Product");

      assertThat(service.label(1L)).isEqualTo("Standard Product");
    }
  }

  /**
   * 悪い例: 商品名の仕様を確認せず、何らかの文字列が返ればよいという Assertion で終わる。
   *
   * <p>商品名が誤っていても Test は通るため、欠陥を検出できない False Negative の例となる。
   */
  @Nested
  @DisplayName("悪い例: 弱すぎる Assertion")
  class WeakAssertionExamples {
    @Test
    void notBlankDoesNotGuaranteeTheExpectedProductName() {
      ProductLookup lookup = mock(ProductLookup.class);
      when(lookup.findName(1L)).thenReturn(Optional.of("WRONG PRODUCT"));
      ProductLabelService service = new ProductLabelService(lookup);

      assertThat(service.label(1L)).isNotBlank();
    }
  }

  /**
   * 悪い例: Service の返却値は正しいのに、内部呼び出し回数への過剰な期待で Test が失敗する。
   *
   * <p>Repository が 2 回呼ばれるべきという仕様は存在しない。キャッシュ導入や処理整理により 内部 call が変化しただけで Test が壊れる場合、Brittle Test
   * / False Positive になり得る。
   */
  @Nested
  @DisplayName("悪い例: 実装詳細への過剰な verification")
  class BrittleVerificationExamples {
    @Test
    void callCountExpectationCanRejectCorrectBehavior() {
      ProductLookup lookup = mock(ProductLookup.class);
      when(lookup.findName(1L)).thenReturn(Optional.of("Standard Product"));
      ProductLabelService service = new ProductLabelService(lookup);

      assertThat(service.label(1L)).isEqualTo("Standard Product");

      // 意図的に不適切な検証: 実際には 1 回しか呼ばれないため、この Test は失敗する。
      verify(lookup, times(2)).findName(1L);
    }
  }

  /**
   * 改善例: 実際の SUT を動かし、利用者から観測できる仕様と期待値を検証する。
   *
   * <p>依存先だけを Mock にし、その戻り値を Test の Arrange として準備する。 仕様上重要ではない内部 call 数は検証せず、振る舞いが変わらない refactoring
   * を妨げない。
   */
  @Nested
  @DisplayName("改善例: SUT の振る舞いを検証")
  class BehaviorFocusedExamples {
    @Test
    void knownProduct_returnsTheExpectedLabel() {
      ProductLookup lookup = mock(ProductLookup.class);
      when(lookup.findName(1L)).thenReturn(Optional.of("Standard Product"));
      ProductLabelService service = new ProductLabelService(lookup);

      assertThat(service.label(1L)).isEqualTo("Standard Product");
    }

    @Test
    void missingProduct_returnsTheFallbackLabel() {
      ProductLookup lookup = mock(ProductLookup.class);
      when(lookup.findName(404L)).thenReturn(Optional.empty());
      ProductLabelService service = new ProductLabelService(lookup);

      assertThat(service.label(404L)).isEqualTo("unknown");
    }
  }
}
