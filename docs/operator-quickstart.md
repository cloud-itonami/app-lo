# operator-quickstart

**この repo で今日実際にできることを、踏める形で上から書く。** 所要 5 分。
Cloudflare のアカウントは要らない（deploy だけが要る。§5）。

出力はすべて実際に walk した結果である。

## 0. 前提

| 要るもの | 確認 | この walk で使った版 |
|---|---|---|
| git | `git --version` | 2.51.0 |
| nbb | `npx --yes nbb --version` | v1.4.208 |
| clojure | `clojure --version` | ビルド時のみ |

## 1. 取得して、書いてあることが本当か検査する

```bash
git clone git@github.com:cloud-itonami/app-lo.git
cd app-lo
REPO=$PWD
npx --yes nbb scripts/verify-docs-claims.cljs .
```

末尾が `OK` なら README の数値・存在・不在は tree と一致している。
**exit 2（UNDETERMINED）は 0 ではない** —— tree を読み切れなかったという別の
答えで、「検査して問題なし」と混ぜない。

この検査には移行の不変条件が入っている: TypeScript と Svelte が戻っていないこと
（撤去した 12 パスの不在 + `.ts` / `.svelte` の総数 + node ビルド設定の不在）、
`wrangler.jsonc` の `main` が shadow の出力先を指していること、`assets` /
`rules` / `compatibility_flags` の残骸が戻っていないこと、そしてページが
route 表と `APP_CAPABILITIES` から描かれていること。

## 2. テストを走らせる（ビルド不要・ブラウザ不要）

判断（`route.cljc`）と描画（`view.cljc`）は純 `.cljc` なので、nbb だけで回る。

**ライブラリは `deps.edn` が pin している sha で走らせる。** この workstation の
共有 west checkout は `jp-go-digital-design-system` を 5 日ぶんだけ古い commit で
持っていた（`0a02180` / pin は `2e2d191`）ので、そのまま classpath に載せると
**テストする source と bundle に入る source が別のライブラリ**になる。

```bash
K=~/github/com-junkawasaki/orgs/kotoba-lang
rm -rf /tmp/lolibs && mkdir -p /tmp/lolibs/dds /tmp/lolibs/html /tmp/lolibs/css
git -C $K/jp-go-digital-design-system archive 2e2d191e9e1731ce6865c79dab163a5d74249053 | tar -x -C /tmp/lolibs/dds
git -C $K/html archive aa57f2730c87b7c2752151ed1a5f2e402c2ac71e | tar -x -C /tmp/lolibs/html
git -C $K/css  archive 6eda5ee28ec177b9e09fdbee92c55a050b18cf7d | tar -x -C /tmp/lolibs/css

CP="src:test:/tmp/lolibs/dds/src:/tmp/lolibs/dds/resources:/tmp/lolibs/html/src:/tmp/lolibs/css/src"
cat > /tmp/lo-run.cljs <<'EOF'
(require '[cljs.test :refer [run-tests]] 'lo.route-test)
(run-tests 'lo.route-test)
EOF
npx --yes nbb --classpath "$CP" /tmp/lo-run.cljs
```

実際の出力:

```
Testing lo.route-test

Ran 6 tests containing 32 assertions.
0 failures, 0 errors.
```

何を固定しているか: `/xrpc/` の後ろは **1 セグメントに制限しない**（deploy されて
いた `[...path]` と同じ意味論。空だけが 400 `Missing XRPC method`）、MCP router の
URL 解決（空白だけの設定は未設定として扱う）、`result` / `structuredContent` の
剥がし方（空 body は `{}`）、`APP_CAPABILITIES` の名前 → 完全修飾 NSID、そして
**ページが渡された値から描かれること**（固定値を焼いていたら落ちる）。

## 3. ページを描画して採点する

