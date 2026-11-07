# 10. テスト実行管理と Coverage

## この Unit の目的

この Unit では、Unit 01〜09 で作成したテストを注文管理アプリケーションの一つの Test Suite として扱う。  
テストを追加してきた順序を振り返り、普段の変更で早く確認したい範囲と、依存環境を準備して確かめる全体回帰を Maven で選べるようにする。

次に、同じ Production Code を純粋な Unit Test と全体の Test Suite で計測し、どの行や分岐に到達したかを JaCoCo で比較する。  
**実行したテスト、通ったコード、Assertion が確認した仕様**の三つを区別して説明できることを目指す。

## 学習内容

### Test Suite を実行単位として扱う

Test Suite は、ある目的のためにまとめて扱うテストの集合である。  
このプロジェクトでは、割引や状態遷移を Java オブジェクトで確かめるテストから、HTTP 入口・業務 Transaction・DB までをつなぐテストまでを一つの Suite として維持する。  
Maven と JUnit の発見・選択を使うため、全クラスを列挙する JUnit の Suite クラスは作成していない。

例えば注文作成では、金額計算、入力値の検証、在庫の更新、送料 API の呼び出し、公開する Response に異なる不具合が起こり得る。  
金額の境界条件を細かく確かめるには計算クラスを直接呼ぶと、入力と期待値を比較しやすい。  
在庫減算の途中で失敗したときの Rollback を確かめるには、Spring の Transaction と実 PostgreSQL を通し、処理の途中と終了後に DB を観測する必要がある。  
HTTP の公開契約と保存結果の対応は、実際に起動した API を入口にして検証する。

| 分類             | テストが選ぶ境界                                                                                            | 結果を読むときの着眼点                                                                                        |
| ---------------- | ----------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| Unit Test        | Domain や Service を Java オブジェクトとして直接呼ぶ。必要に応じて Fake、Mock、Spy、固定 `Clock` を渡す。   | 計算、状態判定、依存への委譲などを細かい入力で確認できる。                                                    |
| Slice Test       | Spring Context を関心のある層に絞る。Unit 05 は MVC、Unit 06 は JDBC、Unit 08 は HTTP Client の構成を使う。 | Slice の種類によって、使う実依存が異なる。`@JdbcTest` は実 DB との Integration Test の性質も持つ。            |
| Integration Test | 実装や基盤を組み合わせ、接続部分を通す。Unit 07 は実 Service Proxy、Repository、PostgreSQL を組み合わせる。 | Spring の Transaction や実 SQL のように、個々の Java クラスを直接呼ぶだけでは観測できない振る舞いを確認する。 |
| API Test         | HTTP の公開入口からアプリケーションを操作する。Unit 09 は REST Assured から組み込み Web Server に接続する。 | HTTP Status・Header・JSON に加え、層をまたいだ保存結果や外部 Request の有無を確認する。                       |

学習上の分類は、入口・依存・観測範囲を説明するために使う。  
一方、Surefire と Failsafe への割り当ては、どの環境を用意してどの段階で実行するかという運用上の判断である。

- **Surefire** に Unit 01〜04 と Unit 05 を割り当てる。Unit 05 は Spring の MVC Slice を起動するが、MockMvc は HTTP ポートを使わず、Service は Mockito Bean で置き換える。
- **Failsafe** に Unit 06〜09 を割り当てる。Unit 06・07 は実 PostgreSQL を使う。Unit 08 は Docker が不要でも WireMock に実 HTTP を送る。Unit 09 は実 HTTP 入口と実 DB の両方を使う。

実行コストにはテスト本体の時間に加え、JVM、Spring Context、Docker Container、Schema、Fixture、HTTP Server の準備と終了が含まれる。  
同じ Context を再利用できるとクラスごとの起動コストを抑えられるが、DB 行データや WireMock の受信履歴はテストごとに分離する必要がある。  
所要時間を比較するときは、クラス単体か全体か、初回取得を含むか、キャッシュと Context の再利用があるかを合わせて確認する。

### Test Discovery と実行対象の選択

Test Discovery は、実行基盤が候補のクラスからテストとして実行できるクラスやメソッドを見つける処理である。  
このプロジェクトでは、まず Maven が `src/test/java` をコンパイルし、各 Plugin が出力されたクラスから候補を選ぶ。  
次に JUnit Platform と Jupiter が、`@Test`、`@ParameterizedTest`、`@RepeatedTest`、`@Nested` などに沿って実行するテストを構成する。

Surefire の既定のクラス名パターンには `Test*`、`*Test`、`*Tests`、`*TestCase` がある。  
Failsafe の既定は `IT*`、`*IT`、`*ITCase` である。  
既存の `OrderServiceTransactionIntegrationTest` も名前の末尾は `Test` であるため、Failsafe の既定では候補に入らない。
クラス名に `Integration` が含まれることや、`@SpringBootTest` を付けることだけで Maven の実行先が決まる仕組みではない。

今回の [POM](../../pom.xml) は、既存のクラス名を維持し、`study.integration.tests` のパターンで実行先を明示する。

| パターン                                                                                        | 選ぶ対象                                 |
| ----------------------------------------------------------------------------------------------- | ---------------------------------------- |
| `**/repository/jdbc/*Test.java`                                                                 | Unit 06 の Repository・制約・Transaction |
| `**/service/*IntegrationTest.java`                                                              | Unit 07 の Service と DB                 |
| `**/client/HttpShippingFeeClientTest.java` と `**/service/OrderServiceShippingFeeHttpTest.java` | package が異なる Unit 08 の 2 クラス     |
| `**/api/*ApiTest.java`                                                                          | Unit 09 の注文 API                       |

これらを Failsafe の `includes` と Surefire の `excludes` で共用する。
同じ集合を両方で管理することで、既定実行で片方への追加ともう片方からの除外がずれることを防ぐ。  
`service` には純粋な Unit Test、実 DB のテスト、実 HTTP のテストが共存するため、package とクラス名を組み合わせて選択している。
テストを追加するときは、接続する依存先を確認し、既定の命名・パターンのどちらに一致するかを見直す。

Surefire の `excludes` には内部クラス用の `**/*$*` も明示している。
`@Nested` のクラスは JUnit が外側のテストクラスから発見する。  
Fixture、`@TestConfiguration`、抽象クラスの Test Support は、テストに利用される補助コードとして読む。

### クラス指定、メソッド指定、Tag の違い

Filtering は、発見の候補を条件で絞る操作である。  
候補を選ぶ Plugin 側の条件と、テストの意味に基づいて選ぶ JUnit 側の条件を区別する。

| 指定                                              | 有効になる範囲                                      | 使い分け                                                                                                                                                     |
| ------------------------------------------------- | --------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `includes` / `excludes`                           | 設定先の Plugin                                     | チームで共有する既定のクラス選択を定める。                                                                                                                   |
| `-Dtest=クラス名` または `クラス名#メソッド名`    | Surefire                                            | 既定の include / exclude を上書きし、指定したクラス・メソッドを調べる。                                                                                      |
| `-Dit.test=クラス名` または `クラス名#メソッド名` | Failsafe                                            | Failsafe 側の既定の選択を上書きする。Lifecycle の `verify` で使う場合は、その前に Surefire も実行される。                                                    |
| `-Dgroups=unit` / `-DexcludedGroups=unit`         | 到達した両 Plugin の JUnit Platform に渡る Tag 条件 | クラス名を並べずに、意味で選ぶ。`test` で `groups=unit` を使うと純粋な Unit Test に、`excludedGroups=unit` を使うと現在の既定集合では MVC Slice に絞られる。 |
| `-Pcoverage`                                      | Maven の `coverage` profile                         | 計測用の Agent、出力先、Report を有効にする。テスト境界による既定の振り分けは通常の Plugin 設定を使う。                                                      |

