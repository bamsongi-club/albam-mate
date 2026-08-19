import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/search-evaluation/run-bge-m3.py"
QUALITY_CORPUS = ROOT / "docs/p2/search-evaluation/quality-corpus.json"
SEARCH_TEXT = ROOT / "docs/p2/search-evaluation/dense-bge-m3/search-text-top1000.json"
QUERIES = ROOT / "docs/p2/search-evaluation/queries.json"
DISPLAY_MAP = ROOT / "docs/p2/search-evaluation/dense-bge-m3/display-map-top1000.json"
SEMANTIC_30_QUERIES = ROOT / "docs/p2/search-evaluation/search-candidate-comparison/semantic-30-queries.json"


def load_module():
    spec = importlib.util.spec_from_file_location("run_bge_m3", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class BgeM3InputContractTest(unittest.TestCase):
    def test_885_contract_accepts_the_15_query_fixture_and_hard_filters(self):
        module = load_module()
        queries = json.loads(QUERIES.read_text(encoding="utf-8"))
        contract = {
            "schemaVersion": 1,
            "kind": "search-04-search-candidate-dense-input",
            "approvalReference": "https://github.com/bamsongi-club/albam-mate/issues/885#issuecomment-5343789151",
            "sourceGitHead": "974e55a05be944a70fc157c30e3da0c7888883d3",
            "sourceHashes": {
                "qualityCorpus": "0ce65a0ab500054c11548fe487afab8247b8f09282087f6db4d144d0783c87e8",
                "searchText": "ec364be3a34268d1bb6d27e3c41e2cdd31852565eec79fa31faaacda17af4ece",
                "queries": "7dae3cdd6b36756dd6b210c6dc1c0121cad9ec197187246dd607b1eb130a2b2f",
                "displayMap": "b3e717225cc091712071506d24c7653823994ec6b9743b2a8c3532fa659b3ffc",
            },
            "queryIds": [query["id"] for query in queries],
            "applyHardFilters": True,
        }
        args = SimpleNamespace(
            model_revision=module.MODEL_REVISION,
            source_git_head=contract["sourceGitHead"],
            model_path=ROOT,
            quality_corpus=QUALITY_CORPUS,
            search_text=SEARCH_TEXT,
            queries=QUERIES,
            display_map=DISPLAY_MAP,
        )
        quality_corpus = json.loads(QUALITY_CORPUS.read_text(encoding="utf-8"))
        search_text = json.loads(SEARCH_TEXT.read_text(encoding="utf-8"))
        display_map = json.loads(DISPLAY_MAP.read_text(encoding="utf-8"))
        games, display_by_id, quality_by_id = module.validate_inputs(
            args,
            quality_corpus,
            search_text,
            display_map,
            queries,
            contract,
        )
        self.assertEqual(len(games), 1000)
        self.assertEqual(len(display_by_id), 1000)
        self.assertEqual(len(quality_by_id), 1000)

    def test_885_contract_accepts_the_semantic_30_fixture_approval(self):
        module = load_module()
        queries = json.loads(SEMANTIC_30_QUERIES.read_text(encoding="utf-8"))
        contract = {
            "schemaVersion": 1,
            "kind": "search-04-search-candidate-dense-input",
            "approvalReference": "https://github.com/bamsongi-club/albam-mate/issues/885#issuecomment-5344383511",
            "sourceGitHead": "974e55a05be944a70fc157c30e3da0c7888883d3",
            "sourceHashes": {
                "qualityCorpus": "0ce65a0ab500054c11548fe487afab8247b8f09282087f6db4d144d0783c87e8",
                "searchText": "ec364be3a34268d1bb6d27e3c41e2cdd31852565eec79fa31faaacda17af4ece",
                "queries": "84522f97b196d12db33b082fc26529218555b9408a973e6b6da3577587387142",
                "displayMap": "b3e717225cc091712071506d24c7653823994ec6b9743b2a8c3532fa659b3ffc",
            },
            "queryIds": [query["id"] for query in queries],
            "applyHardFilters": True,
        }
        with tempfile.TemporaryDirectory() as directory:
            contract_path = Path(directory) / "contract.json"
            contract_path.write_text(json.dumps(contract), encoding="utf-8")
            loaded = module.load_input_contract(contract_path)
        self.assertEqual(loaded["queryIds"], [query["id"] for query in queries])
        self.assertTrue(loaded["applyHardFilters"])

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
