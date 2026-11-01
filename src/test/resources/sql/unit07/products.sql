-- Unit 07 の開始状態。通常用 data.sql と Unit 06 の Test Transaction には依存しない。
-- @Sql の ISOLATED Transaction で Commit し、後から Service が開始する業務 Transaction と分ける。
-- 販売停止は在庫不足と区別できるよう在庫を持たせ、在庫 0 は別の商品で表す。
INSERT INTO
    products (id, name, price, stock_quantity, active)
VALUES
    (7001, 'Keyboard', 3000, 10, TRUE),
    (7002, 'Mouse', 2500, 20, TRUE),
    (7003, 'Archived Hub', 1000, 5, FALSE),
    (7004, 'Sold Out Cable', 1000, 0, TRUE);
