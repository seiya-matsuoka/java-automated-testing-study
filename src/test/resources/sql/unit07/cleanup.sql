-- Unit 07 専用 Container の行データを、外部キーの子から親の順に削除する。
-- @Sql の ISOLATED Transaction で Commit し、次の Test の前提を整える。
-- Test 本体の最終状態 Assertion より後に実行するため、業務 Rollback の証拠には使用しない。
-- sequence の欠番は許容し、採番値を期待するための RESTART IDENTITY は行わない。
DELETE FROM order_items;

DELETE FROM orders;

DELETE FROM products;
