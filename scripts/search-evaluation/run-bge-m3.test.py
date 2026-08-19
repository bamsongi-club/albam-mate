import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/search-evaluation/run-bge-m3.py"
QUALITY_CORPUS = ROOT / "docs/p2/search-evaluation/quality-corpus.json"
SEARCH_TEXT = ROOT / "docs/p2/search-evaluation/dense-bge-m3/search-text-top1000.json"
DISPLAY_MAP = ROOT / "docs/p2/search-evaluation/dense-bge-m3/display-map-top1000.json"
SEMANTIC_30_QUERIES = ROOT / "docs/p2/search-evaluation/search-candidate-comparison/semantic-30-queries.json"
SEMANTIC_30_CONTRACT = ROOT / "docs/p2/search-evaluation/search-candidate-comparison/semantic-30-dense-input-contract.json"


def load_module():
    spec = importlib.util.spec_from_file_location("run_bge_m3", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class BgeM3InputContractTest(unittest.TestCase):
    def test_885_contract_accepts_the_semantic_30_fixture_approval(self):
        module = load_module()
        queries = json.loads(SEMANTIC_30_QUERIES.read_text(encoding="utf-8"))
        loaded = module.load_input_contract(SEMANTIC_30_CONTRACT)
        self.assertEqual(loaded["queryIds"], [query["id"] for query in queries])
        self.assertTrue(loaded["applyHardFilters"])

    def test_candidate_contract_rejects_self_consistent_tampering(self):
        module = load_module()
        contract = json.loads(SEMANTIC_30_CONTRACT.read_text(encoding="utf-8"))
        contract["sourceGitHead"] = "0" * 40
        with tempfile.TemporaryDirectory() as directory:
            contract_path = Path(directory) / "contract.json"
            contract_path.write_text(json.dumps(contract), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "checksum"):
                module.load_input_contract(contract_path)

    def test_hard_filter_semantics_match_the_baseline_contract(self):
        module = load_module()
        member = {
            "minPlayers": 2,
            "maxPlayers": 4,
            "maxPlayTimeMinutes": 45,
        }
        self.assertTrue(module.matches_hard_filters(member, {"minPlayers": 3}))
        self.assertTrue(module.matches_hard_filters(member, {"maxPlayers": 4}))
        self.assertTrue(module.matches_hard_filters(member, {"maxPlayTimeMinutes": 60}))
        self.assertFalse(module.matches_hard_filters(member, {"minPlayers": 5}))
        self.assertFalse(module.matches_hard_filters(member, {"maxPlayers": 1}))
        self.assertFalse(module.matches_hard_filters(member, {"maxPlayTimeMinutes": 30}))
        self.assertFalse(module.matches_hard_filters({**member, "maxPlayTimeMinutes": None}, {"maxPlayTimeMinutes": 30}))


if __name__ == "__main__":
    unittest.main()
