#!/usr/bin/env python3
"""Validate complete canonical capture integrity and optionally its writer summary.

Generic acquisition validation only. No model, feature, or entry-quality logic.
"""
import argparse
from collections import Counter
from decimal import Decimal
import gzip
import hashlib
import json
from pathlib import Path
import zlib

SCHEMA = "bookmap-orderflow-v0.1"
EVENTS = {"CONTROL", "MBO_ADD", "MBO_REPLACE", "MBO_CANCEL", "TRADE"}


class ValidationError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise ValidationError(message)


def integer(value, name, minimum=0):
    require(type(value) is int and minimum <= value < 2**63, f"invalid {name}")
    return value


def number(value, name):
    require(type(value) in (int, Decimal), f"invalid {name}")
    value = Decimal(value)
    require(value.is_finite(), f"nonfinite {name}")
    return value


def parse(line):
    def pairs(values):
        result = {}
        for key, value in values:
            require(key not in result, f"duplicate JSON key {key}")
            result[key] = value
        return result
    def constant(value):
        raise ValidationError(f"nonfinite JSON value {value}")
    return json.loads(line, parse_float=Decimal, object_pairs_hook=pairs, parse_constant=constant)


def file_hash(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(data)
    return digest.hexdigest()


def inspect(path, summary_path=None):
    """Fail closed. gzip is read to EOF, including CRC32 and ISIZE verification."""
    path = Path(path)
    summary = None
    pips = None
    summary_hash = None
    if summary_path is not None:
        raw = Path(summary_path).read_bytes()
        summary_hash = hashlib.sha256(raw).hexdigest()
        summary = parse(raw)
        require(type(summary) is dict and summary.get("schema") == SCHEMA, "invalid summary schema")
        pips = number(summary.get("pips"), "summary pips")
        require(pips > 0, "pips must be positive")
    source_hash = file_hash(path)
    counts, orders = Counter(), {}
    alias, first, last, last_seq = None, None, 0, 0
    started, stopped, phase = False, False, "HISTORY"
    boundary_markers = 0
    opener = gzip.open if path.suffix == ".gz" else open
    try:
        with opener(path, "rb") as stream:
            for line_no, line in enumerate(stream, 1):
                try:
                    require(line.endswith(b"\n"), "unterminated NDJSON record")
                    row = parse(line)
                    require(type(row) is dict and row.get("schema") == SCHEMA, "unsupported schema")
                    seq = integer(row.get("seq"), "seq", 1)
                    ns = integer(row.get("market_ns"), "market_ns")
                    require(seq == last_seq + 1, "sequence discontinuity")
                    require(not stopped, "event after STOP")
                    require(ns >= last and (ns > 0 or seq == 1), "timestamp reversal/zero clock")
                    instrument = row.get("alias")
                    require(type(instrument) is str and bool(instrument), "invalid alias")
                    alias = alias or instrument
                    require(instrument == alias, "mixed instrument aliases")
                    event = row.get("event")
                    require(type(event) is str and event in EVENTS, "unsupported event")
                    common = {"schema", "seq", "market_ns", "phase", "alias", "event"}
                    if event == "CONTROL":
                        expected = common | {"control", "detail"}
                        require(type(row.get("detail")) is str, "invalid control detail")
                        control = row.get("control")
                        if control == "START":
                            require(seq == 1 and not started and row.get("phase") == "HISTORY", "invalid START")
                            started = True
                        elif control == "REALTIME_START":
                            require(started and phase == "HISTORY" and row.get("phase") == "REALTIME", "invalid REALTIME_START")
                            phase = "REALTIME"
                        elif control == "STOP":
                            stopped = True
                        else:
                            require(False, "unsupported control/TIME_REVERSAL")
                    else:
                        require(ns > 0, "zero market clock")
                        level = number(row.get("price_level"), "price_level")
                        price = number(row.get("price"), "price")
                        require(level == level.to_integral_value(), "nonintegral tick coordinate")
                        if pips is not None:
                            require(price == level * pips, "price/pips mismatch")
                        size = integer(row.get("size"), "size", 0 if event == "TRADE" else 1)
                        expected = common | {"price_level", "price", "size"}
                        if event == "TRADE":
                            expected |= {"aggressor_side", "aggressor_order_id", "passive_order_id",
                                         "execution_start", "execution_end", "otc"}
                            require(row.get("aggressor_side") in ("BUY", "SELL"), "invalid aggressor side")
                            for name in ("aggressor_order_id", "passive_order_id"):
                                require(row.get(name) is None or type(row[name]) is str, f"invalid {name}")
                            for name in ("execution_start", "execution_end", "otc"):
                                require(type(row.get(name)) is bool, f"invalid {name}")
                            if size == 0:
                                require(row["execution_end"] and not row["execution_start"], "invalid zero-size trade marker")
                                boundary_markers += 1
                        else:
                            expected |= {"order_id", "side", "anomaly"}
                            oid, side = row.get("order_id"), row.get("side")
                            require(type(oid) is str and bool(oid), "invalid order ID")
                            require(side in ("BID", "ASK") and row.get("anomaly") is False, "anomalous MBO callback")
                            state = (side, level, size)
                            previous = orders.get(oid)
                            if event == "MBO_ADD":
                                require(previous is None, "duplicate ADD")
                                orders[oid] = state
                            else:
                                require(previous is not None, "unknown REPLACE/CANCEL")
                                require(previous[0] == side, "REPLACE/CANCEL changed side")
                                if event == "MBO_CANCEL":
                                    require(previous == state, "CANCEL state mismatch")
                                    del orders[oid]
                                else:
                                    orders[oid] = state
                    require(set(row) == expected, "missing/extra canonical fields")
                    require(started and row.get("phase") == phase, "invalid lifecycle/phase")
                    counts[event] += 1
                    if ns and first is None:
                        first = ns
                    last, last_seq = ns, seq
                except (ValueError, UnicodeError) as error:
                    raise ValidationError(f"line {line_no}: {error}") from error
    except (OSError, EOFError, zlib.error) as error:
        raise ValidationError(f"capture integrity/read failure: {error}") from error
    require(stopped and first is not None, "incomplete capture: missing STOP or market events")
    require(file_hash(path) == source_hash, "capture changed during validation")
    if summary is not None:
        require(summary.get("writer_ok") is True and summary.get("writer_error") is None, "writer failure")
        require(summary.get("export_mbo") is True and summary.get("export_trades") is True, "incomplete export channels")
        require(summary.get("alias") == alias, "summary alias mismatch")
        if "event_file" in summary:
            require(summary["event_file"] == path.name, "summary event filename mismatch")
        expected_summary = {"total_records": last_seq, "records_persisted": last_seq,
                            "first_market_ns": first, "last_market_ns": last,
                            "orders_open_at_stop": len(orders), "duplicate_adds": 0,
                            "unknown_replaces": 0, "unknown_cancels": 0, "time_reversals": 0}
        for event, field in (("MBO_ADD", "mbo_add_received"), ("MBO_REPLACE", "mbo_replace_received"),
                             ("MBO_CANCEL", "mbo_cancel_received"), ("TRADE", "trades_received")):
            expected_summary[field] = counts[event]
        for name, value in expected_summary.items():
            integer(summary.get(name), f"summary {name}")
            require(summary[name] == value, f"summary mismatch: {name}")
        if summary.get("addon_version") == "0.4.0":
            require(path.suffix == ".gz", "v0.4 summary requires gzip capture")
            require(summary.get("flush_policy") == "buffer_full_or_checkpoint", "unexpected v0.4 flush policy")
            require(summary.get("io_buffer_bytes") == 4194304, "unexpected v0.4 buffer size")
            interval = integer(summary.get("max_checkpoint_interval_ms"), "checkpoint interval")
            require(5000 <= interval <= 300000, "invalid checkpoint interval")
            for name in ("application_disk_bytes", "application_disk_write_ops", "checkpoint_flush_count"):
                integer(summary.get(name), name, 1)
            require(summary["application_disk_bytes"] == path.stat().st_size, "application byte count differs from finalized file")
            require(summary.get("last_flush_reason") == "shutdown", "missing clean shutdown flush")
    return {"valid": True, "valid_basic_stream": True, "canonical_schema": SCHEMA,
            "capture_sha256": source_hash, "capture_bytes": path.stat().st_size,
            "summary_sha256": summary_hash, "summary_checked": summary is not None,
            "pips_checked": pips is not None, "records": last_seq, "alias": alias,
            "event_counts": dict(counts), "seq_gaps": 0, "time_reversals_observed": 0,
            "parse_errors": 0, "duplicate_adds_reconstructed": 0,
            "unknown_replaces_reconstructed": 0, "unknown_cancels_reconstructed": 0,
            "orders_open_at_end": len(orders), "first_market_ns": first, "last_market_ns": last,
            "zero_size_execution_end_markers": boundary_markers,
            "compression_integrity": "gzip CRC32/ISIZE passed" if path.suffix == ".gz" else "uncompressed"}


def validate(path, summary_path=None):
    try:
        result = inspect(path, summary_path)
    except (OSError, ValueError, UnicodeError, zlib.error) as error:
        result = {"valid": False, "valid_basic_stream": False, "error": str(error)}
    print(json.dumps(result, indent=2))
    return 0 if result["valid"] else 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("--summary", type=Path)
    args = parser.parse_args()
    return validate(args.capture, args.summary)


if __name__ == "__main__":
    raise SystemExit(main())
