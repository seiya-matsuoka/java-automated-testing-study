# 09. REST API 全体のテスト

## この Unit の目的

この Unit では、起動した Spring Boot アプリケーションへ実 HTTP Request を送信し、HTTP 入口から DB・外部 HTTP 境界までを含む API Test を学ぶ。  
Controller、Validation、DTO 変換、Service、Repository、PostgreSQL、実 HTTP Client を接続したときに、公開する Response と業務結果が対応していることを確認する。

Unit 05 は Service を置き換えた Web MVC Slice Test、Unit 07 は Service を入口にした実 DB との Integration Test、Unit 08 は外向き HTTP Client の Test だった。  
今回はそれらで扱った実装をつなぎ、REST Assured から注文管理 API を操作する。

中心となるのは、**HTTP Response・Commit 後の DB 状態・外部 API の受信記録を組み合わせ、何を保証したかを説明できること**である。  
HTTP 201 や 200 が返ったことだけで、明細・金額・在庫・状態・日時まで正しいとは判断しない。

広い Test は接続部分の不具合を検出できる一方、起動環境、実行時間、Fixture、後始末、失敗原因の切り分けが必要になる。  
既存の狭い Test と役割を分担し、広い境界を選ぶ意味とコストを理解する。

## 学習内容

### API Test は公開された入口から振る舞いを確認する

API Test は、アプリケーションが外部へ公開する API を操作し、その契約や業務上の結果を確認する Test である。  
今回の入口は `POST /api/orders` などの HTTP endpoint であり、Service の Java Method を Test から直接呼ぶ構成とは異なる。

API の契約には、URL・HTTP Method・Request の形式、成功や失敗の Status、Header、JSON の field などが含まれる。  
注文作成のような更新 API では、その契約と実際の保存結果・在庫更新が一致することも重要となる。

例えば「作成結果に正しい合計を返したが、DB には異なる値を保存した」「注文ヘッダだけを保存して明細を残さなかった」という不具合は、Status の確認だけでは検出できない。  
今回の Test は公開 Response と DB を別々に観測し、その接続部分を確認する。

### Unit Test・Slice Test・Integration Test・API Test を比較する

Test の分類は、Annotation 名だけで決まるものではない。  
入口、本物として動かす依存、置き換える依存、観測対象から境界を読み取る。

| Unit   | 入口と主な構成                                          | 主に確認すること                                             |
| ------ | ------------------------------------------------------- | ------------------------------------------------------------ |
| 01〜04 | Java Object を直接組み立てる Unit Test                  | 計算・状態判定・Service の調整処理・依存とのやり取り         |
| 05     | MockMvc → MVC・Controller、Service は Mock              | Mapping、Binding、Validation、Response DTO、例外の HTTP 変換 |
| 06     | JDBC Repository → Spring JDBC → PostgreSQL              | 実 SQL、制約、Row Mapping、Repository の検索・更新           |
| 07     | 実 Service Proxy → Repository → PostgreSQL、送料は Mock | 業務 Transaction、複数更新、Commit、途中失敗の Rollback      |
| 08     | 実 HTTP Client → 実 HTTP → WireMock                     | 外向き Request、Response の変換、Client の例外               |
| 09     | REST Assured → 実 HTTP 入口 → 実アプリ・DB・WireMock    | 公開契約と層をまたぐ実際の業務結果                           |

Slice Test は対象の層に必要な Spring 構成へ範囲を限定する方法である。  
Integration Test は複数の実装や基盤の連携を確認する Test であり、実 PostgreSQL を使う JDBC Slice も Integration Test の性質を持つ。

API Test は公開入口から見る観点であり、今回のように実アプリ・DB をつなぐ場合は広い Integration Test でもある。  
これらを必ず排他的な分類として扱う必要はない。

### System Test・E2E Test・Acceptance Test との位置関係

System Test は、定めたシステム全体を対象として要件を確認する観点である。  
何をシステムの内側とするかによって範囲は変わり、HTTP を使うことだけで、すべての System Test が完了するわけではない。

E2E Test は、利用の経路を端から端までつないで確認する観点である。  
ブラウザから複数サービスを経由するシナリオなども含まれるが、E2E の範囲は対象システムの定義に依存する。

今回はテスト用 JVM の Web Server、使い捨て PostgreSQL、テスト側が制御する WireMock を使う。  
ブラウザ、デプロイ済み環境、Proxy・TLS、実外部サービスを通した確認は行わない。
作成から発送までを HTTP でつなぐケースも、制御した依存を持つバックエンド API の業務シナリオとして位置付ける。

Acceptance Test は、利用者や業務側の受入条件を満たすかを確認する観点である。  
「キャンセルすると対象注文の数量だけ在庫が戻る」という自動テストは受入条件の確認材料になり得るが、人による要件確認や受入判断の完了まで意味するものではない。

### SUT と本物にする依存を選ぶ

今回の SUT は、注文管理 REST API の HTTP 入口から、永続化と外部 HTTP 境界までを含む振る舞いである。  
個別の Controller や Service だけを取り出すのではなく、実アプリの Bean の接続も対象にする。

```text
REST Assured
  → 実 Web Server / Spring MVC
    → OrderController / Request DTO / Validation
      → OrderService の Transaction Proxy
        ├─ Domain / OrderPriceCalculator
        ├─ JDBC Repository → PostgreSQL
        └─ HttpShippingFeeClient → RestClient → WireMock

成功: Response DTO → JSON → REST Assured
失敗: GlobalExceptionHandler → Error JSON → REST Assured
```

Controller・Service・Repository・Client を Mock にすると、今回確認したい接続部分が実行されなくなる。  
そのため新規 API Test には、それらの Mockito Mock / Spy を追加していない。

一方、外部 Shipping Fee API は WireMock へ置き換える。  
実 Client の HTTP 処理を通しながら、相手の稼働やデータに依存せず、正常・異常の Response を安定して用意するためである。

固定 Clock は、JDK の実 `Clock.fixed(...)` を使い、現在時刻だけを制御する。  
実依存か Mock かの二択で考えず、どの性質を本物にし、どの変動を Test 側で固定するかを考える。

### 2 つの HTTP 境界の向きと責務

今回の Test には、独立した 2 つの HTTP 通信がある。

```text
内向き: REST Assured → 注文管理 API のテスト用 Web Server
外向き: HttpShippingFeeClient → Shipping Fee API を代替する WireMock
```

内向きの Request は `shippingPostalCode` と注文明細を持つ。  
外向きの Request は、Service が実商品から計算した割引前の `subtotal`、`totalQuantity`、`postalCode` を持つ。

| 観点        | 注文管理 API の入口               | Shipping Fee API への出口                 |
| ----------- | --------------------------------- | ----------------------------------------- |
| HTTP Client | REST Assured                      | 実 `RestClient`                           |
| HTTP Server | 実 Spring Boot Web Server         | WireMock                                  |
| 操作        | `POST /api/orders` など           | `POST /shipping-fees/calculate`           |
| 主な観測    | Status・Header・注文 / Error JSON | Method・Path・Header・送信 JSON・送信回数 |
| 失敗の表現  | API 利用者へ返す公開エラー        | Client が受け取る upstream の異常         |

例えば WireMock が返す `500` と、注文管理 API が返す `502` は、異なる通信の Status である。  
Unit 08 で使った「実 HTTP」という言葉も、この Unit の内向きの HTTP 入口まで含めていたわけではない。

### Spring Context と実 Web Server を起動する

`@SpringBootTest` は、Spring Boot の起動方法を使って Test 用の Application Context を構成する。  
今回の共通設定は実アプリの `OrderManagementApplication` を起点にし、Component Scan と Auto Configuration で各層を組み立てる。

```java
@SpringBootTest(
    classes = OrderManagementApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.sql.init.mode=never", "server.address=127.0.0.1"})
@Import(OrderApiTestConfiguration.class)
```

| WebEnvironment | Web Server                       | 今回との関係                        |
| -------------- | -------------------------------- | ----------------------------------- |
| `NONE`         | 起動しない                       | Unit 07 の Service を入口にする構成 |
| `MOCK`         | 実 Server は起動しない           | Mock の Web 環境で MVC を扱う選択肢 |
| `RANDOM_PORT`  | 動的ポートで起動する             | Unit 09 の実 HTTP 入口              |
| `DEFINED_PORT` | 設定値や既定値のポートで起動する | 今回は使用しない                    |

MockMvc は TCP ポートへ接続せず、MVC の処理系へ模擬 Request を渡す。  
REST Assured の MockMvc 連携も存在するが、今回使う通常の `rest-assured` と実 Server への通信とは異なる。

`spring.sql.init.mode=never` は、通常用 `data.sql` と Schema の二重初期化を止める指定である。  
DB を使用しない指定でも、Service の Transaction を無効にする指定でもない。

### random port と接続先を分けて管理する

random port は、Server 起動時に利用可能なポートを割り当てる仕組みである。  
固定ポート `8080` などの競合を避け、Test が実際に起動した Server へ接続できる。

```java
@LocalServerPort protected int port;
```

この `port` は注文管理 API の Web Server のポートである。  
WireMock の `shippingFeeServer.port()` や、Testcontainers が公開する PostgreSQL の JDBC ポートとは別に管理する。

| 接続先              | 決定する方法                           | 利用する側            |
| ------------------- | -------------------------------------- | --------------------- |
| 注文管理 Web Server | `RANDOM_PORT` と `@LocalServerPort`    | REST Assured          |
| WireMock            | `dynamicPort()` と動的 property        | 実 `RestClientConfig` |
| PostgreSQL          | Testcontainers と `@ServiceConnection` | 実 DataSource / JDBC  |

Web Server と WireMock は `127.0.0.1` を使う。  
動的な数値を期待値へ埋め込まず、注入されたポートや起動済みリソースの値を照合する。

### REST Assured の Request・操作・Response 検証

REST Assured は、HTTP Request の組み立てと送信、Response の検証・抽出を Java の DSL で記述するためのライブラリである。  
Server の起動や DB の準備を担当するものではない。

```java
var response = createOrder(multiItemRequest());

response
    .then()
    .statusCode(201)
    .contentType(ContentType.JSON)
    .header("Location", notNullValue())
    .body("status", equalTo("CREATED"))
    .body("items.size()", equalTo(2));
long id = response.jsonPath().getLong("id");
```

