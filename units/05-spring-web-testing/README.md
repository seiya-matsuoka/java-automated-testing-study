# 05. Spring Web 層のテスト

## この Unit の目的

この Unit では、Spring MVC が担当する HTTP 境界を、Service・Repository・DB などの業務処理や外部依存から切り離してテストする方法を学ぶ。  
Unit 01～04 では主に Java Object を直接実行する Unit Test を扱ったが、Unit 05 では Spring Context のうち Web 層に必要な部分を起動し、MockMvc で模擬 HTTP Request を送り、その結果を検証する。

対象はメインアプリの `OrderController` である。注文作成・詳細取得・確定・発送・キャンセルの5つの主要 API に加え、既存の注文一覧 API を用いて query parameter の型変換も扱う。  
Controller の責務である Request Mapping、JSON と DTO の変換、Bean Validation、HTTP status / header、Response JSON、共通 Exception Handler の振る舞いを中心に確認する。

重要なのは、HTTP 経由の形式でテストしているからといって、Application 全体を起動する必要はないという点である。  
**今回の Test が保証したい Web 層の振る舞いと、Service が保証する業務ロジックの境界**を区別し、必要な Spring Component だけを使う Test Slice の考え方を身に付ける。

## 学習内容

### Unit 04 から Unit 05 への変化

Unit 04 では Dependency Injection、Testability、Isolation、Deterministic な Test など、テストを成立させる設計と品質を学んだ。  
Unit 05 ではその考え方を Spring MVC に適用する。テスト対象は `OrderService` の計算や状態遷移ではなく、HTTP Request を受け取って業務 Service に渡し、結果を HTTP Response へ変換する Web 層となる。

```text
Unit 03～04:
Test Code → OrderService / その他の Java Object
              └─ 依存先を Test Double などで制御

Unit 05:
MockMvc（模擬 HTTP Request）
    ↓
Spring MVC / DispatcherServlet
    ↓
OrderController / Request DTO / Validation / Advice
    ↓
@MockitoBean OrderService（SUT の外側）
    ↓
MockMvc（HTTP status / header / JSON の検証）
```

Spring MVC 自体が Request を振り分け、Parameter を変換し、Validation や Exception Handler を起動することが、この Unit の新しい学習対象となる。

### Spring Test

Spring Test は、Spring Framework の Component をテストするための基盤を提供する。  
単に JUnit から Java Object を生成するだけでは確認しにくい、Spring の DI、Application Context、MVC の Request Handling などをテストに組み込める。

Unit 05 では `org.springframework.test.web.servlet.MockMvc` など、Spring MVC の Test API を使用する。  
MockMvc は実際の TCP Port へ HTTP Client を接続するのではなく、Spring MVC の処理系へ模擬 Request を渡し、HTTP の結果を検証できる仕組みとなる。

Spring Test はアプリケーションの機能を何でも自動的に保証するものではない。テストでどの構成を起動し、どの結果を Assert するかによって、保証する範囲が決まる。

### Spring Boot Test の位置づけ

Spring Boot Test は、Spring Boot のアプリケーション構成や Auto Configuration と連携したテストを支援する。  
Spring Framework の Test 機能を土台として、`@SpringBootTest` や各種 Slice Test Annotation、Test Starter など、Spring Boot の構成に合わせた仕組みを提供する。

```text
JUnit Jupiter
  └─ Test Method の実行、Lifecycle、結果の管理

Spring Test
  └─ TestContext、DI、MockMvc などのテスト基盤

Spring Boot Test
  └─ Spring Boot の Auto Configuration / Test Slice との連携

Mockito
  └─ SUT の外側にある依存先の stubbing / verification
```

Unit 05 では Spring Boot 4.1.1 の `spring-boot-starter-webmvc-test` を Test Scope で利用し、Web MVC Test に必要な仕組みを導入する。  
`@SpringBootTest` による Application 全体の Integration Test は、今回の中心ではない。

### Spring Context

Spring Context は、Spring が管理する Bean の生成・依存関係の組み立て・設定などを扱う実行環境である。  
`@Autowired` は、その Context に存在する Bean を Test 側へ注入する場合にも利用できる。

Plain Unit Test では、例えば次のように SUT を直接生成していた。

```java
OrderService orderService = new OrderService(
        productRepository,
        orderRepository,
        shippingFeeClient,
        clock);
```

一方、Unit 05 の Web MVC Test では Spring が `OrderController` や MVC Component を管理するため、Test Class から次のように MockMvc を取得できる。

```java
@Autowired
private MockMvc mockMvc;
```

Spring Context を起動する Test では、Bean の構成や DI が正しく成立することが実行の前提になる。  
そのぶん Plain Unit Test より起動コストが増えるが、Spring MVC の実際の振る舞いを検証できる。

### Plain Unit Test との違い

Plain Unit Test と Web MVC Slice Test は、どちらも自動テストであるが、動かすものが異なる。

| 観点               | Unit 03 などの Plain Unit Test    | Unit 05 の Web MVC Slice Test                      |
| ------------------ | --------------------------------- | -------------------------------------------------- |
| 主な SUT           | `OrderService` などの Java Object | `OrderController` を中心とする Web 層              |
| Spring Context     | 起動しない                        | Web 層に必要な部分を起動する                       |
| SUT の実行方法     | Java Method を直接呼び出す        | MockMvc で模擬 HTTP Request を渡す                 |
| 依存先             | Mockito の `@Mock` など           | Spring Bean を `@MockitoBean` で置換               |
| 主な Assert        | 戻り値・状態・interaction         | HTTP status・header・JSON・Validation・interaction |
| DB / 外部 HTTP API | 利用しない                        | 利用しない                                         |

どちらが上位・下位という単純な関係ではない。  
Service 自身の業務ロジックを確認したいときは Plain Unit Test、HTTP 契約や Spring MVC の変換処理を確認したいときは Web MVC Slice Test が適している。

### Test Slice

Test Slice は、Application 全体ではなく特定の Layer・責務に関係する Bean と設定を取り出してテストする考え方である。  
Unit 05 は Web 層の Slice を使うため、Controller や Controller Advice、MVC / JSON / Validation の処理に集中する。

```text
Application 全体:
Controller → Service → Repository → PostgreSQL
                       └─ Client → External API

Unit 05 で動かす範囲:
[Spring MVC → Controller → HTTP Response]
                    ↓
              [Mock Service]

動かさない範囲:
Service の実処理 / JDBC / PostgreSQL / 外部 HTTP 通信
```

Test Slice は「できるだけ小さくすれば必ずよい」という規則ではない。  
今回確認したい HTTP 入出力や Validation が成立するだけの本物の Component を含み、その外側は制御可能にするという選択となる。

### Spring Context をどこまで起動するか

Spring Context の範囲は、何を SUT として保証したいかから判断する。  
Controller Method を Java から直接呼び出すだけでは、HTTP Mapping、JSON deserialization、Validation、Exception Handler などの実際の MVC 処理は通らない。

一方、毎回 Application 全体を起動すると、今回確認したい Web 層とは無関係な DB 接続や外部 API Client などが、Test Setup や失敗原因に影響する可能性がある。  
Unit 05 はその中間として MVC に必要な Context を起動する。

```text
何を保証したいか？
    ↓
HTTP Request / Response と MVC の挙動
    ↓
@WebMvcTest による Web Slice
    ↓
Service は @MockitoBean で置き換える
```

この判断は、Unit 03 で学んだ Test Boundary、Unit 04 で学んだ Testability と直接つながる。

### `@WebMvcTest`

`@WebMvcTest` は Spring MVC のテストに必要な構成を中心に Auto Configuration する Spring Boot の Annotation である。  
Unit 05 の4つの Test Class では同じ指定を利用している。

```java
@WebMvcTest(controllers = OrderController.class)
```

`controllers` には対象 Controller を明示する。今回のテスト対象は `OrderController` であり、`ProductController` を同時にテストするものではない。  
この Slice では `@RestControllerAdvice` である `GlobalExceptionHandler` も Web 層の Component として取り扱われる。

通常の `@Service` や `@Repository` の Bean をアプリケーション全体と同じように読み込む構成ではないため、Controller が必要とする `OrderService` を Mock Bean として与える。

### MockMvc

MockMvc は、Spring MVC の HTTP Request / Response の処理を、実 HTTP Server を起動せずに検証する API である。  
Unit 05 の基本形は次となる。

```java
mockMvc
    .perform(get("/api/orders/{id}", ORDER_ID))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.id").value(100));
```

`perform(...)` で模擬 Request を実行し、`andExpect(...)` で Response の期待条件を確認する。  
`get(...)`、`post(...)`、`patch(...)` などの Request Builder は、HTTP Method・URI・Header・Body などを指定するために使用する。

```text
perform(...)
  → Request を MVC へ送る

andExpect(...)
  → Status / Header / Body などを確認

andReturn()
  → 実行結果を取得し、必要なら追加の検証に利用
```

HTTP の形式でテストしていても、通常の Test 実行では外部の HTTP Port に Listener を立ち上げない。この違いを認識しておく。

### Controller Test

Controller Test は、Controller が受け取る Request と返却する Response の振る舞いを確認する。  
`OrderController` の主な責務は、HTTP Endpoint の提供、Request DTO の受け取り、Service Command への変換、Service の呼び出し、Domain Object から Response DTO への変換である。

例えば注文作成は次の流れとなる。

```text
POST /api/orders（JSON）
    ↓
CreateOrderRequest（JSON の読み取り）
    ↓
Bean Validation
    ↓
CreateOrderRequest.toCommand()
    ↓
OrderService.createOrder(...) ← Mock
    ↓
OrderResponse.from(...)
    ↓
201 Created + Location + JSON
```

Unit 05 ではこの境界を検証する。一方、Service の中で在庫をどのように減らすか、DB Transaction が成功するかといった処理は対象外となる。

### Service を Mock にする理由

`OrderController` は constructor で `OrderService` を受け取る。  
Web 層のテストで Service の実装まで動かすと、Repository や Shipping Fee Client など、今回の目的とは異なる依存先まで構成することになりやすい。

Unit 05 では Spring Context 内の `OrderService` Bean を Mockito の Mock に置き換える。

```java
@MockitoBean
private OrderService orderService;
```

そのうえで、HTTP Response の確認に必要な戻り値や Exception だけを stubbing する。

