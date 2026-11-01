# 08. 外部 HTTP API のテスト

## この Unit の目的

この Unit では、外部 HTTP API に依存する処理を、実外部サービスへ接続せずに自動テストする方法を学ぶ。  
同じ `ShippingFeeClient` に対して、Mockito で Java Object を置き換える境界と、WireMock で HTTP の接続先を置き換える境界を比較する。

Unit 03・07 では、送料 Client の戻り値や例外を Java method の Stub として与えていた。  
今回は実 `HttpShippingFeeClient`、`RestClient`、Request / Response DTO、JSON 変換を通し、送信した HTTP Request と受信した Response を確認する。

中心となるのは、**どの Response を返すかを決める Stub と、実際に何を送信したかを確かめる verification を区別すること**である。  
HTTP 200 の受信だけで成功とせず、JSON の構文、必須 field、送料の値、空 Body なども外部 API との境界で扱う。

さらに、実 HTTP の 500 Response から発生した例外が、Service の業務例外へ変換される代表経路を確認する。  
DB の保存・Rollback は Unit 07、アプリケーションの HTTP 入口からの API Test は Unit 09 と役割を分ける。

## 学習内容

### 外部 API 依存とは何か

外部 API 依存とは、自分の処理を完了するために、別のサービスから値や処理結果を受け取る関係である。  
注文管理アプリケーションでは、商品の小計と数量を計算した後、Shipping Fee API から送料を取得する。

```text
OrderService
  → ShippingFeeClient
    → HttpShippingFeeClient
      → HTTP
        → External Shipping Fee API
```

Service が必要とするのは送料という業務上の値である。  
接続先 URL、HTTP Method、JSON の field 名、Status の扱いは HTTP Client 側の責務へ分けている。

この分離により、Service の業務フローと HTTP Client の通信処理を、異なる Test Boundary から確認できる。  
interface があることは実装を省略する理由ではなく、確認する責務ごとに依存の扱いを選ぶための設計となる。

### network dependency が加える条件

network dependency は、処理の成功がネットワーク通信にも依存することである。  
同じ Java method の引数でも、接続先が停止している、通信が許可されていない、応答が遅いなどの条件で結果が変わり得る。

外部 API との連携では、少なくとも次の段階を分けて考える。

```text
Java の入力値を用意する
  ↓
Request DTO を生成する
  ↓
JSON と HTTP Request に変換する
  ↓
Server へ接続して送信する
  ↓
HTTP Status と Body を受信する
  ↓
JSON を Response DTO に変換する
  ↓
業務上利用できる値を返す
```

HTTP の送受信が成功しても、Body が期待する JSON であるとは限らない。  
JSON の構文が正しくても、必須値が欠けていたり、送料が負数だったりすれば、そのまま成功として扱えない。

どの段階で失敗したかを区別すると、接続環境、送信形式、外部 API の応答、DTO の制約のどこを調べるべきか判断しやすくなる。

### 実外部サービスへ直接接続する Test の問題

自動テストのたびに実外部サービスへ接続すると、確認したいコード以外の条件が Test の結果へ入り込む。  
サービスの稼働時間、認証情報、利用回数制限、料金、共有データ、仕様変更などを Test 側だけでは制御できない。

特に異常系では、実サービスへ「必ず 500 を返す」「必須 field を欠落させる」と依頼できるとは限らない。  
通常は発生しにくい Response ほど、再現できる環境をテスト側で用意する意味がある。

実サービスを使う確認には、接続設定や実際の相手との整合を確かめる役割がある。  
一方、Client の代表的な正常・異常処理を毎回安定して確認する目的には、制御可能な HTTP Server を使う方法が適している。

このリポジトリの Shipping Fee API は学習用の仮想的な外部 API であり、実在する業務サービスへの接続を前提としない。  
通常確認でも自動テストでも WireMock を使うが、その Server の管理方法と目的は異なる。

### 外部依存の isolation

isolation は、Test の成否へ影響する外部の状態を切り離し、必要な条件をテスト側で制御することである。  
今回は Client の接続先を、自分のテストが起動した WireMock に限定する。

- Response の Status・Header・Body をケースごとに制御する。
- 接続先には loopback の `127.0.0.1` と動的ポートを使う。
- 通常確認用の固定ポート `8081` と mapping を共有しない。
- 前回の Stub・Request 記録を次の Test へ持ち越さない。

ここでいう isolation は「ネットワーク機能をまったく使わない」という意味ではない。  
実 Client はローカルの HTTP Server へ接続するため、ポートの利用と loopback 通信が可能な実行環境は必要である。

実外部サービスから独立することと、HTTP 通信そのものを省略することを分けて理解する。

### Mockito と WireMock が置き換える境界

Unit 03 の Mockito Test は、`ShippingFeeClient.calculateShippingFee(...)` の戻り値や例外を制御する。  
その Test から実 `HttpShippingFeeClient` の処理は呼ばれず、JSON や HTTP Request は生成されない。

```text
Unit 03・07 の送料依存

Test → 実 OrderService → ShippingFeeClient の Mock
                            ├─ 送料を Java の値で返す
                            └─ Java の例外を送出する
```

Unit 08 は Client 自身を本物として動かし、その先の HTTP Server を WireMock に置き換える。

```text
Unit 08 の Client Test

Test → 実 HttpShippingFeeClient
         → 実 RestClient / DTO / JSON 変換
           → 実 HTTP 通信
             → WireMock Server
```

| 比較項目         | Mockito による Client の置き換え    | WireMock による接続先の置き換え          |
| ---------------- | ----------------------------------- | ---------------------------------------- |
| 制御する境界     | Java method の呼び出し              | HTTP Request / Response                  |
| Stub の入力      | Java の引数                         | Method・URL・Header・Body など           |
| Stub の出力      | Java の戻り値・例外                 | HTTP Status・Header・Body                |
| 実 Client の実行 | 省略する                            | 実行する                                 |
| JSON 変換・通信  | 通さない                            | 通す                                     |
| 主な観測         | method の引数・回数、Service の結果 | HTTP Request の内容・回数、Client の結果 |

どちらも Test Double を使う方法だが、確認できる不具合は異なる。  
Service の正常系を確認するだけなら Java の Stub で十分な場合があり、JSON の field 名や HTTP Status の扱いを確認するには HTTP 境界を通す必要がある。

### HTTP-level Test Double と WireMock Server

HTTP-level Test Double は、HTTP の接続先として振る舞い、テストが指定した応答を返す代替物である。  
WireMock はその Server を起動し、Request に応じた Stub Response と、受信記録を調べる機能を提供する。

WireMock の業務処理は実 Shipping Fee API の実装ではない。  
一方、Client から見ると HTTP Server であるため、Java の DTO を JSON に変換して送信し、応答を受け取る経路は実際に通る。

本教材の WireMock には主に 2 つの役割がある。

| 役割               | 今回の用途                                    |
| ------------------ | --------------------------------------------- |
| 応答の制御         | 200・500・不正 JSON・必須値欠落などを再現する |
| interaction の観測 | 受信した Request の内容と回数を確認する       |

「WireMock を使った」という事実だけでは、後者まで確認したことにはならない。  
Stub の設定と、実行後の verification を対応させて読む必要がある。

### 今回の HTTP Contract

HTTP Contract は、Client と Server がやり取りする Method、URL、Header、Body、Status などの取り決めである。  
今回の呼び出し先は次の操作となる。

```http
POST /shipping-fees/calculate
Content-Type: application/json
```

中心となる正常ケースの Request Body は次の形である。

```json
{
  "postalCode": "1000001",
  "subtotal": 12000,
  "totalQuantity": 3
}
```

| field           | JSON 上の型 | 意味                                |
| --------------- | ----------- | ----------------------------------- |
| `postalCode`    | 文字列      | 配送先郵便番号。先頭の 0 も保持する |
| `subtotal`      | 数値        | 商品小計                            |
| `totalQuantity` | 整数の数値  | 商品の総数量                        |

郵便番号は算術計算する値ではないため、文字列として扱う。  
`0600001` を数値へ変換すると先頭の `0` を失うため、別の正常ケースで保持を確認する。

正常 Response は Status `200` と、送料を表す JSON で構成する。

```json
{
  "shippingFee": 800
}
```

