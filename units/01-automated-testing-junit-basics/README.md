# 01. 自動テストの全体像と JUnit 基本

## この Unit の目的

この Unit では、自動テストを学び始めるうえで最初に必要となる全体像と基本用語を整理し、Spring や DB に依存しない Java クラスに対して、JUnit を使った基本的な Test Code を読める状態を目指す。  
JUnit の Annotation や Assertion の書き方だけを覚えるのではなく、「何をテスト対象としているのか」「どの振る舞いを保証したいのか」「今回の Test がどの範囲を確認しているのか」を意識して Test Code を読む。

この Unit では、メインアプリの `OrderPriceCalculator` と `Order` を SUT として扱う。  
どちらも Spring Context、PostgreSQL、HTTP Server、外部 API を必要としないため、小さな範囲を高速に確認する Unit Test の基本教材として利用できる。

Unit 01 では JUnit の基本に集中する。  
境界値分析、同値分割、Decision Table、Test Matrix、Parameterized Test、AssertJ は Unit 02、Test Double と Mockito は Unit 03、Spring Web 層のテストは Unit 05 以降で段階的に扱う。

## 学習内容

### Manual Test と Automated Test

Manual Test は、人がアプリケーションを操作したり、コマンドを実行したりして期待どおりの結果になることを確認する方法を指す。  
REST API の `curl` による確認や、画面・ログ・DB 状態を人が確認する作業は Manual Test の例となる。

Automated Test は、入力、処理の実行、期待結果との比較を Test Code として記述し、同じ確認を繰り返し自動実行できるようにする方法を指す。  
Unit 01 では、Java の Test Code から Production Code を直接呼び出し、JUnit の Assertion で期待結果を検証する。

Manual Test と Automated Test は、どちらか一方だけを使うものではない。  
自動化しやすく繰り返し確認したい範囲は Automated Test へ寄せ、人間による判断が必要な確認などは Manual Test で補うという使い分けがある。

### 自動テストを行う目的

自動テストの重要な目的の一つは、Production Code の振る舞いが期待どおりであることを繰り返し確認できる状態を作ることである。

Production Code を変更したとき、過去に成立していた振る舞いが意図せず壊れることがある。  
Test Code が存在すると、同じ確認を何度でも実行でき、変更によって既存の振る舞いが壊れていないかを早く発見しやすくなる。

自動テストには、主に次のような価値がある。

- 同じ確認を繰り返し実行できる。
- 人による確認手順のばらつきを減らせる。
- Production Code の変更による既存機能への影響を検出しやすくなる。
- 期待する振る舞いを Test Code として残せる。
- 小さな単位から問題を発見しやすくなる。
- CI から継続的に実行できる。

ただし、自動テストを書けば必ず品質が保証されるわけではない。  
誤った期待値を設定した Test、重要なケースが不足している Test、実装詳細へ過剰に依存した Test などは、期待する保証を提供できない場合がある。  
Test Code 自体の品質については後続 Unit で段階的に扱う。

### テストコードの役割

Test Code は、単に Production Code のメソッドを呼び出して値を比較するためだけのコードではない。  
「どの条件で」「どの処理を実行したとき」「どの振る舞いが成立するべきか」をコードとして表現する役割を持つ。

例えば、`OrderPriceCalculatorTest` の次の Test Method は、割引対象外の注文でどの金額が保証されるべきかを表している。

```java
@Test
void calculate_returnsPriceSummaryWithoutDiscount() {
    // Arrange
    // Act
    // Assert
}
```

重要なのは Test Method の行数ではなく、この Test がどの振る舞いを保証しようとしているかを読み取ることである。

### SUT

SUT は System Under Test の略で、その Test が主に確認する対象を表す。

Unit 01 の `OrderPriceCalculatorTest` では、主な SUT は `OrderPriceCalculator` となる。

```java
OrderPriceCalculator calculator = new OrderPriceCalculator();
```

`OrderTest` では `Order` が SUT となる。

SUT を意識すると、「今回どのクラス・処理の振る舞いを保証したいのか」と「そのために準備している周辺オブジェクトは何か」を分けて読みやすくなる。

### Test Case と Test Scenario

Test Case は、ある条件・入力・操作・期待結果を一つの確認単位として整理したものを指す。  
例えば、Unit 01 には次のような Test Case がある。

```text
条件:
商品小計が 10,000 円

操作:
OrderPriceCalculator.calculate(...) を実行する

期待結果:
discountAmount が 1,000 円となる
```

Test Scenario は、複数の操作や状態変化を含む、より大きな一連の確認の流れを表す場合に使われる。  
例えば REST API 全体を対象として「注文作成 → 注文確定 → 発送」という流れを確認する場合は、複数の処理を含む Scenario として考えやすい。