```java
when(orderService.findById(ORDER_ID))
        .thenReturn(order(OrderStatus.CONFIRMED));
```

ここで Mock にしているのは、**今回の SUT が Controller / Web 層であるから**であって、Service が Test 不要だからではない。Service 自身の業務判断は既存の Unit Test や後続の Integration Test で確認する。

### `@Mock` と `@MockitoBean` の違い

Unit 03 で利用した Mockito の `@Mock` は、Test Class の field として Mock を作成する。  
Unit 05 で使用する Spring Test の `@MockitoBean` は、Spring Test Context で対象 Bean を Mockito Mock に置き換える／登録する。

```text
@Mock（Unit 03）:
Mock を Test 側に作り、@InjectMocks などで SUT を組み立てる

@MockitoBean（Unit 05）:
Spring Context 内の Controller が参照する Bean を Mock にする
```

`@WebMvcTest` で管理する Controller に対して、Test Class の field に `@Mock` を付けるだけでは、Spring Context の Service 依存を満たしたことにはならない。  
ここでは Spring による DI と Mock の関係まで含めて確認する。

### HTTP Request と Request Mapping

HTTP Request には Method、URI、Header、Body などの情報がある。  
Spring MVC は `@PostMapping`、`@GetMapping`、`@PatchMapping` などを用いて、Request を適切な Controller Method へ振り分ける。

Unit 05 の主要 Endpoint は次となる。

| HTTP Method | Path                       | Controller Method | 成功時の主な結果             |
| ----------- | -------------------------- | ----------------- | ---------------------------- |
| POST        | `/api/orders`              | `create`          | 201、Location、注文詳細 JSON |
| GET         | `/api/orders/{id}`         | `findById`        | 200、注文詳細 JSON           |
| PATCH       | `/api/orders/{id}/confirm` | `confirm`         | 200、`CONFIRMED`             |
| PATCH       | `/api/orders/{id}/ship`    | `ship`            | 200、`SHIPPED`               |
| PATCH       | `/api/orders/{id}/cancel`  | `cancel`          | 200、`CANCELLED`             |
| GET         | `/api/orders`              | `search`          | 200、注文概要の JSON Array   |

`{id}` は URI の一部から `@PathVariable long id` へ変換される。  
一覧 API では `@RequestParam` によって query parameter が Java 型へ変換される。

### Request Body

Request Body は、HTTP Request に含まれるデータ本体である。  
`POST /api/orders` では JSON を `CreateOrderRequest` へ読み取り、Bean Validation を行った後、Service 用の `CreateOrderCommand` に変換する。

```json
{
  "shippingPostalCode": "1234567",
  "items": [{ "productId": 10, "quantity": 2 }]
}
```

Test Code では次のように Request の Content-Type と Body を与える。

```java
post("/api/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .content(validCreateRequestJson())
```

`@RequestBody` は Request Body を Java Object として受け取るための指定である。  
JSON が壊れていて Object として読み取れない場合と、読み取れた Object が Bean Validation に違反する場合は、失敗する段階が異なる。

### Response Body

Response Body は、Controller が Client に返す HTTP Response のデータ本体である。  
今回のアプリでは `OrderResponse`、`OrderSummaryResponse`、`ApiErrorResponse` などが JSON として返される。

```text
注文詳細:
Order → OrderResponse → JSON Object

注文一覧:
List<Order> → List<OrderSummaryResponse> → JSON Array

エラー:
Exception → GlobalExceptionHandler → ApiErrorResponse → JSON Object
```

`OrderResponse` には明細 `items` を含む。一方 `OrderSummaryResponse` は一覧表示向けの DTO であり、`items` を含まない。  
Unit 05 では JSON の項目が正しく変換されているかだけでなく、不要な項目を一覧へ返していないことも確認する。

### HTTP status

HTTP status は、Request の処理結果を HTTP の規則で表す。  
Unit 05 では正常系の 200 / 201 に加えて、Validation や業務例外に対応する 400 / 404 / 409 / 502 を扱う。

| Status                    | 意味                   | この Unit での例                                                   |
| ------------------------- | ---------------------- | ------------------------------------------------------------------ |
| 200 OK                    | 処理成功               | 注文取得・状態更新・一覧検索                                       |
| 201 Created               | Resource 作成成功      | 注文作成                                                           |
| 400 Bad Request           | Request が不正         | Bean Validation、JSON 読み取り不可、重複明細、不正 query parameter |
| 404 Not Found             | 対象が存在しない       | 注文不存在、商品不存在                                             |
| 409 Conflict              | 現在の状態などと衝突   | 状態遷移不可、在庫不足、販売停止                                   |
| 502 Bad Gateway           | 下位サービスの利用失敗 | Shipping Fee Service の失敗                                        |
| 500 Internal Server Error | 想定外の Server Error  | 未処理の内部例外（補助的な確認）                                   |

status だけを見ても、たとえば 400 が Bean Validation と明細重複のどちらなのかは区別できない。  
そのため、今回の API では HTTP status と独自の `code`、`message` などを組み合わせて確認する。

### HTTP header

HTTP Header は、Content-Type や Location など、Response Body 以外の情報を運ぶ。  
注文作成では新しい Resource の URI を `Location` Header へ格納し、201 Created を返す。

```java
.andExpect(status().isCreated())
.andExpect(header().string(HttpHeaders.LOCATION, endsWith("/api/orders/100")))
.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
```

ここでは `Location` の末尾に作成注文の Path が含まれることを確認している。  
今回のコードは Host・Scheme 全体を固定する検証ではない。どの単位を API Contract として保証しているかを Assertion から読み取る。

### JSON

JSON は HTTP API で Request / Response を表現する一般的なデータ形式である。  
Java の `Order` や `OrderResponse` と、HTTP 上の JSON は同じものではない。Spring MVC の Message Conversion / JSON Serialization によって変換される。

```text
Java Object → JSON serialization → Response Body
Request Body → JSON deserialization → Request DTO
```

今回の Test では、数値や Enum、日時、`null` に関係する JSON を確認する。  
例えば `OrderStatus.CONFIRMED` が `"CONFIRMED"` として表現され、`Instant` が ISO-8601 の文字列になることを確認している。

JSON Test は Java Object のフィールド値だけを確認する Test とは異なり、外部へ公開される形を確認できる点が重要となる。

### JSONPath

JSONPath は、JSON Document 内の特定の値や Array 要素を Path で指定する方法である。  
Unit 05 の Test は MockMvc の `jsonPath(...)` を利用する。

```java
.andExpect(jsonPath("$.id").value(100))
.andExpect(jsonPath("$.status").value("CREATED"))
.andExpect(jsonPath("$.items[0].productName").value("Keyboard"))
```

代表的な記法は次となる。

```text
$                  → JSON の root
$.id               → Object の id
$.items[0]         → items Array の最初の要素
$.fieldErrors[*]   → fieldErrors Array の各要素
$[0].status        → root Array の最初の要素の status
```

`jsonPath` は重要な Field を個別に検証したい場合に読みやすい。  
一方、Document 全体の項目構成や余分な Field の有無までまとめて確認したい場合には、JSONassert などとの使い分けができる。

### JSONassert

JSONassert は、JSON Document 同士を JSON として比較する Library である。  
単純な文字列一致ではなく JSON の構造をもとに比較するため、Whitespace や Field の記述順の違いに過剰に依存しない。

Unit 05 では JSONAssert の2つの比較モードを使っている。

| Mode                      | 主な確認の仕方                                             | 実際の使用箇所                        |
| ------------------------- | ---------------------------------------------------------- | ------------------------------------- |
| `JSONCompareMode.LENIENT` | 期待する Field が存在すれば、追加 Field などを許容する比較 | 注文作成の正常系                      |
| `JSONCompareMode.STRICT`  | 期待する Document と Field・Array 内容を厳密に比較         | 送料サービス失敗の 502 Error Response |

```java
JSONAssert.assertEquals(
        """
        {"id":100,"status":"CREATED","items":[{"productId":10,"quantity":2}]}
        """,
        result.getResponse().getContentAsString(),
        JSONCompareMode.LENIENT);
```

`LENIENT` による部分的な確認は、必要な Field だけを読む場面に適するが、余分な Field の混入を防ぐ検証にはならない。  
`STRICT` では追加 Field も不一致となるため、Error Response に内部情報が混入していないかを確認する目的と対応している。厳密さを強くすること自体ではなく、契約上どこまで固定したいかで選ぶ。

### Bean Validation

Bean Validation は、DTO に定義した制約に基づいて入力値を検証する仕組みである。  
今回の `CreateOrderRequest` は `jakarta.validation` の Annotation を利用している。

```java
@NotBlank(message = "shippingPostalCode is required")
@Pattern(regexp = "^[0-9]{7}$", message = "shippingPostalCode must be 7 digits")
String shippingPostalCode
```

また、明細 List とその要素にも制約を設けている。

```java
@NotEmpty(message = "items must not be empty")
List<@NotNull(message = "item must not be null")
     @Valid CreateOrderItemRequest> items
```

`CreateOrderItemRequest` の `productId` は `@NotNull` と `@Positive`、`quantity` は `@NotNull` と `@Min(1)` / `@Max(99)` で制約している。  
これらは HTTP 入力を受け付けてよいかの境界であり、在庫や状態遷移などの業務ルールを Service に代わってすべて判断するものではない。

### `@Valid` と入れ子の Validation

`OrderController.create` の `@Valid @RequestBody CreateOrderRequest` は、読み取った Request DTO を Validation の対象にする。  
さらに `CreateOrderRequest.items` の `@Valid` によって、List 内の `CreateOrderItemRequest` に定義した制約まで確認する。

```text
CreateOrderRequest
├─ shippingPostalCode
│  ├─ @NotBlank
│  └─ @Pattern（7 桁）
└─ items
   ├─ @NotEmpty
   └─ List 要素
      ├─ @NotNull（null 要素を拒否）
      └─ @Valid
         ├─ productId: @NotNull / @Positive
         └─ quantity: @NotNull / @Min(1) / @Max(99)
```

Unit 05 の Test には `items:[null]` も含め、List 内の `null` を見逃さないようにしている。  
ここで確認するのは Annotation の存在だけでなく、実際に MVC の Request Processing を通したときに HTTP 400 になることである。

### Validation Error

Request の検証に失敗すると、Controller は正常な Service 呼び出しへ進まず、Error Response を返す。  
今回の Bean Validation Test では、次をセットで確認する。

