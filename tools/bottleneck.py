#!/usr/bin/env python3
"""Summarize a private parked benchmark JSONL; Python standard library only.

All times describe phone stream delivery. Never subtract local-command medians
and call the remainder ECU latency. No CAN/radio/UART wire timestamps exist here.
"""
import argparse
import base64
from collections import Counter, defaultdict
import json
import math
from pathlib import Path
import re
import statistics


def events(path):
    """Ignore only an interrupted final line, not interior corruption."""
    with Path(path).open(encoding="utf-8") as stream:
        previous = stream.readline()
        while previous:
            following = stream.readline()
            try:
                yield json.loads(previous)
            except json.JSONDecodeError:
                if following or previous.endswith("\n"):
                    raise
            previous = following


def stats(values):
    values = sorted(values)
    if not values:
        return {"n": 0, "median": None, "p10": None, "p90": None, "min": None, "max": None}
    def percentile(p):
        x = (len(values)-1)*p
        low = math.floor(x)
        return values[low] + (values[math.ceil(x)]-values[low])*(x-low)
    return dict(n=len(values), median=statistics.median(values), p10=percentile(.1),
                p90=percentile(.9), min=values[0], max=values[-1])


def frame(line, header):
    """Known 11-bit reply IDs only: an echoed hex command is not a CAN frame."""
    text = re.sub(r"\s+", "", line).upper()
    if not re.fullmatch(r"[0-9A-F]{3}(?:[0-9A-F]{2}){1,8}", text):
        return False
    if not header:
        return False
    reply = int(text[:3], 16)
    expected = int(header, 16)
    if expected == 0x7DF:
        return 0x7E8 <= reply <= 0x7EF
    return reply == expected+8


