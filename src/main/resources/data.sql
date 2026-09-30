-- 通常動作確認用の商品初期データ。
-- 自動テスト用 Fixture とは分離し、手動で API の動作を確認するためだけに利用する。
-- 一般的な注文確認に利用する通常商品。
INSERT INTO
    products (id, name, price, stock_quantity, active)
VALUES
    (1, 'Standard Product', 3000, 100, TRUE) ON CONFLICT (id) DO
UPDATE
SET
    name = EXCLUDED.name,
    price = EXCLUDED.price,
    stock_quantity = EXCLUDED.stock_quantity,
    active = EXCLUDED.active;

-- 1 個で割引境界の 10,000 円を作れる商品。
INSERT INTO
    products (id, name, price, stock_quantity, active)
VALUES
    (2, 'Discount Boundary Product', 10000, 20, TRUE) ON CONFLICT (id) DO
UPDATE
SET
    name = EXCLUDED.name,
    price = EXCLUDED.price,
    stock_quantity = EXCLUDED.stock_quantity,
    active = EXCLUDED.active;

-- 在庫下限付近の動作確認に利用する商品。
INSERT INTO
    products (id, name, price, stock_quantity, active)
VALUES
    (3, 'Single Stock Product', 1500, 1, TRUE) ON CONFLICT (id) DO
UPDATE
SET
    name = EXCLUDED.name,
    price = EXCLUDED.price,
    stock_quantity = EXCLUDED.stock_quantity,
    active = EXCLUDED.active;

-- 注文数量上限 99 と同じ在庫数を持つ商品。
INSERT INTO
    products (id, name, price, stock_quantity, active)
VALUES
    (4, 'Quantity Boundary Product', 100, 99, TRUE) ON CONFLICT (id) DO
UPDATE
SET
    name = EXCLUDED.name,
    price = EXCLUDED.price,
    stock_quantity = EXCLUDED.stock_quantity,
    active = EXCLUDED.active;

-- 在庫不足・在庫 0 の動作確認に利用する商品。
INSERT INTO
    products (id, name, price, stock_quantity, active)
VALUES
    (5, 'Out Of Stock Product', 2000, 0, TRUE) ON CONFLICT (id) DO
UPDATE
SET
    name = EXCLUDED.name,
    price = EXCLUDED.price,
    stock_quantity = EXCLUDED.stock_quantity,
    active = EXCLUDED.active;

-- 販売停止商品の動作確認に利用する商品。
INSERT INTO
    products (id, name, price, stock_quantity, active)
VALUES
    (6, 'Inactive Product', 2500, 50, FALSE) ON CONFLICT (id) DO
UPDATE
SET
    name = EXCLUDED.name,
    price = EXCLUDED.price,
    stock_quantity = EXCLUDED.stock_quantity,
    active = EXCLUDED.active;
