using FGC.Ball;
using FGC.Core;
using FGC.Robot;
using UnityEngine;

namespace FGC.App
{
    /// <summary>
    /// 格納ゾーン（PLAN.md §3.6・次フェーズ②）。SUPPRESSION(赤/青) / EXTINGUISHER に置く isTrigger 体積。
    /// シュートで飛来した球が入ると得点化する。
    /// SUPPRESSION は同盟一致のロボのみ受け付け（リージョン得点）。EXTINGUISHER は全同盟可（GLOBAL）。
    /// ★持ち込み格納（保持球を持つロボがゾーンに入ると自動排出）は廃止(2026-06-13 ユーザー要望:
    /// ゲームから完全削除)。得点経路はシュートのみ。
    /// </summary>
    [RequireComponent(typeof(BoxCollider))]
    public sealed class StorageTrigger : MonoBehaviour
    {
        public WildfireManager wildfire;
        public StorageKind kind = StorageKind.Extinguisher;
        public bool allianceRestricted = true; // SUPPRESSIONはtrue、EXTINGUISHERはfalse
        public AllianceColor acceptAlliance = AllianceColor.Red;

        /// <summary>1球格納するたびに発火（撃ったロボ, 格納種別）。ML報酬・演出に使う。</summary>
        public System.Action<RobotActor, StorageKind> OnDeposited;

        private void Reset()
        {
            GetComponent<BoxCollider>().isTrigger = true;
        }

        private void OnTriggerStay(Collider other)
        {
            if (wildfire == null) return;

            // シュートで飛来した球がゴールに入った場合＝得点化
            var shot = other.GetComponent<ShotBall>();
            if (shot != null) TryCaptureShot(shot);
        }

        /// <summary>飛来シュート球の捕捉。同盟制限を満たせば格納＋撃った主へ報酬帰属。</summary>
        private void TryCaptureShot(ShotBall shot)
        {
            var shooter = shot.shooter;
            if (allianceRestricted && (shooter == null || shooter.alliance != acceptAlliance)) return;

            if (wildfire.CollectBall(shot.gameObject)) // 場から回収
            {
                wildfire.DepositToStorage(kind, 1);
                if (shooter != null) OnDeposited?.Invoke(shooter, kind); // 報酬帰属
            }
        }

        /// <summary>生成用ヘルパ：指定位置にゾーンを作る。</summary>
        public static StorageTrigger Create(Transform parent, string name, Vector3 pos, Vector3 size,
            WildfireManager wf, StorageKind kind, bool allianceRestricted, AllianceColor alliance)
        {
            var go = new GameObject(name);
            go.transform.SetParent(parent, false);
            go.transform.localPosition = pos;
            var box = go.AddComponent<BoxCollider>();
            box.isTrigger = true;
            box.size = size;
            var st = go.AddComponent<StorageTrigger>();
            st.wildfire = wf;
            st.kind = kind;
            st.allianceRestricted = allianceRestricted;
            st.acceptAlliance = alliance;
            return st;
        }
    }
}
