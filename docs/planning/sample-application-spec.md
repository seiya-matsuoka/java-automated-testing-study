# サンプルアプリケーション仕様

## 1. このドキュメントの目的

このドキュメントは、`java-automated-testing-study` で使用するメインアプリケーションの要件・設計・仕様を定義する。  
今回の学習では、1 つの Spring Boot 製注文管理 REST API を全 Unit で共通利用し、同じ Production Code を異なるテスト境界から繰り返し確認する。  
そのため、単なる CRUD サンプルではなく、JUnit、Mockito、Spring Test、DB Integration Test、WireMock、REST Assured、Coverage、CI までの学習に必要な業務ルール、条件分岐、依存関係、DB 操作、外部 HTTP 通信を自然に含むアプリケーションとする。

このアプリケーション自体の開発を学習の主目的とはしない。  
一方で、テスト対象となる Production Code の動作や責務を正しく理解できるよう、Java / Spring Boot / SQL / HTTP / Transaction などの実装内容には十分な日本語コメントを記載する。

## 2. アプリケーションの概要

商品と在庫を管理し、複数の商品を含む注文の作成・取得・状態変更を行う REST API とする。  
注文作成時には、商品状態、注文数量、在庫を検証し、商品小計と割引金額を計算する。  
送料は外部の Shipping Fee API から取得し、最終金額を算出する。  
注文情報と注文明細を PostgreSQL に保存し、注文作成時には在庫を減算し、キャンセル時には在庫を戻す。

このアプリケーションは、実在する EC サービスを忠実に再現することを目的としない。  
業務仕様を理解する負担を抑えつつ、バックエンドの自動テスト学習に必要な分岐・境界・依存関係を自然に持つことを優先する。

## 3. アプリケーションの目的

サンプルアプリケーションは、次の学習を成立させることを目的とする。

- Spring を起動しない Unit Test の対象となる、純粋な Java の業務ロジックを持つ。
- 境界値分析、同値分割、Decision Table、Test Matrix に利用できる明確な業務ルールを持つ。
- Mockito を利用できる複数の依存関係を Service 層に持つ。
- Spring Web 層を切り出して `@WebMvcTest` / MockMvc で確認できる。
- 明示的な SQL を持つ Repository を、PostgreSQL に対してテストできる。
- Service、Repository、DB を組み合わせた Integration Test を行える。
- 外部 HTTP API 依存を持ち、WireMock を用いたテストを行える。
- 起動した REST API を REST Assured からテストできる。
- Coverage の確認に十分な条件分岐や状態遷移を持つ。
- GitHub Actions 上で Unit Test、Integration Test、Coverage を自動実行できる。

## 4. 対象範囲

今回のメインアプリケーションでは、次の機能を実装する。

- 商品一覧取得
- 商品詳細取得
- 注文作成
- 注文詳細取得
- 注文一覧取得・条件検索
- 注文確定
- 注文発送
- 注文キャンセル
- 商品状態・在庫確認
- 注文数量の検証
- 注文金額計算
- 割引計算
- 送料取得
- 注文・注文明細の永続化
- 注文作成時の在庫減算
- 注文キャンセル時の在庫復元
- 注文状態遷移
- 業務例外から HTTP Error Response への変換

## 5. 対象外

学習範囲を必要以上に広げないため、次の機能・技術はメインアプリには導入しない。

- ユーザー登録
- ログイン
- 認証
- 認可
- Spring Security
- Customer 管理
- 商品登録・更新・削除 API
- 決済処理
- 実際の配送処理
- Frontend
- React / JavaScript / TypeScript
- 非同期処理
- Message Queue
- Cache
- Batch
- JPA / Hibernate
- MyBatis
- Flyway
- Liquibase
- 実在する外部 API
- Performance / Load Test 用機能
- Pagination
- 分散 Transaction

対象外の要素を必要以上に追加せず、自動テスト学習に必要な構造へ集中する。

## 6. 技術構成

サンプルアプリケーションでは、次の技術を使用する。

- Java
- Spring Boot
- Spring Web
- Bean Validation
- Spring JDBC
- PostgreSQL
- Maven
- Docker Desktop
- Docker Compose
- Testcontainers
- WireMock
- REST Assured
- JUnit
- AssertJ
- Mockito
- JaCoCo
- GitHub Actions

具体的なバージョン番号は、実際にリポジトリを構築する時点で互換性を確認して決定する。

## 7. 実行環境

