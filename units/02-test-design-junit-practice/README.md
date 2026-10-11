# 02. テストケース設計と JUnit 実践

## この Unit の目的

この Unit では、Unit 01 で学んだ JUnit の基本を土台として、仕様や業務ルールから「何を Test Case として用意するべきか」を体系的に考える方法を学ぶ。  
Test Code を書き始めてから思いついた値を追加するのではなく、Production Code を実行する前に仕様を読み、正常系・異常系・境界値・状態遷移などの観点から必要なケースを整理することを重視する。

また、同じ構造の Test Case が複数存在する場合に、JUnit の Parameterized Test を利用して入力値と期待値を整理する方法を確認する。  
`@ValueSource`、`@CsvSource`、`@MethodSource` をそれぞれ適したデータの複雑さに応じて使い分ける。

Assertion については、Unit 01 で利用した JUnit Assertions に加えて AssertJ を導入する。  
JUnit と AssertJ のどちらが優れているかという単純な比較ではなく、JUnit が Test の実行基盤・Lifecycle・Parameterized Test などを担当し、AssertJ が読みやすい fluent assertion を提供する Assertion Library であることを理解する。

Unit 02 でも Spring Context、PostgreSQL、HTTP Server、外部 API は使用しない。  
テスト境界を小さな Java Object に保ったまま、Test Case Design と JUnit の実践機能に集中する。

## 学習内容

### Unit 01 から Unit 02 への変化

Unit 01 では、すでに選ばれた代表的な Test Case を読みながら、`@Test`、基本 Assertion、AAA、Given-When-Then、Test Runner などを学んだ。

Unit 02 では、その一段前へ戻り、

```text
仕様を読む
↓
条件・分岐・境界・状態を整理する
↓
必要な Test Case を決める
↓
期待値を決める
↓
JUnit / AssertJ で表現する
```

という流れを中心に学ぶ。

重要なのは、Test Code の書き方より先に「何を確認する必要があるか」を考えることである。

### 正常系

正常系は、有効な入力や許可された状態で、仕様どおり正常に処理が成立するケースを指す。

Unit 02 では、例えば注文数量の次の値が正常系となる。

```text
1
2
98
99
```

仕様では注文数量が `1 <= quantity <= 99` と定義されているため、これらはすべて有効な入力となる。

正常系を確認するときも、典型的な値だけを見るのではなく、下限・上限など仕様上重要な位置にある値を含める必要がある。

### 異常系

異常系は、仕様上許可されていない入力や状態に対して、期待する拒否・例外・Error が発生することを確認するケースを指す。

注文数量では次が異常系となる。

```text
0
100
```

単に「例外が出ること」ではなく、

```text
どの入力が不正なのか
どの種類の Exception を期待するのか
必要ならどの message / error code を期待するのか
```

まで Test Oracle として整理する。

### Happy Path

Happy Path は、機能が想定する代表的で問題のない流れを通る正常系を指す。

Unit 02 の金額計算では、例えば次のようなケースを Happy Path として考えられる。

```text
有効な OrderItem を準備
↓
送料も正常値
↓
金額計算を実行
↓
PriceSummary が正常に返る
```

Happy Path は最初に機能の基本成立を確認するうえで重要だが、Happy Path だけでは境界条件や例外条件の問題を検出できない。

### Edge Case

Edge Case は、通常の代表値とは異なる端や特殊条件に位置するケースを指す。

今回の仕様では、次のような値が Edge Case の代表となる。

```text
quantity:
1
99

discount threshold:
9,999
10,000
10,001
```

Edge Case が必ず異常系になるわけではない。  
例えば `quantity = 1` と `quantity = 99` は有効な値だが、有効範囲の端に位置するため重要な Edge Case となる。

### 境界値分析

境界値分析は、条件が切り替わる境界の周辺を重点的に Test Case として選ぶ考え方である。

仕様が次の場合を考える。

```text
1 <= quantity <= 99
```

代表的な境界値は次となる。

```text
0
1
2

98
99
100
```

それぞれ次の意味を持つ。

|  値 | 意味           | 期待結果 |
| --: | -------------- | -------- |
|   0 | 下限のすぐ外側 | 無効     |
|   1 | 下限           | 有効     |
|   2 | 下限のすぐ内側 | 有効     |
|  98 | 上限のすぐ内側 | 有効     |
|  99 | 上限           | 有効     |
| 100 | 上限のすぐ外側 | 無効     |

すべての整数を Test するのではなく、仕様上振る舞いが変わる位置を中心に選ぶ。

同様に割引仕様は、

```text
subtotal < 10,000
→ 割引なし

subtotal >= 10,000
→ 10% 割引
```

であるため、次を確認する。

```text
9,999
10,000
10,001
```

この 3 値により、条件切り替えの直前・境界そのもの・直後を確認できる。

### 同値分割

同値分割は、同じ振る舞いを期待できる入力範囲をグループに分け、そのグループから代表値を選ぶ考え方である。

注文数量では、概念的に次の 3 区分へ分けられる。

```text
quantity <= 0
→ 無効

1 <= quantity <= 99
→ 有効

quantity >= 100
→ 無効
```

すべての値を確認しなくても、同じ振る舞いになる範囲を代表値で確認することで、Test Case 数を現実的な量へ抑えられる。

Unit 02 の `OrderItemQuantityTest` では、境界値分析と同値分割を組み合わせている。

### 境界値分析と同値分割の関係

同値分割は「どの入力範囲が同じ振る舞いか」を整理する。  
境界値分析は「その範囲が切り替わる場所の周辺で何を確認するか」を整理する。

例えば、

```text
無効 | 有効                | 無効
... 0 | 1 ............. 99 | 100 ...
```

という分割を考えた後、

```text
0 / 1 / 2
98 / 99 / 100
```

を選ぶことで、各区分と境界を効率よく確認できる。

### Decision Table

Decision Table は、複数の条件と、その条件に対する結果・Action を表として整理する方法である。

今回の `OrderStatus` 仕様は、状態と操作の組み合わせとして次のように整理できる。

| 現在状態    | confirm | ship | cancel |
| ----------- | ------- | ---- | ------ |
| `CREATED`   | 可      | 不可 | 可     |
| `CONFIRMED` | 不可    | 可   | 可     |
| `SHIPPED`   | 不可    | 不可 | 不可   |
| `CANCELLED` | 不可    | 不可 | 不可   |