`@Tag("unit")` は純粋な Unit Test の外側の 10 クラスに付ける。  
例えば `OrderPriceCalculatorParameterizedTest` では、クラスの Tag で選択すると内部の割引境界ケースも実行対象に入る。  
Tag は選択のための属性であり、Spring Context や DB の起動方法は各テストのコードが決める。

`-Dtest` で Failsafe に割り当てたクラスも Surefire に明示できる。
既存 Unit の個別実行との互換性に使えるが、対象クラス本来の Docker・HTTP 要件が適用される。  
同じ指定を `verify` に付けると、Surefire で先に実行され、後で Failsafe の既定対象として再度実行され得る。  
`-Dgroups=unit` を `verify` に渡すと Failsafe 側にも同じ条件が届き、現在の Failsafe 対象はすべて選択から外れる。  
目的に合った phase と指定の組み合わせを使う。

### Maven Build Lifecycle と phase / goal

Lifecycle は、ビルド作業の順序を表す。  
`test` や `verify` はその途中にある **phase** で、`surefire:test` や `failsafe:verify` は Plugin が提供する具体的な処理である **goal** である。  
phase を指定すると、同じ Lifecycle の先頭から指定した段階まで進む。
例えば `./mvnw verify` では、先行する `test` と `package` も経由する。  
`clean` は出力を削除する別の Lifecycle である。`clean verify` では、前回の出力を取り除く処理と、新しいビルドの検証を順に行う。

| phase                   | このプロジェクトでの動作                                                                                | 結果の確認                                                                              |
| ----------------------- | ------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| `test-compile`          | Test Code と Fixture を含むテスト用クラスをコンパイルする。                                             | コンパイル・依存解決の確認に使う。Test Method の結果はこの段階では得られない。          |
| `test`                  | `surefire:test` が既定の速いグループを実行する。                                                        | Surefire の Text / XML に結果が出る。テスト失敗時は通常ここでビルドが止まる。           |
| `package`               | JAR を組み立てる。                                                                                      | 続く Failsafe は親 POM の設定でコンパイル済みの Production Class を参照する。           |
| `pre-integration-test`  | 統合テストの準備用の段階。計測時は Failsafe 用 Agent の引数を設定する。                                 | テストの Server や DB は既存のテスト起動処理が所有する。                                |
| `integration-test`      | `failsafe:integration-test` が統合グループを実行する。                                                  | クラス別の Text / XML と `failsafe-summary.xml` に記録する。                            |
| `post-integration-test` | Maven 側で統合テストの後処理を結び付けられる段階。                                                      | 今回の Fixture、Server、Container の後始末は JUnit、Spring、Testcontainers が管理する。 |
| `verify`                | `failsafe:verify` が Failsafe の結果を判定する。計測時はその成功後に JaCoCo が集計・Report を生成する。 | Maven の終了結果と、それに対応する各 Plugin の出力を読む。                              |

Failsafe は通常のテスト失敗を結果に記録し、`post-integration-test` を経由してから `verify` でビルドの成否に反映する。
例えば `integration-test` phase だけで止めると、後続 phase の後処理や結果判定まで進まない。  
統合テストは `verify` を入口にする。  
Plugin 設定の不備、対象クラスの未発見、JVM の異常終了など、通常の Assertion Failure とは異なる失敗もあるため、最後に到達した goal と最初の原因を確認する。

Spring Boot の親 POM は、Failsafe のバージョン、`integration-test` と `verify` の execution、Production Class の参照先を `pluginManagement` に持つ。
子 POM の `build/plugins` で Plugin を宣言することで、その設定をこのプロジェクトのビルドに適用する。  
`coverage` profile 内の Surefire / Failsafe 設定は同じ Plugin に JVM 引数などを追加する。

### Test Report から 0 件・未実行・重複を見分ける

コンソールでは Plugin と実行クラスを追い、`Tests run`、`Failures`、`Errors`、`Skipped` を確認する。  
クラス名、ケース名、失敗内容を追うときは `TEST-*.xml` とクラス別の `.txt` を使う。  
Failsafe の `failsafe-summary.xml` は Plugin の集計・判定に用いる結果であり、個々の Assertion の詳細はクラス別 Report で読む。

- **0 件**は全体の集計で判断する。今回の POM は両 Plugin に `failIfNoTests` と `failIfNoSpecifiedTests` を設定する。`@Nested` の外側のクラスのログに単独の `Tests run: 0` が出ても、内側のケースが実行されていれば Plugin 全体の 0 件とは異なる。
- **未実行**は、対象として考えたクラスの集合と今回生成されたクラス別 XML を比較する。件数は Parameterized / Repeated Test の各実行を含むため、クラス数やメソッド数と分けて数える。
- **重複**は、同じ完全修飾クラス名の Report が同じ実行で Surefire と Failsafe の両方に存在するかで調べる。合計件数だけでは、クラスの漏れと重複が相殺される場合がある。
- **前回の結果**は、同じ出力先で部分実行をすると残る場合がある。新しい出力先で集めるか、ログと生成時刻を照合し、当回のクラス集合を特定する。

### Coverage を取得する仕組み

Code Coverage は、対象コードのうちテストの実行が到達した範囲を、行や分岐などの単位で表す。  
JaCoCo は Java Agent をテスト JVM に適用し、実行時にクラスへ挿入した観測点から実行データを得る。
`.exec` は観測点の実行情報である。  
Report はその情報を同じビルドの Production Class と突き合わせ、ソース行や分岐に対応付ける。  
HTML は人がソースをたどるため、XML はカウンター・セッション・行別データを比較するために使う。  
Check は Report の見た目を読む処理ではなく、実行データと Production Class から設定された基準を判定する goal である。

`coverage` profile では、Surefire と Failsafe で異なる Agent 用プロパティを設定する。  
`@{jacoco.surefire.argLine}` / `@{jacoco.failsafe.argLine}` は fork する JVM を起動する直前に展開される。
Maven が goal の実行時に準備した引数を渡しつつ、利用者の `-DargLine` に相当する `${argLine}` も連結する。  
計測時は各 Plugin が 1 fork を再利用し、実行データは JVM の終了時に別々のファイルへ書き出す。
この構成で fork を無効にすると、`argLine` からテスト JVM へ Agent を渡せなくなる。  
Unit 09 の Web Server は Failsafe のテスト JVM 内で動くため、同じ Agent で HTTP 入口側の Java コードも計測できる。