用語の使われ方は組織やプロジェクトによって多少異なる場合があるが、この学習では、Test Case を個別の確認条件、Test Scenario を一連の利用・処理の流れとして区別して考える。

### テストの粒度と種類

自動テストには複数の粒度が存在する。  
名称だけで機械的に分類するのではなく、「どこまでを一緒に動かすのか」というテスト境界を見ることが重要となる。

#### Unit Test

比較的小さな単位を対象として、その対象の振る舞いを確認する Test。  
対象となる単位は必ずしも Java の 1 method と一致するわけではなく、1 class や小さな責務のまとまりを対象とする場合もある。

Unit 01 の Test は次のような構成となる。

```text
Test Code
  ↓
OrderPriceCalculator / Order
```

Spring Context、DB、HTTP、外部 API は起動しない。

#### Integration Test

複数の Component や Layer、または Application と外部依存を組み合わせ、それらの接続や協調が成立することを確認する Test。

この学習では後続 Unit で、例えば次のような境界を扱う。

```text
Repository
  ↓
PostgreSQL
```

または、

```text
Service
  ↓
Repository
  ↓
PostgreSQL
```

Unit Test より広い範囲を本物として動かすため、実行時間や準備コストは大きくなりやすい。

#### System Test

Application や System 全体を、一つの統合された System として確認する考え方。  
個別 class の内部実装よりも、System 全体として要求された機能が成立するかを確認する。

#### E2E Test

End-to-End Test は、利用者側の入口から最終的な依存先まで、処理経路を広く通して確認する Test を指す。

Web Application では、Browser 操作まで含める E2E Test も多い。  
今回の学習では Browser 操作を含む E2E Test は対象外だが、Unit 09 で REST API の入口から DB / 外部 API を含む広い範囲を確認する。

#### Acceptance Test

System が利用者や Business の要求を満たしているかという観点で確認する Test。  
技術的な class や method の正しさより、要求や受け入れ条件に対して成立しているかを重視する。

テストの「粒度」と「目的」は完全に同じ軸ではない。  
例えば同じ HTTP 経由の Test でも、どの要求を確認しているかによって Acceptance Test として位置づけられる場合がある。

### Regression Test

Regression Test は、変更によって既存の正常な振る舞いが壊れていないことを確認するための再テストを指す。

一度作成した自動テストを Production Code の変更後にも継続して実行することは、Regression を検出する基本的な仕組みとなる。  
Unit 10、Unit 11 では、複数の Test をまとめて実行し、CI から継続的に確認する流れへつなげる。

### Smoke Test

Smoke Test は、System の主要機能が最低限成立しており、詳細なテストを続けられる状態かを短時間で確認するための Test を指す。

例えば Application 起動後に主要な API が応答することだけを先に確認し、その後に詳細な Scenario Test を行うような使い方がある。

Unit 01 の 6 Test を Smoke Test として分類するわけではない。  
ここでは、自動テスト全体を理解するための基本用語として位置づけを確認する。

### Test Pyramid

Test Pyramid は、自動テスト群の構成を考えるための代表的な考え方である。  
一般的には、小さく高速な Test を多く持ち、外部依存や System 全体を含む大きな Test ほど数を絞るイメージで表される。

```text
        E2E / System
       ────────────
       Integration
     ───────────────
          Unit
   ──────────────────
```

下側の Test ほど一般に次の特徴を持ちやすい。

```text
高速
原因を特定しやすい
外部環境への依存が少ない
多数実行しやすい
```

上側へ行くほど、より広い接続を確認できる一方で、実行時間、環境準備、失敗原因の切り分けなどのコストが増えやすい。

Test Pyramid は「必ずこの比率にしなければならない」という絶対的なルールではない。  
どのテスト境界にどの程度の Test を配置するかを考えるためのモデルとして理解する。

Unit 01 は Pyramid の最も小さい側にあたる Plain Unit Test から開始し、後続 Unit で徐々にテスト境界を外側へ広げる。

### JUnit の役割

JUnit は Java で Test Code を記述・実行するための Testing Framework である。  
Unit 01 では、主に次の機能を利用する。

```text
@Test
Assertions
Test Discovery
Test Execution
Test Result
```

JUnit を利用すると、通常の `main` method に確認処理を書くのではなく、Test Method として確認単位を分離し、Test Runner からまとめて実行できる。

### JUnit Platform と JUnit Jupiter

現在の JUnit は、複数の構成要素に分かれている。  
Unit 01 では、まず JUnit Platform と JUnit Jupiter の関係を押さえる。