| 記述                                           | 役割                                          |
| ---------------------------------------------- | --------------------------------------------- |
| `given()` / `spec(...)` / `body(...)`          | 接続先、Header、Request Body などの条件を作る |
| `when().post(...)` / `get(...)` / `patch(...)` | HTTP 操作を実行する                           |
| `then().statusCode(...)`                       | HTTP の結果を確認する                         |
| `contentType(...)` / `header(...)`             | 表現形式や必要な Header を確認する            |
| `body(...)`                                    | JSONPath で JSON の field や配列を確認する    |
| `jsonPath().getLong(...)`                      | 作成結果の ID を抽出し、後続操作へ渡す        |

`equalTo(...)` などは Hamcrest の Matcher である。  
DB Snapshot や Jackson の値の比較には AssertJ を使い、それぞれの観測に適した Assertion を選んでいる。

### RequestSpecification と static な共有設定

RequestSpecification は、接続先、port、Header、通信設定などをまとめるための仕様オブジェクトである。  
共通の条件を一か所で読めるようにしつつ、前の Request の Body や検索条件を次へ持ち越さないことが重要になる。

今回の `request()` は、毎回新しい `RequestSpecBuilder` と設定を作る。  
`RestAssured.baseURI`、`RestAssured.port`、`RestAssured.requestSpecification`、`RestAssured.config` への代入は行わない。

```java
new RequestSpecBuilder()
    .setBaseUri("http://127.0.0.1")
    .setPort(port)
    .setAccept(ContentType.JSON)
    .setContentType(ContentType.JSON);
```

実コードでは、これに Test Client の接続 timeout 5 秒、読み取り timeout 10 秒を指定した設定を加え、`RestAssured.given().spec(...)` へ渡す。  
timeout は通信異常時の待機を制限する設定であり、注文処理が一定時間以内に終わることを検証する Performance Test ではない。

また、この設定は REST Assured 側のものであり、Production の外向き `RestClient` の timeout を変更する設定ではない。  
内向きと外向きの Client の構成を混同しない。

### JSON の入力と DTO・Command の変換

注文作成の Request は、公開 field 名が見える JSON 文字列で用意する。  
Production の Request DTO を serialize して期待側にも使うと、同じ field 名の誤りを送信側・期待側へ持ち込む可能性があるためである。

```json
{
  "shippingPostalCode": "1000001",
  "items": [
    { "productId": 9001, "quantity": 2 },
    { "productId": 9002, "quantity": 2 }
  ]
}
```

Server 側では、実際に JSON → `CreateOrderRequest` / `CreateOrderItemRequest` → Bean Validation → Service 用 Command の変換を通る。  
Response も Domain → Response DTO → JSON の実経路を通る。

Test Client が JSON 文字列を送ることは、Server の JSON 変換を省略することではない。  
壊れた JSON のケースでは、Test 側で事前に parse・整形せず、不正な raw 文字列をそのまま送る。

### HTTP Response・DB・外部 Request の 3 方向から観測する

各観測には異なる保証がある。

| 観測                | 分かること                                 | それだけでは分からないこと                 |
| ------------------- | ------------------------------------------ | ------------------------------------------ |
| HTTP Response       | 公開 Status・Header・JSON                  | 実際に何が Commit されたか                 |
| DB の直接 SELECT    | 注文・明細・在庫・状態・日時の最終保存結果 | 公開 JSON が正しいか、外部へ何を送信したか |
| WireMock の受信記録 | 外向き Request の内容と回数                | アプリ内の保存結果や実外部サービスの実装   |

Response と DB が同じ値でも、両方が同じ誤った計算結果を使っている可能性は残る。  
そのため仕様から独立した期待値と比較し、そのうえで Response と保存内容の対応も確認する。

### 注文作成の金額を仕様と Fixture から独立して定める

中心となる作成ケースでは、次の商品を使う。

| 商品 ID | 商品名   |  単価 | 初期在庫 | 注文数量 | 明細金額 |
| ------- | -------- | ----: | -------: | -------: | -------: |
| `9001`  | Keyboard | 3,000 |       10 |        2 |    6,000 |
| `9002`  | Mouse    | 2,500 |       20 |        2 |    5,000 |

```text
subtotal       = 6,000 + 5,000 = 11,000
discountAmount = 11,000 × 10% = 1,100
shippingFee    = WireMock の Response で与える 800
totalAmount    = 11,000 - 1,100 + 800 = 10,700
totalQuantity  = 2 + 2 = 4
作成後の在庫   = Keyboard 8 / Mouse 18
```

10,000 円以上で 10% を割り引き、1 円未満を切り捨てる業務仕様を根拠とする。  
Test の期待値を `OrderPriceCalculator` の戻り値から計算し直す構成にはしていない。

送料 API へ送信する `subtotal` は割引前の `11000` であり、割引後の `9900` や最終合計 `10700` ではない。  
送料 `800` は今回の Stub が返す値であり、実在する配送サービスの料金計算を検証した結果ではない。

| 別の正常ケース                    |   小計 |  割引 | 送料 |   合計 | 作成後の在庫 |
| --------------------------------- | -----: | ----: | ---: | -----: | ------------ |
| Keyboard 2 個・郵便番号 `0600001` |  6,000 |     0 |    0 |  6,000 | 8 / 20       |
| Keyboard 10 個・在庫と同数        | 30,000 | 3,000 |  800 | 27,800 | 0 / 20       |

数量や割引の全境界を広い API Test で繰り返すのではなく、既存 Unit Test の境界値検証と補い合わせる。

### 201・Location・生成 ID・再取得を対応させる

注文作成は `201 Created` と作成した注文の `Location` を返す。  
現 Controller は `ServletUriComponentsBuilder.fromCurrentRequest()` を基準にするため、host・port を持つ絶対 URI を生成する。

```text
Location: http://127.0.0.1:<実際の Web Server port>/api/orders/<生成された ID>
```

これは可変部分を示す説明用表記であり、固定値の実行結果ではない。  
`assertLocation(...)` は scheme `http`、host `127.0.0.1`、注入された port、抽出した ID を含む path、query / fragment がないことを確認する。

中心ケースは、その Location へ GET し、作成 Response と同じ注文・明細 ID、金額、状態、日時を取得できることを確認する。  
GET 後も DB が不変で、送料 API の受信件数が作成時の 1 件のままであることを確認する。

仕様書の相対 URI の例や ID `100` を、そのまま固定の期待値にしない。  
ID は Response から抽出し、DB の保存行と照合して後続 Request へ渡す。

### JSON の数値・null・collection を確認する

JSON の数値は、Test 側で扱う型にも注意が必要である。  
今回の `assertMoney(...)` は Jackson の Node が数値であることを確認し、`decimalValue()` と AssertJ の `isEqualByComparingTo(...)` で金額を比較する。

`BigDecimal` の scale や float / double の丸め差を、金額の意味と混同しない。  
ID 列の比較では `getList("id", Long.class)` を使い、JSONPath の整数型の違いにも依存しない。

`cancelledAt` は、未キャンセルなら field が存在して JSON `null` であることを確認する。  
field の省略、文字列 `"null"`、JSON `null` は同じ表現ではない。

明細は商品 ID ごとの対応と件数を確認し、同じ商品に対応する明細が正確に 1 件あることを保証する。  
入力配列の順序を新しい API 要件にはしていない。

### 注文 Response の field と保存内容を対応させる

作成・詳細・状態変更は共通の `OrderResponse` を返す。  
中心となる 2 商品の作成ケースでは、次の対応を確認する。

| JSON field           | 中心ケースで期待する内容     | 対応する保存内容              |
| -------------------- | ---------------------------- | ----------------------------- |
| `id`                 | 生成された正数 ID            | `orders.id`                   |
| `status`             | `CREATED`                    | `orders.status`               |
| `shippingPostalCode` | 文字列 `1000001`             | `orders.shipping_postal_code` |
| `items`              | Keyboard・Mouse の 2 明細    | 対象注文の `order_items` 2 行 |
| `subtotal`           | 数値 `11000`                 | `orders.subtotal`             |
| `discountAmount`     | 数値 `1100`                  | `orders.discount_amount`      |
| `shippingFee`        | 数値 `800`                   | `orders.shipping_fee`         |
| `totalAmount`        | 数値 `10700`                 | `orders.total_amount`         |
| `createdAt`          | 固定 Clock の Instant        | `orders.created_at`           |
| `cancelledAt`        | field が存在して JSON `null` | `orders.cancelled_at` が NULL |

明細には `id`、`productId`、`productName`、`unitPrice`、`quantity`、`lineAmount` がある。  
明細 ID は動的な保存行との対応、商品 ID は Fixture の識別子との対応、名称・単価は注文時点の Snapshot として確認する。

明細の `order_id` は公開 DTO の field には含めず、Test が直接読む保存行と親の注文 ID を照合する。  
一覧の Summary は上表から `items` を除いた 9 field であり、公開する形と保存用の列名を同一視しない。

### 商品の現在値と注文時点の Snapshot

注文明細の商品名・単価は、注文時点の Snapshot として保存する。  
商品マスタの現在値に置き換えると、過去の注文内容が変わってしまう。

検索用 Fixture の注文 `91001` は、現在の Keyboard の単価 `3000` と異なる `2800`、商品名 `Keyboard at order time` を保持する。  
詳細 API が保存済みの名称・単価・明細金額を返し、現在の商品マスタをそのまま表示していないことを確認する。

### 一覧の検索条件・日時の両端・並び順

一覧 API の任意条件は `status`、`createdFrom`、`createdTo` である。  
未指定の条件は SQL へ追加せず、指定した条件を AND で結合する。

実 SQL の日時条件は `created_at >= createdFrom` と `created_at <= createdTo` であり、両端を含む。  
並び順は `created_at DESC, id DESC` で、同じ日時なら ID が大きい注文を先に返す。

検索 Fixture は次の 5 注文を持つ。  
表中の日付はいずれも UTC の `00:00:00.123456Z` である。

| ID      | 状態      | 作成日     | 明細数 |   合計 |
| ------- | --------- | ---------- | -----: | -----: |
| `91001` | CREATED   | 2026-10-01 |      1 |  3,600 |
| `91002` | CONFIRMED | 2026-10-02 |      1 |  3,800 |
| `91003` | CREATED   | 2026-10-02 |      2 | 10,700 |
| `91004` | SHIPPED   | 2026-10-03 |      1 |  3,800 |
| `91005` | CANCELLED | 2026-10-04 |      1 |  3,800 |

`FROM` は 10 月 2 日、`TO` は 10 月 3 日の上記時刻を指す。  
期待する ID 列は SQL Fixture と条件から明示し、Production の検索結果から生成しない。