| 順序                            | goal / execution                                          | データの流れ                                                                      |
| ------------------------------- | --------------------------------------------------------- | --------------------------------------------------------------------------------- |
| `initialize`                    | `prepare-agent` / `prepare-surefire-coverage`             | Surefire 用の引数を準備し、テスト JVM の終了時に `jacoco/surefire.exec` を得る。  |
| `test` の Surefire 終了後       | `report` / `report-surefire-coverage`                     | Surefire の実行データから `site/jacoco-surefire/` に HTML / XML を生成する。      |
| `pre-integration-test`          | `prepare-agent-integration` / `prepare-failsafe-coverage` | Failsafe 用の引数を準備し、テスト JVM の終了時に `jacoco/failsafe.exec` を得る。  |
| `verify` の Failsafe 成功判定後 | `merge` / `merge-suite-coverage`                          | 二つのファイルを読み、通過した観測点を合わせて `jacoco/suite.exec` を作る。       |
| 同じ `verify` の merge 後       | `report` / `report-suite-coverage`                        | 集計済みのデータから `site/jacoco-suite/` に HTML / XML を生成する。              |
| 全体計測後の明示実行            | `check` / `check-suite-coverage`                          | 同じ `suite.exec` と Production Class に対して Line / Branch の最低値を判定する。 |

表の出力先はビルド出力ディレクトリからの相対パスである。  
二つのグループが同じ行を通っても、その行を二重に数えない。
個々の Coverage 率を足したり平均したりする代わりに、同じ Production Class に対する観測点を統合して比率を算出する。

`test` の Report に含まれる実行は Surefire で当回に選んだテストで決まる。
`-Dgroups=unit` なら純粋な Unit Test のみで、フィルターなしの `test` なら MVC Slice も含む。  
`-Dit.test` 付きの `verify` でも集計ファイルを生成できるが、対象はその部分実行である。  
**全体 Coverage はフィルターや skip を付けない `clean verify` で取り直し、両方の Test Report と `.exec` がそろっていることを確認する。**  
`append=false` は稼働した JVM の出力を上書きする設定である。実行を省略したグループの古いファイルを除くには、専用出力先での `clean` が必要になる。

### Line、Branch、Method、Class の読み分け

| 指標   | 数える対象                                                   | 読むときの注意点                                                                             |
| ------ | ------------------------------------------------------------ | -------------------------------------------------------------------------------------------- |
| Line   | 実行可能なソース行のうち、対応する命令が一つ以上実行された行 | 一つの行に複数の命令や条件があると、到達済みの行にも未実行部分が残る。                       |
| Branch | `if` や `switch` に対応する分岐のうち、実行された分岐        | 短絡評価でも未通過の分岐が生じる。例外処理自体は JaCoCo の Branch カウンターの対象外である。 |
| Method | 命令が一つ以上実行されたメソッド                             | Constructor や初期化処理も含む。メソッド内部のすべての経路を通ったかは、行や分岐で別に読む。 |
| Class  | メソッドが一つ以上実行されたクラス                           | Context 構築で Constructor が動くだけでも、実行済みのクラスに数えられる場合がある。          |

HTML のソース画面では、背景色と分岐のひし形を別々に読む。  
背景色の赤・黄・緑は行に対応する命令の未実行・一部実行・全実行を示す。  
ひし形の赤・黄・緑はその行の分岐の未通過・一部通過・全通過を示す。  
XML のソース行では、`mi` / `ci` が未実行 / 実行済みの命令数、`mb` / `cb` が未実行 / 実行済みの分岐数である。

`OrderPriceCalculator.calculate(...)` の `items == null || items.isEmpty()` では、非空、空、`null` を別の入力として考える。
空の List を使う既存テストは例外行に到達するが、`null` で短絡して右側の条件を評価しない経路には別の入力が要る。  
また、List の要素に `null` が混ざる場合は、List 自体が `null` の場合と異なる Guard の対象である。  
未実行分岐を見つけたら、どの呼び出し元で起こり得る入力か、公開仕様としてどの例外や結果を確かめる価値があるかを考える。

### Coverage と Assertion の役割

`calculateDiscount(...)` の境界で 9,999 円、10,000 円、10,001 円をすべて与えると、割引あり・なしの分岐に到達できる。  
戻り値が存在することだけを確認しても、10,000 円の割引額が 1,000 円であることは保証できない。  
既存テストは各入力に対して金額を独立した期待値で比較するため、境界の 1 円のずれを検出できる。

同じ区別は広いテストにも当てはまる。
例えば Unit 09 の注文作成で HTTP 201 だけを検証した場合、保存された明細や在庫が誤っていても、Status が変わらなければ通る。  
実際の `OrderCreateApiTest` は Response の金額や明細、`Location` からの再取得、DB 行、在庫、送料 Request をそれぞれ観測する。  
Coverage を見た後に、**通った処理のどの結果を Assertion が守っているか**までテストへ戻って読む。

### Coverage Threshold と Check

Coverage Threshold は、ある計測範囲に対して要求する下限である。  
このプロジェクトの Check は Production Class 全体を一つの `BUNDLE` として扱い、`LINE` と `BRANCH` の `COVEREDRATIO` を判定する。
Method / Class は Report で観察する指標である。  
Check の入力は全体用の `suite.exec` で、Report と同じコンパイル済みクラスを参照する。

下限は `coverage.minimum.line` と `coverage.minimum.branch` で実行時に渡す。
値は `0.89` のように 0 から 1 までの比率で指定する。  
運用上の固定閾値は設定しておらず、通常の `verify` ではテスト結果を判定し、Coverage profile の `verify` では計測結果を生成する。  
Check を分けると、同じ `.exec` に対して基準だけを変更し、テスト失敗と閾値未達の違いを観察できる。

固定閾値を検討する場合は、まず同じ対象範囲・実行条件で基準を計測する。
次に、割合の変化を監視する目的と、欠けている業務上重要な確認を分けて判断する。  
例えば全体の Line が高くても、注文確定の競合や在庫 Rollback が期待どおりかは、対応するテストと Assertion が必要になる。  
集計の分母やフィルターを変えたときは、基準値と一緒に比較条件も見直す。

### Parallel Execution、Fail Fast、Retry

| 概念               | 目的と注意点                                                                                                                                                | 今回の位置づけ                                                                                                                                                                                           |
| ------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Parallel Execution | 独立したテストを同時に進め、待ち時間を減らす。JUnit 内のスレッド並列と、Maven Plugin が複数 JVM を fork する実行は別に管理する。                            | 並列化を常設していない。Unit 07・09 の DB 行データ、Unit 08・09 の WireMock の Stub と履歴は、準備・観測・Cleanup の順を保つ必要がある。Unit 09 には `SAME_THREAD` と共有資源の `@ResourceLock` もある。 |
| Fail Fast          | 失敗を早く返すため、一定数の失敗後に残りの実行を止める。Surefire / Failsafe の `skipAfterFailureCount` が該当し、並行実行中はすでに始まった処理も考慮する。 | Unit 10 では常設しない。ビルドがある phase の失敗で止まることと、同一グループの残りを打ち切る設定は別の判断である。                                                                                      |
| Retry              | 失敗したケースを再実行して、失敗が再現するか調べる。`rerunFailingTestsCount` による再試行で通った場合は、最初の失敗と Flaky の記録を確認する。              | 自動 Retry は常設しない。既存の `@RepeatedTest` は成功・失敗にかかわらず予定回数を実行し、各回の Fixture の独立性を確認する。                                                                            |

