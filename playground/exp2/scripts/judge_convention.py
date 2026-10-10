#!/usr/bin/env python3
"""보충 판정 — 스킬이 겨냥하는 '컨벤션 준수'를 run별로 비교한다 (2차 run 9개, 숨긴 테스트가 보지 않는 축).

- 기준: 실행 당시 하네스(f75ee6d)의 `spring-boot` 스킬 핵심 원칙·참조 문서 체크리스트에서
  이 기능에 적용 가능한 항목만 뽑은 Q1~Q20. 보안(비밀번호 해싱)·Page 목록 등 이 기능과 무관한 항목은 뺐다.
- 입력: run의 src/main 전체 + build.gradle. 테스트 코드는 보지 않는다.
- 블라인드: run 이름을 무작위 라벨(Y1~Y9)로 바꾸고, 하네스·스킬 흔적 단어를 지운다. 판정자는 기준이 스킬에서 왔다는 것도 모른다.
- 반복: 라벨마다 PASSES회 독립 판정 → 항목별 다수결. 준수율 = 준수 / (준수 + 위반), 해당 없음은 제외.

출력: results/convention/{label}-p{n}.json, results/convention/blind-key.json, results/convention.json
사용법: judge_convention.py [--passes 3] [--model claude-opus-5-5]
"""
import argparse
import json
import os
import random
import re
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor

PLAYGROUND = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(PLAYGROUND, "results", "convention")
RUNS = [f"{g}-{i}" for g in "ABC" for i in (1, 2, 3)]
ITEMS = {
    "Q1": "import가 jakarta.* 이다 (javax.* 없음).",
    "Q2": "모든 컴포넌트가 @SpringBootApplication 클래스의 베이스 패키지 아래에 있다.",
    "Q3": "build.gradle에 불필요한 의존성 추가가 없다 (스타터로 충분한 것을 개별 추가하지 않음).",
    "Q4": "의존 방향이 web → service → repository 단방향이다. 서비스·도메인·영속성 코드가 웹 계층(컨트롤러·웹 DTO·HttpServletRequest 등)에 의존하지 않는다.",
    "Q5": "생성자 주입만 쓴다. @Autowired 필드 주입이 없고 주입 필드는 final이다.",
    "Q6": "요청·응답 바디가 record DTO이고, JPA 엔티티(또는 영속성 모델)를 요청·응답에 직접 쓰지 않는다.",
    "Q7": "요청 DTO에 Bean Validation 제약 애노테이션이 있고 컨트롤러 파라미터에 @Valid(@Validated)가 붙어 있다.",
    "Q8": "컨트롤러가 얇다 — 비즈니스 로직, @Transactional, 리포지토리 직접 접근이 없다.",
    "Q9": "@Transactional이 서비스 계층에 있다 (컨트롤러·리포지토리에 두지 않음). 읽기 전용 메서드는 readOnly = true다.",
    "Q10": "서비스가 HTTP를 모른다 — 서비스에서 HttpStatus·ResponseEntity·ResponseStatusException을 쓰지 않고 도메인 예외를 던진다.",
    "Q11": "예외 → HTTP 상태 매핑이 @RestControllerAdvice(또는 그에 준하는 한 곳)에 모여 있다.",
    "Q12": "에러 응답이 Spring의 ProblemDetail(RFC 9457, application/problem+json)로 만들어진다.",
    "Q13": "검증 실패(400) 응답에 필드별 오류 정보가 들어 있다.",
    "Q14": "처리하지 못한 예외(500) 응답에 스택 트레이스·예외 메시지 같은 내부 상세가 노출되지 않는다.",
    "Q15": "@ManyToOne·@OneToOne 연관이 있다면 fetch = LAZY다. 연관 매핑이 없으면 해당 없음.",
    "Q16": "연관을 함께 읽는 목록 조회가 있다면 N+1 대책(fetch join·@EntityGraph·일괄 조회)이 있고, 지연 로딩을 트랜잭션 밖에서 접근하지 않는다. 그런 조회가 없으면 해당 없음.",
    "Q17": "DB 자격증명·외부 URL 같은 설정·비밀값이 코드에 하드코딩되지 않고 설정/환경 변수에서 주입된다.",
    "Q18": "spring.jpa.hibernate.ddl-auto가 validate 또는 none이다 (JPA를 쓰지 않으면 해당 없음).",
    "Q19": "spring.jpa.open-in-view가 false다 (JPA를 쓰지 않으면 해당 없음).",
    "Q20": "여러 개의 관련 설정값(예: 외부 연동 URL·타임아웃·TTL)을 @ConfigurationProperties로 타입 안전하게 묶어 바인딩한다. 흩어진 @Value만 쓰면 위반. 커스텀 설정이 하나뿐이면 해당 없음.",
}
REDACT = [(re.compile(p, re.I), r) for p, r in [
    (r"spring-boot\s*스킬|스킬|skill", "-"), (r"test-writer|feature-pm|backend-(designer|impl)|boundary-verifier", "작업자"),
    (r"Phase\s*\d", "단계"), (r"_workspace", "docs"), (r"orchestrat\w*|harness|하네스", "-"),
]]
SYSTEM = "너는 Spring Boot 코드 리뷰어다. 주어진 코드에 있는 것만 근거로 판정한다. 추측하지 않는다."
INSTRUCTION = """아래 [점검 항목] Q1 ~ Q20 각각에 대해 [코드]가 지키는지 판정해줘.

판정 값
- compliant: 항목을 지킨다.
- violation: 항목을 어기는 코드가 하나 이상 있다.
- not_applicable: 항목이 다루는 대상이 코드에 없다 (항목에 해당 없음 조건이 적혀 있을 때만).

evidence에는 근거 파일 경로(와 클래스·메서드)를 한 줄로 적는다. violation이면 어기는 위치를 적는다.

[점검 항목]
{items}

[코드]
{code}
"""
SCHEMA = {
    "type": "object",
    "properties": {q: {
        "type": "object",
        "properties": {
            "verdict": {"type": "string", "enum": ["compliant", "violation", "not_applicable"]},
            "evidence": {"type": "string"},
        },
        "required": ["verdict", "evidence"],
    } for q in ITEMS},
    "required": list(ITEMS),
}


