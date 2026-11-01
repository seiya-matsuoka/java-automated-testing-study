# 07. Spring Boot Integration Test

## この Unit の目的

この Unit では、Spring Context、Service、JDBC Repository、PostgreSQL を組み合わせ、注文の業務処理を広い境界で検証する方法を学ぶ。  
注文作成とキャンセルを題材に、戻り値だけでなく、注文・明細・在庫が一貫した状態で保存されることを確認する。

Unit 03 では、`OrderService` が依存する Repository を Test Double に置き換えた。  
Unit 06 では、その Repository 自身の SQL と PostgreSQL の動作を確認した。
今回は同じ Service と実 Repository をつなぎ、Spring の DI と Transaction 管理も含めて確認する。

中心となるのは、**誰が Transaction を開始し、どの更新を囲み、失敗後の何を根拠に Rollback を確認するか**である。  
Test の後始末で DB が空になったことと、業務処理の途中失敗で更新が取り消されたことを区別する。

そのため、今回は Test 自身を Transaction で囲まず、Commit 済みの Fixture から Service を呼び出す。  
正常時は Commit 後、異常時は業務 Rollback 後、いずれも Cleanup 前の DB 状態を直接観測する。

## 学習内容

### 同じ注文処理を異なる Test Boundary で確認する

Test Boundary は、その Test で本物として動かす範囲と、制御可能な依存へ置き換える境目である。  
「注文作成を Test する」という目的が同じでも、境界によって確認できる不具合は異なる。

| Unit | 中心となる対象                        | 本物として通す範囲                                            | 主な確認内容                                                       |
| ---- | ------------------------------------- | ------------------------------------------------------------- | ------------------------------------------------------------------ |
| 03   | `OrderService`                        | Service・Domain。Repository は Mock / Fake                    | 業務フロー、保存しようとする値、依存先への引数・呼び出し           |
| 05   | `OrderController` を中心とする Web 層 | Spring MVC・Controller・Validation・例外変換。Service は Mock | HTTP の入口と出口、Command への変換、API Error Contract            |
| 06   | JDBC Repository                       | Spring JDBC・SQL・PostgreSQL                                  | 保存・検索・更新、Row Mapping、DB 制約                             |
| 07   | `OrderService`                        | Spring Context・実 Service・実 Repository・PostgreSQL         | 層をまたぐ業務処理、Commit 後の保存結果、途中失敗時の業務 Rollback |

Unit 03 の Mock が期待した値を返しても、実際の SQL が正しく保存できるとは限らない。  
Unit 06 の個々の INSERT / UPDATE が正しくても、それらを 1 つの業務 Transaction にまとめているとは限らない。
Unit 07 はこの接続部分を確認する。

Unit 05 は別の責務を確認している。  
今回の Test が成功しても Request JSON の Binding や HTTP Status が正しいとは結論付けない。
それぞれの Test は、他の Test を置き換えるためではなく、異なる責務を補い合うために存在する。

### Plain Unit Test、Slice Test、Integration Test

Plain Unit Test は、Spring Context を起動せず、必要な Java Object を直接組み立てて実行する Test である。  
Unit 03 の `OrderServiceMockitoTest` では Mockito が依存を注入するが、Spring が Service の Transaction を管理しているわけではない。

Slice Test は、Web や JDBC など、対象の層に必要な Spring の構成へ範囲を限定する。  
Unit 05 の `@WebMvcTest` と Unit 06 の `@JdbcTest` は、どちらも Spring Context を使用する。
ただし、読み込む構成と本物にする依存が異なる。

Integration Test は、複数の実装や基盤を組み合わせ、その連携を確認する Test である。  
Unit 06 も実 SQL と DB をつなぐ Integration Test であるため、「Slice Test と Integration Test は必ず別分類になる」とは考えない。

```text
Unit 03: Test → OrderService → Repository の Test Double
Unit 05: Test → Spring MVC → OrderController → OrderService の Mock
Unit 06: Test → JDBC Repository → Spring JDBC → PostgreSQL
Unit 07: Test → Service の Spring Proxy → OrderService → JDBC Repository → PostgreSQL
```

Annotation 名だけで Test の種類や保証範囲を決めず、実際の入口・依存・観測対象から判断する。

### Spring Context、Bean、Dependency Injection

Spring Context は、アプリケーションで使う Object と設定を管理する入れ物である。  
Spring が管理する Object を Bean と呼び、依存する Bean を constructor などへ渡す仕組みを Dependency Injection（DI）と呼ぶ。

今回の起点は `OrderManagementApplication` の `@SpringBootApplication` である。  
Component Scan が `@Service` や `@Repository` を検出し、Auto Configuration が DataSource、JDBC Template、Transaction 管理などに必要な構成を用意する。

`OrderService` は、constructor で `ProductRepository`、`OrderRepository`、`ShippingFeeClient`、`Clock` を受け取る。  
今回の Test はこの DI を実際に使い、Repository には JDBC 実装、送料には Mock、時刻には固定 Clock を渡す。

Spring Context を起動する理由は、単に Object の生成を省略するためではない。  
実アプリの Bean の組み合わせと、Service の呼び出しに適用される Transaction 管理も検証境界へ含めるためである。

### @SpringBootTest の役割

`@SpringBootTest` は、Spring Boot の起動方法を使って Test 用の Application Context を構成する。  
今回の 3 クラスは、次の設定を共通して使用する。

```java
@SpringBootTest(
    classes = OrderManagementApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "spring.sql.init.mode=never")
```

| 指定                    | 今回の意味                                                       |
| ----------------------- | ---------------------------------------------------------------- |
| `classes`               | 実アプリの設定・Component Scan の起点を明示する                  |
| `webEnvironment = NONE` | Mock の Web 環境も実 Web Server も用意せず、Service を入口にする |
| `properties`            | Test 用に Boot の通常 SQL 初期化を止める                         |

`@SpringBootTest` を付けただけで、Test 自身の Transaction が自動で開始されるわけではない。  
Test に `@Transactional` を付けるか、Service の `@Transactional` を通るかは、別の設定・呼び出しとして考える。

また、広い Context を読み込むことと、すべての業務機能を実行して確認することは異なる。  
今回の入口は Service の公開 Method であり、HTTP Request や Controller の Response 生成は通さない。

### WebEnvironment と HTTP の境界

`webEnvironment` は Web 環境の構成を選ぶ指定である。  
代表的な違いを整理すると、次のようになる。

| 指定          | Web 環境         | 実 Web Server        | 今回との関係                            |
| ------------- | ---------------- | -------------------- | --------------------------------------- |
| `NONE`        | なし             | 起動しない           | Unit 07 で使用する                      |
| `MOCK`        | Mock の Web 環境 | 起動しない           | MVC を含める Test で選択肢となる        |
| `RANDOM_PORT` | 実 Web 環境      | 動的ポートで起動する | 実 HTTP の入口を扱う Unit 09 につながる |

Service を検証したい今回の目的には、Web Server の起動や HTTP 経由の呼び出しは必要ない。  
一方、`NONE` を選んでも Repository や PostgreSQL まで Mock になるわけではない。
Web の構成と DB の構成は別々に決める。

### TestConfiguration と実アプリへの追加設定

`PostgresServiceIntegrationTestConfiguration` は、テスト専用 PostgreSQL と固定 Clock を提供する `@TestConfiguration` である。  
各 Test の `@Import` で明示的に追加し、実アプリの構成を置き換える起点にはしない。

Unit 06 の設定は、JDBC Slice に必要な 2 つの Repository を明示的に import する責務も持っていた。  
今回は実アプリの Component Scan を使うため、その Slice 用設定をそのまま流用せず、Container の管理方法を踏襲した別設定とする。

Unit 06 の `@AutoConfigureTestDatabase(replace = NONE)` は、JDBC Slice のテスト用接続差し替えに関する指定である。  
今回はその Slice を使わず、実アプリの Auto Configuration と `@ServiceConnection` で専用 DataSource を構成する。

`proxyBeanMethods = false` は、この設定クラスの `@Bean` Method 呼び出しを管理する Proxy に関する指定である。  
Service に対する Transaction Proxy を無効化する指定ではない。
設定クラスの Proxy と、業務 Bean へ適用する AOP Proxy を区別する。

### real dependency と Test Double を選ぶ理由

real dependency は、その境界の実装を実際に動かす依存である。  
今回、Repository を本物にするのは、Service が渡した値を SQL が保存し、複数の更新が同じ業務 Transaction に参加することを確認したいからである。

一方、送料取得は Java の `ShippingFeeClient` 境界で制御する。  
外部 HTTP の通信形式や Response の不正を同時に持ち込まず、Service と DB の連携へ焦点を合わせる。

固定 Clock は、JDK の実 `Clock` を使いながら現在時刻だけを決定的にする。  
実依存か Mock かの二択ではなく、「どの性質を本物にし、どの変動を制御するか」を考える。

`OrderPriceCalculator` と Domain は実物を使用する。  
現在の `OrderService` は stateless な計算クラスを内部で生成しており、今回その計算クラスを新しい Spring Bean や Mock に変更しているわけではない。

### @MockitoBean と外部 HTTP 依存の分離

`@MockitoBean` は、Spring Context 内で依存先として注入される Bean を Mockito Mock に置き換える。  
Unit 03 の通常の `@Mock` と異なり、実 Service の DI に組み込まれる。

```java
@MockitoBean(enforceOverride = true)
private ShippingFeeClient shippingFeeClient;
```

`enforceOverride = true` は、置換対象の Bean が既存構成にあることを要求する。  
Bean の登録漏れを、Test が新しい Mock を追加することで隠さないための指定である。

