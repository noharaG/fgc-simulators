using FGC.Robot;
using UnityEngine;

namespace FGC.App
{
    /// <summary>
    /// 「シュートで放たれた球」を表す一時マーカー（次フェーズ：飛来球の得点化）。
    /// 撃ったロボ（報酬帰属用）と有効期限を保持し、格納ゾーンに入ると StorageTrigger が捕捉して得点にする。
    /// 寿命切れ／球がプールへ戻る（非アクティブ化）と自動的に外れ、放出球(spill)を誤って得点化しない。
    /// </summary>
    public sealed class ShotBall : MonoBehaviour
    {
        public RobotActor shooter;
        public float ttl = 3.0f; // この時間内にゴールへ入れば得点（撃ったと見なす窓）

        public void Arm(RobotActor s, float lifetime)
        {
            shooter = s;
            ttl = lifetime;
        }

        private void Update()
        {
            ttl -= Time.deltaTime;
            if (ttl <= 0f) Destroy(this);
        }

        private void OnDisable()
        {
            // 球がプール退避（非アクティブ）された瞬間にマーカーを除去（プール再利用時の誤判定防止）。
            Destroy(this);
        }
    }
}