### 通常のアプリケーション実行

Spring Boot アプリケーションは、Windows 上のローカル JVM で実行する。  
PostgreSQL は Docker Compose で起動する。

```text
Windows
├─ VS Code
├─ Java / JDK
├─ Maven
├─ Spring Boot Application
│   └─ ローカル JVM で実行
└─ Docker Desktop
    └─ PostgreSQL
```

### 自動テスト時

自動テストでは、テストの種類に応じて必要な環境のみを起動する。

```text
Unit Test
└─ Spring Boot / PostgreSQL / Docker は不要

Web Slice Test
└─ Spring Web 層のみを構成

Repository / DB Test
└─ Testcontainers が PostgreSQL を起動

Spring Boot Integration Test
├─ Spring Context
└─ Testcontainers PostgreSQL

External HTTP API Test
└─ WireMock Server

REST API Test
├─ Spring Boot Test Web Server
├─ Testcontainers PostgreSQL
└─ WireMock Server
```

通常の動作確認用 PostgreSQL と、自動テスト用 PostgreSQL は分離する。  
自動テストから通常利用中の開発用 DB を直接更新しない。

## 8. アーキテクチャ

一般的な Layered Architecture を採用する。

```text
Controller
    ↓
Service
    ↓
Repository
    ↓
PostgreSQL

Service
    ↓
Client
    ↓
External Shipping Fee API
```

DDD や Clean Architecture などを新たな学習テーマにはしない。  
Controller、Service、Repository、External Client の責務を分離し、通常の保守しやすい構造にした結果としてテストしやすい設計になることを目指す。

## 9. Package 構成

ルート package は、次を基本とする。

```text
com.example.orderapi
```

想定する package / class 構成は次のとおりとする。

```text
com.example.orderapi
├─ OrderManagementApplication
│
├─ config
│  ├─ ClockConfig
│  └─ RestClientConfig
│
├─ controller
│  ├─ ProductController
│  └─ OrderController
│
├─ dto
│  ├─ product
│  │  └─ ProductResponse
│  ├─ order
│  │  ├─ CreateOrderRequest
│  │  ├─ CreateOrderItemRequest
│  │  ├─ OrderResponse
│  │  ├─ OrderItemResponse
│  │  └─ OrderSummaryResponse
│  └─ error
│     ├─ ApiErrorResponse
│     └─ FieldErrorResponse
│
├─ domain
│  ├─ Product
│  ├─ Order
│  ├─ OrderItem
│  ├─ OrderStatus
│  └─ PriceSummary
│
├─ service
│  ├─ ProductService
│  ├─ OrderService
│  ├─ OrderPriceCalculator
│  └─ model
│     ├─ CreateOrderCommand
│     └─ CreateOrderItemCommand
│
├─ repository
│  ├─ ProductRepository
│  ├─ OrderRepository
│  └─ jdbc
│     ├─ JdbcProductRepository
│     └─ JdbcOrderRepository
│
├─ client
│  ├─ ShippingFeeClient
│  ├─ HttpShippingFeeClient
│  └─ dto
│     ├─ ShippingFeeRequest
│     └─ ShippingFeeResponse
│
└─ exception
   ├─ ProductNotFoundException
   ├─ OrderNotFoundException
   ├─ ProductInactiveException
   ├─ InsufficientStockException
   ├─ InvalidOrderStatusTransitionException
   ├─ DuplicateOrderItemException
   ├─ ShippingFeeServiceException
   └─ GlobalExceptionHandler
```

実装時に責務分離上必要な小さな補助クラスが追加されることは許容する。  
ただし、テストのためだけに不自然な抽象化や interface を大量に増やさない。

## 10. Lombok の扱い

Lombok は使用しない。  
今回の学習ではコードリーディングを重視するため、constructor、field、依存関係などが Java コードとして明示的に見える状態を優先する。

## 11. Domain Model

### 11.1 Product

`Product` は、商品情報と注文可否の判断に必要な情報を表す。

| 項目            | Java 型      | 内容         |
| --------------- | ------------ | ------------ |
| `id`            | `Long`       | 商品 ID      |
| `name`          | `String`     | 商品名       |
| `price`         | `BigDecimal` | 商品単価     |
| `stockQuantity` | `int`        | 現在在庫数   |
| `active`        | `boolean`    | 販売可能状態 |

商品登録・更新・削除は今回の機能対象外とする。

### 11.2 OrderItem

`OrderItem` は、注文時点の商品情報と数量を保持する。

