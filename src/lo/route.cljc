(ns lo.route
  "Which handler answers a request — as data, decided by a pure function.

  This is `.cljc` and not `.cljs` on purpose. Routing is the part of an edge
  worker that is worth testing, and it is testable here without a browser, a
  build, or a network. `lo.worker` is the only namespace that touches
  Request/Response, and it does nothing this file has not already decided.

  It is also the first thing that should move to `.kotoba` once the ingress
  capability qualifies (`:native-aot`/`:wasm-aot` are pending today —
  ADR-2606290000): a route table is a decision over scalars and strings,
  which is exactly the shape that survives that move."
  (:require [clojure.string :as str]))

(def nsid-prefix
  "lo の XRPC 名前空間。3 箇所が同じ値を宣言しているのを 1 箇所に集めたもの:
  `kotodama.jsonld` の `triggers.subscribeRepos.collections`
  (`com.etzhayyim.apps.lo.shipment` / `.route`)、撤去した `src/app.ts` の
  `NSID_PREFIX`、そして `wrangler.jsonc` の `APP_CAPABILITIES` が並べる 8 つの
  メソッド名。**この prefix は表示のためだけに使う** —— 中継そのものは
  deploy されていた SvelteKit の route と同じく、prefix を検査しない。"
  "com.etzhayyim.apps.lo.")

(def actor-did
  "`appview/lo-mcp-component/kotodama.jsonld` の `@id`。ここに写しているのは
  `/health` が名乗るためで、正本はあちら側。"
  "did:web:lo.etzhayyim.com")

(def default-nanoid
  "撤去した `src/app.ts` が `env.APP_NANOID ?? \"dbdw2pcn\"` と書いていた既定値。
  `wrangler.jsonc` の `APP_NANOID` と同じ値である。"
  "dbdw2pcn")

(def routes
  "The public surface, as data. The landing page renders THIS, so a route that
  exists and a route the page advertises cannot drift apart — the defect
  docs/adr/0001 recorded was a page that said `Routes 0` and `vars []` beside a
  wrangler.jsonc declaring two routes and eight vars."
  [{:route/path "/"          :route/method :get  :route/kind :page
    :route/doc "この appview の説明ページ"}
   {:route/path "/health"    :route/method :get  :route/kind :json
    :route/doc "生存確認。デプロイされた面が答えることを外から確かめられる"}
   {:route/path "/xrpc/:nsid" :route/method :post :route/kind :proxy
    :route/doc "XRPC を MCP router へ中継する"}])