Flaky Test は、同じ変更・意図した同じ条件で成功したり失敗したりするテストである。  
再試行で成功したときは、元の失敗を残し、実行順、時刻、共有 Fixture、Port、起動待ち、外部依存などの条件を切り分ける。  
Unit 04 の固定 `Clock` や、Unit 08 の動的ポートとリセットは、変動する入力や状態をテストの設計で制御する例である。 [Unit 04 の教材](../04-testability-test-quality/README.md)

### Mutation Testing と PIT

Mutation Testing は、実装に小さな変化を加えたとき、テストがその違いを検出できるかを調べる方法である。
PIT は Java のバイトコードに変異を加え、関連テストを実行して変異を検出したかを報告するツールである。  
例えば `subtotal.compareTo(DISCOUNT_THRESHOLD) < 0` が `<= 0` 相当になれば、10,000 円だけの結果が変わる。  
既存の `calculateDiscount_returnsExpectedAmountAroundThreshold(...)` が 10,000 円の割引額を直接比較することには、この差を検出する意味がある。

変異が生き残った場合は、関連する入力の欠落、弱い Assertion、外部から観測できる振る舞いを変えない変異などを区別する。  
Coverage は到達した範囲、Mutation Testing は試した変異をテストが識別できたかという別の情報を返す。  
本 Unit ではこの違いを既存のコードで読み、PIT の Plugin 設定と変異実行は扱わない。

### Test Strategy、Test Plan、Test Pyramid

**Test Strategy** は、どのリスクをどのテスト境界で扱うかを決める方針である。  
注文作成では、計算境界と例外の組み合わせを Unit Test で、入力契約を MVC Slice で、保存と Rollback を実 DB で、公開 Response と保存結果の接続を API Test で見る。

**Test Plan** は、方針を具体的な実行手順、必要な環境、確認する成果物と判定条件に落としたものである。  
例えば通常の編集時には `test` で速く結果を得て、送料 Client の変更では Unit 08 を選択し、変更全体の確認には Docker を用意して `verify` と両 Plugin の Report を確認する。  
Coverage が必要な場合は、同じ全体を計測用に実行して出力条件を記録する。

**Test Pyramid** は、細かく速い検証を土台に、実環境に近い接続の検証を上に重ねる考え方である。  
テスト数に固定比率を当てはめるためのものではなく、失敗を切り分けやすい層に多くの条件を置き、広い層では接続の違いでしか検出できないリスクを確認する。  
この Unit では Unit 06 の Parameterized Case 数と Unit 09 の API 件数だけで配分を評価せず、起動コスト、条件の粒度、保証する振る舞いで判断する。

## 使用する技術・ツール

| 技術・ツール                                | この Unit での役割                                                                                          |
| ------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| Java 21、Maven Wrapper 3.3.4 / Maven 3.9.16 | JDK を用意し、Wrapper でプロジェクトが指定する Maven を使う。コンパイル、依存解決、Lifecycle を共通化する。 |
| Spring Boot 4.1.1 の親 POM                  | Surefire / Failsafe 3.5.6 と JUnit Jupiter 6.0.3 などを管理し、Failsafe の既定の execution を提供する。     |
| JUnit Jupiter / JUnit Platform              | テストメソッドとケースを発見・実行する。`@Tag` によって純粋な Unit Test を選択する。                        |
| Maven Surefire / Failsafe                   | テスト候補の振り分け、JVM での実行、クラス別の Test Report とビルドの成否を担当する。                       |
| JaCoCo Maven Plugin 0.8.16                  | Agent の適用、実行データの集計、HTML / XML、明示的な Coverage Check を担当する。                            |
| Spring Test、MockMvc、Mockito               | 既存の Slice、実 Context、Java Object の Test Double を用意する。                                           |
| Testcontainers 2.0.5、PostgreSQL 18.6       | 既存の DB テストに、通常環境と分離した一時 DB を提供する。                                                  |
| WireMock 3.13.2、REST Assured 6.3.0         | テスト JVM 内の送料 API Stub と、Unit 09 の実 HTTP 入口を扱う。                                             |

## テスト対象とテスト境界

今回の実行対象は既存の Unit 01〜09 の Test Suite である。
個々の SUT、Fixture、Assertion、依存先の置き換え方は、各 Unit のテストコードで確認する。  
以下の package は `com.example.orderapi` からの相対表記である。

| Unit・主な SUT                                   | 実装と置き換える依存                                                                                                     | Spring Context                         | Docker / PostgreSQL | WireMock・実 HTTP                  | 既定の実行先 |
| ------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------ | -------------------------------------- | ------------------- | ---------------------------------- | ------------ |
| 01〜04 Domain、価格計算、Service                 | Java オブジェクトを直接生成する。Service の依存先は Fake / Mock / Spy、時刻は必要に応じて固定する。                      | 不要                                   | 不要                | 不要                               | Surefire     |
| 05 `OrderController`、Validation、例外変換       | MVC を MockMvc から呼び、`OrderService` を Mockito Bean で置き換える。                                                   | Web MVC Slice                          | 不要                | 不要                               | Surefire     |
| 06 JDBC Repository、DB 制約、Test Transaction    | 実 SQL と実 PostgreSQL を使う。`@Sql` とテスト Transaction により前提と後始末を分離する。                                | JDBC Slice                             | 必要                | 不要                               | Failsafe     |
| 07 `OrderService` の業務 Transaction             | 実 Service Proxy・JDBC・DB を通し、送料 Client を Mock にする。途中失敗のケースでは実 Repository の Spy で失敗点を作る。 | Boot、Web Environment は `NONE`        | 必要                | 不要                               | Failsafe     |
| 08 `HttpShippingFeeClient` と Service の例外変換 | 実 `RestClient` を WireMock へ接続する。Service を組み立てるケースは Repository を Mock にする。                         | HTTP Client Slice                      | 不要                | 送料向けの loopback HTTP           | Failsafe     |
| 09 注文 API                                      | REST Assured から実アプリケーションを呼ぶ。実 Service・Repository・DB を通し、送料 API の相手を WireMock にする。        | Boot、Web Environment は `RANDOM_PORT` | 必要                | API 入口と送料向けの loopback HTTP | Failsafe     |

### 実行クラスの構成

- Unit 01〜04 の純粋な Unit Test は `domain` の `OrderTest`、`OrderItemQuantityTest`、`OrderStatusTransitionTest`、`OrderTestFixtureTest` と、`service` の `OrderPriceCalculatorTest`、`OrderPriceCalculatorParameterizedTest`、`OrderServiceMockitoTest`、`OrderServiceFakeAndSpyTest`、`OrderServiceTestabilityTest`、`ProductServiceTestabilityTest` である。
- Unit 05 は `controller` の `OrderControllerWebMvcTest`、`OrderControllerValidationWebMvcTest`、`OrderControllerSearchWebMvcTest`、`OrderControllerExceptionHandlerWebMvcTest` である。
- Unit 06 は `repository.jdbc` の `JdbcProductRepositoryTest`、`JdbcOrderRepositoryTest`、`DatabaseConstraintTest`、`JdbcTransactionTest` である。
- Unit 07 は `service` の `OrderServiceCreateIntegrationTest`、`OrderServiceLifecycleIntegrationTest`、`OrderServiceTransactionIntegrationTest` である。
- Unit 08 は `client.HttpShippingFeeClientTest` と `service.OrderServiceShippingFeeHttpTest` である。
- Unit 09 は `api` の `OrderCreateApiTest`、`OrderQueryApiTest`、`OrderLifecycleApiTest`、`OrderErrorApiTest` である。

