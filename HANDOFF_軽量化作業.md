# FGCシミュレータ_軽量版 動作軽量化作業 — 完了報告

最終更新: 2026-06-11 / 作成: Claude (Fable 5 メインセッション)
状態: **✅ 完了**（全実装・監査・テスト・計測・GUIスモークテスト済み）

---

## 1. 成果サマリ

ヘッドレスベンチ（対戦3v3・ボール500・7000tick計測、`practice.PerfBenchmark`）:

| 指標 | 変更前 | 変更後 | 改善率 |
|---|---|---|---|
| 1tick平均 | 261.4ms | **6.8ms** | **38倍** |
| 1tick p95 | 489.6ms | **10.1ms** | 48倍 |
| 1tick 最大 | 3,283ms | **21.2ms** | 155倍 |
| NPC AI | 248.3ms | **3.7ms** | **67倍** |
| ボール物理 | 12.5ms | **2.9ms** | 4.3倍 |

50Hz予算20msに対し平均6.8ms。**挙動を変えない純粋最適化のみ**（RNG呼び出し順序・反復順序・効用値を保存）。
検証: JUnitテスト全合格 / GUI起動スモークテスト合格（例外は既存の無害なjamepadゲームパッドマッピング警告のみ）。

## 2. 実施した最適化（ファイル別）

### practice/CompetitionManager.java（AI、最大の効果）
- `isRayBlocked`: 障害物エッジをflat配列に事前展開（`buildRayCache`）＋レイ/エッジAABB事前棄却
- ボール空間グリッド（`buildBallGrid`、セル1.0m・9×9、ロボット単位再構築）で密度カウントのO(N²)排除。粗選別→正確距離フィルタ＋昇順ソートで**全走査とビット単位同一**
- `collectBallNeighbors` による近傍探索化（collectAccessBonus / cluster / surround / nearby）
- 射撃サンプル36点グリッドの静的キャッシュ（`getShootSampleGrid`、反復順序保存）
- `dist()` ヘルパーで `Math.hypot`→`sqrt(dx²+dy²)`（最終桁差のみ）、比較専用箇所は2乗距離化（厳密同値）約20箇所
- 毎30tickの `[AI]` デバッグprintln削除

### core/BallSimulation.java + core/CircleObstacle.java
- 空間グリッド（70×70）の毎サブステップ再割り当てを廃止（使用セルのみclear()で再利用）
- 衝突ペアを `new int[]` から再利用long[]バッファへ（`(idA<<32)|idB` エンコード、順序保存）
- `CircleObstacle.collideCircleVsOBB` 静的メソッド抽出＋外接円early-out（偽陰性ゼロ）。ボール×ロボット判定の `new CircleObstacle("ball_"+id,…)`（最大12,000個/フレーム）を完全排除
- デバッグ専用の全ボール×白ゴールスキャン＋println削除（毎サブステップ実行されていた）
- hypot→2乗距離比較/sqrt化

### ui/FieldPanel.java（EDT負荷）
- 走行軌跡を Path2D 2本（通常/スリップ）にバッチ化（従来は毎paintセグメント個別draw）
- ボール500個の Ellipse2D を1個再利用
- `calculateShootTargetInfo` の1paint1回化、suppression_unit頂点キャッシュ、Font/Color/Stroke定数化

### core/SimulationEngine.java + ui/SimulatorApp.java + ui/ControlPanel.java
- `SimpleDateFormat` のフィールド化（毎tick生成→1個再利用）
- 描画プッシュを33ms間隔・ステータス更新を100ms間隔にスロットリング＋`invokeLater`でEDT化（スレッド安全性違反も修正）
- メインループのsleepを残時間計算方式に、ControlPanelのColor定数化
- 仕様差（報告済み・軽微）: 軌跡サンプリング50→30Hz、タイマー表示更新100ms間隔（表示精度は実質同一）

## 3. 検証・運用情報

- ベンチ再実行: HANDOFF旧版と同じ。`.\gradlew.bat compileJava` → `java -cp "build\classes\java\main;<gsonのjar>" practice.PerfBenchmark`（gson は `%USERPROFILE%\.gradle\caches\modules-2\files-2.1\com.google.code.gson` 配下）
- テスト: `.\gradlew.bat test` / 起動: `.\run.ps1`
- 変更前スナップショット: `backup_baseline\src\`（git管理外のため、これがdiffの基準。**当面残しておくこと推奨**）
- 計測生ログ: `%TEMP%\fgc_bench_baseline.txt` / `fgc_bench_optimized.txt`(第1弾後) / `fgc_bench_r2.txt`(最終)

## 4. 残課題・フォローアップ候補（未実施）

1. **通常版 `FGCシミュレータ` への移植**: 同一コードベース（実質差分はNPCデッドロック回避処理約40行のみ）なので同じ最適化を適用可能。移植時はその40行（idleStuckTimer まわり）を消さないよう注意
2. 実機での体感確認（特に低スペックPC、対戦モードの [PERF] コンソール出力で確認可）
3. `PerfBenchmark.java` は計測用に残置（プロダクトには無害）。不要なら削除可
4. 対戦モードでNPC挙動が変更前と同じに「見える」かの目視確認（数値上は挙動保存を監査済みだが、最終確認は人間の目で）
