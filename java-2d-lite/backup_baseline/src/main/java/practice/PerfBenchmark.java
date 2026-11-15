package practice;

import core.RobotConfig;
import core.SimulationEngine;
import input.InputSource;

import java.util.Arrays;

/**
 * ヘッドレス性能ベンチマーク。Swing/AWTウィンドウは一切生成しない。
 */
public class PerfBenchmark {

    /** tick数をカウントし、sin波で動き回るInputSourceスタブ実装。 */
    static class StubInputSource implements InputSource {
        int tickCount = 0;

        @Override
        public double getTranslationInput() {
            return 0.6 * Math.sin(tickCount * 0.01);
        }

        @Override
        public double getRotationInput() {
            return 0.4 * Math.sin(tickCount * 0.017);
        }

        @Override
        public void update() {
            tickCount++;
        }

        @Override
        public boolean isIntakePressed() {
            return (tickCount % 300) < 150;
        }

        @Override
        public boolean isShootPressed() {
            return (tickCount % 300) >= 150;
        }

        @Override
        public boolean isEjectPressed() {
            return false;
        }
    }

    public static void main(String[] args) {
        // セットアップ
        RobotConfig config = new RobotConfig(); // デフォルト値 (ballCount=500)

        // CompetitionManager: 3v3対戦、赤アライアンス (isBlueAlliance=false)
        CompetitionManager manager = new CompetitionManager(config, false);

        StubInputSource inputSource = new StubInputSource();

        // SimulationEngine 生成
        SimulationEngine engine = new SimulationEngine(config, inputSource);

        // 対戦モードに設定
        engine.setCompetitionMode(true, manager);

        final int WARMUP_TICKS = 500;
        final int MEASURE_TICKS = 7000;
        final double DT = 0.02;

        // ウォームアップ 500 tick
        System.out.println("ウォームアップ中 (" + WARMUP_TICKS + " ticks)...");
        for (int i = 0; i < WARMUP_TICKS; i++) {
            inputSource.update();
            engine.tick(DT, manager);
        }

        // 計測 7000 tick
        System.out.println("計測中 (" + MEASURE_TICKS + " ticks)...");
        double[] tickWallMs = new double[MEASURE_TICKS];
        double[] aiMs = new double[MEASURE_TICKS];
        double[] ballMs = new double[MEASURE_TICKS];

        long measureStart = System.nanoTime();

        for (int i = 0; i < MEASURE_TICKS; i++) {
            long t0 = System.nanoTime();
            inputSource.update();
            engine.tick(DT, manager);
            long t1 = System.nanoTime();
            tickWallMs[i] = (t1 - t0) / 1_000_000.0;
            aiMs[i] = engine.lastAiTimeMs;
            ballMs[i] = engine.lastBallTimeMs;
        }

        long measureEnd = System.nanoTime();
        double totalWallS = (measureEnd - measureStart) / 1_000_000_000.0;

        // 統計計算
        double tickAvg = average(tickWallMs);
        double tickP95 = percentile95(tickWallMs);
        double tickMax = max(tickWallMs);
        double aiAvg = average(aiMs);
        double ballAvg = average(ballMs);

        // 出力
        System.out.printf("BENCH tick_avg_ms=%.4f tick_p95_ms=%.4f tick_max_ms=%.4f%n",
                tickAvg, tickP95, tickMax);
        System.out.printf("BENCH ai_avg_ms=%.4f ball_avg_ms=%.4f%n",
                aiAvg, ballAvg);
        System.out.printf("BENCH total_wall_s=%.4f%n", totalWallS);
    }

    private static double average(double[] arr) {
        double sum = 0.0;
        for (double v : arr) sum += v;
        return sum / arr.length;
    }

    private static double percentile95(double[] arr) {
        double[] sorted = Arrays.copyOf(arr, arr.length);
        Arrays.sort(sorted);
        // 95パーセンタイル: インデックス = floor(0.95 * n)
        int idx = (int) Math.floor(0.95 * sorted.length);
        if (idx >= sorted.length) idx = sorted.length - 1;
        return sorted[idx];
    }

    private static double max(double[] arr) {
        double m = Double.NEGATIVE_INFINITY;
        for (double v : arr) if (v > m) m = v;
        return m;
    }
}
