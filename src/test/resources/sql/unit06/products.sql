-- Unit 06 専用の Database Fixture。通常起動用 data.sql には依存しない。
-- @Sql は各 Test の Transaction 内で実行され、変更と初期データは終了時に Rollback される。
-- 固定 ID は既存行の識別用であり、Repository が採番する ID の期待値ではない。
-- ID の挿入順を崩し、findAll() が INSERT 順ではなく ORDER BY id で返すことを確認する。
INSERT INTO
    products (id, name, price, stock_quantity, active)
VALUES
    (1003, 'Archived Hub', 1000, 0, FALSE),
    (1001, 'Keyboard', 3000, 10, TRUE),
    (1002, 'Mouse', 2500, 20, TRUE);
