-- Unit 09 専用 Container の行だけを削除する。外部キーの子から親へ進める。
-- ISOLATED Transaction で Commit し、Test 自身の Rollback に後始末を任せない。
-- 業務結果の Assertion より後に実行し、採番はリセットせず欠番を許容する。
DELETE FROM order_items;

DELETE FROM orders;

DELETE FROM products;
