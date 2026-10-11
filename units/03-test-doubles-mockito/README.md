# 03. Test Double と Mockito

## この Unit の目的

この Unit では、依存関係を持つ Service を Unit Test するときに、どこまでを本物として動かし、どこから先を Test Double に置き換えるかという考え方を学ぶ。
Unit 01～02 では、`OrderPriceCalculator`、`Order`、`OrderItem`、`OrderStatus` など、外部依存を持たない小さな Java Object を直接 Test してきた。
Unit 03 では、Repository、外部 API Client、時刻といった依存先を持つ `OrderService` を SUT とする。

## 学習内容

### Unit 03 で意識する Test Boundary

中心となる考え方は、単に Mockito の API を覚えることではない。

```text
今回どこまでを SUT とするか
↓
SUT の外側にある依存先を確認する
↓
その依存先を本物で動かす必要があるか判断する
↓
必要に応じて Test Double に置き換える
↓
SUT の状態と依存先との interaction を検証する
```

という Test Boundary の判断を理解することが目的となる。  
Unit 03 では Mockito を利用して、

```text
ProductRepository
OrderRepository
ShippingFeeClient
Clock
```

を制御可能な依存先へ置き換える。  
一方で、すべてを Mock にするわけではない。
`OrderServiceFakeAndSpyTest` では、`ProductRepository` に対して in-memory の `FakeProductRepository` を用意し、それを Mockito の Spy で包む。
また `Clock` については Mockito Mock ではなく、JDK が提供する `Clock.fixed(...)` という制御可能な実 Object を利用する。

この比較を通して、

```text
Mock にできる
≠
Mock にするべき
```

であることを理解する。

### Test Double

Test Double は、Test 対象が依存する本物の Object の代わりとして利用する Object の総称である。  
映画やドラマで俳優の代わりに Stand-in / Double が使われることになぞらえた用語であり、Test では本物の依存先の代役として利用する。  
例えば `OrderService` は、概念的に次の依存関係を持つ。

```text
OrderService
├─ ProductRepository
│  └─ PostgreSQL
├─ OrderRepository
│  └─ PostgreSQL
├─ ShippingFeeClient
│  └─ External Shipping Fee API
└─ Clock
   └─ 現在時刻
```

このまま本物をすべて利用すると、`OrderService` の Unit Test に、

```text
DB
SQL
HTTP
外部 API
実時刻
```

まで含まれる。  
すると、Test が失敗した場合に原因が `OrderService` なのか、DB なのか、HTTP なのかを切り分けにくくなる。  
Unit 03 では、

```text
SUT:
OrderService

SUT の外側:
ProductRepository
OrderRepository
ShippingFeeClient
Clock
```

と境界を決める。  
SUT の外側を Test Double や制御可能な Object に置き換えることで、`OrderService` 自身の業務フローへ集中する。

### Test Double の分類

代表的な Test Double は次となる。

```text
Dummy
Stub
Fake
Spy
Mock
```

これらは完全に独立した「特定ライブラリのクラス名」ではなく、Test 内でどのような役割を担うかという分類として理解する。  
同じ Object が Test によって複数の役割を持つ場合もある。  
例えば Mockito で作成した Object に対して、

```text
when(...).thenReturn(...)
```

だけを利用するなら、主に Stub として利用している。  
同じ Object に対して、

```text
verify(...)
```

を利用して interaction を確認するなら、Mock としての役割も持つ。

### Dummy

Dummy は、method や constructor の parameter を埋めるために渡すが、その Test では実際には利用しない値・Object を指す。  
例えば概念的に、

```java
target.execute(requiredDependency, unusedDependency);
```

という処理があり、Test Case では `unusedDependency` が絶対に利用されないことが保証されている場合、その parameter を埋めるだけの Object は Dummy として扱える。  
Dummy の役割は、

```text
値を返すこと
interaction を記録すること
実際の処理を再現すること
```

ではない。  
単に Test を成立させるための placeholder となる。  
Unit 03 の実践コードでは Dummy 専用 Object を無理に作っていない。
Dummy を登場させるためだけに不自然な Production Code や Test Code を追加するより、分類として理解することを優先する。

### Stub

Stub は、SUT から依存先が呼ばれたときに、Test で決めた値・例外を返す Test Double である。  
Unit 03 では Mockito の、

```java
when(...).thenReturn(...)
```

や、

```java
when(...).thenThrow(...)
```

によって Stub の役割を作る。  
例えば、

```java
when(productRepository.findById(PRODUCT_ID))
        .thenReturn(Optional.of(product));
```

は、

```text
ProductRepository.findById(1)
↓
Product が存在する
```

という依存先の状況を Test が制御している。  
実際の PostgreSQL に Product を INSERT してから検索する必要はない。

### 戻り値の stubbing

Mockito では、

```java
when(mock.method(...))
        .thenReturn(value);
```

という形で戻り値を指定できる。  
Unit 03 の注文作成 Test では、例えば次を Stub する。

```text
ProductRepository.findById
→ Product

ShippingFeeClient.calculateShippingFee
→ 800

Clock.instant
→ 2026-10-01T00:00:00Z

OrderRepository.insertOrder
→ 100

ProductRepository.decreaseStock
→ 1

OrderRepository.findById
→ 保存後 Order
```

これにより、DB、HTTP、実際の現在時刻に依存せず `OrderService.createOrder()` の処理を Test できる。

### 同じ method の連続 stubbing

同じ method が 1 Test 内で複数回呼ばれ、呼び出しごとに異なる値を返したい場合がある。  
注文確定では、

```text
1 回目の findById
→ CREATED Order

状態更新

2 回目の findById
→ CONFIRMED Order
```

となる。  
Test Code では、

```java
when(orderRepository.findById(ORDER_ID))
        .thenReturn(Optional.of(createdOrder))
        .thenReturn(Optional.of(confirmedOrder));
```

としている。  
この Test では DB の状態変化そのものを再現しているわけではない。

```text
更新前 Repository 応答
更新後 Repository 応答
```

を Stub の連続戻り値によって再現している。  
キャンセル Test でも同じ考えで、

```text
CONFIRMED
↓
CANCELLED
```

の前後を返している。

### 例外の stubbing

依存先が失敗した状況も Test で再現できる。  
Unit 03 では、

```java
when(shippingFeeClient.calculateShippingFee(...))
        .thenThrow(clientFailure);
```

として、Shipping Fee API 相当の依存先失敗を再現する。  
実際に HTTP Server を落としたり、500 response を返す外部 API を準備したりする必要はない。  
この Test で確認しているのは、

```text
ShippingFeeClient が失敗
↓
OrderService が ShippingFeeServiceException へ変換
↓
DB 書き込みへ進まない
```

という Service の振る舞いとなる。  
HTTP レベルの障害再現は Unit 08 で WireMock を利用して扱う。

