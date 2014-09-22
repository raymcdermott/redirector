(ns redirector.web
  (:require [compojure.core :refer [defroutes GET ANY]]
            [compojure.handler :refer [site]]
            [compojure.route :as route]
            [ring.util.response :as response]
            [ring.adapter.jetty :as jetty]
            [monger.core :as mg]
            [monger.collection :as mc]
            [clojure.stacktrace :as trace]
            [clojure.java.io :as io]
            [environ.core :refer [env]]
            [taoensso.carmine :as redis :refer (wcar)]))

; -------*** MONGO HELPERS ... push out to another file
;
; read data from Mongo ... source of truth

(defn mongo-query [query fields]
  (let [mongo-uri (or (env :mongo-url) "mongodb://localhost/test")
        {:keys [conn db]} (mg/connect-via-uri mongo-uri)
        mongo-collection (or (env :mongo-collection) "redirections")
        result (mc/find-one-as-map db mongo-collection query fields)]
    (mg/disconnect conn)
    result))

(defn get-route-from-mongo [brand country]
  (let [{:keys [domain bucket]} (mongo-query {:brand brand :country country} [:domain :bucket])]
    (and domain bucket (str domain "/" bucket))))

; -------*** REDIS HELPERS ... push out to another file
;
; read data from REDIS ... source of speed

(defn get-redis-connection-pool []
  (let [spec {:pool {} :spec (if-let [uri (env :redis-url)]
                               {:uri uri}
                               {:host "127.0.0.1" :port 6379})}]
    spec))

; set a default of 30 seconds for data expiry in the REDIS cache
(def redis-ttl (or (env :redis-ttl-seconds) 30))

(defn set-route-in-cache!
  ([k v]
   (set-route-in-cache! k v redis-ttl))
  ([k v ttl]
   (if (and k v ttl)
     (redis/wcar (get-redis-connection-pool) (redis/setex k ttl v)))))

(defn get-route-from-cache [k]
  (if-let [value (redis/wcar (get-redis-connection-pool) (redis/get k))]
    value))

; -------*** WORK
;
; get data from REDIS or get data from Mongo
; and set the value into REDIS
;

(defn get-route [brand country]
  (if-let [cached-route (get-route-from-cache (str brand country))]
    cached-route
    (let [route (get-route-from-mongo brand country)]
      (set-route-in-cache! (str brand country) route)
      route)))

(defn respond [brand country resource]
  (if-let [cache-domain (get-route brand country)]
    (response/redirect (str cache-domain "/" country "/" resource))
    (response/not-found (str "Cannot locate cache domain for brand: " brand " and country: " country))))


; -------*** EXPOSE TO THE WEB
;

(defroutes app
           (GET "/:brand/:country/*" [brand country *]
                (time (respond brand country *)))
           (ANY "*" []
                (route/not-found "You must use a REST style to specify brand and country keys in the URL")))

; Until the cardb devs get their act together ... put some records in the collection

(defn seed_mongo []
  (let [mongo-uri (or (env :mongo-url) "mongodb://localhost/test")
        {:keys [conn db]} (mg/connect-via-uri mongo-uri)
        mongo-collection (or (env :mongo-collection) "redirections")]
    (mc/drop-indexes db mongo-collection)
    (mc/save db mongo-collection {:brand "LEXUS" :country "IT" :domain "https://s3-eu-west-1.amazonaws.com" :bucket "cache-1"})
    (mc/save db mongo-collection {:brand "LEXUS" :country "FR" :domain "https://s3-eu-west-1.amazonaws.com" :bucket "cache-1"})
    (mc/save db mongo-collection {:brand "LEXUS" :country "ES" :domain "https://s3-eu-west-1.amazonaws.com" :bucket "cache-1"})
    (mc/save db mongo-collection {:brand "LEXUS" :country "UK" :domain "https://s3-eu-west-1.amazonaws.com" :bucket "cache-1"})
    (mc/save db mongo-collection {:brand "TOYOTA" :country "IT" :domain "https://s3-eu-west-1.amazonaws.com" :bucket "cache-1"})
    (mc/save db mongo-collection {:brand "TOYOTA" :country "FR" :domain "https://s3-eu-west-1.amazonaws.com" :bucket "cache-1"})
    (mc/save db mongo-collection {:brand "TOYOTA" :country "ES" :domain "https://s3-eu-west-1.amazonaws.com" :bucket "cache-1"})
    (mc/save db mongo-collection {:brand "TOYOTA" :country "UK" :domain "https://s3-eu-west-1.amazonaws.com" :bucket "cache-1"})
    (mc/ensure-index db mongo-collection {:brand 1, :country 1}, {:unique true})
    (mg/disconnect conn)))


; -------*** START WEB SERVER
;

(defn -main [& [port]]
  (let [port (Integer. (or port (env :port) 5000))]
    (if (env :seed-mongo) (seed_mongo))
    (jetty/run-jetty (site #'app) {:port port :join? false})))

;; For interactive development:
;; (.stop server)
;; (def server (-main))