```text
HTTP status = 400
code        = VALIDATION_ERROR
message     = request validation failed
fieldErrors に対象 field が含まれる
OrderService は呼ばれない
```

```java
.andExpect(jsonPath("$.fieldErrors[*].field", hasItem(expectedField)));
verifyNoInteractions(orderService);
```

`fieldErrors` の順序や Error Message の全文ではなく、どの Field が誤っているかに注目している。  
一つの Request に複数の制約違反が発生する場合もあるため、期待 Field が含まれることを検証する意図となる。

### JSON 読み取りエラーと Validation 違反の違い

HTTP 400 には複数の経路がある。  
例えば壊れた JSON や DTO と型が合わない値は、Bean Validation の前に JSON の読み取り・型変換で失敗する。

```text
JSON として不正 / Java 型に変換できない
  → HttpMessageNotReadableException
  → VALIDATION_ERROR / request body is invalid
  → fieldErrors[0].field = "request"

DTO へ変換できたが制約に違反
  → MethodArgumentNotValidException
  → VALIDATION_ERROR / request validation failed
  → fieldErrors に該当する DTO Field 名
```

両者とも 400 だが、失敗した段階・Response の message が異なる。  
Unit 05 では `"items": [` で終わる壊れた JSON と、`"quantity":"abc"` という型不一致を別の Test Case として確認する。

### Query Parameter と型変換

注文一覧 API は、次の optional query parameter を受け取る。

```text
status      → OrderStatus enum
createdFrom → Instant
createdTo   → Instant
```

`@RequestParam(required = false)` によって、未指定の Parameter はこの Controller では `null` として Service に渡される。  
指定された `"CONFIRMED"` は Enum に、`"2026-10-01T00:00:00Z"` は `Instant` に変換される。

未定義の Enum や日時に変換できない文字列は、Service 呼び出し前の Parameter Binding で失敗する。  
一方、日時の前後関係のように、個々の Parameter は有効でも組み合わせが不適切という判断は今回 Service 側の責務として扱っている。

### Controller Advice

Controller Advice は、複数の Controller に共通する処理をまとめるための Spring MVC の仕組みである。  
今回の `GlobalExceptionHandler` は `@RestControllerAdvice` を付け、HTTP API 用の Exception Handling を共通化している。

例えば Service が `OrderNotFoundException` を発生させた場合、Controller Method ごとに `try/catch` で 404 を生成するのではなく、共通 Handler が Error Response を返す。

```text
OrderController.findById(...)
    ↓
OrderService.findById(...) ← Mock が Exception を返す
    ↓
GlobalExceptionHandler
    ↓
404 + ORDER_NOT_FOUND + message + fieldErrors
```

`@WebMvcTest` では、この Controller Advice も含めて Web 層の処理をテストできる点が重要となる。

### Exception Handler

`@ExceptionHandler` は、指定された Exception が発生した際にどの処理で HTTP Response へ変換するかを定義する。  
`GlobalExceptionHandler` には、Bean Validation、JSON 読み取り、Parameter 変換、業務例外、想定外の Exception に対応する Handler がある。

```java
@ExceptionHandler(OrderNotFoundException.class)
public ResponseEntity<ApiErrorResponse> handleOrderNotFound(
        OrderNotFoundException exception) {
    return error(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", exception.getMessage());
}
```

この Test で確認しているのは、業務例外が発生する条件そのものではなく、**その例外が HTTP status・公開 error code・message に正しく変換されるか**である。

### 400 Bad Request

400 Bad Request は Request を正常に受け付けられない場合の HTTP status である。  
Unit 05 では、原因に応じて異なる処理経路を確認する。

| 発生条件                          | Response code          | 主な message                     |
| --------------------------------- | ---------------------- | -------------------------------- |
| DTO の Bean Validation 違反       | `VALIDATION_ERROR`     | `request validation failed`      |
| 壊れた JSON / 型不一致            | `VALIDATION_ERROR`     | `request body is invalid`        |
| query parameter の型変換失敗      | `VALIDATION_ERROR`     | `request parameter is invalid`   |
| Service による Request 整合性違反 | `VALIDATION_ERROR`     | `request validation failed`      |
| 重複した注文明細                  | `DUPLICATE_ORDER_ITEM` | `duplicate product in order: 10` |

同じ 400 であっても、公開 `code` や `fieldErrors` の構造が異なり得る。  
HTTP status だけで Test を終わらせず、API Contract として必要な情報を併せて検証する。

### 404 Not Found

404 Not Found は対象 Resource が存在しない場合の HTTP status である。  
Unit 05 では `OrderNotFoundException` と `ProductNotFoundException` を別々に検証する。

```text
OrderNotFoundException
  → 404 / ORDER_NOT_FOUND

ProductNotFoundException
  → 404 / PRODUCT_NOT_FOUND
```

status は同じでも対象が異なるため `code` を分けている。  
Order の検索処理や Product の存在確認自体をこの Test で実行しているわけではなく、Service が返す Exception を Mock で再現する。

### 409 Conflict

409 Conflict は、Request 自体が JSON として読めても、現在の Resource や業務上の状態と衝突する場合などに利用される。  
今回の `GlobalExceptionHandler` では次を 409 に変換する。

```text
InvalidOrderStatusTransitionException
  → INVALID_ORDER_STATUS_TRANSITION

InsufficientStockException
  → INSUFFICIENT_STOCK

ProductInactiveException
  → PRODUCT_INACTIVE
```

状態遷移や在庫判断の正しさは Service / Domain の Test が担当する。  
Web Test では、すでに発生した業務上の結果を Client にどのように伝えるかに集中する。

### 502 Bad Gateway

502 Bad Gateway は、今回のアプリでは外部の Shipping Fee Service の利用失敗を HTTP API に表すために使用する。  
Service が `ShippingFeeServiceException` を発生させた場合、`GlobalExceptionHandler` は外部向けに次を返す。

```json
{
  "code": "SHIPPING_FEE_SERVICE_ERROR",
  "message": "shipping fee service is unavailable",
  "fieldErrors": []
}
```

例外に含まれる内部情報や上流サービスの詳細を Client へそのまま公開しないことも確認対象となる。  
Unit 05 では Mock に例外を設定するだけであり、実際の HTTP Client がどのような status を受けたかの Test ではない。外部 HTTP 通信のテストは後続 Unit で扱う。

### API error response

このアプリでは Error Response を `ApiErrorResponse` として統一している。

```text
ApiErrorResponse
├─ code       : API 固有の error code
├─ message    : Client に公開する説明
└─ fieldErrors: Field 単位の入力エラー
    ├─ field
    └─ message
```

Bean Validation などでは `fieldErrors` に対象 Field を設定し、通常の業務例外では空 List を返す。  
HTTP status と Error Response の責務は異なるため、両方を確認する必要がある。

`ApiErrorResponse` が JSON へ変換された公開形まで確認するのが Web 層の Test であり、Exception Object だけを検証する Plain Unit Test との差となる。

### State Verification と Interaction Verification の組み合わせ

Unit 03 で学んだ State Verification と Interaction Verification は Web 層のテストでも利用する。  
ただし確認する対象は異なる。

```text
HTTP Response の検証:
status / header / JSON
    → Client に何が返るか

Service Mock の検証:
verify(...) / verifyNoInteractions(...)
    → Controller が Service をどう呼んだか
```

正常な POST では、変換した `CreateOrderCommand` が Service に渡されたことを `verify(...)` で確認する。  
Validation 失敗時には `verifyNoInteractions(orderService)` により、業務処理に進まないことを保証する。

### Test Fixture と Parameterized Test の役割

Unit 05 では `OrderWebMvcTestFixture` に、Controller Response の検証で共通利用する `Order` と Request JSON を用意している。  
この Fixture は DB を経由せず `Order.restore(...)` で決定的な Object を生成し、Web 層の Test に必要なデータだけを準備する。

また Validation Test では、入力不備ごとに同じ検証を繰り返すため、Unit 02 で学んだ `@ParameterizedTest` と `@MethodSource` を利用する。

```text
入力不備ごとの Request JSON を用意
        ↓
MockMvc で同じ POST Endpoint を実行
        ↓
400 / VALIDATION_ERROR / fieldErrors を確認
        ↓
Service の interaction がないことを確認
```

Fixture や Parameterized Test は新しい Web Framework の機能ではないが、HTTP 境界のケースを読みやすく整理するために継続して活用する。

## 使用する技術・ツール

### Java 21

Production Code と Test Code の実装言語として利用する。  
Unit 05 の Fixture では Java の `record` に対応する Response DTO や、Text Block による Request JSON を扱う。

### Spring Boot 4.1.1 / Spring MVC

`OrderController` と Web 層を実行する Framework として利用する。  
今回の目的は Application 全体の起動ではなく、Spring MVC が担う Request Mapping・型変換・Validation・Response 生成などを検証することとなる。

### `spring-boot-starter-webmvc-test`

Unit 05 で `pom.xml` に追加した、Spring Boot 4 系の Spring MVC Test 用 Starter となる。

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-webmvc-test</artifactId>
  <scope>test</scope>
