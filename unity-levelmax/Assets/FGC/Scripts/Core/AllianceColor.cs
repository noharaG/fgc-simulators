namespace FGC.Core
{
    /// <summary>同盟（赤/青）。赤=x=7側、青=x=0側（field_obstacles.json の viewpoints 準拠）。</summary>
    public enum AllianceColor
    {
        Red,
        Blue
    }

    /// <summary>BRACE 登攀ゾーン。スコアの CLIMB 倍率に使う。</summary>
    public enum ClimbZone
    {
        None = 0,
        Contact = 1, // 接触のみ
        Zone1 = 2,
        Zone2 = 3,
        Zone3 = 4
    }
}