Production Code を見ながら Test Case を考えるのではなく、先にこの仕様表を Test Case の基準とする。

### Test Matrix

Test Matrix は、確認したい条件・状態・入力などの組み合わせを一覧化し、Test の抜けを確認するために利用する。

Unit 02 では `OrderStatus` の 4 状態に対して、

```text
canConfirm
canShip
canCancel
```

の 3 判定を確認する。

概念上は、

```text
4 states × 3 operations = 12 combinations
```

となる。

実際の Test Code では 12 個の Test Method を作るのではなく、1 状態を 1 行として 4 回の Parameterized Test invocation で表現し、各行で 3 つの判定を `assertAll` にまとめている。

Test Matrix は「Test Method を大量に作ること」が目的ではない。  
組み合わせを可視化し、必要な条件が抜けていないかを確認することが目的となる。

### Decision Table と Test Matrix

両者は似た形になる場合があるが、観点が少し異なる。

```text
Decision Table:
業務上の条件と Action / 結果を整理する

Test Matrix:
どの条件・組み合わせを Test するかを整理する
```

今回の状態遷移表は Business Rule の Decision Table として読み、その表を Test Matrix として Test Case へ落とし込んでいる。

### Test Oracle

Test Oracle は、Test の実際結果が正しいかどうかを判断するための期待結果・判断基準を指す。

例えば、

```text
subtotal = 10,000
```

に対して、

```text
expectedDiscount = 1,000
```

とする根拠は Production Code の `calculateDiscount()` の実装ではない。

根拠は仕様の、

```text
10,000 円以上
→ 10% 割引
```

である。

Test Oracle を Production Code そのものから作ると、Production Code が誤っていた場合に Test も同じ誤りを再現してしまう危険がある。

期待値は可能な限り、

```text
業務仕様
要件
設計
API Contract
既知の計算規則
```

など、Test 対象とは独立した根拠から決める。

### 期待値の決め方

期待値は「現在の Production Code が返した値」ではなく、「仕様上返るべき値」とする。

例えば複数明細ケースでは、

```text
Product A:
3,000 × 2 = 6,000

Product B:
2,500 × 2 = 5,000

subtotal:
6,000 + 5,000 = 11,000

discount:
11,000 × 10% = 1,100

shippingFee:
800

total:
11,000 - 1,100 + 800
= 10,700
```

と仕様から独立して計算できる。

この結果を Test Oracle として、

```text
subtotal = 11,000
discountAmount = 1,100
shippingFee = 800
totalAmount = 10,700
```

を Assertion する。

### 条件分岐とテストケース

Production Code に `if` が 1 個あるから Test Case を 2 個作る、というように実装構造だけから機械的に Test を作るべきではない。

まず仕様を見る。

割引仕様なら、

```text
subtotal < 10,000
subtotal >= 10,000
```

という 2 つの仕様上の条件が存在する。

そこから同値分割と境界値分析を行い、

```text
9,999
10,000
10,001
```

という Test Case を選ぶ。

実装の branch は確認材料にはなるが、Test Case の主な根拠は仕様とする。

### 状態遷移とテストケース

状態を持つ Object では、入力値だけでなく「現在どの状態か」が Test Case を決める重要な条件となる。

今回の `OrderStatus` には、

```text
CREATED
CONFIRMED
SHIPPED
CANCELLED
```

が存在する。

同じ `canCancel()` でも状態によって結果が異なる。

```text
CREATED
→ true

CONFIRMED
→ true

SHIPPED
→ false

CANCELLED
→ false
```

状態を持つ処理では、単一の正常系だけを見るのではなく、状態遷移表や Test Matrix を使って現在状態と操作の組み合わせを整理する。

### テストケースの重複を避ける考え方

Test Case の重複を避けるとは、異なる意味を持つ重要な Test Case を削除することではない。

例えば、

```text
9,999
10,000
10,001
```

は似た値だが、それぞれ境界直前・境界・境界直後という異なる意味を持つため削除しない。

一方で、3 ケースすべてについて次のような同じ Test Method をコピーする必要はない。

```java
@Test
void case1() {
    // ...
}

@Test
void case2() {
    // ...
}

@Test
void case3() {
    // ...
}
```

Parameterized Test を利用すれば、

```text
Test Case は残す
Test Code の重複は減らす
```

ことができる。

Test Design 上のケース削減と、Test Code 上の重複削減を混同しないことが重要となる。

### Test Fixture

Test Fixture は、Test を実行するために必要となる事前状態・Object・Test Data などを指す。

Unit 02 の `OrderTestFixtureTest` では、例えば次が Fixture となる。

```text
fixedCreatedAt
Order
OrderItem List
PriceSummary
```

Fixture は単に helper method や field のことではない。  
Test を成立させるために準備する状態全体を表す概念となる。

### Setup / Teardown

Setup は Test 実行前に Fixture を準備する処理、Teardown は Test 実行後に後片付けする処理を指す。

今回の Plain Unit Test では DB Connection や Server などの外部 Resource を持たないため、本質的には複雑な cleanup は必要ない。

それでも Lifecycle Annotation の実行タイミングを確認する教材として、

```text
@BeforeAll
@BeforeEach
@AfterEach
@AfterAll
```

を `OrderTestFixtureTest` で使用する。

後続の DB Test や WireMock Test では、Setup / Teardown が Test Data や外部 Resource の状態管理としてより重要になる。

### `@BeforeEach`

`@BeforeEach` は各 Test Method の実行前に毎回実行される。

今回の Test では、

```java
@BeforeEach
void setUp() {
    // Order Fixture を新規生成
}
```

としている。

Test Method ごとに新しい `Order` を作ることで、ある Test が Fixture を変更しても別 Test へ状態が引き継がれにくい構成になる。

### `@AfterEach`

`@AfterEach` は各 Test Method の実行後に毎回実行される。

```java
@AfterEach
void tearDown() {
    order = null;
}
```

今回の `Order` は Java Object だけなので、実務上この `null` 代入自体が必須な cleanup というわけではない。  
Unit 02 では `@AfterEach` の実行タイミングを明示的に確認するための教材として配置している。

実務では、

```text
一時 Resource の解放
Test 用状態の後片付け
Mock Server の状態リセット
```

など、実際に cleanup が必要な場合に利用する。

