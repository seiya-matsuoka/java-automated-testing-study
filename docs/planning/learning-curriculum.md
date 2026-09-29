# Java 自動テスト学習カリキュラム

## 1. このドキュメントの目的

このドキュメントは、`java-automated-testing-study` における学習範囲と学習順序を定義する。  
Java / Spring Boot の Web アプリケーションを題材として、自動テスト・テストコードに関する基礎から実務で頻出する周辺技術までを体系的に一周することを目的とする。  
JUnit の API だけを個別に覚えるのではなく、何をどの粒度でテストするのか、依存関係をどのように扱うのか、DB・HTTP・外部 API を含む場合にどのようなテストへ広げるのか、作成したテスト群をどのように管理・自動実行するのかまでを一連の流れとして理解する。

## 2. 学習目標

この学習では、次の状態を目標とする。

- Java / Spring Boot における Unit Test、Integration Test、API Test などの違いと役割を理解する。
- 仕様や業務ルールから、正常系・異常系・境界値・条件組み合わせなどのテストケースを考えるための基礎を身につける。
- JUnit を用いた基本的なテストコードの構造と実行方法を理解する。
- Mockito を用いて、依存先を Test Double に置き換える考え方と実装方法を理解する。
- テストしやすい設計、テストの独立性、再現性、Flaky Test など、自動テスト自体の品質に関する考え方を理解する。
- Spring MVC、Repository、PostgreSQL、外部 HTTP API を含むテストを、テスト対象の境界に応じて使い分けられるようになる。
- Spring JDBC と明示的な SQL を用いた Repository を、実 PostgreSQL に対してテストする方法を理解する。
- WireMock や REST Assured を用いて、外部 API や実 HTTP 通信を含むテストを理解する。
- Maven による Unit Test / Integration Test の実行管理と、JaCoCo による Coverage の確認方法を理解する。
- GitHub Actions によって、自動テストを CI 上で実行する一連の流れを理解する。
- 実務で自動テストに関する会話やコードに触れた際に、主要な用語・考え方・代表的なツールの役割を把握できる基礎を作る。

## 3. 学習対象

今回の中心対象は、Java / Spring Boot のバックエンドアプリケーションにおける自動テストとする。  
学習の中心となる技術・領域は次のとおりとする。

- JUnit
- AssertJ
- Mockito
- Spring Test / Spring Boot Test
- MockMvc
- Spring JDBC
- PostgreSQL
- Testcontainers
- WireMock
- REST Assured
- Maven Surefire / Failsafe
- JaCoCo
- GitHub Actions
- Unit Test / Integration Test / API Test
- Test Double
- Test Fixture
- Test Isolation
- Coverage
- Test Matrix
- Flaky Test
- Testability
- CI による自動テスト実行

学習では、1 つの Spring Boot 製の注文管理 REST API をメインアプリとして使用する。  
基本的には全 Unit をメインアプリで学習し、メインアプリへ不自然なコードや悪い設計を入れる必要がある場合のみ、補助教材を使用する。

## 4. 今回の対象外

次の領域は、関連する概念や位置づけを説明する場合はあるが、今回の実践対象には含めない。

- JavaScript / TypeScript / React の自動テスト
- React Testing Library
- Vitest
- Jest
- Playwright
- Cypress
- Selenium
- Selenide
- ブラウザ操作を含む E2E Test の実践
- Performance Test / Load Test
- JMeter
- Gatling
- Contract Test の実践
- Pact
- Architecture Test の実践
- ArchUnit
- Mutation Testing の実践
- PIT
- Security Test
- Chaos Testing
- CD / Deployment
- TDD を中心とした開発プロセスの実践
- BDD を中心とした開発プロセスの実践
- JPA / Hibernate を中心とした Repository 実装
- MyBatis を中心とした Repository 実装

TDD、BDD、Mutation Testing、E2E Test などについては、今回扱う内容との関係を理解するために、関連 Unit で概念や位置づけを説明する。

## 5. 学習全体の流れ

学習は、テスト対象の境界を少しずつ外側へ広げる順序で進める。

```text
小さな Java クラスの Unit Test
        ↓
依存関係を持つ Service の Unit Test
        ↓
テストしやすい設計とテスト品質
        ↓
Spring Web 層
        ↓
Repository / PostgreSQL
        ↓
Spring Boot + DB の Integration Test
        ↓
外部 HTTP API
        ↓
REST API 全体
        ↓
テストスイート全体の管理・Coverage
        ↓
CI による自動実行
```