```text
JUnit Platform
├─ Test を発見・実行するための基盤
└─ Test Engine を動かす

JUnit Jupiter
├─ @Test などの Programming Model
├─ Assertions
└─ Jupiter Test Engine
```

JUnit Platform は Test を発見・実行するための基盤となる。  
JUnit Jupiter は、Unit 01 で使用する `@Test` や `Assertions` などの JUnit の Programming Model と、Jupiter Test を実行する Test Engine を提供する。

`pom.xml` では次の dependency を `test` scope で追加している。

```xml
<dependency>
  <groupId>org.junit.jupiter</groupId>
  <artifactId>junit-jupiter</artifactId>
  <scope>test</scope>
</dependency>
```

`test` scope の dependency は Test Code の compile / execution で利用し、通常の Production Application 実行時の依存関係として扱わない。

### `src/test/java` の位置づけ

Maven の標準的な Project Layout では、Production Code と Test Code を次のように分離する。

```text
src/
├─ main/
│  └─ java/
│     └─ Production Code
└─ test/
   └─ java/
      └─ Test Code
```

今回の Test Code も `src/test/java` 配下に置く。

```text
src/test/java/com/example/orderapi/
├─ domain/
│  └─ OrderTest.java
└─ service/
   └─ OrderPriceCalculatorTest.java
```

Production Code と Test Code は別の Source Set として管理されるが、Test Code から Production Code を参照して振る舞いを確認できる。

### テストクラスとテストメソッド

Unit 01 では Production Class に対応する形で Test Class を配置している。

```text
OrderPriceCalculator
└─ OrderPriceCalculatorTest

Order
└─ OrderTest
```

Test Class の中には、確認したい振る舞いごとに Test Method を定義する。

```java
@Test
void calculate_returnsPriceSummaryWithoutDiscount() {
    // ...
}
```

Test Method 名は、単なる連番ではなく、どの条件でどの振る舞いを期待しているかを読み取りやすい名前にしている。

### `@Test`

`@Test` は、その method を JUnit Jupiter の Test Method として扱うための Annotation である。

```java
@Test
void createdOrder_canBeConfirmedAndCancelledButCannotBeShipped() {
    // ...
}
```

通常の Java method を Test Class に置いただけでは、それが自動的に Test Method になるわけではない。  
JUnit は `@Test` などの情報を利用して実行対象を発見する。

### Assertion

Assertion は、実際の結果が期待結果を満たしているかを検証する処理である。

Unit 01 では JUnit Jupiter が提供する基本的な Assertion を直接利用する。

#### equality の検証

```java
assertEquals(expected, actual);
```

期待値と実際値が等しいことを検証する。

Unit 01 では、例えば次を確認している。

```java
assertEquals(BigDecimal.valueOf(6_000), actual.getSubtotal());
assertEquals(OrderStatus.CREATED, order.getStatus());
```

「値が返ってきた」だけでなく、その値が仕様上期待した値と一致していることを保証する。

#### boolean の検証

```java
assertTrue(condition);
assertFalse(condition);
```

boolean の振る舞いを直接検証する。

```java
assertTrue(order.canConfirm());
assertFalse(order.canShip());
assertTrue(order.canCancel());
```

この Test は CREATED 状態の `Order` に対して、許可される操作と許可されない操作を保証している。

#### `null` の検証

```java
assertNull(value);
assertNotNull(value);
```

`null` であること、または `null` ではないことを検証する。

新規 `Order` は DB 保存前のため `id` を持たず、キャンセルされていないため `cancelledAt` も `null` となる。

```java
assertNull(order.getId());
assertNull(order.getCancelledAt());
```

一方、生成された明細 List 自体が存在することは `assertNotNull` で確認している。

#### 例外の検証

例外が発生すること自体が正しい振る舞いとなる場合がある。  
その場合は `assertThrows` を利用する。

```java
IllegalArgumentException exception =
        assertThrows(
                IllegalArgumentException.class,
                () -> calculator.calculate(items, shippingFee));
```

この Test では、空の注文明細を渡した場合に正常な `PriceSummary` を返すのではなく、`IllegalArgumentException` を発生させることを期待している。

続いて message も確認する。

```java
assertEquals("items must not be empty", exception.getMessage());
```

Test の成功条件は「例外が発生しないこと」ではない。  
Test Case によっては、期待した種類の例外が発生することが正しい結果となる。

### Arrange-Act-Assert

Arrange-Act-Assert は Test Code を整理する代表的な構造で、AAA と略される。

```text
Arrange
テストに必要な SUT、入力、状態を準備する

Act
確認したい処理を実行する

Assert
実際の結果が期待結果を満たすか確認する
```