### Fake

Fake は、本物より簡略化されているが、実際に動作する実装を持つ Test Double である。  
代表例として、

```text
本番:
DB Repository

Fake:
in-memory Repository
```

がある。  
Unit 03 では、

```text
FakeProductRepository
```

を用意している。  
内部では、

```java
Map<Long, Product>
```

を状態として保持し、

```text
findAll
findById
decreaseStock
increaseStock
```

を実際に処理する。  
Mockito Mock のように、

```java
when(productRepository.decreaseStock(...))
        .thenReturn(1);
```

と呼び出しごとの戻り値を指定するのではない。  
Fake 自身が、

```text
初期在庫 10
↓
decreaseStock(1, 2)
↓
内部状態 8
```

という状態変化を持つ。

### Fake は本物の Repository Test ではない

`FakeProductRepository` は `ProductRepository` interface の動きを一部再現するが、本物の JDBC Repository の正しさを保証するものではない。  
Fake には、

```text
SQL
PostgreSQL
Transaction
DB constraint
Row Mapping
同時実行制御
```

などが存在しない。  
したがって、

```text
FakeProductRepository が成功
=
JdbcProductRepository が正しい
```

ではない。  
本物の Repository と PostgreSQL を組み合わせる Test は Unit 06 で扱う。

### Spy

Spy は、実 Object の real behavior を利用しながら、method 呼び出しを観測したり、必要に応じて一部だけ stubbing したりできる Test Double である。  
Unit 03 では、

```java
FakeProductRepository productRepositorySpy =
        spy(new FakeProductRepository(List.of(initialProduct)));
```

としている。  
この Object は、

```text
FakeProductRepository の real method
+
Mockito の interaction 記録
```

を持つ。  
そのため、

```java
verify(productRepositorySpy).findById(PRODUCT_ID);
verify(productRepositorySpy).decreaseStock(PRODUCT_ID, 2);
```

と interaction を確認できる一方で、`decreaseStock()` 自体は Fake の本物の処理が実行される。

### Mock

Mock は、SUT と依存先の interaction を確認するために利用する Test Double である。  
Mockito では、

```java
@Mock
private OrderRepository orderRepository;
```

のように作成する。  
Mock は、Test が何も設定しなければ Production Repository と同じ処理を実行する Object ではない。  
例えば `OrderRepository` Mock は、

```text
SQL を実行しない
PostgreSQL へ接続しない
Order を永続化しない
```

代わりに、

```text
Test で戻り値を設定
method 呼び出しを記録
verify で interaction を確認
```

できる。

### Mockito Mock は Stub と Mock の両方の役割を持ち得る

Mockito の `@Mock` Object を作ったから、その Object の役割が常に「Mock」だけになるとは限らない。  
例えば、

```java
when(orderRepository.findById(ORDER_ID))
        .thenReturn(Optional.of(order));
```

では、`orderRepository` を Stub として利用している。  
一方、

```java
verify(orderRepository)
        .updateStatus(...);
```

では、Mock として interaction を検証している。  
実務上は Mockito Object をまとめて「mock」と呼ぶことも多いが、Test Double の役割を理解するときは、

```text
値を返すために利用しているのか
interaction を検証するために利用しているのか
```

を区別して読む。

### Mockito Mock と Stub の呼び方

Mockito の Object を一般に「Mock」と呼ぶ場合がある。  
ただし Test Double の役割分類では、

```text
when / thenReturn で応答を提供
→ Stub の役割

verify で interaction を確認
→ Mock の役割
```

と整理すると理解しやすい。  
用語の使われ方は文脈によって多少異なるため、API 名ではなく Test 内での役割を見る。

### state verification

state verification は、処理後の結果や状態を確認する Test の考え方である。  
Unit 02 までの Test は主に state verification だった。  
例えば、

```java
assertThat(actual.getStatus())
        .isEqualTo(OrderStatus.CREATED);
```

や、

```java
assertThat(actual.getTotalAmount())
        .isEqualByComparingTo(BigDecimal.valueOf(6_800));
```

のように、

```text
処理した結果
Object の状態
戻り値
```

を見る。  
Unit 03 でも state verification は引き続き利用する。  
注文作成 Test では、

```java
assertThat(actual).isSameAs(persistedOrder);
```

や ArgumentCaptor で取得した Order の状態を確認する。  
Fake / Spy Test では、

```java
assertThat(productRepositorySpy.currentStockQuantity(PRODUCT_ID))
        .isEqualTo(8);
```

として Fake 内部状態の変化を確認する。

### interaction verification

interaction verification は、SUT が依存先をどのように利用したかを確認する。  
例えば、

```java
verify(productRepository)
        .decreaseStock(PRODUCT_ID, 2);
```

は、

```text
OrderService が ProductRepository.decreaseStock(1, 2)
を呼び出した
```

ことを保証する。  
戻り値や最終 Object の状態だけでは、

```text
Repository を正しい引数で呼んだか
外部 API を呼んだか
呼ぶべきでない処理を呼んでいないか
何回呼んだか
```

までは分からない。  
そのため依存境界を持つ Service Test では interaction verification が重要になる。

### state verification と interaction verification の使い分け

両方には異なる役割がある。

```text
state verification
→ 結果として何が成立したか

interaction verification
→ SUT が依存先へどのような指示を出したか
```

Unit 03 の注文作成正常系では両方を利用する。

```text
state:
返却された Order
Captor で取得した保存対象 Order

interaction:
ShippingFeeClient を呼んだ
ProductRepository.decreaseStock を呼んだ
OrderRepository.findById を呼んだ
Clock.instant を呼んだ
```

interaction verification だけに偏ると、内部実装へ Test が強く結びつく可能性がある。  
逆に state verification だけでは、副作用を持つ依存先への指示を確認できない場合がある。  
何を仕様として保証する必要があるかで選ぶ。

### Mockito

Mockito は Java の Test で Test Double を作成・制御・検証するための Library である。  
Unit 03 では主に次を利用する。

```text
@Mock
@InjectMocks
@Captor
MockitoExtension
when
thenReturn
thenThrow
verify
times
never
verifyNoInteractions
Argument Matcher
ArgumentCaptor
spy
```

JUnit Jupiter が Test の実行や Lifecycle を担当し、Mockito は Test Double を担当する。

### `MockitoExtension`

Test Class には、

```java
@ExtendWith(MockitoExtension.class)
```

を付けている。  
これにより JUnit Jupiter と Mockito を連携し、

```text
@Mock
@InjectMocks
@Captor
```

などの Annotation を利用できる。

### mock object の作成

Unit 03 の `OrderServiceMockitoTest` では、

```java
@Mock
private ProductRepository productRepository;

@Mock
private OrderRepository orderRepository;

@Mock
private ShippingFeeClient shippingFeeClient;

@Mock
private Clock clock;
```

