#!/usr/bin/env python3
"""Run the approved local BGE-M3 dense-only SEARCH-04 PoC."""

from __future__ import annotations

import argparse
import hashlib
import json
import platform
import re
from pathlib import Path
from typing import Any

MODEL_ID = "BAAI/bge-m3"
MODEL_REVISION = "5617a9f61b028005a4858fdac845db406aefb181"
APPROVED_SOURCE_GIT_HEAD = "592de01644e33554dcce5a13bfcb5e9d5bfac882"
DIMENSION = 1024
TOP_K = 20
MODEL_ARTIFACT_MANIFEST_SHA256 = "82bc00be0afb7daa72f89b3e7a7f14552b59466550a9eafa2f965b59e65cb708"
APPROVED_SOURCE_HASHES = {
    "qualityCorpus": "0ce65a0ab500054c11548fe487afab8247b8f09282087f6db4d144d0783c87e8",
    "searchText": "ec364be3a34268d1bb6d27e3c41e2cdd31852565eec79fa31faaacda17af4ece",
    "queries": "93d3e79d5a04d9eb147830f513df960a564a7ff32a786441b6f9e59665901772",
    "displayMap": "b3e717225cc091712071506d24c7653823994ec6b9743b2a8c3532fa659b3ffc",
}
APPROVED_MODEL_FILES = {
    "1_Pooling/config.json": "e54c164a07274f2eb45bb724f54a79d1efcc90c41573887cd9a29aeee0597352",
    "config.json": "26159e7ad065073448460117eb24b7a4572f6f4e78eadff65dc0a11c052449fa",
    "config_sentence_transformers.json": "1eef72430e7194a1e59680e635aed81ffa083f05668dbc5bb1c56c04c0999c38",
    "modules.json": "84e40c8e006c9b1d6c122e02cba9b02458120b5fb0c87b746c41e0207cf642cf",
    "pytorch_model.bin": "b5e0ce3470abf5ef3831aa1bd5553b486803e83251590ab7ff35a117cf6aad38",
    "sentence_bert_config.json": "eb9b44b13c0f52a3b3685c3b1cbdea1ba8b04bea123b98f61610048940776eb1",
    "sentencepiece.bpe.model": "cfc8146abe2a0488e9e2a0c56de7952f7c11ab059eca145a0a727afce0db2865",
    "special_tokens_map.json": "8c785abebea9ae3257b61681b4e6fd8365ceafde980c21970d001e834cf10835",
    "tokenizer.json": "21106b6d7dab2952c1d496fb21d5dc9db75c28ed361a05f5020bbba27810dd08",
    "tokenizer_config.json": "a62b2b6784f990259fddef5f16388693a8043be4f69179e6a5257eeb3f9abac4",
}
PLAY_INTENT_QUERIES = {
    "Q-010": "가볍게 웃으면서 즐길 수 있는 게임",
    "Q-011": "상대의 반응을 살피며 서로 눈치를 보는 재미가 있는 게임",
    "Q-012": "보드게임을 처음 하는 초보자와도 부담 없이 시작할 수 있는 게임",
}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as source:
        return json.load(source)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-path", type=Path, required=True)
    parser.add_argument("--model-manifest", type=Path, required=True)
    parser.add_argument("--model-revision", default=MODEL_REVISION)
    parser.add_argument("--source-git-head", required=True)
    parser.add_argument("--quality-corpus", type=Path, required=True)
    parser.add_argument("--search-text", type=Path, required=True)
    parser.add_argument("--display-map", type=Path, required=True)
    parser.add_argument("--queries", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--device", default=None)
    return parser.parse_args()


def load_model_artifact_manifest(path: Path) -> dict[str, Any]:
    if sha256_file(path) != MODEL_ARTIFACT_MANIFEST_SHA256:
        raise ValueError("model artifact manifest is not the pinned BGE-M3 manifest")
    manifest = load_json(path)
    if manifest.get("schemaVersion") != 1 or manifest.get("kind") != "search-04-local-model-artifact":
        raise ValueError("invalid model artifact manifest")
    if manifest.get("modelId") != MODEL_ID or manifest.get("revision") != MODEL_REVISION:
        raise ValueError("model artifact manifest model identity does not match BGE-M3")
    files = {
        item.get("path"): item.get("sha256")
        for item in manifest.get("requiredFiles", [])
        if isinstance(item, dict)
    }
    if files != APPROVED_MODEL_FILES:
        raise ValueError("model artifact manifest file set does not match the approved snapshot")
    return manifest


def validate_model_artifact(model_path: Path, manifest_path: Path) -> str:
    load_model_artifact_manifest(manifest_path)
    for relative_path, expected_sha256 in APPROVED_MODEL_FILES.items():
        actual_path = model_path / relative_path
        if not actual_path.is_file():
            raise ValueError(f"model artifact is missing: {relative_path}")
        if sha256_file(actual_path) != expected_sha256:
            raise ValueError(f"model artifact checksum mismatch: {relative_path}")
    for actual_path in model_path.rglob("*"):
        if not actual_path.is_file():
            continue
        relative_path = actual_path.relative_to(model_path).as_posix()
        if actual_path.suffix == ".safetensors" or (actual_path.suffix == ".bin" and relative_path != "pytorch_model.bin"):
            raise ValueError(f"unapproved alternative model weights are not allowed: {relative_path}")
    return sha256_file(manifest_path)


def validate_inputs(
    args: argparse.Namespace,
    quality_corpus: dict[str, Any],
    search_text: dict[str, Any],
    display_map: dict[str, Any],
    queries: list[dict[str, Any]],
) -> tuple[list[dict[str, Any]], dict[int, dict[str, str | None]]]:
    if args.model_revision != MODEL_REVISION:
        raise ValueError("model revision is not the approved BGE-M3 snapshot")
    if args.source_git_head != APPROVED_SOURCE_GIT_HEAD:
        raise ValueError("source Git SHA is not the approved SEARCH-04 input snapshot")
    if not re.fullmatch(r"[0-9a-f]{40}", args.source_git_head):
        raise ValueError("--source-git-head must be a 40-character Git SHA")
    if not args.model_path.is_dir() or args.model_path.is_symlink():
        raise ValueError("--model-path must be a local model directory")
    for name, path in (
        ("qualityCorpus", args.quality_corpus),
        ("searchText", args.search_text),
        ("queries", args.queries),
        ("displayMap", args.display_map),
    ):
        if sha256_file(path) != APPROVED_SOURCE_HASHES[name]:
            raise ValueError(f"{name} is not the approved SEARCH-04 input snapshot")
    games = search_text.get("games")
    if search_text.get("gameCount") != 1000 or not isinstance(games, list) or len(games) != 1000:
        raise ValueError("search_text must contain exactly 1,000 games")
    game_ids = [game.get("gameId") for game in games]
    if any(not isinstance(game_id, int) or game_id < 1 for game_id in game_ids) or len(set(game_ids)) != len(game_ids):
        raise ValueError("search_text gameId must be unique positive integers")
    if any(not isinstance(game.get("searchText"), str) or not game["searchText"].strip() for game in games):
        raise ValueError("search_text must provide non-empty searchText for every game")
    quality_members = quality_corpus.get("members")
    quality_ids = [member.get("gameId") for member in quality_members] if isinstance(quality_members, list) else []
    if len(quality_ids) != 1000 or set(quality_ids) != set(game_ids):
        raise ValueError("search_text gameId membership does not match the approved quality corpus")
    display_games = display_map.get("games")
    if display_map.get("gameCount") != 1000 or not isinstance(display_games, list) or len(display_games) != 1000:
        raise ValueError("display map must contain exactly 1,000 games")
    display_by_id = {}
    for game in display_games:
        game_id = game.get("gameId")
        if not isinstance(game_id, int) or game_id in display_by_id:
            raise ValueError("display map gameId must be unique positive integers")
        display_by_id[game_id] = {"name": game.get("name"), "englishName": game.get("englishName")}
    if set(display_by_id) != set(game_ids):
        raise ValueError("display map gameId membership does not match search_text")
    if len(queries) != 3 or {query.get("id") for query in queries} != {"Q-010", "Q-011", "Q-012"}:
        raise ValueError("queries must contain Q-010, Q-011, Q-012")
    for query in queries:
        query_id = query.get("id")
        if query.get("query") != PLAY_INTENT_QUERIES[query_id] or query.get("hardFilters") != {}:
            raise ValueError("play-intent queries must match the approved text and have no hard filter")
        if query.get("labelStatus") != "unjudged":
            raise ValueError("unjudged play-intent queries cannot be treated as approved gold")
    return games, display_by_id


def load_model(model_path: Path, device: str | None):
    try:
        from sentence_transformers import SentenceTransformer
    except ImportError as error:
        raise RuntimeError("sentence-transformers is required for the local runner") from error

    model = SentenceTransformer(
        str(model_path),
        device=device,
        local_files_only=True,
        trust_remote_code=False,
    )
    if model.get_sentence_embedding_dimension() != DIMENSION:
        raise ValueError("BGE-M3 must emit the full 1,024-dimensional vector")
    pooling = getattr(model, "_modules", {}).get("1")
    if pooling is None or not getattr(pooling, "pooling_mode_cls_token", False):
        raise ValueError("BGE-M3 execution must use CLS pooling")
    return model


def encode(model, texts: list[str], batch_size: int):
    import numpy as np

    vectors = model.encode(
        texts,
        batch_size=batch_size,
        convert_to_numpy=True,
        normalize_embeddings=True,
        show_progress_bar=False,
    )
    vectors = np.asarray(vectors, dtype=np.float32)
    if vectors.ndim != 2 or vectors.shape[1] != DIMENSION:
        raise ValueError("embedding output dimension is not 1,024")
    if not np.isfinite(vectors).all():
        raise ValueError("embedding output contains NaN or Inf")
    norms = np.linalg.norm(vectors, axis=1)
    if not np.allclose(norms, 1.0, atol=1e-3):
        raise ValueError("embedding output is not L2-normalized")
    return vectors


def rank_results(query_vectors, document_vectors, games, queries, display_by_id):
    scores = query_vectors @ document_vectors.T
    output = []
    for query_index, query in enumerate(queries):
        order = sorted(
            range(len(games)),
            key=lambda index: (-float(scores[query_index, index]), games[index]["gameId"]),
        )[:TOP_K]
        ranked = [
            {
                "rank": rank,
                "gameId": games[index]["gameId"],
                "score": float(scores[query_index, index]),
                "name": display_by_id[games[index]["gameId"]]["name"],
                "englishName": display_by_id[games[index]["gameId"]]["englishName"],
            }
            for rank, index in enumerate(order, start=1)
        ]
        output.append({"id": query["id"], "query": query["query"], "ranked": ranked, "hardFilterViolationGameIds": []})
    return output


def runtime_descriptor(model) -> dict[str, str]:
    import numpy as np
    import sentence_transformers
    import torch

    return {
        "python": platform.python_version(),
        "sentenceTransformers": sentence_transformers.__version__,
        "torch": torch.__version__,
        "numpy": np.__version__,
        "device": str(getattr(model, "device", "unknown")),
    }


def main() -> None:
    args = parse_args()
    if args.batch_size < 1:
        raise ValueError("--batch-size must be a positive integer")
    model_manifest_sha256 = validate_model_artifact(args.model_path, args.model_manifest)
    quality_corpus = load_json(args.quality_corpus)
    search_text = load_json(args.search_text)
    display_map = load_json(args.display_map)
    queries = load_json(args.queries)
    games, display_by_id = validate_inputs(args, quality_corpus, search_text, display_map, queries)
    model = load_model(args.model_path, args.device)
    document_vectors = encode(model, [game["searchText"] for game in games], args.batch_size)
    query_vectors = encode(model, [query["query"] for query in queries], args.batch_size)
    results = {
        "schemaVersion": 1,
        "kind": "search-04-dense-execution",
        "sourceGitHead": args.source_git_head,
        "model": {
            "provider": "local",
            "modelId": MODEL_ID,
            "revision": MODEL_REVISION,
            "dimension": DIMENSION,
            "pooling": "cls",
            "normalized": True,
            "prefix": "none",
            "denseOnly": True,
            "similarity": "normalized-dot",
        },
        "inputs": {
            "qualityCorpusSha256": sha256_file(args.quality_corpus),
            "searchTextSha256": sha256_file(args.search_text),
            "querySha256": sha256_file(args.queries),
            "displayMapSha256": sha256_file(args.display_map),
            "modelArtifactManifestSha256": model_manifest_sha256,
            "corpusRows": len(games),
        },
        "runtime": runtime_descriptor(model),
        "topK": TOP_K,
        "queries": rank_results(query_vectors, document_vectors, games, queries, display_by_id),
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(results, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"model": MODEL_ID, "queries": len(queries), "corpusRows": len(games), "topK": TOP_K}))


if __name__ == "__main__":
    main()
