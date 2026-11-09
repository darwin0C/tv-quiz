#!/usr/bin/env python3
"""
把 bank/source/ 下的题库（小学语数英题库收集项目的输出）转换成电视应用用的题库：
app/src/main/assets/bank.json

电视只能用遥控器方向键选择，所以所有填空题都转换成 4 选 1：
- 整数、小数：在正确答案附近生成干扰项
- "6,9" 这类多空数字：逐个数字微调生成干扰项
- 文字答案（古诗句、口诀、英语字母）：从同一课的其他答案里取干扰项；英语单个字母用其他字母
转换不了（干扰项不足 1 个）的题跳过，并写入 bank/build_report.txt。

用法：python3 tools/build_bank.py
"""
import hashlib
import json
import random
import re
import string
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "bank" / "source"
OUT = ROOT / "app" / "src" / "main" / "assets" / "bank.json"
REPORT = ROOT / "bank" / "build_report.txt"
BOOKS = ["一年级上册", "一年级下册", "二年级上册", "二年级下册", "三年级上册", "三年级下册"]


def rng_for(qid: str) -> random.Random:
    # 每道题固定随机种子，重复运行结果一致
    return random.Random(int(hashlib.md5(qid.encode()).hexdigest()[:8], 16))


def int_distractors(a: int, r: random.Random) -> list:
    pool = [a + d for d in (-3, -2, -1, 1, 2, 3, 10, -10) if a + d >= 0]
    r.shuffle(pool)
    return [str(x) for x in pool[:3]]


def dec_distractors(a: str, r: random.Random) -> list:
    places = len(a.split(".")[1]) if "." in a else 0
    v = float(a)
    step = 10 ** -places if places else 1
    pool = set()
    for d in (-3, -2, -1, 1, 2, 3, 10, -10):
        x = round(v + d * step, places)
        if x >= 0:
            pool.add(f"{x:.{places}f}")
    pool.discard(a)
    pool = sorted(pool)
    r.shuffle(pool)
    return pool[:3]


def multi_int_distractors(a: str, r: random.Random) -> list:
    parts = [int(p) for p in a.split(",")]
    out = set()
    if len(set(parts)) > 1:
        out.add(",".join(str(p) for p in reversed(parts)))
    tries = 0
    while len(out) < 3 and tries < 50:
        tries += 1
        p = parts[:]
        i = r.randrange(len(p))
        p[i] = max(0, p[i] + r.choice((-2, -1, 1, 2)))
        s = ",".join(map(str, p))
        if s != a:
            out.add(s)
    return list(out)[:3]


def letter_distractors(a: str, r: random.Random) -> list:
    pool = [c for c in string.ascii_lowercase if c != a.lower()]
    r.shuffle(pool)
    return pool[:3]


def convert(q: dict, lesson_answers: dict) -> dict | None:
    r = rng_for(q["id"])
    ans = str(q["answer"]).strip()
    opts = [str(o) for o in q.get("options") or []]

    if q["type"] == "choice" and opts:
        if ans not in opts:
            return None
    else:
        if re.fullmatch(r"\d+", ans):
            d = int_distractors(int(ans), r)
        elif re.fullmatch(r"\d+\.\d+", ans):
            d = dec_distractors(ans, r)
        elif re.fullmatch(r"\d+(,\d+)+", ans):
            d = multi_int_distractors(ans, r)
        elif re.fullmatch(r"[A-Za-z]", ans):
            d = letter_distractors(ans, r)
        else:
            same = [x for x in lesson_answers[(q["book"], q["lesson"], q["subject"])] if x != ans]
            same = sorted(set(same))
            r.shuffle(same)
            d = same[:3]
        d = [x for x in dict.fromkeys(d) if x != ans]
        if not d:
            return None
        opts = d + [ans]
        r.shuffle(opts)

    return {
        "id": q["id"],
        "sub": q["subject"],
        "book": q["book"],
        "ui": q["unit_index"],
        "u": q["unit"],
        "l": q["lesson"],
        "t": q["type"],  # 原题型：choice / fill
        "q": q["stem"],
        "a": ans,
        "o": [o.replace(",", "，") if re.fullmatch(r"\d+(,\d+)+", o) else o for o in opts],
        **({"x": q["explain"]} if q.get("explain") else {}),
    }


def main():
    all_q = []
    for book in BOOKS:
        f = SRC / f"{book}.json"
        if not f.exists():
            continue
        for q in json.loads(f.read_text("utf-8")):
            q["book"] = book
            all_q.append(q)

    lesson_answers = defaultdict(list)
    for q in all_q:
        if q["type"] == "fill":
            lesson_answers[(q["book"], q["lesson"], q["subject"])].append(str(q["answer"]).strip())

    out, skipped = [], []
    for q in all_q:
        c = convert(q, lesson_answers)
        if c:
            # 多空答案也按显示格式比较
            if re.fullmatch(r"\d+(,\d+)+", c["a"]):
                c["a"] = c["a"].replace(",", "，")
            out.append(c)
        else:
            skipped.append(q)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":")), "utf-8")

    stats = Counter((q["book"], q["sub"]) for q in out)
    opt_count = Counter(len(q["o"]) for q in out)
    lines = [f"共 {len(all_q)} 题，转换 {len(out)} 题，跳过 {len(skipped)} 题", ""]
    lines += [f"{b} {s}: {n}" for (b, s), n in sorted(stats.items())]
    lines += ["", "选项个数分布: " + ", ".join(f"{k} 个选项 {v} 题" for k, v in sorted(opt_count.items())), ""]
    lines += ["跳过的题："] + [f"  {q['id']}  {q['stem'][:40]!r} -> {q['answer']!r}" for q in skipped]
    REPORT.write_text("\n".join(lines) + "\n", "utf-8")
    print("\n".join(lines[:12 + len(stats)]))
    print(f"\n输出 {OUT.relative_to(ROOT)} ({OUT.stat().st_size // 1024} KB)，报告 {REPORT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