Test は WireMock に送料計算の業務ルールを実装しているわけではない。  
小計から送料 `800` や `0` を算出できることではなく、外部 API が返した値を Client が受け取れることを確認する。

### ShippingFeeClient と HttpShippingFeeClient の責務

`ShippingFeeClient` は、郵便番号・小計・総数量から送料を取得する interface である。  
`OrderService` はこの interface に依存し、HTTP の具体的な操作を直接組み立てない。

`HttpShippingFeeClient` は、その操作を HTTP に変換する実装である。

```java
ShippingFeeRequest request = new ShippingFeeRequest(postalCode, subtotal, totalQuantity);

ShippingFeeResponse response =
    restClient
        .post()
        .uri(CALCULATE_PATH)
        .body(request)
        .retrieve()
        .body(ShippingFeeResponse.class);
```

`post()` と `uri(...)` は送信する操作を表し、`body(request)` は Request DTO を渡す。  
`retrieve()` から Response の読み取りへ進み、`body(ShippingFeeResponse.class)` で Body を DTO として取得する。

この実装を Mock に置き換えると、上記の処理は確認できない。  
今回のテストでは Spring から実 `HttpShippingFeeClient` を取得し、この method を呼び出す。

### DTO と JSON 変換

DTO は、境界をまたいで渡す値を表す Object である。  
Request DTO と Response DTO を分けることで、Domain Model と外部 API の表現を混同しにくくする。

serialization は Java の Object を JSON などの表現へ変換すること、deserialization は受信した表現を Java の Object へ変換することである。

```text
送信:
ShippingFeeRequest → HTTP Message Converter → JSON

受信:
JSON → HTTP Message Converter → ShippingFeeResponse → BigDecimal の送料
```

今回の JSON 変換は、Boot が構成した `RestClient.Builder` から引き継ぐ基盤を使う。  
Test 内で通信結果の DTO を直接生成して渡す方法では、実際の JSON 変換を確認したことにならない。

Client は `Content-Type` を method 内で明示設定していないが、DTO を Body として送信する変換経路を通る。  
Test は、結果として JSON の Content-Type が実送信されることも確認する。

### Request DTO の制約と送信前の拒否

`ShippingFeeRequest` は record の constructor で入力値を検査する。

| 値              | 実装上の制約           |
| --------------- | ---------------------- |
| `postalCode`    | null でなく、数字 7 桁 |
| `subtotal`      | null でなく、0 以上    |
| `totalQuantity` | 1 以上                 |

この検査は HTTP の送信より前に行われる。  
本 Unit では総数量 `0` を代表ケースとし、`IllegalArgumentException` と受信記録 `0` 件を組み合わせて確認する。

例外が出ただけでは、送信前に拒否したのか、Server からエラーが返ったのか区別できない。  
Request Journal に記録がないことを確認することで、通信へ進んでいないことも観測する。

Request DTO の全入力制約を網羅する専用 Test を追加したわけではない。  
HTTP 処理との境目を理解する代表例として、このケースを扱う。

### HTTP stub を設定する

HTTP stub は、指定した条件の Request に対して、決めた HTTP Response を返す設定である。  
正常ケースでは次の構造で登録している。

```java
wireMock.stubFor(
    post(urlEqualTo(CALCULATE_PATH))
        .withHeader("Content-Type", matching(JSON_CONTENT_TYPE))
        .withRequestBody(equalToJson(EXPECTED_REQUEST))
        .willReturn(
            aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"shippingFee\": 800}")));
```

前半は Request を選ぶ条件、`willReturn(...)` 以降は返す Response である。  
Stub を登録した段階では、Client が実際にこの URL へ送信したとは限らない。

`withHeader(...)` が Request 側にある場合は受信条件、`aResponse()` の後にある場合は返す Header の設定となる。  
同じ API 名でも、どちらの builder に適用しているかを確認する。

### request matching の粒度

request matching は、受信 Request が Stub の条件に一致するかを判定する処理である。  
今回の正常ケースでは、次の条件を組み合わせる。

| 条件   | 実装                              | 確認すること                   |
| ------ | --------------------------------- | ------------------------------ |
| Method | `post(...)`                       | POST で送信する                |
| URL    | `urlEqualTo(CALCULATE_PATH)`      | 指定 URL と一致する            |
| Header | `withHeader("Content-Type", ...)` | JSON を送信する                |
| Body   | `equalToJson(EXPECTED_REQUEST)`   | 期待する JSON の内容と一致する |

`urlEqualTo` は Request の相対 URL を比較する。  
今回の値は `/shipping-fees/calculate` であり、不要な query parameter が付けば同じ条件には一致しない。

JSON は field の並び順や整形用の空白が変わっても、意味が同じ場合がある。  
`equalToJson` を使い、JSON 全文の単純な文字列一致へ固定しない。

一方、今回の設定では余分な field を許容する指定はしていない。  
field 名や値の誤りまで、整形差として無視するわけではない。

Content-Type の matcher は、次の文字列を使用する。

```java
"(?i)application/json(?:\\s*;.*)?"
```

これは大文字・小文字の差を無視し、`application/json` の後に charset などのパラメータが続く形も許容する正規表現である。  
JSON という形式の保証と、Header 表記の細かな差を分けている。

### 異常 Response の Stub と Request 検証を分ける

500 や不正 Body のケースでは、主に Method・URL を Stub 条件にして目的の Response を返す。  
その後、実行後の verification で Header・JSON・回数も確認する。

この構成では、次の 2 つを分けて追える。

1. 目的の異常 Response を Client がどう扱ったか。
2. そのとき Client が正しい Request を送信したか。

正常ケースの厳しい matching と、異常ケースの応答制御・送信確認を読み比べると、matching と verification の役割の違いが分かる。  
何にでも一致する Stub で 500 を返す方法にはせず、対象操作に対する失敗を再現する。

### request verification と送信回数

request verification は、処理の実行後に受信記録を調べ、期待する通信が発生したか確認することである。  
Client Test の `verifySingleRequest(...)` は次の形で確認する。

```java
wireMock.verify(
    1,
    postRequestedFor(urlEqualTo(CALCULATE_PATH))
        .withHeader("Content-Type", matching(JSON_CONTENT_TYPE))
        .withRequestBody(equalToJson(expectedJson)));
assertThat(wireMock.getAllServeEvents()).hasSize(1);
```

最初の verification は、指定条件に一致する Request が正確に 1 回あったことを確認する。  
全記録の件数も 1 件とすることで、別 URL への余分な送信が含まれていないことも確認する。

Stub に一致して送料が返っただけでは、同じ Request を複数回送信していても見逃す可能性がある。  
戻り値の Assertion と、送信回数の verification は異なる保証を持つ。

この Test が 1 回を期待することは、一般的な外部 API に Retry が不要という意味ではない。  
現在の Client に対する 1 回の呼び出しが、今回の条件で 1 回だけ送信することを確認している。

### Request Journal と JSON の型・値を観測する

Request Journal は、WireMock が受信した Request の記録である。  
Stub の登録内容を読むだけでなく、この記録を読むことで実際の送信結果を確認できる。

`verifySingleRequest(...)` は受信 Body を `JsonMapper` で読み、型と値も確認する。

```java
var requests = wireMock.findRequestsMatching(anyRequestedFor(anyUrl()).build()).getRequests();
var body = jsonMapper.readTree(requests.getFirst().getBodyAsString());
assertThat(body.get("postalCode").isString()).isTrue();
assertThat(body.get("subtotal").isNumber()).isTrue();
assertThat(body.get("totalQuantity").isIntegralNumber()).isTrue();
```

実コードでは、これらの型に加えて文字列・数値の値も確認する。  
郵便番号を数値へ変換したり、小計を文字列として送信したりする誤りを区別するためである。

この JSON 読み取りは、テストが受信記録を観測するための処理である。  
Client が Response を DTO へ変換する処理とは、対象と目的が異なる。

### Fixture と期待値を独立させる

Test Fixture は、ケースを成立させる入力値・Stub Response・初期状態などの準備である。  
今回は小さな JSON と Java Object を各テスト内に置き、HTTP の違いがコードから直接分かるようにしている。

中心となる正常ケースは、郵便番号 `1000001`、小計 `12000`、総数量 `3` と、送料 `800` の Response を使う。  
送料 0 のケースは郵便番号 `0600001`、小計 `6000`、総数量 `2` に変え、入力を固定したまま送る誤りも検出する。