| 項目          | Java 型      | 内容               |
| ------------- | ------------ | ------------------ |
| `id`          | `Long`       | 注文明細 ID        |
| `orderId`     | `Long`       | 注文 ID            |
| `productId`   | `Long`       | 商品 ID            |
| `productName` | `String`     | 注文時点の商品名   |
| `unitPrice`   | `BigDecimal` | 注文時点の商品単価 |
| `quantity`    | `int`        | 注文数量           |
| `lineAmount`  | `BigDecimal` | 明細金額           |

`productName` と `unitPrice` は注文時点の snapshot として保持する。  
Product の価格や名称が後から変わっても、過去の注文内容には影響させない。

### 11.3 Order

`Order` は注文全体の状態、配送先、金額、明細、日時を表す。

| 項目                 | Java 型            | 内容           |
| -------------------- | ------------------ | -------------- |
| `id`                 | `Long`             | 注文 ID        |
| `status`             | `OrderStatus`      | 注文状態       |
| `shippingPostalCode` | `String`           | 配送先郵便番号 |
| `items`              | `List<OrderItem>`  | 注文明細       |
| `subtotal`           | `BigDecimal`       | 商品小計       |
| `discountAmount`     | `BigDecimal`       | 割引額         |
| `shippingFee`        | `BigDecimal`       | 送料           |
| `totalAmount`        | `BigDecimal`       | 合計金額       |
| `createdAt`          | `Instant`          | 注文作成日時   |
| `cancelledAt`        | `Instant` / `null` | キャンセル日時 |

金額は日本円を想定し、`BigDecimal` を使用する。  
小数円は扱わず、割引計算などで 1 円未満が発生した場合は切り捨てる。

### 11.4 PriceSummary

`PriceSummary` は、金額計算結果をまとめるための値として使用する。

想定する内容は次のとおりとする。

- `subtotal`
- `discountAmount`
- `shippingFee`
- `totalAmount`

## 12. OrderStatus

注文状態は次の 4 種類とする。

```text
CREATED
CONFIRMED
SHIPPED
CANCELLED
```

注文作成直後は必ず `CREATED` とする。

状態遷移は次のとおりとする。

| 現在状態    | confirm     | ship      | cancel      |
| ----------- | ----------- | --------- | ----------- |
| `CREATED`   | `CONFIRMED` | 不可      | `CANCELLED` |
| `CONFIRMED` | 不可        | `SHIPPED` | `CANCELLED` |
| `SHIPPED`   | 不可        | 不可      | 不可        |
| `CANCELLED` | 不可        | 不可      | 不可        |

許可されていない状態遷移では、`InvalidOrderStatusTransitionException` を発生させる。

この状態遷移表は、Decision Table、Test Matrix、Parameterized Test、Branch Coverage の教材として利用する。

## 13. 注文数量ルール

1 商品あたりの注文数量は、次の範囲とする。

```text
1 <= quantity <= 99
```

代表的な境界値は次のとおりとする。

```text
0
1
2
98
99
100
```

`0` 以下または `100` 以上は不正値とする。  
この境界は Bean Validation と Domain / Service のテスト教材として利用する。

## 14. 注文明細ルール

注文は最低 1 件の明細を持つ必要がある。  
同一注文内で、同じ `productId` を複数明細に分けて指定することは禁止する。

不正例:

```json
{
  "items": [
    {
      "productId": 1,
      "quantity": 2
    },
    {
      "productId": 1,
      "quantity": 3
    }
  ]
}
```

重複商品が指定された場合は、`DuplicateOrderItemException` を発生させる。

## 15. 商品の注文可否

商品を注文するためには、次の条件をすべて満たす必要がある。

```text
Product が存在する
AND
active = true
AND
stockQuantity >= requestedQuantity
```

代表的なケースは次のとおりとする。

| 条件             | 結果 |
| ---------------- | ---- |
| 商品が存在しない | 失敗 |
| `active = false` | 失敗 |
| 在庫が 0         | 失敗 |
| 在庫 < 注文数量  | 失敗 |
| 在庫 = 注文数量  | 成功 |
| 在庫 > 注文数量  | 成功 |

商品が存在しない場合は `ProductNotFoundException` を発生させる。  
販売停止の場合は `ProductInactiveException` を発生させる。  
在庫不足の場合は `InsufficientStockException` を発生させる。

## 16. 金額計算

### 16.1 明細金額

