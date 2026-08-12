"""Make lg_yukkuri.graphs.upload_youtube importable and testable offline.

The repo had no test setup at all, so this file also *is* the setup. Two
dependencies are stubbed rather than installed:

  langgraph — the graph wiring is not what these tests are about, and pulling
              it in would add a heavy dependency to prove an ordering property
              that lives entirely inside the node functions.
  kotodama  — a private package (the store client). It is imported lazily
              inside the functions, so a fake in sys.modules is enough, and the
              fake doubles as the assertion surface: the tests read what the
              node actually wrote, in what order.

Nothing here reaches the network. httpx is real; only its transport is faked,
so the code under test builds and reads genuine Request/Response objects.
"""

from __future__ import annotations

import sys
import types
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


# ── langgraph stub ───────────────────────────────────────────────────────────
def _install_langgraph_stub() -> None:
    if "langgraph" in sys.modules:
        return
    pkg = types.ModuleType("langgraph")
    graph = types.ModuleType("langgraph.graph")
    typs = types.ModuleType("langgraph.types")

    class StateGraph:
        def __init__(self, *_a, **_k):
            self.nodes: dict = {}

        def add_node(self, name, fn, **_k):
            self.nodes[name] = fn
            return self

        def add_edge(self, *_a, **_k):
            return self

        def compile(self, **_k):
            return self

    graph.StateGraph = StateGraph
    graph.START = "__start__"
    graph.END = "__end__"

    class RetryPolicy:
        def __init__(self, *_a, **_k):
            pass

    typs.RetryPolicy = RetryPolicy
    pkg.graph = graph
    pkg.types = typs
    sys.modules["langgraph"] = pkg
    sys.modules["langgraph.graph"] = graph
    sys.modules["langgraph.types"] = typs


# ── kotodama store stub ──────────────────────────────────────────────────────
class FakeStore:
    """An in-memory stand-in for the kotoba client, recording write order.

    `writes` is the point: it lets a test assert that the session URL was
    persisted BEFORE the bytes went out, which is the property under test and
    is invisible in the final state.
    """

    def __init__(self) -> None:
        self.tables: dict[str, dict[str, dict]] = {}
        self.writes: list[tuple[str, dict]] = []

    def _key(self, table: str, row: dict) -> str:
        return row.get("vertex_id") or row.get("video_id") or ""

    def seed(self, table: str, row: dict) -> None:
        self.tables.setdefault(table, {})[self._key(table, row)] = dict(row)

    def select_where(self, table, col, val, limit=None):
        rows = [r for r in self.tables.get(table, {}).values() if r.get(col) == val]
        return rows[:limit] if limit else rows

    def insert_row(self, table, row):
        self.tables.setdefault(table, {})
        key = self._key(table, row)
        existing = self.tables[table].get(key, {})
        merged = {**existing, **row}
        self.tables[table][key] = merged
        self.writes.append((table, dict(row)))
        return merged


def _install_kotodama_stub(store: FakeStore) -> None:
    pkg = types.ModuleType("kotodama")
    mod = types.ModuleType("kotodama.kotoba_datomic")
    mod.get_kotoba_client = lambda: store
    pkg.kotoba_datomic = mod
    sys.modules["kotodama"] = pkg
    sys.modules["kotodama.kotoba_datomic"] = mod


_install_langgraph_stub()
_STORE = FakeStore()
_install_kotodama_stub(_STORE)


@pytest.fixture
def store() -> FakeStore:
    _STORE.tables.clear()
    _STORE.writes.clear()
    return _STORE


@pytest.fixture
def uy():
    import lg_yukkuri.graphs.upload_youtube as mod
    return mod
