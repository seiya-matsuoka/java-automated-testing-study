# 06. SQL・Repository・DB Integration Test

## この Unit の目的

この Unit では、明示的な SQL を持つ Repository を実 PostgreSQL に接続してテストし、Java の処理と DB の処理を組み合わせて検証する方法を学ぶ。  
商品取得・在庫更新・注文と注文明細の登録・条件検索を題材に、SQL、Parameter Binding、Row Mapping、DB constraint が実際に連携して動くことを確認する。

Unit 03 では、`OrderService` の外側にある Repository を Mockito の Test Double に置き換え、業務ロジックを確認した。  
今回は、そのとき境界の外側へ置いた JDBC 実装と PostgreSQL を本物として動かす。戻り値を用意する側から、戻り値が SQL と DB から正しく得られることを確かめる側へ視点を移す。

DB Test では、SUT を呼び出すだけでは前提が整わない。  
テーブル定義、接続先、初期データ、Transaction、Cleanup を設計し、他の Test や通常の開発用 DB に依存せず、何度実行しても同じ意味を持つ Test にする必要がある。

そのため、この Unit では Testcontainers によるテスト専用 PostgreSQL と、`@JdbcTest` による JDBC 用 Test Slice を利用する。  
**SQL と DB を本物として確認する境界**と、**Fixture・Transaction・Container の役割を分けて再現性を作る方法**を理解することが中心となる。

## 学習内容

### Repository Test と DB Integration Test

Repository は、アプリケーションが必要とする永続化操作を interface として表し、DB とのやり取りを業務処理から分離する。  
今回の `ProductRepository` と `OrderRepository` はその操作の契約であり、`JdbcProductRepository` と `JdbcOrderRepository` が Spring JDBC と SQL による実装となる。

Repository Test では、取得結果、保存した値、更新条件、並び順など、その Repository が提供する振る舞いを確認する。  
SQL を持つ実装の正しさを確認する場合、Repository 自体を mock にすると SQL は実行されず、Column 名の誤りや JOIN の不備、DB の型との不整合を検出できない。

DB Integration Test は、Java Code、JDBC Driver、DB の実行結果を組み合わせる Test である。  
今回の Repository Test は実 PostgreSQL と連携するため、DB Integration Test でもある。「Repository Test」は主な対象、「Integration Test」は実際に組み合わせる範囲を表している。

```text
Unit 03:
Test → 本物の OrderService → Repository の Test Double
                            └─ 戻り値・例外・呼び出しを制御する

Unit 06:
Test → 本物の JDBC Repository → Spring JDBC → JDBC Driver → PostgreSQL
                            └─ SQL・保存状態・取得結果を確認する
```

Integration Test という名前だけで、HTTP Server や Application 全体を起動することにはならない。  
何を本物として組み合わせる必要があるかを、保証したい責務から決める。

### Repository interface と JDBC 実装

interface は、例えば「商品 ID から商品を取得する」「条件に合う注文を返す」という呼び出し側に必要な操作を定義する。  
SQL の書き方、テーブル名、JDBC の Parameter の作り方などは JDBC 実装側が担当する。

この分離により、Service の Unit Test は interface の Test Double を使い、今回の Test は JDBC 実装を使うという使い分けができる。  
同じ interface を使っていても、Test Boundary に入れた実装が異なれば、確認できる故障も異なる。

現在の在庫更新 API は `decreaseStock` と `increaseStock` である。  
在庫数を任意の値へ置き換える操作ではなく、DB にある現在値を基準に数量を減算・加算し、影響した行数を返す設計となっている。

### テーブルと Schema

DB のテーブルは、商品や注文などのデータを行と Column で保持する。  
例えば `products` の 1 行は 1 商品に対応し、`name`、`price`、`stock_quantity` などの Column がその値を持つ。

この Unit で Schema の初期化と呼ぶのは、テーブル、Column の型、制約、Index など、DB の構造を準備することである。  
構造を定義する SQL は DDL（Data Definition Language）と呼び、今回の `schema.sql` には `CREATE TABLE` と `CREATE INDEX` が書かれている。

テーブルの構造があることと、Test に必要な行があることは別である。  
DDL で構造を準備し、INSERT で行を用意し、SELECT で取得し、UPDATE で値を変更する。この区別が、後で読む Schema 初期化と SQL Fixture の分担につながる。

### Spring JDBC が担当すること

JDBC は、Java から DB に接続して SQL を実行し、結果を受け取るための標準 API である。  
そのまま使う場合には、Connection、Statement、ResultSet の管理や例外処理なども記述する必要がある。

Spring JDBC は、こうした JDBC 操作の定型部分を支援する。  
`JdbcTemplate` などが SQL の実行と Resource の管理を担当し、アプリケーションは SQL、Parameter、結果の変換へ集中できる。

ただし、Spring JDBC が SQL や Domain Model の対応をすべて自動生成するわけではない。  
今回の実装では SQL を明示し、必要な Column を読み、`Product`、`Order`、`OrderItem` へ自分で変換する。この対応自体が Test の対象となる。

### DataSource、Connection、JDBC Driver

`DataSource` は、DB への Connection を取得するための入口である。  
Spring が管理する JDBC Template は DataSource を利用し、実際の通信は PostgreSQL の JDBC Driver が担当する。接続プールがある場合、Connection の取得・返却もその仕組みを通る。

```text
Repository
  ↓ SQL と Parameter
NamedParameterJdbcTemplate / JdbcTemplate
  ↓ Transaction に対応する Connection の利用
DataSource / Connection Pool
  ↓ JDBC Driver
テスト専用 PostgreSQL
```

Test で正しい DataSource を使うことは、データを準備するより前の前提となる。  
Fixture が専用でも、通常利用中の DB に接続していれば安全な Test にはならない。今回の接続先は Container の動的な接続情報から構成し、接続先自体も Test で確認する。

### JdbcTemplate と NamedParameterJdbcTemplate

`JdbcTemplate` は、SQL と Parameter を渡して更新・検索を実行するための API である。  
次のように `?` を使う場合は、値の指定順と Placeholder の順が対応する。

```java
jdbcTemplate.queryForObject(
    "SELECT stock_quantity FROM products WHERE id = ?", Integer.class, KEYBOARD_ID);
```

`NamedParameterJdbcTemplate` は、`:id` や `:quantity` のような名前付き Parameter を使える。  
Column が多い INSERT や、必要な条件だけを追加する検索では、SQL と値の関係を名前で追いやすくなる。内部では JDBC が扱える形へ変換して実行する。

今回の Production Code は `NamedParameterJdbcTemplate` を使う。  
Test 側では、保存結果の直接確認や制約違反を起こす SQL に `JdbcTemplate` を使う。両者は別 DB を使っているのではなく、同じテスト用 DataSource に接続する。

| 操作                     | 主な API                 | 今回の用途                           |
| ------------------------ | ------------------------ | ------------------------------------ |
| 1 行・1 値の取得         | `queryForObject`         | 在庫、件数、保存した Column 値の確認 |
| 複数行の取得             | `query` / `queryForList` | 商品一覧、JOIN 結果、ID 一覧の取得   |
| INSERT / UPDATE / DELETE | `update`                 | 注文登録、在庫更新、制約違反の確認   |
| 同じ SQL の複数回実行    | `batchUpdate`            | 複数の注文明細の登録                 |

### 明示的な SQL と SQL Parameter

明示的な SQL では、「どのテーブルの、どの行を、どの条件で扱うか」が SQL に直接現れる。  
レビューや Test では、Java の Method 名だけでなく、SELECT Column、WHERE、JOIN、ORDER BY も仕様と対応させて読む必要がある。

SQL Parameter は、SQL の構造と値を分けて渡す仕組みである。  
例えば商品 ID や数量を文字列連結で SQL に埋め込まず、`:id` と `:quantity` に対応する値として渡す。

```sql
UPDATE products
SET stock_quantity = stock_quantity - :quantity
WHERE id = :id
  AND stock_quantity >= :quantity
```

Parameter Binding は値の引用や型の取り扱いを JDBC に任せるためにも必要となる。  
ただし、テーブル名や Column 名、`ASC` / `DESC` のような SQL の構造を、そのまま値の Parameter として置き換えられるわけではない。今回の動的検索は、実装内で定義した条件だけを追加し、その条件に渡す値を bind している。

### INSERT と生成 ID

INSERT は、新しい行を DB に登録する操作である。  
`orders.id` は `GENERATED BY DEFAULT AS IDENTITY` で定義されており、通常の注文登録では DB が ID を採番する。

`insertOrder` は `GeneratedKeyHolder` を使って生成 ID を受け取り、戻り値として返す。  
Test では「正の ID が返ったこと」に加えて、その ID の行に status、配送先、金額、日時が保存されたことを直接 SELECT で確認する。

注文ヘッダと注文明細は別テーブルに保存する。  
ヘッダの INSERT 後に返った ID を `insertOrderItems` へ渡し、各明細の `order_id` に使用する。保存前の `OrderItem` が持つ未確定の ID に頼らないことが重要となる。

Fixture が明示する ID と、DB が採番する ID は役割が異なる。  
採番結果を「必ず 1」「前回より必ず 1 大きい」と固定して期待せず、返された ID と保存された行の関係を検証する。

### UPDATE と更新件数

UPDATE の戻り値には、影響した行数が含まれる。  
今回の Repository は、条件に合う 1 行を更新した場合に `1`、該当行がなければ `0` を返す。

`decreaseStock` は、商品 ID だけでなく `stock_quantity >= :quantity` を条件へ含める。  
在庫が足りない場合に負数へ更新してから元に戻すのではなく、更新可能な行だけを SQL で選ぶ。残数と更新可否を同じ SQL に含めることが設計上の要点となる。

`updateStatus` も、ID と `expectedStatus` の両方が一致する場合だけ更新する。  
一方、存在しない ID と期待状態の不一致は、ともに更新件数 `0` となる。戻り値だけで両者の理由まで区別しているわけではない。

更新 Test では、行数と DB の保存状態を組み合わせる。  
`1` が返っても値や対象行が誤っている可能性があり、`0` が返っても本来変更しないデータが変わっていないかを確認する必要がある。

### SELECT と Row Mapping

SELECT は、条件に合う行と Column を取得する操作である。  
JDBC の結果は `ResultSet` として得られ、その Column 値を Java の Object へ変換する処理を Row Mapping と呼ぶ。

`JdbcProductRepository` では、1 行の商品を 1 個の `Product` へ変換する。  
ID、商品名、価格、在庫、販売可否のすべてが正しい Field へ入ることを確認する。例えば価格と在庫を別々に Assert することで、取得件数だけでは見つからない変換の誤りを検出できる。

| DB の値          | Java 側での主な表現                               | 読み取り時の着眼点                      |
| ---------------- | ------------------------------------------------- | --------------------------------------- |
| `BIGINT`         | `long` / `Long`                                   | 商品・注文・明細の ID を取り違えない    |
| `NUMERIC(12, 0)` | `BigDecimal`                                      | 金額を浮動小数点に変換しない            |
| `INTEGER`        | `int`                                             | 数量と在庫の Column を区別する          |
| `BOOLEAN`        | `boolean`                                         | `active = false` も正しく復元する       |
| `VARCHAR` の状態 | `OrderStatus`                                     | DB の文字列と enum の対応を確認する     |
| `TIMESTAMPTZ`    | JDBC 境界の `OffsetDateTime`、Domain の `Instant` | 同じ瞬間を復元し、nullable な日時も扱う |

### JOIN と 1 対多の集約

JOIN は、関連するテーブルの行を結び付ける操作である。  
今回の注文取得は `orders` と `order_items` を `order_id` で結び付けるため、明細が 2 件なら、同じ注文ヘッダを含む行が 2 行返る。

```text
JOIN の結果:
order_id = 10003, order_item_id = 20003, Keyboard
order_id = 10003, order_item_id = 20004, Mouse

Domain Model:
Order(id = 10003)
  └─ items
      ├─ OrderItem(id = 20003, Keyboard)
      └─ OrderItem(id = 20004, Mouse)
```

1 行をそのまま 1 個の Order にすると、注文が重複したり明細が欠落したりする。  
`JdbcOrderRepository` は注文 ID を Key にしてヘッダを一度だけ保持し、明細を追加してから `Order.restore` で復元する。