期待 JSON は API 仕様から明示し、Production の Request DTO を serialize して生成しない。  
送信側と期待側を同じ実装で作ると、field 名の誤りなどを両方へ持ち込み、Test が成功してしまう可能性があるためである。

不正 JSON の Fixture は、意図的に壊れた raw 文字列である。  
Stub 登録前に JSON として parse・整形すると、Client に渡す前に失敗したり、再現したい不正な状態を失ったりする。

### HTTP Status と Response Body は別に確認する

HTTP Status は、HTTP の処理結果を表す情報である。  
正常系の `200` と Server 障害の `500` を区別して、Client の処理を確認する。

500 のケースでは、`RestClient` の Response 処理から `HttpServerErrorException` 系の例外が発生する。  
Test は例外の存在だけでなく、Status `500` と Response Body を確認する。

```json
{ "error": "unit08-shipping-unavailable" }
```

この Body は、意図した Stub からの応答であることを識別する Fixture である。  
外部 API の新たな Error Response 仕様を定義するためのものではない。

500 の Response は正常な送料 DTO として扱わず、HTTP Status の失敗として送出される。  
反対に、Status が 200 でも、Body の形式と値が妥当であるかは別に確認しなければならない。

### 不正 JSON と不完全 Response

不正 JSON は、JSON の構文として読み取れない Response である。  
本教材では `{"shippingFee":` を返し、値と閉じ括弧がない状態を再現する。

不完全 Response は、JSON の構文は正しいが、利用に必要な情報がそろっていない Response である。  
`{}` は Object として正しい JSON でも、必須の `shippingFee` が存在しない。

| Response Body          | 区別する問題                   |
| ---------------------- | ------------------------------ |
| `{"shippingFee":`      | JSON の構文が壊れている        |
| `{}`                   | 必須 field がない              |
| `{"shippingFee":null}` | field はあるが値が null        |
| `{"shippingFee":-1}`   | 値はあるが送料の制約に違反する |

これらは同じ例外 Assertion を使うが、同じ意味のケースではない。  
構文・存在・null・値域という異なる不備を、同じ実変換経路へ入力している。

### Response DTO の制約と例外の cause

`ShippingFeeResponse` は送料が null でないことと、0 以上であることを constructor で検査する。  
この検査は、Response JSON から DTO を構築する過程で適用される。

HTTP 境界では、DTO constructor の例外がそのまま直接送出されるとは限らない。  
JSON の読み取り失敗として Jackson・Spring に包まれるため、Test は次の境界を確認する。

```text
不正な JSON / 不正な DTO の値
  ↓
JSON 読み取り・DTO 構築の失敗
  ↓
HttpMessageNotReadableException
  ↓
RestClientException
```

`calculateShippingFee_invalidResponse_throwsJsonConversionException` は、外側の `RestClientException` と直接の cause の `HttpMessageNotReadableException` を確認する。  
Jackson 内部の細かな例外クラス、最深部の cause、位置情報を含むメッセージ全文までは固定しない。

この Assertion が保証するのは、対象の不正 Response を正常な送料として受理せず、JSON 読み取りに関係する失敗として通知することである。  
DTO を直接生成した場合の例外型と、HTTP 経由で観測する例外型を混同しない。

### 空 Body、JSON 全体の null、送料 0

空 Body は、受信した Body の内容自体がない状態である。  
JSON 全体の `null` は Body に文字列として `null` が存在するが、変換結果の DTO が null になる状態である。

どちらも今回の Client では、Response DTO を取得できないため同じ検査へ到達する。

```java
if (response == null) {
  throw new IllegalStateException("shipping fee response body must not be null");
}
```

一方、`{"shippingFee":null}` は DTO の field の問題であり、前述の JSON 変換失敗として扱う。  
`{"shippingFee":0}` は有効な DTO であり、正常な送料として返す。

| 状態                             | 主な観測結果                              |
| -------------------------------- | ----------------------------------------- |
| HTTP 200 + 空 Body               | Client 自身の `IllegalStateException`     |
| HTTP 200 + JSON 全体の `null`    | Client 自身の `IllegalStateException`     |
| HTTP 200 + `shippingFee` が null | JSON 変換失敗とその cause                 |
| HTTP 200 + `shippingFee` が 0    | 正常値として `BigDecimal.ZERO` と数値比較 |

「値がない」と「値が 0」を区別することで、送料なしの正常ケースを誤って拒否する実装も検出できる。

### Client の例外と Service の業務例外変換

Client は HTTP と DTO の境界を担当し、Service はその依存先の失敗を業務上の意味へ変換する。  
`OrderService.requestShippingFee(...)` は、Client 呼び出しで発生した `RuntimeException` を包む。

```java
try {
  return shippingFeeClient.calculateShippingFee(postalCode, subtotal, totalQuantity);
} catch (RuntimeException exception) {
  throw new ShippingFeeServiceException("failed to calculate shipping fee", exception);
}
```

Client Test は Client の例外を確認し、Client 自身が `ShippingFeeServiceException` を送出するとは説明しない。  
Service の代表 Test は、実 HTTP 500 から業務例外へつながる経路を確認する。

```text
WireMock の 500 Response
  → RestClient の HTTP 例外
    → OrderService の catch
      → ShippingFeeServiceException
          cause に HTTP 例外を保持
```

Service Test は業務例外のメッセージに加え、cause の型・Status・Body を確認する。  
Unit 03・07 のように既知の例外 instance を用意して同一性を比較する方法と、実 HTTP から生じた情報を観測する方法を使い分けている。

全種類の不正 Response を Service 経由でも重複実行する構成ではない。  
HTTP の各異常は Client Test、Service との接続部分は 500 の代表例という分担である。

### Service Test が DB について保証する範囲

Service の代表 Test は、有効な Product と Command を用意し、送料取得前の業務チェックを通す。  
単価 `3000`、数量 `2`、在庫 `10`、販売中の商品から、小計 `6000`・総数量 `2` の Request を送る。

送料取得は注文・明細の保存と在庫減算より前にある。  
失敗後に `OrderRepository` が呼ばれず、`ProductRepository` の在庫更新が呼ばれないことを Mockito で確認する。

ここで観測するのは Repository Mock の interaction である。  
実 DB を読み取って未更新を確認したことや、先行する DB 更新が Rollback されたことを意味しない。

Service は constructor で直接生成しており、Spring の Transaction Proxy を通らない。  
method に `@Transactional` が付いていても、この Test に業務 Transaction が自動で適用されるとは考えない。

### Spring Context を使う理由と起動範囲

WireMock に接続するだけなら、Spring Context は必須ではない。  
今回は Production の `RestClientConfig` と Boot の Builder・JSON 変換を含めて確認するため、Client 用の Slice を使用する。

```java
@RestClientTest(HttpShippingFeeClient.class)
@Import(RestClientConfig.class)
@AutoConfigureMockRestServiceServer(enabled = false)
```

`@RestClientTest` は HTTP Client のテストに必要な構成へ範囲を限定する。  
`@Import` で実 `RestClientConfig` を明示し、その設定が生成する `shippingFeeRestClient` を実 Client に注入する。

`RestClientConfig` は、Boot の Builder に Base URL を渡して `RestClient` を生成する。  
Test 専用の Client 実装や、異なる JSON 変換設定へ置き換えているわけではない。

2 つの Test は開始時に、Context に `DataSource` と `OrderService` の Bean がないことも確認する。  
Service の代表 Test で使う `OrderService` と Repository Mock は、Test 内で生成する Java Object である。

### MockRestServiceServer を無効にする理由

`@RestClientTest` は標準で `MockRestServiceServer` を自動構成する。  
これは実 HTTP Server を用意せず、Client の Request をテスト用の仕組みで扱う別の方法である。

今回は WireMock まで実 HTTP を通すため、`@AutoConfigureMockRestServiceServer(enabled = false)` を指定する。  
この指定を読み落とすと、「HTTP Client 用 Slice を使うこと」と「HTTP 通信を省略すること」を同じ意味として理解してしまう。

Slice Test であることと、実通信を含む Integration Test であることは両立する。  
Annotation の名前だけで分類せず、実際に通る部品と境界から判断する。

### 動的ポートと Base URL

