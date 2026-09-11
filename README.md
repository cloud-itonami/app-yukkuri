# app-yukkuri

**ゆっくり実況動画を 1 トピックから自動生成する application actor。**
`app-` は role 面の prefix（実行役割 = application）で、`yukkuri` が主題。
production service としては `cloud-itonami/yukkuri`、公開 handle は
`yukkuri.etzhayyim.com` を名乗る（`README.edn` の `:boundary` が正本）。

トピック → L/R 掛け合い台本 → TTS 音声 → 生成画像 / SFX → BGM →
headless render → mp4/webm。1 本の動画 = 1 project で、台本・声・絵・効果音・
編集・批評をそれぞれ別の actor DID が担当する構成（詳細は `CLAUDE.md`）。

## まず動かす → [`docs/operator-quickstart.md`](docs/operator-quickstart.md)

初めて開いた人が 1 つ何かを動かすまでの手順。**実際に踏んだ結果だけ**が書いてあり、
踏めなかったものは未検証として分けてある。最短の緑は repo ルートで `nbb run-tests.cljk`
（実測 2026-09-01: JVM と ClojureScript の両方で 44 tests / 141 assertions、
両者が一致しなければ落ちる）。

## 3 つの木

| | 中身 | 役割 |
|---|---|---|
| `lg/` | Python LangGraph + FastAPI。`langgraph.json` に 10 graph | deployed runtime（設計上） |
| `lg-clj/` | 同じ 10 graph の Clojure twin | additive。オフラインで topology が回る |
| `appview/etzhayyim-wasm-yukkuri-y5kk5r1x/` | SvelteKit + Cloudflare Worker | **hostname が DNS 未解決**（下記） |

10 graph は両実装で同一: `health` `list_videos` `get_video` `compose`
`generate_script` `synthesize_voice` `generate_visual` `generate_bgm`
`render_video` `review_video`。NSID は `com.etzhayyim.apps.yukkuri.*`。

Python と clj は **coexist** する設計で、clj 側は Python を消さない
（ADR-2606280030）。移植の忠実性と意図的な逸脱は `lg-clj/README.md` が全部書いている。

`lg-clj/` の src は全部 `.cljc` で、**JVM と ClojureScript の両方で走る**。
2026-09-01 まではそうではなかった —— 走らせていたのは `bb test` だけで babashka は
JVM なので、reader conditional の cljs 側は一度も評価されていなかった。実際
`lg-yukkuri.audit` は cljs では load すらできず、`llm/parse-json-object` と
`render-video/json-parse` は `#?(:clj ... :default nil)` で**どんな入力にも nil を
返して**いた（それは「JSON が無かった」の値でもある）。host 差は
`lg-yukkuri.compat` に 1 箇所ずつ集めてある。

## 外部 effect は全部 injectable

kotoba Datom log / LLM / TTS / 画像 / BGM / render / 公開 / 監査 —— すべて
dynamic var の seam になっており、bind すればサービスを 1 つも立てずに
graph を通せる。seam は 11 個で、**graph 固有のものがある**
（`health` は `store/*query*` ではなく自分の `*rw-ping*` を見る）。
一覧と bind 例は quickstart §4。

## 今日の状態（実測 2026-08-19）

- `lg-clj` のテストは green（40 tests / 115 assertions、外部依存なし）。
- `lg` のテストは green だが `pytest-asyncio` を入れないと 9 件全部が
  紛らわしく落ちる（quickstart §3 に落ち方と対処）。
- **appview は live ではない。** `wrangler.jsonc` の route と `PROJECT.jsonld` の
  `url` が指す `yukkuri.etzhayyim.com` / `y5kk5r1x.etzhayyim.com` は
  どちらも DNS にレコードが無い。これらの URL を本番として引用しないこと。

## 他の入口

- `CLAUDE.md` — 設計の正本（actor 構成 / domain model / XRPC / 表現と著作権の
  不変条件）。**到達目標を含む**ので、現在地は本 README と quickstart を見る。
- `lg-clj/README.md` — 移植仕様（graph ごとの topology、Python からの逸脱）。
- `README.edn` — 機械可読な境界宣言（`etzhayyim.repository/readme-v1`）。
- `PROJECT.jsonld` / `appview/…/kotodama.jsonld` — schema.org / kotodama の
  actor 記述子。

## ライセンス

Apache License 2.0 + etzhayyim Charter Compliance Rider v3.1（`NOTICE`）。
`NOTICE` が参照する `CHARTER-RIDER.md` は **この repo には無い**
（移行時に持ってきていない）——rider 本文が要るときは上流を当たること。