両テーブルに `id` があるため、SELECT では `order_id` と `order_item_id` の alias を使う。  
Test でも注文 ID、明細 ID、商品 ID を別々に確認し、単に List のサイズが合うだけで正しいと判断しない。

### 注文時点の商品情報と Snapshot

注文明細には、注文時点の商品名と単価を保存する。  
現在の商品マスタを更新しても、過去の注文内容が変わらないようにするための Snapshot である。

今回の取得 SQL は、`order_items.product_name` と `order_items.unit_price` を読み取る。  
商品テーブルの現在値を読み直して注文明細を組み立てる実装では、注文後の値上げや商品名変更が過去の注文へ反映されてしまう。

Snapshot の Test は、商品マスタを更新してから注文を取得し、保存済み明細の元の名前・単価が返ることを確認する。  
静的な Fixture を読むだけではなく、現在値と注文時点の値を意図的に異ならせることで、この責務を検証する。

### 複数条件 WHERE と未指定条件

注文一覧は、status、作成日時の下限、作成日時の上限を任意に指定できる。  
実装は、指定された条件だけを WHERE へ追加し、複数なら `AND` で結合する。

この API での `null` は「その検索条件を付けない」という入力である。  
SQL に `o.status = NULL` を追加する意味ではない。SQL で NULL の値を調べる `IS NULL` とも、役割が異なる。

3 条件には、それぞれ指定・未指定があるため、今回の Test は 8 通りを用意する。  
例えば全条件の 1 ケースだけでは、「下限だけを指定すると無視される」「上限を付け忘れる」といった不備を見逃しやすい。

日時の条件は `>=` と `<=` であり、両端を含む。  
下限と上限が同じ瞬間でも有効であることと、下限が上限より後なら Java 側で拒否することを、別の Test として確認する。

### ORDER BY と安定した並び順

SELECT の結果は、ORDER BY がなければ期待する順で返るとは限らない。  
INSERT した順、主キーの順、以前の実行で返った順を、そのまま取得順の仕様にしてはいけない。

商品一覧は ID 昇順で返す。  
注文一覧は作成日時の降順、同時刻なら注文 ID の降順とし、さらに同じ注文の明細を明細 ID 順で取得する。

```sql
ORDER BY o.created_at DESC, o.id DESC, oi.id
```

最後の `oi.id` は省略時の昇順となる。  
Test Fixture には作成日時が同じ注文と、INSERT 順が ID 順と異なる明細を含め、`containsExactly` で順序まで Assert する。

JOIN 後の並び順を Java の結果へ引き継ぐことも必要となる。  
実装が `LinkedHashMap` を使う理由は、注文 ID ごとに集約すると同時に、SQL が決めた注文の順序を維持するためである。

### DB constraint と Java 側の検証

DB constraint は、DB に保存できる値や行の関係を制限する仕組みである。  
Java の入力検証を通らない SQL でも適用されるため、永続データの整合性を支える。

| 制約          | 今回の例                           | 防ぐ不整合                       |
| ------------- | ---------------------------------- | -------------------------------- |
| `PRIMARY KEY` | `products.id`                      | 同じ商品 ID の重複               |
| `NOT NULL`    | `products.name`                    | 必須 Column の欠落               |
| `CHECK`       | 価格が正、在庫が非負、数量が 1〜99 | Column 値が許容範囲を外れること  |
| `UNIQUE`      | `(order_id, product_id)`           | 同じ注文に同じ商品が重複すること |
| `FOREIGN KEY` | 明細の注文 ID・商品 ID             | 参照先が存在しない明細           |

Domain Model にも Guard があるが、それだけでは DDL の正しさを確認できない。  
`DatabaseConstraintTest` は `JdbcTemplate` から直接不正な SQL を実行し、DB 自身が拒否することを確認する。この Test の SUT は Repository Method ではなく、実際に読み込んだ Schema の制約となる。

また、DB 制約がアプリケーションの業務仕様をすべて表しているわけではない。  
この Schema は、明細金額が単価 × 数量に等しいことや、状態遷移の全規則までは CHECK していない。Domain / Service と DB がそれぞれ何を守るかを区別する。

### SQLSTATE と例外の確認

Spring JDBC は、JDBC の `SQLException` を `DataAccessException` 系の例外へ変換する。  
制約違反は今回 `DataIntegrityViolationException` として観測し、その原因となった `SQLException` の SQLSTATE も確認する。

SQLSTATE は、DB エラーの種類を識別するためのコードである。  
単に「何か例外が出た」だけでは、意図した CHECK ではなく外部キー違反で失敗している可能性もある。

| SQLSTATE | 今回確認する種類      |
| -------- | --------------------- |
| `23514`  | CHECK 制約違反        |
| `23505`  | 主キー・UNIQUE の重複 |
| `23503`  | 外部キー制約違反      |
| `23502`  | NOT NULL 制約違反     |

Test では、狙った制約以外を満たす Fixture を使う。  
例えば郵便番号の CHECK を確認する値は、Column の長さ自体には収まる 7 文字とし、別のエラーで先に拒否されることを避けている。

### 実 PostgreSQL と DB 固有挙動

SQL の基本構文が共通でも、すべての DB が同じ型、関数、制約、採番、エラー処理を持つわけではない。  
DB Integration Test では、Production Code が使う DB と同じ製品で検証することに意味がある。

今回の Schema と実装には、PostgreSQL の `IDENTITY`、`NUMERIC`、`TIMESTAMPTZ`、正規表現演算子 `~` を使う CHECK が登場する。  
Java の値を bind し、DB が保存し、JDBC Driver が返すまでを実 PostgreSQL で確認する。

金額は `NUMERIC(12, 0)` と `BigDecimal` で扱う。  
Test では `isEqualByComparingTo` を使い、金額としての数値を比較する。`BigDecimal.equals` が scale も比較することと、金額が等しいことは区別する。

日時は、Domain の `Instant` と JDBC 境界の `OffsetDateTime` を変換する。  
`TIMESTAMPTZ` は同じ瞬間を扱う型であり、入力した `+09:00` という表記そのものを保存して返すことを保証する型ではない。Test は UTC の `Instant` と比較する。

### H2 などの in-memory DB の位置づけ

H2 のような in-memory DB は、環境の準備が軽く、短時間で DB を使った Test を動かす選択肢となる。  
一般的な SQL を用いる機能や、利用する DB 自体が H2 である場合などには有用である。

一方、PostgreSQL 互換の設定を利用しても、PostgreSQL のすべての振る舞いが同じになるわけではない。  
別 DB で成功したことだけを根拠に、PostgreSQL の型変換、正規表現 CHECK、制約違反後の Transaction まで確認済みとは言えない。

この Unit は、その差も含めて JDBC Repository を学ぶため、H2 へ置き換えず実 PostgreSQL を使う。  
その代わり Docker Engine の起動や Container の準備が必要となる。速度・準備の軽さと、どの互換性を確認したいかを対応させて選ぶ。

### Test Data、Database Fixture、Seed Data

Test Data は、Test に使う入力・保存値・期待値などの総称である。  
Database Fixture は、特定の Test が前提とする DB の状態を表す。行数だけでなく、ID、値、親子関係、比較の境界、並び順を検出できる差を含む。

Seed Data は、利用開始時などに DB へ投入する初期データを指す。  
通常の画面・API 確認用 Seed Data と、Assertion の根拠となる Test Fixture は、目的が一致するとは限らない。

今回の `src/main/resources/data.sql` は通常起動用のデータである。  
Unit 06 はこれを利用せず、`src/test/resources/sql/unit06/` の SQL Fixture と、保存する Java Object を作る `JdbcRepositoryTestFixture` を使う。

```text
通常起動用 Seed Data:
手動確認を始めやすくする初期データ

SQL Fixture:
検索・JOIN・制約などを検証する既知の DB 状態

Java Fixture:
Repository へ渡す新しい Order / OrderItem などの入力
```

Fixture を SUT と同じロジックで作ると、双方に同じ誤りが入ったときに Test が成功するおそれがある。  
今回の金額や検索の期待 ID は明示し、SUT に再計算させた結果をそのまま期待値にはしない。

### Setup と Cleanup

Setup は、Test が必要とする前提を整える処理である。  
DB Test では、接続可能な DB、Schema、参照先の親行、検索条件を区別できる行などを用意する。

Cleanup は、Test が行った変更を後続の Test や通常利用へ残さないための後始末である。  
DELETE 用 SQL を書く方法もあるが、親子関係の削除順や実行漏れに注意が必要になる。

この Unit は、各 Test の Transaction で Fixture と更新をまとめ、Rollback を基本の Cleanup とする。  
Container の停止・破棄は、さらに外側の環境の後始末である。行を元に戻すことと、DB 環境を終了することを別の責務として扱う。

### Transaction と Rollback

Transaction は、複数の DB 操作をひとまとまりとして扱う単位である。  
Commit で変更を確定し、Rollback でその Transaction の変更を取り消す。

`@JdbcTest` では、通常、各 Test を Spring Test が管理する Transaction 内で実行し、終了時に Rollback する。  
同じ Test の中の `JdbcTemplate` と Repository は、その DataSource / Transaction 管理の下で同じ変更を観測できる。

```text
Test Transaction 開始
  ↓
@Sql で Fixture を投入
  ↓
@BeforeEach で前提を確認
  ↓
Repository を実行
  ↓
戻り値・DB 状態を Assert
  ↓
Rollback（Fixture の INSERT と Test の更新を取り消す）
```

Rollback 後に残るのは Container 起動時に準備したテーブルであり、各 Test が投入した行ではない。  
Schema の初期化を Test Transaction の外へ置くことと、Fixture の投入をその中へ置くことが、この構成の重要な前提となる。

### DB Test Isolation

DB Test Isolation は、他の Test が作成した行や変更した値に依存せず、各 Test の前提と結果を独立させることである。  
「前の Test が在庫を戻してくれる」「この Test は必ず最後に動く」といった実行順の仮定を持たせない。

今回の Test は、同じ SQL Fixture を Test ごとに投入し、Rollback で取り除く。  
Parameterized Test の各引数や Repeated Test の各回も、それぞれ新しい前提から始まる。検索結果の順を検証することと、Test Method の実行順を固定することは別の話である。

ここでの Isolation は、DB の Transaction Isolation Level の全種類を比較するという意味ではない。  
この Unit では複数 Transaction を並列に競合させたり、分離レベルを変更したりしていない。まずは Test 同士の状態の持ち越しを防ぐことを扱う。

### @JdbcTest と JDBC 用 Test Slice

`@JdbcTest` は、JDBC を利用する Test に必要な設定を用意する Spring Boot の Test Slice Annotation である。  
DataSource、JDBC Template、Transaction 管理などを利用し、Controller や Service を含む Application 全体の起動を避けられる。

ただし、通常の `@Repository` Component が、この Slice ですべて自動登録されるわけではない。  
今回の Test は `PostgresJdbcTestConfiguration` を import し、その設定で 2 つの JDBC 実装を明示的に import する。

```java
@JdbcTest(properties = "spring.sql.init.mode=never")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresJdbcTestConfiguration.class)
```

3 つの指定には、それぞれ役割がある。

- `@JdbcTest` は JDBC 用の Test Context と Test Transaction の基盤を用意する。
- `spring.sql.init.mode=never` は、Spring Boot の通常の SQL 初期化を止める。
- `@AutoConfigureTestDatabase(replace = NONE)` は、DataSource を組み込み DB などへ差し替えない意図を明示する。
- `@Import` は、この Test で使う Repository と Container の設定を追加する。

Annotation をまとめて覚えるだけでなく、「何を起動するか」「何を追加するか」「何を置換・初期化させないか」に分けて読む。

### Testcontainers とテスト用 PostgreSQL

Testcontainers は、Test で必要な DB などを Docker Container として起動し、利用後の終了まで扱うためのライブラリである。  
今回の `PostgreSQLContainer` は PostgreSQL の Container を用意し、接続情報や初期化処理を提供する。

利用するイメージは `postgres:18.6` である。  
ホスト側の Port を固定せず、Container ごとに得られた接続先を利用するため、通常確認用 PostgreSQL の `localhost:5432` を共有する構成ではない。