前半では、Spring を必要以上に意識せず、自動テストそのものの基礎を学ぶ。  
中盤では、Spring Boot の Web 層、DB、外部 HTTP API など、実際のバックエンドアプリケーションで登場する境界へ学習対象を広げる。  
後半では、作成したテスト群をプロジェクト全体として管理し、CI 上で自動実行するところまで扱う。

## 6. Unit 一覧

| Unit | ディレクトリ名                             | 学習テーマ                           | 主教材                  |
| ---- | ------------------------------------------ | ------------------------------------ | ----------------------- |
| 01   | `01-automated-testing-junit-basics`        | 自動テストの全体像と JUnit 基本      | メインアプリ            |
| 02   | `02-test-design-junit-practice`            | テストケース設計と JUnit 実践        | メインアプリ            |
| 03   | `03-test-doubles-mockito`                  | Test Double と Mockito               | メインアプリ            |
| 04   | `04-testability-test-quality`              | テストしやすい設計とテスト品質       | メインアプリ + 補助教材 |
| 05   | `05-spring-web-testing`                    | Spring Web 層のテスト                | メインアプリ            |
| 06   | `06-sql-repository-db-integration-testing` | SQL・Repository・DB Integration Test | メインアプリ            |
| 07   | `07-spring-boot-integration-testing`       | Spring Boot Integration Test         | メインアプリ            |
| 08   | `08-external-http-api-testing`             | 外部 HTTP API のテスト               | メインアプリ            |
| 09   | `09-rest-api-testing`                      | REST API 全体のテスト                | メインアプリ            |
| 10   | `10-test-management-coverage`              | テスト実行管理と Coverage            | メインアプリ            |
| 11   | `11-ci-automated-testing`                  | CI による自動テスト                  | メインアプリ            |

## 7. Unit 01 自動テストの全体像と JUnit 基本

### 学習目的

自動テストの全体像と基本用語を理解し、Spring や DB に依存しない Java クラスに対して基本的な JUnit のテストコードを読める状態を作る。  
JUnit の記法だけではなく、何をテスト対象とし、どのような粒度のテストが存在するのかを最初に整理する。

### 主な学習内容

- Manual Test と Automated Test
- 自動テストを行う目的
- テストコードの役割
- SUT（System Under Test）
- Test Case
- Test Scenario
- Unit Test
- Integration Test
- System Test
- E2E Test
- Acceptance Test
- Regression Test
- Smoke Test
- Test Pyramid
- JUnit の役割
- JUnit Platform と Jupiter の基本的な関係
- `src/test/java` の位置づけ
- テストクラスとテストメソッド
- `@Test`
- 基本的な Assertion
- equality の検証
- boolean の検証
- `null` の検証
- 例外の検証
- Arrange-Act-Assert
- Given-When-Then
- テスト成功 / 失敗
- Assertion Failure
- Stack Trace の基本的な読み方
- Test Discovery
- Test Runner
- Maven からのテスト実行
- `mvn test`
- TDD の基本概念
- Red-Green-Refactor の位置づけ
- BDD の基本概念

### 主なテスト対象

- `OrderPriceCalculator`
- `Order` の単純な判定処理
- その他 Spring に依存しない小さな Domain / Service 処理

### 学習上のポイント

JUnit を使ったテストコードが、単なるメソッド呼び出しと比較処理ではなく、「どの振る舞いを保証したいのか」をコードとして表現するものであることを理解する。  
また、JUnit を使用しているからといって、すべてのテストが Unit Test になるわけではないことを理解する。

## 8. Unit 02 テストケース設計と JUnit 実践

### 学習目的

仕様や業務ルールから、どのようなテストケースを用意するべきかを考えるための基本的な考え方を学ぶ。  
JUnit の基本機能を一段深く扱い、複数ケースや境界値を効率よく表現する方法を理解する。

### 主な学習内容

- 正常系
- 異常系
- Happy Path
- Edge Case
- 境界値分析
- 同値分割
- Decision Table
- Test Matrix
- Test Oracle
- 期待値の決め方
- 条件分岐とテストケース
- 状態遷移とテストケース
- テストケースの重複を避ける考え方
- Test Fixture
- Setup / Teardown
- `@BeforeEach`
- `@AfterEach`
- `@BeforeAll`
- `@AfterAll`
- `@Nested`
- `@DisplayName`
- `assertThrows`
- `assertAll`
- Parameterized Test
- `@ValueSource`
- `@CsvSource`
- `@MethodSource`
- AssertJ
- fluent assertion
- object の検証
- collection の検証
- exception の検証
- JUnit Assertions と AssertJ の役割の違い