```text
lineAmount = unitPrice × quantity
```

### 16.2 商品小計

```text
subtotal = 全 OrderItem の lineAmount 合計
```

### 16.3 割引ルール

商品小計が 10,000 円未満の場合、割引は行わない。  
商品小計が 10,000 円以上の場合、商品小計の 10% を割引する。

| subtotal    | 割引 |
| ----------- | ---- |
| `< 10,000`  | 0    |
| `>= 10,000` | 10%  |

代表的な境界値は次のとおりとする。

```text
9,999
10,000
10,001
```

割引額は次の式で求める。

```text
discountAmount = subtotal × 0.10
```

1 円未満の端数は切り捨てる。

### 16.4 合計金額

```text
totalAmount
= subtotal
- discountAmount
+ shippingFee
```

金額計算処理は、Spring、Repository、HTTP に依存しない `OrderPriceCalculator` に集約する。  
Unit 01～02 の主要なテスト対象として利用する。

## 17. 在庫更新

注文作成成功時には、各商品の在庫を注文数量分減算する。

```text
stockQuantity
-= orderedQuantity
```

注文キャンセル成功時には、注文時に減算した数量を商品在庫へ戻す。

```text
stockQuantity
+= orderedQuantity
```

例:

```text
注文前在庫 10
↓
3 個注文
↓
在庫 7
↓
キャンセル
↓
在庫 10
```

注文作成・キャンセルでは、Order と Product の複数状態が同時に変化するため、Integration Test と Transaction Test の教材として利用する。

## 18. 注文作成ユースケース

注文作成を、このサンプルアプリの中心ユースケースとする。

概念的な処理フローは次のとおりとする。

```text
注文リクエスト受信
↓
リクエスト Validation
↓
重複商品チェック
↓
各商品の取得
↓
商品存在チェック
↓
販売状態チェック
↓
数量・在庫チェック
↓
明細金額計算
↓
小計計算
↓
割引計算
↓
Shipping Fee API 呼び出し
↓
送料取得
↓
合計金額計算
↓
Order 保存
↓
OrderItem 保存
↓
Product 在庫更新
↓
注文結果返却
```

この 1 ユースケースを、Unit Test、Mockito、DB Integration Test、Spring Boot Integration Test、WireMock、REST Assured など異なるテスト境界から繰り返し利用する。

## 19. 注文確定

注文確定では、次の状態遷移のみを許可する。

```text
CREATED
↓
CONFIRMED
```

それ以外の状態からの確定は許可しない。

## 20. 注文発送

注文発送では、次の状態遷移のみを許可する。

```text
CONFIRMED
↓
SHIPPED
```

それ以外の状態からの発送は許可しない。

## 21. 注文キャンセル

注文キャンセルは、次の状態からのみ許可する。

```text
CREATED
↓
CANCELLED

CONFIRMED
↓
CANCELLED
```

キャンセル成功時には、次の処理を行う。

- OrderStatus を `CANCELLED` に更新する。
- `cancelledAt` を設定する。
- 各 OrderItem の数量分を Product の在庫へ戻す。

`SHIPPED` または `CANCELLED` の注文はキャンセルできない。

## 22. 日時と Clock

アプリケーション内で現在時刻を利用する処理は、`Clock` を依存として利用できる構造とする。

Production 環境では UTC の system clock を利用する。  
テストでは fixed clock を注入できるようにする。

業務コード内で `Instant.now()` を直接繰り返し呼び出す構造にはしない。  
これにより、Testability、Deterministic Test、Repeatable Test の教材として利用する。

保存する時刻は UTC の `Instant` を基準とする。

## 23. Repository 構成

Repository 層は interface と JDBC 実装を分離する。

```text
ProductRepository
└─ JdbcProductRepository

OrderRepository
└─ JdbcOrderRepository
```

Service は Repository interface に依存する。  
DB アクセス実装は Spring JDBC を使用する。

## 24. DB アクセス

DB アクセスでは、Spring Data JPA の自動生成 CRUD を中心にせず、`NamedParameterJdbcTemplate` を用いて SQL を明示的に記述する。

基本構成は次のとおりとする。

```text
Repository
↓
NamedParameterJdbcTemplate
↓
明示 SQL
↓
PostgreSQL
```

SQL の内容が Repository Test の対象として明確に見える構成にする。

## 25. Repository Interface

### 25.1 ProductRepository

主な操作は次のとおりとする。

