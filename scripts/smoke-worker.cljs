#!/usr/bin/env nbb
;; smoke-worker — 実際にビルドされた bundle を import して叩く。
;;
;; ここが「deploy される成果物」に触る唯一の検査である。テスト
;; (test/lo/route_test.cljc) はソースの判断を固定するが、bundle が本当に
;; Worker の形で答えるかは言えない —— export の形、shadow の
;; :advanced-optimization、`shadow.resource/inline` で焼いた CSS、そして
;; `APP_CAPABILITIES` の JSON decode（worker.cljs 側にしか無い）は、どれも
;; ビルドを通って初めて存在する。
;;
;; Usage:  nbb scripts/smoke-worker.cljs [<dist/worker.js>]
;; Exit:   0 全て期待どおり · 1 期待と違う · 2 判定できなかった（bundle が無い等）

(require '["node:fs" :as fs] '["node:path" :as path] '["node:url" :as url]
         '[clojure.string :as str])

(def bundle
  "ESM の import は相対パスを package 名と読むので、必ず絶対パスに直してから
  file:// URL にする（`dist/worker.js` をそのまま渡すと『Cannot find package
  dist』になる。実測）。"
  (let [a (first (remove #(str/starts-with? % "--") *command-line-args*))]
    (.resolve path (or a "dist/worker.js"))))

(def failures (atom []))
(defn check! [label expected actual]
  (let [ok (= expected actual)]
    (println (str (if ok "PASS" "FAIL") "\t" label "\texpected=" (pr-str expected) "\tactual=" (pr-str actual)))
    (when-not ok (swap! failures conj label))))

(when-not (.existsSync fs bundle)
  (println (str "UNDETERMINED\tno bundle at " bundle))
  (println "Refusing to report a pass: build it first (see docs/operator-quickstart.md S4).")
  (js/process.exit 2))

(def sentinel
  "env の VALUE がページに出ていないことを確かめるための印。実在しそうな値
  （\"yoro\" 等）だと二つの問題がある: 他の文言と偶然一致しうるし、引用符ごと
  探すと renderer が \" を &quot; に escape するので**決して一致しない** ——
  つまり検査が構造的に落ちなくなる。app-ongakuka の移行で実測したので印を使う。"
  "SENTINEL-9f3a2c")

(def capabilities
  "wrangler.jsonc の APP_CAPABILITIES **そのままの文字列**。ここを config から
  離して書き写すと、decode の検査ではなく写経の検査になる。"
  "[\"createShipment\",\"updateShipment\",\"listShipments\",\"getShipment\",\"createRoute\",\"updateRoute\",\"listRoutes\",\"getRoute\"]")

(def env #js {"APP_NANOID" "dbdw2pcn"
              "APP_UI_TYPE" sentinel
              "APP_CAPABILITIES" capabilities})

(defn- call [h method path]
  (let [req (js/Request. (str "https://lo.etzhayyim.com" path) #js {:method method})]
    (-> (js/Promise.resolve ((.-fetch h) req env #js {}))
        (.then (fn [res] (-> (.text res)
                             (.then (fn [body] {:status (.-status res)
                                                :ct (.get (.-headers res) "content-type")
                                                :allow (.get (.-headers res) "allow")
                                                :cache (.get (.-headers res) "cache-control")
                                                :cors (.get (.-headers res) "access-control-allow-methods")
                                                :body body}))))))))

(-> (js/import (.-href (.pathToFileURL url bundle)))
    (.then
     (fn [m]
       (let [h (.-default m)]
         (check! "default export has fetch" true (fn? (.-fetch h)))
         (-> (js/Promise.all
              #js [(call h "GET" "/") (call h "GET" "/health")
                   (call h "POST" "/xrpc/") (call h "OPTIONS" "/xrpc/x")
                   (call h "GET" "/nope") (call h "POST" "/health")
                   (call h "GET" "/xrpc/x") (call h "POST" "/xrpc/a/b")])
             (.then
              (fn [[page health bad pre nf mna wrong-xrpc multi]]
                (check! "GET / status" 200 (:status page))
                (check! "GET / is html" true (str/includes? (or (:ct page) "") "text/html"))
                ;; ページは route 表から描かれる。表にある path が全部出ていること。
                (doseq [p ["/health" "/xrpc/:nsid"]]
                  (check! (str "page advertises " p) true (str/includes? (:body page) p)))
                ;; APP_CAPABILITIES を decode して 8 本すべてを完全修飾で出す。
                ;; 移行前のページは routeCount:0 / routes:[] / vars:[] を焼いて
                ;; いて、隣の config が宣言する 8 つを一つも出せなかった。
                (doseq [c ["createShipment" "updateShipment" "listShipments" "getShipment"
                           "createRoute" "updateRoute" "listRoutes" "getRoute"]]
                  (check! (str "page advertises " c) true
                          (str/includes? (:body page) (str "com.etzhayyim.apps.lo." c))))
                ;; env のキーは出す、値は出さない
                (check! "page shows a var key" true (str/includes? (:body page) "APP_NANOID"))
                (check! "page hides var values" false (str/includes? (:body page) sentinel))
                ;; DDS の CSS が bundle に焼かれている
                (check! "page carries the design system" true (str/includes? (:body page) "dads-table"))

                (check! "GET /health status" 200 (:status health))
                (check! "health names its routes" true (str/includes? (:body health) "/xrpc/:nsid"))
                (check! "health names its methods" true
                        (str/includes? (:body health) "com.etzhayyim.apps.lo.getRoute"))
                (check! "health names the actor" true
                        (str/includes? (:body health) "did:web:lo.etzhayyim.com"))

                ;; nsid 無しの XRPC は 400。文言は SvelteKit 版のまま。
                (check! "POST /xrpc/ status" 400 (:status bad))
                (check! "POST /xrpc/ reason" true (str/includes? (:body bad) "Missing XRPC method"))
                ;; 多段パスは 400 にしない —— deploy されていた [...path] と同じ
                ;; 意味論で、上流へ渡す（mcp.etzhayyim.com が NXDOMAIN なので
                ;; 実際には 502 になる。到達できないことを 200 で隠さない）。
                (check! "POST /xrpc/a/b is proxied, not rejected" false (= 400 (:status multi)))
                (check! "POST /xrpc/a/b says the router is unreachable" true
                        (str/includes? (:body multi) "MCP router unreachable"))

                (check! "OPTIONS preflight" 204 (:status pre))
                (check! "OPTIONS advertises methods" "POST,OPTIONS" (:cors pre))
                (check! "unknown path" 404 (:status nf))
                (check! "wrong method on /health" 405 (:status mna))
                (check! "wrong method on /xrpc" 405 (:status wrong-xrpc))
                (check! "405 names the allowed methods" "POST, OPTIONS" (:allow wrong-xrpc))

                (let [f @failures]
                  (if (seq f)
                    (do (println (str "FAILED\t" (count f) " check(s): " (str/join ", " f)))
                        (js/process.exit 1))
                    (do (println "OK\tthe built bundle answers as the route table says")
                        (js/process.exit 0))))))))))
    (.catch (fn [e]
              (println (str "UNDETERMINED\tcould not exercise the bundle: " (.-message e)))
              (js/process.exit 2))))