### 主なテスト対象

- 注文数量 1～99 の境界
- 小計 10,000 円を境界とする割引判定
- 注文金額計算
- 複数明細の金額計算
- OrderStatus の状態遷移
- 注文キャンセル可否

### 学習上のポイント

テストコードを書く前に、仕様からケースを整理する工程が重要であることを理解する。  
特に、境界値や状態遷移など、実装コードの見た目だけでは抜けやすいケースを体系的に考える方法を学ぶ。

## 9. Unit 03 Test Double と Mockito

### 学習目的

依存関係を持つクラスを Unit Test する際に、本物の依存先をどのように置き換えるかを理解する。  
Test Double の考え方を整理し、Mockito を用いた stubbing、verification、interaction testing を実践的に理解する。

### 主な学習内容

- Test Double
- Dummy
- Stub
- Fake
- Spy
- Mock
- Test Double の分類と役割
- state verification
- interaction verification
- Mockito
- mock object の作成
- stubbing
- 戻り値の指定
- 例外の stubbing
- verification
- 呼び出し回数の確認
- 呼ばれていないことの確認
- Argument Matcher
- ArgumentCaptor
- Spy
- `@Mock`
- `@InjectMocks`
- mock を使う範囲の考え方
- mock の使いすぎによる問題
- 実物を使う場合と Test Double を使う場合の違い

### 主なテスト対象

`OrderService` と、次の依存先との関係を利用する。

- `ProductRepository`
- `OrderRepository`
- `ShippingFeeClient`
- `Clock`

### 学習上のポイント

「依存先を mock にできるから mock にする」のではなく、どこまでを今回の SUT とし、どこから先をテスト対象外として制御するかという境界の判断が重要であることを理解する。  
Mockito の API だけではなく、Test Double を利用する目的を理解する。

## 10. Unit 04 テストしやすい設計とテスト品質

### 学習目的

テストコードそのものにも品質があり、テストが通るだけでは十分ではないことを理解する。  
Production Code の設計と Testability の関係、自動テストの信頼性・独立性・再現性に関する基本概念を学ぶ。

### 主な学習内容

- Testability
- Dependency Injection
- hard-coded dependency
- global state
- static mutable state
- 時刻への依存
- random 値への依存
- 外部状態への依存
- `Clock`
- 責務分離
- テストしづらいコードから設計上の問題を見つける考え方
- Isolation
- Independence
- Deterministic
- Repeatable
- Fast
- Self-validating
- FIRST の考え方
- Flaky Test
- Brittle Test
- Test Smell
- 実行順依存
- `sleep` 依存
- 過剰な mock
- 実装詳細への過剰依存
- テスト間で共有される mutable state
- False Positive
- False Negative
- 非同期処理のテストで発生しやすい問題
- Awaitility の位置づけ

### 主なテスト対象

- メインアプリの `Clock` を利用する処理
- Dependency Injection された Service / Client / Repository
- 補助教材による Flaky Test、Brittle Test、実行順依存などの悪い例

### 教材

この Unit のみ、メインアプリに加えて極小の補助教材を使用する。  
Production Code 自体を意図的に悪い設計へ変更せず、問題のあるテストや設計を比較するための例は補助教材として分離する。

### 学習上のポイント

テストしやすさは、テストコードだけの問題ではなく Production Code の責務分離や依存関係の設計とも密接に関係することを理解する。  
また、テストの不安定さを Retry で隠すのではなく、原因を取り除くことが重要であることを理解する。

## 11. Unit 05 Spring Web 層のテスト

### 学習目的

Spring MVC の Web 層を他のレイヤーから切り離してテストする方法を理解する。  
Controller、request / response、Validation、例外ハンドリングなど、HTTP 境界に関するテストを学ぶ。

### 主な学習内容

- Spring Test
- Spring Boot Test の位置づけ
- Spring Context
- Plain Unit Test との違い
- Test Slice
- Spring Context をどこまで起動するかという考え方
- `@WebMvcTest`
- MockMvc
- Controller Test
- Service を mock にする理由
- request body
- response body
- HTTP status
- HTTP header
- JSON
- JSONPath
- JSONassert
- Bean Validation
- Validation Error
- Controller Advice
- Exception Handler
- 400 Bad Request
- 404 Not Found
- 409 Conflict
- 502 Bad Gateway
- API error response

