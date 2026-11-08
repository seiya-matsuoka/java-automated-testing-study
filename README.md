# Java Automated Testing Study

<p>
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=ffffff">
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-Study-6DB33F?logo=springboot&logoColor=ffffff">
  <img alt="JUnit" src="https://img.shields.io/badge/JUnit-Testing-25A162">
  <img alt="Maven" src="https://img.shields.io/badge/Maven-Build-C71A36?logo=apachemaven&logoColor=ffffff">
  <img alt="PostgreSQL" src="https://img.shields.io/badge/PostgreSQL-Database-4169E1?logo=postgresql&logoColor=ffffff">
  <img alt="JaCoCo" src="https://img.shields.io/badge/JaCoCo-Coverage-DA3434">
</p>

Java / Spring Boot の自動テストを、完成済みコードのコードリーディングと実行・確認を通して体系的に学習するためのリポジトリ。  
1 つの注文管理 REST API を題材に、JUnit の基本から、テストケース設計、Test Double、Web・DB・外部 HTTP のテスト、Test Suite の実行管理と Coverage までを段階的に扱う。

---

## このリポジトリの位置づけ

自動テストやテストコードの経験が少ない状態から、Java / Spring Boot のバックエンドで使われる主要なテストの考え方と実装を一周するための学習記録である。

各 Unit では、同じ注文管理 REST API に対してテストの境界を変えながら学ぶ。  
小さな Java クラスの振る舞い、依存先との連携、Web 層の入出力、DB への永続化、HTTP 通信、API 全体を、それぞれ適した粒度で確認する。

Production Code は `src/main/`、Test Code は `src/test/` に集約し、Unit を通じて同じアプリケーションと蓄積した Test Suite を使用する。  
`units/` 配下には主に学習用 README を配置し、各 README から読むコードと実行方法を案内する。

詳細な概念・用語は各 Unit README、業務ルール・設計は [サンプルアプリケーション仕様](docs/planning/sample-application-spec.md) を参照する。

---

## 学習目的

このリポジトリでは、主に次の内容を理解することを目指す。

- 自動テストの目的、SUT、Test Case、Unit Test / Integration Test / API Test の役割を理解する。
- 業務仕様から、正常系・異常系・境界値・同値分割・状態遷移・条件の組み合わせを考える。
- JUnit、Parameterized Test、AssertJ を用いたテストの構造と Assertion を読み解く。
- Mock / Stub / Fake / Spy の違いと、依存先を Test Double に置き換える理由を理解する。
- テストしやすい設計、Fixture、Test Isolation、再現性、Flaky Test など、テスト自体の品質を考える。
- Web・Service・Repository・DB・HTTP のどこまでを実際に動かすかを選び、テストが保証する範囲を説明する。
- 実 PostgreSQL やローカルの HTTP Server を使って、永続化、Transaction、外部依存の障害を確認する。
- Maven で Test Suite を分類・選択して実行し、Test Report と Coverage を目的に応じて読み分ける。

---

## 学習範囲

テスト対象の境界を少しずつ外側へ広げ、最後に蓄積したテスト全体の実行管理と Coverage を扱う。

```text
小さな Java クラスの Unit Test
        ↓
テストケース設計・Test Double・テスト品質
        ↓
Spring Web 層のテスト
        ↓
Repository / PostgreSQL のテスト
        ↓
Service + Repository + DB の Integration Test
        ↓
外部 HTTP API のテスト
        ↓
REST API 全体のテスト
        ↓
Test Suite の実行管理・Coverage
```

