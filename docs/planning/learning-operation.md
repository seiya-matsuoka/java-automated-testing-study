# Java 自動テスト学習 運用方針

## 1. このドキュメントの目的

このドキュメントは、`java-automated-testing-study` における学習の進め方、教材の構成、生成単位、ディレクトリ構成、ドキュメント形式、コメント方針、Git 運用などの共通ルールを定義する。  
学習範囲や各 Unit で扱う内容は `learning-curriculum.md`、サンプルアプリの要件・設計・仕様は `sample-application-spec.md` で管理し、このドキュメントでは「どのように学習を進めるか」に集中する。

## 2. 学習の基本方針

今回の学習は、完成したテストコードを中心に読み解くコードリーディング型で進める。  
自動テストやテストコードの経験が少ない状態から、JUnit、Mockito、Spring Test、DB Test、外部 HTTP API Test、Coverage、CI までを段階的に理解できるようにする。

基本的な学習の流れは次のとおりとする。

```text
Unit README で目的・概念・用語を理解する
↓
Production Code を読み、テスト対象の動作や責務を確認する
↓
対応する Test Code を読む
↓
テスト対象・テスト境界・Test Double・Fixture・Assertion などを確認する
↓
テストを実行する
↓
成功・失敗・ログ・DB 状態・レポートなど必要な結果を確認する
↓
README の学習ポイントで内容を整理する
```

テストケースの追加やテストコードの修正を、正式な学習工程として必須にはしない。  
必要に応じて自主的に変更・追加を試すことは可能とするが、それを前提として教材を作らない。  
そのため、練習用の余白を残す目的で必要なテストケースを意図的に省略せず、各 Unit の学習対象として必要な代表ケースは完成状態で用意する。

## 3. 説明・解説の共通品質

すべての Unit で、扱う技術・知識・用語・概念・考え方の説明粒度と丁寧さを同じ基準にする。  
Unit ごとに、ソースコード数、テストケース数、設定ファイル数、実行手順数などの教材量は異なってよいが、説明や解説の詳しさを意図的に薄くしない。

各 Unit では、その Unit で必要となる概念や用語を、実践コードに直接現れないものも含めて十分に説明する。  
個別ツールの API や設定方法だけではなく、なぜその技術を使うのか、どのような問題を解決するのか、他の方法と何が違うのかまで理解できる内容とする。

## 4. リポジトリ構成

学習リポジトリは、メインアプリ本体、テストコード、Unit ごとの学習ドキュメント、計画ドキュメントを分離して管理する。

基本構成は次のとおりとする。

```text
java-automated-testing-study/
├─ docs/
│  └─ planning/
│     ├─ learning-curriculum.md
│     ├─ learning-operation.md
│     └─ sample-application-spec.md
│
├─ units/
│  ├─ 01-automated-testing-junit-basics/
│  │  └─ README.md
│  ├─ 02-test-design-junit-practice/
│  │  └─ README.md
│  ├─ 03-test-doubles-mockito/
│  │  └─ README.md
│  ├─ 04-testability-test-quality/
│  │  ├─ README.md
│  │  └─ examples/
│  ├─ 05-spring-web-testing/
│  │  └─ README.md
│  ├─ 06-sql-repository-db-integration-testing/
│  │  └─ README.md
│  ├─ 07-spring-boot-integration-testing/
│  │  └─ README.md
│  ├─ 08-external-http-api-testing/
│  │  └─ README.md
│  ├─ 09-rest-api-testing/
│  │  └─ README.md
│  ├─ 10-test-management-coverage/
│  │  └─ README.md
│  └─ 11-ci-automated-testing/
│     └─ README.md
│
├─ src/
│  ├─ main/
│  │  ├─ java/
│  │  └─ resources/
│  └─ test/
│     ├─ java/
│     └─ resources/
│
├─ .github/
│  └─ workflows/
│
├─ pom.xml
├─ .gitignore
├─ .gitattributes
└─ README.md
```

実際の package 構成や設定ファイルは、`sample-application-spec.md` と各 Unit の内容に応じて配置する。

## 5. Unit ディレクトリ

Unit ディレクトリ名は次のとおりとする。