Container は実 DB を実行する環境であり、SQL の戻り値を決めた通りに返す Stub ではない。  
実 PostgreSQL の起動、SQL の実行、JDBC の接続を必要とするため、Docker Engine と、初回などのイメージ取得が実行条件に加わる。

### @ServiceConnection と test datasource

`@ServiceConnection` は、Container が提供する接続情報を Spring Boot の接続設定へ渡すために使う。  
今回の PostgreSQL Container が割り当てられた動的な JDBC URL などを、Test の DataSource に反映する。

`application.yml` がまったく読み込まれなくなるという意味ではない。  
今回の構成では、Container の接続情報が通常用の DataSource 設定より優先され、さらに Test 用の Property で Boot の SQL 初期化を止める。

接続先と初期データは別々に管理する。  
`@ServiceConnection` が正しくても通常用 `data.sql` を実行すればデータが混入し、初期化を止めても接続先が通常 DB なら安全な分離は成立しない。

### Container Lifecycle と Spring Context の共有

今回の Container は、Test Class の中で手動起動するのではなく、`@TestConfiguration` の `@Bean` として定義する。  
Spring Boot が開始・終了を管理し、DataSource などの依存 Bean と Container の存続期間を対応させる。

Spring Test は、設定が同じ Test Context をキャッシュして再利用する。  
今回の 4 Class は共通設定を使うため、同一の Test 実行内で Context と Container を共有できる。Test ごとに PostgreSQL を起動し直す構成ではない。

```text
Spring Test Context / Container の期間
  Container 起動 → Schema 準備
    ├─ Test A: Fixture → 実行 → Rollback
    ├─ Test B: Fixture → 実行 → Rollback
    └─ Test C: Fixture → 実行 → Rollback
  Context 終了 → Container 終了
```

Context の共有は起動コストを抑える仕組みであり、DB の行を Test 間で共有してよいという意味ではない。  
Container の寿命と、Fixture が存在する Transaction の寿命を区別する。

### Schema 初期化と @Sql の分担

Schema は、Container 設定の `.withInitScript("schema.sql")` により、`src/main/resources/schema.sql` を Container 起動時に読み込む。  
本番側と同じ DDL を使い、テスト専用の簡略化したテーブル定義は作らない。

一方、`@Sql` は Test の前に SQL Fixture を実行する Spring Test の機能である。  
商品だけが必要な Class は `products.sql`、注文も必要な Class は `products.sql` と `orders.sql` の順で実行する。注文・明細には外部キーがあるため、参照先を先に用意する必要がある。

| 処理                      | 実行するもの                                | 実行時期・範囲                            |
| ------------------------- | ------------------------------------------- | ----------------------------------------- |
| テーブル・Index の作成    | Container の `withInitScript("schema.sql")` | Container 初期化時、Test Transaction の外 |
| 通常用 Seed Data の投入   | 今回は実行しない                            | Boot の SQL 初期化を `never` で停止       |
| 商品・注文 Fixture の投入 | Spring Test の `@Sql`                       | Test ごとの Transaction 内                |
| Java の保存入力の作成     | `JdbcRepositoryTestFixture`                 | 必要な Test の Arrange                    |
| 行データの Cleanup        | Test Transaction の Rollback                | 各 Test の終了時など                      |
| DB 環境の Cleanup         | Spring 管理の Container の終了              | Context の終了時                          |

`spring.sql.init.mode=never` は、`@Sql` や Testcontainers の init script を停止する設定ではない。  
「Boot の初期化」と「Container の初期化」と「Test の Fixture 準備」は、別の仕組みとして動いている。

Method に `@Sql` を指定すると、今回の標準の動作では Class の Script 指定を置き換える。  
注文 0 件の Test はこれを使い、商品だけを投入する。Class の注文 Fixture へ商品だけを追加投入する、という意味ではない。

### 制約違反後の Transaction と Cleanup

PostgreSQL では、Transaction 内で SQL がエラーになると、その Transaction が失敗状態となる。  
Java 側で例外を catch しただけでは、同じ Transaction で次の SELECT を通常どおり続けられる状態には戻らない。

このため制約違反の Test は、1 回の実行で 1 つの不正 SQL と例外・SQLSTATE を確認し、その後は Test の Rollback に任せる。  
複数の不正 SQL を同じ Transaction へ順番に投げ、後半のエラーを別制約の検証結果として扱わない。

正常な境界値 `1` と `99` の Test は制約違反を起こさないため、INSERT 後に SELECT して保存値も確認する。  
正常系と異常系では、DB の Transaction 状態に応じて観測できるものが異なる。

### Test Transaction と業務 Transaction

今回の Rollback は、Spring Test が Test 自身のために管理する Transaction である。  
Service の業務処理を呼び出し、その途中の失敗で関連する更新がすべて取り消されることを確認しているわけではない。

```text
Unit 06:
Test が開始した Transaction
  └─ @Sql / Repository / Test の SELECT

Unit 07 で扱う範囲:
Spring が管理する Service の呼び出し
  └─ 業務処理を囲む @Transactional と実 Repository / DB の連携
```

Test の後に DB がきれいになったことだけで、Production Code の `@Transactional` が正しいと結論付けない。  
誰が Transaction を開始し、どの処理を囲み、何を原因に終了したのかを確認する。

### DBUnit、dataset、expected dataset

DBUnit は、DB Test のデータ準備や比較を支援するツールである。  
代表的には、テーブルと行を dataset として表し、DB へ投入したり、処理後の DB と期待するデータを比較したりする。

dataset は、テスト用の行データをまとまりとして表現したものである。  
expected dataset は、そのうち処理後に期待するテーブルの内容を表す。入力用 dataset と期待結果は、同じ目的のデータではない。

```text
DBUnit を利用する場合の考え方:
初期 dataset → DB の準備 → SUT 実行 → DB の取得結果
                                        ↕ 比較
                                  expected dataset
```

Testcontainers と DBUnit は、どちらかを選べばもう一方の役割が消える関係ではない。

| 観点                   | Testcontainers                            | DBUnit                                     |
| ---------------------- | ----------------------------------------- | ------------------------------------------ |
| 主な責務               | DB などの実行環境を Container で用意する  | テーブルデータの準備・取得・比較を支援する |
| 主に管理するもの       | Container、接続先、開始・終了             | dataset、DB への投入操作、expected dataset |
| 解決する問題           | Test 用 DB がない、手動環境に依存する     | 大量の行データを一定の形で準備・比較したい |
| 単独では決まらないこと | 各 Test に必要な行、期待値、Test 間の分離 | DB Server 自体の起動方法、接続先の安全性   |

この Unit では DBUnit の依存関係は追加していない。  
環境の用意は Testcontainers、初期状態は `@Sql` の SQL Fixture、結果の確認は JdbcTemplate と AssertJ が担当する。この分担を把握したうえで、dataset を使う方法の位置づけを理解する。

## 使用する技術・ツール

### 実行基盤と DB

| 技術・ツール           | この Unit での役割                                                 |
| ---------------------- | ------------------------------------------------------------------ |
| Java 21                | Repository、Domain Model、Test Code の実行                         |
| Spring Boot 4.1.1      | JDBC 用 Test Slice、Auto Configuration、Container 接続情報との連携 |
| Maven Wrapper          | プロジェクトの Maven 設定に沿ったコンパイル・Test 実行             |
| JUnit Jupiter          | 通常 Test、Parameterized Test、Repeated Test、Lifecycle の管理     |
| AssertJ                | 保存値、取得値、順序、例外、SQLSTATE の Assertion                  |
| Spring JDBC            | `JdbcTemplate` / `NamedParameterJdbcTemplate` による実 SQL の実行  |
| PostgreSQL JDBC Driver | Java と実 PostgreSQL の通信・型変換                                |
| PostgreSQL 18.6        | テスト専用 DB。`postgres:18.6` イメージを利用する                  |
| Testcontainers 2.0.5   | PostgreSQL Container の準備、接続情報、初期化、後始末を支援する    |
| Docker Engine          | PostgreSQL と Testcontainers の補助 Container を実行する           |

バージョンの正本は [`pom.xml`](../../pom.xml) と Spring Boot の依存関係管理である。  
Testcontainers のバージョンを Test ごとに指定せず、現在の Spring Boot が管理する組み合わせを利用している。

### Unit 06 で追加した Test Scope の依存関係

| artifact                                                 | 役割                                                 |
| -------------------------------------------------------- | ---------------------------------------------------- |
| `org.springframework.boot:spring-boot-starter-jdbc-test` | `@JdbcTest` など、JDBC 用の Spring Boot Test 基盤    |
| `org.springframework.boot:spring-boot-testcontainers`    | `@ServiceConnection` と Spring 管理 Container の連携 |
| `org.testcontainers:testcontainers-postgresql`           | PostgreSQL 用の `PostgreSQLContainer`                |

Production Code が使う JDBC Starter と PostgreSQL Driver は既存の依存関係を利用する。  
H2、DBUnit、Testcontainers の JUnit Jupiter 拡張は、この Unit の実装には追加していない。

### 主な Annotation と Test API

| API                                                                    | 役割                                                           |
| ---------------------------------------------------------------------- | -------------------------------------------------------------- |
| `@JdbcTest`                                                            | JDBC 用 Test Context と Test Transaction の構成                |
| `@AutoConfigureTestDatabase(replace = NONE)`                           | テスト用 DataSource を組み込み DB へ置換しない指定             |
| `@TestConfiguration` / `@Import`                                       | JDBC 実装と Container Bean を Test 用に組み込む                |
| `@Bean` / `@ServiceConnection`                                         | Spring が Container を管理し、その接続情報を DataSource へ渡す |
| `@Sql`                                                                 | Test ごとの SQL Fixture の投入                                 |
| `@BeforeEach`                                                          | Fixture 投入後に Test の前提を確認する                         |
| `@ParameterizedTest` / `@CsvSource` / `@ValueSource` / `@MethodSource` | 境界値・状態・検索条件・制約違反を複数の入力で確認する         |
| `@RepeatedTest`                                                        | 独立した Fixture で同じ操作を繰り返せることを確認する          |
| `TestTransaction`                                                      | Test Transaction の状態確認と、Test 中の明示的な Rollback      |

Spring Boot 4.1.1 の `@JdbcTest` と `@AutoConfigureTestDatabase` は、`org.springframework.boot.jdbc.test.autoconfigure` package のものを使う。  
古い記事の import や Testcontainers の class 名をそのままコピーせず、実際の Test Code と `pom.xml` を対応させて読む。

## テスト対象とテスト境界

### SUT

| Test Class                  | 主な SUT                                          | 主な保証対象                                                         |
| --------------------------- | ------------------------------------------------- | -------------------------------------------------------------------- |
| `JdbcProductRepositoryTest` | `JdbcProductRepository`                           | 商品の Row Mapping、一覧順、在庫更新の条件・件数・保存値             |
| `JdbcOrderRepositoryTest`   | `JdbcOrderRepository`                             | ヘッダ・明細の保存、生成 ID、JOIN 集約、Snapshot、条件検索、状態更新 |
| `DatabaseConstraintTest`    | `schema.sql` で定義した実 DB の制約               | 不正 SQL の拒否、SQLSTATE、数量境界の許可                            |
| `JdbcTransactionTest`       | Test 用 DataSource / Transaction / Fixture の構成 | 接続先、Rollback 後の状態、繰り返し実行の独立性                      |

後ろの 2 Class は、Repository の API だけを対象にした Test ではない。  
SQL の前提となる Schema と Test 環境にも、意図した制約・分離が成立しているかを確認する責務がある。

### 本物として動かす範囲

```text
Test Code
  ├─ @Sql / Java Fixture
  ├─ JDBC Repository を直接呼び出す
  │    ↓
  │  NamedParameterJdbcTemplate
  │    ↓
  │  DataSource / Transaction Manager / JDBC Driver
  │    ↓
  │  Testcontainers の PostgreSQL
  │    ├─ Production と同じ Schema
  │    └─ Test 専用の行データ
  │
  └─ JdbcTemplate から保存状態を直接確認する
```

SQL、Driver、DB constraint、Row Mapping、Domain Model の復元は本物として動く。  
`JdbcTemplate` による直接観測も同じ DB に対する実 SQL である。

この Unit の 4 Class は Mockito の Test Double を使わない。  
外側の依存をすべて mock にすることが、常に Test の目的に合うわけではない。今回確認したい SQL と DB を境界に含めるためである。

