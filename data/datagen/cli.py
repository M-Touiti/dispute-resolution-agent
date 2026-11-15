import argparse
import datetime as dt
import os
from pathlib import Path

from .generator import Config, generate
from .writers import write_dataset

DEFAULT_OUT = Path(__file__).resolve().parent.parent / "generated"


def main(argv: list[str] | None = None) -> int:
    defaults = Config(seed=0)
    p = argparse.ArgumentParser(description="Generate the synthetic dispute dataset. Same options, same bytes.")
    p.add_argument(
        "--seed",
        type=int,
        default=int(os.environ.get("DATA_SEED", "20261101")),
        help="random seed (default: $DATA_SEED or 20261101)",
    )
    p.add_argument("--out", type=Path, default=DEFAULT_OUT, help=f"output directory (default: {DEFAULT_OUT})")
    p.add_argument(
        "--as-of",
        type=dt.date.fromisoformat,
        default=defaults.as_of,
        help="the dataset's 'today'; never the wall clock (default: %(default)s)",
    )
    p.add_argument("--cardholders", type=int, default=defaults.cardholders)
    p.add_argument("--merchants", type=int, default=defaults.merchants)
    p.add_argument("--disputes", type=int, default=defaults.disputes)
    args = p.parse_args(argv)

    config = Config(
        seed=args.seed, as_of=args.as_of, cardholders=args.cardholders, merchants=args.merchants, disputes=args.disputes
    )
    manifest = write_dataset(generate(config), config, args.out)
    counts = ", ".join(f"{k}={v}" for k, v in manifest["counts"].items())
    print(f"seed={config.seed} as_of={config.as_of} -> {args.out}\n{counts}")
    return 0