| Unit | ディレクトリ名                             |
| ---- | ------------------------------------------ |
| 01   | `01-automated-testing-junit-basics`        |
| 02   | `02-test-design-junit-practice`            |
| 03   | `03-test-doubles-mockito`                  |
| 04   | `04-testability-test-quality`              |
| 05   | `05-spring-web-testing`                    |
| 06   | `06-sql-repository-db-integration-testing` |
| 07   | `07-spring-boot-integration-testing`       |
| 08   | `08-external-http-api-testing`             |
| 09   | `09-rest-api-testing`                      |
| 10   | `10-test-management-coverage`              |
| 11   | `11-ci-automated-testing`                  |

`units/` 配下には、原則として各 Unit の `README.md` を配置する。  
メインアプリやテストコードそのものは `units/` 配下へ複製せず、通常の Java / Maven プロジェクトとして `src/main/`、`src/test/` などに配置する。

同じソースコードを `src/` と `units/` の両方へ置かない。  
コードの正本を 1 か所に限定し、後続 Unit で修正された場合に複数コピーの整合性が崩れることを防ぐ。  
各 Unit で追加・変更・参照するファイルは、Unit README の「使用するファイル」から実ファイルへ案内する。

補助教材が必要な場合のみ、対象 Unit の配下に `examples/` などの補助用ディレクトリを設ける。  
現時点では、Unit 04 の問題のあるテストや設計を扱う補助教材での利用を想定する。

## 6. Unit ごとの生成単位

すべての Unit を、必ず 2 回に分けて生成する。

### 1 回目

その Unit で必要となる `README.md` 以外の成果物をすべて生成する。

対象には、必要に応じて次のようなものを含む。

- Production Code の追加・変更
- Test Code
- Test Fixture
- テストデータ
- SQL
- 設定ファイル
- Maven 設定
- Testcontainers / WireMock / REST Assured / JaCoCo 等の設定
- GitHub Actions Workflow
- 補助教材
- その他、その Unit の学習に必要なプログラム・設定類

1 回目の成果物は、その Unit の学習内容として必要な代表ケースを実装済みの完成状態とする。  
後からユーザーがテストコードを追加することを前提として、意図的な未実装や空の課題部分を残さない。

### 2 回目

1 回目で確定した実際の成果物を前提として、その Unit の `README.md` を生成する。

README は、実際のクラス名、ファイル名、設定、テストコード、実行方法と一致させる。  
1 回目と 2 回目に分けることで、説明文と実装の不一致を防ぐ。

## 7. Unit README の共通フォーマット

各 Unit の `README.md` は、原則として次の構成で統一する。

```markdown
# XX. Unit 名

## この Unit の目的

## 学習内容

## 使用する技術・ツール

## テスト対象とテスト境界

## 使用するファイル

## 前提

## コードリーディング

## 実行・確認

## 学習ポイント

## 補足
```

各項目の役割は次のとおりとする。

### この Unit の目的

この Unit を学ぶ理由と、Unit 全体を通して何を理解するかを記載する。  
個別 API やツールの操作ではなく、その Unit の位置づけと学習目的が分かる内容とする。

### 学習内容

その Unit で理解する必要がある技術、概念、用語、考え方を体系的に説明する中心項目とする。  
実践コードに直接登場しない用語や概念も、Unit の理解に必要であればここで扱う。

単なる項目一覧にせず、次の観点を含めて十分に説明する。

- 用語や概念の意味
- なぜ必要なのか
- どのような問題を解決するのか
- 他のテスト手法・概念との違い
- 実務でどのような場面に登場するか
- 今回のメインアプリやテストコードとどのようにつながるか

### 使用する技術・ツール

その Unit で実際に使用する具体的なライブラリ、フレームワーク、ツール、機能を記載する。  
それぞれについて、その Unit での役割、利用する目的、何を担当するものなのかを説明する。

「学習内容」と一部同じ技術や用語が登場しても問題ない。  
項目間の完全な重複排除よりも、説明の漏れや不足を防ぐことを優先する。

### テスト対象とテスト境界

その Unit で何を SUT とするのか、どこまでを本物として動かし、どこから先を Test Double などへ置き換えるのかを明示する。

必要に応じて、次のような情報を記載する。

```text
テスト対象:
OrderService

本物:
OrderService

Test Double:
ProductRepository
OrderRepository
ShippingFeeClient
```

DB、Spring Context、HTTP Server、外部 API などが起動するかどうかも、理解に必要な場合は明記する。

### 使用するファイル

その Unit で追加・変更する主なファイルと、コードリーディングで参照する主なファイルを記載する。

可能な限り、単なるフルパスのテキストではなく、GitHub 上で実ファイルへ移動できる相対 Markdown リンクにする。

例:

```markdown
### この Unit で追加・変更する主なファイル

- [`OrderServiceTest.java`](../../src/test/java/.../OrderServiceTest.java)

### コードリーディングで参照する主なファイル

- [`OrderService.java`](../../src/main/java/.../OrderService.java)
```

README 生成時には、実際のリポジトリ構成に基づいて正しい相対パスを使用する。

### 前提

その Unit を読む時点で理解済みとして扱う内容や、直前の Unit までに作成済みの成果物などを記載する。  
Unit 内で再度詳しく説明する必要がない既習事項を明確にするために利用する。

ただし、前提として扱うことで今回初めて登場する重要概念の説明を省略しない。

### コードリーディング

その Unit でソースコードをどの順番で読み、どこを確認するかを案内する。

基本的には、次の流れを意識する。

```text
Production Code の対象箇所を確認
↓
対応する Test Code を確認
↓
Fixture / Arrange を確認
↓
Act の対象処理を確認
↓
Assertion / Verification を確認
↓
Test Double や実依存の境界を確認
```

ファイルを上から順番に読むだけでなく、「何を見るために読むのか」が分かる説明とする。

### 実行・確認

その Unit で実行するコマンド、テスト、アプリケーション、レポートなどを記載する。  
単に「実行して成功を確認する」だけではなく、学習上見るべき結果を具体的に示す。

必要に応じて、次のようなものを確認対象とする。

- テスト成功 / 失敗
- Assertion Failure
- Stack Trace
- HTTP response
- JSON
- DB の状態
- Transaction / Rollback
- WireMock の request
- Maven Test Report
- Coverage Report
- GitHub Actions の Job / Log

実行ログは原則として全文を転載せず、学習上確認すべき部分を中心に記載する。

### 学習ポイント

Unit 全体を通して特に押さえるべき本質を整理する。  
個別 API の操作方法ではなく、他の Unit や他の技術でも応用できる考え方、実務で重要となる判断基準を中心にまとめる。

### 補足

本筋ではないが、理解を助ける比較、注意点、関連技術、発展的な情報などを記載する。  
対象外の技術や今回実践しない内容について触れる場合も、必要に応じてここを利用する。

## 8. コードリーディングの進め方

今回の学習では、完成状態の Production Code と Test Code を対応させながら読む。  
コードを読む際には、単に構文を追うのではなく、次の観点を意識する。

- 何が SUT なのか
- どの振る舞いを確認しているのか
- どの条件をテストケースとして選んでいるのか
- Test Fixture は何を準備しているのか
- どこまでが本物の依存先なのか
- どこを Mock / Stub / WireMock などに置き換えているのか
- Assertion は何を保証しているのか
- Verification は何を確認しているのか
- なぜ Unit Test / Slice Test / Integration Test / API Test のいずれとして実装しているのか
- テストが失敗した場合に、どの境界の問題として読み取れるか

代表的な正常系だけでなく、異常系、境界値、状態遷移、外部 API 障害など、各 Unit の学習目標に必要なケースを教材に含める。

## 9. テストコードのコメント方針

Test Code には、学習教材として理解を助ける日本語コメントを十分に記載する。

特に次のような内容を優先してコメントする。

- なぜこのテストケースを用意しているのか
- どの仕様・境界値・状態遷移を確認しているのか
- なぜこの依存先を Mock / Stub にしているのか
- stubbing が何を再現しているのか
- verification が何を保証しているのか
- ArgumentCaptor で何を確認しているのか
- Test Fixture の意図
- DB 初期状態の意味
- WireMock の stub が表す外部 API の状態
- Integration Test で本物として動かしている範囲
- Assertion が保証する振る舞い

コードをそのまま日本語へ言い換えるだけのコメントは避ける。  
コメントだけで理解を完結させるのではなく、README の説明とコードの実装が相互に補完する状態を目指す。

## 10. Production Code のコメント方針

メインアプリの Production Code にも、今回のテスト学習とは直接関係しない Java / Spring Boot / SQL の内容を含めて、十分に詳細な日本語コメントを記載する。

目的は、アプリ本体や Spring の実装を理解する負担を減らし、テストコードが何を対象としているのかを正しく把握できるようにすることである。

特に次の内容を丁寧に説明する。

- クラスやメソッドの責務
- Spring annotation の役割
- Bean と Dependency Injection の関係
- Controller / Service / Repository / Client の役割
- DTO と Domain Model の役割
- 処理フロー
- 業務ルール
- Transaction の意味
- `NamedParameterJdbcTemplate` の役割
- SQL の目的と取得・更新内容
- Row Mapping の役割
- 外部 HTTP Client の役割
- 設定クラスの目的
- `Clock` を利用する理由
- Testability に関係する設計意図
- 初見では理解しづらい Java の処理