正常系では郵便番号・小計・総数量に対する送料を Stub し、障害系では例外を送出させる。  
これは Java Object の応答を制御する Test Double であり、HTTP 500 や不正 JSON を実際に受信したことを意味しない。

今回の Context に RestClient 関連の設定が存在しても、Service から呼ぶ送料 Bean は Mock である。  
Bean が生成されることと、実際に HTTP Request を送ることを分けて読む。

### 固定 Clock と @Primary

時刻を直接取得するコードは、実行日時や処理速度によって期待値が変わりやすい。  
`Clock.fixed(...)` を注入すると、作成・キャンセルの日時を待ち時間や許容誤差なしで確認できる。

Production の `ClockConfig` は UTC の system clock を定義する。  
Test 設定では別名の固定 Clock Bean を追加し、`@Primary` によって Service への注入候補として優先する。

```java
@Bean
@Primary
public Clock unit07Clock() {
  return Clock.fixed(OrderServiceIntegrationTestFixture.NOW, ZoneOffset.UTC);
}
```

Production の Clock Bean を削除したり、同じ Bean 名で上書きしたりしているわけではない。  
また、DB の現在時刻を変更する設定でもない。
今回の日時は Service の `Instant.now(clock)` から生成する。

### 注文作成と複数テーブルの保存

注文作成では、1 回の Service 呼び出しが複数の DB 更新を伴う。  
処理順と保存先を対応させる。

```text
商品存在・販売状態・在庫・重複を検証
  ↓
ShippingFeeClient から送料を取得
  ↓
Order を生成
  ↓
orders にヘッダを INSERT
  ↓
order_items に全明細を INSERT
  ↓
各 products の在庫を順に減算
  ↓
保存済み注文を再取得して返す
```

例えば、Keyboard 3,000 円を 2 個、Mouse 2,500 円を 2 個注文する場合、期待値は次のとおりである。

| 項目       | 期待値                         |
| ---------- | ------------------------------ |
| 商品小計   | 6,000 + 5,000 = 11,000 円      |
| 割引       | 1,100 円                       |
| 送料       | Stub した 800 円               |
| 合計       | 10,700 円                      |
| 注文・明細 | ヘッダ 1 件・明細 2 件         |
| 在庫       | Keyboard 10 → 8、Mouse 20 → 18 |

期待値は仕様から明示し、SUT の計算結果を呼び直して作らない。  
各明細の親 ID は実際に採番された注文 ID と対応させ、ID が特定の連番になることは期待しない。

### 注文時点の商品情報と Snapshot

注文明細の商品名・単価は、注文時点の情報を保持する Snapshot である。  
今回の Service は実 DB から取得した Product を使って OrderItem を生成し、その値を実 Repository が保存する。

正常系では、親注文との関連、商品 ID、商品名、単価、数量、明細金額を直接 SELECT して確認する。  
Unit 03 の「Repository へ渡した値」と、今回の「DB に保存された値」の違いを比較する。

ただし Unit 07 の Test は、保存後の商品マスタ変更を操作していない。  
マスタ変更後にも Snapshot を維持する SQL の確認は Unit 06 の該当ケースと対応させ、今回実行した範囲以上の操作を保証したと説明しない。

### 状態更新と在庫復元

注文確定は `CREATED → CONFIRMED`、発送は `CONFIRMED → SHIPPED` とする。  
これらの操作では、注文作成時の在庫減算をもう一度行わない。

キャンセルは `CREATED` または `CONFIRMED` からだけ許可される。  
注文状態とキャンセル日時を更新した後、全明細の数量をそれぞれの在庫へ戻す。

```text
状態・キャンセル日時の条件付き UPDATE
  ↓
Keyboard の在庫復元
  ↓
Mouse の在庫復元
  ↓
更新後の注文を再取得
```

状態と日時は、現在の Repository では 1 つの UPDATE で保存する。  
その後の複数商品の復元も含めて 1 業務 Transaction とするため、後半の失敗で状態・日時も元に戻る必要がある。

### Transaction Boundary と原子性

Transaction は、複数の DB 操作をひとまとまりにして Commit または Rollback する単位である。  
その開始から終了まで、どの操作を含めるかという境界を Transaction Boundary と呼ぶ。

注文ヘッダだけが残る、明細はあるのに在庫が減っていない、キャンセル済みなのに一部の在庫しか戻らない、といった中間状態を成功結果として残してはならない。  
一連の更新をすべて確定するか、失敗した処理の更新をすべて取り消す性質が原子性である。

個々の Repository Method に成功する SQL があるだけでは、業務単位の原子性は保証できない。  
複数の Repository 呼び出しを囲む Service の Transaction と、それへ参加する JDBC の連携を確認する必要がある。

### Service の Proxy と @Transactional

現在の `OrderService` の更新用公開 Method には `@Transactional` が付いている。  
Spring は、その Bean への呼び出しを Proxy 経由で受け、Method 本体の実行前後に Transaction 管理を適用する。

```text
Test から Spring 管理の Service を呼ぶ
  ↓
Proxy が業務 Transaction を開始
  ↓
実 Service → 実 Repository → 同じ DataSource の DB 更新
  ↓
正常終了なら Commit、対象の例外なら Rollback
  ↓
Test へ戻る
```

通常の `new OrderService(...)` で作った Object は、Spring の Transaction Proxy を通らない。  
Unit 03 が Service の業務フローを確認できても、今回の業務 Transaction を保証していない理由の 1 つである。

`AopUtils.isAopProxy(orderService)` は Proxy の存在を確認するが、それだけで Rollback の正しさを証明するものではない。  
今回の Test は、失敗直前の Transaction 状態と実更新、例外伝播後の DB 状態も組み合わせて確認する。

### Test Transaction と業務 Transaction

Test Transaction は、Spring Test が Test のために開始・終了する Transaction である。  
Unit 06 の `@JdbcTest` では、その中へ Fixture と Repository の操作を入れ、Test 終了時の Rollback を Cleanup に使った。

業務 Transaction は、今回なら注文作成やキャンセルを Service が公開する業務単位として囲む Transaction である。  
本番で必要なのは、利用者が Test の Rollback を用意しなくても業務処理が一貫性を保つことである。

| 観点                    | Unit 06                                   | Unit 07                                    |
| ----------------------- | ----------------------------------------- | ------------------------------------------ |
| Test 自身の Transaction | `@JdbcTest` が管理する                    | 開始しない                                 |
| Fixture の投入          | Test Transaction に参加する               | 独立した Transaction で先に Commit する    |
| 主な検証対象            | Repository と SQL、Test Rollback 後の状態 | Service の Commit / 業務 Rollback 後の状態 |
| データの後始末          | Test Transaction の Rollback              | Assertion 後の明示的 DELETE                |

Service の `@Transactional` は、既存の Transaction があれば参加するのが既定の振る舞いである。  
Test 側が全体を囲むと、Service の設定がなくても Repository 操作が Test Transaction 内で実行され、最後の Test Rollback で消える構成になり得る。

したがって、**Test の最後に DB が空だったことだけでは、Service が正しい Transaction Boundary を持つとは判断できない。**  
今回は Test に `@Transactional` を付けず、Service 呼び出しを Test 側の `TransactionTemplate` で囲むこともしていない。

### 正常 Commit、事前拒否、途中失敗を区別する

異常系で DB が変わらない理由は 1 種類ではない。  
書き込み前に拒否した場合と、書き込みを実行した後に Rollback した場合を分ける。

| ケース                                   | DB 書き込みへ到達するか | 確認すること                             |
| ---------------------------------------- | ----------------------- | ---------------------------------------- |
| 正常な注文・状態更新                     | 到達する                | Commit 後の保存内容                      |
| 商品不存在・販売停止・事前在庫不足・重複 | 到達しない              | 例外、DB 不変、送料 Client 未呼び出し    |
| 送料取得失敗                             | 書き込みへ到達しない    | 業務例外への変換、cause、DB 不変         |
| 後続商品の更新失敗                       | 先行書き込みへ到達する  | 途中状態の存在と、業務 Rollback 後の復元 |

現在の送料照会は、Service の業務 Transaction 内にあるが DB 書き込みより前に実行する。  
「DB 書き込み前」と「Transaction の外」は同じ意味ではない。

今回注入する `DataAccessResourceFailureException` と、更新件数検査からの `IllegalStateException` は RuntimeException の一種である。  
現在の `@Transactional` の既定の Rollback 対象となる。
checked exception も常に同じ扱いになるとは限らないため、例外の型と設定も Transaction の契約として読む。

### DB 初期状態と DB 最終状態

DB 初期状態は、Test の前提として用意した行と値である。  
DB 最終状態は、対象の業務処理が終了した後、Cleanup を始める前の行と値である。

今回の `databaseState(jdbcTemplate)` は、3 テーブルの全列を ID 順で読み、`DatabaseState` にまとめる。  
これは業務 Domain の復元処理ではなく、Test の観測値である。
`orders` と `order_items` を別々に読むため、INNER JOIN では見えない孤立したヘッダも見落とさない。

`DatabaseState` の比較が対象にするのは、3 テーブルの行データである。  
sequence の現在値や DB の全設定まで処理前へ戻ったことを比較しているわけではない。

正常系は、戻り値と直接 SELECT の両方を見る。  
事前拒否・Rollback のケースは処理前の観測値と比較し、注文・明細・在庫だけでなく、既存行の金額・日時や無関係な商品の変化も検出する。

### どの Transaction から観測するか

同じ JdbcTemplate でも、呼び出す位置によって観測の意味が変わる。  
Spring JDBC は、現在のスレッドに対応する Transaction の Connection を利用する。

