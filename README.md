# app-lo

**lo（Logistics Operations）—— 配送と経路を扱う appview。** この repo が持つのは
**その公開面（appview）だけ**である。業務そのものは MCP router の先（AgentGateway /
pod 側 LangServer）にあり、ここには無い —— 薄い edge であって、実装ではない。

`etzhayyim/root` の `60-apps/etzhayyim-project-lo` からの抽出物で、
**2026-08-18 に TypeScript/Svelte から ClojureScript へ移行した**（ADR-0001）。
数字はすべて `scripts/verify-docs-claims.cljs` が tree から再計算して検査する。

## deploy されるものは、いま読んでいるソースである

```
src/lo/route.cljc    判断（どの handler が答えるか）  ← 純 .cljc、テスト対象
src/lo/view.cljc     ページ（jp-go-dds の hiccup）    ← 純 .cljc、テスト対象
src/lo/worker.cljs   Request/Response に触る唯一の層
        ↓ shadow-cljs :target :esm
dist/worker.js       ← wrangler.jsonc の "main" が指すもの
```

移行前、`main` は `svelte/.svelte-kit/cloudflare/_worker.js` を指していた ——
**tree に無く、tree の何もそれをビルドしない**（実測: そのディレクトリは存在せず、
`.svelte-kit` / `_worker` に一致する tracked file は 0 件）。同時に、読み手が開く
`appview/lo-mcp-component/src/app.ts` は **tracked file のどれからも参照されて
いなかった**（実測: `git grep app.ts` が 0 件。`package.json` の script は
`tsc --noEmit` と `vitest run` の 2 本だけで、後者が拾うのは `test/**/*.test.ts`）。
つまり「読めるアプリ」と「deploy されるアプリ」が別で、しかも前者はどの bundle にも
入っていなかった。

いまは `main` が指す bundle が上のソースからコンパイルされたものなので、その形は
構造的に起こり得ない。`scripts/verify-docs-claims.cljs` が **shadow の出力先と
wrangler の `main` と export の ns 名の 3 つが噛み合っていること**を検査し、
噛み合わなくなれば落ちる。

判断を `.cljc` に置いてあるのは、ブラウザもビルドも無しにテストするためであり、
ingress capability が qualify した時に **最初に `.kotoba` へ移る部分**だからで
ある（入口を当面 cljs に置くのは ADR-2606290000 の判断）。

## 公開ルート

| METHOD | PATH | 何をするか |
|---|---|---|
| GET | `/` | この appview の説明ページ |
| GET | `/health` | 生存確認。deploy された面が答えることを外から確かめられる |
| POST | `/xrpc/:nsid` | XRPC を MCP router へ中継する |
| OPTIONS | `/xrpc/*` | CORS preflight |

**この表の出所は `lo.route/routes` で、ページもそこから描く。** 移行前のページは
`routeCount: 0` / `routes: []` / `vars: []` を literal で持っており、隣の
`wrangler.jsonc` が route 2・var 8・capability 8 を宣言していることに気づけなかった。
いまは route 表も env のキーも capability も渡す側が持ち、ページは描くだけなので、
両者がずれる余地が無い。

`/xrpc/` の後ろは **1 セグメントに制限しない**。deploy されていた SvelteKit の
route は `[...path]`（rest parameter）だったので `/xrpc/a/b` は nsid `"a/b"` として
上流へ渡っていた。移行はその意味論を変えていない —— 空（`/xrpc` と `/xrpc/`）だけが
400 `Missing XRPC method` で、文言も SvelteKit 版のままである。

## いま在るもの — 19 ファイル

| 面 | ファイル |
|---|---|
| 判断・描画・edge | `src/lo/{route.cljc, view.cljc, worker.cljs}` |
| テスト | `test/lo/route_test.cljc`（6 tests / 32 assertions） |
| 検査スクリプト（nbb） | `scripts/{smoke-worker.cljs, verify-docs-claims.cljs}` |
| ビルド | `deps.edn` / `shadow-cljs.edn` / `.gitignore` |
| Worker 設定 | `appview/lo-mcp-component/wrangler.jsonc` |
| actor 記述子 | `appview/lo-mcp-component/kotodama.jsonld` |
| 業務プロセス | `bpmn/lo.bpmn` |
| 由来・権利・識別 | `NOTICE` / `README.edn` / `migration.edn` / `MIGRATION-TODO.md` |
| 文書 | `README.md` / `docs/operator-quickstart.md` / `docs/adr/0001-*.edn` |

