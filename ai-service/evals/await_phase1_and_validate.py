"""Wait for independent phase-1 model runs, then merge and validate Golden candidates.

The evaluator credential remains in TOKENMP_API_KEY inherited from the parent
process.  This helper never accepts or writes credentials.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path


def main() -> None:
    args = parse_args()
    deadline = time.monotonic() + args.timeout_minutes * 60
    while True:
        counts = [record_count(directory / "phase1_full_context.jsonl") for directory in args.phase1_dirs]
        print(f"phase1 records: {counts}", flush=True)
        if all(count >= args.expected_records for count in counts):
            break
        if time.monotonic() >= deadline:
            raise TimeoutError(f"Timed out waiting for phase1 records: {counts}")
        time.sleep(args.poll_seconds)

    command = [
        sys.executable,
        str(args.evaluator),
        "--question-bank", str(args.question_bank),
        "--corpus-dir", str(args.corpus_dir),
        "--output-dir", str(args.output_dir),
        "--sample-size", str(args.sample_size),
        "--batch-size", str(args.batch_size),
        "--stage", "validate",
        "--phase1-input-dirs",
        *[str(directory) for directory in args.phase1_dirs],
    ]
    subprocess.run(command, check=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evaluator", type=Path, required=True)
    parser.add_argument("--question-bank", type=Path, required=True)
    parser.add_argument("--corpus-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--phase1-dirs", type=Path, nargs="+", required=True)
    parser.add_argument("--sample-size", type=int, default=50)
    parser.add_argument("--batch-size", type=int, default=3)
    parser.add_argument("--expected-records", type=int, default=50)
    parser.add_argument("--poll-seconds", type=int, default=45)
    parser.add_argument("--timeout-minutes", type=int, default=150)
    return parser.parse_args()


def record_count(path: Path) -> int:
    return len(path.read_text(encoding="utf-8").splitlines()) if path.exists() else 0


if __name__ == "__main__":
    main()