### 主なテスト対象

- `OrderController`
- `POST /api/orders`
- `GET /api/orders/{id}`
- `PATCH /api/orders/{id}/confirm`
- `PATCH /api/orders/{id}/ship`
- `PATCH /api/orders/{id}/cancel`
- Validation Error
- 業務例外から HTTP error response への変換

### 学習上のポイント

Web 層のテストでは、アプリケーション全体を起動する必要がないことを理解する。  
Controller が担当する HTTP 入出力や Validation と、Service が担当する業務ロジックの境界を意識する。

## 12. Unit 06 SQL・Repository・DB Integration Test

### 学習目的

明示的な SQL を持つ Repository を実 PostgreSQL に対してテストし、DB を含む Integration Test の基礎を理解する。  
テストデータ、Transaction、Rollback、Test Isolation など、DB Test で重要となる共通概念を学ぶ。

### 主な学習内容

- Repository Test
- DB Integration Test
- Spring JDBC
- `JdbcTemplate`
- `NamedParameterJdbcTemplate`
- Repository interface と JDBC 実装
- 明示的な SQL
- Row Mapping
- SQL parameter
- INSERT
- UPDATE
- SELECT
- JOIN
- 複数条件 WHERE
- ORDER BY
- Test Data
- Database Fixture
- Seed Data
- Setup
- Cleanup
- Transaction
- Rollback
- DB Test Isolation
- DB constraint
- DB 固有挙動
- `@JdbcTest`
- JDBC 用 Test Slice
- test datasource の考え方
- H2 などの in-memory DB の位置づけ
- 実 PostgreSQL を使う意味
- Testcontainers
- container lifecycle
- テスト用 PostgreSQL
- DBUnit の位置づけ
- dataset
- expected dataset
- Testcontainers と DBUnit の役割の違い

### 主なテスト対象

- `JdbcProductRepository`
- `JdbcOrderRepository`
- 商品取得
- 在庫更新
- Order / OrderItem の登録
- Order と OrderItem の取得
- 注文一覧の条件検索
- status / createdAt を利用した検索・並び順
- DB constraint

### 学習上のポイント

Repository Test では、Repository 自体を mock にするのではなく、本物の SQL と DB の動作を確認する必要があることを理解する。  
また、テスト用 DB を共有し続けるのではなく、Testcontainers によって再現可能で独立した PostgreSQL 環境を用意する意味を理解する。

## 13. Unit 07 Spring Boot Integration Test

### 学習目的

Spring Context、Service、Repository、PostgreSQL を組み合わせた Integration Test を理解する。  
Unit Test と Integration Test を同一機能で比較し、テスト対象の境界が広がることで何が確認でき、何が重くなるのかを理解する。

### 主な学習内容

- Integration Test
- `@SpringBootTest`
- Spring Context
- Plain Unit Test との違い
- Slice Test との違い
- real dependency
- mock dependency
- 依存先を本物にする範囲の考え方
- Service + Repository + PostgreSQL
- Transaction Boundary
- DB 初期状態
- DB 最終状態
- Test Fixture
- Test Isolation
- rollback の確認
- 複数 DB 更新を含む処理の検証
- Unit Test と Integration Test のコスト差
- Test Execution Time

### 主なテスト対象

- 注文作成
- Order / OrderItem の保存
- Product 在庫減算
- 注文キャンセル
- OrderStatus の更新
- Product 在庫復元
- DB 処理途中の失敗と rollback

### 学習上のポイント

Unit 03 では Repository を Mockito で置き換えた `OrderService` を、この Unit では実 Repository と PostgreSQL を使って確認する。  
同じ SUT を異なる境界でテストすることで、Unit Test と Integration Test の役割の違いを理解する。

## 14. Unit 08 外部 HTTP API のテスト

### 学習目的

外部 HTTP API に依存する処理を、自動テストから実サービスへ接続せずに検証する方法を理解する。  
Mockito で Java オブジェクトを置き換える方法と、WireMock で HTTP 境界そのものを置き換える方法の違いを学ぶ。

### 主な学習内容

- 外部 API 依存
- network dependency
- 実外部 API をテストから直接呼ぶ問題
- HTTP-level Test Double
- WireMock
- WireMock Server
- HTTP stub
- request matching
- response body
- HTTP status
- request verification
- test ごとの reset
- 正常 response
- 500 response
- 不正 JSON
- 不完全 response
- Mockito と WireMock の違い
- Java object boundary
- HTTP boundary
- 外部依存の isolation