**production の TypeScript は 4 本から 0 本、Svelte は 1 本から 0 本、正本言語
（`.cljs`/`.cljc`）は 0 本から 3 本になった。**（"production" は `scripts/` にも
`test/` にも属さない tracked file。移行前の 4 本の `.ts` は `src/app.ts` /
`svelte/src/routes/xrpc/[...path]/+server.ts` / `svelte/vite.config.ts` /
`vitest.config.ts`、Svelte 1 本は `svelte/src/routes/+page.svelte`。）
この 3 つの数は検証器の claim なので、TS が戻れば落ちる —— 撤去したパスに戻る場合
（`removed-by-migration-absent`）も、別名で入る場合（`production-ts-files`）も、
別々の claim が捕まえる。

## UI

基盤は `kotoba-lang/jp-go-digital-design-system`（デジタル庁デザインシステム）。
色・寸法は `--hig-*` トークン契約だけで書き、raw hex も px フォントサイズも
置かない。app 固有 CSS は 3 行。CSS は外部リクエストゼロの方針どおり
`shadow.resource/inline` で bundle に焼く。

決定論的 audit（`kotoba-lang/design-quality`）で **100.00 / 100（gate 95）**。

## 持ち越さなかったもの（黙って消していない）

移行前の `appview/lo-mcp-component/src/app.ts` にあって **どこにも deploy されて
いなかった**経路のうち、次は**意図的に移していない**。

| 経路 | 移さない理由（実測） |
|---|---|
| `POST /xrpc/com.etzhayyim.apps.lo.*` → `dispatcher.etzhayyim.com` への中継 | 宛先が **NXDOMAIN**（`dig dispatcher.etzhayyim.com` → `status: NXDOMAIN`、2026-08-18）。さらに必要な binding `DISPATCHER_URL` / `DISPATCHER_INTERNAL_SECRET` が `wrangler.jsonc` に**宣言されていない**（vars は 8 個で、どちらも入っていない）。secret も未設定 |
| `GET /_app/meta` | `app.ts` が `/health` に付けていた別名。同じ payload が `/health` にある。`/_app/` は SvelteKit の内部名前空間で、別名として残す理由が無い |
| NSID prefix の allowlist（`app.ts` の `NSID_PREFIX` 検査） | **deploy されていた route はこれを持っていない**。いま足すのは移行ではなく新しい方針である。ページは宣言された 8 メソッドを「宣言」として表示し、「許可リスト」とは書かない |

**動かない経路を移植して「移行済み」と言わないため**である。必要になった時点で
`route.cljc` に足し、テストと binding を伴って戻す。

## 移行で意図的に変えたところ（挙動の差分）

`/xrpc` の中継そのもの（ヘッダの引き継ぎ、`host` を落とすこと、jsonrpc の封筒、
`result` / `structuredContent` の剥がし方、上流が ok でなければそのステータスで
素通しすること、payload に `error` があれば 502、`cache-control: no-store`）は
SvelteKit 版と同じである。違うのは次の 4 点だけ。

1. **`/health` を足した。** 移行前は `src/app.ts`（未 deploy）にしか無かった。
   上流も binding も要らないので「動かない経路」ではなく、deploy された面が
   答えていることを外から確かめられるようになる。
2. **404 と、上流に到達できなかったときの応答を JSON にした。** SvelteKit は
   どちらも framework の HTML エラーページ（404 / 500）を返していた。
   `mcp.etzhayyim.com` は NXDOMAIN なので**中継の既定の結末は到達失敗**であり、
   それを 200 でも HTML でも隠さず `502 {"error":"MCP router unreachable"}` を返す。