動的ポートは、Server の起動時に利用可能なポートを割り当てる方法である。  
固定ポートを共有するより、通常環境や別のテストとの競合を避けやすい。

```java
.options(wireMockConfig().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true))
```

割り当てたポートは `@DynamicPropertySource` で実設定へ渡す。

```java
registry.add("external.shipping-fee.base-url", () -> "http://127.0.0.1:" + wireMock.getPort());
```

supplier は property を解決するときに起動済み Server の実ポートを読み取る。  
通常用の `application.yml` にある `http://localhost:8081` から、テストが所有する接続先へ差し替えるための設定である。

Base URL は Server の接続先、`CALCULATE_PATH` はその Server 内の操作を表す。  
動的ポートを使用しても、検証する Path `/shipping-fees/calculate` は変わらない。

### WireMock Server の Lifecycle

Lifecycle は、Server や Context をいつ生成し、どの期間利用し、いつ破棄するかという管理の単位である。  
本教材では、各 Test Class が static の `WireMockExtension` を所有する。

```text
クラス開始: Extension が WireMock Server を起動
  ↓
Test Instance を生成し、Spring が field injection
  └─ 初回は動的 URL を使って Client 用 Context を構築
  ↓
各 invocation の前: Extension が Stub・Request 記録を reset
  ↓
@BeforeEach: 空の Fixture と Context の境界を確認
  ↓
Test Method: Stub 登録 → 実 Client 呼び出し → Assertion / verification
  ↓
各 invocation の後: unmatched request の検査
  ↓
クラス終了: Server の停止と Context の破棄
```

`@TestInstance(PER_METHOD)` では、Test Instance を invocation ごとに生成する。  
Server は static Extension のためクラス内で共有し、Context もクラス内で再利用され得る。

動的ポートは「毎回の Test Method で必ず別ポートになる」という指定ではない。  
今回の Server はクラス開始時にポートを割り当て、状態の分離は invocation ごとの reset で行う。

`@DirtiesContext(AFTER_CLASS)` はクラス終了時に Context をキャッシュから除外する。  
停止した Server の接続先を含む Context を後の Test へ持ち越さないための指定である。

### Stub・Request 記録の reset と Test Isolation

Test Isolation は、ほかの Test が先に動いたかどうかで結果が変わらないよう、必要な状態を独立させることである。  
WireMock では Stub と Request Journal の両方が、次の Test へ影響し得る。

| 持ち越す状態      | 起こり得る問題                                       |
| ----------------- | ---------------------------------------------------- |
| 前の Stub         | 今回登録していない Response が返り、準備不足を見逃す |
| 前の Request 記録 | 今回送っていない Request を verification が数える    |

`resetOnEachTest(true)` を明示し、Extension に reset を任せる。  
本教材は default のファイル mapping を用意しないため、各 invocation は空の Stub・空の記録から始まる。

`verifyIsolatedHttpClientBoundary()` は、その空の状態を `@BeforeEach` で確認する。  
Test 内で先に reset してから空を確認する構成ではないため、Extension の準備が成立しているかも観測できる。

正常ケースの `@RepeatedTest(2)` は、同じ入力を 2 回実行する。  
再現性を作る中心は繰り返し Annotation 自体ではなく、各回の reset・Fixture・期待値が独立していることである。

### instance DSL と static な matcher builder

DSL は、対象の操作を表現しやすくするための API のまとまりである。  
WireMock の `post(...)`、`aResponse()`、`equalToJson(...)` などは、HTTP 条件や応答を組み立てるために使用する。

本教材は、Server への操作を `wireMock.stubFor(...)` と `wireMock.verify(...)` のように対象インスタンスへ向ける。  
`configureStaticDsl(false)` とし、グローバルな接続先の設定に依存させない。

一方、matcher を組み立てる method は static import している。  
static な method をすべて避けるという意味ではなく、条件の生成と、どの Server を操作するかという共有状態を区別する。

`@Execution(SAME_THREAD)` はクラス内の Test を逐次実行する指定である。  
reset と Stub 登録を行う共有 Server に、同じクラスの複数 invocation を同時に操作させない。

### 通常確認用 Compose / WireMock との違い

通常確認用の `compose.yaml` は、手動で動かすアプリケーションへ送料 API 相当の応答を提供する。  
自動テストのために、その Server の Stub や受信記録を変更する構成ではない。

| 比較項目     | 通常確認用                              | Unit 08 の自動テスト用            |
| ------------ | --------------------------------------- | --------------------------------- |
| 起動の管理   | Docker Compose                          | JUnit の WireMock Extension       |
| Server       | `wiremock/wiremock:3.13.2` の Container | テスト JVM 内の WireMock          |
| 接続先       | `localhost:8081`                        | `127.0.0.1` の動的ポート          |
| Stub         | `docker/wiremock/mappings/` のファイル  | Test 内で登録する Java DSL        |
| 応答         | 通常確認用の送料 `800`                  | 正常・500・不正 Body などケース別 |
| 状態の初期化 | 通常確認の運用で管理                    | Test の各 invocation 前に reset   |
| Docker       | 必要                                    | Unit 08 の 2 クラスには不要       |

通常用 mapping は Method と Path に応じて送料を返す。  
Unit 08 ではそれに加えて Request の Header・JSON・回数を自動検証する。

Image とライブラリの version がどちらも `3.13.2` でも、同じ Server を利用しているわけではない。  
自動テストは通常用 mapping を volume mount せず、Compose の起動を前提にしない。

### Unit 07・09 との境界と実行コスト

Unit 07 は、Service・Repository・PostgreSQL の連携と業務 Transaction を確認する。  
Unit 08 は、外向きの HTTP Client とその接続先に焦点を移す。

Unit 09 では、起動したアプリケーションへ HTTP Request を送り、Controller から DB・外部 API 境界までを含める予定である。  
Unit 08 に実 HTTP 通信があることと、アプリケーションの HTTP 入口を確認していることを区別する。

| 比較対象                       | 主な起動・処理コスト                                             |
| ------------------------------ | ---------------------------------------------------------------- |
| Unit 03 の Plain Unit Test     | Java Object と Mockito の準備                                    |
| Unit 07 の DB Integration Test | Context、Container、DB 接続、SQL、Fixture・Cleanup               |
| Unit 08 の Client Test         | Client 用 Context、WireMock Server、JSON 変換、loopback HTTP     |
| Unit 09 の予定する API Test    | アプリの Web Server、広い Context、DB、WireMock、入口からの HTTP |

一般に、本物として組み合わせる範囲が増えると、準備と失敗原因の切り分けも増える。  
一方、実行時間は Context Cache、初期化、マシンの状態などに影響されるため、名前だけで厳密な速度順を決めない。

本 Unit の実測値は「実行・確認」に第1回の記録として示す。  
一般的なコストの説明と、特定環境で測った時間は分けて読む。

## 使用する技術・ツール

### 実行基盤と依存関係

| 技術・ツール           | 今回の役割                                                        |
| ---------------------- | ----------------------------------------------------------------- |
| Java 21                | アプリケーションと Test の実行、record・text block などの言語機能 |
| Spring Boot 4.1.1      | Client 用 Slice、Builder・JSON 変換の構成                         |
| Spring `RestClient`    | 同期 HTTP Request の送信と Response の読み取り                    |
| JUnit Jupiter          | Test、Parameterized Test、Repeated Test、Extension の Lifecycle   |
| AssertJ                | 戻り値、例外、JSON の値、受信記録などの Assertion                 |
| Mockito                | Service Test の Repository の置き換えと interaction verification  |
| WireMock 3.13.2        | JVM 内の HTTP Server、Stub、Request Journal、verification         |
| Jackson / `JsonMapper` | JSON 変換、および受信 Request の JSON 型・値の観測                |
| Maven Wrapper 3.9.16   | プロジェクトで指定した Maven の実行                               |
| Maven Surefire         | 現在の `*Test` クラスの実行と Text / XML Report                   |

POM では `spring-boot-starter-restclient-test` と `wiremock-standalone` を test scope で追加している。  
Boot の Test Starter の version は既存の Boot 管理に委ね、WireMock は `wiremock.version` で `3.13.2` に固定する。

standalone 版は依存の多くを shaded package に隔離した配布形式であり、Boot が管理する依存との競合を抑えるために選んでいる。  
名前が standalone でも、本教材では別プロセスを立ち上げず、JUnit の JVM 内で利用する。