### `@BeforeAll`

`@BeforeAll` は Test Class 全体の Test 実行前に 1 回だけ実行される。

今回の Test では、

```java
@BeforeAll
static void setUpClassFixture() {
    fixedCreatedAt = Instant.parse("2026-10-01T00:00:00Z");
}
```

として、全 Test で共通となる固定時刻を準備する。

JUnit Jupiter の標準的な Test Instance Lifecycle では Test Method ごとに Test Instance が生成されるため、通常の `@BeforeAll` / `@AfterAll` method は `static` とする。

### `@AfterAll`

`@AfterAll` は Test Class 全体の Test 実行後に 1 回だけ実行される。

```java
@AfterAll
static void tearDownClassFixture() {
    fixedCreatedAt = null;
}
```

今回も外部 Resource の解放が必要なわけではなく、Class Scope Fixture の lifecycle を確認するための例となる。

### Lifecycle の実行順

`OrderTestFixtureTest` の概念的な順序は次となる。

```text
@BeforeAll
↓
@BeforeEach
↓
Test Method 1
↓
@AfterEach
↓
@BeforeEach
↓
Test Method 2
↓
@AfterEach
↓
@AfterAll
```

`@BeforeEach` で毎回 `Order` が再生成されることが重要となる。

### `@Nested`

`@Nested` は、関連する Test を inner class 単位でグループ化するために利用する。

`OrderItemQuantityTest` では、

```text
OrderItem 注文数量の境界値
├─ 有効な数量
└─ 無効な数量
```

と整理している。

`OrderPriceCalculatorParameterizedTest` では、

```text
OrderPriceCalculator の Test Case 設計
└─ 10,000 円の割引境界
```

という grouping に使用している。

`@Nested` は Test の実行条件を変えるものではなく、関連する Test の構造や意図を読みやすくするために利用できる。

### `@DisplayName`

`@DisplayName` は Test Class や Test Method に、人が読むための表示名を付ける Annotation である。

例えば、

```java
@DisplayName("OrderStatus の状態遷移 Test Matrix")
```

のように利用する。

Java method name だけでは表現しにくい日本語の説明を Test Result 上へ表示できる。

ただし、`@DisplayName` を付ければ Test Method の命名が不要になるわけではない。  
Code 上でも意味が読み取れる method name を維持し、そのうえで Test Result の可読性を高める目的で利用する。

### `assertThrows`

Unit 01 では JUnit Assertions の `assertThrows` を利用した。

```java
IllegalArgumentException exception =
        assertThrows(
                IllegalArgumentException.class,
                () -> calculator.calculate(items, shippingFee));
```

Unit 02 では Test Case Design の観点から、異常系にどの Exception を期待するのかを先に決めることが重要となる。

今回新規追加した `OrderItemQuantityTest` では同じ目的を AssertJ の、

```java
assertThatThrownBy(...)
        .isInstanceOf(...)
        .hasMessage(...);
```

で表現している。

JUnit `assertThrows` と AssertJ の exception assertion は、どちらも「期待する例外を検証する」ための方法となる。  
Unit 02 では両方を比較し、Assertion Library による表現方法の違いを理解する。

### `assertAll`

`assertAll` は、関連する複数の Assertion を 1 つのグループとして実行する JUnit の機能である。

通常の Assertion を順番に書いた場合、途中の Assertion が失敗すると、その後の Assertion まで到達しない。

`assertAll` では、

```java
assertAll(
        "PriceSummary",
        () -> assertEquals(expectedSubtotal, actual.getSubtotal()),
        () -> assertEquals(expectedDiscount, actual.getDiscountAmount()),
        () -> assertEquals(STANDARD_SHIPPING_FEE, actual.getShippingFee()),
        () -> assertEquals(expectedTotal, actual.getTotalAmount()));
```

のように関連する複数条件をまとめて確認できる。

Unit 02 では、

```text
PriceSummary の複数 field
OrderStatus の複数操作可否
```

に利用している。

すべての Assertion を機械的に `assertAll` へ入れるのではなく、同じ結果 Object や同じ Test Case の関連条件をひとまとまりとして確認したい場合に利用する。

### Parameterized Test

Parameterized Test は、同じ Test Logic を複数の入力値・期待値で繰り返し実行する JUnit Jupiter の機能である。

通常の `@Test` では 1 Test Method が 1 回実行される。

Parameterized Test では、

```java
@ParameterizedTest
@ValueSource(ints = {1, 2, 98, 99})
void test(int quantity) {
    // ...
}
```

のようにすると、同じ Test Method がそれぞれの引数で複数回実行される。

重要なのは、Test Case が 1 個に減ったわけではないという点となる。

```text
quantity=1
quantity=2
quantity=98
quantity=99
```

はそれぞれ独立した Test invocation として実行される。

Parameterized Test は Test Case を消す機能ではなく、同じ構造の Test Code の重複を減らす機能と考える。

### `@ValueSource`

`@ValueSource` は、1 parameter の単純な値を複数渡す場合に適している。

今回の `OrderItemQuantityTest` では、

```java
@ValueSource(ints = {1, 2, 98, 99})
```

と、

```java
@ValueSource(ints = {0, 100})
```

を利用する。

1 個の `int quantity` だけを変化させるケースなので、`@ValueSource` が最も単純に表現できる。

### `@CsvSource`

`@CsvSource` は、1 Test Case につき複数の値を渡す場合に利用できる。

割引境界では、

```java
@CsvSource({
    "9999, 0",
    "10000, 1000",
    "10001, 1000"
})
```

として、

```text
subtotal
expectedDiscount
```

を 1 行に対応させている。

状態遷移では、

```java
@CsvSource({
    "CREATED,   true,  false, true",
    "CONFIRMED, false, true,  true",
    "SHIPPED,   false, false, false",
    "CANCELLED, false, false, false"
})
```

として、

```text
status
canConfirm
canShip
canCancel
```

を Decision Table と同じ形で表現している。

文字列から `OrderStatus` enum への parameter conversion は JUnit Jupiter が行う。

### `@MethodSource`

`@MethodSource` は、単純な文字列や primitive だけでは表現しにくい複雑な Test Data を供給する場合に適している。

今回の `amountCases()` は、

```text
caseName
List<OrderItem>
expectedSubtotal
expectedDiscount
expectedTotal
```