| 条件                | 期待する ID の順序                  |
| ------------------- | ----------------------------------- |
| 条件なし            | `91005, 91004, 91003, 91002, 91001` |
| status = CREATED    | `91003, 91001`                      |
| FROM のみ           | `91005, 91004, 91003, 91002`        |
| TO のみ             | `91004, 91003, 91002, 91001`        |
| CREATED と FROM     | `91003`                             |
| CREATED と TO       | `91003, 91001`                      |
| FROM と TO          | `91004, 91003, 91002`               |
| CREATED・FROM・TO   | `91003`                             |
| from = to = FROM    | `91003, 91002`                      |
| SHIPPED と上限 FROM | 空配列                              |

一覧は `OrderSummaryResponse` を返し、`items` を含まない。  
JOIN で 2 行になる注文 `91003` も一覧では 1 注文となり、件数だけでなく ID の重複・欠落・順序を確認する。

### 作成から確定・発送までを 1 Test 内でつなぐ

状態遷移の代表シナリオは、API で作成した注文 ID を使って操作する。

```text
POST → CREATED / 在庫 8・18
  ↓ GET
confirm → CONFIRMED / 在庫 8・18
  ↓ GET
ship → SHIPPED / 在庫 8・18
  ↓ GET
各段階の公開 JSON と保存済み状態を照合
```

確定・発送は状態を変更し、注文作成時に減算した在庫をさらに減算しない。  
明細・金額・作成日時を保持し、送料の受信記録はシナリオ全体で正確に 1 件とする。

各 HTTP 操作は別の業務 Transaction で Commit する。  
一連の Request 全体を、Test 側の 1 Transaction で囲んでいるわけではない。

### キャンセルの状態・日時・在庫復元

キャンセルは CREATED または CONFIRMED からだけ許可される。  
成功時は CANCELLED、固定時刻の `cancelledAt`、全明細分の在庫復元を同じ業務 Transaction で保存する。

状態遷移用 Fixture には、CREATED・CONFIRMED・SHIPPED・CANCELLED の 4 注文と、各 2 明細がある。  
キャンセルされていない 3 注文が各商品を 2 個ずつ消費しており、基準の在庫 `10 / 20` から 6 個ずつ減算した `4 / 14` を現在値とする。

対象の 1 注文をキャンセルした結果は `6 / 16` である。  
他注文分まで復元して `10 / 20` に戻すのは誤りであり、対象注文だけのヘッダ変更と、他注文・全明細・無関係な商品の保持も観測する。

2 回目のキャンセルは `409` となる。  
比較先は最初の Fixture ではなく、1 回目の正常な Commit 後の状態であり、在庫の二重復元・キャンセル日時の再更新を検出する。

### Validation・Business Error・外部障害・想定外エラーを区別する

失敗を一括して「エラー」と捉えず、発生場所と公開契約を分ける。

| 種類               | 代表的な発生場所                   | 公開する結果                       | Unit 09 の確認                           |
| ------------------ | ---------------------------------- | ---------------------------------- | ---------------------------------------- |
| JSON 読み取り失敗  | JSON → Request DTO                 | `400 / VALIDATION_ERROR`           | 不正 JSON と数量の型違反                 |
| Bean Validation    | Request DTO の制約                 | `400 / VALIDATION_ERROR`           | 10 入力ケースと field error              |
| Parameter 変換失敗 | status / Instant の変換            | `400 / VALIDATION_ERROR`           | 未定義 status、不正な上下限日時          |
| 検索期間の不整合   | 実 Repository の期間チェック       | `400 / VALIDATION_ERROR`           | from > to                                |
| 業務上の拒否       | Service / Domain・実 DB の商品条件 | 意味に応じた `400 / 404 / 409`     | 作成拒否、不存在、不正遷移               |
| 送料取得失敗       | 実 Client → Service の例外変換     | `502 / SHIPPING_FEE_SERVICE_ERROR` | upstream 500、不正 JSON、必須 field 欠落 |
| 想定外の内部失敗   | 明示的に扱わない Exception         | `500 / INTERNAL_SERVER_ERROR`      | 新規 API Test では故障注入しない         |

想定外エラーの Handler は実アプリに存在し、Unit 05 の `ship_whenUnexpectedFailure_returns500WithoutInternalDetails()` が Web 層の変換を確認している。  
今回の API Test で任意の DB 障害やアプリ内部の 500 を再現した、と範囲を広げて説明しない。

### Validation Error の field と message

`CreateOrderRequest` の `@Valid`、明細への nested Validation、List 要素の `@NotNull` を実 MVC に通す。  
例えば数量 0 の正常な JSON は、JSON 読み取り後の Bean Validation によって拒否される。

```json
{
  "code": "VALIDATION_ERROR",
  "message": "request validation failed",
  "fieldErrors": [
    { "field": "items[0].quantity", "message": "quantity must be at least 1" }
  ]
}
```

一方、数量 `"abc"` や途中で切れた JSON は、DTO の読み取り段階で失敗する。  
その場合の message は `request body is invalid`、field は `request` になる。

複数の制約違反がある場合、field error の列挙順は固定しない。  
`assertFieldError(...)` は同じ配列要素に期待する field と message の組があるかを調べ、別々の要素から値を拾って誤判定しない。

### Business Error と副作用の観測

作成時の Business Error は、構文上・Validation 上は成立する Request に対する業務判断である。

| ケース             | Status | Error Code                        |
| ------------------ | -----: | --------------------------------- |
| 同一商品が重複     |    400 | `DUPLICATE_ORDER_ITEM`            |
| 商品が存在しない   |    404 | `PRODUCT_NOT_FOUND`               |
| 商品が販売停止     |    409 | `PRODUCT_INACTIVE`                |
| 在庫不足・在庫 0   |    409 | `INSUFFICIENT_STOCK`              |
| 注文が存在しない   |    404 | `ORDER_NOT_FOUND`                 |
| 禁止された状態遷移 |    409 | `INVALID_ORDER_STATUS_TRANSITION` |

例えば在庫 20 の Mouse を 21 個要求すると、次の業務エラーとなる。

```json
{
  "code": "INSUFFICIENT_STOCK",
  "message": "insufficient stock: productId=9002, requested=21, available=20",
  "fieldErrors": []
}
```

作成の拒否ケースには有効な先頭商品も含め、後続商品のチェックで失敗しても、先頭商品の在庫を減らさないことを確認する。  
既存注文を含む 3 テーブルの不変と、送料 API の受信 0 件を合わせて観測する。

これは実 DB の最終状態と外部通信の保証である。  
Service が一度も呼ばれなかったことや、読み取り SQL まで未実行だったことを、DB 不変だけから結論付けない。

### 外部 API の異常から API の 502 まで

外向きの送料照会には、次の代表的な異常 Response を与える。

| upstream Status | Body                                         | 区別する異常              |
| --------------- | -------------------------------------------- | ------------------------- |
| 500             | `{"error":"unit09-upstream-private-detail"}` | HTTP Status の失敗        |
| 200             | `{"shippingFee":`                            | JSON の構文不正           |
| 200             | `{}`                                         | DTO に必要な field の欠落 |

```text
WireMock の異常 Response
  → 実 HttpShippingFeeClient / RestClient の失敗
  → OrderService が ShippingFeeServiceException へ変換
  → GlobalExceptionHandler が 502 と公開 Error JSON へ変換
```

```json
{
  "code": "SHIPPING_FEE_SERVICE_ERROR",
  "message": "shipping fee service is unavailable",
  "fieldErrors": []
}
```

upstream の private な文字列、`stackTrace`、`cause` を Response へ含めないことも確認する。  
有効な商品・数量を使って送料取得まで到達させ、受信した Request が小計 `6000`・総数量 `2` の 1 件であることを検証する。

Client の cause の型や保持された upstream Body、空 Body・null・負数送料などの細かな異常は Unit 08 の役割である。  
Unit 09 は代表経路が実 API の公開エラーと DB 不変につながることを補う。

### WireMock の Stub と request matching

Stub は、どの Request にどの Response を返すかという前提を用意するものである。  
正常時の `stubShippingFee(...)` は、Method・Path・JSON Content-Type・Request JSON を条件にして送料を返す。

```java
post(urlEqualTo("/shipping-fees/calculate"))
    .withHeader("Content-Type", matching("(?i)application/json(?:\\s*;.*)?"))
    .withRequestBody(equalToJson(expectedJson));
```

このコードは matching 部分の抜粋であり、実際の応答設定は `willReturn(...)` に続く。  
Content-Type の表記は、JSON であることと charset 等の付加情報を分けて判定する。

期待する外向き JSON は、例えば次の形式となる。

```json
{
  "postalCode": "1000001",
  "subtotal": 11000,
  "totalQuantity": 4
}
```

`equalToJson(...)` は field 順序や整形上の空白の差を無視するが、今回、余分な field を許容する設定は付けていない。  
Production の `ShippingFeeRequest` を期待 JSON の生成に再利用せず、外部 Contract を明示する。

異常時の `stubShippingResponse(...)` は Method・Path で対象操作を選び、意図した Status・Body を返す。  
その後の verification で送信 JSON・Header・回数を確認し、異常 Response の処理と送信内容の保証を分ける。

### request verification と送信回数

Stub を登録したことと、実際に Request が送信されたことは別である。  
`verifyShippingRequest(...)` は Act 後に WireMock の受信記録を調べる。

```text
期待する Method・Path・Header・JSON に一致する受信が正確に 1 回
AND
全受信記録も 1 件
AND
受信 Body の JSON 型と値が期待どおり
```

期待 Request が 1 回あっても、別 URL への余分な通信があるかもしれない。  
そのため `verify(1, ...)` と `getAllServeEvents()` の件数確認を組み合わせる。

受信 JSON を `JsonMapper` で読み、郵便番号が文字列、小計が数値、総数量が整数であることも確認する。  
郵便番号 `0600001` の先頭 0 を失う、金額を文字列で送る、総数量を固定してしまうといった誤りを区別する。

Validation・作成前の業務拒否・GET・SQL 準備からの状態遷移は `verifyNoShippingRequest()` で受信 0 件を期待する。  
作成を含む一連のシナリオでは、確定・発送・GET 後も全体で 1 件であることを確認する。

### Request Journal・reset・unmatched request

Request Journal は WireMock が受信した Request の記録である。  
Server を再利用すると、Stub とともに前の Test の記録も残り得るため、各 invocation の前に初期化する。

今回の reset は `prepareIsolatedApiBoundary()` が担当する。  
`shippingFeeServer.resetAll()` の後に Stub 一覧・受信記録が空であることを確認し、各 Test が必要な Stub を登録する。