Compose の Image version を、そのまま Maven ライブラリ選択の根拠にしたわけではない。  
用途と依存構成を分けて判断しており、同じ version 番号でも管理対象は別である。

### 主な Annotation と観測 API

| 要素                                                   | 今回の用途                                         |
| ------------------------------------------------------ | -------------------------------------------------- |
| `@RestClientTest`                                      | HTTP Client 用の Spring Context に範囲を限定する   |
| `@Import(RestClientConfig.class)`                      | Production の Client 設定を読み込む                |
| `@AutoConfigureMockRestServiceServer(enabled = false)` | WireMock まで実 HTTP を通す                        |
| `@RegisterExtension`                                   | JUnit に WireMock Server の管理を任せる            |
| `@DynamicPropertySource`                               | 実ポートを含む Base URL を設定する                 |
| `@TestInstance(PER_METHOD)`                            | invocation ごとに Test Instance を生成する         |
| `@Execution(SAME_THREAD)`                              | クラス内の invocation を逐次実行する               |
| `@DirtiesContext(AFTER_CLASS)`                         | Server の利用期間に合わせて Context を破棄する     |
| `@BeforeEach`                                          | Stub・記録の初期状態と Context の境界を確認する    |
| `@RepeatedTest(2)`                                     | 同じ正常ケースを独立した Fixture で繰り返す        |
| `@ParameterizedTest` / `@MethodSource`                 | 4 種類の不正 Response を読みやすく並べる           |
| `wireMock.verify(...)`                                 | HTTP Request の条件・回数を確認する                |
| `getAllServeEvents()`                                  | 受信記録全体の件数を確認する                       |
| `findRequestsMatching(...)`                            | 記録された Request Body を取り出す                 |
| `catchThrowableOfType(...)`                            | 例外を取得し、Status・Body・cause を詳しく確認する |

## テスト対象とテスト境界

### Client Test の SUT と依存

中心となる SUT は `HttpShippingFeeClient` である。  
テストの入口は `calculateShippingFee(...)` という Java method であり、アプリケーションの Controller へ HTTP Request を送る構成ではない。

| 要素                                         | 扱い                               |
| -------------------------------------------- | ---------------------------------- |
| `HttpShippingFeeClient`                      | 実物を Spring から注入             |
| `RestClientConfig` / `shippingFeeRestClient` | 実設定と実 Bean                    |
| Request / Response DTO                       | 実物。constructor の制約も通す     |
| JSON 変換・HTTP 通信                         | 実物                               |
| Shipping Fee API                             | WireMock の HTTP-level Test Double |
| `OrderService` / Repository / DB             | Client Test の処理経路に含めない   |
| アプリケーションの Web Server                | 起動しない                         |

### Service 代表 Test の SUT と依存

`OrderServiceShippingFeeHttpTest` は SUT に実 `OrderService` を含める。  
Client 用 Slice から実 Client を取得し、Test 内の constructor 呼び出しで Service へ渡す。

```text
Test → OrderService（直接生成）
         ├─ ProductRepository の Mock
         ├─ OrderRepository の Mock
         ├─ Clock.fixed(...) の実 Object
         └─ 実 HttpShippingFeeClient → 実 RestClient / DTO → HTTP → WireMock
```

Repository Mock は SQL を実行せず、必要な商品の読み取り結果だけを Stub する。  
送料 Client は Mock にせず、WireMock が返す 500 を起点に例外を発生させる。

固定 Clock は時刻を外部状態にしないための準備である。  
このケースは送料取得で失敗するため、注文日時を生成する後続処理まで到達しない。

### 実装した Test Method と展開件数

次の表は `HttpShippingFeeClientTest` の Test Method である。

| Test Method                                                            | 件数 | 主な保証                                |
| ---------------------------------------------------------------------- | ---: | --------------------------------------- |
| `calculateShippingFee_validResponse_returnsFeeAndSendsExpectedRequest` |    2 | 正常送料・Request・各回の独立性         |
| `calculateShippingFee_zeroFee_preservesDifferentRequestValues`         |    1 | 送料 0・別入力・先頭 0 の郵便番号       |
| `calculateShippingFee_serverError_throwsHttpExceptionWithResponse`     |    1 | HTTP 500・Response Body の保持          |
| `calculateShippingFee_invalidResponse_throwsJsonConversionException`   |    4 | 不正 JSON・欠落・null field・負数       |
| `calculateShippingFee_emptyBody_throwsClientGuardException`            |    1 | 空 Body に対する Client の検査          |
| `calculateShippingFee_jsonNull_throwsClientGuardException`             |    1 | JSON 全体の null に対する Client の検査 |
| `calculateShippingFee_invalidQuantity_rejectsBeforeHttpRequest`        |    1 | 送信前の拒否・受信 0 件                 |

Client Test は 7 つの Test Method が、繰り返し・パラメータ展開後に 11 件となる。  
`OrderServiceShippingFeeHttpTest` の次の 1 件を加え、Unit 08 は合計 12 件である。

- `createOrder_httpServerError_preservesCauseAndDoesNotWriteRepositories`

### 保証する範囲と今回通らない経路

今回確認するのは、送信する Method・URL・必要な Header・Request JSON・回数、代表 Response の読み取り、例外の識別、Service の変換経路である。  
さらに、Stub・Request 記録を各 invocation へ持ち越さない前提を確認する。

実 DB の SQL、保存後の値、Transaction Proxy、業務 Rollback は今回の新規 Test の観測対象に含めない。  
`GlobalExceptionHandler` による API の `502` への変換も、Unit 08 の HTTP 500 Test だけで確認したとは扱わない。

外部 API の全障害、Timeout・Retry・HTTPS・実ネットワーク経路を網羅する構成ではない。  
カリキュラムの正常・500・不正 JSON・不完全 Response を中心に、既存 Client が扱う代表的な条件を確認する。

## 使用するファイル

### この Unit で追加・変更する主なファイル

| ファイル                                                                                                                        | 役割                                               |
| ------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------- |
| [`pom.xml`](../../pom.xml)                                                                                                      | Client Test Starter と WireMock の test scope 依存 |
| [`HttpShippingFeeClientTest.java`](../../src/test/java/com/example/orderapi/client/HttpShippingFeeClientTest.java)              | Client の HTTP Contract、正常・異常、Isolation     |
| [`OrderServiceShippingFeeHttpTest.java`](../../src/test/java/com/example/orderapi/service/OrderServiceShippingFeeHttpTest.java) | 実 HTTP 障害と Service の業務例外変換              |

### コードリーディングで参照する Production Code・設定

- [`ShippingFeeClient.java`](../../src/main/java/com/example/orderapi/client/ShippingFeeClient.java)：Service から見た送料取得の interface。
- [`HttpShippingFeeClient.java`](../../src/main/java/com/example/orderapi/client/HttpShippingFeeClient.java)：POST、Request DTO、Response の読み取り、null 検査。
- [`ShippingFeeRequest.java`](../../src/main/java/com/example/orderapi/client/dto/ShippingFeeRequest.java)：送信値と送信前の制約。
- [`ShippingFeeResponse.java`](../../src/main/java/com/example/orderapi/client/dto/ShippingFeeResponse.java)：受信値と必須・負数検査。
- [`RestClientConfig.java`](../../src/main/java/com/example/orderapi/config/RestClientConfig.java)：Boot の Builder と Base URL による Client Bean の生成。
- [`OrderService.java`](../../src/main/java/com/example/orderapi/service/OrderService.java)：送料取得前の業務チェック、引数の集計、例外変換、書き込みとの順序。
- [`ShippingFeeServiceException.java`](../../src/main/java/com/example/orderapi/exception/ShippingFeeServiceException.java)：cause を保持する業務例外。
- [`Product.java`](../../src/main/java/com/example/orderapi/domain/Product.java)：Service Test の商品 Fixture。
- [`CreateOrderCommand.java`](../../src/main/java/com/example/orderapi/service/model/CreateOrderCommand.java)・[`CreateOrderItemCommand.java`](../../src/main/java/com/example/orderapi/service/model/CreateOrderItemCommand.java)：Service の入力。
- [`ProductRepository.java`](../../src/main/java/com/example/orderapi/repository/ProductRepository.java)・[`OrderRepository.java`](../../src/main/java/com/example/orderapi/repository/OrderRepository.java)：Service Test で Mock にする境界。
- [`GlobalExceptionHandler.java`](../../src/main/java/com/example/orderapi/exception/GlobalExceptionHandler.java)：業務例外と API の HTTP Status の関係を読むための参照先。
- [`application.yml`](../../src/main/resources/application.yml)：通常用 Base URL と DB 設定。
- [`compose.yaml`](../../compose.yaml)・[`shipping-fee-normal.json`](../../docker/wiremock/mappings/shipping-fee-normal.json)：通常確認用 Server と mapping。
- [`maven-wrapper.properties`](../../.mvn/wrapper/maven-wrapper.properties)：Wrapper が使用する Maven の version。