としている。  
これらは Test Method ごとに本物の実装を準備する代わりに、Mockito が作成する Test Double となる。

### `@Mock`

`@Mock` は Mockito Mock Object を field として作成するために利用する。  
手動で、

```java
ProductRepository repository =
        Mockito.mock(ProductRepository.class);
```

と書くこともできるが、Unit 03 では Annotation を利用する。  
`@Mock` が付いていること自体は、

```text
この Object のすべての method を verify する
```

ことを意味しない。  
Test Case に必要な stubbing / verification だけを行う。

### `@InjectMocks`

`@InjectMocks` は、Mockito が作成した Mock を利用して SUT を組み立てるために利用する。  
Unit 03 では、

```java
@InjectMocks
private OrderService orderService;
```

としている。  
Production Code の `OrderService` は constructor で依存先を受け取るため、Mockito はその constructor parameter に対応する Mock を注入する。  
重要なのは、

```text
Test のためだけに Production Code へ setter を追加する
Test 専用 constructor を追加する
```

必要がないこととなる。  
もともとの Constructor Injection を Test でも利用する。

### stubbing

stubbing は、Test Double の応答を Test から決めることを指す。  
Mockito では主に、

```text
thenReturn
thenThrow
```

を利用する。  
例えば、

```text
商品が存在する
商品が存在しない
Shipping Fee API が成功する
Shipping Fee API が失敗する
Repository 更新が成功する
```

といった依存先の状態を Test 内で作る。  
stubbing の目的は、SUT の外側を制御可能にすることとなる。

### verification

verification は、Mock に対してどの interaction が行われたかを確認する。  
基本形は、

```java
verify(mock).method(...);
```

となる。  
指定回数を省略した場合は、基本的に 1 回呼ばれたことを期待する。  
Unit 03 では、

```java
verify(orderRepository)
        .insertOrder(orderCaptor.capture());
```

などを利用する。

### 呼び出し回数の確認

同じ method を複数回呼ぶことが仕様上重要な場合は、

```java
verify(mock, times(2))
        .method(...);
```

と確認できる。  
注文確定 / キャンセルでは、

```java
verify(orderRepository, times(2))
        .findById(ORDER_ID);
```

としている。  
これは、

```text
更新前取得
更新後取得
```

の 2 回を確認する。  
キャンセル時の在庫復元では、

```java
verify(productRepository, times(2))
        .increaseStock(anyLong(), anyInt());
```

として 2 明細分の在庫復元を確認する。

### 呼ばれていないことの確認

副作用を持つ処理では、

```text
呼ばれたこと
```

だけでなく、

```text
呼ばれていないこと
```

が仕様となる場合がある。  
商品が存在しない場合、

```java
verify(productRepository, never())
        .decreaseStock(anyLong(), anyInt());
```

として、在庫更新へ進んでいないことを確認する。  
発送済み注文のキャンセルでも、

```java
verify(productRepository, never())
        .increaseStock(anyLong(), anyInt());
```

として、キャンセル不可状態で在庫を復元していないことを確認する。

### `verifyNoInteractions`

依存先に一切触れていないことを確認したい場合は、

```java
verifyNoInteractions(...)
```

を利用できる。  
商品が存在しない Test では、

```java
verifyNoInteractions(
        shippingFeeClient,
        orderRepository,
        clock);
```

としている。  
これは、

```text
Product の存在確認で処理終了
↓
送料計算しない
Order 保存しない
時刻も取得しない
```

という処理境界を表している。

### Argument Matcher

Mockito では method 引数を完全一致だけでなく Matcher で指定できる。  
Unit 03 では、

```text
any(...)
anyLong()
anyInt()
eq(...)
```

を利用する。  
例えば、

```java
verify(productRepository, never())
        .decreaseStock(anyLong(), anyInt());
```

は、

```text
どの productId / quantity であっても
decreaseStock が呼ばれていない
```

ことを確認する。

### `eq(...)`

Matcher を利用する場合、同じ method call の他の parameter でも Matcher を利用する必要がある場面がある。  
Unit 03 の Captor では、

```java
verify(orderRepository)
        .insertOrderItems(
                eq(ORDER_ID),
                orderItemsCaptor.capture());
```

としている。  
`capture()` も Argument Matcher として扱われるため、同じ呼び出し内の `ORDER_ID` を `eq(...)` で揃えている。

### Matcher を使いすぎない

Matcher は便利だが、すべてを、

```java
any()
anyLong()
anyInt()
```

にすると Test が何を保証しているか弱くなる。  
例えば、

```java
verify(productRepository)
        .decreaseStock(PRODUCT_ID, 2);
```

なら、

```text
商品 1 の在庫を 2 減らした
```

ことまで保証できる。  
一方、

```java
verify(productRepository)
        .decreaseStock(anyLong(), anyInt());
```

だけでは具体的な値を保証できない。  
今回の Test では、

```text
具体値が仕様上重要
→ exact value

値自体ではなく呼び出し有無・回数が重要
→ Matcher
```

と使い分けている。

### ArgumentCaptor

ArgumentCaptor は、Mock method に実際に渡された argument を取り出して、後から詳細に検証するために利用する。  
Unit 03 では、

```java
@Captor
private ArgumentCaptor<Order> orderCaptor;
```

と、

```java
@Captor
private ArgumentCaptor<List<OrderItem>> orderItemsCaptor;
```

を利用する。  
注文作成処理では、`OrderService` の内部で新しい `Order` が生成される。  
Test 側がその instance を直接持っていないため、

```java
verify(orderRepository)
        .insertOrder(orderCaptor.capture());
```

によって Repository に渡された Object を取得する。  
その後、

```text
status
createdAt
subtotal
shippingFee
totalAmount
```

などを state verification する。

### ArgumentCaptor を使う理由

単純な値なら、

```java
verify(repository)
        .method(expected);
```

で十分な場合も多い。  
ArgumentCaptor は、

```text
SUT 内部で生成された Object
複数 property を持つ Object
callback
Collection
```

などを詳細に確認したいときに有効となる。  
ただし、すべての argument を Captor で取得する必要はない。  
Test の意図が direct verification で十分なら、そちらを優先する。

### `@Captor`

`@Captor` は `ArgumentCaptor` を field として生成するために利用する。  
例えば、

```java
@Captor
private ArgumentCaptor<Order> orderCaptor;
```

と書くことで、

```java
ArgumentCaptor.forClass(Order.class);
```

のような初期化を毎回書かずに利用できる。

### Spy と Mockito の `spy(...)`

Mockito の、

```java
spy(realObject)
```

は、実 Object を Spy 化する。  
Unit 03 では、

```java
spy(new FakeProductRepository(...))
```