</dependency>
```

Spring Boot Test と Spring Test、MockMvc、JSONPath / JSONassert などの Web MVC Test 基盤を導入するために利用する。  
`test` scope の Dependency として追加しているため、学習用 Test の実行に必要な依存関係として扱う。

### `@WebMvcTest`

Spring MVC に必要な Context を構成し、Controller を中心とした Slice Test を実行する。  
今回の4つの Test Class すべてで `OrderController.class` を指定している。

### MockMvc / Spring Test

模擬 HTTP Request を実行する `MockMvc`、Request Builder、Response Matcher を利用する。  
`perform` で実行し、`status()`、`header()`、`content()`、`jsonPath()` などの Matcher で HTTP 契約を確認する。

### `@MockitoBean` / Mockito

Controller の依存先である `OrderService` を、Spring Test Context に登録された Mockito Mock として提供する。  
JUnit から直接実行した Service Test での `@Mock` / `@InjectMocks` と用途を比較する。

```java
@Autowired private MockMvc mockMvc;
@MockitoBean private OrderService orderService;
```

`when(...).thenReturn(...)` / `thenThrow(...)`、`verify(...)`、`verifyNoInteractions(...)` などの Mockito API は Unit 03 から引き続き利用する。

### JUnit Jupiter / Parameterized Test

`@Test`、`@DisplayName` で Test Method を定義する。  
Validation の複数ケースには `@ParameterizedTest` と `@MethodSource` / `Arguments` を利用し、入力不備ごとの HTTP 経路を繰り返して確認する。

### Jakarta Bean Validation

`@NotBlank`、`@Pattern`、`@NotEmpty`、`@NotNull`、`@Positive`、`@Min`、`@Max`、`@Valid` を Request DTO の制約として利用する。  
Web MVC Test では、Annotation の定義を読むだけでなく、MockMvc 経由で制約違反時の HTTP 400 を確認する。

### JSONPath / Hamcrest

JSON の Field や Array から必要な値を取り出して確認する。  
例えば `jsonPath("$.fieldErrors[*].field", hasItem(expectedField))` では、`fieldErrors` の中に期待する Field が存在することを検証する。

### JSONassert

JSON Document の構造を比較するために利用する。  
注文作成では `JSONCompareMode.LENIENT`、送料エラーでは `JSONCompareMode.STRICT` を使い、API Contract のどこまでを厳密に保証するかを比べる。

### Maven / Maven Wrapper

Test の Compile / Execution と Surefire Report の出力に利用する。

```bash
./mvnw test
```

Unit 05 の各 Class は `-Dtest=...` による単体指定でも実行できる。  
PostgreSQL・Docker Compose・Shipping Fee Stub Server・起動済み Spring Boot Application は不要となる。

## テスト対象とテスト境界

### Unit 05 の SUT

今回の SUT は `OrderController` を中心とする Spring Web 層である。  
HTTP Request を受け付け、Spring MVC で Binding / Validation を実行し、Controller を通じて結果または Exception を Response として返す部分を確認する。

```text
[Test Code / MockMvc]
        ↓
[Spring MVC / DispatcherServlet]
        ↓
[OrderController]
        ├─ CreateOrderRequest / CreateOrderItemRequest
        ├─ OrderResponse / OrderSummaryResponse
        └─ GlobalExceptionHandler / ApiErrorResponse
        ↓
[@MockitoBean OrderService]
        ×
[実 OrderService / Repository / DB / 外部 HTTP API]
```

ここで `×` は、下位の本物の処理へ進まない Test Boundary を表す。  
Web 層の正しい動作を確認するために Spring MVC を本物として実行し、Service 側の業務処理は Mock の応答で制御する。

### 本物として動かすもの

- Spring MVC の Request Mapping、Handler Method Argument Binding、HTTP Response 処理
- `OrderController` の実装
- `CreateOrderRequest` / `CreateOrderItemRequest` と Bean Validation
- Request DTO から `CreateOrderCommand` への変換
- `OrderResponse.from(...)` / `OrderSummaryResponse.from(...)` と JSON serialization
- `GlobalExceptionHandler` と `ApiErrorResponse` / `FieldErrorResponse`
- Spring Test Context と MockMvc

Test Fixture が返す `Order`、`OrderItem`、`PriceSummary` は本物の Domain Object である。ただし、注文作成や在庫更新などの業務操作を実行しているわけではない。

### Test Double として置き換えるもの

`OrderService` を `@MockitoBean` で置き換える。  
Mock は次の用途で利用する。

```text
成功時:
Service が返したことにした Order / List<Order>
      ↓
Controller が HTTP Response を生成

失敗時:
Service が業務例外を投げたことにする
      ↓
Controller Advice が HTTP Error Response を生成
```

`OrderService` の実際の業務処理、`ProductRepository` / `OrderRepository` の SQL、`ShippingFeeClient` の HTTP Request は、この Test では実行しない。

### 起動しないもの

```text
Spring Boot の実 HTTP Server
実際の OrderService の業務フロー
JdbcProductRepository / JdbcOrderRepository
PostgreSQL
Docker Compose
HttpShippingFeeClient
Shipping Fee Stub Server / 外部 API
```

なお Spring Context は起動する。ただし Application 全体の Bean を起動するわけではない。  
「Spring を起動しない Plain Unit Test」と「Spring Context の Web Slice を起動する今回の Test」を混同しない。

### `OrderControllerWebMvcTest` の境界

正常な HTTP Request を MockMvc で送る。Controller は実際に Service Method を呼ぶが、Service は Mock が用意した `Order` を返す。  
Response の status / header / JSON と、Service に渡された Parameter / Command を確認する。

ここで確認できるのは、正常な Service 応答を HTTP 契約へ変換する Web 層の振る舞いである。正常な注文を Production Service が作れるかは、この Test だけでは保証されない。

### `OrderControllerValidationWebMvcTest` の境界

無効な JSON や Bean Validation に違反する Request を送る。  
Service を呼ぶ手前で拒否され、`GlobalExceptionHandler` が HTTP 400 / `VALIDATION_ERROR` / `fieldErrors` を返すことを確認する。

```text
Invalid Request
    ↓
JSON 読み取り / Binding / Bean Validation
    ↓
400 + Error Response
    ↓
verifyNoInteractions(orderService)
```

Test 対象は入力の拒否と公開 Error Response である。Service 側の在庫不足や状態遷移の判断ではない。

### `OrderControllerExceptionHandlerWebMvcTest` の境界

正しい HTTP Request から Controller へ進み、Service Mock が意図的に Exception を発生させる。  
Exception Handler がこれを 400 / 404 / 409 / 502 / 500 の Error Response に変換することを確認する。

```text
Valid Request → Controller → Service Mock
                                     ↓
                                  Exception
                                     ↓
                           GlobalExceptionHandler
                                     ↓
                         HTTP status + Error JSON
```

業務例外の発生条件や実際の外部サービス障害は再現しない。Service の例外を HTTP へ変換する境界だけを扱う。

### `OrderControllerSearchWebMvcTest` の境界

`GET /api/orders` の query parameter が `OrderStatus` / `Instant` / `null` に変換されて Service へ渡されることを確認する。  
Service Mock が返す注文一覧を `OrderSummaryResponse` へ変換し、明細を含まない JSON Array として公開する。

不正な Enum / 日時は Controller に到達する前の Binding で拒否する。一方、日時の前後関係を Service が拒否したときは、Service Mock から Exception を返し、Advice による 400 変換を確認する。

### 今回の Test で保証しないもの

今回のテストが成功しても、次の事項が成立するとまでは言えない。

- `OrderService` が実際に DB Transaction と在庫更新を正しく実行できること
- `JdbcOrderRepository` / `JdbcProductRepository` の SQL と PostgreSQL の連携
- `HttpShippingFeeClient` の HTTP Request / Response Handling
- 実 HTTP Server から DB・外部 API までつないだ Application 全体の動作

これらはテストが不足しているという意味ではなく、**Unit 05 が意図して選択した境界の外側**にある。後続 Unit で対象範囲を広げて確認する。

## 使用するファイル

### この Unit で追加・変更するファイル

- [`pom.xml`](../../pom.xml) — Spring MVC 用の Test Starter を追加する。
- [`OrderControllerWebMvcTest.java`](../../src/test/java/com/example/orderapi/controller/OrderControllerWebMvcTest.java) — 5つの主要 API の HTTP 正常系を検証する。
- [`OrderControllerValidationWebMvcTest.java`](../../src/test/java/com/example/orderapi/controller/OrderControllerValidationWebMvcTest.java) — Bean Validation、壊れた JSON、型不一致を検証する。
- [`OrderControllerExceptionHandlerWebMvcTest.java`](../../src/test/java/com/example/orderapi/controller/OrderControllerExceptionHandlerWebMvcTest.java) — 業務例外と HTTP Error Response の変換を検証する。
- [`OrderControllerSearchWebMvcTest.java`](../../src/test/java/com/example/orderapi/controller/OrderControllerSearchWebMvcTest.java) — query parameter、一覧 JSON、400 応答を検証する。
- [`OrderWebMvcTestFixture.java`](../../src/test/java/com/example/orderapi/support/OrderWebMvcTestFixture.java) — 決定的な `Order` と Request JSON を提供する。

### コードリーディングで参照する主な Production Code

- [`OrderController.java`](../../src/main/java/com/example/orderapi/controller/OrderController.java)
- [`CreateOrderRequest.java`](../../src/main/java/com/example/orderapi/dto/order/CreateOrderRequest.java)
- [`CreateOrderItemRequest.java`](../../src/main/java/com/example/orderapi/dto/order/CreateOrderItemRequest.java)
- [`OrderResponse.java`](../../src/main/java/com/example/orderapi/dto/order/OrderResponse.java)
- [`OrderSummaryResponse.java`](../../src/main/java/com/example/orderapi/dto/order/OrderSummaryResponse.java)
- [`OrderItemResponse.java`](../../src/main/java/com/example/orderapi/dto/order/OrderItemResponse.java)
- [`GlobalExceptionHandler.java`](../../src/main/java/com/example/orderapi/exception/GlobalExceptionHandler.java)
- [`ApiErrorResponse.java`](../../src/main/java/com/example/orderapi/dto/error/ApiErrorResponse.java)
- [`FieldErrorResponse.java`](../../src/main/java/com/example/orderapi/dto/error/FieldErrorResponse.java)
- [`OrderService.java`](../../src/main/java/com/example/orderapi/service/OrderService.java)
- [`CreateOrderCommand.java`](../../src/main/java/com/example/orderapi/service/model/CreateOrderCommand.java)
- [`CreateOrderItemCommand.java`](../../src/main/java/com/example/orderapi/service/model/CreateOrderItemCommand.java)
- [`Order.java`](../../src/main/java/com/example/orderapi/domain/Order.java)
- [`OrderStatus.java`](../../src/main/java/com/example/orderapi/domain/OrderStatus.java)

### 前 Unit から参照する主な Test

- [`OrderServiceMockitoTest.java`](../../src/test/java/com/example/orderapi/service/OrderServiceMockitoTest.java) — Plain Unit Test における Mockito の使い方と比較する。
- [`OrderServiceTestabilityTest.java`](../../src/test/java/com/example/orderapi/service/OrderServiceTestabilityTest.java) — Testability と Test Boundary の考え方を比較する。

Unit 05 は既存の Production Code を変更せず、Web 層の Test Code と Maven の Test Dependency を追加する Unit となる。

## 前提

Unit 01～04 を完了していることを前提とする。特に次の知識を今回も利用する。

```text
JUnit Jupiter:
@Test / @DisplayName / @ParameterizedTest / @MethodSource