| 観測位置                                    | `TestTransaction.isActive()` | `isActualTransactionActive()` | 読んでいる状態                              |
| ------------------------------------------- | ---------------------------- | ----------------------------- | ------------------------------------------- |
| Test 本体で Service を呼ぶ前                | `false`                      | `false`                       | Commit 済み Fixture                         |
| Service が呼んだ Spy の失敗注入 Callback 内 | `false`                      | `true`                        | 業務 Transaction の未 Commit 更新を含む状態 |
| Service の例外が Test へ戻った後            | `false`                      | `false`                       | 業務 Rollback 後の状態                      |

後者の API は `TransactionSynchronizationManager.isActualTransactionActive()` である。  
単独ではその Transaction の業務上の意味を識別しないため、「Test Transaction がない」「実 Service の Proxy を通った」「その呼び出しの途中で観測した」という文脈と合わせて解釈する。

失敗前の SELECT は、別の DB や別の無関係な Connection から未 Commit 行を読もうとしているわけではない。  
同じ DataSource と Spring JDBC の Transaction 参加を利用している。

### Repository Spy による途中失敗の制御

`@MockitoSpyBean` は、既存の実 Bean を Spy で包み、一部の呼び出しだけを制御する。  
今回の正常系クラスには追加せず、`OrderServiceTransactionIntegrationTest` の商品 Repository にだけ使用する。

```java
@MockitoSpyBean private JdbcProductRepository productRepository;
```

商品の事前取得や 1 商品目の更新は実物のままにし、2 商品目の更新でだけ例外または 0 件更新を返す。  
注文 Repository、JDBC、PostgreSQL、Service の Transaction 管理は置き換えない。

Spy を設定するときに実 Method を呼ぶと、その時点で DB を更新してしまう可能性がある。  
今回の `doAnswer(...).when(productRepository)...` は、設定時に実更新を実行せず、SUT が対象の Method へ到達した時点で観測と失敗注入を行う。

注入した例外が DB 操作に関係する型でも、実際に DB Server を停止させたわけではない。  
保証するのは、この制御された途中失敗に対し、先行する実 DB 更新が業務 Transaction で取り消されることである。

### 注文作成の Rollback で確認する途中状態

両商品を注文可能な初期状態にし、Mouse の減算で例外を送出する。  
その時点では、ヘッダ・2 明細と Keyboard の減算が実 DB に実行済みでなければならない。

| 観測対象           | 呼び出し前 | 失敗注入の直前 | 例外後・Cleanup 前 |
| ------------------ | ---------- | -------------- | ------------------ |
| `orders` 件数      | 0          | 1              | 0                  |
| `order_items` 件数 | 0          | 2              | 0                  |
| Keyboard 在庫      | 10         | 8              | 10                 |
| Mouse 在庫         | 20         | 20             | 20                 |
| 業務 Transaction   | なし       | 有効           | 終了               |

初期状態と最終状態が等しいだけでは、先行更新へ到達しなかった可能性が残る。  
途中状態を保存することで、「更新した後で戻った」と確認できる。

例外は注入した instance そのものを `isSameAs(failure)` で確認する。  
観測 SQL の失敗など、別の例外で処理が止まったことを期待した失敗と取り違えない。

### キャンセルの Rollback で確認する途中状態

SQL Fixture の `CONFIRMED` 注文を対象にし、Mouse の復元だけを 0 件更新にする。  
Service 自身の更新件数検査が `IllegalStateException` を送出し、業務 Rollback へ進む。

| 観測対象                 | 呼び出し前  | 失敗注入の直前 | 例外後・Cleanup 前    |
| ------------------------ | ----------- | -------------- | --------------------- |
| 対象注文の状態           | `CONFIRMED` | `CANCELLED`    | `CONFIRMED`           |
| 対象注文のキャンセル日時 | `null`      | 固定時刻 `NOW` | `null`                |
| Keyboard 在庫            | 8           | 10             | 8                     |
| Mouse 在庫               | 18          | 18             | 18                    |
| 全注文・全明細           | 4 件・8 件  | 明細は保持     | 元の 4 件・8 件を保持 |

このケースでは Fixture が事前に Commit 済みなので、Rollback 後に注文・明細を空にしてはならない。  
取り消すのは今回のキャンセル処理の変更であり、正当な既存データは残す。

### 失敗直前の記録と失敗後の Assertion

失敗注入の Callback は、`observeBeforeFailure()` の結果を Test ローカルの List へ保存する。  
Callback 内では Assertion を行わず、Service から期待する例外が返ったことを確認した後で観測値を検証する。

`FailureObservation` は、Transaction の有効状態、3 テーブルの観測値、両商品の在庫をまとめる。  
static な共有状態や前の Test の記録は使わない。

`verify(productRepository)...` は、狙った境界が呼ばれたことを補助的に確認する。  
呼び出しがあったこと自体は SQL の成功を意味しないため、先行更新の根拠には直接 SELECT した途中状態を使う。

### Test Fixture と通常用データの分離

Fixture は、特定の Test が必要とする初期条件を、意味が分かる形で用意したものである。  
今回の SQL と Java の Fixture は役割を分ける。

| 成果物                    | 担当                                           |
| ------------------------- | ---------------------------------------------- |
| 本番の `schema.sql`       | テーブル・型・制約・Index の構造               |
| Unit 07 の `products.sql` | 商品名・価格・在庫・販売状態                   |
| Unit 07 の `orders.sql`   | 状態別の既存注文・明細、減算済み在庫           |
| Java の Fixture           | 固定 ID・時刻・送料、Command の生成、DB の観測 |
| Unit 07 の `cleanup.sql`  | 次の Test へ行データを持ち越さないための削除   |

キャンセルの Arrange に Service の注文作成を使うと、キャンセルを確認するために注文作成の正しさまで前提にする。  
今回は SQL で既存注文を直接準備し、それぞれの業務処理を独立して読めるようにする。

状態別の 4 注文は、各 Test が対象を選ぶための代表行である。  
全期間の仕入れ・注文履歴を再現するデータではなく、同じ Fixture 上の全注文を続けてキャンセルし、在庫履歴を検算する教材でもない。

### 今回の初期データと固定時刻

`products.sql` が投入する商品は次の 4 件である。

| ID / 定数                        | 商品           | 単価     | 在庫 | 販売状態 |
| -------------------------------- | -------------- | -------- | ---- | -------- |
| 7001 / `KEYBOARD_ID`             | Keyboard       | 3,000 円 | 10   | 販売中   |
| 7002 / `MOUSE_ID`                | Mouse          | 2,500 円 | 20   | 販売中   |
| 7003 / `INACTIVE_PRODUCT_ID`     | Archived Hub   | 1,000 円 | 5    | 販売停止 |
| 7004 / `OUT_OF_STOCK_PRODUCT_ID` | Sold Out Cable | 1,000 円 | 0    | 販売中   |

`orders.sql` を追加すると、次の 4 注文と各 2 明細が入り、Keyboard / Mouse の現在在庫は 8 / 18 となる。  
Hub と Cable の条件は変わらない。

| ID / 定数                    | 状態        | キャンセル日時        |
| ---------------------------- | ----------- | --------------------- |
| 70001 / `CREATED_ORDER_ID`   | `CREATED`   | `null`                |
| 70002 / `CONFIRMED_ORDER_ID` | `CONFIRMED` | `null`                |
| 70003 / `SHIPPED_ORDER_ID`   | `SHIPPED`   | `null`                |
| 70004 / `CANCELLED_ORDER_ID` | `CANCELLED` | `SEEDED_CANCELLED_AT` |

固定時刻は実行日時と無関係に、次の値とする。

| 定数                  | UTC の時刻                    | 使い方                                           |
| --------------------- | ----------------------------- | ------------------------------------------------ |
| `SEEDED_CREATED_AT`   | `2026-10-01T00:00:00.123456Z` | SQL の既存注文の作成日時                         |
| `SEEDED_CANCELLED_AT` | `2026-10-05T00:00:00.654321Z` | SQL のキャンセル済み注文の日時                   |
| `NOW`                 | `2026-10-10T09:00:00.123456Z` | Service が新規作成・キャンセルで使う固定現在時刻 |

Create Test と作成 Rollback Test は、商品 4 件・注文 0 件・明細 0 件から始める。  
Lifecycle Test とキャンセル Rollback Test は、商品 4 件・注文 4 件・明細 8 件から始める。
Console の実行時刻と、この業務データの固定時刻を混同しない。

### @Sql の ISOLATED と Setup / Cleanup

`@SqlConfig(transactionMode = ISOLATED)` は、SQL Script を独立した Transaction で実行する指定である。  
今回の Fixture を業務 Transaction の開始前に Commit し、後の Rollback で Fixture 自体まで消さないようにする。

ここでの `ISOLATED` は、DB の Transaction Isolation Level を SERIALIZABLE などへ変更する指定ではない。  
Test の Script を、既存の Transaction へ参加させるか、独立させるかという制御である。

```text
Context / Container / Schema の準備
  ↓
@Sql: cleanup.sql → products.sql → 必要な orders.sql を実行・Commit
  ↓
@BeforeEach: Test Transaction がないこと、初期状態を確認
  ↓
Test 本体: Service 呼び出し → Commit / Rollback → DB の Assertion
  ↓
@AfterEach: 定義された追加確認
  ↓
@Sql(AFTER_TEST_METHOD): cleanup.sql を実行・Commit
```

Cleanup は `order_items → orders → products` の順に DELETE し、外部キーの子から親へ削除する。  
開始前にも削除するため、前の Test による変更を初期データへ混ぜない。
終了後の削除と組み合わせ、Context を共有しても Test の前提を分離する。

**業務 Rollback は仕様の検証対象、Cleanup は次の Test のための後始末である。**  
異なる目的の操作なので、Cleanup が終わった後の空状態を Rollback の証拠にしない。