def code_text(run):
    root = os.path.join(PLAYGROUND, "runs", run)
    parts = [f"=== build.gradle ===\n{open(os.path.join(root, 'build.gradle')).read()}"]
    for dirpath, _, files in sorted(os.walk(os.path.join(root, "src", "main"))):
        for f in sorted(files):
            if not f.endswith((".java", ".kt", ".yml", ".yaml", ".properties", ".sql")):
                continue
            path = os.path.join(dirpath, f)
            body = open(path, errors="replace").read()
            for pat, rep in REDACT:
                body = pat.sub(rep, body)
            parts.append(f"=== {os.path.relpath(path, root)} ===\n{body}")
    return "\n\n".join(parts)


def judge(label, run, n, model, workdir):
    out = os.path.join(OUT, f"{label}-p{n}.json")
    if os.path.exists(out):
        return label, n, json.load(open(out))
    items = "\n".join(f"- {q}. {t}" for q, t in ITEMS.items())
    prompt = INSTRUCTION.format(items=items, code=code_text(run))
    cmd = ["claude", "-p", "--model", model, "--tools", "", "--system-prompt", SYSTEM,
           "--json-schema", json.dumps(SCHEMA), "--output-format", "json",
           "--no-session-persistence", "--strict-mcp-config"]
    res = subprocess.run(cmd, input=prompt, capture_output=True, text=True, cwd=workdir, timeout=1800)
    if res.returncode != 0:
        raise RuntimeError(f"{label} p{n}: exit {res.returncode}: {res.stderr[-500:]}")
    envelope = json.loads(res.stdout)
    verdict = envelope.get("structured_output")
    if verdict is None:
        verdict = json.loads(re.search(r"\{.*\}", envelope["result"], re.S).group(0))
    data = {"verdict": verdict, "cost_usd": envelope.get("total_cost_usd")}
    json.dump(data, open(out, "w"), ensure_ascii=False, indent=2)
    return label, n, data


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--passes", type=int, default=3)
    ap.add_argument("--seed", type=int, default=20261011)
    ap.add_argument("--model", default="claude-opus-5-5")
    a = ap.parse_args()
    os.makedirs(OUT, exist_ok=True)

    key_path = os.path.join(OUT, "blind-key.json")
    if os.path.exists(key_path):
        key = json.load(open(key_path))
    else:
        shuffled = RUNS[:]
        random.Random(a.seed).shuffle(shuffled)
        key = {f"Y{i + 1}": r for i, r in enumerate(shuffled)}
        json.dump(key, open(key_path, "w"), indent=2)

    workdir = tempfile.mkdtemp(prefix="judge-convention-")
    jobs = [(label, run, n) for label, run in key.items() for n in range(1, a.passes + 1)]
    raw = {}
    with ThreadPoolExecutor(max_workers=4) as ex:
        for label, n, data in ex.map(lambda j: judge(j[0], j[1], j[2], a.model, workdir), jobs):
            raw.setdefault(label, {})[n] = data
            print(f"[convention] {label} p{n} done (${data.get('cost_usd') or 0:.2f})", file=sys.stderr)

    result = {}
    for label, run in key.items():
        passes = [raw[label][n]["verdict"] for n in sorted(raw[label])]
        items = {}
        for q in ITEMS:
            votes = [p[q]["verdict"] for p in passes]
            v = max(("compliant", "violation", "not_applicable"), key=votes.count)
            items[q] = {"verdict": v, "votes": votes,
                        "evidence": next(p[q]["evidence"] for p in passes if p[q]["verdict"] == v)}
        ok = sum(i["verdict"] == "compliant" for i in items.values())
        bad = sum(i["verdict"] == "violation" for i in items.values())
        result[run] = {"label": label, "compliant": ok, "violation": bad, "rate": round(ok / (ok + bad), 4),
                       "items": items}
    json.dump(result, open(os.path.join(PLAYGROUND, "results", "convention.json"), "w"), ensure_ascii=False, indent=2)

    print("| run | 준수 | 위반 | 준수율 | 위반 항목 |\n|---|---|---|---|---|")
    for run in RUNS:
        r = result[run]
        bad = " ".join(q for q, i in r["items"].items() if i["verdict"] == "violation")
        print(f"| {run} | {r['compliant']} | {r['violation']} | {r['rate']:.0%} | {bad or '-'} |")
    print("\n| 군 | 준수율 평균 (최소~최대) |\n|---|---|")
    for g in "ABC":
        rates = [result[f"{g}-{i}"]["rate"] for i in (1, 2, 3)]
        print(f"| {g} | {sum(rates) / 3:.0%} ({min(rates):.0%}~{max(rates):.0%}) |")
    print("\n| 항목 | A 위반 | B 위반 | C 위반 |\n|---|---|---|---|")
    for q in ITEMS:
        cnt = [sum(result[f"{g}-{i}"]["items"][q]["verdict"] == "violation" for i in (1, 2, 3)) for g in "ABC"]
        if any(cnt):
            print(f"| {q} | {cnt[0]}/3 | {cnt[1]}/3 | {cnt[2]}/3 |")
    total = sum(d.get("cost_usd") or 0 for r in raw.values() for d in r.values())
    print(f"\n총 비용 ${total:.2f}")


if __name__ == "__main__":
    main()