Test Design:
正常系 / 異常系 / Test Case / Test Oracle

Mockito:
Mock / Stub / when / thenReturn / thenThrow / verify

Test Quality:
SUT / Test Boundary / Test Isolation / Deterministic

Java / Spring:
Java 21 / Maven / 基本的な Spring MVC / REST API の読み方
```

Unit 05 で新たに中心となるのは Spring Context、Web MVC Slice、MockMvc、HTTP Request / Response、Bean Validation、Controller Advice、API Error Contract である。  
既習の Mockito と Assertion を使うが、SUT と保証範囲が Web 層へ移る点を意識する。

環境については Java 21、Maven Wrapper、リポジトリの依存関係が準備済みであることを前提とする。  
PostgreSQL、Docker、Shipping Fee Stub Server は起動しない状態で問題ない。

## コードリーディング

Unit 05 では、最初に Production Code の HTTP 境界と Request / Response DTO を確認し、その後に Test Code を読む。  
Mockito の stubbing から読み始めるのではなく、まず何が Web 層の責務なのかを把握する。

```text
OrderController / DTO / GlobalExceptionHandler の仕様を見る
    ↓
Test の @WebMvcTest / @MockitoBean と境界を見る
    ↓
OrderWebMvcTestFixture を読む
    ↓
正常系（Mapping / JSON / status / header）
    ↓
Validation（Body / Field Error / Service 未呼び出し）
    ↓
業務例外（Exception → HTTP Error Response）
    ↓
一覧 API（Query Parameter / Summary JSON）
    ↓
Plain Unit Test と保証範囲を比較する
```

### 1. `OrderController` の役割を確認する

[`OrderController.java`](../../src/main/java/com/example/orderapi/controller/OrderController.java) を開く。  
まず `@RestController`、`@RequestMapping("/api/orders")` と constructor で注入される `OrderService` を確認する。

注目するのは Controller が DB や Shipping Fee Client を直接呼んでいないこととなる。  
HTTP の入出力を整え、Service へ業務処理を委譲する構造が Unit 05 の Test Boundary を支える。

### 2. HTTP Method と Path の対応を確認する

`@PostMapping`、`@GetMapping`、`@PatchMapping` の Method を確認する。  
Controller の Method 名そのものではなく、外部 Client が呼ぶ HTTP Method / Path との対応を先に整理する。

```text
POST  /api/orders                 → create
GET   /api/orders/{id}            → findById
PATCH /api/orders/{id}/confirm    → confirm
PATCH /api/orders/{id}/ship       → ship
PATCH /api/orders/{id}/cancel     → cancel
GET   /api/orders                 → search
```

それぞれの Method でどの Service Method を呼び、どの Response を返すかを見る。

### 3. `CreateOrderRequest` の制約と変換を確認する

[`CreateOrderRequest.java`](../../src/main/java/com/example/orderapi/dto/order/CreateOrderRequest.java) と [`CreateOrderItemRequest.java`](../../src/main/java/com/example/orderapi/dto/order/CreateOrderItemRequest.java) を開く。  
郵便番号、空の items、`null` 要素、Item 内の `productId` / `quantity` に付いた Validation Annotation を確認する。

次に `toCommand()` を読む。HTTP 用 DTO を Service 用の Command へ変換しており、Web DTO をそのまま Service 層へ渡していない。  
この変換が正常系の `verify(orderService).createOrder(expectedCommand)` の意味につながる。

### 4. `OrderResponse` と `OrderSummaryResponse` を比較する

[`OrderResponse.java`](../../src/main/java/com/example/orderapi/dto/order/OrderResponse.java) の `from(Order)` と、[`OrderSummaryResponse.java`](../../src/main/java/com/example/orderapi/dto/order/OrderSummaryResponse.java) の `from(Order)` を比較する。

```text
注文詳細・更新:
OrderResponse → items を含む

注文一覧:
OrderSummaryResponse → items を含まない
```

Test で確認する JSON Field が、どの Response DTO の定義から生まれるかを対応させる。  
`OrderService` の戻り値がそのまま HTTP JSON になるのではなく、Web 層で公開 DTO へ変換している点が重要となる。

### 5. `GlobalExceptionHandler` の変換を先に読む

[`GlobalExceptionHandler.java`](../../src/main/java/com/example/orderapi/exception/GlobalExceptionHandler.java) を開く。  
`@RestControllerAdvice` と各 `@ExceptionHandler` を確認し、何の Exception がどの HTTP status・API `code` に変換されるかを整理する。

`ApiErrorResponse.of(code, message)` は `fieldErrors` を空 List にする。  
一方 Validation Handler は Field 名や Message を `FieldErrorResponse` として設定する。この構造が後の `jsonPath("$.fieldErrors...")` の期待値となる。

### 6. `pom.xml` で今回追加した Test Dependency を確認する

[`pom.xml`](../../pom.xml) の `spring-boot-starter-webmvc-test` を探す。  
Spring Boot 4.1.1 の Parent POM と `test` scope の関係を確認する。

```xml
<artifactId>spring-boot-starter-webmvc-test</artifactId>
<scope>test</scope>
```

Unit 01～04 の JUnit / Mockito / AssertJ を残したまま、Web MVC Test の基盤を追加した構成になっている。

### 7. `@WebMvcTest` と `@MockitoBean` を確認する

[`OrderControllerWebMvcTest.java`](../../src/test/java/com/example/orderapi/controller/OrderControllerWebMvcTest.java) を開く。  
Class Annotation と Test Fixture を確認する。

```java
@WebMvcTest(controllers = OrderController.class)

@Autowired private MockMvc mockMvc;
@MockitoBean private OrderService orderService;
```

`@WebMvcTest` が対象の Web Slice を指定し、`@MockitoBean` が Controller の Service Dependency を満たす。  
Unit 03 の `@ExtendWith(MockitoExtension.class)` / `@Mock` / `@InjectMocks` と構成の違いを比較する。

### 8. 共通 Fixture の `order(...)` を読む

[`OrderWebMvcTestFixture.java`](../../src/test/java/com/example/orderapi/support/OrderWebMvcTestFixture.java) を開く。  
固定の Order ID、Product ID、Postal Code、作成日時・キャンセル日時を確認する。

```text
ORDER_ID      = 100
PRODUCT_ID    = 10
POSTAL_CODE   = 1234567
CREATED_AT    = 2026-10-01T00:00:00Z
CANCELLED_AT  = 2026-10-02T12:00:00Z
```

`order(OrderStatus)` は `OrderItem.restore(...)`、`PriceSummary`、`Order.restore(...)` を利用して、HTTP Response 用の状態を直接作る。  
`CANCELLED` の場合だけ `cancelledAt` を設定し、Fixture の状態と期待する JSON を一致させている。

### 9. Request JSON Fixture を読む

`validCreateRequestJson()` は、Bean Validation を通る注文作成 Request を Text Block で返す。

```json
{
  "shippingPostalCode": "1234567",
  "items": [{ "productId": 10, "quantity": 2 }]
}
```

これを正常系だけでなく、業務例外を発生させる Test にも再利用している。  
業務例外の Test では Validation が先に失敗してしまうと、意図した Handler まで到達できないため、有効な Request Fixture を利用する。

### 10. 注文作成正常系の Arrange を読む

`OrderControllerWebMvcTest.create_returnsCreatedLocationAndOrderJson` を読む。  
まず `order(OrderStatus.CREATED)` と `CreateOrderCommand` を準備し、Service の戻り値を stubbing している部分を確認する。

```java
CreateOrderCommand expectedCommand =
        new CreateOrderCommand(
                "1234567", List.of(new CreateOrderItemCommand(10L, 2)));