という複数の Object を `Arguments` として返す。

```java
static Stream<Arguments> amountCases() {
    return Stream.of(
            Arguments.of(...),
            Arguments.of(...));
}
```

これにより、

```text
単一明細・割引なし
複数明細・割引あり
```

を同じ Test Logic で確認できる。

### Parameter Source の使い分け

今回の使い分けは次となる。

| Source          | 今回の用途          | 適したデータ                        |
| --------------- | ------------------- | ----------------------------------- |
| `@ValueSource`  | 注文数量            | 1 parameter の単純値                |
| `@CsvSource`    | 割引境界 / 状態遷移 | 複数の単純値を行単位で表現          |
| `@MethodSource` | 注文明細と期待金額  | List や Object を含む複雑な Fixture |

すべてを `@MethodSource` に統一することも技術的には可能だが、最も単純な表現で意図が読み取れる Source を選ぶことを優先する。

### Parameterized Test の表示名

今回の Test では、

```java
@ParameterizedTest(name = "quantity={0} は有効")
```

や、

```java
@ParameterizedTest(name = "{0}")
```

を利用している。

`{0}` などは parameter を Test invocation の表示名へ埋め込むために使う。

Parameterized Test は 1 method から複数 Test が実行されるため、どの parameter の invocation が失敗したのかを表示名から判断できることが重要となる。

### AssertJ

AssertJ は Java 向けの Assertion Library で、fluent な method chain によって期待条件を表現できる。

Unit 02 では `pom.xml` に次を追加している。

```xml
<dependency>
  <groupId>org.assertj</groupId>
  <artifactId>assertj-core</artifactId>
  <scope>test</scope>
</dependency>
```

Test Code では、

```java
assertThat(actual)
        .isNotNull()
        .satisfies(...);
```

や、

```java
assertThat(order.getItems())
        .hasSize(2)
        .extracting(OrderItem::getProductId)
        .containsExactly(1L, 2L);
```

のように利用する。

### fluent assertion

fluent assertion は、Assertion を method chain として読める形で記述する方法を指す。

例えば、

```java
assertThat(actual.getQuantity()).isEqualTo(quantity);
```

は、

```text
actual.getQuantity()
が
quantity と等しい
```

という流れで読みやすい。

複数条件も対象 Object から連続して記述できるため、Object や Collection の複雑な検証で特に利用しやすい。

### Object の検証

`OrderTestFixtureTest` では、

```java
assertThat(order)
        .isNotNull()
        .satisfies(
                actual -> {
                    // ...
                });
```

を利用する。

`Order` そのものが存在することを確認したうえで、その Object が持つ複数の property を確認している。

ここでは AssertJ が Object 全体の equals 比較を自動で行っているわけではない。  
`satisfies` 内で、今回保証したい property を明示している。

### Collection の検証

AssertJ は Collection に対しても専用の Assertion を提供する。

今回の Test では、

```java
assertThat(order.getItems())
        .hasSize(2)
        .extracting(OrderItem::getProductId)
        .containsExactly(1L, 2L);
```

としている。

ここでは、

```text
明細数が 2
Product ID が 1, 2 の順
```

を確認する。

さらに、

```java
.extracting(OrderItem::getQuantity)
.containsExactly(2, 2);
```

によって数量も確認する。

Collection を for loop で 1 件ずつ比較するより、Test が何を期待しているのかを declarative に表現しやすい。

### Exception の検証

AssertJ では、

```java
assertThatThrownBy(() -> targetMethod())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("...");
```

のように Exception を検証できる。

今回の `OrderItemQuantityTest` では、quantity `0` と `100` に対して、

```text
IllegalArgumentException
quantity must be between 1 and 99
```

を期待する。

JUnit の `assertThrows` で Exception Object を取得して別の Assertion を書く方法と比べ、例外型・message などを fluent に連続して書ける。

### BigDecimal と AssertJ

Unit 01 では、JUnit の `assertEquals` で `BigDecimal` を比較するときに `equals()` の scale も関係することを確認した。

Unit 02 の割引境界では、

```java
assertThat(actual)
        .isEqualByComparingTo(BigDecimal.valueOf(expectedDiscount));
```

を利用する。

`isEqualByComparingTo` は `BigDecimal.compareTo()` に基づいて数値として比較するため、scale の違いを無視した金額比較を表現できる。

例えば、

```text
6000
6000.00
```

を数値として同額と扱いたい Test では、このような Assertion が意図に合う場合がある。

どの Assertion を使うかは、「何を同じとみなす仕様なのか」に合わせて選ぶ。

### JUnit Assertions と AssertJ の役割の違い

JUnit と AssertJ は同じ役割の Framework を完全に置き換え合う関係ではない。

今回の構成を整理すると、

```text
JUnit Jupiter
├─ @Test
├─ @ParameterizedTest
├─ @BeforeEach / @AfterEach
├─ @BeforeAll / @AfterAll
├─ @Nested
├─ @DisplayName
├─ Parameter Source
├─ Test 実行
└─ JUnit Assertions

AssertJ
└─ Assertion Library
   ├─ fluent assertion
   ├─ Object assertion
   ├─ Collection assertion
   ├─ Exception assertion
   └─ BigDecimal assertion
```

AssertJ を導入しても、JUnit Jupiter が Test Framework として不要になるわけではない。

Unit 02 では両方を混在させている。

例えば `OrderStatusTransitionTest` と `OrderPriceCalculatorParameterizedTest` の `assertAll` は JUnit Assertions を利用し、Object / Collection / Exception などの表現では AssertJ を利用する。

Project 全体でどの Assertion Library を中心にするかは Team の方針にもよる。  
この Unit では、両者の特徴を実際に読み比べられることを優先する。

## 使用する技術・ツール

### Java 21

Production Code と Test Code の実装言語として利用する。

Unit 02 でも Test 対象は Plain Java Object であり、Spring Test API は利用しない。

### JUnit Jupiter

Test Framework として引き続き利用する。

Unit 02 では Unit 01 の `@Test` / Assertions に加え、主に次を利用する。

```text
@ParameterizedTest
@ValueSource
@CsvSource
@MethodSource
Arguments
@BeforeEach
@AfterEach
@BeforeAll
@AfterAll
@Nested
@DisplayName
assertAll
```

### AssertJ Core

Unit 02 から新しく導入する Assertion Library。

