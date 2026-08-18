(ns lo.worker
  "Cloudflare Worker の入口。**この repo で唯一 Request/Response に触る層。**

  ここには判断を置かない —— どのハンドラが答えるかは `lo.route/dispatch` が
  決め、ページの中身は `lo.view` が組む。どちらも `.cljc` なので、ブラウザも
  ビルドも無しにテストできる。

  wrangler.jsonc の `main` は `dist/worker.js` を指し、それはこの名前空間を
  コンパイルしたものである。移行前は `svelte/.svelte-kit/cloudflare/_worker.js`
  —— tree に無く、tree の何もそれをビルドしない —— を指していて、読み手が開く
  `src/app.ts` はどの config からも参照されていなかった（docs/adr/0001）。

  `aget` を使うのは `:advanced-optimization` 下で env のキーが潰れないため
  （先例 `listingops.edge.worker` と同じ約束）。"
  (:require [lo.route :as route]
            [lo.view :as view]
            [shadow.resource :as rc]
            [clojure.string :as str]))

(def ^:private dds-css
  "DADS の CSS はビルド時に bundle へ焼く。外部リクエストゼロが design system
  の方針で、Worker から resource を読む経路も無い。"
  (rc/inline "jp_go_dds/dds.css"))

(defn- ->response [body {:keys [status content-type cache extra]}]
  (js/Response.
   body
   #js {:status status
        :headers (clj->js (merge {"content-type" content-type
                                  "cache-control" (or cache "no-store")}
                                 extra))}))

(defn- json [body status]
  (->response (js/JSON.stringify (clj->js body))
              {:status status :content-type "application/json; charset=utf-8"}))

(defn- env->map
  "env の **キーだけ** を keyword で拾う。値はページにも応答にも出さない。"
  [env]
  (if env
    (into {} (map (fn [k] [(keyword k) (aget env k)])) (js/Object.keys env))
    {}))

(defn- decode-capabilities
  "`APP_CAPABILITIES` は JSON の文字列配列（wrangler の vars は文字列しか運べない）。
  読めなければ空 —— **ここで throw させない**のは、宣言が壊れているときにページ
  ごと 500 にするより、`宣言なし` と表示して他の事実を見せる方が使えるからである。

  形を決めるのは `route/capability-nsids`（純 `.cljc`、テスト対象）。ここは
  decode だけを持つ。この decode 自身は build した bundle に対して
  `scripts/smoke-worker.cljs` が **wrangler.jsonc の実際の値**を渡して確かめる
  ので、単体テストの代わりに end-to-end で押さえてある。"
  [s]
  (if (string? s)
    (try (let [v (js->clj (js/JSON.parse s))]
           (if (sequential? v) (route/capability-nsids v) []))
         (catch :default _ []))
    []))

(defn- cors-headers []
  {"access-control-allow-origin" "*"
   "access-control-allow-methods" "POST,OPTIONS"
   "access-control-allow-headers" "content-type,authorization"
   "access-control-max-age" "86400"})

(defn- proxy-xrpc
  "XRPC を MCP router へ中継する。deploy されていた SvelteKit の
  `xrpc/[...path]/+server.ts` と同じ形: 呼び手のヘッダを引き継ぎ（`host` は
  落とす）、jsonrpc の封筒に包み、`result` / `structuredContent` を剥がす。

  上流が ok でなければ**そのステータスで**素通し、payload に `error` があれば
  502 —— 判定の順序も SvelteKit 版のまま。"
  [req env nsid]
  (let [url (route/mcp-router-url (env->map env))
        headers (js/Headers. (.-headers req))]
    (.delete headers "host")
    (.set headers "content-type" "application/json")
    ;; 移行で変えた唯一の wire 値。SvelteKit 版は "sveltekit-edge-bff" を
    ;; 名乗っていたが、名乗りは事実なので嘘にしない（APP_FRAMEWORK も同時に
    ;; 変えてある）。
    (.set headers "x-etzhayyim-bff" "cljs-worker")
    (.set headers "x-etzhayyim-xrpc-method" nsid)
    (-> (.json req)
        (.catch (fn [_] #js {}))
        (.then
         (fn [input]
           (js/fetch url
                     #js {:method "POST"
                          :headers headers
                          :body (js/JSON.stringify
                                 #js {:jsonrpc "2.0"
                                      :id (.randomUUID js/crypto)
                                      :method "tools/call"
                                      :params #js {:name nsid :arguments input}})})))
        (.then (fn [resp]
                 (-> (.text resp)
                     (.then (fn [text]
                              (let [payload (try (when (seq text) (js/JSON.parse text))
                                                 (catch :default _ text))
                                    clj-payload (js->clj payload :keywordize-keys true)]
                                (if-not (.-ok resp)
                                  (json {:error "MCP router request failed"
                                         :upstream clj-payload}
                                        (.-status resp))
                                  (let [{:keys [ok? value error upstream]} (route/unwrap-mcp clj-payload)]
                                    (if ok?
                                      (json value 200)
                                      (json {:error error :upstream upstream} 502))))))))))
        (.catch (fn [e]
                  ;; 到達できなかったことを 200 で隠さない。移行時点で
                  ;; mcp.etzhayyim.com は NXDOMAIN なので、これは想像上の経路
                  ;; ではなく今日の既定の結末である。SvelteKit 版はここで
                  ;; framework の 500 HTML エラーページを返していた —— 移行で
                  ;; 意図的に変えた 2 点目（docs/adr/0001）。
                  (json {:error "MCP router unreachable"
                         :detail (str (.-message e))
                         :url url}
                        502))))))

(defn- page-response [env]
  (let [e (env->map env)]
    (->response
     (view/render {:css dds-css
                   :routes route/routes
                   :methods (decode-capabilities (:APP_CAPABILITIES e))
                   :vars (sort (keys e))
                   :mcp-url (route/mcp-router-url e)
                   :actor route/actor-did
                   :built-at nil})
     {:status 200
      :content-type "text/html; charset=utf-8"
      :cache "public, max-age=60"})))

(defn- health-response [env]
  (let [e (env->map env)]
    (json {:ok true
           :app "lo"
           :runtime "cljs"
           :actor route/actor-did
           :nanoid (or (:APP_NANOID e) route/default-nanoid)
           :routes (mapv :route/path route/routes)
           :methods (decode-capabilities (:APP_CAPABILITIES e))}
          200)))

(defn fetch-handler [req env _ctx]
  (let [url (js/URL. (.-url req))
        path (.-pathname url)
        {:keys [action nsid allow reason]} (route/dispatch (.-method req) path)]
    (case action
      :page   (page-response env)
      :health (health-response env)
      :xrpc   (proxy-xrpc req env nsid)
      :cors-preflight (->response nil {:status 204 :content-type "text/plain"
                                       :extra (cors-headers)})
      :bad-request (json {:error reason} 400)
      :method-not-allowed (->response (js/JSON.stringify #js {:error "Method Not Allowed"})
                                      {:status 405
                                       :content-type "application/json; charset=utf-8"
                                       :extra {"allow" allow}})
      (json {:error "Not Found"
             :routes (mapv (fn [r] (str (str/upper-case (name (:route/method r)))
                                        " " (:route/path r)))
                           route/routes)}
            404))))

(def handler #js {:fetch fetch-handler})