### 起動しない範囲と保証しない範囲

Controller、Service、外部 HTTP Client、Application の HTTP Server は起動しない。  
MockMvc、WireMock、通常確認用 Compose の PostgreSQL / Volume も利用しない。

今回の成功から、次の内容まで保証したとは判断しない。

- Service の `@Transactional` や業務処理全体の Commit / Rollback。
- HTTP Request / Response、Validation、外部送料 API との連携。
- 複数の同時更新、ロック競合、デッドロック、すべての Transaction Isolation Level。
- Index の性能や、本番規模のデータ量での応答時間。
- DB 制約として定義していない業務ルールのすべて。

これらは今回の Test が不足しているという一律の意味ではない。  
各 Layer の Test に役割を持たせ、どの範囲の不備をどの Test で検出するかを明確にする。

## 使用するファイル

### この Unit で追加・変更する主なファイル

| ファイル                                                                                                                    | 役割                                                                                   |
| --------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| [`pom.xml`](../../pom.xml)                                                                                                  | JDBC Test / Spring Boot Testcontainers / PostgreSQL Container の Test Scope 依存を追加 |
| [`JdbcProductRepositoryTest.java`](../../src/test/java/com/example/orderapi/repository/jdbc/JdbcProductRepositoryTest.java) | 商品取得と在庫更新の実 DB Test                                                         |
| [`JdbcOrderRepositoryTest.java`](../../src/test/java/com/example/orderapi/repository/jdbc/JdbcOrderRepositoryTest.java)     | 注文登録・取得・検索・状態更新の実 DB Test                                             |
| [`DatabaseConstraintTest.java`](../../src/test/java/com/example/orderapi/repository/jdbc/DatabaseConstraintTest.java)       | Schema の制約違反と有効な境界値の Test                                                 |
| [`JdbcTransactionTest.java`](../../src/test/java/com/example/orderapi/repository/jdbc/JdbcTransactionTest.java)             | 接続先、Rollback、Fixture の独立性の Test                                              |
| [`PostgresJdbcTestConfiguration.java`](../../src/test/java/com/example/orderapi/support/PostgresJdbcTestConfiguration.java) | 実 Repository の import と Spring 管理の PostgreSQL Container                          |
| [`JdbcRepositoryTestFixture.java`](../../src/test/java/com/example/orderapi/support/JdbcRepositoryTestFixture.java)         | 共通の ID・日時と、保存入力となる Order / OrderItem の生成                             |
| [`products.sql`](../../src/test/resources/sql/unit06/products.sql)                                                          | 商品 3 件の SQL Fixture                                                                |
| [`orders.sql`](../../src/test/resources/sql/unit06/orders.sql)                                                              | 注文 7 件・明細 8 件の SQL Fixture                                                     |

### コードリーディングで参照する主なファイル

| ファイル                                                                                                            | 確認する内容                                           |
| ------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------ |
| [`ProductRepository.java`](../../src/main/java/com/example/orderapi/repository/ProductRepository.java)              | 商品取得・在庫減算・在庫加算の契約                     |
| [`OrderRepository.java`](../../src/main/java/com/example/orderapi/repository/OrderRepository.java)                  | 注文登録・明細登録・取得・検索・状態更新の契約         |
| [`JdbcProductRepository.java`](../../src/main/java/com/example/orderapi/repository/jdbc/JdbcProductRepository.java) | 商品の明示 SQL と Row Mapping                          |
| [`JdbcOrderRepository.java`](../../src/main/java/com/example/orderapi/repository/jdbc/JdbcOrderRepository.java)     | 注文の明示 SQL、Parameter、JOIN 集約、日時変換         |
| [`schema.sql`](../../src/main/resources/schema.sql)                                                                 | 3 テーブル、型、主キー、CHECK、UNIQUE、外部キー、Index |
| [`data.sql`](../../src/main/resources/data.sql)                                                                     | 今回は実行しない通常起動用 Seed Data との区別          |
| [`application.yml`](../../src/main/resources/application.yml)                                                       | 通常の DataSource と SQL 初期化の設定                  |
| [`compose.yaml`](../../compose.yaml)                                                                                | 通常確認用 Container と、Testcontainers の環境の違い   |
| [`Product.java`](../../src/main/java/com/example/orderapi/domain/Product.java)                                      | 商品の復元先となる Domain Model                        |
| [`Order.java`](../../src/main/java/com/example/orderapi/domain/Order.java)                                          | 注文の生成・復元、状態、金額、日時                     |
| [`OrderItem.java`](../../src/main/java/com/example/orderapi/domain/OrderItem.java)                                  | 明細の生成・復元、Snapshot、数量・金額                 |
| [`PriceSummary.java`](../../src/main/java/com/example/orderapi/domain/PriceSummary.java)                            | 注文ヘッダに保存する金額のまとまり                     |
| [`OrderStatus.java`](../../src/main/java/com/example/orderapi/domain/OrderStatus.java)                              | DB に文字列として保存する注文状態                      |

Production Code の Repository、Domain Model、Schema は、既存の実装をそのまま利用する。  
Unit 06 のために別の注文管理アプリケーションや、Test 用だけの Repository を作成しているわけではない。

## 前提

### 既習事項

Unit 01〜05 で、SUT、Test Boundary、Arrange・Act・Assert、Test Double、Parameterized Test、Spring Context と Test Slice を学習済みとする。  
今回も、完成した Code とコメントを読み、Fixture、実行する Method、Assertion の対応を確認してから実行する。

SQL、DB constraint、Test Transaction、Testcontainers は、この Unit の学習対象である。  
すべてを既知として扱う必要はなく、「学習内容」で概念を確認し、後半のコードリーディングで実例へ結び付ける。

### 実行環境

- リポジトリのルートで Java 21 と Maven Wrapper を実行できること。
- Docker Desktop などを通じて、Linux Container を実行する Docker Engine に接続できること。
- Testcontainers が必要とする Container の作成・起動・停止・破棄を行えること。
- 初回やキャッシュ不足時には、Maven 依存関係、`postgres:18.6`、Testcontainers の補助イメージを取得できること。

通常の学習環境は Windows + Git Bash を想定し、以降の例はリポジトリルートで `./mvnw` を使う。  
PowerShell で実行する場合は `./mvnw` を `.\mvnw.cmd` に読み替える。`-Dtest=...` 全体を引用符で囲む例は、そのまま 1 引数として渡す。

Docker Desktop の起動、依存・イメージの取得、Container の作成は、実行環境と必要な権限を確認して行う。

### 通常確認用の環境との関係

Unit 06 の Test を実行するために、`docker compose up` や Application の起動は必要ない。  
PostgreSQL の固定 Port、Compose の永続 Volume、WireMock は Test の前提に含めない。

Test 用 Schema は自動で用意されるため、通常 DB への手動 migration や Seed Data の再投入も行わない。  
実行結果を調べる際も、まず Test の接続先とログを確認し、通常 DB のデータ削除で解決しようとしない。

## コードリーディング

この節では、最初に SQL と保存先を確認し、Test の接続先・Fixture を理解してから、各 Repository の振る舞いと Assertion を追う。  
最後に DB 制約と Rollback を読み、Repository Test が成立するための前提も確認する。

```text
Repository の契約 / Schema
  ↓
Test Slice / Container / Fixture
  ↓
商品: SELECT / Row Mapping / 在庫 UPDATE
  ↓
注文: INSERT / 生成 ID / 明細 / JOIN 集約
  ↓
検索条件 / 並び順 / 状態 UPDATE
  ↓
制約違反 / SQLSTATE / 境界値
  ↓
接続先 / Test Transaction / Rollback / 独立性
```

各 Step では、Test の Method 名を探し、対応する Production Method、Arrange、Act、Assert の順に読む。  
`@Sql` による準備は Test Method の外に書かれているため、Method 本体だけで入力の全体を判断しない。

### 1. Repository の契約を確認する

最初に [`ProductRepository`](../../src/main/java/com/example/orderapi/repository/ProductRepository.java) と [`OrderRepository`](../../src/main/java/com/example/orderapi/repository/OrderRepository.java) を読む。

| Repository | Method                            | 戻り値から読み取る契約               |
| ---------- | --------------------------------- | ------------------------------------ |
| Product    | `findAll`                         | 商品の List                          |
| Product    | `findById`                        | 0 件・1 件を表す `Optional<Product>` |
| Product    | `decreaseStock` / `increaseStock` | 更新した行数                         |
| Order      | `insertOrder`                     | DB が採番した注文 ID                 |
| Order      | `insertOrderItems`                | 渡した親 ID へ複数明細を保存する操作 |
| Order      | `findById`                        | 明細を含む `Optional<Order>`         |
| Order      | `search`                          | 任意条件に合う注文の List            |
| Order      | `updateStatus`                    | 期待状態を条件に更新した行数         |

未存在時に Repository が `Optional.empty()` を返すことと、Service が業務例外へ変換し、Web 層が HTTP 404 にすることは別の責務である。  
Unit 06 では interface の契約を JDBC 実装が満たすかを確認する。

### 2. Schema の親子関係と型を確認する

[`schema.sql`](../../src/main/resources/schema.sql) を読み、3 テーブルの関係を把握する。

```text
products
  └─ id ← order_items.product_id

orders
  └─ id ← order_items.order_id
```

`order_items` の外部キーを成立させるため、商品と注文ヘッダが先に存在する必要がある。  
また、`UNIQUE (order_id, product_id)` は商品 ID 自体を一意にするのではなく、同じ注文内での商品重複を防ぐ。

金額の `NUMERIC(12, 0)`、日時の `TIMESTAMPTZ`、nullable な `cancelled_at`、数量の `BETWEEN 1 AND 99` を確認する。  
後で読む Mapper と Assertion の型・値が、この DDL に対応する。

末尾には検索を補助する Index もある。  
今回の Test は同じ Schema から Index も作成するが、Index の利用有無や速度を測定する Test は実施していない。

### 3. 商品 Repository の SQL と Parameter を追う

[`JdbcProductRepository`](../../src/main/java/com/example/orderapi/repository/jdbc/JdbcProductRepository.java) の 4 つの SQL と、その SQL を呼び出す Method を読む。

- `SELECT_ALL_SQL`：全商品を ID 順で取得する。
- `SELECT_BY_ID_SQL`：名前付き Parameter の `id` で 1 商品を取得する。
- `DECREASE_STOCK_SQL`：その時点の在庫が十分な場合だけ減算する。
- `INCREASE_STOCK_SQL`：DB の現在在庫へ加算する。

Constructor へ渡す `NamedParameterJdbcTemplate` と、`Map.of` の Key を確認する。  
例えば Java の Key `quantity` と SQL の `:quantity` が対応し、SQL の Column 名と Java の Parameter 名は同じものではないことを区別する。

`mapProduct` では、DB の Column 名を指定して各値を読み取る。  
`ResultSet` を呼び出し側へ返さず、Repository 内で `Product` に変換している点も確認する。

### 4. Test Slice の設定を上から読む

[`JdbcProductRepositoryTest`](../../src/test/java/com/example/orderapi/repository/jdbc/JdbcProductRepositoryTest.java) の Class Annotation を読む。  
他の 3 Class も同じ JDBC 用の共通設定を使う。

```java
@JdbcTest(properties = "spring.sql.init.mode=never")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresJdbcTestConfiguration.class)
@Sql("/sql/unit06/products.sql")
```

ここでは、JDBC 用 Context の構築、通常の SQL 初期化の停止、DataSource の置換方針、共通設定の追加、Test Data の投入を分けて読む。  
`@Sql` の先頭 `/` は Classpath 上の Script を指す指定であり、OS のルートディレクトリを指定しているわけではない。

Test は `@Autowired` で実 Repository と `JdbcTemplate` を受け取る。  
Repository は Act、JdbcTemplate は保存結果を直接調べる Assert などに使用し、両者の役割を混同しない。

### 5. 共通設定と Container の初期化を追う

[`PostgresJdbcTestConfiguration`](../../src/test/java/com/example/orderapi/support/PostgresJdbcTestConfiguration.java) を読む。  
まず `@Import({JdbcProductRepository.class, JdbcOrderRepository.class})` により、テスト対象の実装だけを追加していることを確認する。