| Unit | 学習テーマ                                                                                       | 主な内容                                                                           |
| ---- | ------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------- |
| 01   | [自動テストの全体像と JUnit 基本](units/01-automated-testing-junit-basics/README.md)             | テストの種類、SUT、AAA、基本 Assertion、例外、実行結果の読み方                     |
| 02   | [テストケース設計と JUnit 実践](units/02-test-design-junit-practice/README.md)                   | 境界値、同値分割、状態遷移、Test Matrix、Parameterized Test、Fixture、AssertJ      |
| 03   | [Test Double と Mockito](units/03-test-doubles-mockito/README.md)                                | Mock / Stub / Fake / Spy、stubbing、verification、ArgumentCaptor、時刻の制御       |
| 04   | [テストしやすい設計とテスト品質](units/04-testability-test-quality/README.md)                    | Testability、依存の分離、Isolation、Flaky / Brittle Test、問題例との比較           |
| 05   | [Spring Web 層のテスト](units/05-spring-web-testing/README.md)                                   | `@WebMvcTest`、MockMvc、Validation、JSON、例外と HTTP Error Response               |
| 06   | [SQL・Repository・DB Integration Test](units/06-sql-repository-db-integration-testing/README.md) | `@JdbcTest`、Spring JDBC、実 PostgreSQL、SQL、DB 制約、Transaction、Testcontainers |
| 07   | [Spring Boot Integration Test](units/07-spring-boot-integration-testing/README.md)               | Service・Repository・DB の連携、永続化、在庫、状態遷移、Rollback                   |
| 08   | [外部 HTTP API のテスト](units/08-external-http-api-testing/README.md)                           | RestClient、WireMock、HTTP Request / Response、timeout、外部依存の障害             |
| 09   | [REST API 全体のテスト](units/09-rest-api-testing/README.md)                                     | REST Assured、実 HTTP、Web・Service・DB・外部 HTTP Client を通した確認             |
| 10   | [テスト実行管理と Coverage](units/10-test-management-coverage/README.md)                         | Test Discovery、Tag、Surefire / Failsafe、Maven Lifecycle、JaCoCo、Coverage Check  |

ブラウザ操作を含む E2E Test、Performance / Load Test、Mutation Testing の実践などは対象外とする。  
実践する項目と概念として位置づけを学ぶ項目の詳細は、[学習カリキュラム](docs/planning/learning-curriculum.md) を参照する。

---

## 学習の進め方

基本的には Unit 01 から順に、次の流れで進める。

1. 対象 Unit の README で「この Unit の目的」と「学習内容」を読む。
2. 「テスト対象とテスト境界」「使用するファイル」「前提」を確認する。
3. 「コードリーディング」の順序に沿って Production Code を読み、業務ルールと責務を確認する。
4. 対応する Test Code を読み、SUT、実依存と Test Double、Fixture、Assertion / Verification を確認する。
5. 「実行・確認」のコマンドでテストを実行し、コンソール、Test Report、必要に応じて Coverage Report を確認する。
6. 「学習ポイント」「補足」を読み、実装と観測した結果を対応づけて整理する。

各 Unit には、学習に必要な代表ケースを完成状態で用意している。  
コードの追加を必須とせず、入力条件、期待結果、依存先、後始末までを読み解き、実行した結果と照合することを重視する。

コマンドは原則として **リポジトリルートから実行する**。  
Unit ごとに作業ディレクトリを移動せず、共通の `pom.xml` と Maven Wrapper を使用する。

現在のテスト選択は Unit 10 の設定を適用した状態である。  
過去 Unit のコマンドを使う際の読み替えは、後述の「実行・確認」と [Unit 10](units/10-test-management-coverage/README.md) を参照する。

---

## サンプルアプリケーションの概要

商品と在庫を管理し、複数の商品を含む注文の作成・取得・状態変更を行う REST API を使用する。  
注文時には商品状態・数量・在庫を検証し、小計・割引・送料・合計を計算して、注文と注文明細を PostgreSQL に保存する。

主な機能は次のとおり。

- 商品一覧・商品詳細の取得。
- 注文の作成、詳細取得、一覧取得・条件検索。
- 注文の確定・発送・キャンセルと、許可される状態遷移の検証。
- 注文作成時の在庫減算、キャンセル時の在庫復元。
- 注文金額・割引の計算と、外部 Shipping Fee API からの送料取得。
- 入力エラーや業務例外から HTTP Error Response への変換。