Unit 08 の `WireMockExtension` による自動 reset は、この Unit には適用されない。  
Server の所有者を Spring Bean に変えたため、Test 側の Lifecycle で reset と検査を明示している。

`verifyNoUnmatchedShippingRequests()` は `@AfterEach` で未一致 Request がないことを確認する。  
厳しい正常 Stub に一致しない誤送信や、Stub を用意していない場面の通信を、意図した業務エラーと取り違えないための検査である。

### Test と Server は別スレッドで動く

実 HTTP では、Test のスレッドが HTTP Client を実行し、Server のスレッドが Request を処理する。  
Unit 07 で Test から Service Proxy を直接呼ぶ場合とは、スレッドと Transaction の関係が異なる。

```text
Test スレッド                         Server の Request 処理スレッド
  Commit 済み Fixture を準備
  HTTP Request ─────────────────────→ MVC / Controller
                                      Service Proxy が業務 Transaction を開始
                                      商品の参照・送料照会・DB 更新
                                      Commit または例外による終了
  HTTP Response ←──────────────────── JSON の返却
  DB を直接 SELECT
  Assertion
  別 Transaction の Cleanup
```

Test に `@Transactional` を付けても、その Transaction が HTTP 通信先の Server スレッドを囲むわけではない。  
Test 終了時の自動 Rollback だけでは、Server が Commit した注文や在庫更新を後始末できない。

今回の Test 自身には `@Transactional` を付けていない。  
`TestTransaction.isActive()` と `TransactionSynchronizationManager.isActualTransactionActive()` が false であることを、Test 側の準備時に確認する。

### 業務 Transaction の開始と DB 書き込み開始を分ける

`OrderService.createOrder(...)` の `@Transactional` は、Service Proxy の呼び出しから業務処理を囲む。  
その内部で商品を読み、送料を照会してから、注文・明細・在庫の書き込みへ進む。

「送料取得が DB 書き込み前」という説明は、送料取得時点で業務 Transaction 自体が存在しないという意味ではない。  
読み取り、外部 HTTP 照会、書き込みという処理順序と、Transaction が囲む範囲を分けて読む。

```text
createOrder の業務 Transaction
  商品条件を確認
  → 送料を照会
  → orders INSERT
  → order_items INSERT
  → products 在庫減算
  → 正常終了時に Commit
```

キャンセルでは、注文状態・`cancelledAt` の更新と、各商品の在庫加算を同じ業務 Transaction に含める。  
確定・発送・キャンセルを別々の HTTP Request で呼べば、それぞれに業務 Transaction の開始・終了がある。

### Commit 済み Fixture と @Sql の ISOLATED

HTTP Server から参照する Fixture は、Test 側だけの未 Commit データにしない。  
今回は `@SqlConfig(transactionMode = ISOLATED)` を使い、Fixture の投入を独立した Transaction で Commit する。

```java
@SqlConfig(transactionMode = SqlConfig.TransactionMode.ISOLATED)
@SqlMergeMode(SqlMergeMode.MergeMode.MERGE)
@Sql({"/sql/unit09/cleanup.sql", "/sql/unit09/products.sql"})
@Sql(scripts = "/sql/unit09/cleanup.sql",
    executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
```

共通の準備は Cleanup → 商品投入である。  
詳細・検索・異常ケースには `query-orders.sql`、状態遷移の個別ケースには `lifecycle-orders.sql` を Method の `@Sql` で追加する。

`MERGE` は、Method の追加 Script によって共通の前後 Script が置き換わることを防ぐ。  
共通設定の継承と Method の追加条件を合わせて読む必要がある。

### SQL Fixture と API 経由の準備を使い分ける

| 準備方法                    | 今回使う場面                             | 選ぶ理由                                       |
| --------------------------- | ---------------------------------------- | ---------------------------------------------- |
| 商品だけを SQL で投入       | 注文作成、作成から発送のシナリオ、空一覧 | 注文前の在庫・注文 0 件を明示する              |
| 検索用注文を SQL で投入     | 詳細、条件検索、入力・作成拒否・送料障害 | 日時・状態・Snapshot・既存行を固定して観測する |
| 状態遷移用注文を SQL で投入 | キャンセル、不正遷移、不存在更新         | 対象状態と減算済み在庫を独立して準備する       |
| POST の結果から ID を取得   | Location の GET、一連の確定・発送        | 実 API の出力を後続 API の入力につなぐ         |

すべての Fixture を API で作ると、検索やキャンセルの Test が注文作成の成否にも依存する。  
一方、SQL だけでは、実際に作成した ID を使う連携を確認できない。

準備したい条件と確認したい接続部分に応じて選び、Test Method 間の実行順で状態を引き継がない。

### DB の観測時点と直接 SELECT

`databaseState(...)` は `products`・`orders`・`order_items` を全列・ID 順で読む。  
この補助処理に DELETE、在庫の手動復元、Rollback は含まれない。

API の Repository は注文と明細を JOIN して取得する。  
不具合でヘッダだけ残った場合、JOIN の結果に現れない可能性があるため、API の GET だけでは保存の不完全さを見逃し得る。

HTTP Response を受信した後に直接 SELECT することで、Service の業務 Transaction が終了した状態を観測する。  
Test 側の長い読み取り Transaction で古い Snapshot を保持する構成にはしていない。

`assertSavedOrder(...)` は Response の注文・明細 ID と、保存行の全 field を対応付ける。  
独立した期待値は `assertOrder(...)` / `assertItem(...)` で先に確認し、保存内容との一致だけを唯一の根拠にしない。

`assertOrderTransition(...)` は対象ヘッダの状態・日時だけを変えた期待 Snapshot を作り、明細と他注文が保たれることを確認する。  
`assertStocks(...)` は指定した在庫以外の商品列や、無関係な商品も保持されることを確認する。

### 書き込み前の拒否・途中更新の Rollback・Cleanup

処理後の DB が処理前と同じでも、理由は一つではない。

```text
書き込み前に拒否された
  → 今回の変更をまだ行っていない

先行更新後に失敗し、業務 Rollback された
  → 実際に行った今回の更新が取り消された

Test の Cleanup で削除された
  → 次の Test のために行データを後始末した
```

Validation・事前の業務拒否・送料取得失敗は、書き込み前に止まる代表ケースである。  
そこでの DB 不変は、途中更新の Rollback を証明するものではない。

Unit 07 の Rollback Test は、先行する実更新を失敗直前に観測し、業務例外後・Cleanup 前に元の状態へ戻ったことを確認する。  
今回の API Test に、その故障注入を再追加したわけではない。

Cleanup は Test Isolation のための処理である。  
業務結果の Assertion より先に DELETE して、保存の不具合を隠してはならない。

### Setup・Assertion・Cleanup の順序

各 invocation の流れは次のとおりとなる。

```text
BEFORE_TEST_METHOD の @Sql
  Cleanup → 商品 Fixture → 必要な追加注文 Fixture → Commit
  ↓
@BeforeEach
  WireMock reset → 空の Stub / Journal、接続先、Test Transaction なしを確認
  ↓
Test 本体
  Stub・処理前 Snapshot → HTTP 操作 → Response / DB / 外部 Request の検証
  ↓
@AfterEach
  unmatched request がないことを確認
  ↓
AFTER_TEST_METHOD の @Sql
  子から親へ DELETE → Commit
  ↓
クラスの最後の @AfterAll
  3 テーブルが空であることを直接確認
```

通常の Assertion 失敗時にも Spring Test の後処理が走る構成である。  
`verifyCleanupAfterClass(...)` は、次クラスの開始時 DELETE に Cleanup 漏れが隠されないよう、クラス境界で空の状態を観測する。

ここで Server や Container は停止しない。  
行データの後始末と、起動リソースの停止は異なる Lifecycle に属する。

### 固定 Clock・採番・順序による再現性

固定時刻は `2026-11-01T09:00:00.123456Z` であり、実行した日の現在時刻ではない。  
`@Primary` の固定 Clock を Service へ注入し、作成・キャンセル日時を待機や許容誤差なしで確認する。

SQL の既存注文はそれより前の日時を持つ。  
既存の `createdAt` と新しい `cancelledAt` を区別し、状態更新で作成日時を書き換える誤りも検出する。

| 非決定要素                 | 今回の扱い                                                    |
| -------------------------- | ------------------------------------------------------------- |
| 現在時刻                   | 固定 Clock、UTC の Instant                                    |
| DB の日時精度              | マイクロ秒までの Fixture、JDBC の Timestamp と Instant の対応 |
| 新規 ID                    | Response から抽出し、正数・DB 行との対応を確認                |
| sequence                   | `RESTART IDENTITY` を行わず、欠番や開始値を期待しない         |
| 検索順序                   | 固定日時、同時刻の ID 降順、明示した期待 ID 列                |
| 前回の Request 条件        | Request ごとに新しい Specification                            |
| 前回の DB / Stub / Journal | SQL Cleanup と reset                                          |

固定 ID の SQL Fixture と、動的な生成 ID は目的が異なる。  
HTTP の `Date` Header まで固定 Clock で決まるという説明にはせず、Test は対象の業務日時を検証する。

### Spring Context・Web Server・WireMock・PostgreSQL の Lifecycle

4 つの Test Class は `OrderApiTestSupport` の同じ起動構成を継承する。  
Spring Test の Context Cache が同じ構成を再利用する間、Context 内の Bean と実 Web Server も利用可能な状態を保つ。

| リソース            | 所有・起動・停止                                                    | 各 Test で行うこと                              |
| ------------------- | ------------------------------------------------------------------- | ----------------------------------------------- |
| Application Context | Spring Test が構築し、キャッシュ終了時・テスト JVM 終了時等に閉じる | 接続先・実 Bean の境界を確認                    |
| Web Server          | Boot が Context の Web 環境として管理                               | 注入された port へ HTTP 操作                    |
| PostgreSQLContainer | Spring Boot が Container Bean として管理                            | 専用 DB の行データを準備・観測・Cleanup         |
| WireMockServer      | Bean の `initMethod="start"` / `destroyMethod="stop"`               | Stub・Journal の reset、Stub 登録、verification |
| 固定 Clock          | Context 内の Bean                                                   | 時刻を変えずに共有                              |

Unit 08 はクラス所有の WireMock Extension を停止するため、クラス終了時に `@DirtiesContext` で Context も破棄した。  
Unit 09 は Server を Context と同じ期間だけ保持し、クラス終了時に停止済み URL を残さない方法を採用している。