次に `postgresContainer` Bean を読む。  
`@ServiceConnection`、`postgres:18.6`、DB 名 `unit06_repository_test`、`.withInitScript("schema.sql")` がそれぞれ何を決めるかを確認する。

```text
Container Bean の設定
  ↓
PostgreSQL 起動・schema.sql 実行
  ↓
Container の接続情報を Spring Boot へ提供
  ↓
JDBC 基盤と実 Repository が利用可能になる
```

ホスト Port の固定や、Compose の Volume を指定する処理はない。  
また、Test Method ごとの `start()` / `stop()` や JUnit の `@Container` を使わず、Container の Lifecycle を Spring の管理へ合わせている。

### 6. 商品 SQL Fixture の差に意味を見つける

[`products.sql`](../../src/test/resources/sql/unit06/products.sql) を読む。

| ID   | 商品名       | 価格 | 在庫 | active |
| ---- | ------------ | ---- | ---- | ------ |
| 1001 | Keyboard     | 3000 | 10   | true   |
| 1002 | Mouse        | 2500 | 20   | true   |
| 1003 | Archived Hub | 1000 | 0    | false  |

実際の INSERT 順は `1003 → 1001 → 1002` である。  
一覧の期待値を `1001 → 1002 → 1003` とすることで、Fixture の投入順と取得すべき順を分けている。

販売停止・在庫 0 の行は、Mapper の `false` と `0`、一覧に含める範囲、在庫の復元を確認するために存在する。  
Mouse の在庫 20 は、Keyboard の更新が別の商品へ及ばないことを観測する比較対象にもなる。

### 7. 注文 Fixture から検索の期待値を組み立てる

[`orders.sql`](../../src/test/resources/sql/unit06/orders.sql) を読む。  
日時を UTC へ揃えると、注文ヘッダの配置は次のようになる。

| 注文 ID | status    | created_at（UTC）    | 検索 Fixture での意味          |
| ------- | --------- | -------------------- | ------------------------------ |
| 10001   | CONFIRMED | 2026-09-30 23:59:59Z | 下限より前                     |
| 10002   | CONFIRMED | 2026-10-01 00:00:00Z | 下限ちょうど                   |
| 10003   | CONFIRMED | 2026-10-02 00:00:00Z | 範囲内・2 明細                 |
| 10004   | CREATED   | 2026-10-02 00:00:00Z | 10003 と同時刻・別状態         |
| 10005   | SHIPPED   | 2026-10-03 00:00:00Z | 上限ちょうど                   |
| 10006   | CANCELLED | 2026-10-04 00:00:00Z | 上限より後・日時の offset 変換 |
| 10007   | CONFIRMED | 2026-10-04 00:00:00Z | 上限より後・10006 と同時刻     |

`CONFIRMED` は範囲の前・中・後にある。  
status 条件だけが効いても日時条件の不備を見逃さないよう、条件の違いで結果が変わる配置となっている。

明細は全部で 8 件であり、注文 10003 だけが 2 明細を持つ。  
その 2 行は `20004 → 20003` の順に投入し、取得時には `20003 → 20004` を期待する。同じ注文の重複と明細の順序を、別の観点として確認する。

### 8. Java Fixture と SQL Fixture の役割を分ける

[`JdbcRepositoryTestFixture`](../../src/test/java/com/example/orderapi/support/JdbcRepositoryTestFixture.java) を読む。

`KEYBOARD_ID` などは既存行を示す固定値であり、`MISSING_ID` は未存在の入力に使う。  
日時も実行時の現在時刻ではなく固定値とし、`CREATED_AT` と `CANCELLED_AT` は小数部 6 桁を含む。

`newOrder` と `newOrderItems` は、呼び出すたびに保存入力を作る。  
DB への INSERT は行わず、Repository の保存処理へ渡す Java Object を準備する factory である。

```text
Keyboard: 3000 × 2 = 6000
Mouse:    2500 × 2 = 5000
小計:     11000
割引:      1100
送料:       800
合計:     10700
```

この金額は Test の前提として明示されている。  
ここで `OrderPriceCalculator` の正しさや Service の注文作成フローを再検証するわけではない。保存したい値を SQL Parameter へ正しく渡せるかを確認するための入力となる。

### 9. 商品一覧の内容・順序・空結果を確認する

`JdbcProductRepositoryTest` の次の 2 Method を読む。

- `findAll_returnsEveryProductInIdOrder`
- `findAll_whenTableIsEmpty_returnsEmptyList`

最初の Case は ID と商品名を `containsExactly` で比較する。  
単に 3 件であることだけでなく、正しい対象と順序、販売停止商品の包含を保証する。

空結果の Case は、Test Transaction 内で `DELETE FROM products` を実行してから `findAll` を呼ぶ。  
この Class は商品だけを準備しているため、明細の外部キーに妨げられずに空の前提を作れる。削除した Fixture も含めて Test 終了時の Rollback 対象となる。

空 List は有効な検索結果である。  
商品が存在しないことを、`null` の返却や DB 接続の失敗と混同しない。

### 10. 商品の全 Column と Optional を確認する

`findById_mapsEveryColumn` と `findById_whenMissing_returnsEmptyOptional` を読む。

正常な商品の Case に加え、在庫 0・`active = false` の商品も Parameterized Test で確認する。  
`getId`、`getName`、`getPrice`、`getStockQuantity`、`isActive` をそれぞれ Assert し、Row Mapping の対応を追う。

```java
assertThat(product.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(price));
assertThat(product.getStockQuantity()).isEqualTo(stockQuantity);
assertThat(product.isActive()).isEqualTo(active);
```

`hasValueSatisfying` は、Optional に値があることと、その中の Product の内容を一続きで確認する。  
未存在の Case は `MISSING_ID` に対して Optional が空になることを確認し、取得失敗の契約も明確にする。

### 11. 在庫減算の成功・境界・不足・未存在を追う

`decreaseStock_whenSufficient_updatesOneRow` を読み、在庫 10 からの次の 2 入力を確認する。

| 減算数量 | 更新件数 | 保存後の在庫 | 意味                   |
| -------- | -------- | ------------ | ---------------------- |
| 2        | 1        | 8            | 在庫に余裕がある       |
| 10       | 1        | 0            | 在庫をちょうど使い切る |

更新件数に加え、`stockOf` が直接 SELECT した在庫を Assert する。  
Mouse が 20 のままであることも確認し、対象 ID の条件と更新内容を組み合わせて保証する。

次に `decreaseStock_whenInsufficient_keepsDatabaseUnchanged` を読む。  
11 個の減算は更新件数 0、在庫 10 のままとなる。これは DB CHECK の例外ではなく、WHERE の条件に該当しない正常な UPDATE の結果である。

最後に `decreaseStock_whenProductMissing_updatesNoRows` を読む。  
未存在 ID でも 0 行更新となり、既存在庫と商品件数が保たれることを確認する。

### 12. 在庫加算と現在値への累積を追う

次の 3 Method を読む。

- `increaseStock_addsQuantityToCurrentStock`
- `increaseStock_accumulatesChangesAgainstCurrentDatabaseValue`
- `increaseStock_whenProductMissing_updatesNoRows`

加算の Case は、通常商品では `10 → 12`、販売停止・在庫 0 の商品では `0 → 2` を確認する。  
在庫の復元によって販売可否まで変えないことを、更新前後の `active` の比較で確認する。

累積の Case は、同じ Test の中で `+2` と `+3` を実行し、最終在庫 15 を直接確認する。  
DB の現在値を使う加算であることを確認する Case であり、別 Thread の同時実行やすべての競合を再現する Test ではない。

未存在 ID の Case は、更新件数 0 と既存行の保持を確認する。  
成功・未成功の戻り値を、呼び出し側が判断できる契約として読む。

### 13. Java の入力 Guard と DB constraint を区別する

次の 2 Method を読む。

- `decreaseStock_whenQuantityNotPositive_rejectsBeforeUpdate`
- `increaseStock_whenQuantityNotPositive_rejectsBeforeUpdate`

数量 `0` と `-1` は、`IllegalArgumentException` と `quantity must be positive` を期待する。  
Production Code の `validatePositiveQuantity` を読み、SQL へ到達する前の拒否であることを確認する。

この Case は SQL エラーを起こしていないため、例外の確認後も同じ Test Transaction で SELECT して、在庫が 10 のままであることを確認できる。  
後で読む PostgreSQL の制約違反後の状態とは、失敗が発生する場所が異なる。

### 14. 注文ヘッダの保存と生成 ID を確認する

[`JdbcOrderRepository`](../../src/main/java/com/example/orderapi/repository/jdbc/JdbcOrderRepository.java) の `INSERT_ORDER_SQL` と `insertOrder` を読む。  
8 個の保存 Column と `MapSqlParameterSource` の対応、enum の `name()`、日時変換、KeyHolder を確認する。

続いて `JdbcOrderRepositoryTest` の `insertOrder_persistsEveryHeaderColumnAndReturnsGeneratedId` を読む。

```java
long id = repository.insertOrder(newOrder());

assertThat(id).isPositive();
Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM orders WHERE id = ?", id);
```

採番 ID で保存行を取得し、状態、郵便番号、小計、割引、送料、合計、作成日時、null のキャンセル日時を確認する。  
Column ごとに異なる意味を持つ金額を個別に比較し、Parameter の取り違えを検出する。

ここで SUT の `findById` を検証用に呼ばない理由は 2 つある。  
まず、この時点では明細が未登録であり、INNER JOIN を使う取得 SQL の対象にならない。さらに、保存と取得に同じ誤りがあると相殺する可能性があるため、単純な SELECT で保存状態を直接見る。

### 15. nullable な日時 Parameter の両側を読む

`insertOrder_withCancelledOrder_persistsCancellationTimestamp` を読む。  
`Order.restore` でキャンセル済みの入力を用意し、Service のキャンセル処理を経由せずに Repository へ渡している。

未キャンセルの null だけでなく、実際の `CANCELLED_AT` も保存できることを確認する。  
Production Code は `Types.TIMESTAMP_WITH_TIMEZONE` を指定し、null を含む Parameter の SQL 型も明示している。

入力に使った仮 ID `MISSING_ID` は、INSERT の採番に使われない。  
Test は返された ID が正で仮 ID と異なること、その行の status とキャンセル日時が正しいことを確認する。

これは「キャンセル可能な状態を Service が正しく判断した」という Test ではない。  
保存したい Domain の状態を Fixture として与え、その保存を確認している。

### 16. 複数明細の登録と親 ID の受け渡しを追う

Production Code の `insertOrderItems`、`toOrderItemParameters`、`INSERT_ORDER_ITEM_SQL` を読む。  
各明細の Parameter を配列にして `batchUpdate` し、それぞれの更新件数が 1 であることを実装内で確認する。

`insertOrderItems_persistsEveryLineUnderGeneratedOrderId` は、次の順で実行する。

```java
long id = repository.insertOrder(newOrder());

repository.insertOrderItems(id, newOrderItems());
```

入力明細の `id` と `orderId` は未確定である。  
Test は、Method 引数の生成 ID が全明細の `order_id` に保存されたことを直接 SELECT で確認する。

明細の件数だけでなく、商品 ID、商品名、単価、数量、明細金額、正の明細 ID も比較する。  
「2 件保存された」だけでは、同じ内容の行を二度保存した不備や、金額の Parameter の入れ違いを検出できないためである。

この Test の複数 SQL は Test Transaction 内で動く。  
Production の Service がこれらを 1 つの業務 Transaction として正しく呼ぶことまでは、ここから判断しない。

### 17. JOIN から Order を復元する流れを読む

Production Code の `ORDER_WITH_ITEMS_SELECT`、`findById`、`queryOrders`、`OrderAccumulator` を読む。

1. 注文と明細を JOIN し、同名の ID Column に alias を付ける。
2. ResultSet を進め、注文 ID を取り出す。
3. 初めて現れた注文 ID なら、ヘッダの値を保持する。
4. その行の明細を同じ注文の List へ追加する。
5. 最後に、各まとまりを `Order.restore` で Domain Model へ戻す。

`LinkedHashMap` は注文単位で集約しながら、初出の順を維持する。  
ヘッダを行ごとに作り直していないか、明細を 1 件だけで上書きしていないか、注文 ID と明細 ID を取り違えていないかを意識して読む。

