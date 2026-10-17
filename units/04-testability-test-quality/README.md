# 04. テストしやすい設計とテスト品質

## この Unit の目的

この Unit では、Test Code が成功することと、その Test が十分な品質を持つことは同じではないと理解する。  
Unit 03 では Test Double を利用して依存先を制御した。Unit 04 ではその一段前にある「Production Code がなぜ Test しやすいのか」と、一段後にある「作成した Test がなぜ信頼できるのか」を整理する。

メインアプリの `OrderService` と `ProductService` を利用して、Dependency Injection、`Clock` による時刻制御、Test ごとの Fixture 独立性を確認する。  
さらに、意図的に問題のある設計・Test を小さな補助教材として読み、Flaky Test、Brittle Test、Test Smell、実行順依存、過剰な Mock などを比較する。

重要なのは、Test を通すために Production Code を不自然に変更することでも、失敗した Test を Retry で隠すことでもない。  
**Testability を高める設計と、意味のある仕様を安定して検証する Test の両方**を理解することを目指す。

## 学習内容

### Unit 03 から Unit 04 への変化

Unit 03 では、`OrderService` を SUT とし、Repository、`ShippingFeeClient`、`Clock` を本物で利用するか Test Double に置き換えるかを判断した。  
Unit 04 では、「そもそもなぜ依存先を差し替えられるのか」「同じ Test を何度実行しても結果が変わらないのはなぜか」「Test が成功しても何を保証していない可能性があるか」を中心に考える。

```text
Production Code の設計
  ├─ 責務が分かれているか
  ├─ 外部依存を差し替えられるか
  └─ 時刻・乱数・共有状態を制御できるか
          ↓
Test Code の構成
  ├─ Test ごとに Fixture が独立しているか
  ├─ 同じ条件で同じ結果になるか
  └─ 仕様に基づく Assertion になっているか
          ↓
信頼できる Test
  ├─ 不具合を適切に検出する
  └─ 正しい変更を不必要に妨げない
```

この関係を、良い例を動かし、悪い例をコードリーディングで比較することで確認する。

### Testability

Testability は、対象の振る舞いを Test しやすい性質を指す。  
単に Test Method を短く書けるという意味ではなく、必要な初期状態を準備でき、入力や依存先を制御でき、結果を観測して、期待した振る舞いを判断できることに関係する。

例えば Test のために PostgreSQL、外部 HTTP API、現在時刻、複数 Test の実行順序を常に揃えなければならないと、Service 自身の振る舞いを小さな境界で確認しにくい。  
一方、`OrderService` は Repository、Client、`Clock` を constructor で受け取るため、それらを Test 内で制御して Service を直接実行できる。

```text
Test から入力を与えられる
＋
依存先の振る舞いを制御できる
＋
結果・副作用を観測できる
＋
必要な範囲だけを実行できる
```

これらは Testability を考えるための観点となる。ただし、Testability が高ければ Test Case の選び方や期待値が自動的に正しくなるわけではない。

### Dependency Injection

Dependency Injection（DI）は、ある Object が利用する依存先を、その Object の内部で固定的に生成する代わりに、外部から渡す設計上の考え方である。  
Unit 04 の主題は Spring の DI Container の使い方ではなく、**依存先を外から指定できる設計そのもの**となる。

`OrderService` は次の依存先を constructor parameter で受け取る。

```java
public OrderService(
    ProductRepository productRepository,
    OrderRepository orderRepository,
    ShippingFeeClient shippingFeeClient,
    Clock clock) {
  this.productRepository = productRepository;
  this.orderRepository = orderRepository;
  this.shippingFeeClient = shippingFeeClient;
  this.clock = clock;
}
```

Production では本物の実装を、Unit Test では Fake や Mock と固定 `Clock` を渡せる。  
`ProductService` も `ProductRepository` interface を受け取るため、`JdbcProductRepository` を自ら生成する必要がない。

DI は「何でも Mock にするため」の仕組みではない。依存先の選択を組み立て側へ委ね、Service の責務と依存先の構築責務を分ける効果がある。

### hard-coded dependency

hard-coded dependency は、利用する依存先や環境の取得方法が処理の内部へ固定され、呼び出し元や Test から差し替えにくい状態を指す。  
補助教材 `HardCodedReferenceGenerator` は `generate()` 内で次を直接利用する。

```java
Instant createdAt = Instant.now();
int suffix = new Random().nextInt(10_000);
String region = System.getProperty("shipping.region", "JP");
```

同じ `orderId` を渡しても、実時刻、乱数、JVM の System Property によって結果が変わり得る。  
Test の期待値を合わせるために実環境を操作するより、必要な値を依存先として注入する方が意図を表しやすい場合がある。

改善例の `InjectedReferenceGenerator` は `Clock`、`IntSupplier`、`region` を constructor で受け取る。  
これにより、参照番号の組み立て処理と、時刻・乱数・設定値の取得方法を分離できる。

### global state

global state は、複数の処理や Test から共通して参照できる状態を指す。  
JVM の System Property、共有される static field、アプリケーション全体から参照できる変更可能な設定などが例となる。

共有状態そのものが常に悪いわけではない。問題は、Test がその値に暗黙に依存していたり、ある Test の変更が別の Test へ漏れたりすることである。  
例えば補助教材の `System.getProperty("shipping.region", "JP")` は、Test 内の引数だけでは決まらない実行環境の状態を参照する。

```text
Test A が共有状態を変更
        ↓
Test B が同じ状態を参照
        ↓
単独実行と全体実行で結果が異なる
```

Test に必要な状態は、原則として Test 自身が明示的に準備・管理できる方が分かりやすい。

### static mutable state

`static mutable state` は、class 単位で共有され、かつ変更できる状態を指す。  
`static final` で宣言された値でも、参照先の Object が mutable なら中身は変更できる点に注意する。

補助教材 `SharedMutableStateAndOrderExample` では、次のような構成を悪い例としている。

```java
private static final Stock SHARED_STOCK = new Stock(10);
```

`SHARED_STOCK` という参照を別の Object へ再代入できなくても、`reserve(2)` によって内部の `quantity` は変化する。  
これを複数 Test で共有すると、前の Test が実行されたかどうかが次の Test の結果へ影響する。

一方、Unit 04 の通常の Test で `private static final Instant CREATED_AT` のように固定日時を共有することは、変更可能な在庫 Fixture を共有することとは異なる。  
**共有する値が実際に変更可能か**を判断する。

### 時刻への依存

現在時刻は、同じ処理を後から実行すると変化する非決定的な入力の代表である。  
Production Code が直接 `Instant.now()` を呼び出すと、Test からその値を正確に指定しにくくなる。

例えば「キャンセル日時が現在時刻に設定される」という仕様を、実時刻との誤差で検証すると、Test 実行時の遅延を考慮した許容幅が必要になる。  
しかし、許容幅が大きすぎると誤った実装を見逃し、狭すぎると正しい実装でも失敗しやすくなる。

Unit 04 では `OrderService` が `Instant.now(clock)` を利用する既存設計を読む。  
Test から時刻を決められることが重要であり、時間を進めるために待機する必要はない。

### `Clock` による時刻制御

Java の `Clock` は、時刻取得方法を表す JDK の Object である。  
`Instant.now(clock)` と書けば、実際のシステム時刻に限らず、呼び出し元から渡した `Clock` に従う。

```java
Instant expected = Instant.parse("2026-10-01T00:00:00Z");
Clock fixedClock = Clock.fixed(expected, ZoneOffset.UTC);

Instant actual = Instant.now(fixedClock);
```

`actual` は指定した `expected` と正確に一致する。  
`Clock.fixed(...)` は Mockito Mock ではなく、JDK の本物の Object でありながら Test に都合のよい決定的な動作を提供する。

Unit 04 の `OrderServiceTestabilityTest` では、注文作成の `createdAt` とキャンセルの `cancelledAt` をこの仕組みで制御する。  
時刻の制御方法と、その時刻が業務上正しく使われたかという検証を分けて理解する。

### random 値への依存

乱数も、固定の入力から毎回同じ値を得られない原因となる。  
`new Random()` を処理の内部で毎回生成し、その結果を業務用の識別子などに含めると、Test から期待する値を指定しにくい。

補助教材では乱数の取得を `IntSupplier` に置き換えている。

```java
InjectedReferenceGenerator generator =
    new InjectedReferenceGenerator(fixedClock, () -> 1234, "JP");
```

この例では `() -> 1234` が毎回同じ値を返すため、生成結果を正確に検証できる。  
実務では乱数の品質が重要な処理もあるため、Random Object、乱数生成用の interface など、目的に応じて依存先を選ぶ。Unit 04 は乱数アルゴリズム自体を Test する Unit ではない。

### 外部状態への依存

外部状態には DB、HTTP API、File System、環境変数、System Property、実時刻など、SUT の引数だけでは結果を決められない要素が含まれる。  
これらは Production では必要な場合があるが、Unit Test に毎回すべてを含める必要はない。

```text
確認したい対象: OrderService の業務フロー

外側の依存先:
ProductRepository → PostgreSQL
OrderRepository   → PostgreSQL
ShippingFeeClient → HTTP API
Clock             → 時刻
```

今回の Test は外部依存を Fake、Mock、固定 `Clock` に置き換え、Service の振る舞いに集中する。  
ただし、Test Double での成功は JDBC、PostgreSQL、HTTP request / response の正しさまで保証しない。それらは後続 Unit の別の Test Boundary で扱う。