- `findAll()`
- `findById(id)`
- `updateStock(productId, newStockQuantity)`

必要に応じて、複数商品を効率よく取得するための Repository 操作を追加してよい。

### 25.2 OrderRepository

主な操作は次のとおりとする。

- Order の INSERT
- OrderItem の INSERT
- Order の ID 検索
- OrderItem の Order ID 検索
- Order の条件検索
- OrderStatus の更新

実際の method 名は、Java / Spring の一般的な命名規則に沿って決定する。

## 26. SQL の範囲

Repository 実装では、少なくとも次の SQL を自然に含める。

- INSERT
- UPDATE
- SELECT
- JOIN
- 複数条件 WHERE
- ORDER BY

注文一覧検索では、任意条件によって WHERE 句を組み立てる。  
必要性が自然に生じる場合は簡単な集約処理を追加してよい。

一方、次のような高度な SQL は今回の学習目的のためだけに追加しない。

- Window Function
- Recursive Query
- 複雑な CTE
- Stored Procedure

## 27. DB Schema

DB は PostgreSQL を使用し、次の 3 table を基本とする。

```text
products
orders
order_items
```

## 28. products table

| Column           | PostgreSQL 型   | 制約                   |
| ---------------- | --------------- | ---------------------- |
| `id`             | `BIGINT`        | Primary Key / Identity |
| `name`           | `VARCHAR(100)`  | NOT NULL               |
| `price`          | `NUMERIC(12,0)` | NOT NULL / `> 0`       |
| `stock_quantity` | `INTEGER`       | NOT NULL / `>= 0`      |
| `active`         | `BOOLEAN`       | NOT NULL               |

価格は日本円を想定し、小数部を持たない。

## 29. orders table

| Column                 | PostgreSQL 型   | 制約                   |
| ---------------------- | --------------- | ---------------------- |
| `id`                   | `BIGINT`        | Primary Key / Identity |
| `status`               | `VARCHAR(20)`   | NOT NULL               |
| `shipping_postal_code` | `VARCHAR(7)`    | NOT NULL               |
| `subtotal`             | `NUMERIC(12,0)` | NOT NULL / `>= 0`      |
| `discount_amount`      | `NUMERIC(12,0)` | NOT NULL / `>= 0`      |
| `shipping_fee`         | `NUMERIC(12,0)` | NOT NULL / `>= 0`      |
| `total_amount`         | `NUMERIC(12,0)` | NOT NULL / `>= 0`      |
| `created_at`           | `TIMESTAMPTZ`   | NOT NULL               |
| `cancelled_at`         | `TIMESTAMPTZ`   | NULL 可                |

`status` には次の値以外が保存されないよう、DB constraint を設定する。

```text
CREATED
CONFIRMED
SHIPPED
CANCELLED
```

## 30. order_items table

| Column         | PostgreSQL 型   | 制約                   |
| -------------- | --------------- | ---------------------- |
| `id`           | `BIGINT`        | Primary Key / Identity |
| `order_id`     | `BIGINT`        | FK / NOT NULL          |
| `product_id`   | `BIGINT`        | FK / NOT NULL          |
| `product_name` | `VARCHAR(100)`  | NOT NULL               |
| `unit_price`   | `NUMERIC(12,0)` | NOT NULL / `> 0`       |
| `quantity`     | `INTEGER`       | NOT NULL / 1～99       |
| `line_amount`  | `NUMERIC(12,0)` | NOT NULL / `>= 0`      |

次の UNIQUE constraint を設定する。

```text
UNIQUE(order_id, product_id)
```

アプリケーション側だけでなく、DB 側でも同一注文内の商品重複を防ぐ。

## 31. Table Relation

table relation は次のとおりとする。

```text
orders
  1
  ↓
  N
order_items

products
  1
  ↓
  N
order_items
```

`order_items.order_id` は `orders.id` を参照する。  
`order_items.product_id` は `products.id` を参照する。

## 32. Index

最低限、次の index を用意する。

```text
orders(status, created_at)
order_items(order_id)
```

大量データ向け性能設計を学ぶことが目的ではなく、検索条件・並び順に対して不自然ではない DB 設計とすることを目的とする。

## 33. Transaction

### 33.1 注文作成

次の DB 更新を 1 transaction とする。

```text
Order INSERT
↓
OrderItem INSERT
↓
Product 在庫 UPDATE
```

途中で DB 操作が失敗した場合は、すべて rollback する。

### 33.2 注文キャンセル

