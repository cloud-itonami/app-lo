(ns lo.route-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [lo.route :as route]
            [lo.view :as view]))

(deftest dispatch-page-and-health
  (is (= :page (:action (route/dispatch "GET" "/"))))
  (is (= :health (:action (route/dispatch "GET" "/health"))))
  (is (= :method-not-allowed (:action (route/dispatch "POST" "/health"))))
  (is (= :not-found (:action (route/dispatch "GET" "/nope"))))
  (testing "撤去した src/app.ts の別名 /_app/meta は持ち越していない"
    (is (= :not-found (:action (route/dispatch "GET" "/_app/meta"))))))

(deftest dispatch-xrpc
  (testing "nsid はそのまま渡す"
    (is (= {:action :xrpc :nsid "com.etzhayyim.apps.lo.createShipment"}
           (route/dispatch "POST" "/xrpc/com.etzhayyim.apps.lo.createShipment"))))
  (testing "多段パスも nsid として通す —— deploy されていた SvelteKit の
            rest parameter [...path] と同じ意味論。移行はここを変えない"
    (is (= {:action :xrpc :nsid "a/b"} (route/dispatch "POST" "/xrpc/a/b"))))
  (testing "空だけが 400。文言も SvelteKit 版のまま"
    (is (= {:action :bad-request :reason "Missing XRPC method"}
           (route/dispatch "POST" "/xrpc/")))
    (is (= :bad-request (:action (route/dispatch "POST" "/xrpc")))))
  (testing "preflight と method"
    (is (= :cors-preflight (:action (route/dispatch "OPTIONS" "/xrpc/x"))))
    (is (= :cors-preflight (:action (route/dispatch "OPTIONS" "/xrpc"))))
    (is (= {:action :method-not-allowed :allow "POST, OPTIONS"}
           (route/dispatch "GET" "/xrpc/x")))))

(deftest mcp-url-resolution
  (is (= "https://mcp.etzhayyim.com/xrpc/com.etzhayyim.mcp.message"
         (route/mcp-router-url {})))
  (is (= "https://a.example/x" (route/mcp-router-url {:AGENTGATEWAY_MCP_ROUTER_URL "https://a.example/x/"})))
  (testing "空白だけの設定は未設定として扱う"
    (is (= "https://b.example" (route/mcp-router-url {:AGENTGATEWAY_MCP_ROUTER_URL "   "
                                                      :MCP_ROUTER_URL "https://b.example"})))))

(deftest capability-shaping
  (testing "短い名前には NSID prefix を足す"
    (is (= ["com.etzhayyim.apps.lo.createShipment" "com.etzhayyim.apps.lo.getRoute"]
           (route/capability-nsids ["createShipment" "getRoute"]))))
  (testing "既に完全修飾なものは二重に付けない"
    (is (= ["com.etzhayyim.apps.lo.listRoutes"]
           (route/capability-nsids ["com.etzhayyim.apps.lo.listRoutes"]))))
  (testing "空・非文字列は落とす（宣言が壊れていてもページは描ける）"
    (is (= [] (route/capability-nsids ["" "   " nil 42])))))

(deftest unwrap
  (is (= {:ok? true :value {:a 1}} (route/unwrap-mcp {:result {:structuredContent {:a 1}}})))
  (is (= {:ok? true :value {:a 1}} (route/unwrap-mcp {:result {:a 1}})))
  (testing "上流が空 body なら {} —— SvelteKit 版の `structured ?? {}`"
    (is (= {:ok? true :value {}} (route/unwrap-mcp nil))))
  (is (false? (:ok? (route/unwrap-mcp {:error {:message "boom"}})))))

(deftest page-shows-the-real-data
  (testing "ページは渡された値から描く。0 も [] も焼かない（docs/adr/0001 の欠陥）"
    (let [methods (route/capability-nsids ["createShipment" "getRoute"])
          html (view/render {:css "/*x*/" :routes route/routes
                             :methods methods
                             :vars [:APP_NANOID :APP_UI_TYPE]
                             :mcp-url "https://mcp.example/x"
                             :actor route/actor-did})]
      (doseq [r route/routes]
        (is (str/includes? html (:route/path r))
            (str (:route/path r) " がページに出ていない")))
      (doseq [m methods]
        (is (str/includes? html m) (str m " がページに出ていない")))
      (is (str/includes? html "APP_NANOID"))
      (is (str/includes? html "https://mcp.example/x"))
      (is (str/includes? html route/actor-did))
      (testing "移行前のページが出していた嘘の文言は無い"
        (is (not (str/includes? html "No public route is declared")))
        (is (not (str/includes? html "No public vars are declared")))))))

(deftest relay-headers-forwards-what-it-received
  (testing "移行前から authorization は届いていた。落ちていたのは長さの方"
    (let [h (route/relay-headers [["Host" "x.example"]
                                  ["Authorization" "Bearer t"]
                                  ["Content-Length" "9"]
                                  ["Content-Encoding" "gzip"]
                                  ["X-Trace" "abc"]]
                                 "com.a.b")]
      (is (= "Bearer t" (get h "authorization"))
          "authorization が落ちている —— preflight はこれを許可すると言っている")
      (is (= "abc" (get h "x-trace"))
          "呼び手が付けた header が落ちている")
      (is (nil? (get h "host")) "host は宛先が変わるので渡さない")
      (is (nil? (get h "content-length"))
          "呼び手の長さを載せると上流への fetch が失敗し、502 になる（実測）")
      (is (nil? (get h "content-encoding"))
          "body を詰め直すので元の encoding も嘘になる")
      (is (= "application/json" (get h "content-type")))
      (is (= "com.a.b" (get h "x-etzhayyim-xrpc-method")))
      (is (= "cljs-worker" (get h "x-etzhayyim-bff"))))))