`calculate_returnsPriceSummaryWithoutDiscount` では、この 3 段階をコメントで明示している。

```java
// Arrange
OrderPriceCalculator calculator = new OrderPriceCalculator();

// Act
PriceSummary actual = calculator.calculate(items, shippingFee);

// Assert
assertEquals(BigDecimal.valueOf(6_000), actual.getSubtotal());
```

AAA を意識すると、「準備処理」と「今回本当に確認したい実行」と「結果の検証」が混ざりにくくなる。

### Given-When-Then

Given-When-Then も Test の流れを整理する代表的な表現である。

```text
Given
前提・初期状態

When
操作・イベント

Then
期待する結果
```

Unit 01 では `calculate_appliesDiscountWhenSubtotalReachesThreshold` でコメントとして利用している。

AAA と Given-When-Then は完全に別の Test 技法というより、Test の構造を読みやすく整理するための異なる表現として理解できる。

概ね次のように対応する。

| AAA     | Given-When-Then |
| ------- | --------------- |
| Arrange | Given           |
| Act     | When            |
| Assert  | Then            |

Given-When-Then は Business の振る舞いや Scenario を文章として意識しやすい表現となる。

### テスト成功と失敗

JUnit の Test は、期待した Assertion がすべて成立し、予期しない例外が発生しなければ成功となる。

例えば次が成立すれば、その Assertion は成功する。

```java
assertEquals(BigDecimal.valueOf(6_000), actual.getSubtotal());
```

一方、実際値が `6,000` なのに期待値を `7,000` としていた場合、Assertion は失敗する。

```text
expected: <7000>
 but was: <6000>
```

Test Failure は単に「テストが邪魔をしている」という意味ではない。  
期待した振る舞いと実際の振る舞いに差があることを知らせる情報となる。

差が発生した場合は、次の両方を確認する必要がある。

```text
Production Code が誤っている可能性
Test Code の期待値や前提が誤っている可能性
```

### Assertion Failure

Assertion Failure は、Assertion が期待条件を満たさなかったことによる Test Failure を指す。

例えば `assertEquals` なら、期待値と実際値の差が Failure message に表示される。  
まず Failure message から「何を期待し、実際には何だったのか」を確認する。

Test が失敗したときは、Test Method 名も重要な情報となる。

```text
calculate_returnsPriceSummaryWithoutDiscount
```

この名前から、割引対象外の金額計算に関する Test が失敗したことを把握できる。

### Stack Trace の基本的な読み方

Exception や Assertion Failure が発生すると、Stack Trace が表示される。

初学時に Stack Trace 全体を最初から最後まで読む必要はない。  
まず次の順で確認する。

```text
1. 失敗した Test Class / Test Method
2. Error / Exception / Assertion Failure の種類
3. Failure message
4. 自分の Test Code の該当行
5. 必要に応じて Production Code の呼び出し元
```

Framework 内部の大量の呼び出しより、まず自分が書いた Test Code と Production Code に近い行を探す。

例えば `OrderPriceCalculatorTest.java:XX` が表示されていれば、その行の Assertion や SUT 呼び出しを起点に確認する。

### Test Discovery

Test Discovery は、Test Runner が実行対象となる Test を見つける処理を指す。

今回の Test Code では、主に次の情報が Discovery に関係する。

```text
src/test/java 配下に Test Code がある
Test Class 名が Maven の Test 実行で扱いやすい *Test 形式になっている
Test Method に @Test が付いている
JUnit Jupiter が Test Engine を提供する
```

`OrderPriceCalculatorTest` と `OrderTest` は、Maven から Test として検出され、内部の `@Test` method が JUnit Jupiter によって実行される。

### Test Runner

Test Runner は、発見した Test を実際に実行し、成功・失敗を集計する役割を持つ。

この学習では、IDE の実行機能だけに依存せず Maven から Test を実行できることを重視する。

```bash
./mvnw test
```

Maven を入口にすることで、ローカルでも後の CI でも同じ Project 設定を使って Test を実行しやすくなる。

### Maven からのテスト実行

Unit 01 では Maven Wrapper を使用する。

```bash
./mvnw test
```

`test` phase では `src/test/java` の Test Code が compile され、Maven の Test 実行機構から JUnit Platform / Jupiter の Test が実行される。

Unit 01 では Docker Compose、PostgreSQL、WireMock、Spring Boot Application の起動は不要となる。  
Test が必要とする範囲が Java Object の内部だけに閉じているためである。

### TDD

TDD は Test-Driven Development の略で、Test を先に書き、その Test を利用しながら Production Code を成長させていく開発アプローチを指す。