を利用する。  
そのため、

```text
findById
decreaseStock
```

は Fake の real method が動く。  
同時に、

```java
verify(productRepositorySpy)
        .decreaseStock(PRODUCT_ID, 2);
```

も利用できる。

### Spy の注意点

Spy は「本物の処理を実行する」という特徴がある。  
そのため、Spy する Object が、

```text
DB
Network
File System
時間のかかる処理
外部副作用
```

を持つ場合、意図せず実処理が動く可能性がある。  
Unit 03 では副作用を in-memory に閉じ込めた `FakeProductRepository` を Spy しているため、違いを学ぶ題材として扱いやすい。

### 実物を使う場合と Test Double を使う場合

Unit Test だから依存先をすべて Test Double にする必要はない。  
重要なのは、

```text
本物を使っても
速い
決定的
外部副作用がない
Test の境界を不必要に広げない
```

かどうかとなる。  
`OrderServiceFakeAndSpyTest` では、

```java
Clock fixedClock =
        Clock.fixed(CREATED_AT, ZoneOffset.UTC);
```

を利用する。  
`Clock.fixed(...)` は Mockito Mock ではなく、JDK の実 Object となる。  
固定時刻を必ず返すため、

```text
高速
外部 I/O なし
決定的
```

であり、Mock に置き換える必要性が低い。  
一方、`OrderServiceMockitoTest` では Mockito API を学ぶ目的もあるため `Clock` を `@Mock` にして stubbing / verification の対象としている。  
同じ interface でも、Test の目的によって選択が変わる。

### `Clock` を依存注入している意味

Production Code が直接、

```java
Instant.now()
```

だけに依存していると、Test から現在時刻を制御しにくい。  
`Clock` を依存として受け取ることで、

```text
Production:
system clock

Test:
Mock Clock
または
Clock.fixed(...)
```

を選択できる。  
Unit 03 では Test Double を使いやすい Production Code の一例として読む。  
Testability 自体は Unit 04 で詳しく扱う。

### mock を使う範囲の考え方

Mock の候補を判断するときは、

```text
SUT の外側か
外部 I/O を持つか
非決定的か
重い処理か
その依存先自身を今回 Test したいか
```

などを考える。  
Unit 03 では、

```text
OrderService
→ SUT

ProductRepository
OrderRepository
ShippingFeeClient
Clock
→ SUT の依存先
```

と整理する。  
Repository や Client の実装自体の正しさは、Unit 03 の Test 対象ではない。

### mock の使いすぎによる問題

Mock は便利だが、多すぎると Test の品質を下げる場合がある。  
代表的な問題は次となる。

```text
Production Code の内部実装へ強く結びつく
refactoring だけで Test が大量に壊れる
Test Setup が複雑になる
本物との契約差を見逃す
Test が何を保証しているか分かりにくくなる
```

例えば、method call の細かな順序や、仕様上重要でない内部 call まで大量に `verify()` すると、Production Code の内部構造を変更しにくくなる。  
interaction verification は、

```text
外部副作用
Repository 更新
外部 API 呼び出し
呼び出してはいけない処理
```

など、振る舞いとして意味のある interaction を中心にする。

### Test Double と Test Boundary

Test Double の中心は Mockito の文法ではなく Test Boundary となる。  
次の問いを先に考える。

```text
この Test で保証したい振る舞いは何か

SUT はどこまでか

依存先の real behavior は今回必要か

依存先の応答だけを制御したいか

interaction まで保証したいか
```

その判断の後で、

```text
Stub
Fake
Spy
Mock
real object
```

を選ぶ。

## 使用する技術・ツール

### Java 21

Production Code と Test Code の実装言語として利用する。  
Unit 03 でも Test はローカル JVM 上で完結し、Spring Context は起動しない。

### JUnit Jupiter

Test Framework として Unit 01～02 から継続して利用する。  
Unit 03 では主に、

```text
@Test
@DisplayName
@ExtendWith
```

を利用する。

### Mockito

Test Double の作成、stubbing、interaction verification に利用する。  
`pom.xml` では Test Scope として、

```text
mockito-core
mockito-junit-jupiter
```

を追加している。

### `mockito-core`

Mockito の中心 API を提供する。  
Unit 03 では主に、

```text
when
thenReturn
thenThrow
verify
times
never
verifyNoInteractions
ArgumentMatcher
ArgumentCaptor
spy
```

を利用する。

### `mockito-junit-jupiter`

JUnit Jupiter と Mockito を連携するために利用する。  
主に、

```text
MockitoExtension
@Mock
@InjectMocks
@Captor
```

を利用する。

### AssertJ

Unit 02 から継続して state verification / Exception verification に利用する。  
Mockito が Test Double / interaction を担当し、AssertJ が結果状態の Assertion を担当する。

### Maven / Maven Wrapper

Test 実行の入口として利用する。

```bash
./mvnw test
```

Unit 03 の Test でも PostgreSQL、Docker、外部 HTTP Server は不要となる。

## テスト対象とテスト境界

### `OrderServiceMockitoTest`

SUT は `OrderService` となる。

```text
SUT:
OrderService
```

本物として利用する主な Object:

```text
OrderService
Order
OrderItem
Product
PriceSummary
CreateOrderCommand
CreateOrderItemCommand
```

Mockito Test Double:

```text
ProductRepository
OrderRepository
ShippingFeeClient
Clock
```

起動しないもの:

```text
Spring Context
JdbcProductRepository
JdbcOrderRepository
PostgreSQL
HttpShippingFeeClient
External Shipping Fee API
HTTP Server
Docker
```

概念的な境界は次となる。

```text
                  ┌─ Mock ProductRepository
                  │
Test ──> OrderService ── Mock OrderRepository
                  │
                  ├─ Mock ShippingFeeClient
                  │
                  └─ Mock Clock
```

Test 対象は `OrderService` の業務フローとなる。

### `OrderServiceFakeAndSpyTest`

SUT は同じく `OrderService` となる。  
依存先の構成を変え、Test Double / real object の比較を行う。

```text
ProductRepository
→ FakeProductRepository
→ Mockito Spy

OrderRepository
→ Mockito Mock

ShippingFeeClient
→ Mockito Mock

Clock
→ Clock.fixed(...) の real object
```

概念的には次となる。

```text
                         ┌─ Spy(FakeProductRepository)
                         │
Test ──> OrderService ─── Mock OrderRepository
                         │
                         ├─ Mock ShippingFeeClient
                         │
                         └─ real Clock.fixed(...)
```

### `FakeProductRepository`

`FakeProductRepository` 自体は Unit 03 用の Test Support となる。  
Production Code の `ProductRepository` interface を実装し、

```text
Map<Long, Product>
```

を in-memory state として利用する。  
再現する範囲:

```text
findAll
findById
decreaseStock
increaseStock
```

