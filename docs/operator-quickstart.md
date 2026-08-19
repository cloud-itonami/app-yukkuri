# operator quickstart — app-yukkuri

**この手順は 2026-08-19 に実際に踏んだ結果だけを書いている。**
踏めなかったもの（appview の build / deploy）は「未検証」と明記して分けてある
——「まだ動かしていない」と「動いた」を同じ顔で並べない。

対象読者は、この repo を初めて開いて **何かを 1 つ動かしたい** 人。
設計（何を作ろうとしているか）は `CLAUDE.md`、clj twin の移植仕様は
`lg-clj/README.md` が正本で、ここは重複させない。

---

## 0. checkout の remote は `origin` ではない

west 管理の checkout は remote を **org 名**で持つ。`git fetch origin` は
`repository does not exist` で落ちるが、これは repo が無いのではなく remote 名が違う。

```bash
cd orgs/cloud-itonami/app-yukkuri
git remote -v          # → cloud-itonami  git@github.com:cloud-itonami/app-yukkuri
git fetch cloud-itonami
git rev-list --left-right --count HEAD...cloud-itonami/main   # 0  0 なら同期済み
```

作業するときは superproject の **外**に worktree を切る（共有 checkout を直接編集すると
並行セッションの WIP を壊す）:

```bash
git worktree add -b <branch> /tmp/<name> cloud-itonami/main
```

---

## 1. 何が入っているか（3 つの木）

| ディレクトリ | 中身 | 今日の状態 |
|---|---|---|
| `lg/` | Python LangGraph + FastAPI。`langgraph.json` に **10 graph** を登録 | **deployed runtime**（設計上）。テストは 9 件 |
| `lg-clj/` | 同じ 10 graph の Clojure twin（ADR-2606280030） | additive。オフラインで全 topology が回る |
| `appview/etzhayyim-wasm-yukkuri-y5kk5r1x/` | SvelteKit + Cloudflare Worker | **hostname が DNS に無い**（§5） |

10 graph は両実装で同じ: `health` `list_videos` `get_video` `compose`
`generate_script` `synthesize_voice` `generate_visual` `generate_bgm`
`render_video` `review_video`。

---

## 2. clj twin のテストを通す（いちばん短い緑）

```bash
cd lg-clj
bb test
```

実測 2026-08-19:

```
Testing lg-yukkuri.smoke-test

Ran 40 tests containing 115 assertions.
0 failures, 0 errors.
```

外部サービスは一切要らない。`bb.edn` が `langchain-clj` / `langgraph-clj` を
git SHA で pin しているので、初回だけ git fetch が走る。

---

## 3. Python のテストは dev extra を入れないと「9 件失敗」に見える

`pyproject.toml` は `pytest-asyncio` を `[project.optional-dependencies] dev` に
宣言しているが、**入っていないときの落ち方が紛らわしい**。9 件全部が失敗し、
理由は `ModuleNotFoundError` ではなく次の警告として出る:

```
PytestUnknownMarkWarning: Unknown pytest.mark.asyncio - is this a typo?
```

これは「テストが壊れている」ではなく「dev extra が入っていない」のサイン。入れると通る:

```bash
python3 -m venv /tmp/yukkuri-venv
/tmp/yukkuri-venv/bin/pip install "pytest>=8.0" "pytest-asyncio>=0.24" httpx
cd lg && /tmp/yukkuri-venv/bin/python -m pytest tests/ -q
```

実測 2026-08-19: `9 passed in 0.11s`。

`lg/tests/conftest.py` が `langgraph` と `kotodama` を stub しているので、
**この 2 つは入れなくてよい**（`kotodama` は private package なので入れられない）。
ネットワークにも出ない。

---

## 4. graph をオフラインで 1 本叩く

外部 effect は全部 **injectable な dynamic var** になっている。bind すれば
サービスを 1 つも立てずに topology を通せる。**seam は 11 個で、graph 固有のものが
ある**——`store/*query*` を bind しても `health` は緑にならない（`health` は自分の
`*rw-ping*` を持つ）。ここが最初に詰まる場所:

| ns | seam | 何を差し替えるか |
|---|---|---|
| `lg-yukkuri.store` | `*select-where*` `*insert-row*` `*query*` | kotoba Datom log |
| `lg-yukkuri.audit` | `*emit*` | BPMN 監査 emit（既定 no-op） |
| `lg-yukkuri.llm` | `*chat-json*` | murakumo loopback gateway |
| `graphs.health` | `*rw-ping*` | store 疎通 probe（**既定は必ず false**） |
| `graphs.synthesize-voice` | `*tts-one*` | kokoro-ts TTS |
| `graphs.generate-visual` | `*generate-one*` | 画像生成 |
| `graphs.generate-bgm` | `*compose-bgm*` | ongakuka BGM |
| `graphs.render-video` | `*render*` | kami-engine render |
| `graphs.review-video` | `*social-publish*` | 公開 |

