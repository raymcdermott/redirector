(ns redirector.web
  (:require [compojure.core :refer [defroutes GET ANY]]
            [compojure.handler :refer [site]]
            [compojure.route :as route]
            [ring.adapter.jetty :as jetty]
            [monger.core :as mg]
            [monger.collection :as mc]
            [clojure.java.io :as io]
            [taoensso.carmine :as car :refer (wcar)]
            [environ.core :refer [env]]))

; -------*** MONGO HELPERS ... push out to another file
;
; read data from Mongo ... source of truth

(def mongo-url (or "mongodb://localhost" (env :MONGO_URL)))
(def mongo-collection (or "redirections" (env :MONGO_COLLECTION)))

(defn mongo-action
  "Run a data function on MongoDB wrapped by a connect / disconnect"
  ([db-fn data]
   (mongo-action mongo-url db-fn data))

  ([db-uri db-fn data]
   {:pre [(string? db-uri)]}
   (mongo-action db-uri mongo-collection db-fn data))

  ([db-uri collection db-fn data]
   {:pre [(string? db-uri) (string? collection)]}
   (let [{:keys [conn db]} (mg/connect-via-uri db-uri)
         result (db-fn db collection data)]
     (mg/disconnect conn)
     result)))

(defn get-configuration-from-mongo [brand country]
  "Obtain the redirect configuration data from MongoDB for country"
  (let [redirection-data (mongo-action mc/find-one-as-map {:brand brand, :country country} [:domain :bucket])]
    (if (nil? redirection-data)
      (throw (Exception. (str "Failed, cannot find cache domain for : " country)))
      (str (:domain redirection-data) "/" (:bucket redirection-data)))))

; -------*** REDIS HELPERS ... push out to another file
;
; read data from REDIS ... source of speed

(def redis-url (or "redis:default:2600" (env :REDIS_URL)))
(def redis-conn {:pool {} :spec {:uri redis-url}})
(defmacro wcar* [& body] `(car/wcar redis-conn ~@body))

; set a default of 30 seconds for data expiry in the REDIS cache
(def redis-ttl (or (env :REDIS_TTL_SECONDS) 30))

(defn setkv
  ([k v]
   (wcar* (car/set k v redis-ttl)))
  ([k v ttl]
   (wcar* (car/set k v ttl))))

(defn getv [k]
  (wcar* (car/get k)))

; -------*** WORK
;
; get data from REDIS or get data from Mongo

; if data OK return 301 with new URL from the domain cache
; if no data, 404
; if exception 500

(defn get-route
  ([country]
   (country "TOYOTA"))
  ([brand country]
   (get-configuration-from-mongo brand country)))

(defn respond [country resource]
  {:status 200
   :body   (get-route country)})



; -------*** EXPOSE TO THE WEB
;

(defroutes app
           (GET "/:country/*" [country *]
                (time (respond country *)))
           (ANY "*" []
                (route/not-found (slurp (io/resource "404.html")))))


; -------*** START WEB SERVER
;

(defn -main [& [port]]
  (let [port (Integer. (or port (env :port) 5000))]
    (jetty/run-jetty (site #'app) {:port port :join? false})))

;; For interactive development:
;; (.stop server)
;; (def server (-main))