### 責務分離

責務分離は、異なる目的や変更理由を持つ処理を適切に分ける考え方である。  
例えば「注文を作成する」「商品を検索する」「SQL を発行する」「送料 API に HTTP request を送る」「現在時刻を取得する」は、すべて同じ役割ではない。

`OrderService` は注文の業務フローを調整し、`ProductRepository` と `OrderRepository` は永続化の境界を、`ShippingFeeClient` は送料取得の境界を担当する。  
`ProductService` は Repository の検索結果を返し、商品がないときには業務例外へ変換する。

これらが分かれているため、Test の目的に応じて必要な範囲だけを動かせる。  
ただし、責務分離とは class を無制限に小さく増やすことではない。**異なる関心を必要な単位で分けること**が目的となる。

### テストしづらいコードから設計上の問題を見つける

Test の準備が極端に難しい場合、Test Code の書き方だけでなく Production Code の依存関係や責務に原因がある可能性を考える。  
例えば、単純な文字列生成の Test に実 HTTP Server や実時刻の待機が必要なら、外部状態の取得が処理へ強く結び付いていないかを確認する。

```text
Test の準備に時間がかかる
→ 不必要に広い依存先を起動していないか

同じ入力でも結果が変わる
→ 時刻・乱数・共有状態へ暗黙に依存していないか

特定の Test の後でしか成功しない
→ Fixture と mutable state が独立しているか

Mock が大量に必要になる
→ SUT の責務や依存先が広すぎないか
```

これらは設計を確認するための兆候であり、必ず設計不良と断定できるものではない。  
まず Test Boundary と保証したい振る舞いを整理し、必要なら Production Code 側の責務や依存の受け渡し方を見直す。

### Isolation

Isolation は、Test が確認したい振る舞いに集中できるよう、不要な影響を分離する考え方である。  
ここでは、外部依存による影響と、他 Test が持つ状態による影響の両方を考える。

`OrderServiceTestabilityTest` では、各 invocation で新しい `FakeProductRepository`、`OrderRepository` Mock、`ShippingFeeClient` Mock、`OrderService` を組み立てる。  
したがって別の invocation で在庫を更新しても、次の Test の初期在庫は変化しない。

Isolation は、依存先を何でも Mock にすることではない。  
Test 内で完結する Fake の real behavior を利用しても、その状態を Test ごとに新規生成できれば適切に分離できる。

### Independence

Independence は、ある Test の成功が別の Test の先行実行や後続実行に依存しない性質である。  
任意の Test Method だけを実行しても、必要な前提条件がその Test 内または独立した Setup で準備されていれば成功できる。

悪い例では、Test 1 が `SHARED_STOCK.reserve(2)` を実行した後の在庫 `8` を、Test 2 が当然の前提として使っている。  
Test 2 だけを実行すると前提が準備されず、Test を順番どおりに並べて初めて成功する状態となる。

`@TestMethodOrder` や `@Order` は Test の実行順を指定できるが、**順序を固定することと Test を独立させることは異なる**。  
Unit 04 の改善例では `@BeforeEach` で新しい `Stock(10)` を作成し、各 Test が必要な状態を持つようにしている。

### Deterministic

Deterministic は、同じ入力と同じ制御された前提条件で実行すれば、同じ結果が得られる性質を指す。  
Test が成功するかどうかを現在時刻、乱数、実行順序、Thread Scheduling などの偶然に委ねない。

```text
同じ入力 + 同じ前提条件
           ↓
     同じ期待結果
```

`Clock.fixed(...)` の利用や Test ごとの Fixture 初期化は、Deterministic な Test を実現する具体的な方法となる。  
ただし、単に固定値を Stub すればよいのではなく、その値を利用して SUT が正しい仕様を満たしたかを Assertion する必要がある。

### Repeatable

Repeatable は、同じ Test を繰り返し実行しても安定して再現できる性質である。  
1 回だけ偶然成功するのではなく、再実行、単体実行、Test Suite 全体実行などで期待どおりに成立することを重視する。

Unit 04 では `@RepeatedTest(3)` を利用し、毎回新しい Fixture を生成したときに在庫が初期値 `10` から `8` へ変化することを確認する。  
ただし、**`@RepeatedTest` を付けただけで Repeatable な Test になるわけではない**。共有状態や実時刻への依存が残っていれば、繰り返すほど不安定さが表面化する場合もある。

Deterministic は結果の決まり方、Repeatable は同じ確認を繰り返したときの再現性という観点で理解する。両者は密接に関連する。

### Fast

Fast は、Test を素早く実行でき、日常的に何度も確認しやすい性質を指す。  
外部 Server の起動、DB の初期化、実時間の待機などを必要としない Plain Unit Test は、一般に高速になりやすい。

Unit 04 の通常の Test は Spring Context、PostgreSQL、HTTP Server を起動しないため、Service の Test を小さな境界で実行できる。  
ただし、速度だけのために必要な Integration Test を削るべきではない。テスト対象に応じて、Unit Test と Integration Test に異なる役割を持たせることが重要となる。

### Self-validating

Self-validating は、Test の結果を人が毎回目視して判断しなくても、Test Code 自身が期待条件を検証できる性質である。  
単に log を出すだけではなく、Assertion や Verification によって自動的に成功・失敗を判定する。

```java
assertThat(actual.getStatus()).isEqualTo(OrderStatus.CANCELLED);
assertThat(productRepository.currentStockQuantity(PRODUCT_ID)).isEqualTo(10);
```

ここで重要なのは Assertion が存在することだけではない。  
`isNotBlank()` のように弱すぎる期待条件では、本来誤りである結果まで許容することがある。仕様に合った具体的な期待値を選んでこそ、Self-validating の意味がある。

### FIRST の考え方

FIRST は良い Unit Test の性質を整理する代表的な考え方である。  
一般的には次の語の頭文字からなる。

| 要素            | 意味                     | Unit 04 での観点                   |
| --------------- | ------------------------ | ---------------------------------- |
| Fast            | 高速に実行できる         | DB や HTTP Server を不要にする     |
| Independent     | Test 同士が独立している  | 共有 mutable Fixture を避ける      |
| Repeatable      | 何度でも再現できる       | 固定 `Clock`、毎回新しい Fixture   |
| Self-validating | 自動で成否を判定できる   | 仕様に基づく Assertion を置く      |
| Timely          | 適切な時期に Test を作る | 仕様・設計と近い時点で検証を考える |

`Timely` は「Test の実行時間を短くする」という意味ではない。一般に、Production Code の実装前または実装と近い時期に Test を作り、設計の早い段階から振る舞いを検証するという考え方を表す。  
今回のリポジトリは Production Code を完成させてから Test Code を読む学習方式なので、TDD を実践する Unit という意味ではない。FIRST の理念と、この教材の生成順序は分けて理解する。

また、FIRST は唯一の品質基準ではない。仕様の網羅性、Test Oracle の妥当性、読みやすさ、保守性なども合わせて考える。

### Flaky Test

Flaky Test は、Production Code や Test Code の意図的な変更がないにもかかわらず、同じ Test が成功したり失敗したりする不安定な Test を指す。  
原因には、実行順依存、時間依存、非同期処理の Race Condition、外部サービスや環境状態のばらつきなどがある。

```text
コードと仕様は同じ
    ↓
実行 1: 成功
実行 2: 失敗
実行 3: 成功
```

補助教材 `FlakyTimingAndAsyncExample` は、10 ms の `Thread.sleep()` で非同期処理が完了すると仮定する Test と、実時刻の差が常に 20 ms 未満と仮定する Test を悪い例として持つ。  
これらは実行環境によって成功する場合もあるため、「たまたま何回も通った」ことは安定性の証明にならない。

失敗時に Retry を追加すると、一時的に成功へ変わることはある。しかし、原因である競合・共有状態・タイミング依存が取り除かれたわけではない。

### Brittle Test

Brittle Test は、要求された外部から観測可能な振る舞いが変わっていないのに、内部実装の小さな変更などで壊れやすい Test を指す。  
例えば、Product の正しい表示名を返すことが仕様なのに、Repository 呼び出し回数を理由なく `times(2)` と固定すると、実装が正しくてもその期待に一致しない場合がある。

```text
仕様としての返却値: 正しい
内部 call 数: Test が独自に 2 回と決めた
実際の call 数: 1 回
             ↓
             Test Failure
```

Brittle Test は「同じ Test を再実行すると結果が揺れる」という Flaky Test と同じではない。  
毎回確実に失敗していても、その理由が仕様とは無関係な内部実装への依存なら、脆い Test として検討する必要がある。

### Test Smell

Test Smell は、Test Code に品質上の問題が潜んでいる可能性を示す兆候を指す。  
Code Smell と同様に、見つかった瞬間に必ず誤りと判断するのではなく、何を保証したい Test かを踏まえて検討する。

代表的な兆候には次がある。

- Test 間で共有 mutable state を持つ。
- 実行順序を変えると失敗する。
- 完了条件ではなく `Thread.sleep()` に依存する。
- 準備する Mock と stubbing が過剰に多い。
- 仕様ではない内部呼び出しの回数や順序まで厳格に検証する。
- Assertion が弱く、誤った結果でも成功する。
- SUT 自体を Mock にして、その Stub 値を Assertion している。

Unit 04 は複数の Test Smell を小さな比較教材として用意している。  
問題となる理由を「悪い書き方だから」と暗記するのではなく、Test の信頼性・独立性・保証範囲にどう影響するかを読む。

