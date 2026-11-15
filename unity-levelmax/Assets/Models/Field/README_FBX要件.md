# field.fbx 取り込み要件（Onshape→Blender→Unity）

ここに `field.fbx` を置く。Unity側ビルダーがこのモデルを配置し、色・当たり判定を付与する。

## 単位・座標
- 1 unit = 1 メートル（Onshape OBJ はm単位。BlenderのFBX出力で Apply Scalings = FBX Units Scale）
- Up = Y / Forward = -Z（Blender FBX出力）
- **ワールド位置は動かさない**：CAD原点（フィールド中心・RISER底）のまま出す。
  Unity側で +3.5(X) / -0.7(Y) / +3.5(Z) のオフセットを当てて 0..7・床y=0 に合わせる。
  ※Blenderで Apply Transform（回転/スケール）はOKだが、オブジェクトを別位置へ移動しないこと。

## 残す / 消す
- 残す：GUARD_RAIL, RISER, CENTER_GOAL（トラス含む）, SIDE_GOAL_RED, SIDE_GOAL_BLUE,
  HUMAN_PLAYER_STATION_RED/BLUE, 各TAPE, 構造パネル・チューブ・斜めブレース
- 消す：ボルト/ナット/ベアリング/スペーサ等の微小ハードウェア、GAMEPIECE（球はシムが生成）

## オブジェクト分割・命名（重要）
色と当たり判定を構造ごとに当てるため、**構造単位で統合し、以下の名前**を付ける：
- `guard_rail`
- `riser`
- `center_goal`
- `side_goal_red`
- `side_goal_blue`
- `hps_red`
- `hps_blue`
- `alliance_tape_red` / `alliance_tape_blue`
- `field_tape_red` / `field_tape_blue`

（全部1メッシュに統合してしまうと赤/青の塗り分け・ゴール別コライダができないので、最低でも上記単位で分ける）

## 法線
- Blenderで法線を外向きに再計算（Recalculate Outside）してから出力。

## 取り込み後の私の作業
- 旧 FieldStructures の箱/捏造枠/推測ブレース生成を撤去
- このFBXを配置するビルダーへ差し替え（位置オフセット適用）
- 表示メッシュには当たり判定を付けない。床・壁・ゴールは Box Collider を別生成、
  複雑で必要な所のみ凹 Mesh Collider（非Convex・限定使用）