主な依存関係は次のようになる。

```text
HTTP Request
    ↓
Controller
    ↓
Service
    ├─ Domain / OrderPriceCalculator
    ├─ Repository（Spring JDBC） → PostgreSQL
    └─ ShippingFeeClient → Shipping Fee API
```

Spring MVC の入出力、Service の処理、Domain のルール、Repository の SQL、外部 HTTP Client を分けることで、同じ機能を異なる境界からテストできる。  
たとえば Service の Unit Test では依存先を置き換え、DB Integration Test では実 Repository と PostgreSQL を組み合わせ、API Test では実 HTTP の入口から確認する。

通常動作確認の Shipping Fee API は WireMock の Stub を使用する。  
自動テストではテストコードが別の WireMock を起動・制御し、実在する外部サービスへの接続を必要としない。

API、状態遷移、金額計算、SQL、Transaction の詳細は [サンプルアプリケーション仕様](docs/planning/sample-application-spec.md) と [Production Code](src/main/java/com/example/orderapi/) を参照する。

---

## 前提環境

| 環境               | 用途・必要になる範囲                                                                                                                  |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------- |
| Java 21 の JDK     | Production Code / Test Code のコンパイルと実行。`java` と `javac` が利用できること。                                                  |
| Git                | リポジトリの取得と学習履歴の管理。                                                                                                    |
| Windows + Git Bash | コマンド例の基本環境。Maven Wrapper は PowerShell からも利用できる。                                                                  |
| Docker Engine      | Unit 06・07・09 を含む実行で、Testcontainers の一時 PostgreSQL を動かす。Windows では Docker Desktop の Linux Containers を利用する。 |
| Docker Compose     | サンプル API を通常起動して動作確認する場合に使用する。自動テストの実行に Compose の起動は不要。                                      |

Maven はリポジトリに含まれる Wrapper を使うため、別途インストールする必要はない。  
Wrapper が使用する Maven は **3.9.16** であり、設定は [maven-wrapper.properties](.mvn/wrapper/maven-wrapper.properties) にある。

`./mvnw test` と純粋な Unit Test は Docker を使わずに実行できる。  
Unit 08 は Docker を使わないが、テスト JVM 内で WireMock を起動してローカル HTTP 通信を行う。  
全体実行や全体 Coverage の取得では、Docker Engine とローカル HTTP 通信が利用できる必要がある。

Java のクラス・例外・コレクション、Spring Boot の基本、SQL、HTTP / JSON の基礎を参照しながら進めることを想定する。  
自動テスト固有の用語や仕組みは、各 Unit の学習内容で説明する。

---

## 使用技術・ツール

| 分類                   | 技術・ツール                            | このリポジトリでの役割                                                          |
| ---------------------- | --------------------------------------- | ------------------------------------------------------------------------------- |
| 言語・アプリケーション | Java 21、Spring Boot 4.1.1              | 注文管理 REST API と、各境界のテスト対象を構成する。                            |
| Web・入力検証          | Spring MVC、Bean Validation             | HTTP 入出力、JSON、Request の検証、例外応答を扱う。                             |
| DB アクセス            | Spring JDBC、PostgreSQL 18.6            | 明示的な SQL、Row Mapping、DB 制約、永続化、Transaction を扱う。                |
| HTTP Client            | Spring RestClient                       | Shipping Fee API への Request と Response・障害を扱う。                         |
| テスト基盤             | JUnit Jupiter、AssertJ                  | Test Case、Parameterized Test、Lifecycle、Tag、Assertion を記述する。           |
| Test Double            | Mockito、テスト用 Fake                  | 依存先の振る舞いを制御し、状態と相互作用を確認する。                            |
| Spring のテスト        | Spring Test / Spring Boot Test、MockMvc | Slice Test や Spring Context を使い、必要な範囲を組み合わせる。                 |
| 実 DB の準備           | Testcontainers                          | 通常用 DB と分離した一時 PostgreSQL を Docker 上に用意する。                    |
| HTTP の Test Double    | WireMock 3.13.2                         | ローカル HTTP Server として正常応答・異常応答・遅延などを再現する。             |
| API Test               | REST Assured 6.3.0                      | 実 HTTP の Status・Header・JSON と、その後の状態を確認する。                    |
| ビルド・実行管理       | Maven Wrapper、Surefire / Failsafe      | テストの分類・選択、Lifecycle に沿った実行、Test Report を扱う。                |
| Coverage               | JaCoCo 0.8.16                           | テスト JVM の計測、実行データの集計、HTML / XML Report、明示的な Check を行う。 |

