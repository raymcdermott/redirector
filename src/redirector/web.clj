(ns redirector.web
  (:require [compojure.core :refer [defroutes GET ANY]]
            [compojure.handler :refer [site]]
            [compojure.route :as route]
            [ring.util.response :as response]
            [ring.adapter.jetty :as jetty]
            [monger.core :as mg]
            [monger.collection :as mc]
            [clojure.java.io :as io]
            [taoensso.carmine :as car :refer (wcar)]
            [environ.core :refer [env]]))

; -------*** MONGO HELPERS ... push out to another file
;
; read data from Mongo ... source of truth

(def mongo-uri (or (env :MONGO_URL) "mongodb://localhost/test"))
(def mongo-collection (or (env :MONGO_COLLECTION) "redirections"))

(defn mongo-query [data fields]
  (let [{:keys [conn db]} (mg/connect-via-uri mongo-uri)
        result (mc/find-one-as-map db mongo-collection data fields)]
    (mg/disconnect conn)
    result))

(defn get-route-from-mongo [brand country]
  "Obtain the redirect configuration data from MongoDB for brand / country"
  (let [{:keys [domain bucket]} (mongo-query {:brand brand :country country} [:domain :bucket])]
    (if (and domain bucket)
      (str domain "/" bucket)
      (throw RuntimeException))))

; -------*** REDIS HELPERS ... push out to another file
;
; read data from REDIS ... source of speed

(defn get-redis-spec []
  (if-let [uri (env :REDIS_URL)]
    (prn (str uri)
         {:uri uri})
    (prn "using default"
         {:host "127.0.0.1" :port 6379})))

(def redis-conn {:pool {} :spec (get-redis-spec)})
(defmacro wcar* [& body] `(car/wcar redis-conn ~@body))

; set a default of 30 seconds for data expiry in the REDIS cache
(def redis-ttl (or (env :REDIS_TTL_SECONDS) 30))

(defn set-route-in-cache!
  ([k v]
   (set-route-in-cache! k v redis-ttl))
  ([k v ttl]
   (wcar* (car/setex k ttl v))))

(defn get-route-from-cache [k]
  (wcar* (car/get k)))

; -------*** WORK
;
; get data from REDIS or get data from Mongo
;

(defn get-route [brand country]
  (if-let [cached-route (get-route-from-cache (str brand country))]
    cached-route
    (let [route (get-route-from-mongo brand country)]
      (set-route-in-cache! (str brand country) route)
      route)))

(defn respond [brand country resource]
  (try
    (let [url (str (get-route brand country) "/" resource)]
      (response/redirect url))
    (catch Exception e
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
  (let [{:keys [conn db]} (mg/connect-via-uri mongo-uri)]
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
    (if (env :SEED_MONGO) (seed_mongo))
    (jetty/run-jetty (site #'app) {:port port :join? false})))

;; For interactive development:
;; (.stop server)
;; (def server (-main))
