-- 状態遷移の対象を選ぶための、状態別の代表注文。各 Test は対象の 1 注文を独立して操作する。
-- 対象注文の 2 明細は 2 個ずつの注文で、キャンセル前在庫は減算済みの 8 / 18 とする。
-- 全期間の仕入れ・受注履歴を再現せず、注文状態・保存内容・現在在庫の関係に焦点を当てる。
UPDATE products
SET
    stock_quantity = 8
WHERE
    id = 7001;

UPDATE products
SET
    stock_quantity = 18
WHERE
    id = 7002;

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
        70001,
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
        70002,
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
        70003,
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
        70004,
        'CANCELLED',
        '1000001',
        11000,
        1100,
        800,
        10700,
        '2026-10-01 00:00:00.123456+00',
        '2026-10-05 00:00:00.654321+00'
    );

-- Repository の取得は明細 ID 順となる。Keyboard が先に更新され、Mouse が後続する配置を明示する。
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
    (700001, 70001, 7001, 'Keyboard', 3000, 2, 6000),
    (700002, 70001, 7002, 'Mouse', 2500, 2, 5000),
    (700003, 70002, 7001, 'Keyboard', 3000, 2, 6000),
    (700004, 70002, 7002, 'Mouse', 2500, 2, 5000),
    (700005, 70003, 7001, 'Keyboard', 3000, 2, 6000),
    (700006, 70003, 7002, 'Mouse', 2500, 2, 5000),
    (700007, 70004, 7001, 'Keyboard', 3000, 2, 6000),
    (700008, 70004, 7002, 'Mouse', 2500, 2, 5000);
