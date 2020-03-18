(ns appliedsciencestudio.covid19-clj-viz.explore
  (:require [appliedsciencestudio.covid19-clj-viz.china :as china]
            [appliedsciencestudio.covid19-clj-viz.deutschland :as deutschland]
            [appliedsciencestudio.covid19-clj-viz.viz :as viz :refer [oz-config
                                                                      barchart-dimensions]]
            [clojure.data.csv :as csv]
            [clojure.string :as string]
            [jsonista.core :as json]
            [oz.core :as oz])
  (:import [java.time LocalDate]
           [java.time.temporal ChronoUnit]
           [java.time.format DateTimeFormatter]))

;;;; Another bar chart
;; ...sorted and with some rearranging around `province/country`
(oz/view! (merge oz-config barchart-dimensions
                 {:title "COVID19 cases in selected countries",
                  :data {:values (->> viz/covid19-confirmed-csv
                                      rest
                                      ;; grab only province/state, country, and latest report of total cases:
                                      (map (juxt first second last))
                                      ;; restrict to countries we're interested in:
                                      (filter (comp #{"Mainland China" "Iran" "Italy" "Germany"} second))
                                      (reduce (fn [acc [province country current-cases]]
                                                (conj acc {:location (if (string/blank? province)
                                                                       country
                                                                       (str province ", " country))
                                                           :cases (Integer/parseInt current-cases)}))
                                              [])
                                      (remove (comp #{"Hubei, Mainland China"} :location))
                                      (sort-by :cases))},
                  :mark {:type "bar" :color "#9085DA"}
                  :encoding {:x {:field "cases", :type "quantitative"}
                             :y {:field "location", :type "ordinal"
                                 :sort nil}}}))


;;;; Cases over time

(defn new-daily-cases-in [kind country]
  (assert (#{:recovered :deaths :confirmed} kind))
  (assert (viz/data-exists-for-country? kind country))
  (let [rows (case kind
               :recovered viz/covid19-recovered-csv
               :deaths    viz/covid19-deaths-csv
               :confirmed viz/covid19-confirmed-csv)]
    (->> rows
         rest
         (filter (comp #{country} second))
                  (map #(drop 4 %))
         (map #(map (fn [n] (Integer/parseInt n)) %))
         (apply map +)
         (partition 2 1)
         (reduce (fn [acc [n-yesterday n-today]]
                   (conj acc (max 0 (- n-today n-yesterday))))
                 [])
         (zipmap (map (comp str viz/parse-covid19-date)
                      (drop 5 (first rows))))
         (sort-by val))))

(comment
  (new-daily-cases-in :confirmed "Korea, South")

  ;; test a country with province-level data
  (new-daily-cases-in :confirmed "Australia")
  
  )

(defn compare-cases-in [c]
  (->> c
       (new-daily-cases-in :recovered)
       (map (fn [[date recovered]]
              {:date date
               :cases recovered
               :type "recovered"
               :country c}))
       (concat (map (fn [[date cases]]
                      {:date date
                       :cases cases
                       :type "cases"
                       :country c})
                    (new-daily-cases-in :confirmed c))
               (map (fn [[date deaths]]
                      {:date date
                       :cases deaths
                       :type "deaths"
                       :country c})
                    (new-daily-cases-in :deaths c)))
       ;; only since 15 February
       (filter (comp (fn [d] (or (and (= 2 (Integer/parseInt (subs d 5 7)))
                                     (<= 15 (Integer/parseInt (subs d 8 10))))
                                (< 2 (Integer/parseInt (subs d 5 7)))))
                     :date))))


;;;; Grouped bar chart comparing daily new cases, by kind, in Italy & South Korea
;; mimicking https://twitter.com/webdevMason/status/1237610911193387008/photo/1
(oz/view! (merge-with
           merge oz-config
           {:title {:text "COVID-19, Italy & South Korea: daily new cases"
                    :font "IBM Plex Mono"
                    :fontSize 30
                    :anchor "middle"}
            :width {:step 16}
            :height 325
            :config {:view {:stroke "transparent"}}
            :data {:values (concat (compare-cases-in "Korea, South") ;; NB: prior to ~March 11, this was "South Korea". Then "Republic of Korea" until ~March 16
                                   (compare-cases-in "Italy"))},
            :mark "bar"
            :encoding {:column {:field "date" :type "temporal" :spacing 10 :timeUnit "monthday"},
                       :x {:field "type" :type "nominal" :spacing 10
                           :axis {:title nil
                                  :labels false}}
                       :y {:field "cases", :type "quantitative"
                           ;; :scale {:domain [0 2000]}
                           :axis {:title nil :grid false}},
                       :color {:field "type", :type "nominal"
                               :scale {:range ["#f3cd6a" "#de6a83" "#70bebf"]}
                               :legend {:orient "top"
                                        :title nil
                                        ;; this is clearly not 800px as
                                        ;; the docs claim, but it's the
                                        ;; size I want:
                                        :symbolSize 800
                                        :labelFontSize 24}}
                       :row {:field "country" :type "nominal"}}}))

(comment
  ;; last 10 days of new cases in Deutschland
  (vals (take 10 (reverse (sort-by key (new-daily-cases-in :confirmed "Germany")))))
  ;; (1477 1210 910 1597 170 451 281 136 241 129)

  (vals (take 10 (reverse (sort-by key (new-daily-cases-in :confirmed "Italy")))))
  ;; (3233 3590 3497 5198 0 2313 977 1797 1492 1247)
  
  )

(oz/view!
 (merge-with merge oz-config
             {:title {:text "Daily new COVID-19 cases in Germany"
                      :font "IBM Plex Mono"
                      :fontSize 30
                      :anchor "middle"}
              :width 500 ;;{:step 25}
              :height 325
              :data {:values (let [country "Germany"] ;; FIXME change country here
                               (->> (new-daily-cases-in :confirmed country)
                                    (sort-by key)
                                    reverse
                                    (take 20)
                                    vals
                                    (into [])
                                    (map-indexed (fn [i n] {:cases n
                                                           :country country
                                                           :days-ago i}))))},
              :mark {:type "bar" :size 24}
              :encoding {:x {:field "days-ago" :type "ordinal"
                             :sort "descending"}
                         :y {:field "cases", :type "quantitative"}
                         :tooltip {:field "cases" :type "quantitative"}
                         :color {:field "country" :type "nominal"
                                 :scale {:range (mapv val viz/applied-science-palette)}}}}))



;;;; Question: how long ago was X place where Y is now?
;; e.g. X = Italy, Y = Germany means "how long until Germany looks like Italy today?"
(defn case-count-in
  "Last reported number of confirmd coronavirus cases in given `country`."
  ;; FIXME doesn't work for countries with >1 province reporting
  ([country]
   (Integer/parseInt (last (first (filter (comp #{country} second)
                                          viz/covid19-confirmed-csv)))))
  ([country days-ago]
   (Integer/parseInt (nth (reverse (first (filter (comp #{country} second)
                                                  viz/covid19-confirmed-csv)))
                          days-ago))))

(defn date-cases-surpassed [country n]
  "First date when `country` had greater than the given number of _population-scaled_ cases `n`."
  (->> (first (filter (comp #{country} second)
                      viz/covid19-confirmed-csv))
       (drop 4)
       (map (comp (fn [n] (/ n (get viz/country-populations country)))
                  (fn [s] (Integer/parseInt s))))
       (zipmap (map (comp str viz/parse-covid19-date)
                    (drop 4 (first viz/covid19-confirmed-csv))))
       (sort-by val)
       (some (fn [[d c]] (when (> c n)
                          d)))))

(defn days-between [yyyy-mm-dd1 yyyy-mm-dd2]
  (.until (LocalDate/parse yyyy-mm-dd1 (DateTimeFormatter/ofPattern "yyyy-MM-dd"))
          (LocalDate/parse yyyy-mm-dd2 (DateTimeFormatter/ofPattern "yyyy-MM-dd"))
          ChronoUnit/DAYS))

(comment

  (date-cases-surpassed "Italy" (/ 10 (get viz/country-populations "Italy")))
  ;; "2020-02-21"
  
  (/ (case-count-in "Germany")
     (get viz/country-populations "Germany"))
  ;; 954/41463961

  (/ (case-count-in "Italy")
     (get viz/country-populations "Italy"))  
  ;; 4154/20143761

  (let [a "Germany"]
    (days-between (date-cases-surpassed "Italy" (/ (case-count-in a)
                                                   (get viz/country-populations a)))
                  (str (viz/parse-covid19-date (last (first viz/covid19-confirmed-csv))))))
  ;; 10  
  ;; Germany is trailing just over a week behind Italy, ceteris paribus
  ;; (without population adjustmnet, it was 9)



  (case-count-in "US")

  (let [a "US"]
    (days-between (date-cases-surpassed "Italy" (/ 50000 #_1629 #_(case-count-in a)
                                                   (get viz/country-populations "United States")))
                  (str (viz/parse-covid19-date (last (first viz/covid19-confirmed-csv))))))


  ;; TODO rate of change for past few days
  
  )