代表的な流れとして Red-Green-Refactor がある。

```text
Red
期待する振る舞いを Test として書き、まだ実装されていないため失敗する

Green
Test を通すために必要な Production Code を実装する

Refactor
Test が成功する状態を維持しながら設計やコードを整理する
```

今回の学習リポジトリ自体は TDD で Production Code を開発する教材ではない。  
Production Code は Unit 01 開始前に完成させており、完成した Production Code に対する Test Code をコードリーディングする。

そのため Unit 01 では、TDD を自動テストと関係する代表的な開発アプローチとして理解することを目的とする。

### BDD

BDD は Behavior-Driven Development の略で、System の「振る舞い」を中心に要求や Test を考えるアプローチを指す。

Given-When-Then は BDD でよく利用される表現でもある。

```text
Given: どのような前提か
When: 何が起きたか
Then: どのような振る舞いを期待するか
```

BDD は単に Test Method のコメントを Given / When / Then に変更することではない。  
利用者や Business が期待する振る舞いを共通言語として捉える考え方が中心となる。

今回の学習では BDD を中心とした開発プロセスは実践対象外とし、JUnit Test を理解するための関連概念として位置づける。

## 使用する技術・ツール

### Java 21

Production Code と Test Code の実装言語として利用する。  
Unit 01 の Test は Java Object を直接生成するため、Spring 固有の Test API は使用しない。

### JUnit Jupiter

Unit 01 の Test Framework として利用する。

主に次を使用する。

```text
@Test
assertEquals
assertTrue
assertFalse
assertNull
assertNotNull
assertThrows
```

### JUnit Platform

JUnit Jupiter Test を発見・実行する基盤として利用される。  
Unit 01 では Platform API を直接呼び出さないが、JUnit Test がどのような構成で実行されるかを理解するために位置づけを確認する。

### Maven / Maven Wrapper

Project の Build Tool と Test 実行入口として利用する。

```bash
./mvnw test
```

環境へ別途 Maven を導入しているかどうかに依存せず、Project が管理する Maven Wrapper から実行する。

### JUnit Assertions

実際の結果と期待結果を比較するために利用する。  
Unit 01 では JUnit 標準の Assertions を直接使用し、AssertJ はまだ導入しない。

## テスト対象とテスト境界

Unit 01 では、Spring や外部環境を利用しない Plain Unit Test を扱う。

### `OrderPriceCalculatorTest`

```text
SUT:
OrderPriceCalculator

本物として利用:
OrderPriceCalculator
OrderItem
PriceSummary
BigDecimal
List

起動しない:
Spring Context
PostgreSQL
Docker
HTTP Server
Shipping Fee API / WireMock

Test Double:
なし
```

`OrderPriceCalculator` は外部依存を持たないため、Test Code から直接 `new OrderPriceCalculator()` で生成する。

### `OrderTest`

```text
SUT:
Order

本物として利用:
Order
OrderItem
OrderStatus
PriceSummary
Instant
BigDecimal
List

起動しない:
Spring Context
PostgreSQL
Docker
HTTP Server
Shipping Fee API / WireMock

Test Double:
なし
```

この Unit では Mockito や Stub を利用しない。  
依存関係を持つ `OrderService` の Unit Test は Unit 03 で Test Double と Mockito を学ぶ際に扱う。

### Unit Test である理由

今回の Test が Unit Test である理由は、`@Test` を使用しているからではない。

確認対象を小さな Java Object の範囲へ限定し、Spring Context、DB、HTTP、外部 API といった外部境界を動かさず、その Object の振る舞いを直接確認しているため Unit Test として位置づけられる。

同じ JUnit を利用していても、DB や Spring Context を起動する Test は Integration Test や Slice Test など別の境界になる。  
「JUnit を使っている Test = Unit Test」ではないことが重要となる。

## 使用するファイル

### この Unit で追加・変更する主なファイル

- [`pom.xml`](../../pom.xml)
- [`OrderPriceCalculatorTest.java`](../../src/test/java/com/example/orderapi/service/OrderPriceCalculatorTest.java)
- [`OrderTest.java`](../../src/test/java/com/example/orderapi/domain/OrderTest.java)

### コードリーディングで参照する主なファイル

- [`OrderPriceCalculator.java`](../../src/main/java/com/example/orderapi/service/OrderPriceCalculator.java)
- [`Order.java`](../../src/main/java/com/example/orderapi/domain/Order.java)
- [`OrderItem.java`](../../src/main/java/com/example/orderapi/domain/OrderItem.java)
- [`OrderStatus.java`](../../src/main/java/com/example/orderapi/domain/OrderStatus.java)
- [`PriceSummary.java`](../../src/main/java/com/example/orderapi/domain/PriceSummary.java)