### 主なテスト対象

- `ShippingFeeClient`
- `HttpShippingFeeClient`
- Shipping Fee API
- Shipping Fee API 正常時
- Shipping Fee API 障害時
- 不正 response 受信時

### 学習上のポイント

「mock」という言葉を単一の方法として捉えず、依存関係の境界によって適した Test Double の置き方が変わることを理解する。

## 15. Unit 09 REST API 全体のテスト

### 学習目的

起動した Spring Boot アプリケーションへ実 HTTP request を送信し、HTTP 入口から DB・外部 API 境界までを含む広い Integration Test / API Test を理解する。

### 主な学習内容

- API Test
- 実 HTTP 通信を用いたテスト
- Spring Boot のテスト用 Web Server
- random port
- REST Assured
- request
- response
- HTTP status
- header
- JSON body
- Controller
- Service
- Repository
- PostgreSQL
- WireMock
- DB 初期状態
- DB 最終状態
- 外部 API 正常時
- 外部 API 異常時
- Unit Test / Slice Test / Integration Test / API Test の比較
- System Test との違い
- E2E Test との違い
- Acceptance Test との位置関係

### 主なテスト対象

- `POST /api/orders`
- `GET /api/orders/{id}`
- `GET /api/orders`
- `PATCH /api/orders/{id}/confirm`
- `PATCH /api/orders/{id}/ship`
- `PATCH /api/orders/{id}/cancel`
- Validation Error
- Business Error
- DB 最終状態
- Shipping Fee API 正常・異常ケース

### 学習上のポイント

テスト対象の範囲が広がるほど実際のアプリケーションに近い確認ができる一方で、実行時間、準備する依存環境、失敗原因の切り分けなどのコストも大きくなることを理解する。

## 16. Unit 10 テスト実行管理と Coverage

### 学習目的

ここまで作成した個々のテストを、プロジェクト全体の Test Suite として整理・実行・評価する方法を理解する。  
Maven の Test Lifecycle と Coverage を学び、テストコードを書くだけでなく、テスト群をどのように管理するかを理解する。

### 主な学習内容

- Test Suite
- Unit Test / Integration Test の分類
- テストクラスの naming
- package 構成
- `@Tag`
- Filtering
- include / exclude
- 一部テストの実行
- Test Discovery の振り返り
- Parallel Execution の概念
- Fail Fast
- Retry
- Flaky Test と Retry の関係
- Maven Build Lifecycle
- `test`
- `integration-test`
- `verify`
- Surefire
- Failsafe
- Unit Test Report
- Integration Test Report
- Code Coverage
- Line Coverage
- Branch Coverage
- Method Coverage
- Class Coverage
- Coverage Report
- JaCoCo
- HTML Report
- 未実行行の確認
- 未実行 branch の確認
- Coverage Threshold
- Coverage Check
- Coverage とテスト品質の違い
- Assertion が弱いテストと Coverage
- Mutation Testing の基本概念
- PIT の位置づけ
- Test Strategy
- Test Plan
- Test Pyramid の振り返り
- Unit Test / Integration Test の実行コストの違い

### 主なテスト対象

Unit 01～09 までに作成したテストスイート全体を対象とする。

### 学習上のポイント

Coverage の数値を上げること自体を目的にしない。  
Line Coverage や Branch Coverage を、テストされていないコードや条件分岐を見つけるための補助情報として利用する。  
Coverage が高くても、Assertion が弱い、重要な振る舞いを確認していないなど、テスト品質が十分とは限らないことを理解する。

## 17. Unit 11 CI による自動テスト

### 学習目的

ローカルで手動実行していた自動テストを、GitHub Actions によって継続的に自動実行する流れを理解する。  
CI 上で build、Unit Test、Integration Test、Coverage Check を実行し、失敗時に結果を確認できる状態を作る。

### 主な学習内容

- CI
- Continuous Integration
- CI を利用する目的
- Shift Left
- local test と CI test
- reproducibility
- push / Pull Request と自動テスト
- CI Pipeline
- GitHub Actions
- Workflow
- Job
- Step
- checkout
- JDK setup
- Maven build
- Unit Test
- Integration Test
- Testcontainers を含む CI 実行
- Coverage
- Coverage Check
- Test Report
- Coverage Report
- Artifact
- CI failure
- CI log の読み方
- Matrix Build
- matrix strategy
- JDK version などの実行環境の組み合わせ
- Test Matrix と CI Matrix の違い