```bash
CP="src:/tmp/lolibs/dds/src:/tmp/lolibs/dds/resources:/tmp/lolibs/html/src:/tmp/lolibs/css/src"
cat > /tmp/lo-render.cljs <<'EOF'
(require '["node:fs" :as fs] '[lo.view :as view] '[lo.route :as route])
(let [css (.readFileSync fs "/tmp/lolibs/dds/resources/jp_go_dds/dds.css" "utf8")]
  (.writeFileSync fs "/tmp/lo-page.html"
    (view/render {:css css :routes route/routes
                  :methods (route/capability-nsids
                             ["createShipment" "updateShipment" "listShipments" "getShipment"
                              "createRoute" "updateRoute" "listRoutes" "getRoute"])
                  :vars [:AGENTGATEWAY_MCP_ROUTER_URL :APP_CAPABILITIES :APP_DESCRIPTION
                         :APP_DISPLAY_NAME :APP_FRAMEWORK :APP_NANOID
                         :APP_PERFORMER_TYPE :APP_UI_TYPE]
                  :mcp-url "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message"
                  :actor route/actor-did}))
  (println "wrote" (.-size (.statSync fs "/tmp/lo-page.html")) "bytes"))
EOF
npx --yes nbb --classpath "$CP" /tmp/lo-render.cljs

cd $K/design-quality && npx --yes nbb -m design-quality.cli score /tmp/lo-page.html --min 95
```

実際の出力（末尾）:

```
  100.00  /tmp/lo-page.html
aggregate: 100.00
gate: aggregate 100.00 >= min 95.00 -> PASS
```

**この gate の感度について 1 つ測ってある。** 同じページを `:css ""`（デザイン
システムの CSS を一切焼かない）で描くと **96.63 で、min 95 を通ってしまう** ——
`jp-go-dds.page` が `ext-css` を無条件に差し込むので、10 軸のうち 8 軸がそれだけで
満たされるためである。gate が赤になることは別に確かめた（描画済みページから
viewport meta を 1 個消す → 88.76、exit 1）。**「95 を超えた」は「デザインシステムが
入っている」の証明ではない。** それを言うのは §4.5 の smoke（`dads-table` を
bundle 出力の中に探す）の方である。

## 4. bundle をビルドする

**高負荷ビルドは同時 1 本に制限されている**（superproject `CLAUDE.md` の
resource governor）。直接叩かず、必ず guard 経由で:

```bash
cd "$REPO"
node ~/github/com-junkawasaki/scripts/resource-guard.mjs run build -- \
  npx --yes shadow-cljs release worker
ls -la dist/worker.js
```

lock を他セッションが持っていると exit 2 で拒否される。**迂回しない** ——
`resource-guard: build is already running (pid=…)` はエラーではなく順番待ちで
ある（この walk では別 repo のビルドの後ろで **約 30 分**待った —— lock は
09:05:08Z から 09:35:05Z まで cloud-murakumo が保持していた）。

実際の出力（末尾）:

```
shadow-cljs - config: /private/tmp/app-lo-cljs/shadow-cljs.edn
shadow-cljs - starting via "clojure"
[:worker] Compiling ...
[:worker] Build completed. (55 files, 12 compiled, 0 warnings, 110.63s)
```

## 4.5 ビルドした成果物を実際に叩く

ここが deploy されるものに触る唯一の検査である。ソースのテストは判断を固定するが、
**export の形・`:advanced-optimization`・`shadow.resource/inline` で焼いた CSS・
`APP_CAPABILITIES` の JSON decode** は、ビルドを通って初めて存在する。

```bash
cd "$REPO" && npx --yes nbb scripts/smoke-worker.cljs dist/worker.js
```

```
PASS	default export has fetch	expected=true	actual=true
PASS	GET / status	expected=200	actual=200
PASS	GET / is html	expected=true	actual=true
PASS	page advertises /health	expected=true	actual=true
PASS	page advertises /xrpc/:nsid	expected=true	actual=true
PASS	page advertises createShipment	expected=true	actual=true
...（8 メソッドすべて）...
PASS	page shows a var key	expected=true	actual=true
PASS	page hides var values	expected=false	actual=false
PASS	page carries the design system	expected=true	actual=true
PASS	GET /health status	expected=200	actual=200
PASS	health names its routes	expected=true	actual=true
PASS	health names its methods	expected=true	actual=true
PASS	health names the actor	expected=true	actual=true
PASS	POST /xrpc/ status	expected=400	actual=400
PASS	POST /xrpc/ reason	expected=true	actual=true
PASS	POST /xrpc/a/b is proxied, not rejected	expected=false	actual=false
PASS	POST /xrpc/a/b says the router is unreachable	expected=true	actual=true
PASS	OPTIONS preflight	expected=204	actual=204
PASS	OPTIONS advertises methods	expected="POST,OPTIONS"	actual="POST,OPTIONS"
PASS	unknown path	expected=404	actual=404
PASS	wrong method on /health	expected=405	actual=405
PASS	wrong method on /xrpc	expected=405	actual=405
PASS	405 names the allowed methods	expected="POST, OPTIONS"	actual="POST, OPTIONS"
OK	the built bundle answers as the route table says

(30 項目 / exit 0。dist/worker.js は 254,347 バイト)
```

