"""Synthetic acquisition regression tests; no licensed market rows in Git."""
from copy import deepcopy
import gzip
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("validator", Path(__file__).parents[1] / "tools/validate_export.py")
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)


class ValidationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        common = dict(schema=validator.SCHEMA, alias="SYNTHETIC", phase="HISTORY")
        events = [
            dict(event="CONTROL", control="START", detail="synthetic"),
            dict(event="MBO_ADD", order_id="000001", side="BID", price_level=100, price=25.0, size=3, anomaly=False),
            dict(event="MBO_REPLACE", order_id="000001", side="BID", price_level=99, price=24.75, size=2, anomaly=False),
            dict(event="TRADE", aggressor_side="SELL", price_level=99.0, price=24.75, size=1,
                 aggressor_order_id="opaque-id", passive_order_id="000001", execution_start=True, execution_end=False, otc=False),
            dict(event="TRADE", aggressor_side="SELL", price_level=99.0, price=24.75, size=0,
                 aggressor_order_id=None, passive_order_id="", execution_start=False, execution_end=True, otc=False),
            dict(event="MBO_CANCEL", order_id="000001", side="BID", price_level=99, price=24.75, size=2, anomaly=False),
            dict(event="CONTROL", control="REALTIME_START", detail="catchup", phase="REALTIME"),
            dict(event="CONTROL", control="STOP", detail="done", phase="REALTIME"),
        ]
        self.rows = [{**common, "seq": i + 1, "market_ns": 0 if i == 0 else 10**18 + i, **r}
                     for i, r in enumerate(events)]

    def write(self, rows=None, **summary_changes):
        rows = self.rows if rows is None else rows
        capture = self.root / "synthetic.ndjson.gz"
        capture.write_bytes(gzip.compress(b"".join(json.dumps(r).encode() + b"\n" for r in rows)))
        summary = dict(schema=validator.SCHEMA, addon_version="0.4.0", alias="SYNTHETIC", pips=.25,
                       event_file=capture.name, writer_ok=True, writer_error=None, export_mbo=True, export_trades=True,
                       total_records=8, records_persisted=8, mbo_add_received=1, mbo_replace_received=1,
                       mbo_cancel_received=1, trades_received=2, first_market_ns=10**18 + 1,
                       last_market_ns=10**18 + 7, orders_open_at_stop=0,
                       duplicate_adds=0, unknown_replaces=0, unknown_cancels=0, time_reversals=0,
                       flush_policy="buffer_full_or_checkpoint", io_buffer_bytes=4194304,
                       max_checkpoint_interval_ms=60000, application_disk_bytes=capture.stat().st_size,
                       application_disk_write_ops=1, checkpoint_flush_count=1, last_flush_reason="shutdown")
        summary.update(summary_changes)
        path = self.root / "synthetic.summary.json"
        path.write_text(json.dumps(summary))
        return capture, path

    def test_complete_v04_capture_and_summary(self):
        capture, summary = self.write()
        result = validator.inspect(capture, summary)
        self.assertTrue(result["valid"])
        self.assertEqual(result["records"], 8)
        self.assertEqual(result["orders_open_at_end"], 0)
        self.assertEqual(result["zero_size_execution_end_markers"], 1)
        self.assertTrue(result["summary_checked"] and result["pips_checked"])

    def test_clean_v05_journal_with_invalid_bridge(self):
        result = validator.inspect(*self.write(addon_version="0.5.0", journal_overflows=0,
                                               journal_dropped=0, bridge={"invalid": True}))
        self.assertTrue(result["valid"])

    def test_v05_journal_drop_counters_fail_closed(self):
        for name in ("journal_overflows", "journal_dropped"):
            changes = dict(addon_version="0.5.0", journal_overflows=0, journal_dropped=0)
            changes[name] = 1
            with self.assertRaises(validator.ValidationError):
                validator.inspect(*self.write(**changes))

    def test_capture_only_and_plain_ndjson(self):
        capture, _ = self.write()
        result = validator.inspect(capture)
        self.assertFalse(result["pips_checked"])
        plain = self.root / "synthetic.ndjson"
        plain.write_bytes(gzip.decompress(capture.read_bytes()))
        self.assertTrue(validator.inspect(plain)["valid"])

    def test_timestamp_ties(self):
        self.rows[3]["market_ns"] = self.rows[2]["market_ns"]
        self.assertTrue(validator.inspect(*self.write())["valid"])

    def test_bad_stream_fields(self):
        cases = [(2, "seq", 4), (2, "market_ns", 1), (2, "market_ns", True),
                 (2, "alias", "OTHER"), (2, "schema", "v9"), (2, "event", []),
                 (2, "phase", "REALTIME"), (2, "price", 24.76), (2, "size", -1),
                 (2, "order_id", "unknown"), (2, "side", "ASK"), (2, "anomaly", True),
                 (4, "execution_start", True), (4, "execution_end", False),
                 (3, "otc", 0), (3, "aggressor_order_id", 123), (5, "size", 7),
                 (6, "control", "TIME_REVERSAL"), (7, "control", "START")]
        for index, name, value in cases:
            with self.subTest(name=name, value=value):
                rows = deepcopy(self.rows); rows[index][name] = value
                with self.assertRaises(validator.ValidationError):
                    validator.inspect(*self.write(rows))

    def test_duplicate_add(self):
        self.rows[2]["event"] = "MBO_ADD"
        with self.assertRaisesRegex(validator.ValidationError, "duplicate ADD"):
            validator.inspect(*self.write())

    def test_unknown_cancel(self):
        self.rows[5]["order_id"] = "unknown"
        with self.assertRaisesRegex(validator.ValidationError, "unknown"):
            validator.inspect(*self.write())

    def test_missing_extra_fields(self):
        for mutation in (lambda r: r.pop("size"), lambda r: r.update(unversioned_field=1)):
            with self.subTest(mutation=mutation):
                rows = deepcopy(self.rows); mutation(rows[2])
                with self.assertRaises(validator.ValidationError): validator.inspect(*self.write(rows))

    def test_bad_summaries(self):
        for name, value in [("records_persisted", 7), ("total_records", True), ("orders_open_at_stop", 2),
                            ("writer_ok", False), ("writer_error", "failed"), ("trades_received", 0),
                            ("export_mbo", False), ("alias", "OTHER"), ("event_file", "wrong.gz"),
                            ("pips", 0), ("pips", .5), ("time_reversals", 1),
                            ("application_disk_bytes", 999), ("last_flush_reason", "checkpoint"),
                            ("max_checkpoint_interval_ms", 1), ("io_buffer_bytes", 4096),
                            ("application_disk_write_ops", 0), ("checkpoint_flush_count", 0),
                            ("flush_policy", "wrong")]:
            with self.subTest(name=name):
                with self.assertRaises(validator.ValidationError): validator.inspect(*self.write(**{name: value}))

    def test_prior_version_summary_does_not_require_v04_counters(self):
        capture, summary = self.write(addon_version="0.3.0")
        content = json.loads(summary.read_text())
        for name in ("application_disk_bytes", "application_disk_write_ops", "checkpoint_flush_count"):
            content.pop(name)
        summary.write_text(json.dumps(content))
        self.assertTrue(validator.inspect(capture, summary)["valid"])

    def test_gzip_crc_and_truncation(self):
        for kind in ("crc", "truncation", "invalid_deflate"):
            with self.subTest(kind=kind):
                capture, summary = self.write()
                data = bytearray(capture.read_bytes())
                if kind == "crc": data[-8] ^= 1
                elif kind == "truncation": data = data[:-10]
                else: data[10] = 7  # reserved deflate block type
                capture.write_bytes(data)
                with self.assertRaisesRegex(validator.ValidationError, "integrity"):
                    validator.inspect(capture, summary)

    def test_incomplete_stream(self):
        for kind in ("missing_stop", "after_stop", "partial_line"):
            with self.subTest(kind=kind):
                rows = deepcopy(self.rows)
                if kind == "missing_stop": rows.pop()
                elif kind == "after_stop": rows.append({**rows[-1], "seq": 9})
                capture, summary = self.write(rows)
                if kind == "partial_line":
                    capture.write_bytes(gzip.compress(gzip.decompress(capture.read_bytes()).rstrip(b"\n")))
                with self.assertRaises(validator.ValidationError): validator.inspect(capture, summary)

    def test_duplicate_json_keys_and_nonfinite(self):
        for line in ('{"seq":1,"seq":2}', '{"price":NaN}'):
            with self.assertRaises(validator.ValidationError): validator.parse(line)

    def test_cli_failure_exit_code(self):
        capture, summary = self.write(writer_ok=False)
        with patch("builtins.print") as output:
            self.assertEqual(validator.validate(capture, summary), 2)
        self.assertIn('"valid": false', output.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
