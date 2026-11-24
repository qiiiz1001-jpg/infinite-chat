"""Validate deterministic evaluation helpers without HTTP or judge-model calls."""
import ast
import json
from pathlib import Path
import re
from difflib import SequenceMatcher
import unittest

HERE = Path(__file__).resolve().parent
HELPERS = {"_norm", "_trigrams", "is_refusal", "is_negative_sample", "context_hits_answer", "hit_at_k"}
tree = ast.parse((HERE / "ragas_eval.py").read_text(encoding="utf-8"))
scope = {"re": re, "SequenceMatcher": SequenceMatcher, "REFUSAL_PHRASE": "无法回答",
         "HIT_CONTIG_THR": 0.60, "HIT_NGRAM_THR": 0.85}
exec(compile(ast.Module(body=[n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in HELPERS],
                        type_ignores=[]), "ragas_helpers", "exec"), scope)


class OfflineEvaluationTest(unittest.TestCase):
    def test_positive_and_negative_samples(self):
        self.assertTrue(scope["is_negative_sample"]({"reference": "根据现有资料无法回答。"}))
        self.assertTrue(scope["is_negative_sample"]({"type": "negative", "reference": "未知"}))
        self.assertFalse(scope["is_negative_sample"]({"reference": "轻聊使用 Redis"}))

    def test_hit_at_k_uses_ranked_contexts(self):
        contexts = ["天气很好", "轻聊使用 Redis 保存会话记忆"]
        self.assertEqual(scope["hit_at_k"]("Redis 保存会话记忆", contexts, 1), 0)
        self.assertEqual(scope["hit_at_k"]("Redis 保存会话记忆", contexts, 3), 1)

    def test_empty_context_does_not_hit(self):
        self.assertEqual(scope["hit_at_k"]("知识", [], 5), 0)
        self.assertFalse(scope["context_hits_answer"]("", "知识"))

    def test_supplied_dataset_is_valid_and_contains_both_sample_types(self):
        data = json.loads((HERE / "test_dataset.json").read_text(encoding="utf-8"))
        self.assertTrue(data)
        self.assertTrue(all(isinstance(x.get("question"), str) and x.get("reference") for x in data))
        negatives = sum(scope["is_negative_sample"](x) for x in data)
        self.assertGreater(negatives, 0)
        self.assertLess(negatives, len(data))


if __name__ == "__main__":
    unittest.main()
