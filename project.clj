(defproject net.clojars.savya/stdnum-clj "0.21.0"
  :description "Unified validation, parsing, and formatting of standard identifier numbers (credit cards, IBAN/BIC, ISBN, ISIN, bank routing, and more) for Clojure - an idiomatic facade over Apache Commons Validator and iban4j."
  :url "https://github.com/jsavyasachi/stdnum-clj"
  :license {:name "Eclipse Public License 1.0" :url "https://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git" :url "https://github.com/jsavyasachi/stdnum-clj"}
  :dependencies [[commons-validator/commons-validator "1.10.1"]
                 [org.iban4j/iban4j "3.2.13-RELEASE"]
                 [org.clojure/data.json "2.5.2"]]
  :global-vars {*warn-on-reflection* true}
  :test-selectors {:default (complement :integration)
                   :integration :integration
                   :all (constantly true)}
  :profiles {:provided {:dependencies [[org.clojure/clojure "1.12.0"]]}
             :dev {:dependencies [[org.clojure/clojure "1.12.0"]]}
             :clojure-1-10 {:dependencies [[org.clojure/clojure "1.10.3"]]}
             :clojure-1-11 {:dependencies [[org.clojure/clojure "1.11.4"]]}
             :clojure-1-12 {:dependencies [[org.clojure/clojure "1.12.0"]]}}
  :aliases {"all" ["with-profile" "+clojure-1-10:+clojure-1-11:+clojure-1-12"]}
  :deploy-repositories [["clojars" {:url "https://repo.clojars.org"
                                    :username :env/clojars_username
                                    :password :env/clojars_password
                                    :sign-releases false}]])
