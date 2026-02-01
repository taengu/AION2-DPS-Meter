#!/usr/bin/env python3
import argparse
from dataclasses import dataclass
from pathlib import Path


@dataclass
class MatchResult:
    line_number: int
    target_id: int
    actor_id: int | None
    payload: bytes


def encode_varint(value: int) -> bytes:
    if value < 0:
        raise ValueError("VarInt value must be non-negative.")
    out = bytearray()
    while value > 0x7F:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    out.append(value)
    return bytes(out)


def parse_payload(line: str) -> bytes | None:
    if "payload" not in line:
        return None
    payload_idx = line.find("payload")
    if payload_idx == -1:
        return None
    payload_str = line[payload_idx:].split("payload", 1)[1].strip()
    if payload_str.startswith(":"):
        payload_str = payload_str[1:].strip()
    if not payload_str:
        return None
    try:
        return bytes.fromhex(payload_str)
    except ValueError:
        return None


def extract_target(line: str) -> int | None:
    marker = "target"
    if marker not in line:
        return None
    try:
        after = line.split(marker, 1)[1].strip()
        return int(after.split()[0])
    except (ValueError, IndexError):
        return None


def find_matches(payload: bytes, target_id: int, actor_id: int | None) -> bool:
    target_pattern = encode_varint(target_id)
    if target_pattern not in payload:
        return False
    if actor_id is None:
        return True
    actor_pattern = encode_varint(actor_id)
    return actor_pattern in payload


def main() -> None:
    parser = argparse.ArgumentParser(description="Inspect FF FF payload logs.")
    parser.add_argument("--log", type=Path, default=Path("ff_ff.log"), help="Path to ff_ff.log")
    parser.add_argument("--target", type=int, required=True, help="Target actor ID to match")
    parser.add_argument("--actor", type=int, default=None, help="Attacker actor ID to match")
    args = parser.parse_args()

    if not args.log.exists():
        raise SystemExit(f"Log file not found: {args.log}")

    matches: list[MatchResult] = []
    with args.log.open("r", encoding="utf-8") as handle:
        for idx, line in enumerate(handle, start=1):
            payload = parse_payload(line)
            if payload is None:
                continue
            target_id = extract_target(line)
            if target_id is None:
                continue
            if target_id != args.target:
                continue
            if not find_matches(payload, args.target, args.actor):
                continue
            matches.append(MatchResult(idx, target_id, args.actor, payload))

    if not matches:
        print("No matching payloads found.")
        return

    print(f"Matched {len(matches)} payload(s):")
    for match in matches:
        print(f"- line {match.line_number} target={match.target_id} actor={match.actor_id}")
        print(f"  payload: {match.payload.hex(' ')}")


if __name__ == "__main__":
    main()