依存関係と Plugin 設定の正本は [pom.xml](pom.xml) とする。  
Spring Boot の親 POM に管理を委ねるものと、WireMock・REST Assured・JaCoCo のように版を明示するものがある。

---

## セットアップ

### Java と Maven Wrapper の確認

```bash
java -version
javac -version
./mvnw --version
```

Java 21 の JDK が使われていることを確認する。  
複数の JDK がある場合は、`JAVA_HOME` と `PATH`、Maven が表示する Java の情報を照合する。

PowerShell では、Maven Wrapper の実行部分を次のように読み替える。

```powershell
.\mvnw.cmd --version
```

### コンパイルの確認

```bash
./mvnw test-compile
```

Production Code と Test Code をコンパイルする。  
この phase では Test Method を実行しないため、成功してもテストや API の動作確認が完了したことにはならない。

### 初回取得と取得済み環境の違い

初回の Wrapper 実行では Maven 配布版が取得され、ビルド時には必要な依存関係・Plugin が取得される場合がある。  
Docker を使うテストや通常用 Compose では、必要な Image が未取得なら Docker 側の取得も発生する。

配布版・依存関係・Plugin が取得済みの環境では、Maven に `-o` を付けて実行できる。

```bash
./mvnw -o test
```

`-o` は Maven のリポジトリへの接続を制御する指定である。  
Wrapper 自体による配布版の取得、Docker Image の取得、テスト本体のローカル HTTP や JDBC 通信とは区別する。

---

## 実行・確認

### テストの実行

| コマンド        | 対象                                                     | 必要環境                                             |
| --------------- | -------------------------------------------------------- | ---------------------------------------------------- |
| `./mvnw test`   | Unit 01〜04 の純粋な Unit Test と Unit 05 の MVC Slice。 | Docker と実 HTTP は不要。                            |
| `./mvnw verify` | Unit 01〜09 の Test Suite 全体。                         | Docker / Testcontainers とローカル HTTP を使用する。 |

通常の Test Report は `target/surefire-reports/` と `target/failsafe-reports/` に出力される。

過去 Unit の README で `./mvnw test` を全体回帰として使う箇所は、現在の構成では `./mvnw verify` に読み替える。