一方で、変数への代入や単純な getter 呼び出しなど、コードを見れば自明な処理を日本語で読み上げるだけのコメントは避ける。

## 11. テストコードの命名

テストクラス、テストメソッド、Fixture、補助クラスなどの命名は、JUnit、Spring、Maven などの一般的な実務慣習やフレームワークの標準的な使い方に沿う。  
今回独自の特殊な命名規則は設けない。

Unit Test と Integration Test のファイル名についても、Maven Surefire / Failsafe などの一般的な規則と整合する形で実装する。  
具体的な命名は、各 Unit で扱うテストの種類と実行方式に合わせて決定する。

## 12. コマンド・実行手順の記載

README 内の実行コマンドは、コピーして利用しやすいようにコードブロックで記載する。

例:

```bash
mvn test
```

複数行のコマンドを記載する場合も、実際に利用する Shell で読みやすい形式にする。  
通常の学習環境では Windows 上の Git Bash を使用するが、可能な限り Maven / Java / Docker などの一般的なコマンドを使用し、Windows 固有の回避策を過剰に持ち込まない。

実際に環境差異による問題が発生した場合は、その時点で必要な対応を追加する。

## 13. 実行結果の記載

README では、実行結果の巨大なログ全文を保存することを目的としない。  
結果の中から、その Unit の学習内容を理解するために必要な部分を抜き出して説明する。

例えば次のような内容を対象とする。

- JUnit の成功 / 失敗件数
- Assertion Failure の要点
- Exception / Stack Trace の見るべき部分
- HTTP status / body
- DB の更新前後
- Transaction Rollback の結果
- WireMock が受信した request
- Surefire / Failsafe の結果
- JaCoCo の Line / Branch Coverage
- GitHub Actions の Job 成否

結果が環境や実行時刻によって変わる場合は、特定の値そのものではなく、どこを確認すべきかを説明する。

## 14. テストコードの完成度

各 Unit の 1 回目で生成するテストコードは、その Unit で学習する概念を理解するために必要な代表ケースを十分に含める。

コードリーディング中心の学習であるため、次のような教材にはしない。

- TODO のまま残したテスト
- ユーザーが埋めることを前提とした空のテストメソッド
- 練習問題用に意図的に削除した重要ケース
- 学習上必要な Assertion を意図的に省略したコード
- 後から実装しないと Unit の内容を十分理解できない状態

一方で、実際の本番システムに存在し得るすべてのケースを網羅することも目的としない。  
各 Unit の学習目標に対して、代表性と十分なバリエーションを持つケースを実装する。

## 15. 補助教材

基本的にはメインアプリのみで学習を完結させる。  
メインアプリへ不自然なコードを追加しないと説明できない内容についてのみ、補助教材を使用する。

補助教材を使用する場合は、該当 Unit の `examples/` などに配置し、メインアプリのコードと混同しない構成にする。

現時点では Unit 04 で、次のような内容の補助教材を使用する想定とする。

- Flaky Test
- 実行順依存のテスト
- Brittle Test
- 過剰な Mock
- `sleep` に依存するテスト
- static mutable state など Test Isolation を壊す例

補助教材は、悪い例を理解するための最小限のコード量とする。

## 16. Git 運用

Git 上の学習単位は、原則として `1 Unit = 1 feature branch` とする。

branch 名は Unit ディレクトリ名を利用し、次の形式とする。

```text
feature/01-automated-testing-junit-basics
feature/02-test-design-junit-practice
feature/03-test-doubles-mockito
feature/04-testability-test-quality
feature/05-spring-web-testing
feature/06-sql-repository-db-integration-testing
feature/07-spring-boot-integration-testing
feature/08-external-http-api-testing
feature/09-rest-api-testing
feature/10-test-management-coverage
feature/11-ci-automated-testing
```

Pull Request の記載形式、review、merge 方法などの具体的な GitHub 運用ルールは、この学習計画では規定しない。  
それらはユーザー側の運用に従う。

Unit 11 で扱う GitHub Actions や CI は Git / GitHub の運用ルールではなく、自動テスト学習の一部として `learning-curriculum.md` に従って扱う。

## 17. Git に含めるもの・含めないもの

