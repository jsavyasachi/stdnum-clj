#!/usr/bin/env bb

(ns parity-watch
  (:require [babashka.process :refer [shell]]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import [java.time Instant]))

(def upstream "arthurdejong/python-stdnum")
(def top-level-helpers #{"util" "exceptions" "numdb"})

(defn gh
  [& args]
  (-> (apply shell {:out :string :err :inherit} "gh" args)
      :out
      str/trim))

(defn gh-json
  [& args]
  (json/parse-string (apply gh args) true))

(defn basename
  [path]
  (-> path
      (str/split #"/")
      last
      (str/replace #"\.py$" "")))

(defn candidate-path?
  [path]
  (and (str/starts-with? path "stdnum/")
       (str/ends-with? path ".py")
       (let [rel (subs path (count "stdnum/"))
             base (basename path)]
         (and (not= "__init__" base)
              (not (and (not (str/includes? rel "/"))
                        (contains? top-level-helpers base)))))))

(defn path->kw
  [path]
  (when (candidate-path? path)
    (-> path
        (subs (count "stdnum/"))
        (str/replace #"\.py$" "")
        (str/replace "/" "-")
        keyword)))

(defn kw-sort
  [xs]
  (sort-by name xs))

(defn upstream-modules
  []
  (let [tree (:tree (gh-json "api" (str "repos/" upstream "/git/trees/master?recursive=1")))]
    (->> tree
         (keep (fn [{:keys [path]}]
                 (when-let [kw (path->kw path)]
                   [kw path])))
         (into {}))))

(defn changed-existing-types
  [pinned current ours]
  (if (= pinned current)
    []
    (->> (str/split-lines
          (gh "api" (str "repos/" upstream "/compare/" pinned "...master") "--jq" ".files[].filename"))
         (keep path->kw)
         (filter ours)
         set
         kw-sort)))

(defn merged-since
  [pinned-date]
  (->> (gh-json "pr" "list" "-R" upstream "--state" "merged" "--limit" "100"
                "--json" "number,title,mergedAt")
       (filter #(pos? (compare (:mergedAt %) pinned-date)))
       (sort-by :mergedAt #(compare %2 %1))))

(defn bullet-lines
  [xs f]
  (if (seq xs)
    (str/join "\n" (map f xs))
    "_none_"))

(defn section
  [title xs f]
  (str "## " title " (" (count xs) ")\n\n" (bullet-lines xs f)))

(defn stale-aliases
  [aliases upstream-keys ours]
  (->> aliases
       (mapcat (fn [[src target]]
                 (cond-> []
                   (not (contains? upstream-keys src))
                   (conj (str src " -> " target " (source no longer upstream)"))

                   (not (contains? ours target))
                   (conj (str src " -> " target " (target no longer a type)")))))
       sort))

(defn report
  [{:keys [current pinned generated aliases ignore upstream-keys stale-aliases
           new-modules changed open-prs merged removed]}]
  (str "# Upstream parity report - current `" (subs current 0 7)
       "`, pinned `" (subs pinned 0 7) "`, generated " generated " UTC\n\n"
       "renames applied: " (count aliases)
       " · ignored: " (count ignore)
       " · upstream modules: " (count upstream-keys) "\n\n"
       (section "⚠️ Stale aliases (fix .parity/aliases.edn)" stale-aliases
                (fn [violation] (str "- " violation)))
       "\n\n"
       (section "🆕 New modules (missing types)" new-modules
                (fn [[kw path]] (str "- `" kw "` - " path)))
       "\n\n"
       (section "♻️ Changed existing types — re-verify vectors" changed
                (fn [kw] (str "- `" kw "`")))
       "\n\n"
       (section "🔀 Open upstream PRs" open-prs
                (fn [{:keys [number title url]}] (str "- #" number " " title " (" url ")")))
       "\n\n"
       (section "✅ Merged upstream since last pin" merged
                (fn [{:keys [number title mergedAt]}] (str "- #" number " " title " (" mergedAt ")")))
       "\n\n"
       (section "➖ In stdnum-clj, not upstream" removed
                (fn [kw] (str "- `" kw "`")))
       "\n\n"
       "Reconcile, then advance `.parity/upstream-sha` to `" current "`.\n"))

(defn -main
  []
  (let [current (gh "api" (str "repos/" upstream "/commits/master") "--jq" ".sha")
        pinned (str/trim (slurp ".parity/upstream-sha"))
        ours (set (edn/read-string (slurp ".parity/types.edn")))
        aliases (edn/read-string (slurp ".parity/aliases.edn"))
        ignore (edn/read-string (slurp ".parity/ignore.edn"))
        upstream-by-kw (upstream-modules)
        upstream-keys (set (keys upstream-by-kw))
        alias-src (set (keys aliases))
        alias-targets (set (vals aliases))
        new-modules (map (fn [kw] [kw (upstream-by-kw kw)])
                         (kw-sort (set/difference upstream-keys ours alias-src ignore)))
        removed (kw-sort (set/difference ours upstream-keys alias-targets))
        stale (stale-aliases aliases upstream-keys ours)
        changed (changed-existing-types pinned current ours)
        open-prs (gh-json "pr" "list" "-R" upstream "--state" "open" "--limit" "100"
                          "--json" "number,title,url")
        pinned-date (gh "api" (str "repos/" upstream "/commits/" pinned)
                        "--jq" ".commit.committer.date")
        merged (merged-since pinned-date)
        body (report {:current current
                      :pinned pinned
                      :generated (str (Instant/now))
                      :aliases aliases
                      :ignore ignore
                      :upstream-keys upstream-keys
                      :stale-aliases stale
                      :new-modules new-modules
                      :changed changed
                      :open-prs open-prs
                      :merged merged
                      :removed removed})]
    (.mkdirs (io/file ".parity"))
    (spit ".parity/report.md" body)
    (println body)))

(-main)