対応する Test は `findById_aggregatesJoinedRowsAndMapsEveryColumn` である。  
注文 10003 のヘッダ全項目、明細 2 件の全項目、関連 ID、明細 ID 順を確認する。SQL Fixture が「2 明細」と「逆順 INSERT」を持つ理由がここにつながる。

### 18. 状態文字列と TIMESTAMPTZ の復元を確認する

`createOrderAccumulator`、`toOffsetDateTime`、`toInstant` と、`findById_restoresStatusAndTimestamps` を読む。

`OrderStatus.valueOf` は DB の状態文字列から enum へ変換する。  
Test は `CREATED`、`CONFIRMED`、`SHIPPED`、`CANCELLED` の 4 状態を確認する。

日時は `OffsetDateTime` で取得して `Instant` に戻す。  
キャンセル済みの Fixture は `+09:00` で投入しているが、期待値は次の同じ瞬間を表す UTC 値である。

```text
作成:       2026-10-04 09:00:00+09
            = 2026-10-04T00:00:00Z

キャンセル: 2026-10-05 09:00:00.654321+09
            = 2026-10-05T00:00:00.654321Z
```

未キャンセルの状態では `cancelledAt` が null、キャンセル済みでは固定の `CANCELLED_AT` になることを確認する。  
状態の読み取りと、状態遷移を許可する業務判断は分けて考える。

### 19. Snapshot の Case で、変わる値と変わらない値を読む

`findById_preservesOrderedProductSnapshot` を読む。

Arrange で、商品 1001 の現在の名前を `Renamed Keyboard`、価格を 9000 に更新する。  
Act で注文 10003 を取得し、Assert では明細の商品名 `Keyboard`、単価 3000、明細金額 6000 を確認する。

```text
products の現在値:       Renamed Keyboard / 9000
order_items の保存値:    Keyboard / 3000
注文取得で期待する値:     Keyboard / 3000
```

Production の取得 SQL が `products` の現在値を参照せず、明細の Snapshot を返すことを意識する。  
現在値と保存値が同じ Fixture だけでは、この違いを検出できない。

### 20. 注文取得の未存在と入力エラーを読む

次の 2 Method を読む。

- `findById_whenMissing_returnsEmptyOptional`
- `findById_whenIdNotPositive_rejectsInput`

存在しない正の ID は空 Optional、`0` と `-1` は `IllegalArgumentException` となる。  
SQL を実行した結果が 0 件であることと、API の入力条件を満たしていないことを分けている。

商品・注文の各 Method がすべて同一の Guard を持つわけではない。  
一方の実装から他方の入力仕様を推測せず、対象 Method の Code と Test を確認する。

### 21. 動的 WHERE と 8 通りの検索条件を対応させる

Production Code の `search` を読み、条件追加と Parameter 追加が対になっていることを確認する。  
指定なしの条件を SQL へ含めず、残った条件を `AND` でつなぐ。

次に `search_appliesOnlySpecifiedConditionsAndKeepsOrder` と `searchConditions` を読む。  
`SEARCH_FROM` は `2026-10-01T00:00:00Z`、`SEARCH_TO` は `2026-10-03T00:00:00Z` であり、指定する status は `CONFIRMED` である。

| status | 下限   | 上限   | 期待する注文 ID の順序                          |
| ------ | ------ | ------ | ----------------------------------------------- |
| 未指定 | 未指定 | 未指定 | 10007, 10006, 10005, 10004, 10003, 10002, 10001 |
| 指定   | 未指定 | 未指定 | 10007, 10003, 10002, 10001                      |
| 未指定 | 指定   | 未指定 | 10007, 10006, 10005, 10004, 10003, 10002        |
| 未指定 | 未指定 | 指定   | 10005, 10004, 10003, 10002, 10001               |
| 指定   | 指定   | 未指定 | 10007, 10003, 10002                             |
| 指定   | 未指定 | 指定   | 10003, 10002, 10001                             |
| 未指定 | 指定   | 指定   | 10005, 10004, 10003, 10002                      |
| 指定   | 指定   | 指定   | 10003, 10002                                    |

期待 ID は Fixture と仕様から明示され、SUT の検索結果から作っていない。  
`containsExactlyElementsOf` により対象、件数、重複、順序を確認する。

特に、同時刻の `10004 → 10003`、`10007 → 10006` は第 2 ソートキーの ID 降順に対応する。  
また、該当する注文 10003 が 2 明細を持つことも確認し、`search` の経路でも JOIN 集約が成立することを見ている。

### 22. 日時境界・空結果・空テーブル・逆転範囲を読む

次の 4 Method を読む。

| Method                                          | 確認すること                                       |
| ----------------------------------------------- | -------------------------------------------------- |
| `search_includesBothDateBoundaries`             | 下限と上限を同じ瞬間にしても、その瞬間の注文を含む |
| `search_whenNoOrderMatches_returnsEmptyList`    | データはあるが、状態と期間に該当しない場合の空結果 |
| `search_whenOrderTableIsEmpty_returnsEmptyList` | 注文データ自体がない場合の空結果                   |
| `search_whenFromIsAfterTo_rejectsInput`         | 下限が上限より後なら SQL 実行前に拒否する          |

日時境界は `2026-10-01T00:00:00Z` と `2026-10-03T00:00:00Z` をそれぞれ両端へ渡し、注文 10002 または 10005 だけを期待する。  
単に範囲内の注文が取れる Case では、`>=` を `>` に変えた不備を検出できない。

空テーブルの Method には `@Sql("/sql/unit06/products.sql")` がある。  
Class の `products.sql` と `orders.sql` の組み合わせを置き換え、この Method では注文を投入しない。前の Test が注文を削除することに頼らず、Test 自身が空の前提を決めている。

### 23. 期待状態が一致する UPDATE と日時保存を確認する

Production Code の `UPDATE_STATUS_SQL` と `updateStatus` を読む。  
WHERE の `expectedStatus` と SET の `newStatus` は別の Parameter である。

対応する `updateStatus_whenExpectedStatusMatches_updatesOneRow` は、次の 2 入力を確認する。

| 注文 ID | 期待する現在状態 | 保存する新状態 |
| ------- | ---------------- | -------------- |
| 10004   | CREATED          | CONFIRMED      |
| 10003   | CONFIRMED        | SHIPPED        |

更新件数 1、保存した状態、null のキャンセル日時、比較対象の注文 10005 がそのままであることを確認する。  
ここで検証しているのは、Service から渡された条件と値の保存である。

`updateStatus_whenCancelled_persistsTimestamp` は、状態とキャンセル日時を同じ UPDATE で保存する。  
固定の `CANCELLED_AT` が復元できることに加え、作成日時が元のままであることも直接確認する。

### 24. 0 行更新で変更しないことを確認する

次の 2 Method を読む。

- `updateStatus_whenExpectedStatusDiffers_keepsDatabaseUnchanged`
- `updateStatus_whenOrderMissing_updatesNoRows`

前者は、実際には CREATED の注文 10004 に、現在状態 CONFIRMED を期待して更新を依頼する。  
更新件数 0 だけでなく、保存状態が CREATED、キャンセル日時が null のままであることを確認する。

後者は、未存在 ID への更新が 0 件となり、注文の総数も 7 件のままであることを確認する。  
期待不一致の UPDATE を、新規 INSERT や無条件の更新として扱っていないかを確認する。

この 2 Case だけで、同時更新のすべてを再現したとは扱わない。  
今回は期待状態の条件が SQL へ含まれていることと、その逐次実行の結果を確認している。

### 25. 制約 Test の SUT と不正 SQL を確認する

[`DatabaseConstraintTest`](../../src/test/java/com/example/orderapi/repository/jdbc/DatabaseConstraintTest.java) を読む。  
Repository を呼ばず `JdbcTemplate` を使う理由は、Domain / Repository の Guard を通過できない値でも、DDL の制約そのものへ到達させるためである。

`invalidWrite_isRejectedByExpectedDatabaseConstraint` と `invalidDatabaseWrites` の対応を追う。

| 不正な書き込み                | 期待する SQLSTATE | 確認する制約              |
| ----------------------------- | ----------------- | ------------------------- |
| 商品価格 0                    | 23514             | 正の価格の CHECK          |
| 在庫 -1                       | 23514             | 非負在庫の CHECK          |
| 状態 UNKNOWN                  | 23514             | 許可した注文状態の CHECK  |
| 郵便番号 ABC0001              | 23514             | 数字 7 桁の正規表現 CHECK |
| 明細数量 0                    | 23514             | 数量の下限                |
| 明細数量 100                  | 23514             | 数量の上限                |
| 商品 ID 1001 の重複           | 23505             | 商品の PRIMARY KEY        |
| 注文 10003 / 商品 1001 の重複 | 23505             | 注文内の商品 UNIQUE       |
| 未存在の注文 999999 を参照    | 23503             | 注文への FOREIGN KEY      |
| 未存在の商品 999999 を参照    | 23503             | 商品への FOREIGN KEY      |
| 商品名 NULL                   | 23502             | 商品名の NOT NULL         |

すべてを「不正値なら例外」と一括りにせず、実行 SQL と制約の対応を確認する。  
SQLSTATE は制約の種類を区別する情報であり、制約名まで完全一致を検証しているわけではない。

### 26. 例外の観測と数量の有効境界を読む

異常系の Assertion は、まず `DataIntegrityViolationException`、次に `getMostSpecificCause()` で得た `SQLException`、最後に `getSQLState()` を確認する。  
翻訳やバージョンで変わり得る DB のエラーメッセージ全文には依存しない。

ここでは例外後の SELECT がないことにも意味がある。  
制約違反で Transaction が失敗状態になっているため、その実行はそこで観測を終え、Rollback に任せる。

続いて `orderItemQuantity_acceptsBothDatabaseBoundaries` を読む。  
`1` と `99` を INSERT し、更新件数 1 と保存された数量を SELECT で確認する。

追加先は、元々 Mouse だけを持つ注文 10004 に対する Keyboard である。  
商品重複の UNIQUE 違反を避け、数量の許可境界を確認する Case として成立させている。

### 27. 接続先と Test 開始時の前提を確認する

[`JdbcTransactionTest`](../../src/test/java/com/example/orderapi/repository/jdbc/JdbcTransactionTest.java) の `verifyFreshFixtureInsideTestTransaction` を読む。  
`@BeforeEach` は Test Transaction と `@Sql` の準備の後に動き、Transaction が active、商品 3 件、注文 0 件、明細 0 件、Keyboard 在庫 10 を確認する。

次に `dataSource_connectsToTestContainerInsteadOfApplicationDatabase` を読む。

- 注入された Container が実行中である。
- DataSource の JDBC URL が、その Container の URL と一致する。
- DB 製品名が PostgreSQL、major version が 18 である。
- DB 名が `unit06_repository_test` である。
- 商品 ID は 1001・1002・1003 だけであり、通常用 `data.sql` の商品が混入しない。

`dataSource.getConnection()` で直接取得した Connection は、try-with-resources で返却する。  
そこで行うのは Metadata の確認だけであり、その別 Connection から未 Commit の Fixture を SELECT してはいない。Fixture の行の確認は、Test Transaction に参加する JdbcTemplate で行っている。

### 28. Test の中で Rollback を終え、その外側から確認する

`rollback_removesChangesAndTransactionalFixture` を読む。

```java
assertThat(repository.decreaseStock(KEYBOARD_ID, 2)).isEqualTo(1);
assertThat(stockOfKeyboard()).isEqualTo(8);

TestTransaction.flagForRollback();
TestTransaction.end();
```

更新後の 8 を同じ Test Transaction 内で観測した後、Rollback を指定して Transaction を終了する。  
その後で `TestTransaction.isActive()` が false、3 テーブルの件数がすべて 0 であることを直接 SELECT する。

重要なのは、在庫が 10 に戻ったとだけ判断するのではなく、Fixture の INSERT 自体も取り消されることである。  
Schema は Container 初期化で作成したため残り、Test Transaction が投入した商品行はなくなる。

```text
Test Transaction 内: 商品 3 件、在庫 10 → 8
  ↓ Rollback
Test Transaction 外: 商品 0 件、注文 0 件、明細 0 件
                     テーブルは存在する
```

次の Test の状態だけから推測するのではなく、この Test の中で終了を明示して DB 最終状態を観測している点を確認する。

