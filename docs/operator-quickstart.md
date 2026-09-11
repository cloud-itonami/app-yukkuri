# operator quickstart — app-yukkuri

**この手順は実際に踏んだ結果だけを書いている**（§1〜§2 と §4 は 2026-09-01、
§3 は 2026-08-19 に踏んだもの。§3 のコマンドはその後変えていない）。
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
nbb run-tests.cljk          # repo ルートで。両方の runtime を回す
```

実測 2026-09-01:

```
lg-yukkuri: both runtimes agree -- 44 tests, 141 assertions, 0 failures, 0 errors
```

外部サービスは一切要らない。`lg-clj/deps.edn` が `langgraph` / `langchain` /
`json` を git SHA で pin しているので、初回だけ git fetch が走る。

**なぜ 2 つ回すのか。** src は全部 `.cljc` だが、2026-09-01 まで**それを読んだ
runtime は 1 つだけ**だった（`bb test`、babashka は JVM）。だから reader
conditional の ClojureScript 側は一度も評価されておらず、`lg-yukkuri.audit` は
cljs では load すらできなかった。より悪いのは load できた 2 つで、
`llm/parse-json-object` と `render-video/json-parse` は `:default nil` を返す
——それは「JSON オブジェクトが無かった」の値でもあるので、scriptwriter は
整形式の応答すべてに fail-closed し、それをモデルのせいとして報告する。
どのテストも赤くならなかった。**片方だけ回すコマンドはこの種の欠陥を見られない。**

片方ずつ回したいときは:

```bash
clojure -M:test                                                    # JVM だけ
cd lg-clj && nbb --classpath "src:test:$(clojure -Spath -M:test)" run-tests.cljk   # cljs だけ
```

`lg-clj/run-tests.cljk` は **3 値の exit** を返す（0 = 全部通った / 1 = 落ちた /
2 = REFUSED、走った本数が既知の本数に足りない）。走らなかった実行を緑と
区別できるようにするため。

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
cat > /tmp/probe.cljs <<'EOF'
(require '[lg-yukkuri.server :as server]
         '[lg-yukkuri.graphs.health :as health]
         '[lg-yukkuri.audit :as audit])
(binding [health/*rw-ping* (fn [] {:rw_ok true})
          audit/*emit* (fn [_] nil)]
  (prn :ok      (server/ok))
  (prn :health  (server/dispatch-xrpc "com.etzhayyim.apps.yukkuri.health" {}))
  (prn :unknown (server/dispatch-xrpc "com.etzhayyim.apps.yukkuri.nope" {})))
EOF
nbb --classpath "lg-clj/src:$(clojure -Spath -M:test)" /tmp/probe.cljs
```

実測 2026-09-01、nbb で（抜粋）:

```clojure
:health  {:status 200 :body {:rw_ok true :ok true :server_now "2026-09-01T05:37:20Z"
                             :latencyMs 11 :assistantId "health"}}
:unknown {:status 404 :body {:error "unknown NSID: com.etzhayyim.apps.yukkuri.nope"}}
```

`*rw-ping*` を bind しないと `{:rw_ok false :error "rw: store not configured"}` で
`:ok false` になる。**これは故障ではなく既定値**（Python の try/except 経路と parity）。

### 4b. 書き込みのある graph — `compose`

```bash
cat > /tmp/probe-compose.cljs <<'EOF'
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
nbb --classpath "lg-clj/src:$(clojure -Spath -M:test)" /tmp/probe-compose.cljs
```

実測 2026-09-01、nbb で: 1 本目が `:video_id "video-75e17fa1f5a9"` を返し、2 本目が
`{:error "topic is required"}`。**`:rows 1`** ——拒否された方は 1 行も書いていない。
検証が insert の前段にあることが、これで実際に見える。

rkey の 12 桁は `compat/random-hex 6`。JVM は `SecureRandom`、ClojureScript は
WebCrypto の `getRandomValues` で、**どちらも CSPRNG**——store は rkey での upsert
なので、生成器が繰り返すと前のレコードが黙って消える（`generated-rkeys-are-unique-…`
がそれを撃つ）。

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
- ~~`lg-clj` は `bb`（babashka）で回る。~~ **2026-09-01 に移行済み**
  （ADR-2607173000）。`bb.edn` と `run_tests.clj` は撤去し、`lg-clj/deps.edn` +
  `lg-clj/run-tests.cljk`（nbb）+ ルートの `run-tests.cljk`（両方を回して
  一致を要求する）に置き換えた。`run_tests.clj` は本番の capability 配線
  （`with-capabilities`）をテストの入口に置き、しかも `run_tests.clj` という
  ファイルから `ns lg-yukkuri.host` を宣言していた（`load-file` だけが許す
  不一致）。配線は `lg-clj/src/lg_yukkuri/host.cljk` に移し、`http-post` を
  引数に取るので runtime を名指ししなくなった。

---

## 6. 次に触るなら

- `lg-clj/README.md` が graph ごとの topology と Python からの逸脱を全部書いている。
- 未登録のまま Python 側にだけ在る graph が 4 本ある
  （`compose_scene` `generate_character` `translate_video` `upload_youtube`）。
  このうち `upload_youtube` だけがテストを持つ（§3 の 9 件がそれ）。