Context を再利用しても、Test Data を共有する必要はない。  
同一クラス内の実行は `@Execution(SAME_THREAD)`、共有状態への操作は共通名の `@ResourceLock` で保護し、DB や reset を同時操作しないようにする。

### 動的 Base URL を Client Bean の生成前に渡す

WireMock は起動するまで実ポートが決まらない。  
実 `RestClientConfig` は Bean 生成時に `external.shipping-fee.base-url` を受け取るため、後から property を変えるだけでは生成済み Client を差し替えられない。

```java
@Bean
public DynamicPropertyRegistrar shippingFeeProperties(WireMockServer shippingFeeServer) {
  return registry ->
      registry.add(
          "external.shipping-fee.base-url",
          () -> "http://127.0.0.1:" + shippingFeeServer.port());
}
```

`DynamicPropertyRegistrar` は通常の Singleton の事前生成に先行して動的 property を登録する。  
引数の WireMock Bean は `initMethod` の起動を完了してから渡され、その URL を実 Client が利用する。

Unit 08 の static な `@DynamicPropertySource` と異なり、Context が所有する Server Bean と直接関係を作れる。  
Test の `@BeforeEach` は URL の差し替えではなく、すでに構成された接続先の確認と HTTP Fixture の reset を担当する。

### 通常用 Compose・永続 DB・実外部サービスから分離する

`application.yml` の通常用 PostgreSQL や送料 API の固定 URL を、そのまま API Test に使う構成にはしない。  
PostgreSQL は `@ServiceConnection`、送料は動的 property によって、テストが管理する接続先を渡す。

| 観点          | 通常の動作確認                       | Unit 09                                     |
| ------------- | ------------------------------------ | ------------------------------------------- |
| DB            | Compose の PostgreSQL                | Testcontainers の PostgreSQL                |
| 永続化        | 通常用 Volume                        | 使い捨ての専用 Container                    |
| DB 初期データ | 通常用 `data.sql`                    | Unit 09 の明示的な SQL Fixture              |
| Schema        | Production の `schema.sql`           | 同じ `schema.sql` を Container 初期化で使用 |
| 送料 Server   | Compose の WireMock と通常用 mapping | JVM 内の Spring 管理 WireMock               |
| 接続先        | 通常用の固定ポート                   | 動的ポート・専用接続情報                    |

自動テストのために通常用 `docker compose up` を実行する必要はない。  
実外部サービスへの proxy・転送、通常用 mapping の共有、通常用 DB への Fixture 投入は行わない。

### 広い境界で得る保証と実行コスト

今回の Test は、各層単独では成立していても、接続時に崩れる不具合を検出する。  
Request と Command の対応、実商品の値からの送料 Request、保存と Response の対応、例外から公開エラーへの変換などが対象となる。

一方、Spring Context、Web Server、PostgreSQL、WireMock、JDBC、2 つの HTTP 境界の準備が必要になる。  
失敗時も、環境・通信・入力変換・業務処理・SQL・Assertion のどこで失敗したかを調べる必要がある。

狭い Unit Test は条件の組み合わせを詳しく確認しやすく、API Test は主要な接続経路を確認しやすい。  
すべてを API Test に集約せず、代表経路へ広い Test のコストを配分する。

Context Cache、Image の取得状態、JVM の初期化、コンパイルの有無で実行時間は変わる。  
一般的なコストの説明と、後述する第1回のローカル実測値は区別する。

## 使用する技術・ツール

### 実行基盤と依存関係

| 技術・設定                                    | 今回の役割                                        |
| --------------------------------------------- | ------------------------------------------------- |
| Java 21 / Maven Wrapper                       | 実アプリとテストのコンパイル・実行                |
| Spring Boot `4.1.1`                           | 実アプリの構成、Auto Configuration、実 Web Server |
| Spring MVC / Jakarta Validation               | Request Mapping、DTO 変換、入力検証、Advice       |
| REST Assured `6.3.0`                          | 実 HTTP の送信、Status・Header・JSON の確認と抽出 |
| Hamcrest / AssertJ                            | HTTP DSL の Matcher、DB・JSON 値の Assertion      |
| Jackson 3 の `JsonMapper`                     | 実 JSON 変換基盤と、Test の JSON 数値・型の観測   |
| Spring JDBC / PostgreSQL Driver               | 実 Repository と直接 SELECT                       |
| Testcontainers / `@ServiceConnection`         | 専用 PostgreSQL の起動・接続情報・停止            |
| `postgres:18.6`                               | Production と同じ Schema を適用する実 DB          |
| `wiremock-standalone:3.13.2`                  | 外部 HTTP Server 用 Test Double                   |
| JUnit Jupiter / Parameterized / Repeated Test | ケース展開、繰り返し、Lifecycle、結果の管理       |

REST Assured と WireMock の version は POM で明示し、それ以外の管理対象は既存 Boot BOM に委ねている。  
REST Assured の JSONPath と Spring MVC Test で扱った JSONPath は、同じ名前でも API・式の使い方が同一とは限らない。

`wiremock-standalone` は依存の多くを隔離した配布形式の名前である。  
今回、WireMock の別プロセスや Docker Container を起動する指定ではない。

### Annotation と共通の観測 Method

| Annotation / Method                                 | 読み取る役割                                   |
| --------------------------------------------------- | ---------------------------------------------- |
| `@SpringBootTest(RANDOM_PORT)` / `@LocalServerPort` | 実 Web Server と、その実ポート                 |
| `@Import` / `@TestConfiguration`                    | Unit 09 の依存環境を実アプリへ追加             |
| `@Primary`                                          | 固定 Clock を注入候補として優先                |
| `DynamicPropertyRegistrar`                          | Client 生成前の動的 URL 登録                   |
| `@SqlConfig(ISOLATED)` / `@SqlMergeMode(MERGE)`     | Commit 済み Fixture と共通・追加 Script の併用 |
| `@BeforeEach` / `@AfterEach` / `@AfterAll`          | reset、未一致通信検査、Cleanup 後の空 DB 検査  |
| `@Execution(SAME_THREAD)` / `@ResourceLock`         | 共有 DB・WireMock を同時操作しない保護         |
| `assertOrder` / `assertItem` / `assertMoney`        | 独立した公開値の期待と JSON 型の確認           |
| `assertSavedOrder` / `databaseState`                | 保存行との対応と全テーブルの直接観測           |
| `assertOrderTransition` / `assertStocks`            | 許可した変更と、他の保存内容の不変             |
| `assertLocation`                                    | 動的 URI と作成 ID の対応                      |
| `verifyShippingRequest` / `verifyNoShippingRequest` | 実送信・受信 0 件の観測                        |

## テスト対象とテスト境界

### 実行する依存と置き換える依存

```text
SUT:
  注文管理 REST API の HTTP 入口からの振る舞い

本物:
  Web Server / Spring MVC / Validation / Request・Response DTO
  OrderController / GlobalExceptionHandler
  OrderService の Transaction Proxy / Domain / 金額計算
  JDBC Repository / Transaction Manager / PostgreSQL
  HttpShippingFeeClient / RestClientConfig / RestClient / JSON 変換

制御する依存:
  外部 Shipping Fee API → WireMock
  現在時刻 → JDK の固定 Clock
```

`prepareIsolatedApiBoundary()` は実 Service Proxy、実 Client の型、DataSource の URL・DB 名、送料 Base URL を確認する。  
Proxy の存在だけで業務処理の正しさを結論付けず、各 Test の結果観測と組み合わせる。

### 実装した Test Method と展開件数

`OrderCreateApiTest` は、次の 3 Method / 4 件である。

| Test Method                                                       | 件数 | 中心となる保証                                       |
| ----------------------------------------------------------------- | ---: | ---------------------------------------------------- |
| `create_multipleItems_commitsAndCanBeReadFromLocation`            |    2 | 独立した作成・金額・在庫・Location の GET・送料 1 回 |
| `create_zeroShippingFee_preservesPostalCodeAndUndiscountedAmount` |    1 | 先頭 0 の郵便番号、送料 0、割引なし                  |
| `create_quantityEqualsStock_commitsZeroStock`                     |    1 | 在庫と同数の注文と在庫 0 の Commit                   |

`OrderQueryApiTest` は、次の 6 Method / 18 件である。

| Test Method                                          | 件数 | 中心となる保証                                |
| ---------------------------------------------------- | ---: | --------------------------------------------- |
| `findById_returnsHistoricalProductSnapshot`          |    1 | 過去の商品名・単価と保存結果                  |
| `findById_returnsAllItemsAndCancelledTimestamp`      |    1 | 複数明細とキャンセル済み注文の日時            |
| `search_returnsFilteredOrderedSummaries`             |   10 | 指定有無 8 組み合わせ、同時刻上下限、該当なし |
| `search_emptyDatabase_returnsEmptyArray`             |    1 | 注文 0 件の正常な空配列                       |
| `search_invalidCondition_returnsValidationError`     |    4 | status・上下限日時の変換失敗、期間逆転        |
| `findById_missingOrder_returns404WithoutSideEffects` |    1 | 不存在詳細の 404 と既存 DB 不変               |

`OrderLifecycleApiTest` は、次の 5 Method / 11 件である。

| Test Method                                                       | 件数 | 中心となる保証                                           |
| ----------------------------------------------------------------- | ---: | -------------------------------------------------------- |
| `lifecycle_createConfirmShip_preservesItemsAmountsAndStocks`      |    1 | 作成・GET・確定・発送を生成 ID で接続                    |
| `cancel_allowedStatus_commitsTimestampAndRestoresOnlyTargetStock` |    2 | CREATED / CONFIRMED からの復元・日時・他注文保持         |
| `cancel_twice_preservesFirstCommittedCancellation`                |    1 | 2 回目は 409、1 回目の Commit 後を保持                   |
| `transition_invalidStatus_preservesDatabase`                      |    4 | 再確定、直接発送、発送済み・キャンセル済みの cancel 拒否 |
| `transition_missingOrder_returns404WithoutSideEffects`            |    3 | confirm / ship / cancel の不存在 404                     |

`OrderErrorApiTest` は、次の 4 Method / 20 件である。

| Test Method                                                 | 件数 | 中心となる保証                               |
| ----------------------------------------------------------- | ---: | -------------------------------------------- |
| `create_invalidRequest_returnsFieldErrorWithoutSideEffects` |   10 | 郵便番号、items、商品 ID、数量の制約         |
| `create_unreadableJson_returnsRequestError`                 |    2 | 不正 JSON と数量 `"abc"`                     |
| `create_businessError_preservesDatabaseAndSkipsShipping`    |    5 | 重複・不存在商品・販売停止・在庫不足・在庫 0 |
| `create_shippingFailure_returns502AndPreservesDatabase`     |    3 | upstream 500、不正 JSON、必須 field 欠落     |

