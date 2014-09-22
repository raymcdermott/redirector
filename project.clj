(defproject redirector "1.0.0-SNAPSHOT"
  :description "Clojure HTTP redirector"
  :url "http://xxx.herokuapp.com"
  :license {:name "Eclipse Public License v1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [compojure "1.1.9"]
                 [ring/ring-jetty-adapter "1.2.2"]
                 [com.novemberain/monger "2.0.0"]
                 [com.taoensso/carmine "2.7.0"]
                 [environ "1.0.0"]]
  :min-lein-version "2.0.0"
  :plugins [[environ/environ.lein "0.2.1"]]
  :hooks [environ.leiningen.hooks]
  :main "redirector.web"
  :aot :all
  :uberjar-name "redirector-standalone.jar"
  :profiles {:production {:env {:production true}}})