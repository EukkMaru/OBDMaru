#!/usr/bin/env python3
"""Inspect or replay OBDMaru JSONL captures. Uses only Python's standard library."""
import argparse
import base64
import collections
import json
import socket
import sys
import time


def load(path):
    events = []
    with open(path, encoding="utf-8") as stream:
        lines = stream.readlines()
    for i, line in enumerate(lines):
        try:
            events.append(json.loads(line))
        except json.JSONDecodeError:
            if i == len(lines) - 1 and not line.endswith("\n"):
                print("Ignoring incomplete last event from interrupted recording", file=sys.stderr)
            else:
                raise
    return events


def summarize(events):
    requests = {e["request_id"]: e for e in events if e["type"] == "tx"}
    outcomes = collections.Counter(e.get("outcome") for e in events if e["type"] == "request_end")
    coverage = collections.defaultdict(collections.Counter)
    for e in events:
        if e["type"] == "request_end":
            tx = requests.get(e["request_id"], {})
            coverage[tx.get("command", "?")][e.get("outcome", "?")] += 1
    interval_stats = {}
    for event in events:
        if event["type"] != "poll_metrics":
            continue
        for source, stats in event.get("intervals", {}).items():
            total = interval_stats.setdefault(source, {"interval_count": 0, "total_ms": 0, "min_ms": stats["min_ms"], "max_ms": stats["max_ms"]})
            total["interval_count"] += stats["samples"]
            total["total_ms"] += stats["mean_ms"] * stats["samples"]
            total["min_ms"] = min(total["min_ms"], stats["min_ms"])
            total["max_ms"] = max(total["max_ms"], stats["max_ms"])
    for stats in interval_stats.values():
        stats["mean_ms"] = stats.pop("total_ms") / stats["interval_count"]
    return {
        "capture_mode": next((e.get("capture_mode") for e in events if e["type"] == "plan"), None),
        "guide_steps": [{"stage": e.get("stage"), "elapsed_seconds": e["elapsed_ns"] / 1e9,
                         "driver_action_confirmed": e.get("driver_action_confirmed", False)}
                        for e in events if e["type"] == "guide_step"],
        "guide_complete": any(e["type"] == "guide_complete" for e in events),
        "speech_events": dict(collections.Counter(e["type"] for e in events if e["type"].startswith("speech_"))),
        "measured_poll_intervals": interval_stats,
        "session": next((e for e in events if e["type"] == "session_start"), None),
        "clean_end": any(e["type"] == "session_end" for e in events),
        "duration_seconds": (events[-1]["elapsed_ns"] - events[0]["elapsed_ns"]) / 1e9 if events else 0,
        "requests": len(requests),
        "raw_rx_bytes": sum(len(base64.b64decode(e["base64"])) for e in events if e["type"] == "rx"),
        "outcomes": outcomes,
        "errors": [e for e in events if e["type"] in ("request_error", "capture_error", "request_abandoned")],
        "discovery": [e for e in events if e["type"] == "discovery"],
        "coverage_by_command": dict(coverage),
    }


def replay(events, speed, output):
    previous = None
    for e in events:
        if e["type"] != "rx":
            continue
        if previous is not None and speed > 0:
            time.sleep(max(0, (e["elapsed_ns"] - previous) / 1e9 / speed))
        output.write(base64.b64decode(e["base64"]))
        output.flush()
        previous = e["elapsed_ns"]


def serve(events, port, speed):
    """Strict chronological ELM replay; commands and adapter settings must match.

    Client sends ASCII command + CR; server returns original RX chunks/timing.
    Missing prompt and negative replies remain missing/negative. Mismatches close
    the connection rather than inventing ECU responses. Restart to replay again.
    """
    exchanges = []
    current = None
    for e in events:
        if e["type"] == "tx":
            current = {"tx": e, "rx": []}
            exchanges.append(current)
        elif e["type"] == "rx" and current and e.get("request_id") == current["tx"]["request_id"]:
            current["rx"].append(e)
    with socket.socket() as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", port))
        listener.listen(1)
        print(f"Strict replay listening on 127.0.0.1:{port}", file=sys.stderr)
        conn, _ = listener.accept()
        with conn:
            conn.settimeout(60)
            pending = bytearray()
            for exchange in exchanges:
                while b"\r" not in pending:
                    chunk = conn.recv(4096)
                    if not chunk:
                        return
                    pending.extend(chunk)
                command, _, pending = pending.partition(b"\r")
                actual = command.decode("ascii").strip().upper().replace(" ", "")
                expected = exchange["tx"]["command"].upper().replace(" ", "")
                if actual != expected:
                    raise ValueError(f"Replay mismatch: expected {expected!r}, received {actual!r}")
                previous = exchange["tx"]["elapsed_ns"]
                for rx in exchange["rx"]:
                    if speed > 0:
                        time.sleep(max(0, (rx["elapsed_ns"] - previous) / 1e9 / speed))
                    conn.sendall(base64.b64decode(rx["base64"]))
                    previous = rx["elapsed_ns"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("file")
    parser.add_argument("--replay", action="store_true", help="emit exact received bytes to stdout")
    parser.add_argument("--serve", type=int, metavar="PORT", help="strict request/response replay on localhost TCP")
    parser.add_argument("--speed", type=float, default=1, help="timing multiplier; 0 means no delays")
    args = parser.parse_args()
    if args.speed < 0:
        parser.error("speed must be nonnegative")
    events = load(args.file)
    if args.serve:
        serve(events, args.serve, args.speed)
    elif args.replay:
        replay(events, args.speed, sys.stdout.buffer)
    else:
        print(json.dumps(summarize(events), indent=2))


if __name__ == "__main__":
    main()