### 4a. dispatch surface をそのまま見る

```bash
cd lg-clj && cat > /tmp/probe.clj <<'EOF'
(require '[lg-yukkuri.server :as server]
         '[lg-yukkuri.graphs.health :as health]
         '[lg-yukkuri.audit :as audit])
(binding [health/*rw-ping* (fn [] {:rw_ok true})
          audit/*emit* (fn [_] nil)]
  (prn :ok      (server/ok))
  (prn :health  (server/dispatch-xrpc "com.etzhayyim.apps.yukkuri.health" {}))
  (prn :unknown (server/dispatch-xrpc "com.etzhayyim.apps.yukkuri.nope" {})))
EOF
bb -f /tmp/probe.clj
```

実測 2026-08-19（抜粋）:

```clojure
:health  {:status 200 :body {:rw_ok true :ok true :server_now "…Z" :assistantId "health"}}
:unknown {:status 404 :body {:error "unknown NSID: com.etzhayyim.apps.yukkuri.nope"}}
```

`*rw-ping*` を bind しないと `{:rw_ok false :error "rw: store not configured"}` で
`:ok false` になる。**これは故障ではなく既定値**（Python の try/except 経路と parity）。

### 4b. 書き込みのある graph — `compose`

```bash
cd lg-clj && cat > /tmp/probe-compose.clj <<'EOF'
(require '[lg-yukkuri.server :as server]
         '[lg-yukkuri.store :as store]
         '[lg-yukkuri.audit :as audit])
(def written (atom []))
(binding [store/*insert-row* (fn [t row] (swap! written conj [t row]) nil)
          audit/*emit* (fn [_] nil)]
  (prn :ok    (server/dispatch-xrpc "com.etzhayyim.apps.yukkuri.compose" {"topic" "テスト話題"}))
  (prn :blank (server/dispatch-xrpc "com.etzhayyim.apps.yukkuri.compose" {"topic" "  "})))
(prn :rows (count @written))
EOF
bb -f /tmp/probe-compose.clj
```

実測 2026-08-19: 1 本目が `:video_id "video-…"` を返し、2 本目が
`{:error "topic is required"}`。**`:rows 1`** ——拒否された方は 1 行も書いていない。
検証が insert の前段にあることが、これで実際に見える。

`/xrpc` は camelCase を snake_case に落として graph へ渡す（`dispatch-xrpc`）。
`/runs` は `{:assistant_id … :input …}` を取り、`x-api-key` を任意で強制する:

```clojure
(server/dispatch-run {:assistant_id "health"} {:x-api-key "wrong" :api-key "secret"})
;; => {:status 401 :body {:error "invalid x-api-key"}}
```

---

## 5. まだ動かせないもの（正直に）

- **appview は hostname が解決しない。** `wrangler.jsonc` は
  `yukkuri.etzhayyim.com/*` と `y5kk5r1x.etzhayyim.com/*` を route に持ち、
  `PROJECT.jsonld` の `url` も前者を指すが、実測 2026-08-19 で **どちらも DNS に
  レコードが無い**（`dig +short` が空。親の `etzhayyim.com` は Cloudflare を返す）。
  `curl` は接続エラー（exit 6 / http_code 000）。**これらの URL を「本番」として
  引用しない。**
- **appview の build / deploy はこの手順では検証していない。** `svelte/` の build は
  workspace の resource governor を通す必要がある（同時 1 本）:
  `node scripts/resource-guard.mjs run build -- <command>`。
- **`lg/` の FastAPI サーバ自体は起動していない。** `langgraph.json` の
  `dependencies` が superproject 外の相対パス
  （`../../../40-engine/kotoba/crates/kotoba-kotodama/py`）を指しており、
  west 配置ではそこに解決先が無い。§3 のテストは `conftest.py` の stub で
  この依存を迂回している。
- `lg-clj` は `bb`（babashka）で回る。workspace 全体としては script host を
  nbb に寄せる方針（ADR-2607173000）だが、この repo はまだ移行していない。

---

## 6. 次に触るなら

- `lg-clj/README.md` が graph ごとの topology と Python からの逸脱を全部書いている。
- 未登録のまま Python 側にだけ在る graph が 4 本ある
  （`compose_scene` `generate_character` `translate_video` `upload_youtube`）。
  このうち `upload_youtube` だけがテストを持つ（§3 の 9 件がそれ）。
