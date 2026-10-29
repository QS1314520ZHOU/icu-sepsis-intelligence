"""Direction 2: alert handling — burden, false-alarm share, time to review, shift differences, lead time."""
from collections import Counter

from . import ci, result
from ..io import get
from ..stats import median_iqr, wilson

CATEGORIES = ("已处理", "已知晓，继续观察", "误报：监测干扰", "误报：数据错误", "其他")


def category(reason):
    if not reason:
        return "未填写"
    for c in CATEGORIES:
        if reason.startswith(c):
            return c
    return "其他"


def run(alerts, cohort):
    if not alerts:
        return result("no_data", "无预警记录（系统上线运行后才会积累）")
    n = len(alerts)
    reviewed = [a for a in alerts if a.get("reviews")]
    first_review = [sorted(a["reviews"], key=lambda h: h.get("min") if h.get("min") is not None else 1e12)[0] for a in reviewed]
    cats = Counter(category(h.get("reason")) for h in first_review)
    false_alarm = cats.get("误报：监测干扰", 0) + cats.get("误报：数据错误", 0)
    t_review = [h.get("min") for h in first_review if h.get("min") is not None]
    day = [a for a in alerts if a.get("hourOfDay") is not None and 8 <= a["hourOfDay"] < 20]
    night = [a for a in alerts if a.get("hourOfDay") is not None and not (8 <= a["hourOfDay"] < 20)]

    def rev_time(group):
        out = []
        for a in group:
            ms = [h.get("min") for h in a.get("reviews", []) if h.get("min") is not None]
            if ms:
                out.append(min(ms))
        return median_iqr(out)
    bed_days = sum((get(r, "outcome.icuLosHours") or 0) for r in cohort) / 24.0
    keys = {r["stayKey"]: r for r in cohort}
    lead = []
    for a in alerts:
        r = keys.get(a.get("stayKey"))
        abx = get(r, "timing.abxFirstMin") if r else None
        if r and a.get("minFromT0") is not None and abx is not None and abx >= a["minFromT0"]:
            lead.append(abx - a["minFromT0"])
    actors = {h.get("actorKey") for a in alerts for h in a.get("reviews", []) if h.get("actorKey")}
    lines = [f"预警 {n} 条，已处置 {len(reviewed)} 条（{ci(wilson(len(reviewed), n))}）。",
             f"误报占已处置的 {ci(wilson(false_alarm, len(first_review)))}。"]
    med = median_iqr(t_review)
    if med[0] is not None:
        lines.append(f"首次处置用时中位数 {med[0]:.0f} 分钟（IQR {med[1]:.0f}–{med[2]:.0f}）。")
    if bed_days > 0:
        lines.append(f"每 100 ICU 床日 {n / bed_days * 100:.1f} 条预警（床日取导出病例的 ICU 住院时长合计，近似）。")
    status = "ok" if len(first_review) >= 30 else "insufficient"
    return result(status, lines, alerts=n, reviewed=len(reviewed), categories=dict(cats), falseAlarm=wilson(false_alarm, len(first_review)),
                  reviewMinutes=med, dayReview=rev_time(day), nightReview=rev_time(night), dayCount=len(day), nightCount=len(night),
                  alertsPer100BedDays=(n / bed_days * 100) if bed_days > 0 else None, alertToAntibioticMin=median_iqr(lead),
                  linkedToAntibiotic=len(lead), reviewers=len(actors))