### 実行順依存

実行順依存は、ある Test が別の Test の副作用や準備を前提とし、実行する順序によって結果が変わる問題である。  
`SharedMutableStateAndOrderExample` では、`firstTest_modifiesSharedState()` が在庫を `10` から `8` へ減らし、`secondTest_requiresTheFirstTestToRun()` がその変更済み状態を期待する。

```text
Test 1 → Test 2
  10 → 8 → 8 を検証（成功し得る）

Test 2 のみ
  10 → 8 を期待（失敗）
```

`@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` で順序を固定しても、単体実行時に必要な Fixture が存在しないという問題は解決しない。  
このような Test の前提は各 Test 内で作るか、`@BeforeEach` で毎回新しく準備する。

なお、手順そのものが仕様となる E2E Scenario と、Test Method 同士が暗黙に状態を引き継ぐ問題は区別する。今回の対象は後者となる。

### テスト間で共有される mutable state

Test Class の instance field、static field、Fake Repository、Collection などは、どの範囲で生成・共有されるかによって危険性が変わる。  
`@BeforeEach` で新しい `Stock` を生成する例では、Test Method ごとに初期在庫が `10` になり、前の Test の操作は引き継がれない。

```java
private Stock stock;

@BeforeEach
void setUp() {
  stock = new Stock(10);
}
```

単に共有 Object を `reset` する方法もあるが、reset 漏れや状態の複雑さを伴う場合がある。  
小さな Fixture なら各 Test で新規生成する方が、前提を明確にしやすい。

一方、変更されない `String`、`Instant`、数値定数などは共有すること自体が問題ではない。共有の有無ではなく、他の Test が変え得る状態かどうかを見る。

### `sleep` 依存

`Thread.sleep(10)` は、指定時間以上その Thread を休止させる方法であり、「非同期処理が完了した」と保証するものではない。  
CPU 負荷、JVM の起動・実行状況、OS Scheduling などによって、非同期処理の開始・完了までに必要な時間は変わる。

```java
CompletableFuture<Integer> future =
    CompletableFuture.supplyAsync(() -> 42);

Thread.sleep(10);
assertTrue(future.isDone());
```

この例では 10 ms 後の `isDone()` が必ず `true` である保証はない。  
`sleep` を 100 ms や 1,000 ms に伸ばしても、本質的な完了条件を検証したことにはならず、Test を遅くする。

同期可能な Future がある場合は、期限付きの `get(timeout, unit)` などで完了そのものを待つ方法を検討する。  
ただし timeout 自体は無制限に長くするものではなく、失敗と待機時間のバランスを設定する。

### 過剰な Mock

Mock は依存先を制御するための手段だが、多用すればよいわけではない。  
特に SUT 自体を Mock にすると、本来確認したい Production Code の振る舞いが実行されなくなる。

```java
ProductLabelService service = mock(ProductLabelService.class);
when(service.label(1L)).thenReturn("Standard Product");

assertThat(service.label(1L)).isEqualTo("Standard Product");
```

この Test は、Test 自身が指定した Stub 値が返ることを確認しているだけで、`ProductLabelService.label()` の分岐や依存先を使う処理を検証していない。  
補助教材ではこれを意図的な悪い例として扱う。

一方、Repository の応答が SUT の外側にあり、その応答に対する Service の振る舞いを確認したい場合には Repository Mock が適切な場合がある。  
Mock の個数だけで判断せず、**何を実際に動かしているか**を確認する。

### 実装詳細への過剰依存

Service が仕様上正しい結果を返すかどうかとは別に、内部 method の呼び出し順や回数を過度に固定すると、仕様を変えない refactoring でも Test が失敗する。  
interaction verification は Unit 03 で学んだ重要な方法だが、すべての内部 call を検証する必要はない。

`BrittleMockAndAssertionExample` では、`ProductLabelService` が正しいラベルを返した後で、根拠のない `verify(lookup, times(2)).findName(1L)` を実行する。  
実際の call は 1 回なので失敗するが、「2 回呼ぶこと」はこの教材で定めた仕様に含まれていない。

対照的に、注文の在庫更新や外部 API 呼び出しの有無など、副作用を持つ境界では interaction の有無・引数が仕様上重要になることがある。  
**検証するかどうかを決める根拠は、実装の形ではなく保証したい振る舞い**となる。

### False Positive

Test の評価でいう False Positive は、何を「Positive」と呼ぶかによって言い方が揺れるため、基準を明示する必要がある。  
Unit 04 の補助教材では、**「欠陥を検出した／問題ありと判定した」ことを Positive とする**考え方を採用する。

その場合の False Positive は、Production Code の要求された振る舞いが正しいのに、Test が問題ありと判定して失敗するケースを指す。  
例えば、仕様上問題のない `ProductLabelService` に対して根拠のない `times(2)` を要求し、1 回の呼び出しで Test Failure になる例が該当する。

```text
実際の仕様: 成立している
Test の判定: 問題がある（失敗）
           ↓
       False Positive
```

ただし、一般的な Test の成否を Positive / Negative と呼ぶ文脈では用語の対応が逆に説明される場合もある。  
そのため実務では「正しい実装なのに Test が失敗した」と具体的な状況で説明することが重要となる。

### False Negative

同じく欠陥検出を Positive とする考え方では、False Negative は、Production Code に仕様上の誤りがあるのに Test が見逃して成功するケースを指す。  
補助教材では、商品名が `"WRONG PRODUCT"` であるにもかかわらず、`isNotBlank()` だけを検証する例を用意している。

```java
when(lookup.findName(1L))
    .thenReturn(Optional.of("WRONG PRODUCT"));

assertThat(service.label(1L)).isNotBlank();
```

空文字列でなければ成功してしまい、期待する商品名と一致しているかを保証できない。  
このように、Assertion が存在しても Test Oracle が弱いと、重要な欠陥を検出できない場合がある。

SUT 自体を Mock にする悪い例も、実際の Production Code の欠陥に気付けないという観点で考えられる。

### Flaky Test と Brittle Test の違い

両者は Test 品質の問題だが、典型的な発生条件が異なる。

| 観点         | Flaky Test                            | Brittle Test                         |
| ------------ | ------------------------------------- | ------------------------------------ |
| 典型的な問題 | 同じコードでも成功・失敗が変わる      | 仕様を変えない実装変更でも壊れやすい |
| 主な原因     | 時間・順序・共有状態・非同期競合      | 実装詳細への過剰な依存               |
| 例           | 10 ms の sleep 後に `isDone()` を期待 | 仕様上不要な `times(2)` を期待       |
| 改善方向     | 非決定的な原因を制御する              | 仕様に意味のある検証へ絞る           |

どちらも Test の失敗が信頼されなくなる原因になる。  
ただし問題が重なることもあるため、単純な分類名より具体的な失敗原因を優先して確認する。

### 非同期処理のテストで発生しやすい問題

非同期処理では、Test Thread と処理を実行する Thread の進行順序が固定されない場合がある。  
CompletableFuture の結果をすぐ参照したり、短い `sleep` だけで完了すると仮定したりすると、Thread Scheduling の差によって結果が変わり得る。

Unit 04 の改善例では、次のように Future 自身の完了を期限付きで待つ。

```java
CompletableFuture<Integer> future =
    CompletableFuture.supplyAsync(() -> 42);

assertEquals(42, future.get(1, TimeUnit.SECONDS));
```

`get(1, TimeUnit.SECONDS)` は「1 秒待てば必ず成功する」という意味ではない。最大 1 秒以内に完了結果が得られることを期待し、完了しなければ Timeout によって失敗を検出する。  
実際の非同期テストでは、終了条件、Timeout、例外、Thread / Executor の後片付けなども検討対象になる。

### Awaitility の位置づけ

Awaitility は、非同期的に変化する状態が一定時間内に期待条件を満たすまで、polling しながら待つ用途で知られる Java Library である。  
例えば、別 Thread の処理によって status が変化するまで待ちたいとき、固定時間 `sleep` してから一度だけ Assertion する方法とは異なる考え方を提供する。

概念的な使い方は次となる。

```java
// 概念例。Unit 04 の pom.xml には Awaitility を追加していない。
// await().atMost(Duration.ofSeconds(1))
//        .untilAsserted(() -> assertThat(status()).isEqualTo("DONE"));
```

Awaitility は今回の依存ライブラリに含めず、実践コードでも利用しない。  
`CompletableFuture` のように完了を直接待てる仕組みがあるなら、まずその契約を利用する。Awaitility は主に、外部から観測できる状態の変化を待つ必要がある場合の選択肢として位置づける。

### Retry と原因の修正

Flaky Test に対して Retry を設定すると、たまたま次の実行で成功して不安定さが見えにくくなる場合がある。  
しかし、同じコードで成功・失敗が揺れる原因自体は解消されない。

```text
Flaky Test を発見
       ↓
時刻・共有状態・順序・非同期・外部状態を調べる
       ↓
必要な初期状態と完了条件を明示する
       ↓
再実行・単体実行で安定性を確認する
```

実務では障害調査の補助として Retry の情報を利用する場合もあるが、**Retry が通ったことを修正完了とみなさない**。  
Unit 04 では Test の信頼性を回復するために、原因を取り除くという姿勢を重視する。

## 使用する技術・ツール

### Java 21

Production Code、通常の Test Code、補助教材の実装言語として利用する。  
`Clock`、`Instant`、`IntSupplier`、`CompletableFuture` など、JDK が提供する仕組みを Testability の観点から扱う。