次の DB 更新を 1 transaction とする。

```text
Order status UPDATE
↓
cancelledAt UPDATE
↓
Product 在庫 UPDATE
```

途中で失敗した場合は、すべて rollback する。

外部 Shipping Fee API は DB 更新前の料金照会のみを行い、副作用を持つ外部処理にはしない。  
分散 Transaction を扱う構成にはしない。

## 34. REST API 一覧

| Method  | Endpoint                   | 内容               |
| ------- | -------------------------- | ------------------ |
| `GET`   | `/api/products`            | 商品一覧取得       |
| `GET`   | `/api/products/{id}`       | 商品詳細取得       |
| `POST`  | `/api/orders`              | 注文作成           |
| `GET`   | `/api/orders/{id}`         | 注文詳細取得       |
| `GET`   | `/api/orders`              | 注文一覧・条件検索 |
| `PATCH` | `/api/orders/{id}/confirm` | 注文確定           |
| `PATCH` | `/api/orders/{id}/ship`    | 注文発送           |
| `PATCH` | `/api/orders/{id}/cancel`  | 注文キャンセル     |

## 35. 商品一覧 API

```http
GET /api/products
```

Response 例:

```json
[
  {
    "id": 1,
    "name": "Standard Product",
    "price": 3000,
    "stockQuantity": 100,
    "active": true
  }
]
```

正常時は `200 OK` を返す。

## 36. 商品詳細 API

```http
GET /api/products/{id}
```

存在する場合は `200 OK` を返す。  
存在しない場合は `404 Not Found` と `PRODUCT_NOT_FOUND` を返す。

## 37. 注文作成 API

```http
POST /api/orders
```

Request 例:

```json
{
  "shippingPostalCode": "1000001",
  "items": [
    {
      "productId": 1,
      "quantity": 2
    },
    {
      "productId": 2,
      "quantity": 1
    }
  ]
}
```

Validation は次のとおりとする。

### shippingPostalCode

- 必須
- 7 桁の数字
- ハイフンなし

### items

- 必須
- 1 件以上

### productId

- 必須
- 正の値

### quantity

- 必須
- 1～99

成功時は `201 Created` を返す。  
さらに、作成した注文への `Location` header を返す。

```http
Location: /api/orders/{id}
```

## 38. OrderResponse

注文作成、注文詳細取得、状態変更後の response は、基本的に共通の `OrderResponse` を利用する。

Response 例:

```json
{
  "id": 100,
  "status": "CREATED",
  "shippingPostalCode": "1000001",
  "items": [
    {
      "productId": 1,
      "productName": "Standard Product",
      "unitPrice": 3000,
      "quantity": 2,
      "lineAmount": 6000
    }
  ],
  "subtotal": 12000,
  "discountAmount": 1200,
  "shippingFee": 800,
  "totalAmount": 11600,
  "createdAt": "2026-09-28T12:00:00Z",
  "cancelledAt": null
}
```

HTTP Test で status、header、JSON field、collection、金額、状態、日時などを十分に検証できる形式とする。

## 39. 注文詳細 API

```http
GET /api/orders/{id}
```

存在する場合は `200 OK` と `OrderResponse` を返す。  
存在しない場合は `404 Not Found` と `ORDER_NOT_FOUND` を返す。

## 40. 注文一覧 API

```http
GET /api/orders
```

次の query parameter を任意指定できる。

- `status`
- `createdFrom`
- `createdTo`

例:

```http
GET /api/orders?status=CREATED
```

並び順は次に固定する。

```text
created_at DESC
```

Pagination は実装しない。

## 41. 注文確定 API

```http
PATCH /api/orders/{id}/confirm
```

`CREATED` の注文のみ成功する。  
成功時は `200 OK` と更新後の `OrderResponse` を返す。

## 42. 注文発送 API

```http
PATCH /api/orders/{id}/ship
```

`CONFIRMED` の注文のみ成功する。  
成功時は `200 OK` と更新後の `OrderResponse` を返す。

## 43. 注文キャンセル API

```http
PATCH /api/orders/{id}/cancel
```

`CREATED` または `CONFIRMED` の注文のみ成功する。  
成功時は `200 OK` と更新後の `OrderResponse` を返す。

キャンセル成功時には、OrderStatus、`cancelledAt`、Product 在庫を更新する。

## 44. External Shipping Fee API

アプリケーションは送料取得のため、外部 Shipping Fee API に依存する。