### Method 単位の @Sql と MERGE

Transaction Test の作成ケースは商品だけを使い、キャンセルケースには既存注文を追加する。  
キャンセル Method の `@Sql("/sql/unit07/orders.sql")` を、Class 単位の前後 Script と組み合わせる。

`@Sql` は通常、Method の宣言が Class の宣言を上書きするため、今回の Transaction Test は次を明示する。

```java
@SqlMergeMode(SqlMergeMode.MergeMode.MERGE)
```

これにより、Class の Cleanup・商品投入を保持した後で、Method の注文投入を実行する。  
Class に定義した Test Method 終了後の Cleanup も失わない。
Annotation が増えた理由を、単なる定型ではなく Fixture の組み立て方から理解する。

Class の商品投入と Method の注文投入は、それぞれの `@Sql` 宣言で実行・Commit される。  
すべての Setup が必ず 1 Transaction になるという意味ではなく、後から開始する Service の業務 Transaction と分離していることが重要である。

### Test Isolation と繰り返し実行

Test Isolation は、別の Test の実行結果や順序を、その Test の前提にしないことである。  
Container が新しくても、同じ実行内で行データを共有し続ければ独立性は成立しない。

今回の単一商品作成は `@RepeatedTest(2)` を使い、毎回、注文 0 件・在庫 10 から注文 1 件・在庫 8 へ進むことを確認する。  
繰り返し回数そのものではなく、各 invocation の Setup と初期状態確認が再現性を作る。

同じ注文の 2 回キャンセルは、意図した状態遷移を 1 Test 内で連続して確認するケースである。  
別 Test の実行順へ依存することとは異なる。
2 回目の拒否後は初期 Fixture ではなく、1 回目の正常 Commit 後の状態と比較する。

この Fixture は、同じ DB へ書き込む Test の逐次実行を前提とする。  
並列実行で互いの Cleanup が行を削除する構成へ、そのまま切り替えてよいという保証ではない。

### Container、接続先、Schema の再利用

Unit 06 と同様、Container を Spring Bean として管理し、`@ServiceConnection` で DataSource へ動的な接続情報を渡す。  
Container 内は実 PostgreSQL であり、SQL の結果を固定する Stub ではない。

DB 名は `unit07_service_test` である。  
通常用の Compose DB・永続 Volume・ホストの固定ポートを使わず、Context が終了するときに専用環境も後始末する。

本番と同じ `schema.sql` を Container の起動時に実行し、`spring.sql.init.mode=never` で Boot の通常初期化を止める。  
「Schema も作らない」のではなく、構造の準備を Container 側へ任せ、各 Test の行データは `@Sql` が担当する。

`@ServiceConnection` は接続先、SQL 初期化の停止は通常用データの混入防止という別の役割を持つ。  
`application.yml` 全体を無視する設定ではない。
Testcontainers を利用できないときに通常用 DB へ代替接続する構成も設けていない。

### Context Cache、Container Lifecycle、Mock の reset

Spring Test は同じ構成の Context をキャッシュし、複数クラスで再利用できる。  
今回の Create Test と Lifecycle Test は、起点・追加設定・Property・送料 Mock の構成を揃えている。

Transaction Test は Repository Spy が加わるため、別構成の Context になる。  
その Context も自身の Container Bean を持つ。
Unit 06 の JDBC Slice とも別であり、全体で常に 1 Container になるとは期待しない。

Mock / Spy の既定の AFTER reset は、各 Test 後に stubbing と呼び出し記録を除去する。  
これは DB 行の Cleanup ではない。
Context の再利用、Mock の reset、SQL による行の削除、Container の破棄は、それぞれ異なる寿命・状態を管理する。

Context Cache の共有は、同じ実行内での再利用である。  
別の Maven コマンドで前回の Context を引き継いだり、`withReuse(true)` で Container を永続化したりする構成ではない。

### 実行コストと Test Execution Time

境界を広げると、現実の連携を確認できる一方で、準備・実行・失敗原因の切り分けのコストが増える。  
今回追加される主なコストは、広い Context の構築、Docker / PostgreSQL の起動、実 SQL、Fixture と Cleanup、外部環境の準備である。

一方、広い Test をすべて増やせばよいわけではない。  
細かな計算境界は Plain Unit Test、Web の契約は Slice Test、層をまたぐ保存と Transaction は今回の Test、という分担で、確認範囲と実行コストを釣り合わせる。

Surefire のクラス別 `Time elapsed` は、そのクラスの実行に必要となった初回の Context 構築を含む場合がある。  
後続クラスが Context を再利用すれば、その時間をもう一度負担しない。
クラス別の数値だけで、各業務 Method が何秒かかったかを判断できない。

Maven の `Total time` は、コンパイルやテスト起動などを含む別の範囲である。  
さらに、コマンドの壁時計上の所要時間、Container 起動時間、個々の Test Case の時間も区別する。
今回の教材は実行時間を合否条件にする性能 Test ではない。

## 使用する技術・ツール

### 実行基盤と依存関係

| 技術・設定                                    | 今回の役割                                                       |
| --------------------------------------------- | ---------------------------------------------------------------- |
| Java 21                                       | Service・Domain・Test の実行、`record` による観測値の表現        |
| Spring Boot 4.1.1                             | `pom.xml` の Parent。実 Application Context と依存バージョン管理 |
| Spring Test / Spring Boot Test                | JUnit と Context の連携、Bean の置換、SQL の前後実行             |
| Spring JDBC / PostgreSQL Driver               | 実 Repository の SQL と、Test の直接 SELECT                      |
| PostgreSQL `postgres:18.6`                    | 本番と同じ Schema を使う実 DB                                    |
| Testcontainers / `spring-boot-testcontainers` | 専用 Container の Lifecycle と接続情報の受け渡し                 |
| JUnit Jupiter                                 | 通常 Test、Parameterized Test、Repeated Test、前後処理           |
| AssertJ                                       | 戻り値・行データ・例外・観測記録の検証                           |
| Mockito                                       | 送料の Mock、Rollback Test での商品 Repository Spy               |
| Maven Wrapper / Surefire                      | Test の選択・実行・結果と時間のレポート                          |

Unit 07 では新しい依存関係を追加せず、既存の `pom.xml` の構成を使用する。  
Unit 05 の Web MVC Test Starter と Unit 06 の JDBC / Testcontainers 関連依存を含む既存基盤を使い、WireMock、REST Assured、DBUnit は追加していない。

Maven Wrapper が指定する Maven は 3.9.16 である。  
実際の依存関係・Plugin の構成は `pom.xml` と Wrapper の設定を正本として確認し、外部資料の最新版へ読み替えて変更しない。

### 主な Annotation と観測 API

| Annotation / API                                                | 今回の読み方                                             |
| --------------------------------------------------------------- | -------------------------------------------------------- |
| `@SpringBootTest`                                               | 実アプリを起点とする Context を構成する                  |
| `@Autowired`                                                    | Spring 管理の Service・JDBC・接続基盤を受け取る          |
| `@TestConfiguration` / `@Import`                                | 専用 Container と固定 Clock の設定を追加する             |
| `@Bean` / `@Primary`                                            | Test 用の実 Object を登録し、固定 Clock を優先候補とする |
| `@ServiceConnection`                                            | Container の接続情報を DataSource へ渡す                 |
| `@MockitoBean(enforceOverride = true)`                          | 既存の送料 Client を Mock に置き換える                   |
| `@MockitoSpyBean`                                               | 実商品 Repository の指定操作だけを制御する               |
| `@Sql` / `@SqlConfig`                                           | Fixture と Cleanup を独立した Transaction で実行する     |
| `@SqlMergeMode(MERGE)`                                          | Class と Method の Script を組み合わせる                 |
| `@BeforeEach` / `@AfterEach`                                    | 初期状態と、定義された終了時の条件を確認する             |
| `TestTransaction.isActive()`                                    | Spring Test が管理する Test Transaction の有無を確認する |
| `TransactionSynchronizationManager.isActualTransactionActive()` | 観測中の実 Transaction の有効状態を確認する              |
| `AopUtils.isAopProxy(...)`                                      | 注入された Service が AOP Proxy であることを確認する     |

これらをすべて「Test 用の定型」とまとめず、起動範囲、依存の制御、行の準備、Transaction の観測へ役割を分ける。

## テスト対象とテスト境界

### SUT と実依存

SUT は `OrderService` である。  
Test は Spring から取得した Service の公開 Method を直接呼び、実 Repository と PostgreSQL を含めた結果を確認する。

```text
[Test Code]
    ↓
[Spring の Service Proxy / 業務 Transaction]
    ↓
[実 OrderService]
    ├─ 実 Domain / OrderPriceCalculator
    ├─ 実 JdbcOrderRepository → Spring JDBC → PostgreSQL
    ├─ 実 JdbcProductRepository → Spring JDBC → PostgreSQL
    │      └─ Rollback 専用クラスだけ Spy で後続の 1 操作を制御
    ├─ @MockitoBean ShippingFeeClient
    │      └─ 外部 HTTP へ進まない
    └─ Clock.fixed(...) を @Primary で注入

[Test の JdbcTemplate]
    └─ 同じ DataSource から途中状態・最終状態を直接観測
```

本物として組み合わせるのは、DI、Service、Domain、Repository、SQL、JDBC、PostgreSQL、Transaction Manager である。  
送料の応答と時刻を制御し、Rollback 専用 Test では後続の更新結果だけを制御する。

### 各 Test Class の担当