Coverage の分析対象は、同じ Maven プロジェクトでコンパイルした Production Class である。
テストクラス、依存ライブラリ、PostgreSQL や WireMock の処理をレポートの分母に加える設定はない。  
例えば SQL を実行する Java メソッドに到達しても、Coverage から SQL の全条件や保存結果の正しさを読み取ることはできない。  
それらは Unit 06・07・09 の実 DB に対する Assertion で確認する。

## 使用するファイル

### この Unit で追加・変更する主なファイル

- [pom.xml](../../pom.xml) に、既定のテスト選択、Failsafe の有効化、Coverage 用 profile、計測と判定の設定がある。
- [domain のテスト](../../src/test/java/com/example/orderapi/domain) と [service のテスト](../../src/test/java/com/example/orderapi/service) のうち、上記の純粋な Unit Test に `@Tag("unit")` を付けている。

### コードリーディングで参照する主なファイル

| ファイル                                                                                                                                                                                                                                                                                                                                                                                                                                                                        | 確認すること                                                             |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| [Maven Wrapper 設定](../../.mvn/wrapper/maven-wrapper.properties)、[mvnw](../../mvnw)、[mvnw.cmd](../../mvnw.cmd)、[.gitignore](../../.gitignore)                                                                                                                                                                                                                                                                                                                               | Maven の配布版と起動方法、再生成可能な出力先                             |
| [OrderPriceCalculator.java](../../src/main/java/com/example/orderapi/service/OrderPriceCalculator.java)、[OrderPriceCalculatorTest.java](../../src/test/java/com/example/orderapi/service/OrderPriceCalculatorTest.java)、[OrderPriceCalculatorParameterizedTest.java](../../src/test/java/com/example/orderapi/service/OrderPriceCalculatorParameterizedTest.java)                                                                                                             | 入力 Guard、割引境界、`@Tag`、金額の独立した期待値                       |
| [OrderControllerValidationWebMvcTest.java](../../src/test/java/com/example/orderapi/controller/OrderControllerValidationWebMvcTest.java)、[ProductController.java](../../src/main/java/com/example/orderapi/controller/ProductController.java)                                                                                                                                                                                                                                  | MockMvc の入力検証と、起動された Bean のメソッドが実際に呼ばれたかの違い |
| [JdbcTransactionTest.java](../../src/test/java/com/example/orderapi/repository/jdbc/JdbcTransactionTest.java)、[PostgresJdbcTestConfiguration.java](../../src/test/java/com/example/orderapi/support/PostgresJdbcTestConfiguration.java)、[Unit 06 の SQL](../../src/test/resources/sql/unit06)                                                                                                                                                                                 | テスト Transaction と専用 PostgreSQL                                     |
| [OrderService.java](../../src/main/java/com/example/orderapi/service/OrderService.java)、[OrderServiceTransactionIntegrationTest.java](../../src/test/java/com/example/orderapi/service/OrderServiceTransactionIntegrationTest.java)、[PostgresServiceIntegrationTestConfiguration.java](../../src/test/java/com/example/orderapi/support/PostgresServiceIntegrationTestConfiguration.java)、[Unit 07 の SQL](../../src/test/resources/sql/unit07)                              | 業務 Transaction と途中失敗の観測                                        |
| [HttpShippingFeeClient.java](../../src/main/java/com/example/orderapi/client/HttpShippingFeeClient.java)、[HttpShippingFeeClientTest.java](../../src/test/java/com/example/orderapi/client/HttpShippingFeeClientTest.java)、[OrderServiceShippingFeeHttpTest.java](../../src/test/java/com/example/orderapi/service/OrderServiceShippingFeeHttpTest.java)                                                                                                                       | 実 Client、WireMock と Java 側の Test Double の境界                      |
| [OrderCreateApiTest.java](../../src/test/java/com/example/orderapi/api/OrderCreateApiTest.java)、[OrderErrorApiTest.java](../../src/test/java/com/example/orderapi/api/OrderErrorApiTest.java)、[OrderApiTestSupport.java](../../src/test/java/com/example/orderapi/support/OrderApiTestSupport.java)、[OrderApiTestConfiguration.java](../../src/test/java/com/example/orderapi/support/OrderApiTestConfiguration.java)、[Unit 09 の SQL](../../src/test/resources/sql/unit09) | 実 HTTP の入口と外向き HTTP、Commit 後の DB、Cleanup                     |
| [schema.sql](../../src/main/resources/schema.sql)、[data.sql](../../src/main/resources/data.sql)、[application.yml](../../src/main/resources/application.yml)、[compose.yaml](../../compose.yaml)                                                                                                                                                                                                                                                                               | 共通 Schema と通常用のデータ・接続設定の分離                             |

## 前提

JUnit の基本、Parameterized / Repeated Test、Fixture、Mockito の Test Double、Spring の Slice と Integration Test を既習とする。  
DB・HTTP テストの詳細は [Unit 06](../06-sql-repository-db-integration-testing/README.md)、[Unit 07](../07-spring-boot-integration-testing/README.md)、[Unit 08](../08-external-http-api-testing/README.md)、[Unit 09](../09-rest-api-testing/README.md) で確認できる。  
アプリケーションの業務ルールと通常用・テスト用の環境は [サンプルアプリケーション仕様](../../docs/planning/sample-application-spec.md) を参照する。

- Java 21 の JDK を利用し、リポジトリルートから Maven Wrapper を実行する。コマンド例は Windows 上の Git Bash を想定する。PowerShell では `./mvnw` に対応する `.\mvnw.cmd` を使う。
- 通常の `test` と純粋な Unit Test の計測は、Docker Engine や HTTP Server を起動せずに実行できる。
- Unit 08 の部分実行は JVM 内で WireMock を起動し、loopback HTTP を利用する。Unit 06・07・09 を含む実行では、利用可能な Docker Engine、一時 DB と Schema / Fixture を扱える環境が必要になる。
- Unit 09 の Web Server と WireMock はテストが起動する。通常用 Compose、永続 DB、Volume、実外部サービスとは分離されている。通常用アプリケーションを別途起動する手順は不要である。

## コードリーディング

### 1. POM で既定の実行対象と phase を追う

[pom.xml](../../pom.xml) の `parent` と `study.integration.tests` を起点に、`build/plugins` の Surefire / Failsafe を読む。
同じプロパティが Surefire では除外、Failsafe では選択に使われている箇所を確認する。  
例えば `service/OrderServiceTransactionIntegrationTest.java` と `service/OrderServiceMockitoTest.java` を並べ、package が同じでも実行先が異なる理由を説明する。

親 POM から継承された goal と子 POM の選択条件を区別する。  
実効 POM を見る場合は、`pluginManagement` の定義に加え、実際に `build/plugins` に入った `maven-failsafe-plugin` とその execution を確認する。

### 2. Tag と割引の Assertion を読む

最初に [OrderPriceCalculator.java](../../src/main/java/com/example/orderapi/service/OrderPriceCalculator.java) の `calculate(...)` と `calculateDiscount(...)` を読む。  
入力チェック、商品小計、割引境界、合計の計算を分ける。