## 前提

Unit 01 開始前に、サンプルアプリの Production Code と通常動作確認が完了していることを前提とする。

Production Code は基本固定とし、以降は明確な仕様違反や学習を妨げる不具合がない限り、Test Code の学習を優先する。

Java の基本構文、class、method、constructor、List、enum、例外、`BigDecimal` などは既習事項として扱う。  
一方、自動テスト固有の用語、JUnit の仕組み、Assertion、Test Runner などは Unit 01 で初めて学ぶ内容として説明する。

Unit 01 の Test 実行では次は不要となる。

```text
Docker Compose 起動
PostgreSQL 起動
WireMock 起動
Spring Boot Application 起動
```

## コードリーディング

Unit 01 では、Production Code と対応する Test Code を次の順番で読む。

### 1. `OrderPriceCalculator` の責務を確認する

最初に [`OrderPriceCalculator.java`](../../src/main/java/com/example/orderapi/service/OrderPriceCalculator.java) を読む。

確認する内容:

- Spring Component や Repository に依存していないこと。
- `OrderItem` と `shippingFee` を入力として金額を計算すること。
- subtotal、discountAmount、shippingFee、totalAmount の関係。
- 商品小計 10,000 円以上で 10% 割引となること。
- 入力条件を満たさない場合に例外となる箇所。

ここでは Test Code を見る前に、「この class のどの振る舞いを Test できそうか」を確認する。

### 2. `OrderPriceCalculatorTest` 全体を見る

[`OrderPriceCalculatorTest.java`](../../src/test/java/com/example/orderapi/service/OrderPriceCalculatorTest.java) を開き、まず Test Class 全体の構造を見る。

```text
OrderPriceCalculatorTest
├─ calculate_returnsPriceSummaryWithoutDiscount
├─ calculate_appliesDiscountWhenSubtotalReachesThreshold
└─ calculate_throwsIllegalArgumentExceptionWhenItemsAreEmpty
```

3 Test が、それぞれ異なる振る舞いを確認していることを把握する。

### 3. 割引なしの Test で AAA を読む

`calculate_returnsPriceSummaryWithoutDiscount` を読む。

#### Arrange

```java
OrderPriceCalculator calculator = new OrderPriceCalculator();
```

SUT を直接生成する。  
Spring から Bean を取得していないことを確認する。

続いて `OrderItem`、`List`、`shippingFee` を準備している。  
これらは SUT を動かすための入力となる。

#### Act

```java
PriceSummary actual = calculator.calculate(items, shippingFee);
```

今回確認したい Production Method を実行する。

Test Code の中で Production Code の実行がどこにあるかを明確にすることが重要となる。

#### Assert

```java
assertNotNull(actual);
assertEquals(BigDecimal.valueOf(6_000), actual.getSubtotal());
assertEquals(BigDecimal.ZERO, actual.getDiscountAmount());
assertEquals(BigDecimal.valueOf(800), actual.getShippingFee());
assertEquals(BigDecimal.valueOf(6_800), actual.getTotalAmount());
```

それぞれの Assertion が何を保証しているのかを確認する。

特に、単に `actual != null` だけを確認するのではなく、業務上意味のある金額まで検証している点を見る。

### 4. 10,000 円の Test で Given-When-Then を読む

`calculate_appliesDiscountWhenSubtotalReachesThreshold` を読む。

この Test ではコメントを Given / When / Then としている。

```text
Given
10,000 円となる OrderItem

When
calculate を実行

Then
discountAmount = 1,000
totalAmount = 9,800
```

AAA と Test の基本構造は同じでありながら、Business の前提・操作・期待結果として読みやすく表現していることを確認する。

この Unit では 10,000 円だけを代表ケースとして読む。  
9,999 / 10,000 / 10,001 のように境界値を体系的に設計する方法は Unit 02 で扱う。

### 5. 例外 Test で `assertThrows` を読む

`calculate_throwsIllegalArgumentExceptionWhenItemsAreEmpty` を読む。

```java
IllegalArgumentException exception =
        assertThrows(
                IllegalArgumentException.class,
                () -> calculator.calculate(items, shippingFee));
```

確認する内容:

- 例外が発生すること自体を期待していること。
- 期待する Exception class を指定していること。
- Lambda 内が実際に例外発生を確認する処理であること。
- 返された Exception Object から message も検証できること。

正常系だけでなく、入力条件を満たさない場合の振る舞いも Test Code で表現できることを確認する。

### 6. `Order` の責務と状態判定を見る

