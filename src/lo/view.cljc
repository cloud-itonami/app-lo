(ns lo.view
  "この appview の説明ページ。純 hiccup。

  基盤は `jp-go-dds`(デジタル庁デザインシステム) —— superproject の
  skill `kotoba-uiux` が定める新規 UI の base。色・寸法は `--hig-*` トークン
  契約で書き、raw hex も px フォントサイズも置かない。

  **表示する事実は引数で受け取る。ページの中に焼かない。**
  これは装飾の都合ではなく、docs/adr/0001 が記録した欠陥そのものへの答えで
  ある —— 移行前の `+page.svelte` は `routeCount: 0` / `routes: []` /
  `vars: []` を literal で持っていて、隣の `wrangler.jsonc` が route 2・var 8・
  capability 8 を宣言していることに気づけなかった。ここでは route 表も env の
  キーも capability も渡す側が持ち、ページは描くだけなので、両者がずれる余地が
  無い。"
  (:require [jp-go-dds.core :as dds]
            [jp-go-dds.page :as page]
            [jp-go-dds.tokens :as tokens]
            [clojure.string :as str]))

(def app-css
  "app 固有の最小 CSS。`--hig-*` 契約だけを使う(bridge が DADS の上に再定義する)。
  DADS を base にした app の下には `shitsuke.hig` が居ないので、bridge が運んで
  いないトークンは何にも解決しない —— 使うのは運ばれている 71 個の中だけ。"
  (str/join
   "\n"
   [".lo-lede { color: var(--hig-color-secondary-label); max-width: 42rem; }"
    ".lo-note { color: var(--hig-color-secondary-label); font-size: var(--hig-text-footnote-font-size); }"
    ".lo-mono { font-family: var(--hig-font-mono); }"]))

(defn- route-rows [routes]
  (mapv (fn [r]
          [(str/upper-case (name (:route/method r)))
           [:span {:class "lo-mono"} (:route/path r)]
           (:route/doc r)])
        routes))

(defn body
  "opts:
   :routes    lo.route/routes（この Worker が実際に答えるもの）
   :methods   宣言された XRPC method の完全修飾 NSID（APP_CAPABILITIES 由来）
   :vars      wrangler が渡した env のキー（値は出さない）
   :mcp-url   XRPC の中継先（route/mcp-router-url の戻り値）
   :actor     この appview の actor DID
   :built-at  bundle のビルド時刻（不明なら nil）"
  [{:keys [routes methods vars mcp-url actor built-at]}]
  (dds/container
   (dds/section
    {}
    (dds/heading 1 "Lo — Logistics Operations")
    [:p {:class "lo-lede"}
     "配送と経路を扱う appview の公開面。業務そのものは MCP router の先"
     "（AgentGateway / pod 側 LangServer）にあり、ここには無い —— この面は"
     "薄い edge であって、実装ではない。"])

   (dds/section
    {:title "この面が答えるもの"}
    (dds/table {:caption "公開ルート"
                :headers ["METHOD" "PATH" "何をするか"]
                :rows (route-rows routes)})
    [:p {:class "lo-note"}
     "この表は Worker の route 表そのものから描いている。ページに焼いた値では"
     "ないので、実際に答えるものと表示がずれない。"])

   (dds/section
    {:title "宣言されている XRPC メソッド"}
    (if (seq methods)
      [:div
       (dds/table {:caption "APP_CAPABILITIES が並べるもの"
                   :headers ["NSID"]
                   :rows (mapv (fn [m] [[:span {:class "lo-mono"} m]]) methods)})
       [:p {:class "lo-note"}
        "wrangler の APP_CAPABILITIES を読んで描いている。中継そのものは "
        "prefix を検査しない（deploy されていた SvelteKit の route と同じ）ので、"
        "この表は「宣言」であって「許可リスト」ではない。"]]
      [:p {:class "lo-note"} "APP_CAPABILITIES が渡されていない（ローカル描画）。"]))

   (dds/section
    {:title "実行時の設定"}
    (if (seq vars)
      [:div (into [:p] (interpose " "
                                  (map (fn [k] (dds/chip-label (name k))) vars)))
       [:p {:class "lo-note"} "キー名のみ。値は出さない。"]]
      [:p {:class "lo-note"} "env が渡されていない（ローカル描画）。"])
    [:p {:class "lo-note"} "XRPC の中継先: "
     [:span {:class "lo-mono"} mcp-url]]
    (when actor
      [:p {:class "lo-note"} "actor: " [:span {:class "lo-mono"} actor]]))

   (dds/section
    {:title "現在地"}
    [:p {:class "lo-lede"}
     "この appview は TypeScript/Svelte から ClojureScript へ移行済み。"
     "deploy される bundle は、いま読んでいるソースからコンパイルされたもので"
     "ある（docs/adr/0001）。"]
    (when built-at
      [:p {:class "lo-note"} "bundle build: " built-at]))))

(defn render
  "完全な HTML 文書。`css` は呼び出し側が渡す(ライブラリは I/O を持たない)。"
  [{:keys [css] :as opts}]
  (page/->page
   {:title "Lo — Logistics Operations"
    :description "配送と経路を扱う appview の公開面。業務は MCP router の先にある。"
    :lang "ja"
    :css css
    :app-css (str tokens/bridge-css "\n" app-css)}
   (body opts)))