テスト境界の詳細は [Unit 10 のテスト対象とテスト境界](units/10-test-management-coverage/README.md#テスト対象とテスト境界) を参照する。  
クラス・メソッド・Tag による部分実行、実行対象の確認、失敗時の調査は [Unit 10 の実行・確認](units/10-test-management-coverage/README.md#実行確認) を参照する。

### Coverage の取得

Docker とローカル HTTP を利用できる状態で、フィルターや skip を付けずに全体を計測する。

```bash
./mvnw -Pcoverage clean verify
```

`clean` は Coverage 専用出力先 `target/unit10-coverage/` の前回結果を削除する。  
全体の HTML Report は `target/unit10-coverage/site/jacoco-suite/index.html` に生成される。

固定の運用閾値は設けておらず、Coverage Check は別途明示的に実行する。  
部分計測、Report の読み方、Coverage Check、失敗時の切り分けは [Unit 10 の実行・確認](units/10-test-management-coverage/README.md#実行確認) を参照する。

### サンプル API の通常起動（任意）

通常用 PostgreSQL と送料 Stub を Compose で起動し、状態を確認する。

```bash
docker compose up -d
docker compose ps
```

PostgreSQL が healthy になり、送料 Stub が起動していることを確認してから、アプリケーションを起動する。

```bash
./mvnw spring-boot:run
```

別のターミナルから商品一覧を取得する。

```bash
curl http://localhost:8080/api/products
```

終了時はアプリケーションのターミナルで `Ctrl+C` を入力し、通常用 Container を停止する。

```bash
docker compose stop
```

**自動テストでは、通常用 Compose やアプリケーションの別途起動は不要である。**  
通常用の環境・データの詳細は [サンプルアプリケーション仕様](docs/planning/sample-application-spec.md) を参照する。

---

## リポジトリ構成

主なディレクトリとファイルの構成を示す。

```text
java-automated-testing-study/
├─ README.md
├─ AGENTS.md
├─ pom.xml
├─ mvnw / mvnw.cmd
├─ .mvn/
│  └─ wrapper/maven-wrapper.properties
├─ compose.yaml
├─ docker/
│  └─ wiremock/mappings/shipping-fee-normal.json
├─ docs/
│  ├─ planning/
│  │  ├─ learning-curriculum.md
│  │  ├─ learning-operation.md
│  │  └─ sample-application-spec.md
│  └─ codex-handover.md
├─ src/
│  ├─ main/
│  │  ├─ java/com/example/orderapi/
│  │  │  ├─ controller/、dto/、exception/
│  │  │  ├─ domain/、service/
│  │  │  ├─ repository/、client/、config/
│  │  │  └─ OrderManagementApplication.java
│  │  └─ resources/
│  │     ├─ application.yml
│  │     ├─ schema.sql
│  │     └─ data.sql
│  └─ test/
│     ├─ java/com/example/orderapi/
│     │  ├─ domain/、service/、controller/
│     │  ├─ repository/jdbc/、client/、api/
│     │  └─ support/
│     └─ resources/sql/
│        ├─ unit06/
│        ├─ unit07/
│        └─ unit09/
└─ units/
   ├─ 01-automated-testing-junit-basics/
   ├─ 02-test-design-junit-practice/
   ├─ 03-test-doubles-mockito/
   ├─ 04-testability-test-quality/
   │  ├─ README.md
   │  └─ examples/
   ├─ 05-spring-web-testing/
   ├─ 06-sql-repository-db-integration-testing/
   ├─ 07-spring-boot-integration-testing/
   ├─ 08-external-http-api-testing/
   ├─ 09-rest-api-testing/
   └─ 10-test-management-coverage/
```

各 Unit ディレクトリに学習用 `README.md` を配置する。  
Unit 04 の `examples/` は、依存の固定、共有状態、タイミング依存、過剰な Mock などの問題を比較する補助教材である。

`src/test/java/` にはテストと共通 Support・Fixture を配置し、`src/test/resources/sql/` には DB テスト用のデータ準備・Cleanup の SQL を配置する。  
通常起動用のデータは `src/main/resources/data.sql` に分け、通常動作確認用の環境は `compose.yaml` と `docker/wiremock/` で管理する。

---

## ドキュメント

| ドキュメント                                                             | 参照する内容                                                                           |
| ------------------------------------------------------------------------ | -------------------------------------------------------------------------------------- |
| [学習カリキュラム](docs/planning/learning-curriculum.md)                 | 学習目標、Unit 01〜10 の範囲・順序、概念の対応、対象外の領域。                         |
| [学習運用方針](docs/planning/learning-operation.md)                      | コードリーディング型の進め方、Unit README の構成、教材・コメント・実行確認の共通方針。 |
| [サンプルアプリケーション仕様](docs/planning/sample-application-spec.md) | API、業務ルール、状態遷移、DB、外部 HTTP、各層の責務とテスト境界。                     |

Unit ごとの概念説明、読むファイル、実行方法は、「学習範囲」の一覧から各 README を参照する。
