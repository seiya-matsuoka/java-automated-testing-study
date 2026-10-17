package com.example.orderapi.unit04.examples;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Random;
import java.util.function.IntSupplier;

/**
 * Testability を低下させる hard-coded dependency と、依存性を注入する設計の比較用教材。
 *
 * <p>このファイルはメインアプリの Production Code ではない。意図的に問題のある設計を示すため、
 * units/04-testability-test-quality/examples/ 配下に独立させる。
 */
public final class HardCodedDependencyExample {

  private HardCodedDependencyExample() {}

  /**
   * 悪い例: 時刻・乱数・外部環境に対する依存を method 内で固定している。
   *
   * <p>Clock や Random を Test から差し替えられず、結果が実行時刻・乱数の状態・JVM の System Property に左右される。
   * 同じ入力を与えても同じ出力が返る保証がないため、期待値が安定しない。
   */
  static final class HardCodedReferenceGenerator {

    String generate(long orderId) {
      Instant createdAt = Instant.now();
      int suffix = new Random().nextInt(10_000);
      String region = System.getProperty("shipping.region", "JP");
      return region + "-" + orderId + "-" + suffix + "-" + createdAt;
    }
  }

  /**
   * 改善例: 時刻・乱数・設定値を constructor から注入する。
   *
   * <p>Clock.fixed(...)、() -> 1234、固定の region を渡すことで、実行環境に依存しない Test が可能になる。 Production では
   * Clock.systemUTC() や Random::nextInt 相当の実装を渡せばよい。
   * 「何を生成するか」という責務と「現在時刻・乱数・設定をどこから取得するか」という責務を分離する。
   */
  static final class InjectedReferenceGenerator {

    private final Clock clock;
    private final IntSupplier suffixSupplier;
    private final String region;

    InjectedReferenceGenerator(Clock clock, IntSupplier suffixSupplier, String region) {
      this.clock = Objects.requireNonNull(clock);
      this.suffixSupplier = Objects.requireNonNull(suffixSupplier);
      this.region = Objects.requireNonNull(region);
    }

    String generate(long orderId) {
      int suffix = suffixSupplier.getAsInt();
      return region + "-" + orderId + "-" + suffix + "-" + Instant.now(clock);
    }
  }

  /** 改善例の使い方を示す。Test に都合のよい値が得られる理由が Production Code の隠れた分岐ではなく、 呼び出し元が明示的に渡した依存関係にあることを確認する。 */
  static String deterministicExample() {
    Clock clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), java.time.ZoneOffset.UTC);
    InjectedReferenceGenerator generator = new InjectedReferenceGenerator(clock, () -> 1234, "JP");
    return generator.generate(100L);
  }
}
