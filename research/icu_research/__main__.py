"""CLI: python -m icu_research report --export DIR --out DIR | synth --out DIR [--n N --seed S --scenario signal|null]"""
import argparse
import sys

from . import report, synth


def main(argv=None):
    p = argparse.ArgumentParser(prog="icu_research")
    sub = p.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("report", help="分析导出目录并生成 results.json 与 report.md")
    r.add_argument("--export", required=True)
    r.add_argument("--out", required=True)
    s = sub.add_parser("synth", help="生成合成导出（仅测试用）")
    s.add_argument("--out", required=True)
    s.add_argument("--n", type=int, default=1200)
    s.add_argument("--seed", type=int, default=1)
    s.add_argument("--scenario", choices=["signal", "null"], default="signal")
    a = p.parse_args(argv)
    if a.cmd == "synth":
        print(synth.write(a.out, a.n, a.seed, a.scenario))
        return 0
    res = report.run(a.export, a.out)
    for k, v in res["sections"].items():
        print(f"{k}: {v['status']}")
    return 1 if any(v["status"] == "error" for v in res["sections"].values()) else 0


if __name__ == "__main__":
    sys.exit(main())
