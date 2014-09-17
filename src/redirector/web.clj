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

(def mongo-uri (or "mongodb://localhost/test" (env :MONGO_URL)))
(def mongo-collection (or "redirections" (env :MONGO_COLLECTION)))

(defn mongo-query [data fields]
  (let [{:keys [conn db]} (mg/connect-via-uri mongo-uri)
        result (mc/find-one-as-map db mongo-collection data fields)]
    (mg/disconnect conn)
    result))

(defn get-route-from-mongo [brand country]
  "Obtain the redirect configuration data from MongoDB for brand / country"
  (let [{:keys [domain bucket]} (mongo-query {:brand brand :country country} [:domain :bucket])]
    (if (nil? domain)
      (throw (Exception. (str "Failed, cannot find cache domain for : " country)))
      (str domain "/" bucket))))

; -------*** REDIS HELPERS ... push out to another file
;
; read data from REDIS ... source of speed

(defn get-redis-spec []
  (if (nil? (env :REDIS_URL))
    {:host "127.0.0.1" :port 6379}
    {:uri (env :REDIS_URL)}))

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
; if data OK return 301 with new URL from the domain cache
; if exception 500

(defn get-route [brand country]
  (if-let [cached-route (get-route-from-cache (str brand country))]
    cached-route
    (let [route (get-route-from-mongo brand country)]
      (set-route-in-cache! (str brand country) route)
      route)))

(defn respond [brand country resource]
  (str (get-route brand country) "/" resource))


; -------*** EXPOSE TO THE WEB
;

(defroutes app
           (GET "/:brand/:country/*" [brand country *]
                (time (response/redirect (respond brand country *))))
           (ANY "*" []
                (route/not-found "That URL cannot be found")))


; -------*** START WEB SERVER
;

(defn -main [& [port]]
  (let [port (Integer. (or port (env :port) 5000))]
    (jetty/run-jetty (site #'app) {:port port :join? false})))

;; For interactive development:
;; (.stop server)
;; (def server (-main))