**bundle が無ければ exit 2**（「判定できなかった」であって合格ではない）。

## 4.6 compat flag を外したので、workerd で実際に動くことを確かめる

`compatibility_flags` の `nodejs_compat` / `nodejs_als` は `adapter-cloudflare` の
要件だったので外した。**外したなら、外した状態で動くことを見る。**

```bash
cd "$REPO/appview/lo-mcp-component"
npx --yes wrangler dev --local --port 8799 --ip 127.0.0.1
# 別の端末で
curl -s -o /tmp/p.html -w 'status=%{http_code} ct=%{content_type} bytes=%{size_download}\n' http://127.0.0.1:8799/
curl -s http://127.0.0.1:8799/health
curl -s -X POST -w '\nstatus=%{http_code}\n' http://127.0.0.1:8799/xrpc/
curl -s -X OPTIONS -D - -o /dev/null http://127.0.0.1:8799/xrpc/x
```

実際の出力（wrangler 4.69.0、compatibility_date 2025-03-17、**flag なし**）:

```
[wrangler:info] Ready on http://127.0.0.1:8799
status=200 ct=text/html; charset=utf-8 bytes=84036
{"ok":true,"app":"lo","runtime":"cljs","actor":"did:web:lo.etzhayyim.com",
 "nanoid":"dbdw2pcn","routes":["/","/health","/xrpc/:nsid"],"methods":[... 8 本 ...]}
{"error":"Missing XRPC method"}   status=400
HTTP/1.1 204 No Content / access-control-allow-methods: POST,OPTIONS
{"error":"Not Found","routes":["GET /","GET /health","POST /xrpc/:nsid"]}  status=404
```

runtime エラーは 0 件。ビルド済み bundle に `node:` / `AsyncLocalStorage` /
`async_hooks` の出現も **0 件**（`grep -c` 実測）。

**workerd が返したページは §3 で採点したものと byte 単位で同一**である
（sha256 `eb065d97…`、84,036 バイト）。採点した artifact と deploy される
artifact が同じものであることを、名前ではなく hash で確かめてある。
そのページには `yoro`（`APP_UI_TYPE` の**値**）が 0 回しか出ない —— つまり
0 回、キーだけが出ている。

## 5. deploy

```bash
cd "$REPO/appview/lo-mcp-component"
npx wrangler deploy
```

**この walk では deploy していない。** そして route が指すホストは解決しない
（`lo.etzhayyim.com` / `dbdw2pcn.etzhayyim.com` とも NXDOMAIN、2026-08-18 実測）。
deploy が成功しても誰も到達できない。`/xrpc/` の中継先 `mcp.etzhayyim.com` も
同様なので、到達できたとしても中継は **502 を返す**（成功と同じ形で隠さない）。

superproject の deploy guard は `origin/main` を含む checkout からの deploy しか
許さない点も併せて注意。

## 6. ここに無いもの

- `dispatcher.etzhayyim.com` への中継 / `/_app/meta` / NSID prefix の allowlist
  —— 移行前の `src/app.ts` にあり、どこにも deploy されていなかった経路。宛先が
  NXDOMAIN、または binding が `wrangler.jsonc` に無いので**持ち越していない**
  （README の「持ち越さなかったもの」）
- 業務そのもの（MCP router の先の AgentGateway / pod 側 LangServer にある）
- `MIGRATION-TODO.md` の codemod 3 項目