### 比較する既存 Test

- [`OrderServiceMockitoTest.java`](../../src/test/java/com/example/orderapi/service/OrderServiceMockitoTest.java)：Java Object 境界での例外 Stub と cause の同一性。
- [`OrderServiceCreateIntegrationTest.java`](../../src/test/java/com/example/orderapi/service/OrderServiceCreateIntegrationTest.java)：実 DB と組み合わせた送料 Client 失敗時の観測。

## 前提

### 既習事項

Unit 01〜07 の JUnit、AssertJ、Fixture、Mockito、Constructor Injection、Spring Context・Slice Test、DB Integration Test の位置づけを前提とする。  
特に、Unit 03・07 の `ShippingFeeClient` の置き換えと、Service の例外変換を読み比べられる状態が望ましい。

HTTP の Stub、Request Journal、動的 URL、WireMock の Lifecycle は今回の学習対象である。  
既習事項として説明を省略せず、本 README と実コードを対応させて理解する。

### Unit 08 の実行環境

- Java 21 と Maven Wrapper を実行できる環境。
- Client Test Starter・WireMock を含む Maven の依存関係と Plugin。
- JVM 内で HTTP Server を起動し、loopback 通信できる権限・環境。

Unit 08 の新規 2 クラスには Docker、PostgreSQL、通常確認用の Compose は不要である。  
Spring Boot アプリケーションや WireMock を別の Terminal から起動する準備も必要ない。

通常の学習環境は Windows + Git Bash を想定する。  
以降のコマンドはリポジトリのルートで実行し、PowerShell では `./mvnw` を `.\mvnw.cmd` に読み替える。

### 全体実行で追加される前提

`./mvnw test` は、Unit 08 だけでなく既存 Unit 06・07 の DB Test も実行する。  
この場合は Linux Container を起動できる Docker Engine と、既存の PostgreSQL・Testcontainers 補助 Image が必要となる。

Unit 08 単独と全体実行の必要環境を混同しない。  
全体実行で利用する DB も Testcontainers のテスト専用 DB であり、通常用 DB や Compose の永続 Volume を使用しない。

### 初回取得と offline 実行

初回は Maven の依存関係・Plugin・Wrapper 本体の取得が必要になる場合がある。  
取得が必要な状態では、Maven Central などへアクセスできる環境を用意する。

Maven の `-o` は、取得済みの Maven キャッシュを使う指定である。  
WireMock へのローカル HTTP 通信を無効にする指定ではなく、未取得の Wrapper 本体や Docker Image を用意する指定でもない。

## コードリーディング

### 読む順序の全体像

```text
ShippingFeeClient / HttpShippingFeeClient
  ↓
Request / Response DTO と RestClientConfig
  ↓
Client Test の Slice・Server・動的 URL・初期状態
  ↓
正常 Stub → 実行 → 戻り値 → Request verification
  ↓
送料 0・500・不正 Response・空 Body・送信前拒否
  ↓
OrderService と Service の代表 Test
  ↓
Unit 03・07 との保証範囲の比較
```

### 1. interface が隠す HTTP の詳細を確認する

`ShippingFeeClient.calculateShippingFee(...)` の引数と戻り値を読む。  
Service が必要とする操作だけを interface に置き、URL や JSON を引数へ持ち込んでいないことを確認する。

同じ interface があるため、Unit 03・07 では Java Object を置き換え、今回は実装を通すという選択ができる。

### 2. Client の POST と Response 読み取りを追う

`HttpShippingFeeClient.calculateShippingFee(...)` を読む。  
Request DTO の生成、`post()`、`uri(...)`、`body(...)`、`retrieve()`、Response DTO の取得を順に追う。

最後の `response == null` と `response.shippingFee()` を確認する。  
通信が成功したことと、送料として返せる値を取得したことを分けている箇所である。

### 3. 送信前と受信時の DTO の制約を比較する

`ShippingFeeRequest` と `ShippingFeeResponse` の constructor を読む。  
Request の数量検査は送信前、Response の必須・負数検査は受信 JSON の変換時に適用されることを確認する。

HTTP Test で観測する例外が、DTO を直接生成した場合と同じとは限らない理由を整理する。

### 4. 実 RestClientConfig の Bean 生成を確認する

`shippingFeeRestClient(...)` を読む。  
Boot の `RestClient.Builder` と、`external.shipping-fee.base-url` を受け取り、Base URL を設定していることを確認する。

Client constructor の `@Qualifier("shippingFeeRestClient")` と Bean 名の対応も確認する。

### 5. Client Test の Annotation と Context の範囲を読む

`HttpShippingFeeClientTest` の class 宣言前を読む。  
`@RestClientTest`、実設定の `@Import`、MockRestServiceServer の無効化を対応させる。

`@SpringBootTest` でアプリ全体を構成する方法との違いを確認する。  
今回は HTTP Client の保証に必要な構成を選んでいる。

### 6. WireMock の所有者と起動設定を読む

static の `wireMock` field と `@RegisterExtension` を読む。  
loopback の bind、動的ポート、HTTP/2 plain の無効化、instance DSL、reset、unmatched request の検査を確認する。

どのクラスが Server を所有し、どの Test が同じ Server を使うかを説明できるようにする。

### 7. 動的 URL を実 Client へ渡す経路を追う

`shippingFeeBaseUrl(...)` を読み、supplier の値が `RestClientConfig` の property へ渡ることを確認する。  
通常用 `application.yml` の `8081` を Test が参照し続ける構成ではないことを確認する。

Test Method ごとに URL を書き換える処理がないことと、Server のクラス単位の Lifecycle を対応させる。

### 8. 各 invocation の初期状態を確認する

`verifyIsolatedHttpClientBoundary()` を読む。  
Stub・Request 記録が空であることと、DataSource・Service Bean がないことを確認している。

手動 reset を先に実行して空にする method ではなく、Extension が準備した状態を観測する method である。

### 9. 正常ケースの Arrange・Act・Assert を読む

`calculateShippingFee_validResponse_returnsFeeAndSendsExpectedRequest()` を読む。  
Stub の Method・URL・Header・Body と、返す Status・Header・Body を分けて追う。

Act は実 Client の method 呼び出しである。  
その後の `isEqualByComparingTo("800")` と `verifySingleRequest(...)` が異なる観測をしていることを確認する。

`@RepeatedTest(2)` の各回が、同じ空の初期状態から始まることも対応させる。

### 10. 実送信の verification を詳しく読む

`verifySingleRequest(...)` を読む。  
一致する Request の回数、全受信記録の件数、記録から取り出した JSON の型と値という順に確認する。

`EXPECTED_REQUEST` が Production DTO の serialization 結果ではなく、明示した期待値であることを確認する。

### 11. 別の正常入力と送料 0 を読む

`calculateShippingFee_zeroFee_preservesDifferentRequestValues()` を読む。  
郵便番号・小計・数量を変えても実際の送信値が追随し、送料 0 が成功することを確認する。

先頭 0 の保持と、送料の下限を同じケースで確認する Fixture の意味を整理する。

### 12. HTTP 500 の例外を読む

`calculateShippingFee_serverError_throwsHttpExceptionWithResponse()` を読む。  
Stub の Status が 500 であること、`catchThrowableOfType(...)` で HTTP 例外を取得すること、Status・Body を確認することを対応させる。

この method が Service を呼んでいないため、業務例外の変換までは確認していないことも整理する。

### 13. 不正 Response のデータと Assertion を対応させる

