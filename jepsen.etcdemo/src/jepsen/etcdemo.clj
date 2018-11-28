(ns jepsen.etcdemo
  (:require [jepsen.cli :as cli]
            [jepsen.tests :as tests]))


(defn etcd-test
  "Take cli options and construct test map"
  [opts]
  (merge tests/noop-test
         opts))

(defn -main
  "Runs command line args!"
  ; Comments with semicolons
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn etcd-test})
                   (cli/serve-cmd))
            args))