次に [`Order.java`](../../src/main/java/com/example/orderapi/domain/Order.java) と [`OrderStatus.java`](../../src/main/java/com/example/orderapi/domain/OrderStatus.java) を読む。

Unit 01 では、状態遷移全体を網羅するのではなく、次の単純な振る舞いに注目する。

```text
新規 Order は CREATED
新規 Order の id は null
新規 Order の cancelledAt は null

CREATED:
confirm 可能
ship 不可
cancel 可能

CONFIRMED:
confirm 不可
ship 可能
cancel 可能
```

### 7. `OrderTest` で基本 Assertion を対応させる

[`OrderTest.java`](../../src/test/java/com/example/orderapi/domain/OrderTest.java) を読む。

#### equality / null

`create_setsInitialStateForNewOrder` では次を確認する。

```java
assertEquals(OrderStatus.CREATED, order.getStatus());
assertNull(order.getId());
assertNull(order.getCancelledAt());
assertNotNull(order.getItems());
assertEquals(createdAt, order.getCreatedAt());
```

`assertEquals`、`assertNull`、`assertNotNull` が、それぞれ Domain Model のどの仕様と対応しているかを確認する。

#### boolean

`createdOrder_canBeConfirmedAndCancelledButCannotBeShipped` では次を確認する。

```java
assertTrue(canConfirm);
assertFalse(canShip);
assertTrue(canCancel);
```

Test Method 名と Assertion を対応させると、どの振る舞いを保証している Test なのかを読み取りやすい。

#### 状態を変えた Test

`confirmedOrder_canBeShippedAndCancelledButCannotBeConfirmedAgain` では、`Order.restore(...)` によって `CONFIRMED` の Order を準備している。

同じ `canConfirm()` / `canShip()` / `canCancel()` でも、SUT の状態が異なると期待値が変わることを確認する。

全状態と操作の組み合わせを整理する Decision Table / Test Matrix は Unit 02 で扱う。

### 8. Test Helper Method の役割を見る

`OrderTest` には次の private helper method がある。

```text
createOrder
createOrderItem
createPriceSummary
```

Test Method ごとに同じ Data 準備を大量に繰り返さず、読みたい Test の本体を見やすくする役割がある。

ただし Unit 01 では、Fixture 設計や `@BeforeEach` などを本格的には扱わない。  
Setup / Teardown、Fixture の整理は Unit 02 で扱う。

### 9. `pom.xml` で JUnit の導入位置を確認する

最後に [`pom.xml`](../../pom.xml) を確認する。

```xml
<dependency>
  <groupId>org.junit.jupiter</groupId>
  <artifactId>junit-jupiter</artifactId>
  <scope>test</scope>
</dependency>
```

確認する内容:

- JUnit が Test 用 dependency であること。
- Production Code 自体へ JUnit を持ち込んでいないこと。
- Test Code が `src/test/java` に分離されていること。
- Unit 01 では AssertJ / Mockito などをまだ追加していないこと。

## 実行・確認

### 全 Test を実行する

Docker、PostgreSQL、WireMock、Spring Boot Application を起動せず、リポジトリルートで実行する。

```bash
./mvnw test
```

Unit 01 の成果物だけが Test として存在する状態では、次の 2 Test Class、合計 6 Test が実行される。

```text
OrderPriceCalculatorTest: 3
OrderTest: 3

Total: 6
```

最終的に Maven が次の状態となることを確認する。

```text
BUILD SUCCESS
```

見るべき点は Maven log 全体ではなく、主に次となる。

```text
Tests run
Failures
Errors
Skipped
BUILD SUCCESS / BUILD FAILURE
```

正常時は、Unit 01 の 6 Test がすべて成功し、Failures / Errors が 0 となることを確認する。

### `OrderPriceCalculatorTest` だけを実行する

```bash
./mvnw -Dtest=OrderPriceCalculatorTest test
```

3 Test が実行されることを確認する。

### `OrderTest` だけを実行する

```bash
./mvnw -Dtest=OrderTest test
```

3 Test が実行されることを確認する。

Class 単位で Test を絞り込めることを確認し、「Project の全 Test を実行する場合」と「調査対象だけを実行する場合」の入口が異なることを理解する。

### Test が失敗した場合に見る場所

Test Failure が発生した場合は、まず次の順番で確認する。

```text
1. どの Test Class / Test Method が失敗したか
2. Failure か Error か
3. expected / actual や Exception message
4. Test Code の該当行
5. 必要に応じて Production Code
```

Assertion Failure の場合は、期待値と実際値の違いを最初に確認する。

```text
expected: <...>
 but was: <...>
```

予期しない Exception の場合は Exception class と message を確認し、自分の Test / Production Code に最初に現れる Stack Trace の行を探す。