`invalidResponses()` と `calculateShippingFee_invalidResponse_throwsJsonConversionException(...)` を一緒に読む。  
4 つの raw Body が、それぞれ異なる不備を表していることを確認する。

`stubJsonResponse(...)` は Status 200 と JSON の Header を設定する。  
それでも失敗することにより、Status の成功と Body の妥当性が別であると分かる。

Assertion の外側の型と cause を確認し、Jackson 内部の細かな例外型を期待値にしていないことを読む。

### 14. 空 Body と JSON 全体の null を比較する

`calculateShippingFee_emptyBody_throwsClientGuardException()` と `calculateShippingFee_jsonNull_throwsClientGuardException()` を読む。  
一方は Body を設定せず、もう一方は `"null"` を返している。

両方が Client の null 検査へ到達し、同じ例外メッセージを確認することを読む。  
`invalidResponses()` の null field との違いも整理する。

### 15. 送信前拒否と受信 0 件を読む

`calculateShippingFee_invalidQuantity_rejectsBeforeHttpRequest()` を読む。  
Stub を登録せず、数量 0 の例外と受信記録 0 件を確認している。

不正な入力を Server が拒否したケースではなく、Request DTO の生成段階で止まったケースである。

### 16. Service の送料取得と書き込み順を読む

`OrderService.createOrder(...)` と `requestShippingFee(...)` を読む。  
商品の事前検査と引数の集計、送料取得、注文・明細・在庫の書き込みという順序を確認する。

Client の例外を包む catch の範囲と、`ShippingFeeServiceException` の constructor が cause を保持することを確認する。

### 17. Service Test の Fixture と依存を読む

`OrderServiceShippingFeeHttpTest.createOrder_httpServerError_preservesCauseAndDoesNotWriteRepositories()` を読む。  
単価 3000・数量 2 の実 Product / Command と、読み取り結果を返す Repository Mock を確認する。

Service は直接生成する一方、送料 Client は Spring から注入した実物である。  
HTTP 失敗を Mockito の `thenThrow` で作っていないことを確認する。

### 18. Service の cause と禁止される書き込みを読む

同じ Test の Assertion と verification を読む。  
外側の業務例外、cause の HTTP Status・Body、Request の内容・回数、Repository 書き込み未呼び出しを順に確認する。

受信記録は WireMock、Repository の interaction は Mockito が観測している。  
異なる境界に異なる Test Double を使っていることを整理する。

### 19. Unit 03・07 の送料失敗 Test と比較する

`OrderServiceMockitoTest.createOrder_whenShippingFeeClientFails_wrapsExceptionAndDoesNotWriteDatabase()` と、`OrderServiceCreateIntegrationTest.createOrder_shippingFailure_preservesDatabaseAndCause()` を読む。  
前者は Java method の Stub と書き込み未呼び出し、後者は実 DB の前後状態も確認している。

今回の Test は実 HTTP 障害から cause がつながることを補う。  
どの Test も同じ責務を重複しているだけではなく、通す依存と観測対象が異なる。

## 実行・確認

### 1. 実行対象と必要環境を確認する

Unit 08 の新規 Test は Java・Maven・loopback HTTP の利用が可能であれば実行できる。  
通常用のアプリケーション、Compose、PostgreSQL を事前起動する手順は含めない。

以降のコマンドはリポジトリのルートで実行する。  
通常のコマンドは必要に応じて Maven 依存を取得するため、取得済みの場合は後述の offline 実行も利用できる。

### 2. 新規 2 クラスを個別に実行する

```bash
./mvnw -Dtest=HttpShippingFeeClientTest test
```

Client Test の期待件数は 11 件である。  
正常系 2 回、不正 Response の 4 条件、それ以外の各ケースを含む。

```bash
./mvnw -Dtest=OrderServiceShippingFeeHttpTest test
```

Service の代表 Test の期待件数は 1 件である。  
実 HTTP 500 からの業務例外変換と、書き込み未呼び出しを確認する。

### 3. Unit 08 をまとめて実行する

```bash
./mvnw "-Dtest=HttpShippingFeeClientTest,OrderServiceShippingFeeHttpTest" test
```

期待件数は合計 12 件である。  
それぞれのクラスが Server と動的 URL を所有し、クラスの終了時に Context を破棄する。

PowerShell での同じコマンドは次の形となる。

```powershell
.\mvnw.cmd "-Dtest=HttpShippingFeeClientTest,OrderServiceShippingFeeHttpTest" test
```

クラス名の一覧を引用符で囲み、Shell による解釈の違いを避ける。

### 4. 特定の Test Method を読みながら実行する

正常系の繰り返しを確認する場合は、次の selector を使う。

```bash
./mvnw "-Dtest=HttpShippingFeeClientTest#calculateShippingFee_validResponse_returnsFeeAndSendsExpectedRequest" test
```

1 つの Test Method を選ぶが、`@RepeatedTest(2)` により 2 件実行される。  
各回の最初に Stub・Request 記録が空であることも確認する。

不正 Response の組み合わせは次の method で確認できる。

```bash
./mvnw "-Dtest=HttpShippingFeeClientTest#calculateShippingFee_invalidResponse_throwsJsonConversionException" test
```

`invalidResponses()` の 4 条件が実行される。  
ケース名と raw Body、例外の cause を対応させる。

Service との接続部分は次の method を選択できる。

```bash
./mvnw "-Dtest=OrderServiceShippingFeeHttpTest#createOrder_httpServerError_preservesCauseAndDoesNotWriteRepositories" test
```

これらは学習時に対象を絞るためのコマンドである。  
第1回の検証記録は、後述するクラス個別・Unit 一括・全体実行の結果として読む。

### 5. 既存の Java Object 境界と読み比べる

```bash
./mvnw -Dtest=OrderServiceMockitoTest test
```

現在の `OrderServiceMockitoTest` は 6 件である。  
同じ送料失敗でも、こちらは Mockito が Java の例外を送出し、WireMock Server は起動しない。

Unit 08 と読み比べ、Service の責務と HTTP Client の責務を分けて説明できるか確認する。

### 6. 既存 DB Test を含む全 Test を実行する

Docker Engine と既存 Testcontainers 用 Image を利用できる環境で実行する。

```bash
./mvnw test
```

Unit 08 の第1回時点では、全体は 167 件である。  
内訳には新規 12 件と既存 155 件が含まれ、Unit 06 の 64 件、Unit 07 の 23 件も実行される。

このコマンドはテスト専用 PostgreSQL を起動し、その DB で Schema・Fixture・Cleanup を実行する。  
Unit 08 単独の必要環境へ、DB Test の前提が追加されることを意識する。

通常用 DB や Compose の永続 Volume を初期化するコマンドへ置き換えない。

### 7. 取得済み環境では offline 実行を使う

```bash
./mvnw -o "-Dtest=HttpShippingFeeClientTest,OrderServiceShippingFeeHttpTest" test
```

Maven の依存関係・Plugin が取得済みであれば、offline で同じ対象を実行できる。  
WireMock の HTTP Server とローカル通信は通常どおり利用する。

全体を offline 実行する場合も、Docker Engine と必要な Image は別に必要である。  
Maven の `-o` だけで Container Image の取得や Docker 側の通信方針まで制御できるわけではない。

### 8. コンパイル確認と実 HTTP の成功を分ける

```bash
./mvnw -DskipTests test-compile
```

これはソースと Test のコンパイル確認であり、WireMock の起動・Request の送信・Assertion の成功までは確認しない。  
Client Test の実行が成功して初めて、対象ケースの実 HTTP 経路を通した結果を確認できる。

| 確認              | 分かること                                    |
| ----------------- | --------------------------------------------- |
| POM の構文確認    | Maven 設定として読み取れること                |
| `test-compile`    | Test Code までコンパイルできること            |
| Unit 08 の `test` | 実 HTTP と対象 Assertion が成功すること       |
| 全体の `test`     | 既存 DB Test を含む対象スイートが成功すること |
| CI                | CI の実行環境・設定で成功すること             |

これらを同じ成功として扱わず、実際に実行した段階を明確にする。

### 9. 件数・Report・Request 記録の読み方

Unit 08 の一括実行では、結果の要点は次の形となる。

```text
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

全体実行では、第1回時点の要点は次の形である。

```text
Tests run: 167, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