### JUnit Jupiter

`@Test`、`@DisplayName`、`@ParameterizedTest`、`@RepeatedTest`、`@BeforeEach` などを利用する。  
今回は JUnit の構文自体を習得することだけが目的ではなく、それらを用いた Test が独立性や再現性を保っているかを確認する。

`@RepeatedTest(3)` は同じ Test Method を 3 回実行する機能であり、Test に独立性を自動的に与えるものではない。  
`@BeforeEach` は補助教材の改善例で Test ごとに新しい `Stock` を作成するために利用する。

### Mockito

既存の `mock(...)`、`when(...).thenReturn(...)`、`verify(...)`、`verifyNoInteractions(...)`、`ArgumentCaptor` を利用する。  
Unit 03 と異なり、Mockito の API の種類を増やすのではなく、どの依存先を Mock にし、何を検証対象として残すかを中心に読む。

補助教材では `times(2)` による不適切な過剰検証も登場する。  
Mockito 自体の問題ではなく、Test が何を仕様として保証したいかという使い方の問題として理解する。

### AssertJ

`assertThat(...)`、`assertThatThrownBy(...)`、`isEqualTo(...)`、`isSameAs(...)`、`isEqualByComparingTo(...)`、`satisfies(...)` などを利用する。  
特に、期待値の具体性が Test の保証範囲を左右することを、通常の Test と弱い Assertion の補助教材から比較する。

### `Clock.fixed(...)`

JDK の `Clock` を固定時刻で構築するために利用する。  
Unit 04 では注文作成、キャンセル、補助教材の文字列生成に登場する。実際の時間経過や `Thread.sleep()` を必要とせず、期待時刻を正確に制御できる。

### `IntSupplier`

JDK の関数型 interface で、引数なしで `int` 値を返す処理を表す。  
補助教材の改善例では `() -> 1234` を渡し、乱数の取得方法を文字列生成ロジックから切り離す。Unit 04 では乱数ライブラリを追加しない。

### `CompletableFuture` と `TimeUnit`

JDK の非同期計算機構と時間単位の指定に利用する。  
補助教材の改善例では `future.get(1, TimeUnit.SECONDS)` によって完了を期限付きで待つ。固定時間 `sleep` で完了を推測する方法との違いを確認する。

### Maven / Maven Wrapper

通常の Test Suite と Unit 04 の Test Class を実行する入口として利用する。

```bash
./mvnw test
```

Unit 04 では新しい Maven Dependency を追加していない。  
JUnit Jupiter、Mockito、AssertJ は既存の `pom.xml` に設定済みのものを継続して利用する。Awaitility は概念のみを扱い、追加・実行しない。

## テスト対象とテスト境界

### `OrderServiceTestabilityTest`

SUT は実際の `OrderService` となる。既存の Constructor Injection を利用して、Spring Context を起動せずに組み立てる。

```text
実際に動く SUT:
OrderService

real object:
Order / OrderItem / Product / PriceSummary
CreateOrderCommand / CreateOrderItemCommand
FakeProductRepository
Clock.fixed(...)

Mockito Mock:
OrderRepository
ShippingFeeClient

null Command の Test だけ:
ProductRepository / OrderRepository
ShippingFeeClient / Clock を Mock
```

`FakeProductRepository` は Test 内で in-memory の在庫状態を保持し、`decreaseStock()` と `increaseStock()` を実際に処理する。  
`OrderRepository` と `ShippingFeeClient` は、DB・外部 HTTP API を呼び出す代わりに Test 内で応答を用意する。

null Command の Test では、入力時点で拒否されるため依存先を一切利用しないことを確認する。  
この Test では専用の Stub 応答を作らず、依存先の interaction がないことを検証する。

### `ProductServiceTestabilityTest`

SUT は実際の `ProductService` となる。Repository interface を介した依存関係を利用し、次の構成で3つの Test Case を扱う。

```text
ProductService.findById 正常系:
  ProductRepository → Mockito Mock

ProductService.findById 商品不存在:
  ProductRepository → Mockito Mock

ProductService.findAll:
  ProductRepository → FakeProductRepository
```

`ProductService` 自身は Mock にしない。  
商品の取得・商品不存在の業務例外への変換・一覧の返却といった Service の実際の振る舞いを検証する。

### 比較用の補助教材

`units/04-testability-test-quality/examples/` の4ファイルは、Testability や Test 品質を比較するための小さな教材となる。  
通常の Maven Test Source である `src/test/java` とは別の場所に配置し、意図的に失敗・不安定化する例を Test Suite へ混ぜない。

| ファイル                                 | 比較する内容                                       | 主な読み方                                    |
| ---------------------------------------- | -------------------------------------------------- | --------------------------------------------- |
| `HardCodedDependencyExample.java`        | 時刻・乱数・System Property の直接参照と DI        | 悪い設計と改善設計を比較する                  |
| `SharedMutableStateAndOrderExample.java` | static mutable state、実行順依存                   | BadExamples と IndependentExamples を比較する |
| `FlakyTimingAndAsyncExample.java`        | `sleep`、実時刻、非同期完了の待機                  | BadExamples と BetterExamples を比較する      |
| `BrittleMockAndAssertionExample.java`    | SUT 自体の Mock、弱い Assertion、過剰 verification | 悪い例と BehaviorFocusedExamples を比較する   |

補助教材の Java ファイルには `@Test` を含むものもあるが、配置先が通常の `src/test/java` ではないため、既存の Maven 設定で実行される Test には含まれない。  
これらをそのまま `src/test/java` に移すと、意図的な失敗・不安定さが通常の Test 結果に混ざり得るため、**移動して実行することを今回の学習手順には含めない**。

### この Unit で起動しないもの

通常の Test は Plain Unit Test として構成している。

```text
Spring Boot Application / Spring Context
PostgreSQL / JDBC Repository の実処理
Docker / Docker Compose
HTTP Server / External Shipping Fee API
WireMock Server
```

`@Service` や `@Transactional` が付いている Production Code であっても、`new OrderService(...)` や `new ProductService(...)` で生成した Plain Java Object には Spring の Transaction Proxy は適用されない。  
したがって、この Unit の成功は DB Transaction や Spring の Bean Wiring の正しさを保証しない。今回の SUT は Service の Java Logic と Testability となる。

## 使用するファイル

### この Unit で追加する Test Code

- [`OrderServiceTestabilityTest.java`](../../src/test/java/com/example/orderapi/service/OrderServiceTestabilityTest.java)
- [`ProductServiceTestabilityTest.java`](../../src/test/java/com/example/orderapi/service/ProductServiceTestabilityTest.java)

### この Unit で追加する補助教材

- [`HardCodedDependencyExample.java`](examples/HardCodedDependencyExample.java)
- [`SharedMutableStateAndOrderExample.java`](examples/SharedMutableStateAndOrderExample.java)
- [`FlakyTimingAndAsyncExample.java`](examples/FlakyTimingAndAsyncExample.java)
- [`BrittleMockAndAssertionExample.java`](examples/BrittleMockAndAssertionExample.java)

### コードリーディングで参照する主な Production Code

- [`OrderService.java`](../../src/main/java/com/example/orderapi/service/OrderService.java)
- [`ProductService.java`](../../src/main/java/com/example/orderapi/service/ProductService.java)
- [`ProductRepository.java`](../../src/main/java/com/example/orderapi/repository/ProductRepository.java)
- [`OrderRepository.java`](../../src/main/java/com/example/orderapi/repository/OrderRepository.java)
- [`ShippingFeeClient.java`](../../src/main/java/com/example/orderapi/client/ShippingFeeClient.java)
- [`ClockConfig.java`](../../src/main/java/com/example/orderapi/config/ClockConfig.java)
- [`Order.java`](../../src/main/java/com/example/orderapi/domain/Order.java)
- [`OrderItem.java`](../../src/main/java/com/example/orderapi/domain/OrderItem.java)
- [`Product.java`](../../src/main/java/com/example/orderapi/domain/Product.java)
- [`CreateOrderCommand.java`](../../src/main/java/com/example/orderapi/service/model/CreateOrderCommand.java)
- [`CreateOrderItemCommand.java`](../../src/main/java/com/example/orderapi/service/model/CreateOrderItemCommand.java)

### 前 Unit から引き続き参照する Test Support / Test Code

- [`FakeProductRepository.java`](../../src/test/java/com/example/orderapi/support/FakeProductRepository.java)
- [`OrderServiceMockitoTest.java`](../../src/test/java/com/example/orderapi/service/OrderServiceMockitoTest.java)
- [`OrderServiceFakeAndSpyTest.java`](../../src/test/java/com/example/orderapi/service/OrderServiceFakeAndSpyTest.java)
- [`pom.xml`](../../pom.xml)

Unit 04 では既存の Production Code、既存の Test Code、`pom.xml` を変更しない。  
Unit 03 から継続利用する `FakeProductRepository` も再実装せず、同じ Test Support を利用する。

## 前提

Unit 01～03 で学んだ次の内容を前提とする。

```text
Unit 01:
SUT / Unit Test / AAA / JUnit Jupiter / Assertion / Maven Test

Unit 02:
Test Case Design / Test Oracle / Test Fixture
Parameterized Test / AssertJ / @BeforeEach

Unit 03:
Test Double / Stub / Mock / Fake / Spy
stubbing / verification / ArgumentCaptor / Test Boundary
```