| Test Class                               | 主な入口                                     | 主な観測対象                                                                              |
| ---------------------------------------- | -------------------------------------------- | ----------------------------------------------------------------------------------------- |
| `OrderServiceCreateIntegrationTest`      | `createOrder(...)`                           | Proxy と専用 DB 接続、正常 Commit、商品条件の事前拒否、送料取得失敗、繰り返し時の初期状態 |
| `OrderServiceLifecycleIntegrationTest`   | `confirm(...)` / `ship(...)` / `cancel(...)` | 状態・日時の Commit、明細と金額の保持、全商品の在庫復元、禁止状態と二重キャンセル         |
| `OrderServiceTransactionIntegrationTest` | `createOrder(...)` / `cancel(...)`           | 途中の実更新、Transaction の有無、指定例外、業務 Rollback 後の全行比較                    |

### 今回保証する範囲

- 実 Service が取得した商品から注文を生成し、ヘッダ・全明細・在庫を保存できること。
- 固定時刻と仕様から決めた金額が、戻り値と DB に対応していること。
- 在庫と同数の注文が成功し、事前に不正な業務条件は書き込み前に拒否されること。
- 確定・発送で在庫を再減算せず、許可されたキャンセルで全明細分を復元すること。
- 禁止操作や 2 回目のキャンセルが、既存の状態・日時・在庫へ副作用を残さないこと。
- 指定した後続更新の失敗に対し、先行する実 DB 更新も業務 Transaction で取り消されること。
- 同じ Context を使う Test も、明示した Fixture と Cleanup によって前提を持ち越さないこと。

### 今回の入口を通らない範囲

HTTP Request、Request DTO の Bean Validation、Controller の Response DTO 変換、HTTP Status / Header / JSON は通さない。  
Web 層の契約は Unit 05、実 HTTP でつなぐ広い経路は Unit 09 と比較する。

実 `HttpShippingFeeClient` の通信、HTTP Status や JSON に応じた変換、WireMock の Request Verification は実行しない。  
これらは Unit 08 の HTTP 境界で扱う。

実際の並行注文、ロック待ち、分離レベルの比較、DB Server の停止・再接続、分散 Transaction、性能要件の測定も今回の検証には含めない。  
SQL の競合対策の意図と、逐次実行の Test が観測した保証範囲を分ける。

## 使用するファイル

### この Unit で追加・変更する主なファイル

| ファイル                                                                                                                                                | 役割                                                    |
| ------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------- |
| [`OrderServiceCreateIntegrationTest.java`](../../src/test/java/com/example/orderapi/service/OrderServiceCreateIntegrationTest.java)                     | 注文作成、正常 Commit、商品条件・送料失敗、繰り返し実行 |
| [`OrderServiceLifecycleIntegrationTest.java`](../../src/test/java/com/example/orderapi/service/OrderServiceLifecycleIntegrationTest.java)               | 確定・発送・キャンセル、保存内容の保持、禁止操作        |
| [`OrderServiceTransactionIntegrationTest.java`](../../src/test/java/com/example/orderapi/service/OrderServiceTransactionIntegrationTest.java)           | 後続更新の失敗と業務 Rollback                           |
| [`PostgresServiceIntegrationTestConfiguration.java`](../../src/test/java/com/example/orderapi/support/PostgresServiceIntegrationTestConfiguration.java) | 専用 PostgreSQL・接続情報・固定 Clock                   |
| [`OrderServiceIntegrationTestFixture.java`](../../src/test/java/com/example/orderapi/support/OrderServiceIntegrationTestFixture.java)                   | 固定値・Command・在庫と全行の観測                       |
| [`products.sql`](../../src/test/resources/sql/unit07/products.sql)                                                                                      | 通常商品・販売停止・在庫 0 の初期条件                   |
| [`orders.sql`](../../src/test/resources/sql/unit07/orders.sql)                                                                                          | 状態別の注文・全明細・減算済み在庫                      |
| [`cleanup.sql`](../../src/test/resources/sql/unit07/cleanup.sql)                                                                                        | Test 前後の子から親への DELETE                          |

### コードリーディングで参照する Production Code・設定

- [`OrderService.java`](../../src/main/java/com/example/orderapi/service/OrderService.java)：業務フロー、`@Transactional`、在庫と状態更新の件数検査。
- [`CreateOrderCommand.java`](../../src/main/java/com/example/orderapi/service/model/CreateOrderCommand.java)・[`CreateOrderItemCommand.java`](../../src/main/java/com/example/orderapi/service/model/CreateOrderItemCommand.java)：今回の Service 入口の入力。
- [`JdbcOrderRepository.java`](../../src/main/java/com/example/orderapi/repository/jdbc/JdbcOrderRepository.java)・[`JdbcProductRepository.java`](../../src/main/java/com/example/orderapi/repository/jdbc/JdbcProductRepository.java)：実際の登録・状態更新・在庫更新と取得順。
- [`Order.java`](../../src/main/java/com/example/orderapi/domain/Order.java)・[`OrderItem.java`](../../src/main/java/com/example/orderapi/domain/OrderItem.java)・[`OrderStatus.java`](../../src/main/java/com/example/orderapi/domain/OrderStatus.java)：注文・明細・状態遷移の業務上の意味。
- [`OrderPriceCalculator.java`](../../src/main/java/com/example/orderapi/service/OrderPriceCalculator.java)：実物として動く金額計算。
- [`ShippingFeeClient.java`](../../src/main/java/com/example/orderapi/client/ShippingFeeClient.java)・[`HttpShippingFeeClient.java`](../../src/main/java/com/example/orderapi/client/HttpShippingFeeClient.java)：Mock に置く境界と実 HTTP 実装の分担。
- [`ClockConfig.java`](../../src/main/java/com/example/orderapi/config/ClockConfig.java)・[`RestClientConfig.java`](../../src/main/java/com/example/orderapi/config/RestClientConfig.java)：通常の時刻・HTTP Client 設定。
- [`OrderManagementApplication.java`](../../src/main/java/com/example/orderapi/OrderManagementApplication.java)：Component Scan と Auto Configuration の起点。
- [`schema.sql`](../../src/main/resources/schema.sql)・[`application.yml`](../../src/main/resources/application.yml)・[`data.sql`](../../src/main/resources/data.sql)：本番と共用する構造、通常用接続・初期化、今回読み込まない通常用データ。
- [`pom.xml`](../../pom.xml)・[`maven-wrapper.properties`](../../.mvn/wrapper/maven-wrapper.properties)・[`compose.yaml`](../../compose.yaml)：既存の依存・実行基盤と、Test から独立した通常確認用環境。

## 前提

### 既習事項

Unit 01〜06 の JUnit、AssertJ、Mock / Stub / Spy、固定 Clock、Spring の Slice Test、SQL・Row Mapping、PostgreSQL、Testcontainers、Fixture と Cleanup を前提とする。  
特に Unit 03 の注文作成と Unit 06 の保存・在庫更新・Test Rollback を対応させて読める状態が望ましい。

今回新たに詳しく扱うのは、実 Service を Spring 管理下で動かす境界と、その業務 Transaction を Test の Transaction から分離して観測する方法である。  
既習の Rollback と同じ名前だからといって、開始主体や保証範囲まで同じとは考えない。

### 実行環境

- Java 21 と Maven Wrapper を実行できる環境。
- Linux Container を実行できる Docker Engine。
- PostgreSQL と Testcontainers の補助 Container を起動するための Resource。
- 必要な Maven 依存関係・Plugin・Wrapper 本体、および Container Image の取得済みキャッシュ、または取得できる環境。

通常の学習環境は Windows + Git Bash を想定する。  
以降のコマンドはリポジトリのルートで実行する。
PowerShell では `./mvnw` を `.\mvnw.cmd` に読み替える。

本 Unit の Test は Context から PostgreSQL を起動するため、事前に Spring Boot Application を別プロセスで起動する必要はない。  
通常確認用の `docker compose up`、WireMock Stub Server、永続 DB の初期化も手順に含めない。

### 初回取得と通常環境の分離

Maven の依存関係取得と Docker Image の取得は別である。  
Maven の `-o` を付けても、未取得の Wrapper 本体や Container Image まで用意されたり、Docker 側の通信が一律に無効になったりするわけではない。

テスト用 DB は一時 Container の中に作成し、通常利用中の DB や Compose の Volume を共有しない。

## コードリーディング

読む順序は、業務フローと Transaction Boundary を確認し、その後に Test の設定、Fixture、正常系、事前拒否、途中失敗へ進む形とする。

```text
OrderService の作成・状態更新・キャンセル
  ↓
Repository の SQL と Schema
  ↓
@SpringBootTest / 専用設定 / SQL Fixture
  ↓
正常 Commit と事前拒否
  ↓
状態更新と在庫復元
  ↓
失敗直前の観測と業務 Rollback
  ↓
Unit 03・05・06 との保証範囲の比較
```

### 1. createOrder の更新順を確認する

`OrderService.createOrder(...)` の `@Transactional` と、商品検証・送料照会・ヘッダ保存・明細保存・在庫減算・再取得の順を読む。  
どの行より前なら書き込みがまだなく、どの行より後なら部分的な更新があるかを区切る。

`insertOrder(...)` の戻り値は実採番 ID であり、その ID を `insertOrderItems(...)` へ渡す。  
在庫減算のループは `multiItemCommand()` の Keyboard → Mouse の順と対応させる。

### 2. 確定・発送・キャンセルの違いを確認する

`confirm(...)` と `ship(...)` は、状態の可否を確認してから `updateStatusOrThrow(...)` を呼ぶ。  
在庫更新が含まれないことを確認する。

`cancel(...)` では状態・日時を先に更新し、明細ごとに `increaseStock(...)` と `assertSingleRowUpdated(...)` を実行する。  
後続の復元が失敗した場合、在庫だけでなく先に更新した状態・日時も戻る必要がある理由を考える。

### 3. 実 Repository と Schema を対応させる