合計は 18 Method / 53 件である。  
Test Method 数、展開した invocation 数、1 Method 内で送る HTTP Request 数は異なる。

### 保証する範囲と今回追加しない検証

公開契約、実 SQL による保存・検索、正常 Commit、代表的な拒否時の DB 不変、実外向き Request を確認する。  
また、各クラスの最後に Cleanup 済みの行データが空であることを検査する。

実外部サービスの実装・料金計算、任意のネットワーク障害・timeout、並行注文の全競合条件、途中 DB 障害の API からの故障注入は今回の新規ケースに含めない。  
既存 Unit 07・08 の保証と合わせて読み、単独の API Test を万能の検証として扱わない。

## 使用するファイル

### この Unit で追加・変更する主なファイル

| ファイル                                                                                                          | 役割                                      |
| ----------------------------------------------------------------------------------------------------------------- | ----------------------------------------- |
| [pom.xml](../../pom.xml)                                                                                          | REST Assured の test 依存と version       |
| [OrderCreateApiTest.java](../../src/test/java/com/example/orderapi/api/OrderCreateApiTest.java)                   | 注文作成と保存結果                        |
| [OrderQueryApiTest.java](../../src/test/java/com/example/orderapi/api/OrderQueryApiTest.java)                     | 詳細・一覧・検索条件                      |
| [OrderLifecycleApiTest.java](../../src/test/java/com/example/orderapi/api/OrderLifecycleApiTest.java)             | 確定・発送・キャンセル・拒否              |
| [OrderErrorApiTest.java](../../src/test/java/com/example/orderapi/api/OrderErrorApiTest.java)                     | 入力・業務・送料の失敗                    |
| [OrderApiTestSupport.java](../../src/test/java/com/example/orderapi/support/OrderApiTestSupport.java)             | 共通の境界設定と Assertion / Verification |
| [OrderApiTestConfiguration.java](../../src/test/java/com/example/orderapi/support/OrderApiTestConfiguration.java) | Context が所有する依存環境                |
| [OrderApiTestFixture.java](../../src/test/java/com/example/orderapi/support/OrderApiTestFixture.java)             | 入力、固定値、DB 観測                     |
| [products.sql](../../src/test/resources/sql/unit09/products.sql)                                                  | 商品 4 件の基準 Fixture                   |
| [query-orders.sql](../../src/test/resources/sql/unit09/query-orders.sql)                                          | 検索・詳細・拒否用の既存注文              |
| [lifecycle-orders.sql](../../src/test/resources/sql/unit09/lifecycle-orders.sql)                                  | 状態別注文・明細・減算済み在庫            |
| [cleanup.sql](../../src/test/resources/sql/unit09/cleanup.sql)                                                    | 子から親の順の明示的 Cleanup              |

### コードリーディングで参照する Production Code・設定

- [OrderController.java](../../src/main/java/com/example/orderapi/controller/OrderController.java)：HTTP endpoint と Location。
- [CreateOrderRequest.java](../../src/main/java/com/example/orderapi/dto/order/CreateOrderRequest.java)・[CreateOrderItemRequest.java](../../src/main/java/com/example/orderapi/dto/order/CreateOrderItemRequest.java)：入力 JSON の制約と Command への変換。
- [OrderResponse.java](../../src/main/java/com/example/orderapi/dto/order/OrderResponse.java)・[OrderItemResponse.java](../../src/main/java/com/example/orderapi/dto/order/OrderItemResponse.java)・[OrderSummaryResponse.java](../../src/main/java/com/example/orderapi/dto/order/OrderSummaryResponse.java)：公開する field と詳細・一覧の違い。
- [GlobalExceptionHandler.java](../../src/main/java/com/example/orderapi/exception/GlobalExceptionHandler.java)・[ApiErrorResponse.java](../../src/main/java/com/example/orderapi/dto/error/ApiErrorResponse.java)：例外と共通 Error JSON。
- [OrderService.java](../../src/main/java/com/example/orderapi/service/OrderService.java)・[OrderPriceCalculator.java](../../src/main/java/com/example/orderapi/service/OrderPriceCalculator.java)：業務フロー、Transaction、金額ルール。
- [OrderStatus.java](../../src/main/java/com/example/orderapi/domain/OrderStatus.java)：状態遷移の可否。
- [JdbcOrderRepository.java](../../src/main/java/com/example/orderapi/repository/jdbc/JdbcOrderRepository.java)・[JdbcProductRepository.java](../../src/main/java/com/example/orderapi/repository/jdbc/JdbcProductRepository.java)：検索・保存・在庫増減の SQL。
- [HttpShippingFeeClient.java](../../src/main/java/com/example/orderapi/client/HttpShippingFeeClient.java)・[RestClientConfig.java](../../src/main/java/com/example/orderapi/config/RestClientConfig.java)：実 HTTP と動的 Base URL の利用先。
- [ShippingFeeRequest.java](../../src/main/java/com/example/orderapi/client/dto/ShippingFeeRequest.java)・[ShippingFeeResponse.java](../../src/main/java/com/example/orderapi/client/dto/ShippingFeeResponse.java)：外部 API の JSON と値制約。
- [schema.sql](../../src/main/resources/schema.sql)・[application.yml](../../src/main/resources/application.yml)・[compose.yaml](../../compose.yaml)：共通 Schema と通常用環境の違い。
- [Maven Wrapper 設定](../../.mvn/wrapper/maven-wrapper.properties)：使用する Maven 配布版。

### 比較する既存教材

- [Unit 05 README](../05-spring-web-testing/README.md) と [OrderControllerWebMvcTest.java](../../src/test/java/com/example/orderapi/controller/OrderControllerWebMvcTest.java)。
- [Unit 07 README](../07-spring-boot-integration-testing/README.md) と [OrderServiceTransactionIntegrationTest.java](../../src/test/java/com/example/orderapi/service/OrderServiceTransactionIntegrationTest.java)。
- [Unit 08 README](../08-external-http-api-testing/README.md)、[HttpShippingFeeClientTest.java](../../src/test/java/com/example/orderapi/client/HttpShippingFeeClientTest.java)、[OrderServiceShippingFeeHttpTest.java](../../src/test/java/com/example/orderapi/service/OrderServiceShippingFeeHttpTest.java)。

## 前提

### 既習事項と今回追加する境界

Unit 01〜08 の JUnit・Assertion、Test Double、Fixture、Spring Test、JDBC・PostgreSQL、業務 Transaction、WireMock を前提とする。  
個別の API を暗記していることより、SUT・依存・入力・観測対象を分けて読めることが重要となる。

今回新たに扱うのは、起動した Web Server へ REST Assured から実 HTTP Request を送り、Server と Test のスレッド・Transaction を分けて管理する構成である。  
Unit 07 の `NONE` や Unit 08 の Client Slice をそのまま入口として流用しない。

### 実行環境

| 必要なもの                     | 理由                                         |
| ------------------------------ | -------------------------------------------- |
| Java 21                        | アプリとテストの実行基盤                     |
| Maven Wrapper                  | POM の依存解決、コンパイル、Test の実行      |
| 利用可能な Docker Engine       | Testcontainers の PostgreSQL・Ryuk           |
| JVM のローカルポート利用       | 注文管理 Web Server と WireMock              |
| loopback HTTP 通信             | REST Assured → Web Server、Client → WireMock |
| テスト専用 DB の作成・更新権限 | Schema、Fixture、業務更新、Cleanup           |

通常の学習環境は Windows + Git Bash とし、以下のコマンドはリポジトリルートから実行する。  
Docker Desktop を使う場合は、利用者が起動して Engine を利用可能にしておく。

既存の通常用 Compose・PostgreSQL・送料 Stub を起動する必要はない。  
API Test は自分で Web Server・WireMock・専用 Container を準備するため、別ターミナルで `spring-boot:run` を起動する必要もない。

### 初回取得と取得済み環境を区別する

初回は Maven 配布版・依存ライブラリ・必要な Docker Image の取得が発生し得る。  
通常実行でも、Image が未取得なら Docker 側が取得を必要とする。

Maven の `-o` は Maven の offline mode であり、依存や Plugin がキャッシュ済みの場合に利用する。  
`-o` を付けても、Docker Image の取得を制御したり、Test の loopback HTTP / JDBC / Docker 接続を省略したりするわけではない。

## コードリーディング

### 読む順序の全体像

```text
Controller / DTO / Advice の公開契約
  → Service の業務フローと Transaction
  → JDBC / Schema / 外向き Client
  → Test Configuration と Support の起動・Lifecycle
  → SQL Fixture と独立した期待値
  → 作成・詳細・検索・状態遷移・異常系の Test
  → Response / DB / Request 記録の Assertion
  → Cleanup と保存済み検証記録の読み方
```

以下では、Test Method を上から読むだけでなく、何を確かめるためにその Method を用意したかを追う。

### 1. HTTP endpoint と DTO の責務を読む

`OrderController.create(...)` と `findById(...)`、`search(...)`、各 PATCH を読む。  
Controller が担当する Validation、Command への変換、Response DTO、Status・Location と、Service に委譲する業務判断を分ける。

`CreateOrderRequest` と `CreateOrderItemRequest` では、郵便番号・items・商品 ID・数量の制約、List 要素の `@NotNull` と nested `@Valid` を確認する。  
`OrderResponse` / `OrderItemResponse` と `OrderSummaryResponse` の違いも見る。

### 2. 例外から公開 Error Response への対応を読む

`GlobalExceptionHandler` の JSON 読み取り失敗、Bean Validation、Parameter 変換、業務例外、送料障害の Handler を追う。  
同じ 400 でも `message` と field error の意味が異なることを確認する。

`ApiErrorResponse` が常に `fieldErrors` を持ち、業務エラーでは空 List になる点を見る。  
内部例外用の 500 Handler が存在することと、今回の新規 API Test がそこへ故障注入することは別である。

### 3. 作成・状態変更と Transaction を読む

`OrderService.createOrder(...)` で、重複・商品条件の確認、割引前小計・総数量、送料取得、注文・明細の保存、在庫減算の順序を追う。  
送料取得失敗が書き込み前であることと、Method 自体が業務 Transaction に囲まれることを確認する。

`confirm(...)` / `ship(...)` は在庫を再減算しない。  
`cancel(...)` は状態・日時の更新と全明細分の在庫加算を同じ Transaction で行う。