次に [OrderPriceCalculatorTest.java](../../src/test/java/com/example/orderapi/service/OrderPriceCalculatorTest.java) の `calculate_throwsIllegalArgumentExceptionWhenItemsAreEmpty()` で、どの入力が複合条件の右側まで進むかを確認する。  
[OrderPriceCalculatorParameterizedTest.java](../../src/test/java/com/example/orderapi/service/OrderPriceCalculatorParameterizedTest.java) では、外側の `@Tag("unit")` から `@Nested` 内の `calculateDiscount_returnsExpectedAmountAroundThreshold(...)` へ進む。  
`@CsvSource` の各行と `isEqualByComparingTo(...)` を対応させ、どの期待値が境界の不具合を検出するかを読む。  
クラスの選択と、各ケースが検証する金額の意味は別々に説明できる。

### 3. HTTP を使わない MVC と DB テストを比較する

[OrderControllerValidationWebMvcTest.java](../../src/test/java/com/example/orderapi/controller/OrderControllerValidationWebMvcTest.java) の `create_whenBeanValidationFails_returnsFieldError(...)` で、MockMvc の入力から Error Response と Service Mock の呼び出し有無までを追う。  
同じ Surefire に入る価格計算と比較すると、Spring Context の有無と HTTP ポートの有無を分けて説明できる。

[JdbcTransactionTest.java](../../src/test/java/com/example/orderapi/repository/jdbc/JdbcTransactionTest.java) の `dataSource_connectsToTestContainerInsteadOfApplicationDatabase()` と `rollback_removesChangesAndTransactionalFixture()` を読む。
`@JdbcTest` でも DB は実 PostgreSQL であり、接続先とテスト Transaction の Rollback を観測している。  
[PostgresJdbcTestConfiguration.java](../../src/test/java/com/example/orderapi/support/PostgresJdbcTestConfiguration.java) の `@ServiceConnection` と Schema 初期化を確認する。

### 4. Rollback を実際の観測点で読む

[OrderServiceTransactionIntegrationTest.java](../../src/test/java/com/example/orderapi/service/OrderServiceTransactionIntegrationTest.java) の `createOrder_secondStockUpdateFails_rollsBackAllEarlierWrites()` を読む。
実 Service が処理を進め、後続商品の減算時に Spy が例外を送出するケースである。  
失敗直前の観測で注文、明細、先行商品の減算が実際にあったことを確かめ、例外後には Cleanup 前の DB が事前状態と同じことを確認する。

Service や Repository の Coverage に実行済みの行が増えても、どの更新が Rollback されたかは Report だけから判定できない。  
例外の同一性、Transaction の状態、失敗前後の DB Snapshot を比較する Assertion が保証の根拠となる。

### 5. Unit 08 と Unit 09 の実 HTTP を区別する

[HttpShippingFeeClientTest.java](../../src/test/java/com/example/orderapi/client/HttpShippingFeeClientTest.java) の `calculateShippingFee_validResponse_returnsFeeAndSendsExpectedRequest()` を読む。
実 Client が送った入力と、応答から受け取った送料を確認する。  
[OrderServiceShippingFeeHttpTest.java](../../src/test/java/com/example/orderapi/service/OrderServiceShippingFeeHttpTest.java) の `createOrder_httpServerError_preservesCauseAndDoesNotWriteRepositories()` では、HTTP 障害の Service での扱いと Repository Mock の確認へ進む。

[OrderApiTestSupport.java](../../src/test/java/com/example/orderapi/support/OrderApiTestSupport.java) の `RANDOM_PORT`、`@Sql`、`prepareIsolatedApiBoundary()` を読み、Unit 09 で追加される HTTP 入口と DB を確認する。  
次に [OrderCreateApiTest.java](../../src/test/java/com/example/orderapi/api/OrderCreateApiTest.java) の `create_multipleItems_commitsAndCanBeReadFromLocation()`、[OrderErrorApiTest.java](../../src/test/java/com/example/orderapi/api/OrderErrorApiTest.java) の `create_shippingFailure_returns502AndPreservesDatabase(...)` を読む。  
公開 Response、実 Request、Commit 後の DB、外向き Request のどれを検証しているかを区別する。

### 6. Agent から Report と Check までを POM で追う

`coverage` profile を開き、`study.build.directory`、両 Plugin の `argLine`、JaCoCo の executions の順に読む。  
二つの Agent が書くファイル名と、`report-surefire-coverage`、`merge-suite-coverage`、`report-suite-coverage` の入出力を一つずつ対応させる。  
最後に `check-suite-coverage` の `phase` が `none` であること、`dataFile`、`BUNDLE`、二つの `minimum` を確認し、Report 生成と Check の実行条件を説明する。

## 実行・確認

### 1. JDK、Wrapper、取得状態を確認する

以下のコマンドはリポジトリルートから実行する。  
初回は Wrapper が Maven の配布物を取得する場合がある。  
Maven は、使用する phase と profile に応じて依存ライブラリと Plugin を解決する。
`verify` で初めて使う Failsafe や、`coverage` profile で初めて使う JaCoCo も取得対象になる。

```bash
./mvnw --version
```

Maven が 3.9.16、Java が 21 系であることを確認する。  
配布版・依存・Plugin がすべて取得済みなら Maven に `-o` を付けて実行できる。  
`-o` は Maven のリポジトリへの接続を制御する指定である。
Wrapper の配布版が未取得の場合や、Docker Image が未取得の場合の通信、テスト本体の loopback HTTP や JDBC は、それぞれ別に考える。

Docker が必要な実行の前には、利用者が Engine を利用可能にしておく。
Testcontainers は PostgreSQL と、環境に応じて後始末用の補助 Container を準備する。  
必要な Image が手元にない場合は Docker 側で取得が発生し得る。

```bash
docker info
```

初回にコンパイルと依存解決を切り分けたい場合は、次の phase で確認する。  
コンパイルの成功は、Test Method や API Test の成功と区別する。

```bash
./mvnw test-compile
```

### 2. 速いグループを実行し、Tag と個別指定を試す

```bash
./mvnw test
```

純粋な Unit Test と Web MVC Slice が Surefire で実行される。  
現行のクラス構成では、DB を使う Unit 06・07・09 と、実 HTTP を使う Unit 08・09 はこの既定実行から外れる。

次のコマンドはそれぞれ独立した絞り込み例である。
Tag を使う場合は、クラス名を指定せず純粋な Unit Test の集合を選べる。  
メソッド指定の `#` や複数クラスの区切りが Shell に解釈されないよう、`-D` 引数全体を引用符で囲む。

```bash
./mvnw -Dgroups=unit test
./mvnw "-Dtest=OrderTest,OrderPriceCalculatorTest" test
./mvnw "-Dtest=OrderPriceCalculatorTest#calculate_appliesDiscountWhenSubtotalReachesThreshold" test
```

初めのコマンドでは、`OrderPriceCalculatorParameterizedTest` の割引境界を含む純粋な Unit Test を確認する。
後の二つでは、`TEST-*.xml` のクラス名や `testcase` の名前を指定と照合する。  
同じディレクトリへ続けて出力すると前回の Report が残り得るため、部分実行の Report を比較するときは、用途ごとに新しいビルド出力先を指定する。