主に次を利用する。

```text
assertThat
assertThatThrownBy
isEqualTo
isEqualByComparingTo
isNotNull
satisfies
hasSize
extracting
containsExactly
isInstanceOf
hasMessage
```

### Maven / Maven Wrapper

Unit 01 と同様に Test 実行の入口として利用する。

```bash
./mvnw test
```

Unit 02 でも Docker、PostgreSQL、WireMock、Spring Boot Application の起動は不要となる。

## テスト対象とテスト境界

Unit 02 では Test Case Design と JUnit 実践に集中するため、Test 対象は引き続き Spring に依存しない Domain / Service の小さな範囲とする。

### `OrderItemQuantityTest`

```text
SUT:
OrderItem

主な仕様:
1 <= quantity <= 99

確認:
境界値
同値分割
正常系
異常系
AssertJ exception assertion

本物:
OrderItem
BigDecimal

Test Double:
なし

起動しない:
Spring Context
DB
HTTP
外部 API
```

### `OrderStatusTransitionTest`

```text
SUT:
OrderStatus

主な仕様:
4 状態 × confirm / ship / cancel

確認:
Decision Table
Test Matrix
状態遷移
@CsvSource
assertAll

Test Double:
なし
```

### `OrderTestFixtureTest`

```text
SUT:
Order

主な学習対象:
Test Fixture
Setup / Teardown
Lifecycle Annotation
AssertJ Object / Collection Assertion

本物:
Order
OrderItem
PriceSummary
Instant
List

Test Double:
なし
```

### `OrderPriceCalculatorParameterizedTest`

```text
SUT:
OrderPriceCalculator

主な仕様:
10,000 円割引境界
単一明細 / 複数明細
注文金額計算

確認:
境界値分析
@CsvSource
@MethodSource
assertAll
AssertJ BigDecimal assertion

Test Double:
なし
```

### Unit 02 のテスト境界

全 Test の概念的な境界は次となる。

```text
Test Code
↓
Domain / 小さな Service
```

次は含まない。

```text
Spring Context
Controller
Repository
PostgreSQL
ShippingFeeClient
HTTP
WireMock
Docker
```

依存関係を持つ `OrderService` の Unit Test と Test Double / Mockito は Unit 03 で扱う。

## 使用するファイル

### この Unit で追加・変更する主なファイル

- [`pom.xml`](../../pom.xml)
- [`OrderItemQuantityTest.java`](../../src/test/java/com/example/orderapi/domain/OrderItemQuantityTest.java)
- [`OrderStatusTransitionTest.java`](../../src/test/java/com/example/orderapi/domain/OrderStatusTransitionTest.java)
- [`OrderTestFixtureTest.java`](../../src/test/java/com/example/orderapi/domain/OrderTestFixtureTest.java)
- [`OrderPriceCalculatorParameterizedTest.java`](../../src/test/java/com/example/orderapi/service/OrderPriceCalculatorParameterizedTest.java)

### コードリーディングで参照する主な Production Code

- [`OrderItem.java`](../../src/main/java/com/example/orderapi/domain/OrderItem.java)
- [`OrderStatus.java`](../../src/main/java/com/example/orderapi/domain/OrderStatus.java)
- [`Order.java`](../../src/main/java/com/example/orderapi/domain/Order.java)
- [`PriceSummary.java`](../../src/main/java/com/example/orderapi/domain/PriceSummary.java)
- [`OrderPriceCalculator.java`](../../src/main/java/com/example/orderapi/service/OrderPriceCalculator.java)

### Unit 01 から比較のために参照する Test Code

- [`OrderPriceCalculatorTest.java`](../../src/test/java/com/example/orderapi/service/OrderPriceCalculatorTest.java)
- [`OrderTest.java`](../../src/test/java/com/example/orderapi/domain/OrderTest.java)

Unit 01 の Test は削除・置換せず、そのまま残す。  
Unit 02 では同じ Production Code に対して、より体系的な Test Case Design と JUnit の機能を追加している。

## 前提

Unit 01「自動テストの全体像と JUnit 基本」を完了していることを前提とする。

次の内容は Unit 01 で理解済みとして扱う。

```text
Automated Test の基本的な目的
SUT
Test Case / Test Scenario
Unit Test
JUnit Platform / Jupiter
@Test
src/test/java
基本的な Assertion
assertEquals
assertTrue / assertFalse
assertNull / assertNotNull
assertThrows の基本
Arrange-Act-Assert
Given-When-Then
Test Discovery
Maven からの Test 実行
Assertion Failure / Stack Trace の基本
```

Unit 02 ではこれらを再びゼロから説明するのではなく、Test Case Design と JUnit の実践機能へ発展させる。

Java の `Stream`、method reference、lambda、enum、List、BigDecimal などは Java の既習事項として扱う。  
ただし、Test Code の中で何のために利用しているかはコードリーディングで確認する。

## コードリーディング

Unit 02 では、最初に Production Code を細かく追うより、まず仕様を Test Case の表へ整理し、その後で Test Code と対応させる。

基本順序は次とする。

```text
仕様を確認
↓
Test Case / Test Matrix を整理
↓
Production Code の対象責務を確認
↓
Test Code を読む
↓
Parameter Source と Test Oracle を確認
↓
Assertion を確認
↓
実行結果を確認
```

### 1. 注文数量の仕様を表にする

まず注文数量仕様を確認する。

```text
1 <= quantity <= 99
```

Test Code を読む前に、次の表を作る。

| quantity | 分類     | 期待結果  |
| -------: | -------- | --------- |
|        0 | 下限外   | Exception |
|        1 | 下限     | 成功      |
|        2 | 下限直後 | 成功      |
|       98 | 上限直前 | 成功      |
|       99 | 上限     | 成功      |
|      100 | 上限外   | Exception |

この段階では `OrderItem.create()` の `if` 文を答えとして使わない。  
仕様から期待結果を決める。

### 2. `OrderItem.java` を確認する

[`OrderItem.java`](../../src/main/java/com/example/orderapi/domain/OrderItem.java) を開き、quantity に関する責務を確認する。

見るポイント:

- quantity の許可範囲。
- `create()` がどのように quantity を受け取るか。
- 有効な場合に `OrderItem` を生成すること。
- 無効な場合に `IllegalArgumentException` となること。

