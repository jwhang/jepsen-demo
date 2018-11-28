(ns jepsen.etcdemo
  (:require [clojure.tools.logging :refer [info warn]]
            [clojure.string :as str]
            [jepsen [cli :as cli]
                    [control :as c]
                    [db :as db]
                    [tests :as tests]]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]))

(def dir "/opt/etcd")

(defn db
  "Constructs a database for given etcd version"
  [version]
  (reify db/DB
    (setup! [_ test node]
      (info "installing etcd" version)
      (c/su
        (let [url (str "https://storage.googleapis.com/etcd/" version
                       "/etcd-" version "-linux-amd64.tar.gz")]
          (cu/install-archive! url dir))))
    (teardown! [_ test node]
      (info "tearing down etcd"))))

(defn etcd-test
  "Take cli options and construct test map"
  [opts]
  (merge tests/noop-test
         opts
         {:name "etcd"
          :os   debian/os
          :db   (db "v3.1.5")}))

(defn -main
  "Runs command line args!"
  ; Comments with semicolons
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn etcd-test})
                   (cli/serve-cmd))
            args))
