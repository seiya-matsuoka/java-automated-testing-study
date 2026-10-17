package com.example.orderapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.orderapi.domain.Product;
import com.example.orderapi.exception.ProductNotFoundException;
import com.example.orderapi.repository.ProductRepository;
import com.example.orderapi.support.FakeProductRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ProductService の Dependency Injection と責務分離を確認する Unit Test。
 *
 * <p>ProductService は Repository interface に依存し、JdbcProductRepository という具体実装や DB Connection
 * を生成しない。 そのため、Service が持つ「検索結果の返却と商品不存在の業務例外への変換」を DB なしで確認できる。
 *
 * <p>依存先をすべて Mock にするのではなく、Test の目的に応じて Fake と Mock を選ぶ。
 */
@DisplayName("ProductService の Dependency Injection と Test Isolation")
class ProductServiceTestabilityTest {

  private static final long PRODUCT_ID = 1L;

  /**
   * Repository interface への依存により、実 JDBC 実装を起動せず Service を Test できることを確認する。
   *
   * <p>Test の関心は ProductService が取得した Product を返すことにあるため、SQL や Repository method の内部処理を検証しない。
   */
  @Test
  @DisplayName("注入した Repository の検索結果を ProductService が返す")
  void findById_withInjectedRepository_returnsTheProduct() {
    ProductRepository repository = mock(ProductRepository.class);
    Product expected = product(PRODUCT_ID, "Standard Product");
    ProductService service = new ProductService(repository);

    when(repository.findById(PRODUCT_ID)).thenReturn(Optional.of(expected));

    Product actual = service.findById(PRODUCT_ID);

    // 呼び出し回数の細部より、取得された商品の identity / 属性という observable result を確認する。
    assertThat(actual).isSameAs(expected);
    assertThat(actual.getId()).isEqualTo(PRODUCT_ID);
    assertThat(actual.getName()).isEqualTo("Standard Product");
  }

  /**
   * Repository の「該当商品なし」という応答を、Service が業務例外へ変換する契約を確認する。
   *
   * <p>例外の検証だけが目的なので、追加の Product、Spring Context、DB 初期データは用意しない。 Test
   * の準備を必要最小限にすることで、失敗時の原因も絞り込みやすくする。
   */
  @Test
  @DisplayName("未存在の商品は Service の ProductNotFoundException になる")
  void findById_whenMissing_throwsDomainSpecificException() {
    ProductRepository repository = mock(ProductRepository.class);
    ProductService service = new ProductService(repository);
    when(repository.findById(404L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.findById(404L))
        .isInstanceOf(ProductNotFoundException.class)
        .hasMessage("product not found: 404")
        .satisfies(
            exception -> {
              ProductNotFoundException missing = (ProductNotFoundException) exception;
              assertThat(missing.getProductId()).isEqualTo(404L);
            });
  }

  /**
   * Fake Repository は本物に近い in-memory 検索動作を持ち、Mockito の stubbing なしに結果を返す。
   *
   * <p>Fixture の List と Fake は Test Method 内で生成する。ほかの Test が Fake の Map を更新してもこの Test に影響しない。
   * ProductService の役割は Repository が返した一覧を利用者へ渡すことであり、DB による sort/SQL は検証対象外とする。
   */
  @Test
  @DisplayName("独立した Fake Repository の初期状態から商品一覧を取得する")
  void findAll_withFreshFake_returnsAnIsolatedSnapshot() {
    Product first = product(1L, "Standard Product");
    Product second = product(2L, "Another Product");
    FakeProductRepository repository = new FakeProductRepository(List.of(first, second));
    ProductService service = new ProductService(repository);

    List<Product> actual = service.findAll();

    assertThat(actual).containsExactly(first, second);
    assertThat(actual).extracting(Product::getId).containsExactly(1L, 2L);
  }

  /** Test 用の Product は Test ごとに生成し、変更可能な状態を Test Class 全体で共有しない。 */
  private Product product(long id, String name) {
    return new Product(id, name, BigDecimal.valueOf(3_000), 10, true);
  }
}