### 29. 繰り返し実行と Context 共有を分けて読む

`repeatedExecution_startsWithIndependentDatabaseFixture` を読む。  
`@RepeatedTest` は 2 回実行し、各回で Keyboard の在庫を `10 → 8` にする。

各回の前に `@Sql` と `@BeforeEach` が働き、前回の 8 を引き継がない。  
Test 内に在庫を戻す UPDATE、sleep、実行順固定はない。独立性を作っているのは Repeated Test の名前ではなく、Fixture と Transaction の構成である。

また、4 Class の共通設定は Spring Context の再利用を可能にする。  
環境を共有して起動コストを抑えながら、行データは Test ごとに独立させるという 2 つの設計を分けて読む。

### 30. Assertion の経路と保証範囲を振り返る

最後に、登録 Test と取得 Test がどのように前提と結果を作っていたかを見直す。

```text
保存の Test:
Java Fixture → Repository の INSERT / UPDATE → 直接 SELECT で保存状態を確認

取得の Test:
SQL Fixture → Repository の SELECT / Row Mapping → Domain の内容を確認

制約の Test:
SQL Fixture → 不正 SQL を直接実行 → 例外・SQLSTATE を確認

環境・Transaction の Test:
接続 Metadata / TestTransaction / 直接 SELECT → 接続先と終了後の状態を確認
```

すべてを `保存 → SUT の取得 → 等価比較` の形へ統一していないことに注目する。  
何を検証する Case かに合わせて観測経路を選び、SUT の読み書きの誤りが互いを隠す可能性を減らしている。

そのうえで、Unit 03 の Service Test と Unit 05 の Web MVC Test を思い出す。  
今回の Test は、それらが Test Double の外へ置いていた永続化部分を補う。どの Test も同じアプリケーションを使うが、主張できる保証はそれぞれ異なる。

## 実行・確認

### 1. Java と Docker の前提を確認する

リポジトリルートで実行する。

```bash
java -version
./mvnw -version
docker info
```

Java と Maven が使用する Java が 21 であること、Docker の Client だけでなく Server の情報も取得できることを確認する。  
Windows では、Docker Desktop の Linux Engine が利用できる状態にする。Docker のコマンドが存在することだけでは、Engine への接続確認にはならない。

初回は Maven の配布物・依存関係や Container イメージの取得が発生し得る。  
取得できない場合と、Repository の Assertion が失敗した場合を分けて扱う。

### 2. 商品 Repository の Test を実行する

```bash
./mvnw -Dtest=JdbcProductRepositoryTest test
```

JUnit が報告する実行数は 17 件となる。  
特に次の対応を確認する。

- 一覧は Fixture の投入順ではなく ID 昇順になる。
- `active = false`、在庫 0 も正しく読み取る。
- 減算数量 10 は成功して残数 0、11 は 0 行更新で残数 10 になる。
- 加算は DB の現在値へ累積し、販売可否や別商品の在庫を変えない。
- 数量 0・負数は Java の Guard で拒否し、DB の値は変わらない。

Test が成功したことに加え、各 Case の期待値が SQL のどの条件に対応するかを Code と照合する。

### 3. 注文 Repository の Test を実行する

```bash
./mvnw -Dtest=JdbcOrderRepositoryTest test
```

実行数は 30 件となる。  
生成 ID と保存 Column、明細の親 ID、JOIN 集約、Snapshot、8 通りの検索条件、日時の両端、期待状態付き UPDATE を確認する。

登録 Case の成功を「保存後に SUT の取得が成功した」と読み替えない。  
実際の Test は単純な SELECT で保存行を確認している。検索 Case は、SUT で保存した行ではなく SQL Fixture を前提にしている。

### 4. DB 制約の Test を実行する

```bash
./mvnw -Dtest=DatabaseConstraintTest test
```

実行数は 13 件となる。  
内訳は、不正な SQL の拒否 11 件と、数量の有効境界 `1`・`99` の 2 件である。

この Class は例外が発生することを期待しているため、DB の制約違反が Test の成功条件に含まれる。  
一方、期待する制約の種類と SQLSTATE が違えば失敗となる。ログ中に DB エラーに関する情報があることだけで、Test 全体が失敗したとは判断しない。

### 5. 接続先・Rollback・独立性の Test を実行する

```bash
./mvnw -Dtest=JdbcTransactionTest test
```

実行数は 4 件となる。  
通常の Test が 2 件、Repeated Test が 2 回である。

次の 3 点を確認する。

1. DataSource の接続先が、実際に起動した PostgreSQL Container と一致する。
2. 明示的な Rollback の後に、Fixture の行も含めて 3 テーブルが空になる。
3. 繰り返し実行の各回で、同じ初期在庫 10 から開始できる。

Test 終了後に通常 DB を開いて行を探す必要はない。  
保存状態の確認は Test 内で行い、Fixture は Rollback、Container は Context 終了時の後始末の対象となる。

### 6. Unit 06 をまとめて実行する

```bash
./mvnw '-Dtest=JdbcProductRepositoryTest,JdbcOrderRepositoryTest,DatabaseConstraintTest,JdbcTransactionTest' test
```

| Test Class                  | Test Method 数 | Parameter / Repetition を展開した実行数 |
| --------------------------- | -------------- | --------------------------------------- |
| `JdbcProductRepositoryTest` | 12             | 17                                      |
| `JdbcOrderRepositoryTest`   | 17             | 30                                      |
| `DatabaseConstraintTest`    | 2              | 13                                      |
| `JdbcTransactionTest`       | 3              | 4                                       |
| 合計                        | 34             | 64                                      |

`@BeforeEach` や `@MethodSource` の供給 Method は、上表の Test Method 数に含めない。  
Parameterized Test や Repeated Test があるため、ソース上の Method 数と Surefire の `Tests run` は一致しない。

まとめて実行すると、同じ設定の Spring Context を再利用する経路も確認できる。  
一方、Class ごとの Maven コマンドを別々に実行した場合は、別の JVM / Context となるため、前のコマンドの Container を引き継ぐ構成ではない。

### 7. 既存 Unit を含む全 Test を実行する

```bash
./mvnw test
```

現在の構成では、既存 Unit の 68 件と Unit 06 の 64 件を合わせて 132 件となる。  
Unit 06 追加後の全 Test には DB Test も含まれるため、このコマンドにも Docker Engine が必要となる。

`*Test` という名前の Class を Maven Surefire が実行する構成であり、今回は Failsafe や別の Integration Test 用 Profile に分離していない。  
JUnit / Maven の Lifecycle や Surefire / Failsafe の使い分けは、Unit 10 で扱う。

Docker を使わず既存の一部の Plain Unit Test だけを復習する場合は、対象を明示して実行できる。

```bash
./mvnw '-Dtest=OrderTest,OrderPriceCalculatorTest' test
```

この例の成功は、その 2 Class の確認結果である。  
DB Test を含む全体の回帰確認の代わりにはならない。

### 8. キャッシュ済み環境での offline 実行

Maven の必要な依存関係と Plugin がローカルへ取得済みなら、`-o` を付けて Maven の offline mode を使える。

```bash
./mvnw -o '-Dtest=JdbcProductRepositoryTest,JdbcOrderRepositoryTest,DatabaseConstraintTest,JdbcTransactionTest' test
./mvnw -o test
```

`-o` は Maven の依存取得に関する指定である。  
Docker Engine を不要にしたり、Testcontainers のイメージ取得や補助処理の外部通信を一律に止めたりする設定ではない。Container イメージも含めたキャッシュや環境条件は別途必要となる。

offline 実行で依存関係が見つからない場合は、Test の不備ではなく、まず取得状況を確認する。  
Codex が不足分を取得する場合は、その操作の承認範囲に従う。

### 9. Surefire の結果を読む

Console の `Tests run`、`Failures`、`Errors`、`Skipped` と、最後の Build 結果を確認する。  
例えば Unit 06 のみを実行した場合、正常終了の件数は次のようになる。

```text
Tests run: 64, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

上記は見るべき項目を示す抜粋であり、起動ログ全文ではない。  
Container の Port、ID、起動にかかった時間などは環境ごとに変わる。

詳細は `target/surefire-reports/` の Class ごとの Text / XML を確認する。

```text
target/surefire-reports/
  com.example.orderapi.repository.jdbc.JdbcProductRepositoryTest.txt
  com.example.orderapi.repository.jdbc.JdbcOrderRepositoryTest.txt
  com.example.orderapi.repository.jdbc.DatabaseConstraintTest.txt
  com.example.orderapi.repository.jdbc.JdbcTransactionTest.txt
  TEST-com.example.orderapi.repository.jdbc.*.xml
```

失敗時は、Class と Case 名、期待値と実際値、最初の原因となった例外を読む。  
レポートが残っていても、それだけでは直前のコマンドが成功した証拠にならない。実行したコマンド、終了結果、対象、レポートの更新を対応させる。

`-DskipTests` などを付けた `BUILD SUCCESS` は、Test の成功を意味しない。  
コンパイルできたことと、実 PostgreSQL を起動して 64 件を実行したことを区別する。

### 10. 失敗した段階から切り分ける

| 段階・症状                                | 最初に確認すること                                             |
| ----------------------------------------- | -------------------------------------------------------------- |
| Java のバージョンやコンパイルで失敗する   | `java -version`、Maven が使用する Java、`pom.xml`              |
| Maven が artifact / Plugin を解決できない | offline 指定、キャッシュ、取得の可否                           |
| Docker API / Linux Engine に接続できない  | Docker Server の起動、Linux Container を実行できる Context     |
| PostgreSQL / 補助イメージを取得できない   | イメージのキャッシュ、Registry 接続、実行環境の制約            |
| Container の起動・待機で失敗する          | 起動ログ、Resource、実際の原因。Assertion に到達したか         |
| `schema.sql` の実行で失敗する             | Container が使う DB、DDL と PostgreSQL の対応                  |
| `@Sql` の実行で失敗する                   | Script の Classpath、親子の投入順、重複 ID、前の変更の持ち越し |
| Repository Bean が見つからない            | `@Import` と共通 Test Configuration                            |
| 接続先や商品 ID の Assertion が違う       | `@ServiceConnection`、`replace = NONE`、Boot SQL 初期化の停止  |
| 取得値や順序だけが違う                    | SQL の対象・WHERE・ORDER BY、alias、Row Mapping、Fixture の値  |
| 期待した SQLSTATE と違う                  | 狙った制約へ到達する前に、別の制約へ違反していないか           |
| 制約違反の後に追加 SQL が失敗する         | 失敗状態の Transaction へ処理を続けていないか                  |

Spring Context の起動失敗は、外側の例外だけでなく最初の原因まで確認する。  
Docker 接続の問題を Repository の SQL 修正で直そうとしたり、Fixture の問題を通常 DB の初期化で解消しようとしたりしない。

### 11. 実行後の環境と成果物

通常終了では、Spring が管理する Container は Test Context の終了に伴って終了する。  
利用中の通常 DB / Compose の Volume を削除する操作は、この Test の手順に含まれない。

必要なら、実行前後に読み取り専用の状態を確認する。

```bash
docker ps
```

異常終了などで残ったものがある場合も、まず今回の Test の Container かを識別する。  
他の作業の Container や Volume まで対象にする一括削除は行わない。

`target/` は再生成可能な実行成果物であり、教材の正本ではない。  
実装、SQL Fixture、設定、README と、実行ログ・レポートを区別して扱う。

## 学習ポイント

### 確認したい責務から、本物にする範囲を決める

Service の業務判断を確認するときと、Repository の SQL を確認するときでは、必要な Test Boundary が異なる。  
Test Double を多く使うか、実 DB を使うかを先に決めず、何の故障を検出したいかから選ぶ。

```text
Service の Unit Test:
Repository の応答を制御 → 業務判断を確認