Service は `ShippingFeeClient` interface に依存し、HTTP 実装は `HttpShippingFeeClient` とする。

```text
OrderService
↓
ShippingFeeClient
↓
HttpShippingFeeClient
↓
External Shipping Fee API
```

実在する外部 API は用意せず、テスト時は WireMock で再現する。

## 45. Shipping Fee API Contract

概念上、次の API を呼び出す。

```http
POST /shipping-fees/calculate
```

Request 例:

```json
{
  "postalCode": "1000001",
  "subtotal": 12000,
  "totalQuantity": 3
}
```

Response 例:

```json
{
  "shippingFee": 800
}
```

WireMock では、少なくとも次の状態を再現できるようにする。

- `200 OK` + 正常 JSON
- `500 Internal Server Error`
- `200 OK` + 不正 JSON
- `200 OK` + 必須 field が欠落した response

Shipping Fee API 呼び出し失敗時は、`ShippingFeeServiceException` として扱う。

## 46. 業務例外

主な業務例外は次のとおりとする。

- `ProductNotFoundException`
- `OrderNotFoundException`
- `ProductInactiveException`
- `InsufficientStockException`
- `InvalidOrderStatusTransitionException`
- `DuplicateOrderItemException`
- `ShippingFeeServiceException`

必要に応じて Validation 用の標準例外や想定外例外も `GlobalExceptionHandler` で扱う。

## 47. HTTP Error Mapping

| ケース                | Error Code                        | HTTP Status                 |
| --------------------- | --------------------------------- | --------------------------- |
| request validation    | `VALIDATION_ERROR`                | `400 Bad Request`           |
| 重複商品              | `DUPLICATE_ORDER_ITEM`            | `400 Bad Request`           |
| Product なし          | `PRODUCT_NOT_FOUND`               | `404 Not Found`             |
| Order なし            | `ORDER_NOT_FOUND`                 | `404 Not Found`             |
| 販売停止商品          | `PRODUCT_INACTIVE`                | `409 Conflict`              |
| 在庫不足              | `INSUFFICIENT_STOCK`              | `409 Conflict`              |
| 不正な状態遷移        | `INVALID_ORDER_STATUS_TRANSITION` | `409 Conflict`              |
| Shipping Fee API 障害 | `SHIPPING_FEE_SERVICE_ERROR`      | `502 Bad Gateway`           |
| 想定外エラー          | `INTERNAL_SERVER_ERROR`           | `500 Internal Server Error` |

## 48. ApiErrorResponse

通常の業務エラーは、共通形式で返す。

例:

```json
{
  "code": "INSUFFICIENT_STOCK",
  "message": "Requested quantity exceeds available stock."
}
```

Error Response に現在時刻は含めない。  
テスト上不要な非決定要素を増やさないためである。

## 49. Validation Error Response

Validation Error では、field 単位の情報を含める。

