#!/usr/bin/env python3
import gzip
import json
import sys
from collections import Counter
from pathlib import Path


def open_text(path: Path):
    if path.suffix == ".gz":
        return gzip.open(path, "rt", encoding="utf-8")
    return path.open("rt", encoding="utf-8")


def validate(path: Path):
    counts = Counter()
    last_seq = None
    last_ns = None
    seq_gaps = 0
    time_reversals = 0
    parse_errors = 0
    duplicate_adds = 0
    unknown_replaces = 0
    unknown_cancels = 0
    orders = {}
    first = None
    last = None

    with open_text(path) as f:
        for line_no, line in enumerate(f, 1):
            try:
                e = json.loads(line)
            except Exception as exc:
                parse_errors += 1
                print(f"PARSE ERROR line {line_no}: {exc}", file=sys.stderr)
                continue

            seq = e.get("seq")
            if last_seq is not None and seq != last_seq + 1:
                seq_gaps += 1
                print(f"SEQ GAP line {line_no}: {last_seq} -> {seq}", file=sys.stderr)
            last_seq = seq

            ns = e.get("market_ns")
            if isinstance(ns, int) and ns > 0:
                if first is None:
                    first = ns
                last = ns
                if last_ns is not None and ns < last_ns:
                    time_reversals += 1
                last_ns = ns

            et = e.get("event", "UNKNOWN")
            counts[et] += 1

            if et == "MBO_ADD":
                oid = e.get("order_id")
                if oid in orders:
                    duplicate_adds += 1
                orders[oid] = (e.get("side"), e.get("price_level"), e.get("size"))
            elif et == "MBO_REPLACE":
                oid = e.get("order_id")
                if oid not in orders:
                    unknown_replaces += 1
                else:
                    side, _, _ = orders[oid]
                    orders[oid] = (side, e.get("price_level"), e.get("size"))
            elif et == "MBO_CANCEL":
                oid = e.get("order_id")
                if oid not in orders:
                    unknown_cancels += 1
                else:
                    del orders[oid]

    valid = parse_errors == 0 and seq_gaps == 0
    print(json.dumps({
        "file": str(path),
        "valid_basic_stream": valid,
        "records": sum(counts.values()),
        "event_counts": dict(counts),
        "seq_gaps": seq_gaps,
        "time_reversals_observed": time_reversals,
        "parse_errors": parse_errors,
        "duplicate_adds_reconstructed": duplicate_adds,
        "unknown_replaces_reconstructed": unknown_replaces,
        "unknown_cancels_reconstructed": unknown_cancels,
        "orders_open_at_end": len(orders),
        "first_market_ns": first,
        "last_market_ns": last,
    }, indent=2))
    return 0 if valid else 2


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} <export.ndjson.gz>", file=sys.stderr)
        raise SystemExit(64)
    raise SystemExit(validate(Path(sys.argv[1])))
