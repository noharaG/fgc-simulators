using FGC.Core;
using FGC.Robot;
using UnityEngine;

namespace FGC.App
{
    /// <summary>
    /// 登攀（CLIMB）判定（PLAN.md §3.6・次フェーズ④）。BRACE（鋼管）への接触と機体高さ(y)から
    /// ClimbZone（接触/Z1/Z2/Z3）を判定して RobotActor.ClimbZone を更新する。
    /// ScoreBoard が ClimbZone を集計済みなので、ここで設定すれば CLIMB 倍率・COOPERTITION に即反映される。
    ///
    /// 判定モデル（暫定・外観図とテープ位置で要校正）:
    ///   BRACE は低端24cm〜高端197cm。接触中の機体底面高さ y を 3 等分してゾーン化する。
    ///   非接触になったら一定の猶予後 None に戻す（G21静定の簡易対応）。
    /// </summary>
    public sealed class RobotClimb : MonoBehaviour
    {
        public float releaseGrace = 1.0f; // 非接触後にゾーンを保持する猶予[s]

        private RobotActor _actor;
        private bool _touching;
        private float _sinceTouch;

        // BRACE の高さ帯（FieldConstants より）。低端〜高端を3分割。
        private static readonly float Low = FieldConstants.BraceLowEnd;   // 0.24
        private static readonly float High = FieldConstants.BraceHighEnd; // 1.97
        private static float Band => (High - Low) / 3f;

        private void Awake() => _actor = GetComponent<RobotActor>();

        private void Update()
        {
            if (!_touching)
            {
                _sinceTouch += Time.deltaTime;
                if (_sinceTouch >= releaseGrace) _actor.ClimbZone = ClimbZone.None;
            }
            _touching = false; // 次の FixedUpdate 群で OnCollisionStay により再セットされる
        }

        private void OnCollisionStay(Collision collision)
        {
            if (!IsBrace(collision.collider)) return;
            _touching = true;
            _sinceTouch = 0f;

            float y = transform.position.y; // 機体基準高さ
            _actor.ClimbZone = ZoneForHeight(y);
        }

        private static bool IsBrace(Collider col)
        {
            // FieldBuilder が brace を id "brace_*" の名前で生成している前提。
            return col != null && col.gameObject.name.StartsWith("brace");
        }

        private static ClimbZone ZoneForHeight(float y)
        {
            if (y < Low) return ClimbZone.Contact;          // 接触のみ（まだ登っていない）
            if (y < Low + Band) return ClimbZone.Zone1;
            if (y < Low + 2f * Band) return ClimbZone.Zone2;
            return ClimbZone.Zone3;
        }
    }
}