### 主な対象

`java-automated-testing-study` プロジェクト全体を対象とし、Unit 01～10 までに構築したテスト環境を CI 上で再現する。

### 学習上のポイント

自動テストの価値は、ローカルでコマンドを実行できることだけではなく、変更のたびに同じテストを継続的に再実行できることにある。  
CI の成功・失敗を通じて、テストコード、Build Tool、DB Test、Coverage が一つの開発フローとしてつながることを理解する。

CD / Deployment は今回の対象外とする。

## 18. Unit を横断して理解する主要概念

一部の概念は、1 つの Unit だけで完結せず、複数 Unit を通して理解を深める。

| 概念                                      | 主に扱う Unit  |
| ----------------------------------------- | -------------- |
| Unit Test                                 | 01～04         |
| Integration Test                          | 01、06、07、09 |
| E2E Test                                  | 01、09         |
| Regression Test                           | 01、10、11     |
| Test Pyramid                              | 01、10         |
| Test Scenario / Test Case                 | 01、02         |
| Test Oracle                               | 02             |
| Boundary Value / Equivalence Partitioning | 02             |
| Decision Table / Test Matrix              | 02             |
| Test Fixture                              | 02、06、07、09 |
| Test Double                               | 03、08         |
| Mock / Stub / Spy / Fake                  | 03             |
| Interaction Testing                       | 03             |
| Testability                               | 04             |
| Test Isolation                            | 04、06、07、09 |
| Deterministic / Repeatable                | 04             |
| Flaky Test / Brittle Test                 | 04、10         |
| Spring Context / Test Slice               | 05～07         |
| DB Test                                   | 06、07、09     |
| Testcontainers                            | 06、07、09、11 |
| External API Test                         | 08、09         |
| WireMock                                  | 08、09         |
| REST API Test                             | 09             |
| Test Suite                                | 10             |
| Coverage                                  | 10、11         |
| Surefire / Failsafe                       | 10、11         |
| CI                                        | 11             |
| TDD / BDD                                 | 01～02 の補足  |
| Mutation Testing                          | 10 の補足      |
| Shift Left                                | 11             |

## 19. メインアプリと学習内容の対応

学習では、注文管理 REST API の同じ機能を、Unit ごとに異なる境界から確認する。

| Unit | 主なアプリ要素                                                       |
| ---- | -------------------------------------------------------------------- |
| 01   | `OrderPriceCalculator`、Domain の単純な判定                          |
| 02   | 数量境界、割引境界、状態遷移                                         |
| 03   | `OrderService` と Repository / Client                                |
| 04   | `Clock`、Dependency Injection、Testability                           |
| 05   | Controller、Validation、Error Handling                               |
| 06   | Spring JDBC Repository、SQL、PostgreSQL                              |
| 07   | Service + Repository + PostgreSQL                                    |
| 08   | `ShippingFeeClient`                                                  |
| 09   | HTTP → Controller → Service → Repository → PostgreSQL / External API |
| 10   | Unit 01～09 のテストスイート全体                                     |
| 11   | Maven / Test Suite / Coverage / CI を含むプロジェクト全体            |

この構成により、同じ Production Code に対して、テスト対象の境界や使用する技術がどのように変化するかを比較しながら学習する。

## 20. 学習範囲のまとめ

このカリキュラムでは、Java / Spring Boot の Web バックエンドにおける自動テストを、次の流れで体系的に学習する。

```text
テストの全体像
↓
JUnit
↓
テストケース設計
↓
AssertJ
↓
Test Double / Mockito
↓
Testability / Test Quality
↓
Spring Web Test
↓
SQL / Repository / PostgreSQL
↓
Spring Boot Integration Test
↓
External HTTP API / WireMock
↓
REST API / REST Assured
↓
Maven Test Management / JaCoCo
↓
GitHub Actions / CI
```

個別のツールの操作だけに偏らず、テスト対象の境界、テストケース設計、依存関係の扱い、テスト自体の品質、DB や HTTP を含む Integration Test、Coverage、CI までを一連の知識としてつなげる。  
この学習を通して、特定のライブラリだけに依存しない、自動テストに関する汎用的な考え方と、Java / Spring Boot の実務で利用される代表的な技術の双方を身につけることを目指す。
