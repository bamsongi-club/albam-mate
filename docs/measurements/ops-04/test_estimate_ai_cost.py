#!/usr/bin/env python3

import importlib.util
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).parent
SPEC = importlib.util.spec_from_file_location("estimate_ai_cost", ROOT / "estimate_ai_cost.py")
ESTIMATOR = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(ESTIMATOR)


class EstimateAiCostTest(unittest.TestCase):
    def setUp(self) -> None:
        self.snapshot = json.loads(
            (ROOT / "openai-gpt-5.6-luna-standard-2026-07-30.json").read_text(encoding="utf-8")
        )

    def test_t2_recalculates_request_and_period_cost_from_the_fixed_snapshot(self) -> None:
        usage = {
            "observationStatus": "OBSERVED",
            "provider": "openai",
            "model": "gpt-5.6-luna",
            "requests": [
                {"inputTokens": 1000, "outputTokens": 200, "totalTokens": 1200},
                {"inputTokens": 500, "outputTokens": 300, "totalTokens": 800},
            ],
        }

        result = ESTIMATOR.estimate(self.snapshot, usage)

        self.assertEqual("ESTIMATED", result["status"])
        self.assertEqual("0.000900000000", result["estimatedCostUsd"])
        self.assertEqual(
            ["0.000440000000", "0.000460000000"],
            [request["estimatedCostUsd"] for request in result["requestEstimates"]],
        )
        self.assertEqual(result["estimatedCostUsd"], result["periodEstimate"]["estimatedCostUsd"])
        self.assertEqual(2000, result["totalTokens"])
        self.assertFalse(result["actualInvoiceAmount"])

    def test_t4_missing_usage_is_not_reported_as_zero_cost(self) -> None:
        result = ESTIMATOR.estimate(self.snapshot, {
            "observationStatus": "NO_OBSERVATION", "provider": "openai",
            "model": "gpt-5.6-luna", "requests": [],
        })

        self.assertEqual("NO_OBSERVATION", result["status"])
        self.assertIsNone(result["estimatedCostUsd"])

    def test_t4_model_mismatch_and_unsupported_pricing_do_not_fall_back(self) -> None:
        base = {
            "observationStatus": "OBSERVED", "provider": "openai",
            "model": "gpt-5.6-terra",
            "requests": [{"inputTokens": 10, "outputTokens": 1, "totalTokens": 11}],
        }
        mismatch = ESTIMATOR.estimate(self.snapshot, base)
        long_context = ESTIMATOR.estimate(self.snapshot, {
            **base, "model": "gpt-5.6-luna",
            "requests": [{"inputTokens": 272001, "outputTokens": 1, "totalTokens": 272002}],
        })

        self.assertEqual("MODEL_MISMATCH", mismatch["reason"])
        self.assertEqual("LONG_CONTEXT_PRICE_NOT_REPRESENTED", long_context["reason"])

    def test_snapshot_rate_card_is_checksum_protected(self) -> None:
        changed = json.loads(json.dumps(self.snapshot))
        changed["rateCard"]["outputUsd"] = "9.99"

        with self.assertRaisesRegex(ValueError, "checksum"):
            ESTIMATOR.validate_snapshot(changed)


if __name__ == "__main__":
    unittest.main()