`JdbcOrderRepository` のヘッダ INSERT、明細の batch INSERT、状態とキャンセル日時の UPDATE を読む。  
`schema.sql` の親子関係と、保存対象の Column を対応させる。

`JdbcProductRepository` は現在在庫へ加減算し、減算では在庫条件を WHERE に含める。  
更新件数を Service が検査することと、SQL が実際に変更する行の条件を分けて確認する。

### 4. @SpringBootTest の構成を読む

3 クラスの Annotation を比較し、実アプリを起点にすること、Web 環境を持たないこと、通常 SQL 初期化を止めることが共通していると確認する。  
Unit 06 の `@JdbcTest` をそのまま Service Test へ付け替えた構成ではない。

Test のフィールドは `@Autowired OrderService` である。  
`@InjectMocks` や Test 内の `new OrderService(...)` で作っていないことを確認する。

### 5. Container と固定 Clock の設定を読む

`PostgresServiceIntegrationTestConfiguration.postgresContainer()` で、Image、DB 名、`@ServiceConnection`、`withInitScript("schema.sql")` を読む。  
通常用の接続先と永続 Volume を使わない理由を Unit 06 と対応させる。

続いて `unit07Clock()` の固定時刻と `@Primary` を確認する。  
Production の `clock()` を変更せず、Service が Test の固定 Clock を受け取る構成を追う。

### 6. 商品・注文 Fixture の初期条件を読む

`products.sql` の商品 7001〜7004 を確認する。  
販売停止の Hub は在庫 5、在庫切れの Cable は在庫 0 とし、販売状態と在庫不足を別条件として表している。

`orders.sql` は状態別の注文 70001〜70004 と、それぞれ 2 明細を投入する。  
Keyboard / Mouse の在庫を 8 / 18 に変更してから、キャンセル対象を操作できる初期状態を作る。

明細 ID の小さい Keyboard を先、Mouse を後に配置している。  
Repository の `ORDER BY oi.id` と合わせ、キャンセルの失敗を「1 商品目の実更新後」に置けることを確認する。

### 7. Java Fixture と DB 観測の責務を読む

`singleItemCommand(...)` と `multiItemCommand()` は入力を作る。  
一方、`stockOf(...)` と `databaseState(...)` は SELECT で現実の状態を読む。
入力の作成と結果の観測を混同しない。

`DatabaseState` は 3 つの List を持つ `record` である。  
Row Mapping による業務 Object を使わず、全列・ID 順で読んだ行データを処理前後で比較する狙いを確認する。

### 8. @Sql と Test 開始時の前提を読む

Create Test の `verifyCommittedFixtureWithoutTestTransaction()` では、商品 4 件、注文・明細 0 件、在庫 10 / 20 を確認する。  
さらに Test Transaction と実 Transaction の両方がないことを確認する。

Lifecycle Test の `verifyCommittedLifecycleFixture()` は注文 4 件・明細 8 件・在庫 8 / 18 を確認し、既存キャンセル日時も固定値と対応させる。  
この違いは Test ごとの Fixture の目的の違いであり、別の Test の更新が残った結果ではない。

### 9. Proxy と専用 DB 接続の確認を読む

`context_usesServiceProxyAndDedicatedPostgres()` は Proxy、Container の起動、JDBC URL、DB 製品名、DB 名を確認する。  
接続情報の確認用 Connection は try-with-resources で返却し、この Connection で Fixture を更新する処理はない。

Proxy の存在だけで Transaction の保証を済ませず、後半の Rollback Test まで読む。

### 10. 単一商品の正常 Commit と繰り返しを読む

`createOrder_singleItem_commitsAndIsRepeatable()` は Keyboard 2 個の注文を 2 回の独立した invocation で実行する。  
送料 Stub の引数が小計 6,000・総数量 2 であることと、合計 6,800 を確認する。

`assertCommittedHeader(...)` の後に、直接 SELECT の明細と在庫を確認する。  
2 回目も在庫 8、注文 1 件となる理由を、前後の SQL と開始時 Assertion から説明できるようにする。

### 11. 複数明細の保存と Assertion の独立性を読む

`createOrder_multipleItems_commitsHeaderItemsAndBothStocks()` は小計 11,000、割引 1,100、合計 10,700 を明示する。  
送料 Client へ渡す総数量 4 と、明細件数 2 を混同しない。

`assertSavedItem(...)` は、2 個注文の正常系で親 ID・商品 Snapshot・数量・金額を確認する補助 Method である。  
戻り値の明細 ID / 親 ID と、DB 行の親 ID も確認し、ヘッダだけの保存で成功にしない。

### 12. 在庫と同数の注文境界を読む

`createOrder_quantityEqualsStock_commitsZeroStock()` は、在庫 10 に対して 10 個を注文する。  
合計 27,800、保存数量 10、最終在庫 0 を確認する。

このケースの数量確認は専用 SELECT で行う。  
数量 2 を確認する前項の補助 Method を、任意数量用の汎用 Assertion と読み違えない。

### 13. 事前拒否の 5 条件を読む

`createOrder_invalidBusinessCondition_leavesDatabaseUnchanged(...)` と `invalidOrders()` を対応させる。  
商品不存在、販売停止、在庫超過、在庫 0、重複商品が別々の条件として渡される。

例外型、3 テーブルの処理前後比較、送料 Client 未呼び出しを確認する。  
これらのケースを「書き込み途中の Rollback を証明する Test」とは説明しない。

### 14. 送料取得失敗と cause を読む

`createOrder_shippingFailure_preservesDatabaseAndCause()` は、送料 Mock から例外を送出させる。  
`ShippingFeeServiceException` への変換と、元の `clientFailure` が cause に保持されることを確認する。

DB の全行が処理前と等しいことも確認する。  
実 HTTP Status や JSON の解析は、この Java Object 境界の Stub からは検証されない。

### 15. 確定・発送で保持する値を読む

`confirm_createdOrder_commitsStatusWithoutChangingInventory()` と `ship_confirmedOrder_commitsStatusWithoutDecreasingStockAgain()` を比較する。  
目標状態以外に、明細・在庫・作成日時・金額を保持することを確認する。

`assertHeaderUpdateAndPreservedItems(...)` は処理前の行をコピーし、対象注文の状態・キャンセル日時だけを期待値へ反映する。  
対象外の注文や他の Column の誤更新、明細削除も比較対象となる。

### 16. 両方の許可状態からのキャンセルを読む

`cancel_allowedStatus_commitsTimestampAndRestoresAllStocks(...)` と `cancellableOrders()` を読む。  
`CREATED` / `CONFIRMED` の各 invocation が、別々に作り直した Fixture から始まる。

戻り値と DB の状態・固定キャンセル日時に加え、`assertRestoredProducts(...)` で在庫 10 / 20 と商品属性の保持を確認する。  
`@AfterEach` の `verifyNoShippingCallAndNoRemainingTransaction()` は、状態変更で送料を再照会しないことも確認する。

### 17. 二重キャンセルと禁止状態を読む

`cancel_twice_preservesFirstCommittedCancellation()` は、1 回目の正常 Commit 後に観測値を保存し、2 回目の拒否後と比較する。  
初期状態へ戻ることではなく、正当な 1 回目の結果を保つことが期待値である。

`transition_invalidStatus_preservesAllTables(...)` と `invalidTransitions()` は、再確定、作成済みからの直接発送、発送済み・キャンセル済みのキャンセルを代表ケースとする。  
Domain の全状態表の繰り返しではなく、実 Service から DB へ副作用を残さない拒否を確認する。

`cancel_missingOrder_preservesExistingOrdersAndProducts()` は、実 Repository の空結果を Service の `OrderNotFoundException` へ変換する境界も確認する。

### 18. Rollback Test の設定を比較する

Transaction Test にだけ `@MockitoSpyBean JdbcProductRepository` があることを確認する。  
Service や注文 Repository 自体を Mock に変えず、実更新を残したまま後続操作の失敗を制御する。

`verifyNoTestManagedTransaction()` は、各 Test の開始時に Test Transaction と実 Transaction がないことを確認する。  
この前提と、Callback 中の実 Transaction 有効という観測をつなぐ。

### 19. 作成途中の例外注入と観測値を読む

`createOrder_secondStockUpdateFails_rollsBackAllEarlierWrites()` の `doAnswer(...)` は、Mouse の減算だけを対象にする。  
その Callback が記録する `observeBeforeFailure()` は SELECT だけを行い、DELETE や在庫復元を行わない。

観測値のヘッダ 1 件・明細 2 件・在庫 8 / 20 と、業務 Transaction 有効 / Test Transaction 無効を確認する。  
期待した例外 instance が返ってから、全行が処理前と等しいことを確認する順を追う。

### 20. キャンセルの追加 Fixture と 0 件更新を読む

`cancel_secondStockRestoreReturnsZero_rollsBackStatusTimestampAndFirstRestore()` の Method 単位の `@Sql` と、Class の `@SqlMergeMode(MERGE)` を対応させる。  
商品だけの準備へ注文を追加し、Class の前後 Cleanup を保持している。

Mouse の復元では実 SQL を実行せず 0 を返す。  
Service の `assertSingleRowUpdated(...)` が送出する例外と Message を確認し、失敗を検出する主体が実 Service であることを読む。

### 21. キャンセル日時と先行復元の Rollback を読む

失敗直前の対象注文は `CANCELLED`、日時は `NOW`、在庫は 10 / 18 である。  
この途中状態と、例外後の `CONFIRMED`・`null`・8 / 18 を区別する。

JDBC の行 Map 内の日時は `Timestamp` として比較しているため、期待する `Instant` を `Timestamp.from(NOW)` へ変換する。  
型の違いと、表している時点の違いを混同しない。