Repository の DB Integration Test:
Repository と DB を実行 → SQL と永続化の契約を確認
```

両方を用意することで、業務ロジックと永続化の問題を切り分けやすくなる。  
広い Test だけですべてを確認する構成より、失敗の責任範囲も読み取りやすくなる。

### 更新件数と保存状態を組み合わせる

UPDATE が例外なく終了したことだけでは、正しい行が正しい値になったとは限らない。  
件数、対象行の値、変更しない比較対象を組み合わせて、SQL の条件と内容を確かめる。

また、0 行更新は必ずしも DB 障害ではない。  
在庫不足や期待状態の不一致など、API が返す有効な結果である場合がある。何を例外にし、何を戻り値として伝えるかを契約から読む。

### Fixture の差が、Test の検出力を作る

同じ状態・同じ時刻・同じ値のデータだけでは、条件の抜けや Column の取り違えを見逃しやすい。  
Fixture には、境界の内外、同時刻の別 ID、複数明細、false、0、Snapshot と現在値の差など、検出したい誤りに対応する差を作る。

データ件数を増やすこと自体が目的ではない。  
「この行がなければ、どの誤りを検出できなくなるか」を説明できる状態にする。

### 期待値と観測経路を SUT に依存させすぎない

保存と取得を同じ SUT だけで往復すると、両方の誤りが相殺する可能性がある。  
今回のように、取得の前提は SQL、保存の入力は Java、保存結果の確認は単純な SELECT と分けることで、検証の根拠を読み取りやすくできる。

直接 SELECT も Test Code であり、絶対に誤らない観測装置ではない。  
複雑な SQL を再実装せず、確認したい Column や行数へ絞って単純に保つことが重要となる。

### SQL の行と Domain の Object は同じ単位とは限らない

1 対多の JOIN では、ResultSet の行数と Order の件数が異なる。  
ID の alias、集約の Key、明細の蓄積、順序の維持まで含めて、取得の契約を考える。

件数だけ、ヘッダだけ、先頭明細だけの Assertion では十分でない場合がある。  
今回の 2 明細のように、変換の構造に合った代表例を選ぶ。

### 環境の寿命と行データの寿命を分ける

Container の起動、Schema の作成、Fixture の投入、Test の更新は、同じタイミングで行う必要はない。  
高コストな環境は Context と共有し、Test の前提となる行は Transaction ごとに独立させる設計ができる。

共有 Context があることだけで Flaky Test になるわけではない。  
どの状態を共有し、何を毎回準備し、何を確実に取り除くかが再現性を左右する。

### Cleanup の結果を観測する

「Test Framework が Rollback するはず」という理解に加え、どの変更が取り消されたかを実際に観測する。  
今回の Test は、明示的に Transaction を終え、その外側で Fixture の行もなくなることを確認する。

ただし、その成功を Service の業務 Transaction の保証へ広げない。  
Transaction を開始した主体と境界を説明できることが、次の Unit への土台となる。

### 同じ入力値でも、検証する Layer が違う

数量 `0` や `99` は、Domain Test と DB constraint Test の両方へ登場し得る。  
前者は Java の Guard、後者は実 DDL の拒否・許可を確認するため、同じ値でも一方が他方の完全な代替にはならない。

重複を避けることだけを優先せず、責務ごとに必要な保証を持たせる。  
一方、DB Test ですべての業務ルールを再現する必要もなく、境界と代表例を選ぶことが重要となる。

### 例外が発生した理由と、その後の状態を読む

例外の型だけでなく SQLSTATE も確認することで、意図した種類の制約違反へ到達したかを判断できる。  
また、Java の Guard と DB の SQL エラーでは、例外後の Transaction 状態が異なる。

失敗を検証する Test では、「期待どおり失敗した後に何が可能か」も設計へ含める。  
catch したことと、DB が次の操作を受け付けることは同じではない。

### 本物の DB を使う保証とコストを対応させる

実 PostgreSQL を使えば、その製品の型・SQL・Driver・制約を組み合わせて確認できる。  
その代わり Docker と Container の起動条件が必要となり、Plain Unit Test より環境要因が増える。

高速な Test をなくして実 DB Test だけへ寄せるのではなく、それぞれの役割を組み合わせる。  
成功件数とともに、どの環境で何を実行したかを記録する。

## 補足

### Spring JDBC と Spring Data JDBC

Spring JDBC は、SQL と Row Mapping を明示して JDBC 操作を行うための基盤である。  
Spring Data JDBC は、その基盤を使いながら Repository 抽象化などを提供する別の仕組みとなる。

今回の Repository interface は、アプリケーションが定義した interface である。  
Spring Data の Repository interface を継承して SQL を自動生成する構成ではなく、`@JdbcTest` を利用する。`@DataJdbcTest` を使う教材と混同しない。

### Testcontainers の JUnit 拡張と Spring 管理の Container

Testcontainers の記事では、`@Testcontainers` と `@Container` を使う構成も見かける。  
それは JUnit 拡張に Container の Lifecycle を管理させる方法であり、今回の `@Bean` による Spring 管理とは担当が異なる。

この Unit では、キャッシュされる Spring Context と接続先の Container の寿命を合わせるため、Spring 管理を採用している。  
別方式の Annotation や手動 `stop()` を追加すると、Context が DataSource を保持している間に Container だけを終了してしまう可能性がある。複数の管理方法を目的なく混在させない。

Testcontainers の cleanup 用補助 Container が現れる場合もある。  
それは送料 API の Stub や通常用 Compose の Container ではなく、Testcontainers の実行基盤に関係するものとして識別する。

### Container を共有することと再利用を永続化すること

今回の共有は、同一の Test 実行内で Spring Context をキャッシュすることによる共有である。  
実行をまたいで Container を残す `withReuse(true)` や、通常利用中の永続 Volume を用いる構成にはしていない。

Container が毎回新しいことだけでは、1 回の実行中の Test 同士が独立するとは限らない。  
同じ Container 内でも Fixture と更新を Rollback する必要がある。逆に、行の Rollback だけでは通常 DB への誤接続を防げないため、両方の設計を組み合わせる。

### @Sql の上書きと別 Transaction での投入

`search_whenOrderTableIsEmpty_returnsEmptyList` は、Method の `@Sql` が Class の Script 指定を置き換える標準の動作を利用している。  
`@SqlMergeMode(MERGE)` による Script の統合は指定していない。

また、今回の `@Sql` は既存の Test Transaction に参加する構成である。  
別 Transaction で Commit する投入方法や、`@Commit`、`@Rollback(false)` を利用すれば、同じ Cleanup の説明は成立しなくなる。

そのような設定が必要な Test では、永続化する範囲と後始末を別途設計する。  
この Unit の Script だけを別 Transaction で実行して、Rollback の前提を崩さないようにする。

### PostgreSQL の sequence と欠番

PostgreSQL の採番に使う sequence は、Transaction の Rollback により消費前の値へ戻るわけではない。  
Test の行を取り消しても採番が進むことがあり、欠番は正常な挙動として扱う。

`GENERATED BY DEFAULT` へ明示 ID を INSERT することも、sequence をその最大 ID まで自動調整するという意味ではない。  
今回の固定 ID は既存 Fixture を識別するために使い、新規注文の期待 ID は固定しない。

この Fixture は有限の代表 Test のための配置であり、任意の長時間実行で明示 ID と採番 ID が永久に衝突しないことを保証する仕組みではない。  
連番の厳密な値や、Test の実行順から予想した ID に依存しないことが学習上の要点となる。

### 金額・日時・null の比較

`BigDecimal` の数値比較と scale を含む同値比較は異なる。  
今回の金額の Assertion は `isEqualByComparingTo` で数値を比較するが、小数桁数自体を契約とする別の機能では、その桁数も別途確認対象となり得る。

PostgreSQL の日時精度と Java の `Instant` の精度も同一とは限らない。  
今回の Fixture は小数部 6 桁の固定値を使い、現在時刻や sleep の長さに依存せず、保存・復元後の瞬間を比較する。任意のナノ秒値をそのまま保存できるという主張ではない。

未キャンセルの日時は null である。  
新規ヘッダの null、キャンセル済みヘッダの非 null、更新時の null / 非 null、取得時の null / 非 null を別の経路で確認する。

### 直接取得した Connection と Test の Connection

Spring の Transaction 管理下で使う JdbcTemplate は、現在の Transaction に対応する Connection を利用する。  
一方、DataSource から直接取得した Connection を、無条件に同じ Transaction の Connection と考えてはいけない。

`JdbcTransactionTest` は、直接取得した Connection では URL、DB 製品、バージョン、DB 名という Metadata だけを確認する。  
未 Commit の SQL Fixture をその Connection から読むことを前提にしていない。DB 行の観測は、対象の Transaction に参加する JdbcTemplate で行っている。

### 条件付き UPDATE と並行実行の検証

在庫の条件と減算を同じ UPDATE に含めること、期待状態を UPDATE の WHERE に含めることは、古い取得値を無条件で上書きしないための設計である。  
今回の Test は、その SQL 条件と保存結果を代表値で検証している。

複数の接続や Thread を同時に動かす Test は実装していない。  
ロック待ち、並行 Commit、分離レベル、リトライなどを含む保証には、別の Test 設計が必要となる。SQL の意図と、実際の Test が再現した範囲を分けて説明する。

### INNER JOIN と登録途中の状態

注文取得は `orders` と `order_items` の INNER JOIN である。  
ヘッダだけを登録した時点では、明細と結合する行がないため、その取得経路から Order を得る前提は成立しない。

これは、ヘッダ保存 Test が直接 SELECT を使う理由の一つである。  
単体の保存操作を確かめる段階と、ヘッダ・明細が揃った注文を取得する段階を区別する。

### DB 制約と Domain / Service の責務

DB 制約は、型、必須値、一意性、参照関係、Column 単位の範囲などを守る。  
今回の Schema が、注文全体のすべての整合性を自動で検証するわけではない。

例えば、数量と単価からの明細金額、明細合計とヘッダ小計の一致、状態遷移の可否、キャンセル時の在庫復元は、それぞれ Domain / Service の責務と関係する。  
Repository の `updateStatus` も、すべての状態遷移を業務判断する Method ではない。

直接 SQL で作れることと、通常の API でその状態を作ってよいことは同一ではない。  
DB 制約 Test の入力と、業務シナリオの入力を目的に応じて分ける。

### 計画資料と現在の在庫更新 API

サンプルアプリケーション仕様には、在庫更新操作を `updateStock(productId, newStockQuantity)` と記載している箇所がある。  
現在の `ProductRepository` と JDBC 実装は、`decreaseStock(productId, quantity)` と `increaseStock(productId, quantity)` を提供している。

この README と Unit 06 の Test は、既存の実装にある API と、その条件付き減算・現在値への加算に対応する。  
仕様資料にある Method が実在すると仮定して読まず、今回のために Production Code を任意値への在庫上書きへ変更しているわけでもない。

### DBUnit を導入する場合にも残る設計

DBUnit の dataset を利用すれば、初期行や expected dataset を、Code とは別のデータ表現で管理できる。  
多くのテーブルをまとめて比較したい場面などで選択肢となる一方、どの Column を比較するか、生成 ID や日時をどう扱うか、投入・削除の順をどうするかは検討が必要となる。

Testcontainers を採用しただけで dataset が用意されるわけではなく、DBUnit を採用しただけで安全な DB 環境が起動するわけでもない。  
また、どちらも単独で Test Transaction の境界を正しく設計してくれるものではない。

この Unit の実装では、次の対応を読むことで同じ設計課題を学べる。

| 必要なもの                          | 今回の表現                                        |
| ----------------------------------- | ------------------------------------------------- |
| 初期 dataset に相当する既知の状態   | `products.sql` / `orders.sql`                     |
| 保存操作へ渡す入力                  | `JdbcRepositoryTestFixture` の factory            |
| expected dataset に相当する期待結果 | 明示した ID 一覧・Column 値・AssertJ の Assertion |
| DB 環境                             | Testcontainers の PostgreSQL                      |
| Test ごとのデータ分離               | `@Sql` と Test Transaction / Rollback             |

ここでの「相当」は、SQL Fixture を DBUnit の dataset ファイルとして実行しているという意味ではない。  
役割を比較して理解するための対応であり、今回 DBUnit をインストールする手順はない。

### 次の Unit とのつながり

Unit 07 で Spring Context、Service、Repository、PostgreSQL を組み合わせる。  
Unit 06 で理解した SQL・Fixture・接続先・Transaction を土台に、業務処理を囲む境界へ広げる。

Unit 08 は外部 HTTP Client、Unit 09 は API 全体の連携、Unit 10 は Test の実行構成や Coverage、Unit 11 は CI へと続く。