再現しない範囲:

```text
SQL
JDBC
PostgreSQL
Transaction
DB constraint
Lock
同時実行
```

Fake は Repository の full replacement ではなく、Unit 03 の Test に必要な最小の real behavior を提供する。

### Repository Mock は Repository の正しさを Test していない

Unit 03 で、

```java
when(orderRepository.insertOrder(...))
        .thenReturn(ORDER_ID);
```

としても、`JdbcOrderRepository.insertOrder()` の SQL が正しいことは確認できない。  
Mock は `OrderService` から見た Repository contract を Test 内で再現しているだけとなる。  
Repository 自身は Unit 06 で実 PostgreSQL と組み合わせて Test する。

### ShippingFeeClient Mock は HTTP Contract を Test していない

同様に、

```java
when(shippingFeeClient.calculateShippingFee(...))
        .thenReturn(BigDecimal.valueOf(800));
```

は `HttpShippingFeeClient` の HTTP request / response handling を Test していない。  
Unit 03 では `OrderService` の依存先として扱う。  
HTTP Client 自体の Test は Unit 08 で扱う。

## 使用するファイル

### この Unit で追加・変更する主なファイル

- [`pom.xml`](../../pom.xml)
- [`OrderServiceMockitoTest.java`](../../src/test/java/com/example/orderapi/service/OrderServiceMockitoTest.java)
- [`OrderServiceFakeAndSpyTest.java`](../../src/test/java/com/example/orderapi/service/OrderServiceFakeAndSpyTest.java)
- [`FakeProductRepository.java`](../../src/test/java/com/example/orderapi/support/FakeProductRepository.java)

### コードリーディングで参照する主な Production Code

- [`OrderService.java`](../../src/main/java/com/example/orderapi/service/OrderService.java)
- [`ProductRepository.java`](../../src/main/java/com/example/orderapi/repository/ProductRepository.java)
- [`OrderRepository.java`](../../src/main/java/com/example/orderapi/repository/OrderRepository.java)
- [`ShippingFeeClient.java`](../../src/main/java/com/example/orderapi/client/ShippingFeeClient.java)
- [`Product.java`](../../src/main/java/com/example/orderapi/domain/Product.java)
- [`Order.java`](../../src/main/java/com/example/orderapi/domain/Order.java)
- [`OrderItem.java`](../../src/main/java/com/example/orderapi/domain/OrderItem.java)
- [`OrderStatus.java`](../../src/main/java/com/example/orderapi/domain/OrderStatus.java)
- [`PriceSummary.java`](../../src/main/java/com/example/orderapi/domain/PriceSummary.java)
- [`CreateOrderCommand.java`](../../src/main/java/com/example/orderapi/service/model/CreateOrderCommand.java)
- [`CreateOrderItemCommand.java`](../../src/main/java/com/example/orderapi/service/model/CreateOrderItemCommand.java)

### 前 Unit から引き続き参照する主な Test

- [`OrderPriceCalculatorTest.java`](../../src/test/java/com/example/orderapi/service/OrderPriceCalculatorTest.java)
- [`OrderTest.java`](../../src/test/java/com/example/orderapi/domain/OrderTest.java)
- [`OrderItemQuantityTest.java`](../../src/test/java/com/example/orderapi/domain/OrderItemQuantityTest.java)
- [`OrderStatusTransitionTest.java`](../../src/test/java/com/example/orderapi/domain/OrderStatusTransitionTest.java)
- [`OrderTestFixtureTest.java`](../../src/test/java/com/example/orderapi/domain/OrderTestFixtureTest.java)
- [`OrderPriceCalculatorParameterizedTest.java`](../../src/test/java/com/example/orderapi/service/OrderPriceCalculatorParameterizedTest.java)

Unit 01～02 の Test は削除せず、Unit 03 以降も Test Suite の一部として残す。

## 前提

Unit 01「自動テストの全体像と JUnit 基本」と Unit 02「テストケース設計と JUnit 実践」を完了していることを前提とする。  
次の内容は理解済みとして扱う。

```text
SUT
Test Case
Unit Test
JUnit Jupiter
@Test
@DisplayName
Assertion
assertThrows
AssertJ
fluent assertion
state verification の基本
Test Fixture
正常系 / 異常系
境界値
状態遷移
Maven からの Test 実行
```

Unit 03 で新しく中心となるのは、

```text
依存関係を持つ SUT
Test Boundary
Test Double
Dummy / Stub / Fake / Spy / Mock
stubbing
interaction verification
Argument Matcher
ArgumentCaptor
```

となる。

## コードリーディング

Unit 03 では、Mockito の API から読むのではなく、最初に Production Code の依存関係を確認する。  
基本順序は次となる。

```text
OrderService の依存関係を確認
↓
今回の SUT / Test Boundary を決める
↓
各依存先をなぜ Test Double にするか確認
↓
OrderServiceMockitoTest を読む
↓
stubbing を読む
↓
SUT 呼び出しを読む
↓
state verification を読む
↓
interaction verification を読む
↓
Fake / Spy Test と比較する
```

### 1. `OrderService` の依存関係を見る

[`OrderService.java`](../../src/main/java/com/example/orderapi/service/OrderService.java) を開く。  
最初に constructor dependency を確認する。  
見る対象は、

```text
ProductRepository
OrderRepository
ShippingFeeClient
Clock
```

となる。  
ここで「Mockito で Mock にする方法」をまだ考えない。  
先に、

```text
OrderService の責務は何か
各依存先は何を担当しているか
```

を整理する。

### 2. Test Boundary を決める

Unit 03 では、

```text
OrderService
→ SUT

Repository / Client / Clock
→ SUT 外
```

とする。  
したがって、

```text
JdbcProductRepository
JdbcOrderRepository
HttpShippingFeeClient
PostgreSQL
HTTP
```

は動かさない。  
この判断が Mockito を利用する理由となる。

### 3. `@Mock` を確認する

[`OrderServiceMockitoTest.java`](../../src/test/java/com/example/orderapi/service/OrderServiceMockitoTest.java) を開く。  
最初に、

```java
@Mock
private ProductRepository productRepository;

@Mock
private OrderRepository orderRepository;

@Mock
private ShippingFeeClient shippingFeeClient;

@Mock
private Clock clock;
```

を見る。  
それぞれが SUT のどの依存境界に対応しているか確認する。

### 4. `@InjectMocks` を確認する

次に、

```java
@InjectMocks
private OrderService orderService;
```

を見る。  
Production Code の Constructor Injection と対応させる。  
Test 専用 setter / constructor を作っていないことを確認する。

### 5. `@Captor` を確認する

次に、

```java
@Captor
private ArgumentCaptor<Order> orderCaptor;

@Captor
private ArgumentCaptor<List<OrderItem>> orderItemsCaptor;
```