### Assertion Failure を理解するための補足確認

Unit 01 の正式な成果物はすべて成功する状態としている。  
学習の必須作業として Test Code を壊す必要はない。

Assertion Failure の表示を実際に確認したい場合だけ、ローカルで一時的に期待値を変更して `./mvnw test` を実行し、Failure message と Stack Trace を確認できる。  
確認後は必ず元の正しい期待値へ戻す。

これは任意確認であり、Unit 01 の成果物として誤った Test Code を残すものではない。

## 学習ポイント

### Test Code は振る舞いを保証するコード

Test Code を読むときは、JUnit API の名前だけを追わない。  
「この Test Method は、どの条件で、SUT のどの振る舞いを保証しようとしているか」を最初に考える。

例えば、

```java
assertEquals(BigDecimal.valueOf(1_000), actual.getDiscountAmount());
```

という 1 行は、単なる Object 比較ではなく、「小計 10,000 円なら 10% = 1,000 円を割り引く」という業務上の振る舞いを保証している。

### SUT と周辺準備を区別する

`OrderPriceCalculatorTest` には `OrderItem` や `List` も登場するが、主な SUT は `OrderPriceCalculator` となる。

Test Code に登場するすべての Object を同じ重要度で見るのではなく、「何を確認する Test なのか」を中心に読む。

### Unit Test かどうかは JUnit の使用だけでは決まらない

JUnit は Unit Test だけの Framework ではない。  
後続 Unit でも JUnit を利用しながら、Spring Context、PostgreSQL、HTTP Server、WireMock などを含む Test を実装する。

重要なのは Framework 名ではなくテスト境界となる。

```text
Unit 01:
Java Object の小さな範囲

後続 Unit:
Web Layer
Repository + PostgreSQL
Spring Boot + DB
External HTTP API
REST API 全体
```

### 小さい Test は失敗原因を追いやすい

Unit 01 の Test が失敗した場合、DB 接続や HTTP 通信を疑う必要はない。  
テスト境界が小さいため、SUT、入力、Assertion 周辺へ原因を絞りやすい。

後続 Unit でテスト境界が広がると、構成する要素も増える。  
Unit ごとに「今回どこまでを動かしているのか」を意識することが重要となる。

### AAA / Given-When-Then は Test の意図を読みやすくする

どちらも Test を、

```text
準備
↓
実行
↓
検証
```

という流れで整理する考え方となる。

形式を守ること自体が目的ではなく、Test の中心となる操作と期待結果を読み取りやすくするために利用する。

### Test Failure は原因調査の入口

Test が失敗したら、すぐに Production Code が誤っていると決めつけない。

```text
Production Code
Test Code
Test Data
期待値
前提条件
```

のどこに差があるかを確認する。

Unit 01 では小さな Unit Test から、この Failure の読み方に慣れる。

## 補足

### Unit 02 で扱う内容

Unit 01 の Test Case は JUnit の基本を理解するための代表ケースに絞っている。  
次の内容は Unit 02「テストケース設計と JUnit 実践」で体系的に扱う。

```text
正常系 / 異常系
Happy Path / Edge Case
境界値分析
同値分割
Decision Table
Test Matrix
Test Oracle
Test Fixture
@BeforeEach / @AfterEach
@BeforeAll / @AfterAll
@Nested
@DisplayName
assertAll
Parameterized Test
@ValueSource
@CsvSource
@MethodSource
AssertJ
```

そのため、Unit 01 の Test にすべての境界値や状態遷移を追加して網羅しようとする必要はない。

### Unit 03 で扱う内容

`OrderService` は `ProductRepository`、`OrderRepository`、`ShippingFeeClient`、`Clock` などの依存関係を持つ。  
これらを Unit Test でどのように扱うかは、Unit 03「Test Double と Mockito」で学ぶ。

Unit 01 では、依存関係を置き換える必要がない小さな class を利用することで、JUnit 自体の基本に集中する。

### Spring Test との違い

Unit 01 では Spring Test Annotation を使用しない。

```text
@SpringBootTest
@WebMvcTest
@JdbcTest
```

などは後続 Unit で扱う。

Unit 01 の Test は Spring Framework がなくても SUT を直接生成して実行できる範囲となる。

### TDD / BDD の扱い

TDD と BDD は今回の学習全体の中心テーマではない。  
Unit 01 では自動テストに関連する代表的な考え方として位置づけを理解する。

このリポジトリでは完成済み Production Code に対する Test Code を読み解く学習方式を採用しているため、Unit 01 の学習手順自体を Red-Green-Refactor で進める必要はない。