学習教材そのものとなるソースコード、テストコード、設定、SQL、Fixture、Unit README、計画ドキュメント、GitHub Actions Workflow などは Git 管理対象とする。

一方、通常の開発プロジェクトで再生成可能な Build 成果物や一時ファイルは Git 管理対象外とする。  
例えば、Maven の `target/` 配下などは原則として `.gitignore` の対象とする。

テストツールや Coverage ツールなどが生成する成果物については、通常の Build 成果物と同様に扱うものを除き、必要性が明確になった場合に学習成果物として個別に判断する。  
このドキュメントでは、それらを一律に Git 管理対象とは定めない。

## 18. Markdown・文書表記ルール

Markdown ドキュメントは、これまでの学習リポジトリで採用してきた表記ルールと同じ基準で作成する。

### 改行

同一段落や連続した説明として改行したい場合は、行末に半角スペース 2 つを付けて Markdown の改行とする。  
段落そのものを分ける必要がある場合のみ空行を使用する。  
不要な空行を増やさない。

### 日本語と英単語

技術用語などの英単語と日本語の間には、原則として半角スペースを入れる。

例:

```text
JUnit を使用する。
Spring Boot の Test Slice を確認する。
```

ただし、コード、クラス名、メソッド名、パス、コマンドなどの表記を不自然に分割しない。

### コード・パス・コマンド

- class / method / annotation / path / command などは必要に応じて code span または code block を使用する。
- ファイル参照は、可能な限り相対 Markdown link を使用する。
- コマンドは実行可能な形の code block で記載する。

## 19. 成果物の受け渡し

生成物は、原則として実際のリポジトリのディレクトリ構成に合わせて配置した状態で ZIP 形式にまとめて連携する。

例えば、Unit の成果物が次のファイルである場合、ZIP 内でも同じ構成を保持する。

```text
src/test/java/.../OrderServiceTest.java
units/03-test-doubles-mockito/README.md
```

修正や追加が発生した場合は、変更・追加対象となるファイルのみを ZIP に含める。  
変更のない、すでに連携済みのファイルを再度含める必要はない。

単一のドキュメントのみを生成する場合は、単独ファイルまたは ZIP のどちらでもよい。

## 20. Unit 間の継続性

メインアプリとテストスイートは、Unit を進めるごとに継続して成長させる。  
Unit ごとに別のアプリケーションや別プロジェクトを作成し直さない。

例えば、Unit 01～02 で作成した Unit Test は後続 Unit でもそのまま残し、Unit 03 では Service の Test を追加し、Unit 05 では Controller Test、Unit 06 では Repository Test を追加していく。  
Unit 10 では、ここまで作成したテスト群全体を Test Suite として扱い、Unit 11 では同じテスト群を CI 上で自動実行する。

この継続性により、同じ Production Code に対してテスト境界がどのように広がるのかを比較しながら学習できる構成とする。

## 21. README とコードの役割分担

README とソースコードは、どちらか一方だけで学習内容を説明し切るのではなく、相互に補完する。

README は主に次を担当する。

- 用語・概念・考え方
- 技術やツールの役割
- テスト対象と境界
- コードを読む順序
- 実行方法
- 結果の読み方
- 実務上の考え方や注意点

ソースコードのコメントは主に次を担当する。

- その箇所の責務
- 実装意図
- 処理の意味
- Spring / Java / SQL の仕組み
- その Test Code が何を確認しているか
- Fixture / Mock / Stub / Assertion / Verification の意図

同じ内容が一部重複することは許容する。  
重複を避けるために重要な説明を省略することはしない。

## 22. 学習運用のまとめ

このリポジトリでは、1 つの Spring Boot 製注文管理 REST API と、Unit を進めるごとに蓄積されるテストスイートを中心に学習する。  
各 Unit では完成済みの Production Code と Test Code を読み、テスト対象、テスト境界、Fixture、Test Double、Assertion、DB、HTTP などの役割を理解し、実際に実行して結果を確認する。

全 Unit で説明の粒度と丁寧さを統一し、教材量のみをテーマに応じて増減させる。  
各 Unit は 2 回に分けて生成し、1 回目でプログラム・設定類を完成させ、2 回目で実装内容と一致した README を作成する。  
メインアプリとテストコードは通常の Maven プロジェクト構成に配置し、`units/` は学習ドキュメントと必要最小限の補助教材を管理する。

この運用により、コードリーディングを中心としながら、Java / Spring Boot における自動テストを一貫した教材と同じアプリケーションを通して体系的に学習できる状態を維持する。