when(orderService.createOrder(expectedCommand)).thenReturn(createdOrder);
```

Service 自身を実行するのではなく、Command が期待どおり作られた場合に返す Response 用 Order を定義している。

### 11. 注文作成の MockMvc Request を読む

正常系の `.perform(...)` を読む。

```java
post("/api/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .content(validCreateRequestJson())
```

HTTP Method、Path、Request Content-Type、受け取りたい Response の Media Type、Request Body の役割をそれぞれ分けて確認する。  
この Test では Mockito の `createOrder()` を直接呼ばず、MockMvc から Spring MVC の処理経路を通している。

### 12. 201 / Location / Content-Type の検証を読む

同じ Test の `andExpect(...)` を確認する。

```java
.andExpect(status().isCreated())
.andExpect(header().string(HttpHeaders.LOCATION, endsWith("/api/orders/100")))
.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
```

正常な POST に 200 ではなく 201 を返すこと、作成 Resource の URI が Location Header に示されることを確認する。  
`endsWith(...)` と `contentTypeCompatibleWith(...)` は、完全一致ではなくテストで必要な範囲を指定している点にも注目する。

### 13. 注文作成の Response JSON と `verify` を読む

JSONPath で `id`、`status`、`shippingPostalCode`、`items[0].productName`、`subtotal`、`shippingFee`、`totalAmount` などを確認している。

次に `JSONAssert.assertEquals(..., JSONCompareMode.LENIENT)` を読む。期待 Field を含んでいれば追加 Field を許容する比較となる。  
最後の `verify(orderService).createOrder(expectedCommand)` は、Request DTO が正しい Command に変換されたことまで検証している。HTTP Response の検証と interaction verification の両方が存在する理由を考える。

### 14. 注文詳細 GET を読む

`findById_returnsOrderDetails` を読む。  
`get("/api/orders/{id}", ORDER_ID)` が Controller の `@PathVariable` へ値を渡す処理に対応している。

```java
when(orderService.findById(ORDER_ID))
        .thenReturn(order(OrderStatus.CONFIRMED));
```

Response JSON の `createdAt` が固定 ISO-8601 文字列となること、`items[0].lineAmount` が含まれること、`cancelledAt` が空であることを確認する。  
`verify(orderService).findById(ORDER_ID)` では Controller から Service への正しい引数の受け渡しを確認している。

### 15. 3つの PATCH 正常系を比較する

次の3つの Test Method を順番に読む。

```text
confirm_returnsConfirmedOrder
  → PATCH /api/orders/{id}/confirm → CONFIRMED

ship_returnsShippedOrder
  → PATCH /api/orders/{id}/ship → SHIPPED

cancel_returnsCancelledOrder
  → PATCH /api/orders/{id}/cancel → CANCELLED
```

それぞれ Mock した Service が更新後の `Order` を返し、Controller が JSON を返す。  
この Test では状態遷移の可否や、実際の在庫更新を再計算していない。Service Test と異なる責務を確認する。

### 16. キャンセル日時の JSON を確認する

`cancel_returnsCancelledOrder` の `jsonPath("$.cancelledAt")` を確認する。  
`OrderWebMvcTestFixture.order(CANCELLED)` が用意した `CANCELLED_AT` と Response DTO の `cancelledAt` が対応する。

```java
.andExpect(jsonPath("$.cancelledAt")
        .value("2026-10-02T12:00:00Z"));
```

`Clock` を Mock にして日時を生成する Test ではなく、すでに決まった Domain Object の日時が HTTP JSON に正しく表現されることを確認する Test となる。

### 17. Validation Test の共通構造を確認する

[`OrderControllerValidationWebMvcTest.java`](../../src/test/java/com/example/orderapi/controller/OrderControllerValidationWebMvcTest.java) を開く。  
`@WebMvcTest` / `@MockitoBean` の構成が正常系と同じであることを確認する。

次に `@ParameterizedTest` / `@MethodSource("invalidOrderRequests")` を読む。  
1つの Test Method に複数の `Request JSON / expectedField` を与え、同じ HTTP 経路で失敗を検証する構造となる。

### 18. Bean Validation の8ケースを読む

`invalidOrderRequests()` の8件を、どの DTO 制約に対応するかという観点で整理する。

| ケース                  | 対応する制約・境界     | 期待する Field       |
| ----------------------- | ---------------------- | -------------------- |
| 郵便番号未指定          | `@NotBlank`            | `shippingPostalCode` |
| 郵便番号が7桁数字でない | `@Pattern`             | `shippingPostalCode` |
| 明細が空 List           | `@NotEmpty`            | `items`              |
| 明細 List 未指定        | `@NotEmpty`            | `items`              |
| `items:[null]`          | List 要素の `@NotNull` | `items[0]`           |
| Product ID 未指定       | `@NotNull`             | `items[0].productId` |
| Quantity = 0            | `@Min(1)`              | `items[0].quantity`  |
| Quantity = 100          | `@Max(99)`             | `items[0].quantity`  |

特に List 自体への制約と List 要素への制約を区別する。  
すべて同じ `400 / VALIDATION_ERROR` でも `fieldErrors` が指す位置は異なる。

### 19. Validation Error の Response 検証を読む

`create_whenBeanValidationFails_returnsFieldError` の `andExpect(...)` を読む。

```java
.andExpect(status().isBadRequest())
.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
.andExpect(jsonPath("$.message").value("request validation failed"))
.andExpect(jsonPath("$.fieldErrors[*].field", hasItem(expectedField)));
```

`hasItem(expectedField)` により、複数 Field Error の順番を固定せず、対象 Field が含まれることを確認している。  
その後の `verifyNoInteractions(orderService)` は、無効な Request が業務 Service へ到達していないことを示す。

### 20. 壊れた JSON の Test を読む

`create_whenJsonMalformed_returnsBadRequest` を読む。  
JSON の末尾が閉じられていないため、DTO の Bean Validation より前に Message Conversion が失敗する。

```text
HTTP 400
code = VALIDATION_ERROR
message = request body is invalid
fieldErrors[0].field = request
```

`GlobalExceptionHandler.handleHttpMessageNotReadable` の Response と比較する。  
同じ 400 でも Bean Validation 違反とは Response Message が異なることを確認する。

### 21. JSON Field の型不一致を読む

`create_whenJsonFieldHasWrongType_returnsBadRequest` を読む。  
`quantity` が数値ではなく `"abc"` となっているため、DTO が要求する `Integer` へ読み取れない。

この Test でも `fieldErrors[0].field` が `"request"` となり、Service へ到達しないことを確認している。  
正常な JSON Structure でも Java 型と一致しなければ Request Body が無効になり得る点を見る。

### 22. Exception Handler Test の配置を確認する

[`OrderControllerExceptionHandlerWebMvcTest.java`](../../src/test/java/com/example/orderapi/controller/OrderControllerExceptionHandlerWebMvcTest.java) を開く。  
まず Test の `@WebMvcTest` と `@MockitoBean` を確認し、今回の Exception がどこから発生するかを整理する。

```text
MockMvc が妥当な Request を送る
    ↓
OrderController が Mock Service を呼ぶ
    ↓
when(...).thenThrow(...) が Exception を返す
    ↓
GlobalExceptionHandler が HTTP Response へ変換
```

Service 内で本当に Exception を発生させる条件を作っているわけではないことを先に押さえる。

### 23. 注文不存在と商品不存在の 404 を比較する

`findById_whenOrderNotFound_returns404` と `create_whenProductNotFound_returns404` を読む。  
どちらも HTTP 404 だが、API Code と Message は異なる。

```text
OrderNotFoundException(100)
  → 404 / ORDER_NOT_FOUND / order not found: 100

ProductNotFoundException(10)
  → 404 / PRODUCT_NOT_FOUND / product not found: 10
```

`OrderNotFound` の Test は `verify(orderService).findById(ORDER_ID)` も確認している。  
同一 status の別の業務エラーを `code` で区別する考え方を確認する。

### 24. 重複明細の 400 を読む

`create_whenDuplicateOrderItem_returns400` を読む。  
正しい JSON を送っているが、Service Mock が `DuplicateOrderItemException` を返す構成となる。

```text
HTTP = 400
code = DUPLICATE_ORDER_ITEM
message = duplicate product in order: 10
fieldErrors = []
```

Bean Validation の `VALIDATION_ERROR` と同じ 400 であっても、業務上の意味が違う。  
`fieldErrors` が空になることも、Response DTO の使い分けと対応する。

### 25. 不正な状態遷移の 409 を読む

`confirm_whenInvalidTransition_returns409` を読む。  
Service Mock に `InvalidOrderStatusTransitionException(ORDER_ID, SHIPPED, CONFIRMED)` を設定する。

Handler が 409 と `INVALID_ORDER_STATUS_TRANSITION` を返し、Message に現在状態と目標状態が反映されていることを確認する。  
Service の状態遷移規則をこの Test で再評価しているわけではない。

### 26. 在庫不足と販売停止の 409 を比較する

`create_whenStockInsufficient_returns409` と `create_whenProductInactive_returns409` を読む。  
同じ 409 でも、次のように公開 Error Code が異なる。

```text
InsufficientStockException
  → INSUFFICIENT_STOCK

ProductInactiveException
  → PRODUCT_INACTIVE
```

Service の Exception と HTTP 契約を一対一に対応させて読む。  
在庫数量や商品の販売状態は Mock の Exception Constructor に設定した前提条件であり、実 DB から読み取った結果ではない。

### 27. 502 の Error Response を読む

`create_whenShippingFeeUnavailable_returns502WithoutInternalDetails` を読む。  
`ShippingFeeServiceException` の Message や Cause に、内部的な文字列を意図的に含めている。

```java
new ShippingFeeServiceException(
        "internal upstream failure",
        new IllegalStateException("secret endpoint"))
```

それにもかかわらず Response が `502 / SHIPPING_FEE_SERVICE_ERROR` と、安全な固定 Message になることを確認する。  
`JSONCompareMode.STRICT` は Error Document 全体を比較し、内部情報を追加 Field として公開していないかも検証するために利用している。

### 28. 想定外の 500 を読む

`ship_whenUnexpectedFailure_returns500WithoutInternalDetails` を読む。  
Service Mock が `IllegalStateException("secret SQL detail")` を発生させた場合、共通 Handler が 500 と `INTERNAL_SERVER_ERROR` を返す。

公開 Message は `"unexpected server error"` であり、内部 Exception Message ではない。  
500 を必ず発生させたいわけではなく、想定外の Exception でも Client に詳細を漏らさない契約を確認している。

### 29. 一覧 API と query parameter を読む

[`OrderControllerSearchWebMvcTest.java`](../../src/test/java/com/example/orderapi/controller/OrderControllerSearchWebMvcTest.java) を開く。  
最初に Controller の `search` が `@RequestParam(required = false)` と `@DateTimeFormat(...)` を利用していることを確認する。

`search_bindsQueryParametersAndReturnsOrderSummary` では、String で渡した Parameter を `OrderStatus.CONFIRMED`、2つの `Instant` に変換し、Service へ渡している。  
`verify(orderService).search(OrderStatus.CONFIRMED, from, to)` と対応させて読む。

### 30. 検索結果の JSON Array を読む

同じ Test で Service Mock が `List.of(order(CONFIRMED))` を返す。  
Response JSON は root が Array になるため、次の Path を利用する。

```java
.andExpect(jsonPath("$[0].id").value(100))
.andExpect(jsonPath("$[0].status").value("CONFIRMED"))
.andExpect(jsonPath("$[0].items").doesNotExist());
```

`OrderSummaryResponse` に注文明細が含まれない仕様を確認する。  
`doesNotExist()` は Property が存在しないことの検証であり、空 List を返すこととは異なる。

### 31. Optional Parameter 未指定を読む

`search_withoutParametersPassesNulls` を読む。  
何も Parameter を渡さない `GET /api/orders` が、Service の `search(null, null, null)` に変換されることを確認する。

Mock Service が空 List を返した結果、Response JSON の root Array が空であることも確認する。  
`null` は今回の Controller / Service 間の取り決めであり、すべての Spring MVC API が必ず同じ設計になるわけではない。

### 32. 不正な Enum と日時の 400 を読む

`search_whenStatusInvalid_returns400`、`search_whenDateTimeInvalid_returns400` を読む。  
それぞれ `"NOT_A_STATUS"` と `"yesterday"` が Java 型に変換できないため、`GlobalExceptionHandler.handleMethodArgumentTypeMismatch` が応答する。

```text
HTTP = 400
code = VALIDATION_ERROR
message = request parameter is invalid
fieldErrors[*].field = status または createdFrom
```

`verifyNoInteractions(orderService)` により、Binding が失敗した場合には Service へ到達しないことを確認する。

### 33. 日時範囲の業務整合性エラーを読む

`search_whenServiceRejectsDateRange_returns400` を読む。  
`createdFrom` と `createdTo` は、それぞれ `Instant` としては正しく変換できる。しかし前後関係が不正であるため、Service Mock が `IllegalArgumentException` を投げる。

```text
型変換は成功
  ↓
Controller が search(null, from, to) を呼ぶ
  ↓
Service Mock が IllegalArgumentException
  ↓
400 / VALIDATION_ERROR / fieldErrors[0].field = request
```

前の Enum / 日時変換エラーとの違いを確認する。  
この Test では Service へ到達しているので、`verify(orderService).search(null, from, to)` が存在する。

### 34. 4つの Test Class の役割を比較する

ここまで読んだ Test を、何を保証しているかで整理する。

| Test Class                                  | 主な確認対象                | Service Mock の使い方                       |
| ------------------------------------------- | --------------------------- | ------------------------------------------- |
| `OrderControllerWebMvcTest`                 | 正常な HTTP 入出力          | 戻り値を返し、Command / ID の受け渡しを確認 |
| `OrderControllerValidationWebMvcTest`       | 無効な Request の拒否       | 呼び出さないことを確認                      |
| `OrderControllerExceptionHandlerWebMvcTest` | 業務例外の HTTP 変換        | Exception を stubbing                       |
| `OrderControllerSearchWebMvcTest`           | query parameter / 一覧 JSON | 成功結果、型変換失敗、整合性違反を区別      |

Spring MVC のどこまでが本物として動くかは共通している。  
Test Method ごとに異なるのは、入力の条件・Mock の応答・保証したい HTTP Contract である。

### 35. これまでの Unit と Test Boundary を比較する

最後に Unit 03 の `OrderServiceMockitoTest` と比較する。

```text
Unit 03:
SUT = OrderService
依存先 = Mock Repository / Mock ShippingFeeClient / Clock
確認 = Service の業務フロー、状態、interaction

Unit 05:
SUT = Spring MVC + OrderController + Advice 等の Web 層
依存先 = @MockitoBean OrderService
確認 = HTTP status / header / JSON / Validation / Error Contract
```

同じ Mockito を使っていても、SUT が変われば `verify(...)` の意味や、その Test が保証できる範囲は変わる。  
この違いを読み取れることが、Unit 05 のコードリーディングにおける中心的な成果となる。

## 実行・確認

### 実行前の確認

リポジトリルートで作業する。  
Java 21 と Maven Wrapper が利用できること、Unit 05 の第1回生成物が適用されていることを確認する。

今回の Test は `@WebMvcTest` を用いた Web Slice Test なので、Spring MVC に必要な Context は Test 実行時に構成される。  
別途 Spring Boot Application を起動したり、PostgreSQL・Docker Compose・Shipping Fee Stub Server を起動したりする必要はない。

### 全 Test を実行する

これまでの Unit の Test も含めて実行する。

```bash
./mvnw test
```

Unit 01～04 の Plain Unit Test や、Unit 05 の Web MVC Slice Test が、同じ Maven Test Suite に含まれることを確認する。  
`@WebMvcTest` によって Spring Context が必要な Test は、その実行時に Slice 用 Context を構築する。Spring が必要ない Test は引き続き Plain Unit Test として実行される。

### Unit 05 の Test Class を個別実行する

主要 API の正常系を確認する。

```bash
./mvnw -Dtest=OrderControllerWebMvcTest test
```

Request Validation と JSON 読み取りエラーを確認する。

```bash
./mvnw -Dtest=OrderControllerValidationWebMvcTest test
```

業務例外と Error Response の変換を確認する。

```bash
./mvnw -Dtest=OrderControllerExceptionHandlerWebMvcTest test
```

Query Parameter と注文一覧 API を確認する。

```bash
./mvnw -Dtest=OrderControllerSearchWebMvcTest test
```

それぞれ Spring MVC の Slice を起動する Test Class であり、Test 実行のために実 HTTP Server を別起動する必要はない。

### Unit 05 の4 Test Class をまとめて実行する

Maven の `-Dtest` に複数 Class Pattern を指定する。

```bash
./mvnw '-Dtest=OrderControllerWebMvcTest,OrderControllerValidationWebMvcTest,OrderControllerExceptionHandlerWebMvcTest,OrderControllerSearchWebMvcTest' test
```

Git Bash でも Shell による解釈を避けられるよう、`-Dtest=...` 全体を引用符で囲んでいる。  
個別の Class を実行した後、まとめても意図した結果となることを確認する。

### Unit 05 の Test Case 構成を確認する

第1回生成物の Test Case 数は次となる。

| Test Class                                  | Test Method 数 | 実行される Test Case 数 |
| ------------------------------------------- | -------------: | ----------------------: |
| `OrderControllerWebMvcTest`                 |              5 |                       5 |
| `OrderControllerValidationWebMvcTest`       |              3 |                      10 |
| `OrderControllerExceptionHandlerWebMvcTest` |              8 |                       8 |
| `OrderControllerSearchWebMvcTest`           |              5 |                       5 |
| **合計**                                    |         **21** |                  **28** |

Validation Class は Parameterized Test Method 1つに対して、`@MethodSource` が8セットの入力を供給する。  
したがって Method 定義数と、JUnit における Test Invocation 数は一致しない。この違いは Unit 02 の Parameterized Test と同じ考え方となる。

### 正常系の HTTP Response を確認する

`OrderControllerWebMvcTest` では、少なくとも次の期待条件を Test Code 上で確認する。

```text
POST /api/orders
  → 201 + Location /api/orders/100 + 注文詳細 JSON

GET /api/orders/100
  → 200 + CONFIRMED の注文詳細 JSON

PATCH /api/orders/100/confirm
  → 200 + CONFIRMED

PATCH /api/orders/100/ship
  → 200 + SHIPPED

PATCH /api/orders/100/cancel
  → 200 + CANCELLED / cancelledAt
```

Maven の通常の成功ログには各 Request / Response の全文が表示されるとは限らない。  
コードリーディングでは `andExpect(...)` に書かれた HTTP Contract が成功していることを確認する。

### Validation Error と Service 未呼び出しを確認する

`OrderControllerValidationWebMvcTest` の Parameterized Test に、8つの入力条件が用意されていることを確認する。  
そのうえで、いずれも `400 / VALIDATION_ERROR` と対象 `fieldErrors` を検証していることを見る。

特に次の入力は対象となる階層が異なる。

```text
shippingPostalCode の値が不正
    → 単純 Field Validation

items = []
    → List 自体の Validation

items = [null]
    → List 要素の Validation

items[0].quantity = 0 / 100
    → List 内 DTO の Validation
```

`verifyNoInteractions(orderService)` が成功することで、Request の不正が業務処理の手前で拒否されたことを確認する。

### 壊れた JSON と型不一致を確認する

`create_whenJsonMalformed_returnsBadRequest` と `create_whenJsonFieldHasWrongType_returnsBadRequest` を確認する。

```text
HTTP = 400
code = VALIDATION_ERROR
message = request body is invalid（壊れた JSON のケース）
fieldErrors[0].field = request
Service は呼ばれない
```

この2ケースは Bean Validation の8ケースとは別に Test Method として存在する。  
JSON が読み取れない場合と、DTO に変換後に制約違反となる場合の失敗箇所を説明できることが重要となる。

### 400 / 404 / 409 / 502 / 500 を確認する

`OrderControllerExceptionHandlerWebMvcTest` では、Service Mock に設定した Exception に応じて期待 status と公開 `code` が変わる。

| Exception                               | 期待 status | 期待 code                         |
| --------------------------------------- | ----------- | --------------------------------- |
| `OrderNotFoundException`                | 404         | `ORDER_NOT_FOUND`                 |
| `ProductNotFoundException`              | 404         | `PRODUCT_NOT_FOUND`               |
| `DuplicateOrderItemException`           | 400         | `DUPLICATE_ORDER_ITEM`            |
| `InvalidOrderStatusTransitionException` | 409         | `INVALID_ORDER_STATUS_TRANSITION` |
| `InsufficientStockException`            | 409         | `INSUFFICIENT_STOCK`              |
| `ProductInactiveException`              | 409         | `PRODUCT_INACTIVE`                |
| `ShippingFeeServiceException`           | 502         | `SHIPPING_FEE_SERVICE_ERROR`      |
| 想定外の `IllegalStateException`        | 500         | `INTERNAL_SERVER_ERROR`           |

同じ HTTP status に複数の Error Code が存在しても、API Contract 上の違いを検証している。  
502 と 500 の Test は、内部の Exception Message をそのまま公開しないことにも注目する。

### JSONPath と JSONassert の確認箇所を比較する

注文作成正常系では JSONPath による Field 単位の確認に加え、JSONassert の `LENIENT` 比較を実行する。  
502 Error Response では `STRICT` 比較を実行する。

```text
JSONPath:
重要 Field の個別検証

JSONassert LENIENT:
重要 Field を含む JSON の構造確認

JSONassert STRICT:
Error Response 全体の構造を厳密に確認
```

`LENIENT` で追加 Field を許容していることと、`STRICT` で追加 Field を拒否していることの意味を説明できるようにする。  
いずれも Test Code が保証したい契約に対応しているかで判断する。

### Query Parameter の変換と 400 を確認する

`OrderControllerSearchWebMvcTest` では、正常な Enum / Instant への変換、Parameter 未指定、不正 Enum、不正日時、Service 側の日時範囲エラーを確認する。

```text
Binding 成功:
Request String → OrderStatus / Instant → Service 呼び出し

Binding 失敗:
Request String → 型変換できない → 400 / Service 未呼び出し

業務整合性失敗:
型変換成功 → Service Mock が拒否 → 400 / Service 呼び出しあり
```

各 Test の `verify(...)` と `verifyNoInteractions(...)` を比較し、Service 呼び出しに到達するかどうかの違いを確認する。

### Surefire Report の確認

Maven の Test が実行されると、通常 `target/surefire-reports/` に Test 結果が生成される。  
Class ごとの Report を確認し、Unit 05 の4 Class が Test として発見・実行されたことを確認する。

```text
target/surefire-reports/
├─ TEST-com.example.orderapi.controller.OrderControllerWebMvcTest.xml
├─ TEST-com.example.orderapi.controller.OrderControllerValidationWebMvcTest.xml
├─ TEST-com.example.orderapi.controller.OrderControllerExceptionHandlerWebMvcTest.xml
└─ TEST-com.example.orderapi.controller.OrderControllerSearchWebMvcTest.xml
```

Report の `tests` / `failures` / `errors` / `skipped` などを見る。  
XML File 名や詳細な内容は Surefire / JUnit の実行環境によって変わることがあるため、学習上は Test Class と実行結果を対応付けることを優先する。

### テストが失敗した場合

まず Maven が報告する失敗の種類から確認する。

```text
Context 起動の失敗
  → @WebMvcTest の対象、Bean / Dependency、設定を確認

HTTP status の不一致
  → Request Mapping、Validation、Exception Handler を確認

JSONPath / JSONassert の不一致
  → Response DTO、serialization、期待 JSON を確認

verify(...) の不一致
  → Controller から Service への引数・呼び出しを確認
```

Unit 05 は、実際の DB や外部 HTTP API を利用しない設計となっている。  
まず Web 層の境界と Test の期待条件を確認し、関連のない外部システムの起動を安易に追加して解決しようとしない。

## 学習ポイント

### Web 層のテスト境界は SUT から決める

Mockito を使えるから Service を Mock にするのではなく、今回の SUT が `OrderController` と HTTP 境界だから Service を置き換える。  
Test Boundary が先にあり、その範囲に適した Test Double や Framework を選ぶことが基本となる。

```text
何を保証したいか
    ↓
動かす Component を決める
    ↓
境界の外側を制御する
    ↓
境界内の結果を検証する
```

### Spring Context を起動する範囲は Test の目的で変わる

Spring を利用した Application を Test する場合でも、必ず Application 全体を起動する必要はない。  
Spring MVC が必要な Test では Web Slice を使い、業務 Service の直接テストでは Plain Unit Test を使うなど、保証したい対象に合わせて実行範囲を選ぶ。

### HTTP Contract と業務ロジックを区別する

`POST /api/orders` が 201 / Location / JSON を返すことと、注文作成が正しく在庫を更新することは、別の保証内容となる。  
両方とも重要だが、すべてを一つの Test で実行する必要はない。

```text
Unit 05:
Request / Response の契約を確認

Service / Domain Test:
業務ルール・状態遷移を確認

Integration Test:
Layer 間の実際の接続を確認
```

### Controller Test は Method を直接呼ぶだけではない

Controller Method を Java の Method として直接呼び出すだけでは、Request Mapping、HTTP Message Conversion、Bean Validation、Controller Advice の処理を通らない。  
今回 MockMvc を使用するのは、これらの MVC 処理を含めて確認したいからである。

### 成功時は status だけでなく header / JSON も見る

HTTP 201 だけでは、作成された Resource の URI や返却 JSON が正しいとまでは言えない。  
Client が利用する API として必要な情報を選び、status・header・body を組み合わせて検証する。

### Service への Command 変換も Web 層の責務となる

Request DTO を Service Command に変換する処理は Controller / DTO 側に存在する。  
POST 正常系の `verify(orderService).createOrder(expectedCommand)` は、見た目の JSON だけでなく、業務 Service に正しい値を渡したことまで確認している。

### Validation Error は Service に進ませない

HTTP 入力として不正なデータは、業務ロジックへ到達する前に拒否されるべき場合がある。  
`verifyNoInteractions(orderService)` は、この境界を Test で表現するために有効となる。

```text
無効な Request
    ↓
Validation で拒否
    ↓
HTTP 400
    ↓
Service は呼ばれない
```

### 400 の原因は一つではない

Bean Validation、壊れた JSON、query parameter の型変換失敗、Service による業務入力の拒否は、すべて HTTP 400 になり得る。  
失敗した段階によって Exception、Response Message、Field Error が異なるため、単に 400 を期待するだけでは区別できない。

### `@Mock` と `@MockitoBean` の役割を混同しない

どちらも Mockito の Mock を利用できるが、後者は Spring Test Context の Bean を置き換える。  
SUT を Spring が構築するか、Test Code が直接構築するかによって、依存先の与え方も変わる。

### Error Response は公開契約として検証する

同じ HTTP status を使う業務エラーであっても、Client が `code` で区別する設計がある。  
そのため status に加えて、`code`、`message`、`fieldErrors` の構造を合わせて確認する。

### 内部情報を Error Response に漏らさない

下位 Service の例外詳細や SQL 情報を、そのまま HTTP Response に返すことが望ましくない場合がある。  
今回の 502 / 500 Test は、既定の公開 Message に変換されることを確認している。特に 502 では JSONassert の `STRICT` を利用して Response 全体も比較する。

### JSONPath と JSONassert は使い分ける

JSONPath は特定 Field の検証、JSONassert は JSON Document の構造比較に適している。  
常に最も厳しい Matcher を使うのではなく、仕様としてどこまで保証したいかで選ぶ。

```text
特定 Field を確認       → JSONPath
主要 Field を構造で確認  → JSONassert LENIENT
公開 Document 全体を固定 → JSONassert STRICT
```

### Parameterized Test はケースを消すものではない

Validation の8入力は、1つの Parameterized Test Method で表現されている。  
Test Method 数は少なくても、JUnit が異なる Request ごとに Test Invocation を実行していることを理解する。

### Test Fixture は境界外の複雑さを持ち込まない

HTTP Response 用の `Order` を準備するために DB を起動したり、Service の注文作成処理を通したりする必要はない。  
`OrderWebMvcTestFixture` は、今回の保証対象に必要な状態だけを直接作成している。

### Mock の戻り値が正しくても Production Service は未検証

Mock は Test で指定した応答を返す。  
Web MVC Test が成功しても、実際の `OrderService` / Repository / PostgreSQL の連携まで正しいとは証明できない。Mock がある境界では保証対象が区切られていることを常に意識する。

### Test の失敗原因を境界ごとに切り分ける

Spring Context の生成、MVC Binding、Validation、Controller の Response 変換、Exception Handler、Mock の stubbing などは異なる要素となる。  
失敗した場合は HTTP status・JSON・interaction のどこで期待と異なったかを確認し、境界に合わせて原因を追う。

## 補足

### `@WebMvcTest` と `@SpringBootTest` の関係

`@WebMvcTest` は Spring MVC に必要な Bean を中心に読み込む Slice Test となる。  
一方 `@SpringBootTest` は Application のより広い構成を読み込んで Test できる。後者と MockMvc を組み合わせる方法も存在するが、何を本物として動かすかは Test ごとの設定で決まる。

Unit 05 は Web 層の契約に集中するため `@WebMvcTest` を使う。後続 Unit では Application 全体の構成や DB を含めた Integration Test と比較する。

### 実 HTTP Server を起動しないことと HTTP を検証しないことは違う

MockMvc は模擬 HTTP Request / Response を利用するが、Spring MVC の Request Mapping、Validation、Serialization などを通す。  
そのため HTTP の公開契約に関する重要な確認ができる。

ただし、実際の Network、Port、Application Server の起動・接続、Proxy などを含む E2E な保証はこの Test には含まれない。  
Mock MVC Test と実 HTTP Server を経由した Test は、互いに代替するだけではなく確認範囲が異なる。

### Security / Filter を扱う場合

`@WebMvcTest` では Web 層に関係する Filter や Security 設定が Test に影響する場合がある。  
今回のリポジトリは Security を学習対象として追加しておらず、認証・認可の Test Case は今回の実装に含まれない。

将来 Security を導入する場合には、現在の HTTP status の期待値や MockMvc Request の準備条件が変わる可能性がある。  
Unit 05 の既存 Test を見る際には、現時点の Production Code / Dependency 構成が前提となる。

### HTTP Validation と業務 Validation の区別

`CreateOrderRequest` の郵便番号形式や数量の範囲は、HTTP 入力時の Validation となる。  
一方、在庫不足や状態遷移不可などは、単純な DTO Annotation だけでは判定できない業務条件となる。

```text
HTTP 入力の形式・単項目制約
    → Bean Validation / Controller

現在の在庫・注文状態などを使う判断
    → Service / Domain

Exception を HTTP に表現する責務
    → GlobalExceptionHandler
```

同じ 400 や 409 として Client に返る場合があっても、どの Layer がその問題を発見したかは異なる。

### `fieldErrors` の順序と検証対象

Bean Validation で複数の制約違反が発生すると、`fieldErrors` に複数要素が入る場合がある。  
今回の Test は `hasItem(expectedField)` を利用して Field の存在を確認し、Error List 全体の順序までは固定していない。

これは情報不足を放置するという意味ではなく、今回の仕様で重要なのが対象 Field の検出であるため、その範囲に Assertion を絞っている。  
将来 Field Error の順序や Message 全文を公開 API の契約として扱うなら、それに対応する Test を追加する必要がある。

### `JSONCompareMode.STRICT` の適用範囲

`STRICT` は、追加 Field の有無を含む JSON Document の比較に利用できる。  
今回の 502 Test では、公開する Error Response が所定の3 Field から構成されることを確認するために用いる。

一方、注文の正常な Response は多くの Field を持つため、すべてを Document 全体で固定するよりも、重要 Field を JSONPath で確認する方が学習目的に適する。  
Test の厳密さは「一律に強いこと」ではなく、公開契約に必要な条件を過不足なく表現することで判断する。

### 502 と外部 HTTP Client Test の違い

今回の 502 Test は、`ShippingFeeServiceException` を受け取った Web 層が Client 向けに何を返すかを確認する。  
実際の `HttpShippingFeeClient` が外部 API に送信する Request や、Response Status / JSON から Exception を生成する処理は通らない。

外部 HTTP Client の Contract は後続の専用 Unit、Controller / Service / DB などを組み合わせた動作は Integration Test 側で確認する。

### Unit 06 以降へのつながり

Unit 05 では HTTP 境界を Spring MVC Test Slice として独立して確認した。  
次の Unit 06 では Repository と PostgreSQL の接続を、Unit 07 では Spring Boot と DB の Integration Test を扱い、さらに外部 HTTP Client や REST API 全体の Test へ範囲を広げる。

```text
Unit 05: Spring MVC / HTTP の入出力
    ↓
Unit 06: SQL / Repository / PostgreSQL
    ↓
Unit 07: Spring Boot + DB の Integration Test
    ↓
Unit 08: 外部 HTTP Client
    ↓
Unit 09: REST API 全体の Test
```

今回学んだ Test Slice と Test Boundary は、後続 Unit でどの範囲を本物として動かすかを判断する際の基礎となる。