### 22. Assertion と Cleanup の順序を確認する

両 Rollback Test の `assertThat(databaseState(jdbcTemplate)).isEqualTo(before)` は Test 本体の中にある。  
`AFTER_TEST_METHOD` の DELETE はその後に実行される。

「次の Test が正常に始まったから」「Container が消えたから」という間接的な理由ではなく、同じ Test の例外後に期待状態を確認していると説明できるようにする。

### 23. 既存 Test と同じユースケースを比較する

Unit 03 の `createOrder_stubsDependenciesAndCapturesPersistenceArguments()` と、今回の複数商品作成を比較する。  
引数を保存しようとしたこと、SQL が実行されたこと、Commit 後に保存されていることのどこを確認しているかを整理する。

Unit 05 の `create_returnsCreatedLocationAndOrderJson()` は実 Service を通らず、Unit 06 の `rollback_removesChangesAndTransactionalFixture()` は Test Transaction を終了している。  
今回との違いを、単に「軽い / 重い」ではなく、SUT・開始主体・実依存・観測時点から説明する。

## 実行・確認

### 1. 実行環境とコマンドの場所を確認する

以下は、リポジトリのルートで実行する Git Bash 向けのコマンドである。

```bash
java -version
./mvnw -version
docker info
```

Java 21、Wrapper が使用する Maven と Java、Docker の Server 情報を確認する。  
Docker CLI がインストールされているだけでは、Linux Container を起動できる Engine へ接続できるとは限らない。

PowerShell では Maven Wrapper の部分を次の形にする。

```powershell
.\mvnw.cmd -version
```

通常用の Application、PostgreSQL Compose、送料 Stub を先に起動する手順は不要である。  
Test が必要な Context と専用 DB を準備する。

### 2. 新規 3 クラスを個別に実行する

```bash
./mvnw '-Dtest=OrderServiceCreateIntegrationTest' test
./mvnw '-Dtest=OrderServiceLifecycleIntegrationTest' test
./mvnw '-Dtest=OrderServiceTransactionIntegrationTest' test
```

それぞれ、注文作成 11 件、状態更新 10 件、業務 Rollback 2 件を実行する。  
コマンドを別々に実行するため、前のコマンドの JVM / Spring Context を次のコマンドへ引き継がない。

正常系では、単に戻り値が正しいことだけでなく、Test 本体の直接 SELECT の Assertion が通っていることを確認する。  
Rollback Test では、期待する例外を Assertion で確認するため、意図した例外が発生しても Test は成功となる。

### 3. Unit 07 をまとめて実行する

```bash
./mvnw '-Dtest=OrderServiceCreateIntegrationTest,OrderServiceLifecycleIntegrationTest,OrderServiceTransactionIntegrationTest' test
```

| Test Class                               | ソース上の Test Method 数 | 展開後の実行数 |
| ---------------------------------------- | ------------------------- | -------------- |
| `OrderServiceCreateIntegrationTest`      | 6                         | 11             |
| `OrderServiceLifecycleIntegrationTest`   | 6                         | 10             |
| `OrderServiceTransactionIntegrationTest` | 2                         | 2              |
| 合計                                     | 14                        | 23             |

Create Test は、通常 Test 4 Method、2 回繰り返す 1 Method、5 条件の Parameterized Test 1 Method からなる。  
Lifecycle Test は、通常 Test 4 Method、2 状態のキャンセル 1 Method、4 条件の禁止操作 1 Method からなる。

`@BeforeEach`、`@AfterEach`、`@MethodSource` の供給 Method、補助 Assertion は Test Method 数へ含めない。  
JUnit の展開後の件数と、ソースに見える Method 数が異なる理由を確認する。

まとめて実行したときも、各 Test の初期状態 Assertion が成功することを見る。  
Context の共有を許しつつ、行データと Mock / Spy の設定を持ち越さない構成の確認となる。

### 4. 特定の Test Method を読みながら実行する

例えば、正常な在庫境界と作成途中の Rollback に絞る場合は、次を使う。

```bash
./mvnw '-Dtest=OrderServiceCreateIntegrationTest#createOrder_quantityEqualsStock_commitsZeroStock' test
./mvnw '-Dtest=OrderServiceTransactionIntegrationTest#createOrder_secondStockUpdateFails_rollsBackAllEarlierWrites' test
```

前者は、在庫 10 に対する 10 個の注文が成功し、在庫 0 を Commit することを見る。  
後者は、先行するヘッダ・明細・Keyboard の更新を観測したうえで、例外後に処理前へ戻ることを見る。

Method 単位でも、その Method の Context・Container・Fixture が必要となる。  
実行対象を絞ることと、DB を使わない Test へ変わることは別である。

### 5. 既存 Unit を含む全 Test を実行する

```bash
./mvnw test
```

Unit 07 第1回生成時点では、既存 Unit 01〜06 の 132 件と、新規 23 件を合わせて 155 件となる。  
全体には Unit 06・07 の DB Test が含まれるため、Docker Engine も必要である。
後続 Unit の追加後は、その時点の Test Suite の件数を確認する。

現在は `*Test` に該当する名前のクラスを Surefire が `test` Phase で実行する。  
`OrderService...IntegrationTest` も末尾が `Test` であり、別途 `*IT` や Failsafe 専用 Profile を追加していない。
実行管理の分離は Unit 10 で扱う。

Plain Unit Test を比較のために選択する場合は、例えば次を使える。

```bash
./mvnw '-Dtest=OrderServiceMockitoTest' test
```

この選択は Spring Context や DB を必要としない Service Unit Test の復習になる。  
その成功を、今回の実 Repository・業務 Transaction の成功として扱わない。

### 6. キャッシュ済み環境では offline 実行を使う

Maven の依存関係・Plugin・Wrapper 本体が取得済みなら、Maven の `-o` を使用できる。  
第1回の検証では、この offline 実行を使用した。

```bash
./mvnw -o '-Dtest=OrderServiceCreateIntegrationTest,OrderServiceLifecycleIntegrationTest,OrderServiceTransactionIntegrationTest' test
./mvnw -o test
```

PowerShell の一括実行は次の形となる。

```powershell
.\mvnw.cmd -o '-Dtest=OrderServiceCreateIntegrationTest,OrderServiceLifecycleIntegrationTest,OrderServiceTransactionIntegrationTest' test
.\mvnw.cmd -o test
```

Maven が offline でも Docker は必要であり、Container Image のキャッシュは別途必要である。  
不足する artifact や Image を取得する場合、取得の可否と、業務の Assertion に到達したかを分けて判断する。

### 7. コンパイル確認と Test 成功を区別する

Test を実行せず、Test Code のコンパイルまで確認するコマンドは次である。

```bash
./mvnw -o -DskipTests test
```

この場合の `BUILD SUCCESS` と `Tests are skipped.` は、Test が実行されていないことを表す。  
Spring Context、PostgreSQL、SQL、Commit / Rollback を実行して成功したという証拠にはならない。

今回の教材についても、コンパイル成功、実 DB Test 成功、文書と実装の静的確認を別々の結果として扱う。

### 8. Surefire の成功件数と失敗箇所を読む

Unit 07 の一括実行が成功した場合、確認する主要項目は次の形となる。

```text
Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

全体実行では、第1回生成時点の主要項目は次の形となる。

```text
Tests run: 155, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

上記は結果の要点であり、Container の ID・Port・起動時間を固定した期待ログではない。  
巨大な起動ログの全文より、対象クラス、件数、最初の原因、Assertion の期待値と実際値を対応させる。

詳細は `target/surefire-reports/` の Text / XML にある。

```text
target/surefire-reports/
  com.example.orderapi.service.OrderServiceCreateIntegrationTest.txt
  com.example.orderapi.service.OrderServiceLifecycleIntegrationTest.txt
  com.example.orderapi.service.OrderServiceTransactionIntegrationTest.txt
  TEST-com.example.orderapi.service.OrderServiceCreateIntegrationTest.xml
  TEST-com.example.orderapi.service.OrderServiceLifecycleIntegrationTest.xml
  TEST-com.example.orderapi.service.OrderServiceTransactionIntegrationTest.xml
```

レポートが存在するだけでは、直前のコマンドや現在のソースの Test 成功を証明しない。  
いつ、どの対象・コマンドで実行したかと、結果・更新されたレポートを組み合わせて読む。

DB の途中状態や最終状態は、Test 内の Assertion で確認している。  
正常終了時に各行の表が Console へすべて表示される実装ではないため、値の根拠は対応する Test と Fixture も読む。

### 9. 実測した時間と一般的なコスト差を比較する

同じ第1回の実行記録から、クラス別の `Time elapsed` を比較すると次のようになる。

| Test Class       | クラス単独のコマンド | Unit 07 一括実行内 | 全体実行内 |
| ---------------- | -------------------- | ------------------ | ---------- |
| Create Test      | 8.300 秒             | 6.748 秒           | 2.449 秒   |
| Lifecycle Test   | 6.614 秒             | 0.398 秒           | 0.354 秒   |
| Transaction Test | 7.213 秒             | 2.763 秒           | 1.918 秒   |

単独コマンドでは各クラスが Context と Container の起動を必要とする。  
一括実行では Lifecycle Test が通常構成の Context を再利用し、Spy を持つ Transaction Test は別 Context を用意する。

全体実行では、それ以前の Spring / Mockito の初期化や JVM の状態なども影響する。  
同じクラスの時間が変わっても、業務 Method 自体の速度が同じ倍率で改善したとは判断できない。

これらは 1 環境での実行記録であり、性能保証や厳密な Benchmark ではない。  
「Plain Unit Test は環境起動を必要としない」「実 DB を含む Test は起動・SQL・Cleanup の負担を持つ」という一般的な説明と、この表の個別実測値を分けて理解する。