`OrderService` はすでに完成した Production Code であり、Unit 03 で依存先を置き換えて Service の振る舞いを Test している。  
Unit 04 ではその前提を引き継ぎ、設計上の差し替えやすさと Test の品質に視点を移す。

`Flaky Test`、`Brittle Test`、`FIRST`、`False Positive`、`False Negative`、`Awaitility` などは、この Unit の新しい学習内容として扱う。  
これらを既習事項として説明から省略するものではない。

## コードリーディング

Unit 04 のコードリーディングは、次の順序で進める。  
最初にメインアプリの良い構成を確認し、次に補助教材で悪い例と改善例を比較する。補助教材を先に単独で眺めるより、「どの設計判断が Test の品質へ影響するか」を対応させて読む。

```text
OrderService / ProductService の Production Code
                  ↓
OrderServiceTestabilityTest
                  ↓
ProductServiceTestabilityTest
                  ↓
HardCodedDependencyExample
                  ↓
SharedMutableStateAndOrderExample
                  ↓
FlakyTimingAndAsyncExample
                  ↓
BrittleMockAndAssertionExample
                  ↓
通常の Test を実行し、信頼できる検証を整理
```

### 1. `OrderService` の constructor dependency を確認する

最初に [`OrderService.java`](../../src/main/java/com/example/orderapi/service/OrderService.java) を開く。  
`ProductRepository`、`OrderRepository`、`ShippingFeeClient`、`Clock` の4つが constructor から渡されていることを確認する。

見るべき点は「どの依存先を Mockito で作るか」よりも、`OrderService` 自身が依存先の具体実装を生成していないことである。

```text
OrderService が行うこと:
注文作成・状態遷移・在庫更新などの業務フロー

外側へ委ねること:
DB の読み書き・送料取得・時刻の提供
```

Test が小さな境界で成立する理由を先に整理する。

### 2. `Instant.now(clock)` の利用箇所を追う

`OrderService.createOrder()` と `OrderService.cancel()` の時刻取得を確認する。

```java
Instant.now(clock)
```

Production Code が `Instant.now()` を引数なしで直接呼ばず、注入された `Clock` を利用している点が Testability に関係する。  
Test 側で固定 `Clock` を渡せば、`createdAt` / `cancelledAt` の期待値を正確に決定できる。

### 3. `ClockConfig` と Test の `Clock.fixed(...)` を比較する

[`ClockConfig.java`](../../src/main/java/com/example/orderapi/config/ClockConfig.java) を確認する。  
Production の `Clock` をどこで用意するかと、Test で `Clock.fixed(...)` を直接渡す構成を比較する。

```text
Production:
Spring が利用する Clock Bean

Plain Unit Test:
Test が用意した Clock.fixed(...)
```

どちらも `OrderService` の constructor が受け取る `Clock` であり、Service 自身に Test 専用分岐を追加していないことを確認する。

### 4. `OrderServiceTestabilityTest` の全体構成を確認する

[`OrderServiceTestabilityTest.java`](../../src/test/java/com/example/orderapi/service/OrderServiceTestabilityTest.java) を開く。  
最初に4つの Test Method を確認する。

```text
createOrder_withInjectedClock_usesExactlyTheGivenInstant
createOrder_withFreshFixtures_isRepeatableWithoutSharedMutableState
cancel_withFixedClock_updatesTimestampAndRestoresStock
createOrder_withNullCommand_failsBeforeTouchingAnyDependency
```

最初の Test は `@ParameterizedTest` によって2回、2番目の Test は `@RepeatedTest(3)` によって3回実行される。  
残りは通常の `@Test` であり、**4 Test Method、合計7 invocation** となる。

### 5. `@ParameterizedTest` と固定時刻の Fixture を読む

`createOrder_withInjectedClock_usesExactlyTheGivenInstant` を読む。  
`@ValueSource` には次の2つの時刻が指定されている。

```text
2026-10-01T00:00:00Z
2026-12-31T23:59:59Z
```

各 invocation で `Instant.parse(instantText)` から期待日時を作り、`Clock.fixed(expectedCreatedAt, ZoneOffset.UTC)` を生成している。  
同じ Test Logic が、異なる固定時刻でも成立することを確かめる構成となる。

### 6. 注文作成 Test の依存先を確認する

同じ Test の Arrange を読む。

```text
ProductRepository → FakeProductRepository
OrderRepository   → Mockito Mock
ShippingFeeClient → Mockito Mock
Clock             → real Clock.fixed(...)
```

ここでは Unit 03 の `@InjectMocks` に頼らず、`new OrderService(...)` へ依存先を明示的に渡している。  
**Constructor Injection が Spring Context なしでも成立する**ことと、依存先の制御方法を Test が選べることを確認する。

### 7. 注文作成 Test の stubbing を読む

`when(...).thenReturn(...)` で指定している応答を、今回必要な Test Boundary に対応させる。

```text
ShippingFeeClient.calculateShippingFee
→ 送料 800

OrderRepository.insertOrder
→ Order ID 100

OrderRepository.findById
→ 永続化後と見なす Order
```

`ProductRepository` の在庫減算には `thenReturn` を指定していない。  
Fake の実装が在庫を変更するためであり、Mock と Fake の役割を意識する。

### 8. `ArgumentCaptor` の検証意図を読む

注文作成の Act で `orderService.createOrder(command())` を実行した後、次を見る。

```java
ArgumentCaptor<Order> savedOrder = ArgumentCaptor.forClass(Order.class);
verify(orderRepository).insertOrder(savedOrder.capture());
```

`OrderRepository.findById()` の戻り値は Test が Stub した `persistedOrder` である。  
その返却 Object を確認するだけでなく、SUT が実際に Repository へ保存しようとした `Order` を Captor で取得している点が重要となる。

### 9. 注文作成時刻と金額の Assertion を読む

Captor で取得した `Order` の `createdAt`、`status`、`subtotal`、`shippingFee`、`totalAmount` を確認する。

```text
createdAt  → Test が注入した固定時刻
status     → CREATED
subtotal   → 6,000
shippingFee→ 800
totalAmount→ 6,800
```

これにより、Repository の Stub 応答を確認しただけで「Service が正しく作成した」と誤解することを避ける。  
`actual` が `persistedOrder` と同じ instance であることも確認するが、それと保存対象の検証は異なる役割となる。

### 10. Fake 在庫の state verification を読む

同じ Test の末尾で、`currentStockQuantity(PRODUCT_ID)` が `8` になることを確認する。

```text
初期在庫 10
     ↓
2 個の注文作成
     ↓
現在在庫 8
```

この状態変化は Fake の real method によって発生する。  
外部 DB を起動せずに、SUT が依存先へ行った処理の結果を観測できることを確認する。

### 11. `@RepeatedTest(3)` の Fixture の生成範囲を見る

`createOrder_withFreshFixtures_isRepeatableWithoutSharedMutableState` を読む。  
3回の invocation それぞれで、Fake、Mock、`Clock`、`OrderService`、Repository 応答用の `Order` を新規に準備する。

```text
1 回目: 在庫 10 → 8
2 回目: 在庫 10 → 8
3 回目: 在庫 10 → 8
```

「前の invocation の残り在庫8を次の開始値として使う」のではない。  
`@RepeatedTest` は確認回数を増やす道具であり、安定した初期状態を作る責任は Test 側にある。

### 12. キャンセル時刻と在庫復元を読む

`cancel_withFixedClock_updatesTimestampAndRestoresStock` を読む。  
初期在庫 `8` の Fake と、`CANCELLED_AT` で固定した `Clock` を利用する。

`OrderRepository.findById()` は確定状態の `Order` と、キャンセル済み `Order` を順に返し、`updateStatus(...)` は更新件数 `1` を返すよう Stub している。  
キャンセル処理が注入された時刻を引数として更新を依頼し、Fake の在庫を `8` から `10` に戻すことを確認する。

### 13. キャンセル Test の保証範囲を区別する

次をそれぞれ確認する。

```java
assertThat(actual.getCancelledAt()).isEqualTo(CANCELLED_AT);
verify(orderRepository)
    .updateStatus(ORDER_ID, OrderStatus.CONFIRMED, OrderStatus.CANCELLED, CANCELLED_AT);
assertThat(productRepository.currentStockQuantity(PRODUCT_ID)).isEqualTo(10);
verifyNoInteractions(shippingFeeClient);
```

戻り値の `Order` は Repository Stub が返した Object であり、実 DB の更新結果ではない。  
一方、更新時に渡す日時と Fake の在庫変化は Service が実行した振る舞いを表す。何が Test Double の応答で、何が実際に検証されたかを区別する。

### 14. null Command の Test を読む

`createOrder_withNullCommand_failsBeforeTouchingAnyDependency` を読む。  
この Test では4つの依存先をすべて Mock にし、stubbing を設定していない。

```text
Act:
orderService.createOrder(null)

Expected:
IllegalArgumentException
"command must not be null"

依存先:
interaction なし
```

入力時点で拒否されるため、DB や HTTP に相当する境界へ進まないことが仕様となる。  
対象の振る舞いを確認するのに不要な stubbing を準備しないという判断も Test 品質に関係する。

### 15. Test Fixture helper の責務を確認する

`newProductRepository()`、`command()`、`persistedOrder(...)` の役割を読む。  
これらは Test の準備を整理する helper であり、Test 間で mutable Fixture を共有するためのものではない。

`newProductRepository(int)` は呼ぶたびに新しい `Product` と `FakeProductRepository` を生成する。  
`persistedOrder(...)` は、Repository が返す保存後状態の Fixture を作り、SUT の Act と Fixture 生成を分離する。