を見る。  
この時点では、

```text
SUT 内部で生成された保存対象を観測するため
```

と理解しておく。

### 6. 注文作成正常系の Fixture を見る

`createOrder_stubsDependenciesAndCapturesPersistenceArguments` を読む。  
最初に、

```text
Product
CreateOrderCommand
persistedOrder
```

を確認する。  
Fixture が何を表すか整理する。

```text
Product:
注文可能商品

Command:
Product 1 を 2 個注文

persistedOrder:
Repository 保存後に再取得される Order
```

### 7. 注文作成正常系の stubbing を読む

次を 1 行ずつ「どの外部境界を制御しているか」で読む。

```text
productRepository.findById
→ 商品が存在

shippingFeeClient.calculateShippingFee
→ 送料 800

clock.instant
→ 固定時刻

orderRepository.insertOrder
→ Order ID 100

productRepository.decreaseStock
→ 更新件数 1

orderRepository.findById
→ 保存後 Order
```

単に `when / thenReturn` の文法として読まない。

### 8. SUT 呼び出しを見る

```java
Order actual =
        orderService.createOrder(command);
```

ここだけが Act に相当する。  
stubbing は SUT ではなく依存境界の準備であることを区別する。

### 9. state verification を読む

最初に、

```java
assertThat(actual)
        .isSameAs(persistedOrder);
```

を見る。  
その後 ArgumentCaptor で保存対象の状態を見る。

```text
status
createdAt
subtotal
shippingFee
totalAmount
```

ここは Mockito の interaction verification ではなく、Captor で取得した Object に対する state verification となる。

### 10. `ArgumentCaptor<Order>` を読む

```java
verify(orderRepository)
        .insertOrder(orderCaptor.capture());
```

を見る。  
`OrderService` が内部生成した `Order` を Repository に渡す瞬間に捕捉している。  
次の、

```java
Order capturedOrder =
        orderCaptor.getValue();
```

で Test 側へ取り出す。

### 11. `ArgumentCaptor<List<OrderItem>>` を読む

```java
verify(orderRepository)
        .insertOrderItems(
                eq(ORDER_ID),
                orderItemsCaptor.capture());
```

を見る。  
`capture()` が Matcher として扱われるため、同じ invocation の `ORDER_ID` が `eq(...)` になっている点を確認する。  
その後、

```text
productId
quantity
lineAmount
```

を確認する。

### 12. interaction verification を読む

正常系の末尾を見る。

```text
ShippingFeeClient
ProductRepository
OrderRepository
Clock
```

に対する `verify()` を確認する。  
ここで、

```text
戻り値が正しい
```

だけではなく、

```text
外部境界へ正しい依頼を行った
```

ことを保証している。

### 13. Product 不存在 Test を読む

`createOrder_whenProductDoesNotExist_stopsBeforeExternalAndPersistenceBoundaries` を読む。  
Stub は、

```text
ProductRepository.findById
→ Optional.empty()
```

だけとなる。  
その後、

```text
ProductNotFoundException
```

を確認する。  
重要なのは末尾の、

```java
verifyNoInteractions(
        shippingFeeClient,
        orderRepository,
        clock);
```

となる。  
「Product が存在しなければ後続処理へ進まない」という Service の制御フローを interaction で保証している。

### 14. `never()` と `verifyNoInteractions()` を比較する

同じ Test 内で、

```java
verify(productRepository, never())
        .decreaseStock(...);
```

と、

```java
verifyNoInteractions(...)
```

がある。  
違いを確認する。

```text
never():
特定 method が呼ばれていない

verifyNoInteractions():
対象 Mock に interaction 自体がない
```

`productRepository` は `findById()` が呼ばれているため、`verifyNoInteractions(productRepository)` にはできない。  
そのため、在庫減算だけを `never()` で確認している。

### 15. 例外 stubbing Test を読む

`createOrder_whenShippingFeeClientFails_wrapsExceptionAndDoesNotWriteDatabase` を読む。  
次を見る。

```java
.thenThrow(clientFailure);
```

依存先障害を Test 内で再現している。

### 16. Exception の cause verification を読む

次の Assertion を確認する。

```java
assertThatThrownBy(...)
        .isInstanceOf(ShippingFeeServiceException.class)
        .hasMessage("failed to calculate shipping fee")
        .cause()
        .isSameAs(clientFailure);
```

ここでは、

```text
Service が別 Exception へ変換した
AND
元の依存先 Exception instance を cause として保持した
```

ことを確認している。  
`isSameAs` は同じ instance であることを確認している。

### 17. DB 未更新を確認する

Shipping Fee Client が失敗した場合に、

```text
insertOrder
insertOrderItems
decreaseStock
```

が呼ばれていないことを見る。  
依存先失敗後に副作用へ進まないという仕様を確認している。

### 18. 注文確定の連続 stubbing を読む

`confirm_whenCreated_updatesStatusOnceAndReturnsUpdatedOrder` を読む。

```java
when(orderRepository.findById(ORDER_ID))
        .thenReturn(Optional.of(createdOrder))
        .thenReturn(Optional.of(confirmedOrder));
```

を、

```text
1 回目:
更新前

2 回目:
更新後
```

として読む。  
その後、

```java
verify(orderRepository, times(2))
        .findById(ORDER_ID);
```

と対応していることを確認する。

### 19. `times(2)` を確認する

注文確定では、

```text
更新前取得
更新後取得
```

の 2 回。  
キャンセルでは、

```text
更新前取得
更新後取得
```

に加え、

```text
2 明細分の increaseStock
```

もある。  
同じ `times(2)` でも何を意味する 2 回かは Test Case ごとに異なる。

### 20. キャンセル Test の Fixture を読む

`cancel_whenConfirmed_restoresEveryItemAndUsesStubbedClock` を読む。  
private helper の、

```text
createConfirmedOrderWithTwoItems
createCancelledOrderWithTwoItems
createTwoPersistedItems
```

を確認する。  
2 明細は、

```text
Product 1:
quantity 2

Product 2:
quantity 1
```

となる。  
この Fixture が、

```java
verify(productRepository)
        .increaseStock(1L, 2);

verify(productRepository)
        .increaseStock(2L, 1);
```

と対応する。

### 21. Clock の stubbing を読む

Mockito Test では、

```java
when(clock.instant())
        .thenReturn(CANCELLED_AT);
```

とする。  
実際の現在時刻を使うと Test 実行ごとに値が変わる。  
固定時刻にすることで expected を決定的にする。

### 22. SHIPPED のキャンセル不可 Test を読む

`cancel_whenShipped_rejectsBeforeUpdateAndStockRestore` を読む。  
ここでは state verification だけでなく、

```text
updateStatus しない
increaseStock しない
ShippingFeeClient を呼ばない
Clock を呼ばない
```