3. **`x-etzhayyim-bff` ヘッダの値を `sveltekit-edge-bff` から `cljs-worker` に
   変えた。** 名乗りは事実なので嘘にしない。`wrangler.jsonc` の `APP_FRAMEWORK`
   も同時に `sveltekit-edge-bff` → `cljs-esm-worker` にしてある。
4. **`wrangler.jsonc` から 3 つのブロックを外した。**
   - `assets`（`./svelte/.svelte-kit/cloudflare/client`）—— 指す先が消える
   - `rules` の `CompiledWasm`（`**/*.wasm`）—— tree の `.wasm` は **0 件**（実測）
   - `compatibility_flags` の `nodejs_compat` / `nodejs_als` —— `adapter-cloudflare`
     の要件だった。外したうえで、**この bundle が node builtin に触らないこと**と
     **flag 無しの workerd で実際に答えること**を確かめてある（下記「検証」）。

## 呼び先が 1 つも解決しない（移行では直らない）

| ホスト | 役割 | DNS（2026-08-18 実測） |
|---|---|---|
| `lo.etzhayyim.com` | 公開ホスト（wrangler の route） | **NXDOMAIN** |
| `dbdw2pcn.etzhayyim.com` | 同（nanoid 側） | **NXDOMAIN** |
| `mcp.etzhayyim.com` | `/xrpc/:nsid` の中継先 | **NXDOMAIN** |
| `dispatcher.etzhayyim.com` | `app.ts` の中継先（持ち越さず） | **NXDOMAIN** |

deploy 先も中継先も、いま存在しない（`etzhayyim.com` の apex だけが解決する）。
`/xrpc/` は到達できなければ **502 を返す** —— 成功と同じ形で隠さない。

## 由来（custody）

`migration.edn` は出所を `etzhayyim/root` の tree `c19a166c` と宣言し、
`:allowed-additions` に `README.edn` と `migration.edn` を持つ。移行後の状態:

- 継承した 6 ファイル（8,218 バイト）は**いまも 1 バイトも変わっていない**
  （sha256 を検証器に固定）
- `wrangler.jsonc` は**意図的に変更**した（上記 4 点目 + `main` の付け替え +
  `APP_FRAMEWORK`）
- TypeScript/Svelte/node ビルド設定の 12 ファイルは**移行で撤去**した。検証器は
  その 12 パスを名指しで「不在であること」を検査する —— byte 合計は
  「TS が消えた」と言えない

## 残っている欠陥（移行では直っていない）

1. **ホストが 1 つも解決しない**（上表）。deploy するか retire するかは別の決定。
2. **`MIGRATION-TODO.md` の codemod 3 項目が未着手**のまま（`DISPATCHER_URL` が
   etzhayyim-substrate の dispatcher を指しているかの確認 / settlement の
   USDC + ERC-4337 化 / `kotoba/` reference slice）。移行はこれを直さない。
3. **業務そのものは実装されていない。** `bpmn/lo.bpmn` はプロセスを記述するが、
   それを実行するのは MCP router の先であり、この repo ではない。

## 検証

```bash
nbb scripts/verify-docs-claims.cljs .          # <dir> は先頭に置く
```

exit 0 = 全一致 / 1 = 食い違い / **2 = 判定できなかった**（0 と区別する）。
2026-08-18 の実測では 18 claim すべて PASS。

| 何を | どこで踏めるか | 2026-08-18 の実測 |
|---|---|---|
| テスト（ビルド不要） | quickstart §2 | 6 tests / 32 assertions、0 failures |
| ページの採点 | quickstart §3 | 100.00 / 100、gate 95 PASS |
| bundle のビルド | quickstart §4 | 55 files / 12 compiled / 0 warnings / 110.63s、254,347 バイト |
| **ビルド済み bundle を叩く** | quickstart §4.5 | 30 項目 PASS、exit 0 |
| **compat flag 無しの workerd** | quickstart §4.6 | `wrangler dev --local` で 200/200/400/204/404、runtime エラー 0 件 |

最後の 2 つが「deploy される成果物」に触る唯一の検査である。**workerd が返した
ページは §3 で採点したページと sha256 が同一**（`eb065d97…`）なので、採点した
artifact と出荷される artifact が同じものであることは名前ではなく hash で
確かめてある。