```bash
./mvnw "-Dstudy.build.directory=target/unit10-local-selection" "-Dtest=OrderTest,OrderPriceCalculatorTest" test
```

### 3. 実 HTTP の部分実行と既存コマンドの意味を確認する

Docker なしで Unit 08 の実 HTTP を学ぶ場合は、Failsafe に渡すクラスを二つに絞る。

```bash
./mvnw "-Dit.test=HttpShippingFeeClientTest,OrderServiceShippingFeeHttpTest" verify
```

この実行では、先に Surefire の既定グループを実行し、その後 Failsafe で Unit 08 の二つのクラスを実行する。  
WireMock と loopback HTTP が必要で、Unit 06・07・09 の DB・Web Server は起動しない。

過去 Unit の個別コマンドも利用できる。
例えば Unit 09 の次のコマンドは Surefire に明示的な対象を渡し、既定の除外を上書きする。  
メソッドは `@RepeatedTest` のため、一つのメソッド指定でも二つのケースを実行する。  
実行には Docker、一時 PostgreSQL、Web Server と WireMock が必要になる。

```bash
./mvnw "-Dtest=OrderCreateApiTest#create_multipleItems_commitsAndCanBeReadFromLocation" test
```

[Unit 06](../06-sql-repository-db-integration-testing/README.md)〜[Unit 09](../09-rest-api-testing/README.md) の README にある個別クラス・メソッド指定は、その対象の再現に使える。  
過去の README で引数なしの `./mvnw test` を **全体回帰**として使う箇所は、Unit 10 適用後のツリーでは `./mvnw verify` に読み替える。  
取得済み環境での `./mvnw -o test` も、全体を対象にする場合は `./mvnw -o verify` に読み替える。

### 4. 全体回帰で両 Plugin の Report を照合する

Docker Engine とローカルのポート利用が可能な状態で、フィルターなしの `verify` を実行する。

```bash
./mvnw verify
```

別の用途で `target/` に出力済みの場合は、既存の結果と分けるために、例えば次のように今回用の出力先を選べる。

```bash
./mvnw "-Dstudy.build.directory=target/unit10-local-verify" verify
```

既定の出力先と、読み取る情報は次のとおりである。  
`study.build.directory` を変更した場合は、先頭の `target/` を指定したディレクトリに読み替える。

| ファイル・ディレクトリ                                   | 見る内容                                                                                       |
| -------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| `target/surefire-reports/TEST-*.xml` とクラス別の `.txt` | 純粋な Unit Test と MVC Slice。クラス名、ケース数、`failures` / `errors` / `skipped`、失敗箇所 |
| `target/failsafe-reports/TEST-*.xml` とクラス別の `.txt` | Unit 06〜09 のクラス別結果。既定の全体実行では Surefire 側とクラスが重複しないこと             |
| `target/failsafe-reports/failsafe-summary.xml`           | `failsafe:verify` が読む統合グループの集計。クラス別 XML と実行結果が対応すること              |
| Maven のコンソール                                       | 両 Plugin が実行された順序、各集計、`failsafe:verify` と Maven の最終結果                      |

両方の `TEST-*.xml` を完全修飾クラス名で分け、両集合の共通部分が空で、合わせると「実行クラスの構成」の 27 クラスになることを確認する。  
クラス別 XML の件数を合計し、コンソールや Failsafe の summary と突き合わせる。  
表示名や Parameterized / Repeated Test のケース名と、ソース上のクラス名・メソッド名は分けて読む。

0 件の失敗を観察する場合は、次の負の例を一つずつ実行する。
意図的に存在しないメソッド・Tag・クラスを指定している。  
最後の例は先行する Surefire を `OrderTest` に絞り、実 DB・実 HTTP を使うクラスの起動前に Failsafe の未発見を観察する。

```bash
./mvnw "-Dtest=OrderTest#unit10MissingMethod" test
./mvnw -Dgroups=unit10-no-match test
./mvnw "-Dtest=OrderTest" "-Dit.test=Unit10NoSuchTest" verify
```

各コマンドは失敗する。
コンソールの対象 Plugin、`No tests were executed` または `No tests matching pattern` を含むメッセージ、終了コードを確認する。  
skip を指定して意図的にテストを省略した場合は、実行成功と区別して記録する。

### 5. 純粋な Unit Test と全体の Coverage を分けて計測する

純粋な Unit Test だけを計測する。  
出力を `target/unit10-coverage-unit` に分けると、次の全体計測後にも比較できる。

```bash
./mvnw -Pcoverage "-Dstudy.build.directory=target/unit10-coverage-unit" -Dgroups=unit clean test
```

次に Docker とローカルの HTTP を使える状態で、全体を計測する。  
このコマンドは profile の既定の `target/unit10-coverage` を使用する。

```bash
./mvnw -Pcoverage clean verify
```

上の二つの `clean` は、指定された専用出力先の前回結果を消してから当回のデータを生成するために使う。
通常の `target/` に戻した状態での `clean` は通常出力も削除するため、出力先を変更する場合は削除対象を先に確認する。  
`target/` 以下の Report や `.exec` は再生成する成果物であり、新しい checkout での前提ファイルに含めない。

| 計測コマンド               | 主な出力                                                                                                                                            |
| -------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| 純粋な Unit Test           | `target/unit10-coverage-unit/jacoco/surefire.exec`、`target/unit10-coverage-unit/site/jacoco-surefire/index.html` と同じディレクトリの `jacoco.xml` |
| 全体計測の Surefire 部分   | `target/unit10-coverage/jacoco/surefire.exec`、`target/unit10-coverage/site/jacoco-surefire/index.html` と `jacoco.xml`                             |
| 全体計測の Failsafe と集計 | `target/unit10-coverage/jacoco/failsafe.exec` と `suite.exec`、`target/unit10-coverage/site/jacoco-suite/index.html` と `jacoco.xml`                |

Coverage profile 内でも Test Report は同じビルド出力先の `surefire-reports/` と `failsafe-reports/` に出る。  
全体計測では Test Report でクラス集合を確認してから、`surefire.exec` と `failsafe.exec` が両方生成され、merge が両方を読み込んだことを確認する。  
続く `report-suite-coverage` が `suite.exec` を読み込めば、今回の Test Suite 全体と Report が対応する。

### 6. HTML の行・分岐から Test Code に戻る

1. 純粋な Unit Test の `jacoco-surefire/index.html` を開き、`com.example.orderapi.service` から `OrderPriceCalculator` とソース表示へ進む。
2. `calculate(...)` の `items == null || items.isEmpty()` の背景色と分岐のひし形を別々に見る。第1回の純粋な Unit Test 計測では、現行ソースの 35 行目は命令 5 / 5、分岐 3 / 4 だった。空の List を渡す既存テストと非空の List を使う正常ケースを確認し、残る短絡経路を特定する。
3. List の中に `null` がある場合の例外を投げる 39 行目を開く。同じ計測では命令 0 / 5 だった。List 自体が空・`null` の場合と区別し、追加で保証するならどの入力でどの例外を確かめるかを考える。
4. `calculateDiscount(...)` の割引判定と `calculateDiscount_returnsExpectedAmountAroundThreshold(...)` を対応させる。分岐の通過に加え、境界の 10,000 円で割引額を直接比較していることを確認する。
5. 全体の `jacoco-suite/index.html` で `ProductController` の Constructor と `findAll()` / `findById(...)` を比較する。第1回の全体計測では Constructor が実行され、二つの HTTP ハンドラーの本体は未実行だった。アプリケーション起動でクラスに到達することと、商品 API を呼び出して確認することを区別する。

