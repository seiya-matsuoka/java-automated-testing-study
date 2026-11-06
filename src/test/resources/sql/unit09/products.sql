-- 各 invocation の基準。通常用 data.sql や Unit 07 の行データへ依存しない。
-- 商品の性質を分け、販売停止を在庫不足と誤認しても Test が通る状態にしない。
-- @Sql の ISOLATED Transaction で Commit し、別スレッドの HTTP Server から参照可能にする。
INSERT INTO
    products (id, name, price, stock_quantity, active)
VALUES
    (9001, 'Keyboard', 3000, 10, TRUE),
    (9002, 'Mouse', 2500, 20, TRUE),
    (9003, 'Archived Hub', 1000, 5, FALSE),
    (9004, 'Sold Out Cable', 1000, 0, TRUE);