def summarize(source):
    pending = {}
    measurements = []
    phases = {}
    local = defaultdict(list)
    sync = []
    outcomes = Counter()
    errors = 0
    rx_bytes = 0
    complete = False
    clean_end = False
    detailed = False
    header = None
    headers = True
    latest = None
    current_phase = None
    scheduler = defaultdict(list)

    def finish(req):
        if "metrics" in req:
            return req["metrics"]
        tx = req["tx"]
        timing = req.get("timing", {})
        start = timing.get("write_start_ns") or tx.get("mono_ns")
        first = timing.get("first_read_ns") or req.get("first_read")
        last = timing.get("last_read_ns") or req.get("last_read")
        m = dict(phase=tx.get("phase", ""), command=tx["command"],
                 outcome=req.get("outcome", "incomplete"), ascii_bytes=req.get("bytes", 0),
                 stream_chunks=req.get("chunks", 0), phone_can_lines=len(req.get("lines", [])),
                 timing_basis="read_end_ns" if req.get("detailed") else "rx event timestamp")
        def delta(key, a, b):
            if a is not None and b is not None:
                m[key] = (b-a)/1e6
        delta("write_to_first_read_ms", start, first)
        delta("write_to_prompt_ms", start, last if req.get("prompt") else None)
        lines = req.get("lines", [])
        if lines:
            delta("write_to_first_can_line_ms", start, lines[0])
            delta("first_to_last_can_line_ms", lines[0], lines[-1])
            delta("last_can_line_to_prompt_ms", lines[-1], last if req.get("prompt") else None)
        for key in ("rx_log_ns", "max_delivery_to_dequeue_ns", "tx_log_and_prepare_ns", "request_end_log_ns", "post_prompt_ns"):
            if key in timing:
                m[key.removesuffix("_ns")+"_ms"] = timing[key]/1e6
        delta("write_flush_ms", timing.get("write_start_ns"), timing.get("write_end_ns"))
        req["metrics"] = m
        measurements.append(m)
        return m

    for e in source:
        kind = e.get("type")
        rid = e.get("request_id")
        if kind == "tx":
            command = e["command"]
            if command.startswith("ATSH"):
                header = command[4:]
            if command in ("ATH0", "ATH1"):
                headers = command == "ATH1"
            pending[rid] = dict(tx=e, header=header, headers=headers, tail="", lines=[])
            latest = rid
        elif kind == "rx":
            raw = base64.b64decode(e["base64"], validate=True)
            rx_bytes += len(raw)
            req = pending.get(rid)
            if req is None:
                continue
            at = e.get("read_end_ns") or e.get("mono_ns")
            if at is None:
                continue
            req["detailed"] = bool(e.get("read_end_ns"))
            req.setdefault("first_read", at)
            req["last_read"] = at
            req["bytes"] = req.get("bytes", 0)+len(raw)
            req["chunks"] = req.get("chunks", 0)+1
            # A partial line is timestamped when its terminator reaches the phone.
            pieces = re.split(r"[\r\n>]", req["tail"]+raw.decode("ascii", errors="replace"))
            req["tail"] = pieces.pop()
            if req["headers"]:
                req["lines"].extend(at for line in pieces if frame(line, req["header"]))
            req["prompt"] = req.get("prompt", False) or b">" in raw
        elif kind == "request_end":
            outcomes[e.get("outcome", "unknown")] += 1
            if rid in pending:
                pending[rid]["outcome"] = e.get("outcome", "unknown")
        elif kind == "request_error":
            errors += 1
            if rid in pending:
                pending[rid].setdefault("outcome", "io_error")
        elif kind == "request_timing":
            detailed = True
            req = pending.get(rid)
            if req is not None:
                req["timing"] = e
                m = finish(req)
                if m["command"].startswith("AT") and "write_to_prompt_ms" in m:
                    local[m["command"]].append(m["write_to_prompt_ms"])
        elif kind == "benchmark_sample":
            req = pending.get(latest)
            if req and req["tx"].get("command") == e.get("wire_command") and req["tx"].get("phase") == "bench:"+e["id"]:
                m = finish(req)
                m.update(benchmark_id=e["id"], warmup=e["warmup"], valid=e["valid"],
                         logical_command=e["command"], header_ms=e["header_ms"],
                         query_ms=e["query_ms"], cycle_ms=e["cycle_ms"])
        elif kind == "benchmark_phase_start":
            current_phase = e["id"]
            phases.setdefault(current_phase, {})["start"] = e
        elif kind in ("benchmark_phase_end", "benchmark_phase_skip", "benchmark_phase_abort"):
            phases.setdefault(e["id"], {})[kind.removeprefix("benchmark_phase_")] = e
        elif kind == "dashboard_poll_timing" and current_phase == "scheduler":
            scheduler[e["channel"]].append(e)
        elif kind == "log_sync":
            sync.append((e["sync_end_ns"]-e["sync_start_ns"])/1e6)
        elif kind == "benchmark_complete":
            complete = True
        elif kind == "session_end":
            clean_end = True

    for req in pending.values():
        finish(req)
    # No raw payloads, addresses, VINs or device identifiers appear in the report.
    grouped = defaultdict(list)
    all_grouped = defaultdict(list)
    for m in measurements:
        if m.get("benchmark_id"):
            all_grouped[m["benchmark_id"]].append(m)
            if not m.get("warmup", True):
                grouped[m["benchmark_id"]].append(m)
    report = []
    metrics = ("header_ms", "query_ms", "cycle_ms", "ascii_bytes", "stream_chunks", "phone_can_lines",
               "write_to_first_read_ms", "write_to_prompt_ms", "write_to_first_can_line_ms",
               "first_to_last_can_line_ms", "last_can_line_to_prompt_ms", "rx_log_ms",
               "max_delivery_to_dequeue_ms", "tx_log_and_prepare_ms", "request_end_log_ms", "write_flush_ms")
    for phase_id, lifecycle in phases.items():
        samples = grouped[phase_id]
        valid = [s for s in samples if s["valid"]]
        entry = dict(id=phase_id, label=lifecycle.get("start", {}).get("label", phase_id),
                     attempted_samples=len(samples), valid_samples=len(valid),
                     invalid_samples=len(samples)-len(valid),
                     warmup_samples=len(all_grouped[phase_id])-len(samples),
                     invalid_including_warmup=sum(not s["valid"] for s in all_grouped[phase_id]),
                     completed=lifecycle.get("end", {}).get("completed", False),
                     skipped=lifecycle.get("skip", {}).get("reason"), aborted=lifecycle.get("abort", {}).get("reason"),
                     outcomes=dict(Counter(s["outcome"] for s in all_grouped[phase_id])),
                     all_attempt_query_ms=stats([s["query_ms"] for s in samples]))
        entry["valid_metrics"] = {k: stats([s[k] for s in valid if k in s]) for k in metrics}
        report.append(entry)
    return dict(benchmark_complete=complete, clean_session_end=clean_end, detailed_transport_timing=detailed,
                total_rx_bytes=rx_bytes, request_outcomes=dict(outcomes), request_errors=errors, phases=report,
                adapter_commands_ms={k: stats(v) for k, v in sorted(local.items())}, log_sync_ms=stats(sync),
                scheduler={k: dict(polls=len(v), query_ms=stats([p["query_ms"] for p in v]),
                                   header_ms=stats([p["header_ms"] for p in v]), late_ms=stats([p["late_ms"] for p in v]))
                           for k, v in scheduler.items()},
                limits=["Phone RFCOMM delivery timestamps; not CAN arrival, RF packets or UART bit timing.",
                        "A stream read may combine many CAN lines or fragment one line; zero line gaps do not mean zero CAN spacing.",
                        "Invalid replies and warmups are excluded from valid latency summaries, but failures and partial phases remain listed.",
                        "Do not subtract adapter-only medians and label the remainder ECU latency.",
                        "Wire bitrate, CAN arbitration/stuffing, ECU timestamps and internal dongle queues remain unobserved."])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("--json", action="store_true", help="emit full report as JSON")
    args = parser.parse_args()
    report = summarize(events(args.capture))
    if args.json:
        print(json.dumps(report, indent=2))
        return
    print(f"Benchmark complete: {report['benchmark_complete']}; clean end: {report['clean_session_end']}; detailed timing: {report['detailed_transport_timing']}")
    print("Phase                                      Valid/tries   Median query ms   Median header ms")
    for p in report["phases"]:
        def number(key):
            value = p["valid_metrics"][key]["median"]
            return "—" if value is None else f"{value:.2f}"
        print(f"{p['id']:42} {p['valid_samples']:4}/{p['attempted_samples']:<4} {number('query_ms'):>15} {number('header_ms'):>18}")
        if p["skipped"] or p["aborted"]:
            print("  "+str(p["skipped"] or p["aborted"]))
    for limitation in report["limits"]:
        print("* "+limitation)


if __name__ == "__main__":
    main()