行番号は現行ソースに対応する。以降は番号に加え、メソッド名と条件式で照合する。  
XML でも同じソース行の `mi`、`ci`、`mb`、`cb` を確認できる。  
テストを増やすかは、未実行の箇所がどの境界から到達し、どの仕様や障害を観測できるかで判断する。

### 7. 同じ全体データで合格と閾値未達を比較する

手順 5 のフィルターなしの全体計測が成功した後に、同じ `target/unit10-coverage` にある `suite.exec` と Production Class を使う。  
以下は第1回の実測値（Line 89.53%、Branch 70.41%）で合格を確認した例である。  
自分の計測値が異なる場合は、当回の `jacoco.xml` の `covered / (covered + missed)` に収まる下限を選ぶ。

```bash
./mvnw -Pcoverage -Dcoverage.minimum.line=0.89 -Dcoverage.minimum.branch=0.70 jacoco:check@check-suite-coverage
```

`All coverage checks have been met.` と Maven の成功を確認する。  
続いて、計測データはそのままに、下限だけを変更する。  
Branch に未通過がある計測に対して次を実行すると、Branch の閾値未達を確認できる。

```bash
./mvnw -Pcoverage -Dcoverage.minimum.line=0.89 -Dcoverage.minimum.branch=1.00 jacoco:check@check-suite-coverage
```

第1回の結果では、`jacoco:check` が `Rule violated` と Branch の実測比率・最低値を出し、終了コードは `1` だった。
Check は Test Method を再実行せず、既存データを基に判定する。  
出力先を変えて全体を計測した場合は、Check にも同じ `study.build.directory` を指定する。  
Check の前に `clean` を付けると、入力にする実行データも削除される。

`Skipping JaCoCo execution` や入力欠落が出たときは、閾値を通過したと判断しない。
片方だけのデータを merge できる場合もあるため、両 Plugin の実行結果と両方の `.exec` を確認してから Check に進む。  
この手順の比率は学習用の指定値であり、固定の合格基準は POM に置いていない。

### 8. 失敗した段階を切り分ける

| 症状                                                          | 先に確認すること                                                                                                        |
| ------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| Wrapper、依存、Plugin の解決やコンパイルに失敗する            | JDK、Wrapper の配布版、依存キャッシュ、通信の可否、`test-compile` の結果                                                |
| 実行対象が 0 件、または期待クラスが現れない                   | 到達した phase、Plugin、include / exclude、名前・Tag、`-Dtest` と `-Dit.test` の違い、当回の出力先                      |
| `surefire:test` で失敗する                                    | Surefire のクラス別 Report と最初の原因。後続の Failsafe は実行されていない場合がある。                                 |
| `failsafe:verify` で失敗する                                  | Failsafe のクラス別 Report と summary。Assertion、Docker 接続、Context、DB、HTTP のどこで失敗したかを調べる。           |
| テスト後に Coverage Report が生成されない、または内容が欠ける | `-Pcoverage`、最後に到達した goal、Agent の引数と JVM 終了、各 `.exec` の有無とサイズ、classfile の不一致や skip のログ |
| `jacoco:check` が `Rule violated` で失敗する                  | `LINE` / `BRANCH` のどちらか、当回の比率と指定下限、全体を計測した `suite.exec` を使っているか                          |
| `jacoco:check` に設定エラーや skip が出る                     | 二つの下限値、profile、ビルド出力先、実行データとコンパイル済みクラス。閾値未達のログと区別する。                       |

テスト自体が成功しても、Agent や Report の問題は別に起こり得る。
反対に Test Report が失敗を示していれば、古い HTML が存在しても今回の Coverage が生成されたとは判断できない。  
Maven の最終結果、失敗した goal、今回の出力をセットで読む。

### 9. Server・Container・DB の後始末を確認する

- Unit 06 はテスト Transaction の Rollback、Unit 07・09 は業務結果を観測した後の `@Sql` Cleanup で、次のケースに行データを持ち越さない。Unit 09 の `verifyCleanupAfterClass(...)` は、クラス最後の Cleanup 後に対象テーブルが空であることを確認する。
- Unit 08 の WireMock は JUnit Extension が管理する。Unit 09 の Web Server と WireMock は Spring Context が管理し、同じ Context を利用する間は起動リソースを再利用できる。個々のケースでは Stub と受信履歴を初期化する。
- 実 PostgreSQL を使うテストは、`@ServiceConnection` が渡す一時 Container の接続先を使う。`schema.sql` を初期化に利用し、通常起動用の `data.sql` の代わりにテスト用 SQL を使う。通常用 Compose のサービスと永続 Volume は、この Cleanup の対象外である。

終了後に Docker の残存を調べる場合は、一覧を確認する。

```bash
docker ps
docker ps -a --filter label=org.testcontainers
```

別の Test Suite も同じ Engine を使うことがある。結果は今回のログの Container と照合する。  
DB の業務結果は、テスト内の Cleanup 前の Assertion で確認する。  
テスト終了後の通常用 DB に接続して、保存結果を確認する手順は用意していない。

## 学習ポイント

### 実行する目的から、必要な境界を選ぶ

計算や状態判定を変更したときは、狭い範囲のテストで原因を絞れる。  
DB や HTTP を越える変更では、単体で通るロジックをつないだときの振る舞いも確認する。  
速い実行を日常の確認に、広い実行を接続部分の回帰確認に使うことで、各テストの価値を保てる。

### 件数とクラス集合を分けて確認する

実行件数は Parameterized / Repeated Test の展開や選択条件で変わる。  
全体回帰の妥当性は、対象クラスの集合、ケース数、失敗とスキップの内訳を合わせて判断する。  
明示的なクラス指定が既定の選択を上書きする点は、互換性にも重複の原因にもなる。

### Coverage を業務上の問いに戻す

未実行の分岐が見つかったら、実際の呼び出し方、入力、期待する状態・戻り値・副作用を考える。  
到達済みの場合も、値や状態が誤っていたら Assertion が失敗するかを確認する。  
割引境界、在庫 Rollback、送料障害の API 応答は、この三つを別の層で考える例となる。

## 補足

### Report の種類を使い分ける

Test Report は、どのテストが何件実行され、どの Assertion や例外で失敗したかを調べるために使う。  
JaCoCo Report は、実行したテスト全体がどの Production Code に到達したかを調べるために使う。  
Coverage の HTML から特定の行を見つけたら、対応する入力と期待値は Test Code や Test Report で確認する。

### 実行条件を後から確認できる形で残す

実行結果を共有するときは、コマンド、選択条件、JDK / Maven、必要だった外部環境、両 Plugin の件数、Report の集計範囲を添える。
比較に時間を使う場合は、初回取得や JVM・Context・Container の起動を含むかも記録する。  
ローカルの結果と、別環境で実行する CI の結果は、同じコマンドでも別の検証結果として扱う。
