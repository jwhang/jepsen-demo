(ns jepsen.etcdemo
  (:require [clojure.tools.logging :refer [info warn]]
            [clojure.string :as str]
            [knossos [model :as model]]
            [jepsen [cli :as cli]
                    [client :as client]
                    [control :as c]
                    [db :as db]
                    [tests :as tests]
                    [independent :as independent]
                    [nemesis :as nemesis]
                    [generator :as gen]
                    [checker :as checker]]
            [slingshot.slingshot :refer [try+]]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]
            [jepsen.checker.timeline :as timeline]
            [verschlimmbesserung.core :as v]))

(def dir "/opt/etcd")
(def binary "etcd")
(def pidfile (str dir "/etcd.pid"))
(def logfile (str dir "/etcd.log"))

(defn node-url
  [node port]
  (str "http://" node ":" port))

(defn client-url
  [node]
  (node-url node 2379))

(defn peer-url
  [node]
  (node-url node 2380))

(defn initial-cluster
  [test]
  (->> (:nodes test)
    (map (fn [node]
         (str node "=" (peer-url node))))
    (str/join ",")))

(defn db
  "Constructs a database for given etcd version"
  [version]
  (reify db/DB
    (setup! [_ test node]
      (info "installing etcd" version)
      (c/su
        (let [url (str "https://storage.googleapis.com/etcd/" version
                       "/etcd-" version "-linux-amd64.tar.gz")]
          (cu/install-archive! url dir)
          (cu/start-daemon!
            {:logfile logfile
             :pidfile pidfile
             :chdir   dir}
            binary
            :--log-output         :stderr
            :--name               node
            :--listen-peer-urls   (peer-url node)
            :--listen-client-urls (client-url node)
            :--advertise-client-urls       (client-url node)
            :--initial-cluster-state       :new
            :--initial-advertise-peer-urls (peer-url node)
            :--initial-cluster             (initial-cluster test))
          (Thread/sleep 10000))))
    (teardown! [_ test node]
      (info "tearing down etcd")
      (cu/stop-daemon! binary pidfile)
      (c/su (c/exec :rm :-rf dir)))

    db/LogFiles
    (log-files [_ test node]
      [logfile])))

(defn r [test process]
  {:type :invoke, :f :read, :value nil})

(defn w [test process]
  {:type :invoke, :f :write, :value (rand-int 5)})

(defn cas [test process]
  {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defn parse-long
  [x]
  (when x
    (Long/parseLong x)))

(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (assoc this :conn (v/connect (client-url node)
                                 {:timeout 5000})))

  (setup! [this test])

  (invoke! [this test op]
    (let [[k v] (:value op)]
    (try+
      (case (:f op)
        :read  (let [value (-> conn
                               (v/get k {:quorum? true})
                               parse-long)]
                 (assoc op
                        :type :ok
                        :value (independent/tuple k value)))
        :write (do (v/reset! conn k v)
                   (assoc op :type :ok))
        :cas (let [[old new] v]
               (assoc op :type (if (v/cas! conn k old new)
                                 :ok
                                 :fail))))
        (catch java.net.SocketTimeoutException e
          (assoc op
                 :type  (if (= :read (:f op)) :fail :info)
                 :error :timeout))
        (catch java.net.ConnectException e
          (assoc op :type :fail, :error :connect))
        (catch [:errorCode 100] e
          (assoc op :type :fail, :error :not-found)))))

    (teardown! [this test])

    (close! [this test]))


(defn etcd-test
  "Take cli options and construct test map"
  [opts]
  (merge tests/noop-test
         opts
         {:name "etcd"
          :os   debian/os
          :db   (db "v3.1.5")
          :client (Client. nil)
          :nemesis (nemesis/partition-random-halves)
          :generator  (->> (independent/concurrent-generator
                             10
                             (range)
                             (fn [k]
                               (->> (gen/mix [r w cas])
                                    (gen/stagger 1/10)
                                    (gen/limit 100))))
                           (gen/nemesis
                             (gen/seq (cycle [(gen/sleep 5)
                                              {:type :info, :f :start}
                                              (gen/sleep 5)
                                              {:type :info, :f :stop}])))
                           (gen/time-limit (:time-limit opts)))
          :checker (checker/compose {:perf   (checker/perf)
                                     :indep (independent/checker
                                              (checker/compose
                                                {:timeline (timeline/html)
                                                 :linear (checker/linearizable)}))})
          :model    (model/cas-register)}))

(defn -main
  "Runs command line args!"
  ; Comments with semicolons
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn etcd-test})
                   (cli/serve-cmd))
            args))