ことを見る。  
不正な状態遷移で副作用を起こさないことを保証している。

### 23. private helper method を読む

Test Method を読んだ後で private helper を読む。  
`createPersistedOrder` では、

```java
Order.restore(...)
```

で必要な状態を直接作っている。  
Test の前提状態を作るために Production Code の状態遷移処理を呼ぶと、今回確認したい処理以外まで Test Fixture 準備へ含まれる。  
`restore()` を利用することで、

```text
Fixture 準備
SUT の Act
```

を分離している。

### 24. `FakeProductRepository` を読む

次に [`FakeProductRepository.java`](../../src/test/java/com/example/orderapi/support/FakeProductRepository.java) を読む。  
Mockito Mock との違いを意識する。  
Mock:

```text
Test が戻り値を stubbing
```

Fake:

```text
Map に real state を保持
method が real behavior を実行
```

### 25. Fake の constructor を読む

初期 `Product` 一覧を Map へ格納する。  
この初期状態が後の、

```text
findById
decreaseStock
increaseStock
```

の結果へ影響する。

### 26. Fake の `findById` を読む

```java
Optional.ofNullable(products.get(id))
```

によって、現在の Map 状態を検索する。  
Test ごとに `thenReturn(...)` を指定しているわけではない点を見る。

### 27. Fake の `decreaseStock` を読む

次の流れを確認する。

```text
Product 取得
↓
存在 / 在庫確認
↓
在庫を減らした Product を Map に再格納
↓
更新件数 1
```

Fake の内部 state が本当に変化する。

### 28. `currentStockQuantity` を読む

これは Production Repository interface には存在しない Test Support 用 method となる。  
Fake の内部状態が、

```text
10
↓
8
```

に変化したことを state verification するために利用する。

### 29. Fake / Spy Test を読む

[`OrderServiceFakeAndSpyTest.java`](../../src/test/java/com/example/orderapi/service/OrderServiceFakeAndSpyTest.java) を開く。  
最初に、

```java
FakeProductRepository productRepositorySpy =
        spy(new FakeProductRepository(...));
```

を見る。  
Mock を生成しているのではなく、すでに real behavior を持つ Fake を Spy 化している。

### 30. `Clock.fixed(...)` を確認する

この Test では、

```java
Clock fixedClock =
        Clock.fixed(CREATED_AT, ZoneOffset.UTC);
```

を利用する。  
Mockito Mock ではない。  
次を満たすため real object を選択できる。

```text
外部 I/O なし
高速
固定値
決定的
```

「依存先だから必ず Mock」という考え方ではないことを確認する。

### 31. Fake の state verification を読む

注文作成後、

```java
assertThat(productRepositorySpy.currentStockQuantity(PRODUCT_ID))
        .isEqualTo(8);
```

を見る。  
これは interaction ではなく、Fake の real behavior によって変化した状態の検証となる。

### 32. Spy の interaction verification を読む

最後に、

```java
verify(productRepositorySpy)
        .findById(PRODUCT_ID);

verify(productRepositorySpy)
        .decreaseStock(PRODUCT_ID, 2);
```

を見る。  
1 Object に対して、

```text
real behavior
state verification
interaction verification
```

を組み合わせている。

### 33. Mockito Test と Fake / Spy Test を比較する

最後に両 Test を並べる。  
Mockito Test:

```text
ProductRepository
→ Mock

在庫減算:
thenReturn(1)

確認:
verify(...)
```

Fake / Spy Test:

```text
ProductRepository
→ Fake + Spy

在庫減算:
Fake の real method

確認:
内部在庫 8
+
verify(...)
```

どちらか一方が常に正解というわけではない。  
Test の目的と境界に合わせて選ぶ。

## 実行・確認

### 全 Test を実行する

リポジトリルートで実行する。

```bash
./mvnw test
```

Unit 03 の Test は Plain Unit Test なので、次は起動不要となる。

```text
Spring Boot Application
Spring Context
PostgreSQL
Docker Compose
Shipping Fee Stub Server
WireMock
```

### Unit 03 の Test Class だけ実行する

Mockito 中心の Test:

```bash
./mvnw -Dtest=OrderServiceMockitoTest test
```

Fake / Spy Test:

```bash
./mvnw -Dtest=OrderServiceFakeAndSpyTest test
```

Unit 03 で追加した Test Method は次となる。

```text
OrderServiceMockitoTest
├─ createOrder_stubsDependenciesAndCapturesPersistenceArguments
├─ createOrder_whenProductDoesNotExist_stopsBeforeExternalAndPersistenceBoundaries
├─ createOrder_whenShippingFeeClientFails_wrapsExceptionAndDoesNotWriteDatabase
├─ confirm_whenCreated_updatesStatusOnceAndReturnsUpdatedOrder
├─ cancel_whenConfirmed_restoresEveryItemAndUsesStubbedClock
└─ cancel_whenShipped_rejectsBeforeUpdateAndStockRestore

OrderServiceFakeAndSpyTest
└─ createOrder_spyOnFakeRepository_preservesRealBehaviorAndRecordsInteraction
```

Unit 03 追加分は 7 Test Method となる。

### 正常時に確認すること

正常時は、

```text
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

となることを確認する。  
Unit 03 では Test 成功だけでなく、Test Code を読みながら次を確認する。

```text
どの dependency を Stub したか
どの interaction を verify したか
どこで never() を利用したか
どこで verifyNoInteractions() を利用したか
Captor で何を取得したか
Fake の state がどう変化したか
Spy がどの real method を実行したか
```

### stubbing を一時的に変更する場合

正式な学習工程として Test Code の変更は必須ではない。  
任意で Mockito の挙動を確認する場合のみ、例えば、

```text
ProductRepository.findById
→ Optional.empty()
```

などへ一時変更すると、SUT の分岐が変化することを確認できる。  
確認後は必ず元へ戻す。

### verification Failure を確認する場合

任意で、

```text
times(2)
→ times(3)
```

などへ一時変更すると、Mockito の verification Failure を確認できる。  
この場合、Assertion Failure とは異なり、

```text
Wanted:
何回呼ばれるべきだったか

Actual:
実際に何回呼ばれたか
```

という interaction に関する Failure が表示される。  
Unit 03 では Stack Trace 全文を暗記する必要はなく、

```text
どの verify が失敗したか
期待回数
実際回数
実際の invocation
```

を見る。

### `never()` Failure を確認する場合

呼ばれる処理に対して誤って `never()` を指定すると、

```text
Wanted but not invoked
```

または「呼ばれてはいけないのに呼ばれた」ことを示す Mockito Failure になる。  
これにより、interaction verification が state verification とは異なる Failure を持つことを確認できる。

### ArgumentCaptor の確認

注文作成 Test では、debugger を利用する場合、

```text
capturedOrder
orderItemsCaptor.getValue()
```

を見ることができる。  
特に、

```text
status
subtotal
shippingFee
totalAmount
productId
quantity
lineAmount
```

が Fixture / stubbing と対応していることを確認する。

### Fake の状態変化を確認する

Fake / Spy Test では、

```text
before:
stock = 10