ここで Production Code が先ほどの仕様と一致しているかを確認する。

### 3. `OrderItemQuantityTest` を読む

[`OrderItemQuantityTest.java`](../../src/test/java/com/example/orderapi/domain/OrderItemQuantityTest.java) を読む。

最初に class 全体を見る。

```text
OrderItemQuantityTest
├─ ValidQuantity
│  └─ quantity=1 / 2 / 98 / 99
└─ InvalidQuantity
   └─ quantity=0 / 100
```

`@Nested` によって正常系と異常系が構造化されていることを確認する。

### 4. `@ValueSource` と境界値を対応させる

有効ケース:

```java
@ValueSource(ints = {1, 2, 98, 99})
```

無効ケース:

```java
@ValueSource(ints = {0, 100})
```

値を単に暗記せず、なぜその値なのかを境界値表へ戻って確認する。

### 5. Test Oracle を確認する

有効ケースの Oracle は、

```text
生成に成功する
AND
OrderItem.quantity が入力値と一致する
```

となる。

異常ケースは、

```text
IllegalArgumentException
message = quantity must be between 1 and 99
```

となる。

ここでは Production Code の戻り値をコピーして expected にしていないことを確認する。

### 6. AssertJ の Exception Assertion を読む

無効ケースでは、

```java
assertThatThrownBy(...)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("quantity must be between 1 and 99");
```

となっている。

Unit 01 の `assertThrows` と比較する。

Unit 01:

```text
assertThrows
↓
Exception Object を取得
↓
別 Assertion で message を確認
```

Unit 02:

```text
assertThatThrownBy
↓
Exception type
↓
message
```

どちらも同じ仕様を検証できるが、表現方法が異なることを確認する。

### 7. OrderStatus の Decision Table を確認する

次に状態遷移仕様を見る。

| Status      | confirm | ship  | cancel |
| ----------- | ------- | ----- | ------ |
| `CREATED`   | true    | false | true   |
| `CONFIRMED` | false   | true  | true   |
| `SHIPPED`   | false   | false | false  |
| `CANCELLED` | false   | false | false  |

この表自体を Test Oracle として扱う。

### 8. `OrderStatus.java` を確認する

[`OrderStatus.java`](../../src/main/java/com/example/orderapi/domain/OrderStatus.java) で、

```text
canConfirm
canShip
canCancel
```

の責務を見る。

実装を確認した後も、expected の根拠は先ほどの状態遷移仕様であることを忘れない。

### 9. `OrderStatusTransitionTest` を読む

[`OrderStatusTransitionTest.java`](../../src/test/java/com/example/orderapi/domain/OrderStatusTransitionTest.java) の `@CsvSource` を見る。

```java
@CsvSource({
    "CREATED,   true,  false, true",
    "CONFIRMED, false, true,  true",
    "SHIPPED,   false, false, false",
    "CANCELLED, false, false, false"
})
```

Decision Table がほぼそのまま Test Data になっていることを確認する。

### 10. `assertAll` を読む

1 状態について、

```text
canConfirm
canShip
canCancel
```

の 3 条件を確認する。

```java
assertAll(
        "operation availability for " + status,
        () -> assertEquals(canConfirm, status.canConfirm()),
        () -> assertEquals(canShip, status.canShip()),
        () -> assertEquals(canCancel, status.canCancel()));
```

1 Assertion が失敗しても、同じ group 内の他 Assertion も評価されることを理解する。

### 11. Fixture の対象を確認する

次に [`OrderTestFixtureTest.java`](../../src/test/java/com/example/orderapi/domain/OrderTestFixtureTest.java) を読む。

最初に Fixture を洗い出す。

```text
fixedCreatedAt

Order
├─ OrderItem Product A
├─ OrderItem Product B
└─ PriceSummary
```

Test Method 本体だけを見るのではなく、その前にどの状態が準備されるのかを確認する。

### 12. Lifecycle Annotation の順番を見る

次の4つを Test Code 上で確認する。

```text
@BeforeAll
@BeforeEach
@AfterEach
@AfterAll
```

特に、

```text
@BeforeEach
→ 各 Test 用に新しい Order を生成
```

という点が重要となる。

今回の `@AfterEach` / `@AfterAll` の `null` 代入は、外部 Resource cleanup が必要だからではなく、Lifecycle の対応をコード上で明示する教材であることも確認する。

### 13. AssertJ の Object Assertion を読む

`orderObject_canBeVerifiedFluently` を読む。

```java
assertThat(order)
        .isNotNull()
        .satisfies(...);
```

`satisfies` 内では、

```text
status
shippingPostalCode
subtotal
totalAmount
createdAt
```

を確認する。

Fixture に設定した値と Assertion の expected が一致している理由を確認する。

### 14. AssertJ の Collection Assertion を読む

`orderItems_canBeVerifiedAsCollection` では、

```java
.hasSize(2)
.extracting(OrderItem::getProductId)
.containsExactly(1L, 2L);
```

を読む。

確認したいのは、

```text
Collection size
property
order
```

である。

`containsExactly` では要素だけでなく順序も期待している点に注意する。

### 15. 割引仕様の境界値表を確認する

次に割引ルールを整理する。

| subtotal | 分類     | expectedDiscount |
| -------: | -------- | ---------------: |
|    9,999 | 境界直前 |                0 |
|   10,000 | 境界     |            1,000 |
|   10,001 | 境界直後 |            1,000 |

`10,001 × 10% = 1,000.1` だが、仕様で 1 円未満は切り捨てるため expected は `1,000` となる。

この計算を Production Code を読む前に確認する。

### 16. `OrderPriceCalculator.java` を確認する

[`OrderPriceCalculator.java`](../../src/main/java/com/example/orderapi/service/OrderPriceCalculator.java) の、

```text
calculateDiscount
calculate
```

を確認する。

見るポイント:

- 10,000 円で条件が切り替わること。
- 割引率。
- 端数処理。
- 複数明細の subtotal 合算。
- total の計算式。

### 17. 割引境界の `@CsvSource` を読む

[`OrderPriceCalculatorParameterizedTest.java`](../../src/test/java/com/example/orderapi/service/OrderPriceCalculatorParameterizedTest.java) の `DiscountBoundary` を読む。

```java
@CsvSource({
    "9999, 0",
    "10000, 1000",
    "10001, 1000"
})
```

