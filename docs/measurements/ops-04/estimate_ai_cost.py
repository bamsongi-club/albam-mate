#!/usr/bin/env python3
"""OPS-04 공식 가격 snapshot 기반 AI 추정 비용 계산기."""

from __future__ import annotations

import argparse
import hashlib
import json
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from typing import Any


OUTPUT_SCALE = Decimal("0.000000000001")
APPROVED_SNAPSHOT_ID = "openai-gpt-5.6-luna-standard-2026-07-30-v1"
APPROVED_SNAPSHOT_CHECKSUM_SHA256 = (
    "4fcaa7afd3e6ec6f0f223051d06d488ddfb48c4e17939fd462122868e94a22bb"
)


def canonical_sha256(value: Any) -> str:
    encoded = json.dumps(
        value, ensure_ascii=False, separators=(",", ":"), sort_keys=True
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def validate_snapshot(snapshot: dict[str, Any]) -> None:
    required = {
        "schemaVersion", "snapshotId", "provider", "model", "currency",
        "billingMode", "effectiveFrom", "retrievedAt", "sources", "rateCard",
        "rateCardChecksumSha256", "snapshotChecksumSha256", "calculationPolicy",
    }
    missing = sorted(required - set(snapshot))
    if missing:
        raise ValueError(f"snapshot fields are missing: {', '.join(missing)}")
    if snapshot["schemaVersion"] != 1:
        raise ValueError("unsupported snapshot schemaVersion")
    if snapshot["currency"] != "USD" or snapshot["billingMode"] != "standard":
        raise ValueError("only the approved standard USD snapshot is supported")
    actual_checksum = canonical_sha256(snapshot["rateCard"])
    if actual_checksum != snapshot["rateCardChecksumSha256"]:
        raise ValueError("rateCard checksum does not match the snapshot")
    snapshot_payload = {
        key: value for key, value in snapshot.items()
        if key != "snapshotChecksumSha256"
    }
    if canonical_sha256(snapshot_payload) != snapshot["snapshotChecksumSha256"]:
        raise ValueError("snapshot checksum does not match the snapshot")
    if (
        snapshot["snapshotId"] != APPROVED_SNAPSHOT_ID
        or snapshot["snapshotChecksumSha256"]
        != APPROVED_SNAPSHOT_CHECKSUM_SHA256
    ):
        raise ValueError("snapshot is not in the approved allowlist")


def no_observation(snapshot: dict[str, Any], reason: str) -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "status": "NO_OBSERVATION",
        "reason": reason,
        "snapshotId": snapshot["snapshotId"],
        "currency": snapshot["currency"],
        "estimatedCostUsd": None,
    }


def estimate(snapshot: dict[str, Any], usage: dict[str, Any]) -> dict[str, Any]:
    validate_snapshot(snapshot)
    if usage.get("observationStatus") != "OBSERVED":
        return no_observation(snapshot, "USAGE_NOT_OBSERVED")
    if usage.get("provider") != snapshot["provider"]:
        return no_observation(snapshot, "PROVIDER_MISMATCH")
    if usage.get("model") != snapshot["model"]:
        return no_observation(snapshot, "MODEL_MISMATCH")

    requests = usage.get("requests")
    if not isinstance(requests, list) or not requests:
        return no_observation(snapshot, "USAGE_NOT_OBSERVED")

    rate_card = snapshot["rateCard"]
    unit = Decimal(str(rate_card["unitTokens"]))
    input_rate = Decimal(rate_card["inputUsd"])
    output_rate = Decimal(rate_card["outputUsd"])
    maximum_input = int(rate_card["shortContextMaximumInputTokens"])
    total_input = 0
    total_output = 0
    request_estimates: list[dict[str, Any]] = []

    for index, request in enumerate(requests):
        try:
            input_tokens = request["inputTokens"]
            output_tokens = request["outputTokens"]
            total_tokens = request["totalTokens"]
        except (KeyError, TypeError) as error:
            raise ValueError(f"request[{index}] has invalid token fields") from error
        token_counts = (input_tokens, output_tokens, total_tokens)
        if any(type(value) is not int for value in token_counts):
            raise ValueError(f"request[{index}] has invalid token fields")
        if any(value < 0 for value in token_counts):
            raise ValueError(f"request[{index}] token counts must be non-negative")
        if total_tokens != input_tokens + output_tokens:
            raise ValueError(f"request[{index}] totalTokens does not match input + output")
        cached_input_tokens = request.get("cachedInputTokens")
        if type(cached_input_tokens) is not int or cached_input_tokens != 0:
            return no_observation(snapshot, "CACHED_INPUT_NOT_MEASURED")
        if input_tokens > maximum_input:
            return no_observation(snapshot, "LONG_CONTEXT_PRICE_NOT_REPRESENTED")
        total_input += input_tokens
        total_output += output_tokens
        request_cost = (
            Decimal(input_tokens) * input_rate / unit
            + Decimal(output_tokens) * output_rate / unit
        ).quantize(OUTPUT_SCALE, rounding=ROUND_HALF_UP)
        request_estimates.append({
            "requestIndex": index,
            "inputTokens": input_tokens,
            "outputTokens": output_tokens,
            "totalTokens": total_tokens,
            "estimatedCostUsd": format(request_cost, "f"),
        })

    estimated = (
        Decimal(total_input) * input_rate / unit
        + Decimal(total_output) * output_rate / unit
    ).quantize(OUTPUT_SCALE, rounding=ROUND_HALF_UP)
    return {
        "schemaVersion": 1,
        "status": "ESTIMATED",
        "snapshotId": snapshot["snapshotId"],
        "provider": snapshot["provider"],
        "model": snapshot["model"],
        "currency": snapshot["currency"],
        "requestCount": len(requests),
        "requestEstimates": request_estimates,
        "inputTokens": total_input,
        "outputTokens": total_output,
        "totalTokens": total_input + total_output,
        "estimatedCostUsd": format(estimated, "f"),
        "periodEstimate": {
            "requestCount": len(requests),
            "inputTokens": total_input,
            "outputTokens": total_output,
            "totalTokens": total_input + total_output,
            "estimatedCostUsd": format(estimated, "f"),
        },
        "actualInvoiceAmount": False,
    }


def read_json(path: Path) -> dict[str, Any]:
    if not path.is_file() or path.is_symlink():
        raise ValueError(f"regular JSON file is required: {path}")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"JSON object is required: {path}")
    return value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--snapshot", required=True, type=Path)
    parser.add_argument("--usage", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    result = estimate(read_json(args.snapshot), read_json(args.usage))
    rendered = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
    else:
        print(rendered, end="")
    return 0 if result["status"] == "ESTIMATED" else 2


if __name__ == "__main__":
    raise SystemExit(main())
