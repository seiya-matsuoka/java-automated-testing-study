package com.example.orderapi.unit04.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Flaky Test を生む時間依存・非同期処理の確認方法を対比するための補助教材。
 *
 * <p>BadExamples は OS scheduling、CPU 負荷、実時刻の影響を受けるよう意図的に作っている。 学習用の悪い例を通常の Maven Test Suite
 * に混ぜると、正常な変更でも CI が不安定になり得るため分離する。
 */
public class FlakyTimingAndAsyncExample {

  /**
   * 悪い例: sleep の経過時間を「非同期処理が終わった証拠」として利用している。
   *
   * <p>CompletableFuture の処理開始・終了時刻は JVM / OS の scheduling に左右される。 sleep の時間を長くしても原因は解消せず、Test
   * が遅くなるだけの場合がある。
   */
  @Nested
  @DisplayName("悪い例: sleep と非同期 race")
  class BadExamples {
    @Test
    void asyncWork_assumesTenMillisAreAlwaysEnough() throws InterruptedException {
      CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> 42);

      Thread.sleep(10);

      // 10 ms 待てば必ず完了するという契約はなく、実行環境で結果が変わり得る。
      assertTrue(future.isDone());
      assertEquals(42, future.getNow(-1));
    }

    @Test
    void realClock_usesAnArbitraryTolerance() throws InterruptedException {
      Instant before = Instant.now();
      Thread.sleep(5);
      Instant after = Instant.now();

      // 実時刻と OS scheduling に依存する上、期待する業務仕様も表現していない。
      assertTrue(after.toEpochMilli() - before.toEpochMilli() < 20);
    }
  }

  /**
   * 改善例: 時間の経過を仮定せず、固定時刻または明示された非同期完了条件を検証する。
   *
   * <p>CompletableFuture.get(timeout) は指定した完了を期限付きで待つ。 Awaitility は「一定時間内に状態が期待条件を満たすこと」を polling
   * で待つ場合に有用だが、 今回の例のように Future を直接待てるなら、新たなライブラリを導入する必要はない。
   */
  @Nested
  @DisplayName("改善例: 固定時刻と完了条件")
  class BetterExamples {
    @Test
    void fixedClock_hasNoTimingTolerance() {
      Instant expected = Instant.parse("2026-10-01T00:00:00Z");
      Clock clock = Clock.fixed(expected, ZoneOffset.UTC);

      assertEquals(expected, Instant.now(clock));
    }

    @Test
    void asyncWork_waitsForItsCompletion() throws Exception {
      CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> 42);

      // 何 ms sleep すればよいかを推測せず、完了した結果を timeout 付きで待つ。
      assertEquals(42, future.get(1, TimeUnit.SECONDS));
    }
  }
}