### 10. 失敗した段階から切り分ける

| 段階・症状                         | 最初に確認すること                                                    |
| ---------------------------------- | --------------------------------------------------------------------- |
| Java / コンパイルで失敗            | Java 21、Wrapper が使用する Java、実際の `pom.xml`                    |
| artifact / Plugin を解決できない   | offline 指定とキャッシュ。Test 本体の失敗と区別する                   |
| Docker API に接続できない          | Engine の稼働、Linux Container の Context、実行権限                   |
| Image 取得・Container 起動で失敗   | キャッシュ・Registry・Resource・起動ログ                              |
| Application Context が起動しない   | 最初の cause、実アプリの起点、追加設定、必要な Bean                   |
| 送料 Mock の置換先がない           | `enforceOverride` の対象と Component Scan。新しい Mock 追加で隠さない |
| 固定日時の Assertion が違う        | `@Primary` の Clock、`NOW`、SQL の既存日時、JDBC の型                 |
| @Sql で外部キー・重複・Script 不在 | Classpath、親子の順、前後 Cleanup、Method の Script と MERGE          |
| 初期状態が違う                     | 通常用初期化の停止、適用した商品・注文 Script、共有状態や並列実行     |
| 失敗前の観測が記録されない         | Spy の対象商品・数量、事前チェックで終了していないか                  |
| 観測に Test Transaction がある     | Test / 親クラス / 合成 Annotation で Transaction を開始していないか   |
| 観測に実 Transaction がない        | Spring 管理の Service を使っているか、Proxy と Transaction の適用     |
| 期待した例外と違う                 | Callback の SQL、失敗対象、Service の例外変換、最初の cause           |
| 例外後にヘッダや先行在庫更新が残る | Service の Transaction Boundary、例外伝播、Repository の参加先        |
| 明細だけ正しく見える               | INNER JOIN だけでヘッダの残存を見落としていないか、直接 SELECT の結果 |
| 個別では成功し一括で失敗           | SQL の上書き、Mock / Spy reset、Cleanup、実行順・並列実行への依存     |

起動できない問題を、在庫の期待値を変える修正で隠さない。  
また、Rollback 後の行が違う問題を、Assertion 前の DELETE や補償 UPDATE で解消しない。
失敗した責務と観測時点へ戻って確認する。

第1回では Mockito / Byte Buddy の動的 Java agent に関する警告が出たが、Test はすべて成功した。  
警告と Test の失敗は区別し、必要な設定検討を無関係な教材修正へ混ぜない。

### 11. 実行後の環境を確認する

通常終了では、Context の終了に伴い Spring 管理の Container が後始末される。  
必要なら、実行後に読み取り専用で確認する。

```bash
docker ps -a --filter label=org.testcontainers=true
```

Container の破棄は、Test 本体で業務 Rollback を観測することの代わりではない。  
通常利用中の DB / Compose Volume を削除する操作は、この Unit の手順に含めない。

## 学習ポイント

### 保証したい責務から本物にする依存を決める

同じ注文作成でも、Service の分岐、HTTP の契約、SQL の保存、業務 Transaction は別の責務である。  
確認したい故障に必要な実装を境界へ入れ、その他の変動は適切な Test Double や固定値で制御する。

### Spring Context の起動と業務処理の実行を分ける

Bean を読み込めることは重要な前提だが、それだけで保存や Rollback の動作は保証しない。  
実 Service を呼び、実 DB の結果まで確認して初めて、その経路の振る舞いを確認できる。

### Transaction は開始主体と終了原因まで読む

`@Transactional` や Rollback という名前だけで理解を止めない。  
Test が始めたのか Service の Proxy が始めたのか、何を囲み、どの例外で終了したのかを追う。

### 途中状態を確認してから最終状態を比較する

最終状態が変わらない理由には、書き込み前に止まった場合も含まれる。  
部分的な実更新へ到達した証拠と、例外後に戻った証拠を組み合わせることで、業務 Rollback の意味が明確になる。

### 正当な既存データと今回の変更を区別する

Rollback は、DB を常に空にする操作ではない。  
今回の業務 Transaction が変更した部分を取り消し、Commit 済みの既存注文・明細・在庫を保持する。

### Cleanup で仕様の不備を隠さない

Cleanup は Test を独立させる仕組みであり、業務の正しさを作る補償処理ではない。  
処理後の Assertion を先に置き、後始末が実装の不具合を隠さない順序にする。

### interaction と DB の状態を使い分ける

Mock / Spy の Verification は、呼び出した境界と引数を確認する。  
SQL による実更新や Commit / Rollback の結果には、DB の状態の Assertion を使う。
どちらか一方を万能の証拠として扱わない。

### Context の共有とデータの共有を混同しない

Context と Container の再利用で起動コストを抑えても、各 Test の前提は分離できる。  
SQL、Mock の reset、ローカルな観測記録を組み合わせ、順序依存を持ち込まない。

### 実行時間は測定範囲と条件を添えて読む

Test 数、Context の初回構築、Container のキャッシュ、JVM の初期化、コンパイルの有無で時間は変わる。  
値の大小だけで Test の品質やユースケースの性能を判断せず、必要な保証へコストを配分する。

## 補足

### @Transactional の Proxy と内部呼び出し

通常の Proxy 方式では、外から Proxy を通る呼び出しに Transaction の処理が適用される。  
同じ Object 内から別 Method を呼ぶだけで、新しい Proxy 境界を通ったことにはならない。

今回の Service は公開ユースケースを Test から Proxy 経由で呼び、その中の private な補助処理と Repository 呼び出しを同じ業務 Transaction に含める。  
内部の補助 Method ごとに別 Transaction が開始されるという説明にはしない。

### 例外の種類と Rollback の設定

今回の Service には、独自の `rollbackFor` や伝播設定を追加していない。  
検証したのは RuntimeException による失敗と、既存の業務 Transaction の組み合わせである。

checked exception、例外を catch して正常終了する処理、別 Transaction を開始する処理では、同じ前提が成立するとは限らない。  
実務では例外を上位へ伝える契約と Transaction の設定を合わせて確認する。

### SQL エラー後の PostgreSQL と今回の失敗注入

Unit 06 では、実 SQL の制約違反で PostgreSQL の Transaction が失敗状態になるケースを扱った。  
今回の作成 Rollback は、Repository 境界で例外を注入するため、PostgreSQL が実際に制約違反を検出したケースではない。

どちらも Rollback が関係するが、故障の発生場所と観測できる条件は異なる。  
Test Double で再現した障害を、DB の全障害パターンを検証した結果として一般化しない。

### record と観測値の扱い

`DatabaseState` と `FailureObservation` の `record` は、関係する観測値をまとめて渡すための型である。  
`record` であることだけで、内部の List や Map まで深く不変になるわけではない。

今回の期待状態作成では行 Map を `LinkedHashMap` へコピーし、元の観測値を直接書き換えない。  
観測値自体を期待値に合わせて変更すると、誤った実装を検出できなくなるためである。

3 テーブルの SELECT をまとめた値は、DB の Snapshot Isolation を設定する機能でもない。  
今回の逐次実行での比較を目的とし、同時更新中の一貫した全 DB Snapshot の取得を保証するものではない。

### BigDecimal、日時、採番の比較

金額は `isEqualByComparingTo(...)` で数値を比較する。  
JDBC の行 Map に入った金額を比較する際も、数値と小数桁数を含む同値性の違いを意識する。

Domain の時刻は `Instant`、JDBC の取得は `OffsetDateTime` や `Timestamp` として扱う箇所がある。  
固定値は小数部 6 桁に揃え、表している瞬間と Java の型の違いを分けて確認する。

PostgreSQL の sequence は、業務 Rollback や DELETE によって必ず消費前へ戻るわけではない。  
`DatabaseState` の行比較と sequence の値は別であり、欠番や新規 ID の具体値を Test の失敗理由にしない。

### Container の管理方式を混在させない

今回の Container は Spring Bean として管理するため、JUnit の `@Container`、手動 `start()` / `stop()` を追加していない。  
Context が DataSource を保持している間に Container だけを終了させないよう、Lifecycle の担当を揃える。

Testcontainers の補助 Container が現れることもある。  
それは通常用の送料 Stub や Compose のサービスではなく、テスト環境の後始末などを支える実行基盤として識別する。

### 並行更新と Test Isolation の違い

在庫減算の条件付き UPDATE と、期待状態を含む状態 UPDATE は、古い取得値で無条件に上書きしないための設計である。  
ただし今回の Spy による 0 件更新は、複数スレッドや複数 Transaction を実際に競合させたものではない。

DB の並行実行保証を広げる場合は、更新の順序、別 Connection、Commit、ロック、分離レベルを含む別の Test 設計が必要になる。  
Test 同士の Fixture を独立させることと、同時更新の安全性を確認することは別である。

### Unit 08・09・10 へのつながり

Unit 08 では `ShippingFeeClient` の Java Object 境界から、実 `HttpShippingFeeClient` と WireMock による HTTP 境界へ焦点を移す。  
HTTP Status、JSON、Request の一致や送信確認を扱うことで、今回の送料 Mock が担当していない部分を確認する。

Unit 09 では、実 Web Server に HTTP Request を送り、Controller から DB・外部 HTTP 境界までをつなぐ。  
別スレッドで処理される HTTP Server の Transaction を、呼び出し側 Test の Transaction と同一視しないことも重要となる。

Unit 10 では、蓄積した Test Suite を Surefire / Failsafe、Tag、Coverage などで管理する。  
今回 `test` Phase で動く Integration Test があることと、後で実行管理を分けることを矛盾と考えず、学習する責務の段階として捉える。