### 16. `ProductService` の責務を見る

[`ProductService.java`](../../src/main/java/com/example/orderapi/service/ProductService.java) を開く。  
`ProductRepository` を constructor で受け取り、`findAll()` は一覧を返し、`findById()` は該当商品を返すか `ProductNotFoundException` を発生させる構成を確認する。

SQL を生成するコードや JDBC Connection を Service 内で作っていない点を読む。  
そのため Repository の応答を指定するだけで Service の業務上の振る舞いに集中できる。

### 17. `ProductServiceTestabilityTest` の3ケースを読む

[`ProductServiceTestabilityTest.java`](../../src/test/java/com/example/orderapi/service/ProductServiceTestabilityTest.java) を読む。

```text
findById_withInjectedRepository_returnsTheProduct
findById_whenMissing_throwsDomainSpecificException
findAll_withFreshFake_returnsAnIsolatedSnapshot
```

最初の2ケースは Mockito Mock、最後の1ケースは Fake を利用する。  
なぜ Test の目的によって依存先を切り替えているかを意識する。

### 18. Product 検索正常系の Assertion を読む

`findById_withInjectedRepository_returnsTheProduct` は、Mock Repository が返す `Product` を Service が返すことを確認する。  
`isSameAs(expected)` に加え、`id` と `name` を確認する。

この Test の目的は、SQL の正しさでも Repository call 数の固定でもない。  
SUT の返却結果を仕様の観点から評価していることを確認する。

### 19. Product 不存在の業務例外を読む

`findById_whenMissing_throwsDomainSpecificException` は、`Optional.empty()` に対する Service の振る舞いを Test する。  
`ProductNotFoundException` の種類、message、`productId` を確認する。

このケースに商品一覧や送料の Fixture は不要となる。  
Test の関心に必要な前提だけを用意することが、読みやすさと原因の切り分けにつながる。

### 20. Product 一覧の Fake と Fixture を読む

`findAll_withFreshFake_returnsAnIsolatedSnapshot` では、2つの `Product` を持つ Fake を Test 内で作成する。  
`containsExactly(first, second)` と Product ID の `containsExactly(1L, 2L)` を確認する。

Fake の `findAll()` が in-memory state から結果を組み立てる点を確認する。  
ただし SQL、DB の sort、Transaction はこの Test では動いていない。`IsolatedSnapshot` という Test 名を、本物の DB Snapshot Isolation の確認と混同しない。

### 21. `HardCodedReferenceGenerator` の隠れた依存先を確認する

[`HardCodedDependencyExample.java`](examples/HardCodedDependencyExample.java) を開く。  
`HardCodedReferenceGenerator.generate(long orderId)` が `Instant.now()`、`new Random()`、`System.getProperty(...)` を直接呼んでいることを確認する。

```text
見える入力:
orderId

結果に影響する隠れた入力:
現在時刻
乱数
shipping.region System Property
```

`orderId` が同じでも参照番号全体が一定とならない理由を、それぞれの依存先から説明できるように読む。

### 22. `InjectedReferenceGenerator` の改善点を比較する

同じファイルの `InjectedReferenceGenerator` を読む。  
`Clock`、`IntSupplier`、`String region` を constructor parameter とし、`generate()` では注入された依存先を利用する。

```java
String generate(long orderId) {
  int suffix = suffixSupplier.getAsInt();
  return region + "-" + orderId + "-" + suffix + "-" + Instant.now(clock);
}
```

`deterministicExample()` では固定日時、`() -> 1234`、`"JP"` を渡している。  
期待する出力は、乱数のタイミングや実行環境の地域設定ではなく、明示した値によって決まることを確認する。

### 23. 責務の違いとして設計を比較する

2つの ReferenceGenerator を比較して、単に行数が減ったかどうかではなく、次の責務の境界がどう変わったかを読む。

```text
HardCodedReferenceGenerator:
値の取得方法と文字列組み立てを内部で固定

InjectedReferenceGenerator:
値の取得方法は外部から注入
文字列組み立ては Generator が担当
```

補助教材の例は `OrderService` そのものではない。  
Production Code を意図的に悪くする代わりに、同じ設計上の問題を小さなコードで確認していることを意識する。

### 24. 共有 `Stock` を持つ悪い Test を読む

[`SharedMutableStateAndOrderExample.java`](examples/SharedMutableStateAndOrderExample.java) の `BadExamples` を読む。

```java
private static final Stock SHARED_STOCK = new Stock(10);
```

`Stock` は `quantity` を変更できる mutable Object である。  
`static final` によって `SHARED_STOCK` を再代入できなくても、`reserve(...)` を実行すると内部状態が変更されることを確認する。

### 25. `@Order(1)` と `@Order(2)` の前提を追う

`firstTest_modifiesSharedState()` は在庫を2減らして `8` と確認する。  
`secondTest_requiresTheFirstTestToRun()` は、何も準備せずに `8` を期待する。

この2つを個別の Test Case として考えると、2番目だけでは Arrange が成立しない。  
`@TestMethodOrder` で実行順序を指定していることが、依存関係を解決していない点を読み取る。

### 26. `IndependentExamples` の `@BeforeEach` を読む

次に同じファイルの `IndependentExamples` を読む。

```java
@BeforeEach
void setUp() {
  stock = new Stock(10);
}
```

`reserveTwo_hasEightRemaining()` と `reserveThree_hasSevenRemaining()` は、それぞれ新しい在庫 `10` から Act を始める。  
Test Method の順序にかかわらず、期待結果が独立して決まる構成と比較する。

### 27. `FlakyTimingAndAsyncExample` の悪い例を読む

[`FlakyTimingAndAsyncExample.java`](examples/FlakyTimingAndAsyncExample.java) の `BadExamples` を読む。  
`asyncWork_assumesTenMillisAreAlwaysEnough()` は `CompletableFuture.supplyAsync(...)` の直後に `Thread.sleep(10)` を実行し、Future が完了したと期待する。

```java
Thread.sleep(10);
assertTrue(future.isDone());
assertEquals(42, future.getNow(-1));
```

`getNow(-1)` は「未完了なら代替値 `-1` を返す」という確認に使っている。  
`sleep` が Future の完了を保証しないため、これらの Assertion は Thread Scheduling の影響を受け得る。

### 28. 実時刻の許容幅が持つ問題を読む

同じ `BadExamples` の `realClock_usesAnArbitraryTolerance()` を読む。

```java
Instant before = Instant.now();
Thread.sleep(5);
Instant after = Instant.now();

assertTrue(after.toEpochMilli() - before.toEpochMilli() < 20);
```

5 ms の `sleep` を挟んだからといって、取得時刻の差が必ず20 ms未満になるわけではない。  
そもそもこの差の検証がどの業務仕様を保証するのかが不明確な点も読み取る。

### 29. 固定時刻と Future 完了待ちへ置き換えた例を読む

`BetterExamples` の2 Test を確認する。

```java
assertEquals(expected, Instant.now(clock));

assertEquals(42, future.get(1, TimeUnit.SECONDS));
```

固定時刻は正確な値を検証し、Future は期限付きで完了条件を待つ。  
`get(...)` に Timeout が存在することと、根拠のない固定 `sleep` で「もう終わったはず」と決め付ける方法との違いを理解する。

### 30. `ProductLabelService` の仕様を先に確認する

[`BrittleMockAndAssertionExample.java`](examples/BrittleMockAndAssertionExample.java) を開く。  
最初に `ProductLookup` と `ProductLabelService` を読む。

```java
String label(long id) {
  return productLookup.findName(id).orElse("unknown");
}
```

仕様として確認したいのは「商品名が存在すればその名前、なければ `"unknown"` を返す」ことである。  
内部で Repository を何回呼ぶかは、この例の仕様に含まれない点を先に押さえる。

### 31. SUT 自体を Mock にする悪い例を読む

`OverMockedExamples.mockingTheSutOnlyConfirmsTheStub()` を読む。

```java
ProductLabelService service = mock(ProductLabelService.class);
when(service.label(1L)).thenReturn("Standard Product");
```

Test が実際の `ProductLabelService.label()` を実行していないことを確認する。  
Assertion が成功しても、それは Production Code のロジックを検証した結果ではない。

### 32. 弱い Assertion の悪い例を読む

`WeakAssertionExamples.notBlankDoesNotGuaranteeTheExpectedProductName()` を読む。  
Repository 相当の Stub が `"WRONG PRODUCT"` を返すのに、Assertion が `isNotBlank()` だけである点を確認する。

```text
期待したい仕様:
正しい商品名が返る

実際に検証していること:
空文字列ではない
```

Test が成功することと、期待する仕様を十分に保証することが異なる例となる。  
False Negative を具体的な Test Oracle の弱さとして理解する。

### 33. 内部 call 数を過剰に固定する悪い例を読む

`BrittleVerificationExamples.callCountExpectationCanRejectCorrectBehavior()` を読む。  
実際の `label(1L)` は正しい商品名を返すが、次の verification を追加している。

```java
verify(lookup, times(2)).findName(1L);
```

`ProductLabelService.label()` はこの例では1回だけ `findName()` を呼ぶため、この検証は失敗する。  
2回呼ぶことが仕様ではないのに Test が失敗するという点を、Brittle Test / False Positive の例として確認する。

### 34. `BehaviorFocusedExamples` を比較する