仕様表と Test Data が対応していることを確認する。

### 18. `isEqualByComparingTo` を確認する

割引額の Assertion は、

```java
assertThat(actual)
        .isEqualByComparingTo(BigDecimal.valueOf(expectedDiscount));
```

となっている。

Unit 01 の `assertEquals(BigDecimal, BigDecimal)` との違いを確認する。

今回保証したいのは金額として同額であることなので、scale より数値比較を重視している。

### 19. 複数明細の Test Oracle を手計算する

`amountCases()` の「複数明細・割引あり」を Test Code の expected を見る前に計算する。

```text
3,000 × 2 = 6,000
2,500 × 2 = 5,000

subtotal = 11,000
discount = 1,100
shippingFee = 800
total = 10,700
```

その後で `Arguments.of(...)` の expected と一致しているか確認する。

### 20. `@MethodSource` を読む

`amountCases()` は `List<OrderItem>` や複数の `BigDecimal` を渡す。

`@CsvSource` では表現が読みにくくなる複雑な Fixture を Java method から提供している点を見る。

```java
@MethodSource("amountCases")
```

と、

```java
static Stream<Arguments> amountCases()
```

の対応を確認する。

### 21. Unit 01 と Unit 02 を比較する

Unit 01 の [`OrderPriceCalculatorTest.java`](../../src/test/java/com/example/orderapi/service/OrderPriceCalculatorTest.java) と Unit 02 の [`OrderPriceCalculatorParameterizedTest.java`](../../src/test/java/com/example/orderapi/service/OrderPriceCalculatorParameterizedTest.java) を比較する。

Unit 01:

```text
代表ケース
JUnit 基本
AAA / Given-When-Then
基本 Assertion
```

Unit 02:

```text
仕様からケースを整理
境界値
Parameterized Test
複数ケース
Test Oracle
AssertJ
```

同じ Production Code でも、学習する観点が異なることを確認する。

## 実行・確認

### 全 Test を実行する

リポジトリルートで実行する。

```bash
./mvnw test
```

Unit 02 でも次は起動不要となる。

```text
Docker Compose
PostgreSQL
WireMock
Spring Boot Application
```

Unit 02 で追加した Test invocation は合計 17 件となる。

```text
OrderItemQuantityTest
├─ 有効数量: 4
└─ 無効数量: 2
合計: 6

OrderStatusTransitionTest
4 states
合計: 4

OrderTestFixtureTest
通常 Test: 2
合計: 2

OrderPriceCalculatorParameterizedTest
├─ 割引境界: 3
└─ 金額計算: 2
合計: 5

Unit 02 追加分:
6 + 4 + 2 + 5 = 17
```

Unit 01 の 6 Test と合わせ、この時点の学習用 Test は合計 23 件となる。

```text
Unit 01: 6
Unit 02: 17
Total: 23
```

正常時は、

```text
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

となることを確認する。

### Unit 02 の Test Class だけ実行する

注文数量:

```bash
./mvnw -Dtest=OrderItemQuantityTest test
```

状態遷移:

```bash
./mvnw -Dtest=OrderStatusTransitionTest test
```

Fixture / AssertJ:

```bash
./mvnw -Dtest=OrderTestFixtureTest test
```

金額計算:

```bash
./mvnw -Dtest=OrderPriceCalculatorParameterizedTest test
```

Class 単位で実行すると、その題材の Parameterized Test invocation や表示名を確認しやすい。

### Parameterized Test の結果を見る

Parameterized Test は Test Method が 1 個でも、parameter ごとに複数回実行される。

例えば `OrderItemQuantityTest` では、

```text
quantity=1
quantity=2
quantity=98
quantity=99
```

がそれぞれ別 invocation として実行される。

Console / Test Report 上で、`@ParameterizedTest(name = "...")` の値が Test Case の識別に役立つことを確認する。

### Test Matrix の結果を見る

`OrderStatusTransitionTest` は 4 状態が 4 invocation として実行される。

```text
CREATED
CONFIRMED
SHIPPED
CANCELLED
```

もし 1 状態の期待値を誤って変更した場合、その状態に対応する invocation が失敗する。

Test Matrix を Parameterized Test に変換しても、どの状態が失敗したのかを識別できることを確認する。

### `assertAll` の結果を見る

`assertAll` 内で複数条件が失敗した場合、関連する Failure をまとめて確認できる。

Unit 02 の正式な Test Code は成功状態なので、必須作業として期待値を変更する必要はない。  
任意で挙動を確認する場合のみ、一時的に expected を変更し、確認後は必ず元へ戻す。

### Lifecycle の確認

`OrderTestFixtureTest` の実行では Test Result だけを見ると、`@BeforeEach` / `@AfterEach` の呼び出しそのものは目立たない。

コードリーディングで次の順序を確認する。

```text
Class Fixture 準備
↓
Order Fixture 準備
↓
Test
↓
Order Fixture 後処理
↓
次の Test 用 Order Fixture 準備
```

Lifecycle を理解するためだけに `System.out.println` を Production/Test Code へ追加する必要はない。

### AssertJ の Failure message

AssertJ の Assertion が失敗すると、

```text
期待した条件
実際の値
```

が Assertion 種類に応じて表示される。

JUnit Assertions と AssertJ では Failure message の表現が異なる場合がある。  
この Unit では、どちらも Test Failure として JUnit Platform / Maven から集計されることを確認する。

### Surefire Report

Unit 01 と同様に、詳細な Test Report は通常次へ生成される。

```text
target/surefire-reports/
```

Parameterized Test の実行結果もこの Test Report から確認できる。

## 学習ポイント

### Test Code より先に仕様を整理する

Unit 02 の最も重要なポイントは、Test Code の API を覚えることではない。

先に、

```text
仕様
↓
条件
↓
境界
↓
状態
↓
Test Case
↓
Test Oracle
```

を整理する。

その後で JUnit / AssertJ を使ってコードへ表現する。

### Production Code を Test Oracle にしない

Production Code の実装結果をそのまま expected にすると、その Production Code が誤っていても Test が成功する可能性がある。

Test Oracle は可能な限り仕様から独立して導く。

```text
仕様:
subtotal >= 10,000
→ 10% 割引

