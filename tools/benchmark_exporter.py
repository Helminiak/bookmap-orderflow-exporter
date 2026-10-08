#!/usr/bin/env python3
"""Synthetic real-callback journal benchmark; no Bookmap application or raw market data.

Build with: gradle test jar writeFixtureClasspath
Run with: python tools/benchmark_exporter.py --classpath-file build/fixture-classpath.txt --output result.json
Linux peak RSS is ru_maxrss for this single Java child, not queue-retained memory.
"""
import argparse
import json
import platform
from pathlib import Path
import socket
import subprocess
import tempfile
import time
from validate_export import inspect


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    parser.add_argument('--classpath-file', type=Path, required=True)
    parser.add_argument('--cycles', type=int, default=1000)
    parser.add_argument('--rate', type=int, default=100000)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.cycles < 1 or args.rate < 0:
        parser.error('cycles >= 1 and rate >= 0 required')
    ports = []
    for _ in range(2):
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            ports.append(listener.getsockname()[1])
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        started = time.monotonic()
        process = subprocess.run([args.java, '-cp', args.classpath_file.read_text().strip(),
                                  'com.limacharlie.orderflow.ExporterCallbackFixture',
                                  *map(str, ports), '100000', str(args.cycles), str(args.rate),
                                  'false', str(root)], capture_output=True, text=True, timeout=120)
        wall = time.monotonic() - started
        if process.returncode:
            raise RuntimeError(process.stderr or process.stdout)
        summary_path = next(root.glob('*.summary.json'))
        summary = json.loads(summary_path.read_text())
        validation = inspect(root / summary['event_file'], summary_path)
        peak = None
        if platform.system() == 'Linux':
            import resource
            peak = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss * 1024
        result = dict(synthetic_callbacks_no_bookmap=True, bridge_enabled=False,
                      platform=platform.platform(), machine=platform.machine(),
                      python=platform.python_version(), cycles=args.cycles, target_event_rate=args.rate,
                      process_wall_seconds=wall, records_per_process_wall_second=validation['records'] / wall,
                      java_child_peak_rss_bytes=peak, peak_rss_method='Linux RUSAGE_CHILDREN; one child',
                      validation=validation,
                      summary={k: summary.get(k) for k in ['total_records','records_persisted',
                              'orders_open_at_stop','application_disk_bytes','application_disk_write_ops',
                              'journal_queue_high_water','journal_overflows','journal_dropped',
                              'callback_latency','writer_ok','last_flush_reason']})
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps({k: result[k] for k in ['process_wall_seconds','records_per_process_wall_second',
                                                'java_child_peak_rss_bytes']}))


if __name__ == '__main__':
    main()
