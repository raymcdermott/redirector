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

(defn mongo-connect []
  (let [mongo-uri (or (env :mongo-url) "mongodb://localhost/test")
        {:keys [conn db]} (mg/connect-via-uri mongo-uri)]
    (if (and conn db)
      [conn db])))

(defn mongo-query [query fields]
  (let [[conn db] (mongo-connect)
        mongo-collection (or (env :mongo-collection) "redirections")
        result (mc/find-one-as-map db mongo-collection query fields)]
    (mg/disconnect conn)
    result))

(defn get-route-from-mongo [brand country]
  "Obtain the redirect configuration data from MongoDB for brand / country"
  (let [{:keys [domain bucket]} (mongo-query {:brand brand :country country} [:domain :bucket])]
    (if (and domain bucket)
      (str domain "/" bucket)
      (throw (Exception. (str "Failed, cannot find cache domain for brand: " brand " and country: " country))))))

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
   (redis/wcar (get-redis-connection-pool) (redis/setex k ttl v))
   (prn "key " k " set in cache for " ttl " seconds")))

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
      (if-not (nil? route)
        (prn (str "mongo route " route))
        (set-route-in-cache! (str brand country) route)
        route))))

(defn respond [brand country resource]
  (try
    (if-let [cache-domain (get-route brand country)]
      (response/redirect (str cache-domain "/" brand "/" country "/" resource))
      (response/not-found (str "Cannot locate cache domain for brand: " brand " and country: " country)))
    (catch Exception e
      (trace/print-stack-trace e)
      (response/not-found (str "Cannot locate cache domain for brand: " brand " and country: " country " exception: " e)))))


; -------*** EXPOSE TO THE WEB
;

(defroutes app
           (GET "/:brand/:country/*" [brand country *]
                (time (respond brand country *)))
           (ANY "*" []
                (route/not-found "That URL is not supported so cannot be found")))

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
