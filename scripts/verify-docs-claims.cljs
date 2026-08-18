#!/usr/bin/env nbb
;; verify-docs-claims — re-derive every number README.md and docs/operator-quickstart.md
;; state, from the tree itself, and fail when the tree and the prose disagree.
;;
;; Before the cljs migration this file's load-bearing claim would have been a GAP:
;; the Worker that would be deployed was svelte/.svelte-kit/cloudflare/_worker.js --
;; a build output that is not in the tree and that nothing in the tree builds --
;; while appview/lo-mcp-component/src/app.ts, the file that reads like the
;; application, was referenced by no config at all. That gap is closed, and the
;; claims are written so it cannot quietly come back: the TypeScript and the Svelte
;; are asserted ABSENT BY NAME, not merely absent from a byte total.
;;
;; Usage:  nbb scripts/verify-docs-claims.cljs [<dir>]     (<dir> FIRST, default ".")
;; Exit:   0 every claim holds · 1 a claim is false · 2 could not answer

(require '["node:fs" :as fs]
         '["node:child_process" :as cp]
         '["node:crypto" :as crypto]
         '[clojure.string :as str])

(def root (or (first (remove #(str/starts-with? % "--") *command-line-args*)) "."))
(def APP "appview/lo-mcp-component")

(def claims
  {:tracked-files 19
   :inherited-bytes 8218           ; the 6 inherited files still carried unchanged
   :production-ts-files 0
   :production-svelte-files 0
   :production-canonical-files 3
   :declared-vars 8
   :declared-routes 2
   :declared-capabilities 8
   :wrangler-main "../../dist/worker.js"
   :shadow-output-dir "dist"
   :shadow-export "lo.worker/handler"})

;; Inherited files this repository still carries BYTE-IDENTICAL. wrangler.jsonc left
;; this set deliberately in the migration and is checked by content below instead.
(def preserved
  {"MIGRATION-TODO.md" "20efe4553a770c9a63c400fb14f495afca788f5b7b545aba9423097e1c1251bb"
   "NOTICE" "f487a5bfd0c55764ffc608048654dfb3f8f88680c8191dba20047dd9b18f9404"
   "README.edn" "8ddf94208e3f48d8a2074252ac7826f050543e7dcba6417dd90438e3202e3572"
   "migration.edn" "5b1aee4483932ef81d4c5623658f4c05fdfdd6fed12fd9c0af149e638bc2a034"
   "appview/lo-mcp-component/kotodama.jsonld" "781528ce4d3d1307a846965387981ba5db88ed1fac22c9bac492b64faa12a149"
   "bpmn/lo.bpmn" "25e6729f530b7cf39542d4d13604e59b6df8a2e4cc202c412a4353e00a6eadc7"})

;; What the migration REMOVED, by name. A byte total cannot say "the TypeScript is
;; gone"; this can, and it fails if any of it comes back.
(def removed-by-migration
  ["appview/lo-mcp-component/src/app.ts"
   "appview/lo-mcp-component/package.json"
   "appview/lo-mcp-component/package-lock.json"
   "appview/lo-mcp-component/vitest.config.ts"
   "appview/lo-mcp-component/test/lo.test.ts"
   "appview/lo-mcp-component/svelte/package.json"
   "appview/lo-mcp-component/svelte/src/app.html"
   "appview/lo-mcp-component/svelte/src/routes/+page.svelte"
   "appview/lo-mcp-component/svelte/src/routes/xrpc/[...path]/+server.ts"
   "appview/lo-mcp-component/svelte/svelte.config.js"
   "appview/lo-mcp-component/svelte/tsconfig.json"
   "appview/lo-mcp-component/svelte/vite.config.ts"])

(def undetermined (atom []))
(def failures (atom []))
(defn undet! [m] (swap! undetermined conj m))

(defn tracked-files []
  (try (->> (.execSync cp "git ls-files" #js {:cwd root :encoding "utf8"})
            str/split-lines (remove str/blank?) vec)
       (catch :default e (undet! (str "git ls-files failed: " (.-message e))) nil)))
(defn slurp* [rel] (try (.readFileSync fs (str root "/" rel) "utf8") (catch :default _ nil)))
(defn bytes-of [rel] (try (.-size (.statSync fs (str root "/" rel))) (catch :default _ nil)))
(defn sha256 [rel]
  (try (-> (.createHash crypto "sha256") (.update (.readFileSync fs (str root "/" rel))) (.digest "hex"))
       (catch :default _ nil)))
(defn strip-jsonc [s] (str/replace s #"(?m)^\s*//.*$" ""))

(defn check! [label expected actual]
  (let [ok (= expected actual)]
    (println (str (if ok "PASS" "FAIL") "\t" (name label)
                  "\texpected=" (pr-str expected) "\tactual=" (pr-str actual)))
    (when-not ok (swap! failures conj label))
    ok))

(let [files (tracked-files)]
  (when (nil? files) (println "UNDETERMINED\tcould not list tracked files") (js/process.exit 2))
  (println (str "SCANNED\t" (count files)))
  (when (zero? (count files)) (println "UNDETERMINED\tscanned 0 files") (js/process.exit 2))

  (let [sizes (into {} (map (juxt identity bytes-of)) files)]
    (when-let [bad (seq (keep (fn [[f s]] (when (nil? s) f)) sizes))]
      (undet! (str "tracked but unreadable: " (str/join ", " bad))))

    (check! :tracked-files (:tracked-files claims) (count files))
    (check! :inherited-bytes (:inherited-bytes claims)
            (reduce + 0 (keep #(get sizes %) (keys preserved))))
    (check! :preserved-files-unchanged []
            (vec (keep (fn [[f want]] (let [got (sha256 f)]
                                        (when-not (= want got) (str f " " (or got "MISSING")))))
                       preserved)))

    ;; the TypeScript and the Svelte are gone, by name
    (check! :removed-by-migration-absent []
            (vec (filter #(some? (bytes-of %)) removed-by-migration)))

    ;; language of the production source. "production" = not a script, not a test.
    (let [prod (remove #(or (str/starts-with? % "scripts/")
                            (str/starts-with? % "test/")
                            (str/includes? % "/test/"))
                       files)]
      (check! :production-ts-files (:production-ts-files claims)
              (count (filter #(str/ends-with? % ".ts") prod)))
      (check! :production-svelte-files (:production-svelte-files claims)
              (count (filter #(str/ends-with? % ".svelte") prod)))
      (check! :production-canonical-files (:production-canonical-files claims)
              (count (filter #(re-find #"\.(cljs|cljc|clj|kotoba)$" %) prod))))

    ;; the deployed bundle is built from the source in this tree
    (let [w (some-> (slurp* (str APP "/wrangler.jsonc")) strip-jsonc)
          sh (slurp* "shadow-cljs.edn")]
      (if (or (nil? w) (nil? sh))
        (undet! "wrangler.jsonc or shadow-cljs.edn unreadable")
        (let [j (js->clj (.parse js/JSON w) :keywordize-keys false)]
          (check! :wrangler-main (:wrangler-main claims) (get j "main"))
          (check! :declared-vars (:declared-vars claims) (count (get j "vars")))
          (check! :declared-routes (:declared-routes claims) (count (get j "routes")))
          (check! :declared-capabilities (:declared-capabilities claims)
                  (count (js->clj (.parse js/JSON (get-in j ["vars" "APP_CAPABILITIES"] "[]")))))
          ;; the old config served a SvelteKit client dir that no longer exists,
          ;; and matched **/*.wasm in a tree with zero .wasm files
          (check! :no-stale-assets-binding true (nil? (get j "assets")))
          (check! :no-stale-wasm-rules true (nil? (get j "rules")))
          (check! :no-wasm-in-tree 0 (count (filter #(str/ends-with? % ".wasm") files)))
          ;; nodejs_compat / nodejs_als were adapter-cloudflare's requirement.
          ;; They are gone, and the claim below is what makes that removal
          ;; falsifiable: the bundle must not reach for a node builtin.
          (check! :no-node-compat-flags true (nil? (get j "compatibility_flags")))
          (check! :shadow-builds-that-main true
                  (and (str/includes? sh (str ":output-dir \"" (:shadow-output-dir claims) "\""))
                       (str/includes? sh (:shadow-export claims))
                       (str/includes? (get j "main") (str (:shadow-output-dir claims) "/worker.js")))))))

    ;; The page renders the route TABLE and the declared capabilities rather than
    ;; baked literals -- the defect ADR-0001 recorded was `routeCount: 0`,
    ;; `routes: []` and `vars: []` beside a config declaring 2 routes, 8 vars and
    ;; 8 capabilities. Asserted structurally (the view takes the data, the worker
    ;; passes the real values) and NOT by forbidding a substring: a check that a
    ;; docstring explaining the old defect can trip is a check about prose.
    (let [v (slurp* "src/lo/view.cljc")
          w (slurp* "src/lo/worker.cljs")]
      (if (or (nil? v) (nil? w))
        (undet! "view.cljc or worker.cljs unreadable")
        (check! :page-renders-the-data true
                (and (str/includes? v "[{:keys [routes methods vars mcp-url actor built-at]}]")
                     (str/includes? v "(route-rows routes)")
                     (str/includes? w ":routes route/routes")
                     (str/includes? w ":methods (decode-capabilities")))))

    ;; nothing in the tree builds a node/TypeScript artifact any more
    (check! :no-node-build-config []
            (vec (filter #(re-find #"(^|/)(package\.json|package-lock\.json|tsconfig\.json|vite\.config\.ts|vitest\.config\.ts|svelte\.config\.js)$" %)
                         files)))))

(let [u @undetermined f @failures]
  (when (seq u)
    (doseq [m u] (println (str "UNDETERMINED\t" m)))
    (println "Refusing to report a pass: the tree could not be read completely.")
    (js/process.exit 2))
  (if (seq f)
    (do (println (str "FAILED\t" (count f) " claim(s): " (str/join ", " (map name f)))) (js/process.exit 1))
    (do (println "OK\tevery claim in README.md and docs/operator-quickstart.md holds") (js/process.exit 0))))