after:
stock = 8
```

となる。  
Mockito Mock の `decreaseStock()` は内部状態を持たないが、Fake は state を実際に変更するという差を確認する。

### Surefire Report

これまでの Unit と同様に、Maven Surefire の report は通常、

```text
target/surefire-reports/
```

へ生成される。  
Unit 03 の Mockito Test も通常の JUnit Jupiter Test として report に含まれる。

## 学習ポイント

### Mockito を使う前に Test Boundary を決める

Unit 03 の最重要ポイントとなる。

```text
Mock の作り方
when の書き方
verify の書き方
```

から始めない。  
最初に、

```text
何が SUT か
何を今回保証したいか
どの依存先は Test 対象外か
```

を決める。  
その結果として Test Double が必要になる。

### Test Double は本物を完全再現するものではない

Mock / Stub / Fake は、本物の依存先のすべてを再現するわけではない。  
例えば Fake Repository では、

```text
SQL
DB constraint
Transaction
PostgreSQL 固有動作
```

を確認できない。  
Test Double の Test が成功しても、Integration Test が不要になるわけではない。

### Stub は状況を作る

Stub は、

```text
商品が存在する
商品が存在しない
送料取得が成功する
送料取得が失敗する
```

など、SUT が遭遇する依存先状況を作る。  
Test Case の Arrange の一部として読む。

### Mock は interaction を保証する

Mock の verification は、

```text
何を呼んだか
何回呼んだか
何を呼ばなかったか
どの argument だったか
```

を保証する。  
Service の外部副作用や依存境界との契約を Test する場合に有効となる。

### state verification と interaction verification を混同しない

```text
actual の状態
→ state verification

verify(...)
→ interaction verification
```

として区別する。  
ArgumentCaptor は interaction から argument を取得するが、その後 captured Object の property を AssertJ で確認する部分は state verification と考えられる。

### `never()` は重要な仕様を表現できる

異常系で、

```text
DB を更新しない
在庫を変更しない
外部 API を呼ばない
```

ことは重要な仕様となる。  
このような「副作用がないこと」は戻り値だけでは確認できない場合がある。  
Mockito の `never()` / `verifyNoInteractions()` が有効となる。

### `verifyNoInteractions()` は対象選択に注意する

一度でも必要な interaction がある Mock に対して `verifyNoInteractions()` は利用できない。  
例えば Product 不存在 Test では、

```text
productRepository.findById
```

は必要である。  
そのため、

```text
productRepository:
decreaseStock は never()

その他:
verifyNoInteractions()
```

と分ける。

### Matcher は検証を弱くし得る

```text
any()
```

は柔軟だが、具体的な argument が仕様上重要な場合には exact value を利用する。  
Matcher の便利さと Test の保証範囲を両方意識する。

### ArgumentCaptor は SUT 内部生成 Object の観測に有効

Repository に渡す Object が SUT 内部で生成される場合、Captor によってその内容を確認できる。  
ただし、Captor を使うこと自体が目的ではない。

```text
本当に argument 内容を Test する必要があるか
direct verify では不足するか
```

を考える。

### Fake は state を持つ

Fake は Stub と異なり、内部 state を持って real behavior を提供できる。  
今回の、

```text
stock 10
→ decreaseStock(2)
→ stock 8
```

がその例となる。  
Fake は複数 Test で再利用しやすい場合がある一方、本番実装との挙動差が広がらないよう注意が必要となる。

### Spy は real behavior を実行する

Spy は便利だが、real method が実際に動く。  
Spy 対象が外部 I/O を持つ場合は、思わぬ副作用や遅い Test につながる。  
Unit 03 では in-memory Fake を Spy しているため、学習上安全な範囲で real behavior と interaction を比較できる。

### real object を使えるなら Test Double にしなくてもよい

`Clock.fixed(...)` のように、

```text
高速
決定的
外部副作用なし
```

の real object が利用できる場合、Mock は必須ではない。  
Test Double を使うかは「依存しているか」だけで決めない。

### Mock の使いすぎに注意する

大量の Mock と `verify()` が必要な Test は、

```text
SUT が多すぎる責務を持つ
Test Boundary が広すぎる
内部実装へ Test が密結合している
```

可能性もある。  
Unit 04 では Testability と Test Quality をより詳しく扱う。

### interaction をすべて verify しない

Mock が持つすべての method call を検証する必要はない。  
Test Case の仕様として重要な interaction だけを確認する。  
内部実装を細かく固定しすぎない。

### Mockito Test と Integration Test は役割が異なる

Mockito Test:

```text
OrderService の業務フロー
依存先応答を制御
速い
外部 I/O なし
```

Integration Test:

```text
Service + Repository + DB
実 SQL
Transaction
実データ状態
```

Unit Test があるから Integration Test が不要になるわけではない。  
後続 Unit で同じアプリケーションに対して Test Boundary を広げていく。

## 補足

### Dummy を実コードへ無理に登場させていない理由

カリキュラムでは Test Double の分類として Dummy を扱う。  
一方、Unit 03 の実際の `OrderService` Test では、Dummy 専用 Object を作る自然な場面がない。  
そのため、

```text
用語・分類
→ README で理解

自然な実践例
→ Stub / Mock / Fake / Spy をコードで確認
```

としている。  
学習項目を消化するためだけに不自然な Test Code を作らない。

### Fake と in-memory DB は同じ意味ではない

今回の `FakeProductRepository` は Map を利用した Test Support であり、DB Engine ではない。  
H2 などの in-memory DB と、

```text
Map ベース Fake Repository
```

は別物となる。  
DB Test の考え方は Unit 06 で扱う。

### Spy と Partial Mock

Spy は real method を利用しながら一部だけ stubbing する用途でも使われるため、Partial Mock と関連して説明されることがある。  
Unit 03 では、一部 method を上書きする例より、

```text
real behavior を維持
+
interaction を verify
```

という基本的な特徴を優先して確認する。

### Unit 04 へのつながり

Unit 03 では Constructor Injection によって dependency を差し替えられることを利用した。  
次の Unit 04 では、

```text
なぜ Test しやすいのか
Testable Design とは何か
Mock が多すぎる Test は何を示すか
Test Isolation
Deterministic
Repeatable
Flaky / Brittle Test
```

などへ学習を広げる。  
Unit 03 で「Test Double を使う方法」を理解し、Unit 04 で「Test Double を使いやすい設計と、Test Code 自体の品質」を考える流れとなる。
