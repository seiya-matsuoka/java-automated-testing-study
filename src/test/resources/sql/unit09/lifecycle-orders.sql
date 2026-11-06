-- 3 件の有効な既存注文が各商品を 2 個ずつ予約し、キャンセル済みの 1 件は復元済み。
-- 基準在庫 10 / 20 から 6 個ずつ減算済みの 4 / 14 とする。
-- 1 注文のキャンセルで 6 / 16 になり、他注文分の予約まで戻してはならない。
UPDATE products
SET
    stock_quantity = 4
WHERE
    id = 9001;

UPDATE products
SET
    stock_quantity = 14
WHERE
    id = 9002;

INSERT INTO
    orders (
        id,
        status,
        shipping_postal_code,
        subtotal,
        discount_amount,
        shipping_fee,
        total_amount,
        created_at,
        cancelled_at
    )
VALUES
    (
        92001,
        'CREATED',
        '1000001',
        11000,
        1100,
        800,
        10700,
        '2026-10-01 00:00:00.123456+00',
        NULL
    ),
    (
        92002,
        'CONFIRMED',
        '1000001',
        11000,
        1100,
        800,
        10700,
        '2026-10-01 00:00:00.123456+00',
        NULL
    ),
    (
        92003,
        'SHIPPED',
        '1000001',
        11000,
        1100,
        800,
        10700,
        '2026-10-01 00:00:00.123456+00',
        NULL
    ),
    (
        92004,
        'CANCELLED',
        '1000001',
        11000,
        1100,
        800,
        10700,
        '2026-10-01 00:00:00.123456+00',
        '2026-10-05 00:00:00.654321+00'
    );

INSERT INTO
    order_items (
        id,
        order_id,
        product_id,
        product_name,
        unit_price,
        quantity,
        line_amount
    )
VALUES
    (920001, 92001, 9001, 'Keyboard', 3000, 2, 6000),
    (920002, 92001, 9002, 'Mouse', 2500, 2, 5000),
    (920003, 92002, 9001, 'Keyboard', 3000, 2, 6000),
    (920004, 92002, 9002, 'Mouse', 2500, 2, 5000),
    (920005, 92003, 9001, 'Keyboard', 3000, 2, 6000),
    (920006, 92003, 9002, 'Mouse', 2500, 2, 5000),
    (920007, 92004, 9001, 'Keyboard', 3000, 2, 6000),
    (920008, 92004, 9002, 'Mouse', 2500, 2, 5000);