例:

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed.",
  "fieldErrors": [
    {
      "field": "items[0].quantity",
      "message": "must be between 1 and 99"
    }
  ]
}
```

これにより MockMvc / REST Assured で status だけでなく error body の検証も行える。

## 50. 初期データ

通常のアプリケーション動作確認用として、性質の異なる商品データを用意する。

少なくとも次のような性質を持たせる。

- 一般的な通常商品
- 10,000 円の割引境界を作りやすい商品
- 在庫 1 の商品
- 在庫 99 の商品
- 在庫 0 の商品
- `active = false` の販売停止商品

具体的な商品名そのものは重要ではなく、テストしやすい条件を作れることを優先する。

## 51. 通常用データとテストデータの分離

通常起動用の初期データと、自動テスト用の Test Data / Fixture は分離する。

通常起動用データは、アプリの手動動作確認のために使用する。  
自動テストでは、各テストの前提として必要なデータを明示的に準備する。

自動テストが通常起動用の初期データへ暗黙に依存しない構成とする。

## 52. DB Script

アプリケーションには、少なくとも次の SQL Script を用意する。

- Schema 作成用 SQL
- 通常確認用の初期データ SQL

テスト用 SQL や Fixture が必要な場合は、`src/test/resources/` 配下へ分離する。

## 53. Testability 方針

Production Code は、次の方針に沿って実装する。

- Constructor Injection を使用する。
- Repository を依存境界として分離する。
- External HTTP Client を依存境界として分離する。
- `Clock` を injection する。
- static mutable state を持たない。
- random behavior を導入しない。
- business logic を Controller に書かない。
- SQL を Service に直接書かない。
- external URL を hard-code しない。
- Testability のためだけに不自然な interface を大量に増やさない。

通常の責務分離と依存関係の整理を行った結果として、テストしやすい構造になることを目指す。

## 54. Production Code のコメント方針

Production Code には、Java / Spring Boot / SQL / HTTP / Transaction などの理解を助ける日本語コメントを十分に記載する。

特に次の内容を説明する。

- クラスの責務
- method の責務
- Spring annotation の意味
- Bean の役割
- Dependency Injection
- Controller / Service / Repository / Client の役割
- DTO と Domain Model の違い
- 業務ルール
- 処理フロー
- Transaction Boundary
- `NamedParameterJdbcTemplate` の役割
- SQL の目的
- Row Mapping
- External HTTP Client
- `Clock`
- Testability に関係する設計意図
- 初見では理解しづらい Java の処理

一方で、単純な変数代入や getter 呼び出しなど、コードを見れば明らかな処理を日本語で読み上げるだけのコメントは避ける。

## 55. Unit とサンプルアプリの対応

| Unit | 主に利用するアプリ要素                                                               |
| ---- | ------------------------------------------------------------------------------------ |
| 01   | `OrderPriceCalculator`、Domain の単純な判定                                          |
| 02   | 数量 1～99、10,000 円割引境界、OrderStatus 状態遷移                                  |
| 03   | `OrderService`、`ProductRepository`、`OrderRepository`、`ShippingFeeClient`、`Clock` |
| 04   | `Clock`、Constructor Injection、依存関係、Testability                                |
| 05   | `OrderController`、Validation、`GlobalExceptionHandler`、Error Response              |
| 06   | `JdbcProductRepository`、`JdbcOrderRepository`、明示 SQL、PostgreSQL                 |
| 07   | Order / OrderItem 保存、在庫更新、Transaction、Rollback                              |
| 08   | `ShippingFeeClient`、`HttpShippingFeeClient`、Shipping Fee API                       |
| 09   | REST API 全体、Spring Boot、PostgreSQL、WireMock                                     |
| 10   | Production Code と Unit 01～09 のテストスイート全体                                  |
| 11   | Maven、Testcontainers、Coverage、GitHub Actions を含むプロジェクト全体               |

この対応を基準として、アプリ実装時に各 Unit の学習対象が不足していないことを確認する。

## 56. 実装時の確認ポイント

メインアプリ実装完了時には、少なくとも次の状態を満たしていることを確認する。

- Spring Boot アプリケーションがローカル JVM で起動できる。
- Docker Compose の PostgreSQL へ接続できる。
- Product API が動作する。
- Order 作成が動作する。
- Order 作成時に在庫が減算される。
- 10,000 円境界の割引が正しく適用される。
- Shipping Fee API との通信構造が成立している。
- Order の confirm / ship / cancel が状態遷移ルールに従う。
- cancel 時に在庫が復元される。
- Repository が明示 SQL で動作する。
- Transaction が注文作成・キャンセルへ適用される。
- Validation と Error Response が定義どおり動作する。
- `Clock` が dependency として利用されている。
- Unit 01～11 で必要となるテスト対象が Production Code 上に存在する。

## 57. サンプルアプリケーション仕様のまとめ

このサンプルアプリケーションは、Spring Boot + Spring Web + Bean Validation + Spring JDBC + PostgreSQL + Maven を使用する注文管理 REST API とする。  
DB アクセスは `NamedParameterJdbcTemplate` と明示 SQL で実装し、`products`、`orders`、`order_items` の 3 table を中心に構成する。

注文作成では、商品存在・販売状態・数量・在庫を検証し、金額計算、割引計算、外部 Shipping Fee API からの送料取得、Order / OrderItem の保存、在庫更新を行う。  
Order は `CREATED`、`CONFIRMED`、`SHIPPED`、`CANCELLED` の状態を持ち、明確な状態遷移ルールに従う。  
キャンセル時には在庫を復元し、注文作成・キャンセルの DB 更新は Transaction で一貫性を保つ。

Repository と External HTTP Client を依存境界として分離し、`Clock` を注入可能にすることで、Unit Test、Mockito、Spring Web Test、Repository Test、DB Integration Test、WireMock、REST Assured などを、同じ Production Code に対して異なる境界から学習できる構造とする。  
この仕様を、`java-automated-testing-study` の全 Unit で使用するメインアプリケーションの実装基準とする。