JUnit の件数は Test Method のソース上の個数と同じとは限らない。  
繰り返しと Parameterized Test が invocation に展開されるため、7 つの Client Test Method は 11 件として数える。

詳細は次の Surefire Report で確認できる。

```text
target/surefire-reports/
  com.example.orderapi.client.HttpShippingFeeClientTest.txt
  com.example.orderapi.service.OrderServiceShippingFeeHttpTest.txt
  TEST-com.example.orderapi.client.HttpShippingFeeClientTest.xml
  TEST-com.example.orderapi.service.OrderServiceShippingFeeHttpTest.xml
```

Report が存在するだけでは、現在のソースで直前に Test が成功したことを意味しない。  
対象コマンド、実行日時、件数、Report の更新を対応させて読む。

Request Body や DB の全内容を、正常終了時に Console へ常に表示する実装ではない。  
送信内容の根拠は `verifySingleRequest(...)` の Assertion と、Service Test 内の verification も読む。

起動ログに `Found @SpringBootConfiguration` や Boot の banner が表示されても、アプリ全体の DB・Web Server が起動したとは判断しない。  
選択した Slice と、開始時の Bean 境界の Assertion を合わせて確認する。

### 10. 失敗した段階から切り分ける

| 症状・段階                         | 主に確認すること                                                                  |
| ---------------------------------- | --------------------------------------------------------------------------------- |
| 依存解決の失敗                     | Test Starter・WireMock・Plugin の取得状態、offline 指定、外部通信・キャッシュ権限 |
| import / class が見つからない      | POM、Boot 4 の package、WireMock の依存が読み込まれているか                       |
| Client Bean の生成失敗             | `@RestClientTest` の対象、実設定の `@Import`、Base URL の property                |
| Server 起動時の loopback 接続拒否  | 実行環境がポート利用・ローカル通信を許可しているか                                |
| 意図しない Connection refused      | Server の起動期間、動的 URL、通常用 `8081` への誤接続                             |
| WireMock の未一致・404             | Method、URL、Header、JSON が Stub 条件に一致しているか                            |
| 期待しない JSON 変換例外           | Response の Status・Content-Type・Body と、例外の cause                           |
| verification が 0 件               | Client を実際に呼んだか、送信前に失敗していないか、条件が正しいか                 |
| verification が 2 件以上           | 余分な送信、Request 記録の持ち越し、対象条件                                      |
| `@BeforeEach` の初期状態確認が失敗 | reset、default mapping、共有 Server の並行操作、Context の起動範囲                |
| 全体実行だけ Docker 接続で失敗     | Unit 06・07 の環境、Docker Engine、Image。Unit 08 単独の前提とは分ける            |

最初に発生した原因と、後続の Assertion Failure を分けて読む。  
例えば Stub に未一致の Request が 404 を受けた場合、意図した 500 Response を Client が受け取れたことにはならない。

不正 Response のケースでは、期待した例外が発生することが成功条件である。  
Stack Trace に例外名があるかだけでなく、どの Test が何を期待していたかを確認する。

環境の通信制限を、送料の仕様変更や Production Code の不自然な回避策で解消しない。  
実行環境の権限、Test の構成、対象コードの処理を順に切り分ける。

### 11. 後始末を確認する

Unit 08 の Server は Extension がクラス終了時に停止し、Context もクラス終了時に破棄する。  
通常確認用の Server を起動・停止する手順は含めない。

全体実行では、既存 Testcontainers の Container 管理と後始末も確認する。  
必要に応じて、稼働 Container を読み取り確認する。

```bash
docker ps
```

確認のために通常用 Container や Volume を一括停止・削除する必要はない。  
自分の Test が所有したリソースと、ほかの作業のリソースを区別する。

## 学習ポイント

### 保証したい責務から Test Double の境界を選ぶ

Service の判断を確認するなら、Client の Java method を制御する方法が使える。  
HTTP の組み立てと変換を確認するなら、Client を本物として通し、接続先を制御する。

「Mock を使うか」だけでなく、「どの境界で、何を本物として確認するか」を説明できることが重要である。

### 応答の準備と実行結果の観測を分ける

Stub は必要な条件を作るための準備である。  
戻り値・例外・Request 記録の Assertion / verification は、実際に起きたことを観測する処理である。

準備した内容を読み返すだけでなく、それが実行された根拠を確認する。

### 成功の判定を段階ごとに考える

接続できたこと、HTTP 200 を受け取ったこと、JSON を読めたこと、必須値と値域が妥当なことは異なる条件である。  
どの層が何を検査するかを分けると、正常と異常の境界を明確にできる。

### 例外は型と情報の両方から読む

例外が発生した事実だけで成功とせず、HTTP Status、Response Body、cause、後続処理の有無を確認する。  
一方で、内部ライブラリの細かな文言や動的ポートまで期待値へ固定しない。

Assertion は、必要な不具合を検出できる具体性と、無関係な変更へ依存しない安定性の両方を持たせる。

### 状態を共有する単位と初期化する単位を分ける

Server や Context をクラス内で再利用しても、各 Test の Fixture と受信記録は独立させる。  
共有期間・reset・停止の組み合わせまで含めて、Test Isolation を設計する。

### 広い Test と狭い Test を補い合わせる

Unit 07 の DB Test、Unit 08 の Client Test、Unit 09 の予定する API Test は、それぞれ異なる接続部分を確認する。  
広い Test があることを理由に、失敗箇所を切り分けやすい狭い Test の意味を失わない。

### 実行した範囲を根拠とともに説明する

コンパイル、実 HTTP、実 DB、CI を区別し、どのコマンドと Assertion で確認したかを示す。  
過去の Report と現在の実行、一般的なコストと個別の実測時間も分けて扱う。

## 補足

### WireMock を使った Test と Contract Test

本 Unit は、想定する Response と現在の Client の連携を確認する。  
WireMock の Stub が成功しても、独立した外部サービスが同じ Contract を現在も実装していることまで証明したとは扱わない。

Consumer と Provider の合意を検証する Contract Test は関連する考え方だが、本カリキュラムではその実践を対象にしていない。  
今回の HTTP matching と、実サービスとの契約検証を同一視しない。

### HTTP の向きと API の入口

Unit 08 の POST は、アプリケーションの外向き通信 `/shipping-fees/calculate` である。  
注文 API の入口である `POST /api/orders` へテストから HTTP Request を送っているわけではない。

Service の代表 Test も Command を入口にしている。  
API Request の Validation、Controller、HTTP 502 の Response JSON などは、この経路の保証へ含めない。

### HTTP/1.1、HTTPS、接続障害の範囲

本教材は `http2PlainDisabled(true)` で plain HTTP の HTTP/2 を無効にする。  
通常確認用の `--disable-http2-plain` と目的をそろえ、今回の Contract を HTTP/1.1 の経路で確認する。

これは HTTPS の証明書、HTTP/2 固有の動作、実 DNS、Timeout・接続切断などの網羅を意味しない。  
それらを実務で扱う場合は、必要な仕様と観測を別に定める。

### BigDecimal と JSON 数値

`BigDecimal` の `equals` は scale の違いにも影響される。  
送料や小計の数値としての一致には `isEqualByComparingTo(...)` を使い、JSON の表記差と金額の意味を分ける。

Request 記録では、文字列の `"6000"` と数値の `6000` を区別する。  
総数量については `isIntegralNumber()` と整数値を確認し、郵便番号は文字列として先頭 0 を保持する。

### 依存ライブラリの package と Test の import

今回の Boot 4 の Test Annotation は `org.springframework.boot.restclient.test.autoconfigure` 配下にある。  
既存 Test の import を正本とし、別世代のサンプルにある package 名をそのまま混ぜない。

Request 記録の JSON 観測には `tools.jackson.databind.json.JsonMapper` を使用する。  
POM の管理する変換基盤と、実コードの import を対応させて読む。

### Unit 09・10 へのつながり

Unit 09 では、実 Web Server の HTTP 入口から Controller・Service・Repository・PostgreSQL・外部 HTTP 境界を組み合わせる。  
今回独立して読んだ Client の処理が、広い API Test のどこへ入るかを対応させる。

Unit 10 では、蓄積した Test Suite の実行管理と Coverage を扱う。  
Unit 08 では現在の Surefire の命名・実行方式を使い、Failsafe や Coverage の仕組みを先行導入していない。
