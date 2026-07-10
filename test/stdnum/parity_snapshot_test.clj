(ns stdnum.parity-snapshot-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [stdnum.core :as stdnum]))

(deftest parity-snapshot-matches-types
  (let [snapshot (set (edn/read-string (slurp ".parity/types.edn")))
        current (set (map (comp keyword name) stdnum/types))]
    (is (= snapshot current)
        ".parity/types.edn is stale; regenerate it (see .parity/README.md).")))