最後に `knownProduct_returnsTheExpectedLabel()` と `missingProduct_returnsTheFallbackLabel()` を読む。  
実際の SUT を動かし、依存先だけを Mock にして、仕様で定めた期待ラベルを検証する。

```text
Product が存在する
→ "Standard Product"

Product が存在しない
→ "unknown"
```

Test が何を保証しているかが利用者から観測できる結果に対応していることを確認する。  
不要な内部 call 数の verification を置かなくても、この教材で確認したい契約を表現できる。

### 35. 全教材を Testability と Test 品質の観点で比較する

コードリーディングの最後に、次の2つの問いを分けて整理する。

```text
Production Code の問題:
Test から依存先・時刻・乱数を制御できる設計か
責務が適切に分かれているか

Test Code の問題:
Fixture は独立しているか
同じ条件で同じ結果になるか
Assertion は仕様を保証するか
内部実装に過度に依存していないか
```

Unit 04 はこの両方を扱う。  
「動く Test を増やすこと」だけでなく、「その Test が信頼できる理由と限界を説明できること」を目指して読む。

## 実行・確認

### 実行前の確認

Unit 04 の第1回目で追加した2つの Test Class と、4つの補助教材が配置されていることを確認する。  
実行する場所はリポジトリルートとし、通常の Java 21 / Maven Wrapper 環境を利用する。

補助教材は `src/test/java` に配置されていないため、通常の Maven Test の実行対象には含まれない。  
意図的な不安定 Test や常に失敗する過剰 verification の例を、通常の Test Suite に追加しないことが今回の前提となる。

### 全 Test を実行する

リポジトリルートで次を実行する。

```bash
./mvnw test
```

Unit 01～03 の既存 Test に加えて、Unit 04 の通常の Test Class が実行される。  
JUnit の Test を実行するため、Spring Boot Application、PostgreSQL、Docker Compose、外部 API Stub Server の起動は不要となる。

テスト成功時は Maven Surefire の集計と Build 成功を確認する。  
もし失敗した場合は、最初にどの Test Class / Method / invocation が失敗したのかを読み、Unit 04 の新規 Test なのか既存 Test なのかを切り分ける。

### `OrderServiceTestabilityTest` のみ実行する

```bash
./mvnw -Dtest=OrderServiceTestabilityTest test
```

この Class には4 Test Method があり、Parameterized Test 2回、Repeated Test 3回、通常の Test 2回で、合計7 invocation となる。

| Test                     | invocation 数 | 確認対象                   |
| ------------------------ | ------------: | -------------------------- |
| 注文作成 + 固定 `Clock`  |             2 | 異なる固定時刻を正確に反映 |
| 注文作成 + fresh Fixture |             3 | 毎回の初期在庫 `10` → `8`  |
| キャンセル               |             1 | 日時と在庫復元             |
| null Command             |             1 | 入力拒否と依存先未使用     |
| **合計**                 |         **7** |                            |

`@RepeatedTest` が3回実行されたことだけで再現性を証明したと考えず、各 invocation が独立した Fixture で始まるコード上の構成と合わせて確認する。

### `ProductServiceTestabilityTest` のみ実行する

```bash
./mvnw -Dtest=ProductServiceTestabilityTest test
```

この Class は3つの通常の `@Test` method を持つ。

```text
Product が存在する → Product を返す
Product が存在しない → ProductNotFoundException
Fake Repository に 2 Product → 順序付き List を返す
```

結果確認では、Product が正しく返ることと、DB の SQL を実行していないことを区別する。  
今回の Test 成功だけで JDBC Repository の正しさが保証されるわけではない。

### Unit 04 の通常の Test 全体を確認する

2 Class の実行結果をまとめて確認したい場合は次を実行する。

```bash
./mvnw '-Dtest=OrderServiceTestabilityTest,ProductServiceTestabilityTest' test
```

Unit 04 の通常の Test Code には、合計7つの Test Method があり、Parameterized / Repeated invocation を含めると合計10 invocation となる。  
Test Method 数と Maven の集計上の実行件数は一致しない場合があることを確認する。

### Surefire Report の確認

Maven Surefire の実行結果は通常、`target/surefire-reports/` 配下に出力される。  
Class ごとの `.txt` や `.xml` では、実行件数、失敗件数、エラー件数、スキップ件数などを確認できる。

```text
target/surefire-reports/
  ├─ com.example.orderapi.service.OrderServiceTestabilityTest.txt
  └─ com.example.orderapi.service.ProductServiceTestabilityTest.txt
```

表示形式や個々の invocation 名は Maven / JUnit / Surefire の環境・設定で異なる場合がある。  
固定のログ全文を一致させるのではなく、対象 Class の Test が実行され、期待する Assertion が成立したことを確認する。

### 固定時刻の確認

`OrderServiceTestabilityTest` の2つの `@ValueSource` 値に注目する。  
どちらの invocation でも `savedOrder.getValue().getCreatedAt()` が、その回に注入した `Clock.fixed(...)` と一致することを確認する。

```text
2026-10-01T00:00:00Z → 同じ日時が保存対象へ設定される
2026-12-31T23:59:59Z → 同じ日時が保存対象へ設定される
```

2つの日付の間を実際に待つ必要はなく、Test が条件を設定している点が重要となる。

### 共有状態のない繰り返し実行を確認する

`@RepeatedTest(3)` の Test では、繰り返しごとに在庫 `8` を期待する。  
この結果は前の Test が残した在庫から続けて減算したものではない。

```text
正しい Fixture の準備:
10 → 8
10 → 8
10 → 8

共有 mutable state を引き継いだ場合の例:
10 → 8 → 6 → 4
```

後者の挙動を実際に作る必要はない。  
Unit 04 の通常の Test の source と結果から、Test 間で mutable state が共有されていないことを確認する。

### 悪い例は実行せずにコード上で原因を確認する

次の4ファイルは意図的な悪い設計・Test を含むため、**コードリーディング用**として扱う。

```text
units/04-testability-test-quality/examples/
├─ HardCodedDependencyExample.java
├─ SharedMutableStateAndOrderExample.java
├─ FlakyTimingAndAsyncExample.java
└─ BrittleMockAndAssertionExample.java
```

`src/test/java` へコピーしたり、Maven の Test Source 設定を変更して実行対象へ追加したりする必要はない。  
悪い例の Test Failure や Flaky な挙動は、学習用の意図的なものなので、通常の Test Suite の成功条件に含めない。

### 実行順依存の悪い例を読み取る

`SharedMutableStateAndOrderExample` の `BadExamples` では、最初の Test が在庫を `8` にしたことを次の Test が前提としている。  
2番目だけを実行した場合は初期状態 `10` のままで期待値 `8` と一致しないことを、コードの流れから説明できるようにする。

比較対象の `IndependentExamples` では `@BeforeEach` により毎回 `Stock(10)` が作られることを確認する。  
ここで見るべきなのは「Test を番号順で動かせるか」ではなく「任意の Test を単独実行できる状態か」である。

### Flaky Test の悪い例を読み取る

`FlakyTimingAndAsyncExample` の `BadExamples` は、短い `Thread.sleep()` が完了条件の代わりになると仮定している。  
実際には非同期処理の完了時点も、実時刻の差も一定ではないため、成功・失敗が実行環境に左右され得る。

`BetterExamples` では固定時刻や Future の期限付き完了待ちを利用する。  
Test を何回実行すれば Flaky Test でないと証明できるか、という発想ではなく、**不安定になる依存関係が残っていないか**を確認する。

### Brittle Test と False Positive / Negative を読み取る

`BrittleMockAndAssertionExample` の3種類の悪い例を確認する。

| 悪い例                  | Test がしていること     | 問題                          |
| ----------------------- | ----------------------- | ----------------------------- |
| SUT 自体を Mock         | Stub の値をそのまま確認 | Production Logic を動かさない |
| `isNotBlank()` だけ確認 | 誤った商品名でも成功    | 仕様の欠陥を見逃す            |
| `times(2)` を固定       | 正しい返却値でも失敗    | 根拠のない内部仕様で失敗する  |

`BehaviorFocusedExamples` は実際の SUT を動かし、存在する商品と存在しない商品の返却結果を仕様の期待値と比較する。  
悪い例を実行して失敗させることを目的とせず、何が検証できているかを正確に読み取る。

### もし通常の Test が失敗した場合

Unit 04 の通常の Test が失敗した場合は、次を切り分ける。

1. 失敗した Test Class / Test Method / invocation は何か。
2. Assertion の期待値と実際値は何か。
3. Test が Stub した応答なのか、SUT が生成した結果なのか。
4. Fixture が Test ごとに初期化されているか。
5. 固定時刻や依存先の条件が意図どおり渡されているか。

Flaky な失敗に見えても、まず原因を確認する。  
Test を無条件で再実行して成功に変えることや、期待値を実際値へ合わせることを修正方法とはしない。

## 学習ポイント

### Testability は Production Code の設計とも関係する

Test が難しいとき、その原因が Test Code だけにあるとは限らない。  
SUT が自分で DB Client や現在時刻を固定的に取得する設計では、依存先の制御が難しくなり、Test Boundary を小さく設定しにくい。

`OrderService` や `ProductService` のように、業務処理と依存先の構築を分離しておくと、同じ Production Logic を使いながら必要な依存先を Test で選べる。  
Testability は設計の結果として現れる性質でもある。

### Dependency Injection は Test 専用の仕組みではない

DI は、Test のために Mock を注入できることだけが価値ではない。  
Service が具体的なインフラ実装を自分で構築しないことで、依存先の選択を組み立て側に委ね、責務を分けられる。

