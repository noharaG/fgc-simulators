# 朝の実行手順書（夜間自走の成果を使い切るための順序つきチェックリスト）

最終更新: 2026-06-10 03:35（夜間自走で作成）。詳細の正典は `HANDOFF.md`。ここは**上から順に実行する要約**。

夜間に進めたこと（コミット済み・GitHub同期済み）:
- ① NPCの動き: Java競技NPC(CompetitionManager)のPD操舵＋回避を `RuleBasedNpcController` へ忠実移植＋EditModeテスト8件。
- ② ML環境: Python学習側がE2E完成（`ml/.venv310`＝torch+CUDA+mlagents、trainerがポート5004接続待ち到達）。報酬/配線/観測速度の品質バグも修正。
- 既存: ブレース当たり判定・登攀・段階⑤(HUD)・段階⑥(VR HMD表示+頭追従) は検証済み。

---

## STEP 0｜まず Unity でコンパイル確認（最優先）
夜間のコード変更は**Unityで未コンパイル検証**（Editor.logが6/9 23:39で停止）。
1. Unityをフォーカス → 自動再コンパイル。
2. Console / `Editor.log` で **error CS が0** を確認。
3. 万一エラーが出たら、夜間変更を疑い `git log --oneline` から該当を `git revert`（機能単位でコミット分離済）。疑う順: `RuleBasedNpcController.cs`(PD操舵) → `EcsRobotSim.cs`(速度供給) → `MatchDirector.cs` → `Assets/FGC/Tests/`。

## STEP 1｜NPCの検証（①）
1. **Test Runner**（Window > General > Test Runner）> EditMode > Run All。
   `RuleBasedNpcControllerTests` の **8ケースが緑**なら、移植した競技PD操舵＋回避が数値的に正しい。
2. `FGC > Build Field Scene` → **Play**。NPCが「最寄り球へ正対しながら接近→回収→ゴールへ→正対でシュート」、
   さらに「壁際で前進停止して回頭」「近接ロボを避けて回り込む」動きになっていれば①は反映成功。
3. （任意）`EcsRobotSim` の速度供給修正の効き: NPCが旋回時にカクつかず滑らかに収束するか（PD D項）。

## STEP 2｜ML環境を有効化（②）★Sentis問題の回避策は判明済み
1. **Window > Package Manager > 「ML Agents」を install**。
   Unity 6000.0 では **com.unity.ml-agents 4.0.x 系**に解決し、**Inference Engine(`com.unity.ai.inference`)を自動導入**（旧Sentis非依存＝当エディタで通る）。
2. `Editor.log` で **版解決＋コンパイル0** を確認。error CS が出たら manifest/packages-lock を `git revert`。
   ※`MLAgentController.cs` は Agent/BehaviorParameters/ActionSpec/VectorSensor を使用（2.x〜4.xで安定API）。4.0.xで差異があればここを微修正。
3. **Python版を Unity版に合わせる**: com.unity.ml-agents 4.0.x は **Release 23**。Python venv は現在 `mlagents 1.1.0`。
   - Release 22/23 とも Python は 1.1.0 系のため **そのまま動く可能性が高い**。
   - Play時に `communication API versions ... differ at the minor version level` の**警告が出ても、minorレベルなら学習は動く（無視可）**。慌てない。
   - もし **hard mismatch で接続失敗**したら、Release 23 ページの対応版を確認して `ml\.venv310\Scripts\python.exe -m pip install mlagents==<対応版>`。
4. `FGC.App.asmdef` に `FGC.ML` 参照を戻す → **Player Settings > Player > Scripting Define Symbols に `FGC_MLAGENTS`** を追加。
   → `MLAgentController` / `RewardShaper`（格納報酬のECS結線済）/ 観測22次元 が復活。

## STEP 3｜学習を開始（③ PPO自己対戦）
1. `FGC > Build Training Scene`（全MLスロット・自動連続）。
2. 別のPowerShellで: `./ml/train.ps1`（run-id自動）。`[INFO] Listening on port 5004...` が出る。
3. Unityで **Play** → 学習開始（Editor接続）。高速並列なら STEP3-別案へ。
   - **別案（高速）**: ビルドした実行ファイルを作り `./ml/train.ps1 -EnvExe <path>`（`--num-envs=8 --time-scale=20 --no-graphics`）。
4. 監視: `tensorboard --logdir results`（venvの tensorboard）。成果は `results/<run-id>/`（.gitignore済）。

## STEP 4｜起床後に確認したい既知事項
- **観測速度の修正**（`EcsRobotSim.TryGetVelocity`）はPlayで効果未確認 → NPCの旋回収束/ML観測で問題なければOK。
- **①の未移植バックログ**（`HANDOFF.md`に正確値）: 難易度プリセット / 弾道予測迎撃 / シュート視線判定 / チーム球割当 / シュートコミット。Play検証下で順次。
- 段階⑥仕上げ: SetupMenu/HUDのVR内Canvas化（未）。
- 段階⑧: 旧PhysX撤去＋フィールドSubScene bake（斜め部材の厳密形状もここで精緻化）。

---
困ったら `HANDOFF.md`（詳細ログの正典）と `ECS移行_引継ぎ_2026-06-09.md`（段階表）を参照。