Test Oracle:
10,000
→ 1,000
```

という順序で考える。

### 境界値は実装の見た目ではなく仕様から選ぶ

`if` 文の branch を見てから適当に値を選ぶのではなく、

```text
1 <= quantity <= 99
```

という仕様から、

```text
0 / 1 / 2 / 98 / 99 / 100
```

を導く。

これにより、Production Code の実装漏れや条件演算子の誤りも検出しやすくなる。

### 正常系と異常系の両方を見る

Happy Path だけでは不正入力や境界外の振る舞いを保証できない。

一方、異常系だけでも基本機能が成立することを保証できない。

```text
正常系
異常系
境界
```

を組み合わせて Test Case を考える。

### Parameterized Test は Test Case を減らすためではない

Parameterized Test を導入したからといって、

```text
9,999
10,000
10,001
```

の 3 Case が 1 Case になるわけではない。

3 つの意味ある Test Case は維持したまま、共通する Test Logic を 1 method にまとめる。

この違いは重要となる。

### Data Source は読みやすさで選ぶ

Unit 02 では、

```text
単一 int
→ @ValueSource

複数の単純値
→ @CsvSource

List / Object を含む複雑な値
→ @MethodSource
```

と使い分けている。

技術的に使えるかだけではなく、Test Case と expected の関係が最も読みやすい形を選ぶ。

### Decision Table / Test Matrix で抜けを可視化する

状態を持つ処理を Test Method の思いつきだけで作ると、特定状態・操作の組み合わせが抜けやすい。

表にすることで、

```text
どの状態があるか
どの操作があるか
どの組み合わせを確認したか
```

を確認しやすくなる。

### Fixture は Test の前提条件

Fixture は単なる Test Data の再利用テクニックではない。  
Test がどの状態から始まるのかを表現する重要な情報となる。  
Fixture を共有するときは、Test の意図が隠れすぎないようにする必要がある。

### Setup を共有しすぎない

`@BeforeEach` へ何でも移動すると、Test Method だけを読んだときに前提が分かりにくくなる場合がある。

今回の `OrderTestFixtureTest` は Fixture Lifecycle を学ぶため共有しているが、実務では、

```text
複数 Test で本当に共通か
Test Method から前提を追えるか
不要な Data まで準備していないか
```

を考えて利用する。

DRY にすることだけを目的に Test Fixture を共通化しない。

### Teardown は必要なときに使う

Unit 02 の `null` 代入は Lifecycle 教材として明示している。

Plain Java Object だけなら通常 GC に任せられるため、毎回 `null` を代入する必要はない。

後続 Unit で、

```text
DB
Server
Container
External Resource
```

などを扱うとき、Teardown / Cleanup の重要性がより高くなる。

### `assertAll` は関連条件をまとめる

複数の Assertion があるからという理由だけで無条件に `assertAll` を利用するのではない。

```text
同じ PriceSummary
同じ OrderStatus の操作可否
```

のように、1 つの Test Case として一緒に結果を確認したい条件をまとめる。

### AssertJ は JUnit を置き換えない

AssertJ は Assertion Library であり、JUnit Jupiter は Test Framework となる。

```text
JUnit:
Test lifecycle / execution / parameterization

AssertJ:
期待条件の表現
```

という役割を意識する。

### Assertion は仕様に合わせて選ぶ

`BigDecimal` では、

```text
equals として一致させたい
数値として一致させたい
```

のどちらを保証するかで適した Assertion が異なる。

Assertion API を便利だから選ぶのではなく、「この Test では何を等しいとみなすのか」という仕様から選ぶ。

### Test の読みやすさも品質の一部

Test Code は Production Code の変更後も繰り返し読まれる。

そのため、

```text
なぜこの値か
何を期待しているか
どの仕様表に対応するか
```

が読み取りやすいことは重要となる。

`@DisplayName`、Parameterized Test name、`@Nested`、fluent assertion などは、その可読性を高める手段として利用する。

## 補足

### Unit 01 の Test を残す理由

Unit 02 では `OrderPriceCalculator` や `Order` を再度 Test するが、Unit 01 の Test は削除しない。

Unit 01 の Test は、

```text
JUnit の基本構造を学ぶ教材
```

という役割を持つ。

Unit 02 の Test は、

```text
Test Case Design
Parameterized Test
Fixture
AssertJ
```

を学ぶ役割を持つ。

同じ Production Code を対象としていても学習目的が異なるため、両方を残す。

### Test Case の完全網羅が目的ではない

Unit 02 では Unit 01 より多くのケースを扱うが、本番 System に存在し得るすべての入力組み合わせを網羅することが目的ではない。

例えば、すべての 1～99 の quantity を Test する必要はない。

```text
同値分割
境界値分析
Decision Table
```

などを利用して、学習目標に対して代表性のある Case を体系的に選ぶ。

### Coverage との違い

Unit 02 では Branch を意識するが、Coverage 数値そのものはまだ主題ではない。

```text
Coverage が高い
=
良い Test Case Design
```

とは限らない。

Coverage は Unit 10 で扱う。  
Unit 02 では、仕様上重要な Case をどう導くかを優先する。

### Mockito はまだ使用しない

Unit 02 の SUT は外部依存を持たない小さな Java Object となる。

Repository や ShippingFeeClient などの依存先を持つ `OrderService` に対して Test Double を利用する方法は Unit 03 で扱う。

そのため Unit 02 では Mockito を導入しない。

### Spring Test はまだ使用しない

Unit 02 では、

```text
@SpringBootTest
@WebMvcTest
@JdbcTest
```

などは使用しない。

JUnit の Test Case Design と Assertion に集中するため、Spring Context は起動しない。

### `@BeforeEach` と helper method

Fixture の共通化方法は Lifecycle Annotation だけではない。

Unit 01 の `OrderTest` では private helper method を利用していた。

Unit 02 では `@BeforeEach` を利用する例を追加している。

どちらを使うかは、

```text
Fixture を Test ごとに自動準備したいか
Test Method から明示的に生成した方が分かりやすいか
```

などを考えて選ぶ。

### JUnit Assertions と AssertJ の混在

今回の教材では両方を比較するため、1 Project 内で JUnit Assertions と AssertJ を混在させている。

実務では Team 内で Assertion の書き方をある程度統一する場合も多い。

Unit 02 の目的は特定 Style を唯一の正解として決めることではなく、両者の役割と表現の違いを理解し、Test の意図に適した Assertion を読める状態を作ることにある。
