"""Embedding model loader — lazy-loaded so app boot is fast.

Default: nlpai-lab/KURE-v1 (Korean-focused, BGE-M3 fine-tune, 1024-dim).
Swap via .env EMBEDDING_MODEL=... (e.g. BAAI/bge-m3). Same dim → no schema change.
"""
from typing import List, Sequence
from threading import Lock
from functools import lru_cache

import numpy as np

from .config import settings

_model = None
_model_lock = Lock()


def get_model():
    global _model
    with _model_lock:
        if _model is None:
            from sentence_transformers import SentenceTransformer
            _model = SentenceTransformer(settings.embedding_model, device=settings.embedding_device)
    return _model


def embed_texts(texts: Sequence[str]) -> np.ndarray:
    """Returns (n, dim) float32, L2-normalized."""
    model = get_model()
    vecs = model.encode(
        list(texts),
        normalize_embeddings=True,
        convert_to_numpy=True,
        show_progress_bar=False,
    )
    return vecs.astype("float32")


@lru_cache(maxsize=128)
def _query_embedding(text: str) -> tuple:
    return tuple(embed_texts([text])[0].tolist())


def embed_text(text: str) -> List[float]:
    # Cache only bounded query-sized text; do not retain large transcripts indefinitely.
    return list(_query_embedding(text)) if len(text) <= 512 else embed_texts([text])[0].tolist()