(defn- xrpc-rest
  "`/xrpc/<rest>` の rest。無ければ nil。

  **多段パスを 400 にしない。** deploy されていた SvelteKit の route は
  `[...path]`（rest parameter）なので `/xrpc/a/b` は nsid `\"a/b\"` として
  上流へ渡っていた。移行はその意味論を変えない —— 変えるなら移行ではなく
  別の決定である。空（`/xrpc` と `/xrpc/`）だけが 400
  （`Missing XRPC method`、文言も SvelteKit 版そのまま）。"
  [path]
  (when (str/starts-with? path "/xrpc/")
    (let [rest' (subs path (count "/xrpc/"))]
      (when (seq rest') rest'))))

(defn dispatch
  "method + path → 何をするか。Request も Response も知らない。

  返すのは `{:action …}` で、`:action` は
  `:page` / `:health` / `:xrpc` / `:cors-preflight` / `:not-found` /
  `:method-not-allowed` / `:bad-request` のいずれか。"
  [method path]
  (let [m (keyword (str/lower-case (or method "get")))
        p (or path "")]
    (cond
      (and (= m :options) (or (= p "/xrpc") (str/starts-with? p "/xrpc/")))
      {:action :cors-preflight}

      (or (= p "/xrpc") (str/starts-with? p "/xrpc/"))
      (if (= m :post)
        (if-let [nsid (xrpc-rest p)]
          {:action :xrpc :nsid nsid}
          {:action :bad-request :reason "Missing XRPC method"})
        {:action :method-not-allowed :allow "POST, OPTIONS"})

      (= p "/health") (if (= m :get)
                        {:action :health}
                        {:action :method-not-allowed :allow "GET"})
      (= p "/")       (if (= m :get)
                        {:action :page}
                        {:action :method-not-allowed :allow "GET"})
      :else {:action :not-found})))

(defn mcp-router-url
  "env の設定 → MCP router の URL。末尾スラッシュは落とす。

  解決順（`AGENTGATEWAY_MCP_ROUTER_URL` → `MCP_ROUTER_URL` → 既定）も、
  空白だけを未設定として扱うところも、deploy されていた
  `svelte/src/routes/xrpc/[...path]/+server.ts` の `mcpRouterUrl` と同じ。"
  [{:keys [AGENTGATEWAY_MCP_ROUTER_URL MCP_ROUTER_URL]}]
  (let [pick (fn [s] (when (and (string? s) (seq (str/trim s))) (str/trim s)))]
    (-> (or (pick AGENTGATEWAY_MCP_ROUTER_URL)
            (pick MCP_ROUTER_URL)
            "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message")
        (str/replace #"/+$" ""))))

(defn capability-nsids
  "`APP_CAPABILITIES` が並べる短いメソッド名 → 完全修飾 NSID。

  decode（JSON 文字列 → seq）は `lo.worker` の側にある。**ここは形だけを決める** ——
  すでに完全修飾なものは二重に prefix を付けない（宣言側が長い名前に変わっても
  ページが壊れない）。"
  [names]
  (into []
        (comp (filter string?)
              (map str/trim)
              (remove str/blank?)
              (map (fn [n] (if (str/includes? n ".") n (str nsid-prefix n)))))
        names))

(def ^:private drop-headers
  "上流へ渡さない header。

  `host` —— 移行前の SvelteKit route も削っていた（宛先が変わるので嘘になる）。
  移行はここまでは正しく写していた。

  `content-length` / `content-encoding` —— **これが抜けていた。** body は
  JSON-RPC の封筒に詰め直されるので、呼び手が付けた長さもエンコーディングも
  もう本文を説明していない。それを載せたまま上流へ投げると fetch 自体が失敗し、
  Worker は 502 `MCP router unreachable` を返す —— router には 1 度も届かない。
  実測 2026-08-19、ビルド済み bundle に `content-length` 付きの POST を通して
  確認した（付けなければ同じ bundle が 200 を返す）。POST に `content-length`
  を付けないクライアントは実際にはほぼ無いので、これは稀な経路ではない。

  **それ以外は全部渡す。** `authorization` はこの repo では最初から届いていた。"
  #{"host" "content-length" "content-encoding"})

(defn relay-headers
  "受け取った header を、上流へ渡す形にする。`in` は [[k v] …] の列。

  ここが `.cljc` にあるのは、これがビルドもブラウザも無しに固定できる**判断**
  だからである。`js/Headers` を worker 側で組み立てていたので、何が渡って何が
  落ちるかを述べたテストが書けず、上の欠陥は誰にも気づかれなかった。

  `x-etzhayyim-bff` の値だけは移行で変えてある（SvelteKit 版は
  `sveltekit-edge-bff` を名乗っていた）。名乗りは事実なので、SvelteKit で
  なくなった後もそう名乗り続けるのは嘘になる（APP_FRAMEWORK も同時に変えた）。
  この註は worker 側の組み立てに付いていたもので、値と一緒にここへ移した。"
  [in nsid]
  (into {"content-type" "application/json"
         "x-etzhayyim-bff" "cljs-worker"
         "x-etzhayyim-xrpc-method" nsid}
        (comp (remove (fn [[k _]] (contains? drop-headers (str/lower-case k))))
              (map (fn [[k v]] [(str/lower-case k) v])))
        in))

(defn unwrap-mcp
  "MCP router の応答から、呼び手に返す値を取り出す。

  `{:result {:structuredContent X}}` → X、`{:result X}` → X、それ以外は素通し。
  `{:error …}` は呼び出し側が 502 にするので、ここでは判定だけ返す。
  `nil`（上流が空 body）は `{}` —— SvelteKit 版の `structured ?? {}` と同じ。"
  [payload]
  (cond
    (and (map? payload) (contains? payload :error))
    {:ok? false :error (get-in payload [:error :message] "MCP router returned an error")
     :upstream payload}

    (and (map? payload) (contains? payload :result))
    (let [r (:result payload)
          v (if (and (map? r) (contains? r :structuredContent))
              (:structuredContent r)
              r)]
      {:ok? true :value (if (nil? v) {} v)})

    :else {:ok? true :value (if (nil? payload) {} payload)}))