### 4. SQL・Schema・外向き Client を読む

`JdbcOrderRepository.search(...)` の任意条件と `ORDER BY`、JOIN 結果を注文ごとに集約する処理を読む。  
`JdbcProductRepository` の条件付き在庫減算・加算と、`schema.sql` の FK・数量・金額の制約も確認する。

`RestClientConfig` が property から Base URL を受け取り、`HttpShippingFeeClient` が実 POST と Response DTO への変換を行うことを追う。  
送料 Client の例外は Service で業務例外へ変換され、その外側で API Error となる。

### 5. 共通 Test の起動範囲を読む

`OrderApiTestSupport` の `@SpringBootTest`、`@Import`、`@LocalServerPort` を読む。  
実アプリを起点とする RANDOM_PORT の Web 環境であり、MockMvc や Client 用 Slice を使っていないことを確認する。

`prepareIsolatedApiBoundary()` では、Test Transaction がないこと、Service が Proxy であること、Client の実型、専用 DataSource と送料 URL をどう観測しているかを見る。

### 6. Context が所有するリソースと動的 URL を読む

`OrderApiTestConfiguration.postgresContainer()`、`shippingFeeServer()`、`shippingFeeProperties(...)`、`unit09Clock()` を読む。  
通常用接続先の差し替えと、Container / Server / Context の存続期間を対応させる。

特に `DynamicPropertyRegistrar` が起動済み WireMock の URL を Client Bean 生成前に公開する経路を確認する。  
Unit 08 の static Extension と `@DirtiesContext` の組み合わせと比較する。

### 7. SQL と時刻・在庫の前提を読む

`products.sql` の 4 商品を確認し、`OrderApiTestFixture` の識別子と対応させる。  
`query-orders.sql` の 5 注文・6 明細、`lifecycle-orders.sql` の 4 注文・8 明細と在庫 `4 / 14` の意味を読む。

検索用の現在在庫は過去の仕入れも含む現在値として扱い、履歴数量の単純合計から作らない。  
状態遷移用は対象注文だけを復元した `6 / 16` を明示的に期待する。

### 8. REST Assured の接続・送信・抽出を読む

`request()` と `createOrder(...)` / `getOrder(...)` / `transition(...)` を読む。  
接続先・port・Header の共通化、Request ごとの Specification、Path Parameter、HTTP Method を確認する。

`OrderQueryApiTest` の `queryParams(query)` では、日時文字列を手動連結せず、条件を Request へ渡す方法を見る。  
Response の `jsonPath().getLong("id")` と `getList("id", Long.class)` の抽出先の違いも確認する。

### 9. 作成の中心ケースと Location の再取得を読む

`create_multipleItems_commitsAndCanBeReadFromLocation()` を読む。  
注文・明細が空、在庫 `10 / 20`、小計 `11000`・総数量 `4` を送信する Stub、という Arrange を先に確認する。

`201` と Header / JSON、独立した金額・明細期待値、直接 SELECT、在庫 `8 / 18`、送料の受信記録を対応させる。  
Location の GET 後も DB 不変・全受信 1 件となる点と、`@RepeatedTest(2)` の各回で前提が再準備される点を見る。

### 10. 別の正常値と在庫境界を読む

`create_zeroShippingFee_preservesPostalCodeAndUndiscountedAmount()` では、郵便番号 `0600001` と送料 0 が、外向き JSON・Response・DB に保持されることを追う。  
中心ケースと異なる値により、送信内容の固定化を検出できることを確認する。

`create_quantityEqualsStock_commitsZeroStock()` では、在庫と同数の 10 個が成功し、在庫 0 が Commit されることを見る。  
小計 `30000`・割引 `3000`・合計 `27800` の根拠を仕様から確かめる。

### 11. 詳細の Snapshot・複数明細・日時を読む

`findById_returnsHistoricalProductSnapshot()` は、注文 `91001` の古い名称・単価を返すことを確認する。  
現在の商品の名称・単価を期待値にしていない理由を読む。

`findById_returnsAllItemsAndCancelledTimestamp()` は、`91003` の複数明細と `91005` のキャンセル日時を 1 Method 内で読む。  
これは 2 Request を送る 1 Test であり、展開件数は 1 件である。

### 12. 条件表と実 SQL の結果を対応させる

`searchConditions()` の 10 データ行と `search_returnsFilteredOrderedSummaries(...)` を対応させる。  
入力条件、期待 ID 列、Summary の field、DB 不変、送料 0 件を順に確認する。

`search_emptyDatabase_returnsEmptyArray()` と、Fixture があるが条件に一致しないケースを比較する。  
どちらも 200 の空配列だが、開始状態が異なる。

### 13. 検索入力の失敗と不存在を読む

`invalidQueries()` と `search_invalidCondition_returnsValidationError(...)` を読む。  
未定義 status・不正日時は Parameter 変換、期間逆転は実 Repository の整合性チェックで失敗する。

`findById_missingOrder_returns404WithoutSideEffects()` では、実 Repository の未検出が `ORDER_NOT_FOUND` に変換される経路を確認する。  
既存注文を含む DB 全体を保持し、送料を呼ばないことも見る。

### 14. 作成から確定・発送までの接続を読む

`lifecycle_createConfirmShip_preservesItemsAmountsAndStocks()` を読む。  
作成した ID と明細 ID を引き継ぎ、各操作後の GET と DB 比較で、状態以外の金額・明細・在庫を保持することを確認する。

補助の `assertReadableAs(...)` は、GET が更新 Response と同じ表現を返し、DB を変更しないことを観測する。  
最後の送料 verification がシナリオ全体の正確な 1 回を保証することを読む。

### 15. キャンセルと、正当な先行 Commit の保持を読む

`cancellableOrders()` と `cancel_allowedStatus_commitsTimestampAndRestoresOnlyTargetStock(...)` は、2 状態それぞれから独立して開始する。  
Response、対象ヘッダ、他注文・全明細、在庫 `6 / 16`、GET、送料 0 件を対応させる。

`cancel_twice_preservesFirstCommittedCancellation()` は、1 回目の成功後に Snapshot を採り、2 回目の拒否後と比較する。  
エラー時に最初の Fixture へ戻すのが正しい、という誤った期待を持たせない。

### 16. 禁止された遷移と不存在更新を読む

`invalidTransitions()` の再確定・直接発送・発送済み cancel・キャンセル済み cancel と、`transition_invalidStatus_preservesDatabase(...)` を読む。  
例外そのものではなく、HTTP 409・Error JSON・3 テーブル不変を観測している。

`transition_missingOrder_returns404WithoutSideEffects(...)` は、confirm / ship / cancel の 3 endpoint を同じ不存在条件で比較する。  
GET の不存在とは別に、更新入口の接続も確認する。

### 17. Validation・JSON 読み取り・Business Error を読む

`invalidRequests()` と `create_invalidRequest_returnsFieldErrorWithoutSideEffects(...)` の 10 入力を読む。  
続いて `unreadableRequests()` / `create_unreadableJson_returnsRequestError(...)` で、JSON 変換失敗との違いを確認する。

`invalidBusinessOrders()` と `create_businessError_preservesDatabaseAndSkipsShipping(...)` では、入力 DTO として有効な Request が、実商品の条件で拒否されることを追う。  
いずれも既存注文を含む Snapshot と受信 0 件を確認するが、拒否する責務は異なる。

### 18. 実送料障害と 502 の変換を読む

`shippingFailures()` と `create_shippingFailure_returns502AndPreservesDatabase(...)` を読む。  
Client の例外を `thenThrow` で作らず、WireMock の Response から実際に発生させる点を確認する。

API が返す固定の公開 message、内部情報を含まないこと、DB 不変、期待 Request 1 件を対応させる。  
Unit 08 の cause の検証と、Unit 09 の公開 API の保証を区別する。

### 19. Assertion と後始末の責務を読む

`assertOrder(...)` / `assertItem(...)` の独立期待値、`assertSavedOrder(...)` の保存行との照合、`assertOrderTransition(...)` / `assertStocks(...)` の許可した変更だけの比較を読む。  
元の Snapshot を直接書き換えず、期待状態用の Map をコピーしていることを確認する。

最後に `verifyNoUnmatchedShippingRequests()`、`cleanup.sql`、`verifyCleanupAfterClass(...)` を読む。  
Test 本体の業務 Assertion、HTTP Fixture の検査、行データの削除、リソースの停止を、それぞれ別の役割として整理する。

## 実行・確認

### 1. 環境と起動対象を確認する

リポジトリルートで Java・Maven・Docker Engine を確認する。  
Maven Wrapper の初回利用では配布版の取得が必要になり得る。

```bash
java -version
./mvnw --version
docker info
```

今回の Test は、Web Server・WireMock・テスト専用 PostgreSQL と、その DB の更新・Cleanup を伴う。  
通常用 Compose、永続 DB、別ターミナルのアプリ起動は前提にしない。

### 2. 新規クラスを個別に実行する

初回取得が必要な環境では `-o` を付けずに実行する。  
各コマンドは個別の Maven / テスト JVM で実行され、起動コストもそれぞれ発生する。

```bash
./mvnw "-Dtest=OrderCreateApiTest" test
./mvnw "-Dtest=OrderQueryApiTest" test
./mvnw "-Dtest=OrderLifecycleApiTest" test
./mvnw "-Dtest=OrderErrorApiTest" test
```

| クラス                | 展開後件数 | 見るべき結果                                             |
| --------------------- | ---------: | -------------------------------------------------------- |
| OrderCreateApiTest    |          4 | 作成・Location・DB・在庫・送料 Request、繰り返しの独立性 |
| OrderQueryApiTest     |         18 | 詳細・ID 順序・条件・空配列・入力失敗・不存在            |
| OrderLifecycleApiTest |         11 | 各 Commit、復元する在庫、先行 Commit の保持、拒否        |
| OrderErrorApiTest     |         20 | 公開 Error JSON、既存 DB 不変、送料 0 回 / 1 回          |

Status や JSON は主に Assertion で検証し、通常の成功ログへすべての Request / Response を表示する構成ではない。  
何を観測したかは Test 本体と共通 helper の Assertion を対応させて読む。

### 3. Unit 09 を一括実行する

```bash
./mvnw "-Dtest=OrderCreateApiTest,OrderQueryApiTest,OrderLifecycleApiTest,OrderErrorApiTest" test
```

合計 53 件が対象となる。  
同じ設定のクラスが Context を再利用しても、各 Test の SQL・reset・後始末が独立して働くことを確認する。