Unit 04 では、この性質を利用して Test から Fake、Mock、固定 `Clock` を直接渡す。  
Spring の DI Container を起動しなくても、Java の Constructor Injection として成立する点が重要となる。

### 制御できない入力を減らす

時刻、乱数、System Property、共有状態などは、Test Code に明示されないまま結果へ影響し得る。  
同じ Test 入力でも結果が変わる場合は、その処理の本当の入力がほかにないかを確認する。

固定 `Clock` や `IntSupplier` のような差し替え可能な依存先を利用すると、Test の前提条件を明示しやすい。  
ただし、依存先を増やせばよいわけではなく、Test から制御する必要がある外部状態を適切に見極める。

### Test の境界は保証範囲でもある

Mock Repository を用いた Test で Service の返却結果が正しくても、SQL の構文や DB Transaction の正しさまで確認したことにはならない。  
同様に Fake の在庫が変化しても、PostgreSQL の更新が正しいとは限らない。

狭い Test Boundary は Service の問題を速く切り分けるのに適するが、広い範囲の接続確認は別の Test が必要となる。  
Unit Test を増やすことと Integration Test が不要になることは同じではない。

### Isolation と Independence を区別して理解する

Isolation と Independence は互いに関係するが、注目する点が異なる。

```text
Isolation:
外部依存や別 Test の状態から不要な影響を分離する

Independence:
ほかの Test を先に実行しなくても成立する
```

単に実行順を固定することは独立性を実現しない。  
各 Test が必要な Fixture を新しく用意することで、単独実行、順序変更、繰り返し実行でも成立する構成を作れる。

### mutable state の共有範囲を見る

`static final` で宣言されているから安全とは限らない。  
参照先が mutable な `Stock` であれば、Test 1 が変更した `quantity` を Test 2 が観測する可能性がある。

一方、変更できない `Instant` や `String` の定数は、Test 全体で共有しても別 Test の副作用を引き継がない。  
宣言方法だけでなく、参照先の Object とその更新方法まで確認する。

### Deterministic と Repeatable は設定だけでは成立しない

固定 `Clock` を渡し、同じ Fixture を毎回新しく生成することは決定性・再現性を高める。  
しかし、Test が実時刻や外部状態をほかの箇所で参照していれば不安定さは残る。

`@RepeatedTest` は同じ確認を繰り返す機能であり、Test の前提を独立させる機能ではない。  
**再現できる理由は Annotation ではなく、依存先・Fixture・期待値の設計にある**。

### Fast と必要な検証範囲を両立させる

Plain Unit Test は外部 I/O や Spring Context を動かさず、素早く結果を確認しやすい。  
それにより Service の変更後も頻繁に Test を実行できる。

ただし、速度だけを優先して Repository や HTTP Client の接続確認を省略してはいけない。  
Unit ごとに、どの境界を確認するかを設計することが重要となる。

### Test が成功するだけでは品質を判断できない

SUT 自体を Mock にし、Test が設定した Stub 値を Assertion すれば、実際の Production Code を動かさずに成功できる。  
また `isNotBlank()` のように弱い Assertion では、期待していない商品名でも成功する。

Test の成功だけでなく、**どの Production Logic を実行し、どの仕様をどの条件で保証したか**を説明できることが必要となる。

### Test Oracle を仕様から決める

Test Code が何を成功条件とするかは、Test 品質の中心となる。  
「文字列が空ではない」だけでよいのか、「`Standard Product` と完全一致する」ことが必要なのかは、対象の仕様によって決まる。

Unit 02 の Test Oracle の考え方は Unit 04 にもつながる。  
Production Code が返した値をそのまま expected にするのではなく、期待結果の根拠を明確にする。

### interaction verification も仕様に基づいて選ぶ

Mock の `verify()` は依存境界の副作用や、行ってはいけない処理を確認するのに有効である。  
一方、仕様に含まれない call 数や内部順序まで Test が固定すると、正しい refactoring を妨げる場合がある。

何を検証するかの根拠を、内部の実装手順ではなく Test Case が保証したい振る舞いへ結び付ける。  
Unit 03 で学んだ Mockito の技術を、Unit 04 では品質の判断として使い直す。

### Flaky Test と Brittle Test の原因は別に考える

Flaky Test と Brittle Test では、問題の現れ方と見直すべき点が異なる。

```text
Flaky Test:
同じコードでもタイミングや状態により成否が変わる
→ 不確定要因を制御する

Brittle Test:
仕様上意味のない実装変更に対して壊れやすい
→ 仕様と関係の薄い検証を見直す
```

分類名だけで対応を決めず、具体的な失敗条件を確認する。

### 非同期の完了は推測せず条件で待つ

非同期処理では、「時間が経ったこと」と「処理が完了したこと」を区別する。

```text
sleep:
一定時間が経過するまで待つ
→ 別 Thread の処理完了を保証しない

Future の期限付き完了待ち:
完了を知る仕組みを利用して待つ
→ 完了条件と Timeout を明示できる
```

外部状態の変化を監視する必要がある場合には、Awaitility のように期待条件を polling する方法も選択肢になる。  
いずれも、無期限に待つのではなく完了条件と Timeout を設計する。

### False Positive / Negative は基準を明示する

Unit 04 では、欠陥の検出を Positive として次のように整理する。

```text
False Positive:
正しい実装なのに Test が問題ありと誤判定する（失敗）

False Negative:
誤った実装なのに Test が問題なしと誤判定する（成功）
```

この呼び方は文脈によって揺れ得るため、用語の暗記よりも Test が何を誤判定したかを説明することが重要となる。

根拠のない `times(2)` や弱い `isNotBlank()` の例は、それぞれ異なる方向で Test の信頼性を損なう。

### Flaky Test は Retry ではなく原因を修正する

Retry の成功は、実行順依存や非同期の Race Condition が解消された証拠ではない。  
偶然成功する状況を増やすだけでは、Test の結果への信頼は回復しない。

失敗した Test の初期状態・依存関係・完了条件・期待値を調べ、変動の原因を取り除く。  
再実行は修正結果を確認するために利用し、不具合を隠す目的にしない。

### 悪い例を本番の Test Suite へ混ぜない

Unit 04 では、失敗や不安定さが生じる理由を説明するために問題のある Test を用意している。  
それらを通常実行する `src/test/java` から分離したことにも意図がある。

通常の Test Suite は安定して仕様を検証するものであり、失敗を観察する教材を混ぜると結果を信用しにくくなる。  
教材の実行範囲自体を管理することも Test 品質の一部となる。

## 補足

### 補助教材が通常の Test Source に含まれない理由

4つの補助教材は `units/04-testability-test-quality/examples/` に配置している。  
これは学習目的の意図的な Bad Example を、メインアプリの Production Code と通常の自動テスト群から分離するためである。

ファイルに `@Test` が書かれていても、既存 Maven 設定の `src/test/java` 配下にないため、通常の `./mvnw test` では自動実行されない。  
補助教材を追加でビルド・実行できる特別な Maven Profile や Runner は今回用意していない。実行せずにコードの差を読むことを想定する。

### すべての外部依存を排除すればよいわけではない

Unit 04 は外部依存を Test Double や固定値で制御するが、実際のアプリケーションに DB や HTTP Client が不要だという意味ではない。  
Unit 06 以降では PostgreSQL、Spring Context、外部 HTTP API などを実際に動かすことで、より広い境界を確認する。

必要な Test の種類は、保証したい振る舞いと依存先のどこまでを本物で動かすかによって変わる。

### `@TestMethodOrder` 自体を禁止するものではない

順序を指定できる JUnit の機能が悪いわけではない。  
ただし、`@TestMethodOrder` を利用して別 Test の副作用に頼る設計は、個別実行や並列実行、再実行で問題になり得る。

本来1つの連続した Scenario として検証する内容なら、その Test 自身が前提から結果までを管理する形を検討する。  
この Unit では、独立しているべき複数の Test Method が共有状態によって結び付く例を扱う。

### `Clock.fixed(...)` と Mockito Mock の選択

`Clock` の制御には、Unit 03 と Unit 04 で異なる方法を利用している。

```text
Unit 03:
Mockito Mock + when(clock.instant())
→ Stub した値で時刻を制御する

Unit 04:
JDK の Clock.fixed(...)
→ 固定時刻を返す実 Object で制御する
```

同じ依存先であっても、Test の目的によって適切な選択は変わる。  
「Mock にできること」と「Mock にする必要があること」は一致しない。

### Awaitility は今回導入しない

Awaitility は非同期状態の検証で利用されるが、Unit 04 の `pom.xml` には追加していない。  
補助教材では `CompletableFuture.get(timeout)` で完了を待つ例を利用し、Awaitility は関連技術の位置づけを理解する範囲に留める。

外部状態の polling が必要な Test を今後設計するとき、完了条件・Timeout・polling 間隔を考えるための選択肢となる。

### Unit 05 以降へのつながり

Unit 05 では Spring Web 層の Test、Unit 06～07 では Repository / DB / Spring の Integration Test、Unit 08～09 では外部 HTTP API と REST API 全体の Test を学ぶ。  
Test Boundary が広がると、Fixture、外部 Resource、実行時間、失敗原因の切り分けがより重要になる。

Unit 04 で扱う Isolation、Independence、Deterministic、Repeatable、Fast、Self-validating、Flaky Test の考え方は、後続の Unit Test / Integration Test にも適用する基本的な判断基準となる。