クラスの実行順や、動的ポート・採番値を固定して読む必要はない。  
各ケースが自分の前提から成功することを確認する。

### 4. 特定 Method を読みながら実行する

```bash
./mvnw "-Dtest=OrderCreateApiTest#create_multipleItems_commitsAndCanBeReadFromLocation" test
./mvnw "-Dtest=OrderQueryApiTest#search_returnsFilteredOrderedSummaries" test
./mvnw "-Dtest=OrderLifecycleApiTest#cancel_allowedStatus_commitsTimestampAndRestoresOnlyTargetStock" test
./mvnw "-Dtest=OrderErrorApiTest#create_shippingFailure_returns502AndPreservesDatabase" test
```

それぞれ 2 回の繰り返し、10 条件、2 状態、3 障害が展開される。  
Method を指定したことと、単一 invocation だけを実行することは同じではない。

### 5. 既存 Unit と境界を比較する

```bash
./mvnw "-Dtest=OrderControllerWebMvcTest" test
./mvnw "-Dtest=OrderServiceCreateIntegrationTest" test
./mvnw "-Dtest=HttpShippingFeeClientTest,OrderServiceShippingFeeHttpTest" test
```

Unit 05 は実 Web Server・DB を起動しない。  
Unit 07 は実 DB を使うが、HTTP 入口と実送料 HTTP を通さない。
Unit 08 は実外向き HTTP を通すが、アプリの HTTP 入口・実 DB は含まない。

同じ「注文作成」「送料失敗」という題材でも、入口・実依存・観測対象が異なることを確認する。

### 6. 既存 Test を含む全体回帰を実行する

```bash
./mvnw test
```

Unit 09 の第1回時点では、新規 53 件と既存 167 件を含む全 220 件である。  
Unit 06 の DB Test、Unit 07 の Service / DB Test、Unit 08 の外部 HTTP Test も実行する。

新規テストが単独で成功したことと、既存 Test と同じ classpath・実行環境で成功したことを分けて確認する。  
後続 Unit でテストが追加された場合は、全体件数も変化する。

### 7. 取得済み環境では offline 実行を使う

```bash
./mvnw -o "-Dtest=OrderCreateApiTest,OrderQueryApiTest,OrderLifecycleApiTest,OrderErrorApiTest" test
./mvnw -o test
```

依存・Plugin・Maven 配布版が未取得の場合、offline では実行できない。  
必要な取得を行った後で使用し、Docker Engine と取得済み Image、ローカル通信が必要な点は変わらない。

### 8. コンパイル確認と実 API Test の成功を区別する

```bash
./mvnw -o -DskipTests test-compile
```

このコマンドはコンパイル確認用であり、REST Assured の送信、Server の処理、DB 更新、WireMock の受信検証は実行しない。  
コンパイル成功を、実 HTTP 入口から DB・WireMock までの成功と扱わない。

PowerShell では Wrapper の名前を次のように読み替え、`-D` の長い引数やドットを含む引数は引用符で囲む。

```powershell
.\mvnw.cmd -o "-Dtest=OrderCreateApiTest,OrderQueryApiTest,OrderLifecycleApiTest,OrderErrorApiTest" test
.\mvnw.cmd -o test
```

### 9. Report・DB・外部 Request の結果を読む

Maven の末尾では、対象範囲に対応する件数と `Failures / Errors / Skipped` を見る。  
例えば Unit 09 一括実行の成功時は、次の形式になる。

```text
Tests run: 53, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

失敗の詳細とクラス別の結果は、実行で生成された `target/surefire-reports/` を確認する。  
Parameterized / Repeated Test のケース名と Assertion の失敗箇所を対応させる。

DB は自動で Cleanup されるため、終了後に通常用 DB を開いて Test の保存行を探す手順にはしない。  
今回の業務結果は Test 内の Cleanup 前の SELECT と Assertion、外部 Request は WireMock の記録と verification で観測している。

全クラスの `verifyCleanupAfterClass(...)` が成功したことは、各クラス最後の Cleanup 後に 3 テーブルが空だった確認を含む。  
これは業務結果の正しさとは別の、後始末の確認である。

### 10. 失敗した段階から原因を切り分ける

| 症状・失敗箇所                    | 確認する境界・前提                                         |
| --------------------------------- | ---------------------------------------------------------- |
| Maven の依存解決に失敗            | 通信許可、初回取得、offline のキャッシュ不足、引数の引用符 |
| Docker / Container の起動に失敗   | Engine の利用可否、Docker 接続権限、Image の取得状態       |
| Context の構築に失敗              | Bean・Auto Configuration、動的 property、Client の生成順序 |
| DataSource・Base URL の確認が失敗 | 専用 Container / WireMock の接続情報、通常用設定への依存   |
| `@Sql` が失敗                     | Schema、FK 順序、Script の MERGE、重複行、専用 DB          |
| 接続拒否・timeout                 | Web Server / WireMock の起動と存続、実 port、loopback 通信 |
| 201 / 200 ではなく 400            | Request JSON、Content-Type、DTO 型・制約、Parameter 形式   |
| 期待した業務エラーでなく 502      | 意図しない送料呼び出し、正常 Stub の matching、送信値      |
| WireMock の未一致記録・件数不一致 | Method・Path・Header・JSON、余分な送信、reset              |
| JSON・金額・Location が不一致     | 公開 DTO、独立期待値、動的 origin / ID の対応              |
| DB 最終状態が不一致               | Service の更新順序、Transaction、SQL、Fixture の現在在庫   |
| クラス終了時の空 DB 検査が失敗    | AFTER_TEST_METHOD の Cleanup、追加 Script の上書き         |

### 11. 後始末を確認する

行データは各 Test の SQL、最後の空 DB 検査までを確認する。  
起動リソースは Context / テスト JVM の終了に合わせて停止し、Testcontainers が一時 Container を破棄する構成である。

実行後に Container の残存を調べる場合は、読み取り専用の一覧を使う。

```bash
docker ps
docker ps -a --filter label=org.testcontainers=true
```

他のサービスの Container が存在しても、今回の残存と判断して削除しない。  
通常用 Compose の停止・Volume 削除を、API Test の後始末として実行する必要はない。

## 学習ポイント

### 広い Test の価値は接続部分の観測にある

Controller、Service、Repository、Client がそれぞれ正しくても、渡す値や例外の意味が接続時に崩れる場合がある。  
今回の API Test は、実際の公開入口から保存・外部通信までを通し、その接続部分を観測する。

### HTTP の成功だけで業務結果を判断しない

Status は公開契約の一部である。  
注文・明細・在庫・状態・日時の保存と、外部へ送った Request も確認して、何が成功したかを具体化する。

```text
公開する結果: Status / Header / JSON
保存した結果: orders / order_items / products
外へ伝えた値: Shipping Fee Request と送信回数
```

### 期待値を実装から独立させる

Production の DTO・計算結果・検索結果を、そのまま期待値の生成にも使うと、同じ誤りを見逃し得る。  
Fixture と業務仕様から期待を定め、Response と DB の照合をその補強として用いる。

### 実依存と Test Double を責務から選ぶ

実 HTTP Client を通すことと、実外部サービスへ接続することは別である。  
実装の通信・変換を確認したい境界は本物にし、制御したい外部 Response は WireMock で用意する。

### Transaction・観測・Cleanup の順序を明確にする

Test と Server のスレッド・Transaction を区別し、Commit 済み Fixture を渡す。  
業務処理後・Cleanup 前に結果を観測し、後始末で業務の不備を隠さない。

### 共有する起動リソースと分離する Test Data を分ける

Context と Server / Container は再利用しても、行データ・Stub・Journal・Request 条件は持ち越さない。  
存続期間と初期化単位を別々に設計することが、実行コストと Test Isolation の両立につながる。

### 失敗の意味と保証範囲を説明する

書き込み前の拒否、途中更新の Rollback、Cleanup は異なる出来事である。  
どの証拠を観測したかを言葉にし、実施していない故障条件や CI の成功へ一般化しない。

## 補足

### API Test の公開値と Test 内の直接 DB 参照

API 利用者は通常、DB を直接参照しない。  
今回の Test は学習用の観測手段として DB を読み、公開 Response だけでは見えない不完全な保存や在庫の副作用を確認している。

直接 DB 参照には Schema との結び付きも生じる。  
実務では、公開 API の互換性を重視する Test と、保存構造を詳しく観測する Test の目的を分けて配置する判断も必要になる。

### Response の field 数を固定する Assertion

現在の helper は注文 10 field、明細 6 field、Summary 9 field、Error 3 field を確認する。  
公開 JSON の現在の形を明確に検証する一方、互換性のある field 追加でも Test の見直しが必要になる。

追加 field を許すか、完全な形を固定するかは API の互換性方針による。  
今回の helper の選択を、すべての API に共通する唯一の正解として一般化しない。

### JDBC の日時型と Snapshot のコピー

アプリの業務日時は Instant、今回の `JdbcTemplate.queryForMap` / `queryForList` の観測値は Timestamp として扱う。  
比較時は `Timestamp.from(...)` や `toInstant()` を使い、同じ時点を異なる表現で保持していることを意識する。

`DatabaseState` が record でも、内部の List / Map まで深く不変になるわけではない。  
状態遷移・在庫の期待 Map はコピーして作り、処理前の証拠を直接書き換えない。

### readOnly と副作用の観測

検索用 Service の `@Transactional(readOnly = true)` と、Test が DB 不変を観測することは別の事柄である。  
Annotation だけから「すべての更新が必ず拒否される」と判断せず、今回確認した最終状態を根拠として説明する。

### 仕様書の例示と現在の実装

仕様書の Location は相対 URI の例だが、現 Controller は Request を基準に絶対 URI を生成する。  
また、仕様書の OrderResponse 例には掲載明細の合計と小計が一致しない箇所があるため、作成 Test の期待金額へ転記していない。

一覧の同時刻の ID 降順、詳細・一覧の field、field error の実際の message は、確定した実装と Fixture に対応させている。  
仕様書や既存 Unit の例示を黙って書き換えず、この Unit が確認する実際の振る舞いとして読む。

### この Unit の先にある実行管理・CI

Unit 10 は、ここまで蓄積した Test Suite の命名・選択・Surefire / Failsafe・Coverage などを扱う。  
今回の Test は既存の `test` 実行で発見される `*Test` とし、Failsafe や Coverage の設定を先行導入していない。

Unit 11 では CI 上で同じ環境を再現し、Job・Log・Report などを確認する。  
この Unit のローカル成功を、その環境での成功として扱わない。
