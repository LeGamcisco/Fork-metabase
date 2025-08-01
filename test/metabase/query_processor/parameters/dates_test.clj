(ns metabase.query-processor.parameters.dates-test
  (:require
   [clojure.test :refer :all]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [java-time.api :as t]
   [metabase.query-processor.parameters.dates :as params.dates]
   [metabase.test :as mt]))

(deftest ^:parallel date-string->filter-test
  (testing "year and month"
    (is (=? [:between
             {}
             [:field {:base-type :type/DateTime, :temporal-unit :day} "field"]
             "2019-04-01"
             "2019-04-30"]
            (params.dates/date-string->filter
             "2019-04"
             [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))))

(deftest ^:parallel date-string->filter-test-2
  (testing "quarter year"
    (is (=? [:between
             {}
             [:field {:base-type :type/DateTime, :temporal-unit :day} "field"]
             "2019-04-01"
             "2019-06-30"]
            (params.dates/date-string->filter
             "Q2-2019"
             [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))))

(deftest ^:parallel date-string->filter-test-3
  (testing "single day"
    (is (=? [:=
             {}
             [:field {:base-type :type/DateTime, :temporal-unit :day} "field"]
             "2019-04-01"]
            (params.dates/date-string->filter
             "2019-04-01"
             [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))))

(deftest ^:parallel date-string->filter-test-4
  (testing "single minute"
    (is (=? [:=
             {}
             [:field {:base-type :type/DateTime, :temporal-unit :minute} "field"]
             "2019-04-01T09:33:00"]
            (params.dates/date-string->filter
             "2019-04-01T09:33:00"
             [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))))

(deftest ^:parallel date-string->filter-test-5
  (testing "day range"
    (is (=? [:between
             {}
             [:field {:base-type :type/DateTime, :temporal-unit :day} "field"]
             "2019-04-01"
             "2019-04-03"]
            (params.dates/date-string->filter
             "2019-04-01~2019-04-03"
             [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))))

(deftest ^:parallel date-string->filter-test-6
  (testing "datetime range"
    (is (=? [:between
             {}
             [:field {:base-type :type/DateTime, :temporal-unit :default} "field"]
             "2019-04-01T12:30:00"
             "2019-04-03T16:30:00"]
            (params.dates/date-string->filter
             "2019-04-01T12:30~2019-04-03T16:30"
             [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))))

(deftest ^:parallel date-string->filter-test-7
  (testing "after day"
    (is (=? [:>
             {}
             [:field {:base-type :type/DateTime, :temporal-unit :day} "field"]
             "2019-04-01"]
            (params.dates/date-string->filter
             "2019-04-01~"
             [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))))

(deftest ^:parallel date-string->filter-test-8
  (testing "relative (past) exclusive"
    (is (=? [:time-interval
             {:include-current false}
             [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"]
             -3
             :day]
            (params.dates/date-string->filter
             "past3days"
             [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))))

(deftest ^:parallel date-string->filter-test-9
  (testing "relative (past) inclusive"
    (is (=? [:time-interval
             {:include-current true}
             [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"]
             -3
             :day]
            (params.dates/date-string->filter
             "past3days~"
             [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))))

(deftest ^:parallel date-string->filter-test-10
  (testing "relative (next) exclusive"
    (is (=? [:time-interval
             {:include-current false}
             [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"]
             3
             :day]
            (params.dates/date-string->filter
             "next3days"
             [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))))

(deftest ^:parallel date-string->filter-test-11
  (testing "relative (next) inclusive"
    (is (=? [:time-interval
             {:include-current true}
             [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"]
             3
             :day]
            (params.dates/date-string->filter
             "next3days~"
             [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))))

(deftest ^:parallel date-string->filter-test-12
  (testing "quarters (#21083)"
    (is (=? [:time-interval
             {:include-current false}
             [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"]
             -30
             :quarter]
            (params.dates/date-string->filter
             "past30quarters"
             [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))))

(deftest ^:parallel date-string->filter-test-13
  (testing "relative (past) with starting from "
    (is (=? [:relative-time-interval
             {}
             [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"]
             -3
             :day
             -3
             :year]
            (params.dates/date-string->filter
             "past3days-from-3years"
             [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))))

(deftest ^:parallel date-string->filter-test-14
  (testing "relative (next) with starting from"
    (is (=? [:relative-time-interval
             {}
             [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"]
             7
             :hour
             13
             :month]
            (params.dates/date-string->filter
             "next7hours-from-13months"
             [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))))

(deftest ^:parallel date-string->filter-test-15a
  (testing "exclusions"
    (mt/with-clock #t "2016-06-07T12:13:55Z"
      (testing "hours"
        (is (=? [:!=
                 {}
                 [:field {:base-type :type/DateTime, :temporal-unit :hour-of-day} "field"]
                 "2016-06-07T00:00:00Z"]
                (params.dates/date-string->filter
                 "exclude-hours-0"
                 [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))
        (is (=? [:!=
                 {}
                 [:field {:base-type :type/DateTime, :temporal-unit :hour-of-day} "field"]
                 "2016-06-07T00:00:00Z"
                 "2016-06-07T23:00:00Z"]
                (params.dates/date-string->filter
                 "exclude-hours-0-23"
                 [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Don't know how to parse date string \"exclude-hours-\""
             (params.dates/date-string->filter
              "exclude-hours-"
              [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Don't know how to parse date string \"exclude-hours-24-3\""
             (params.dates/date-string->filter
              "exclude-hours-24-3"
              [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))))))

(deftest ^:parallel date-string->filter-test-15b
  (testing "exclusions"
    (mt/with-clock #t "2016-06-07T12:13:55Z"
      (testing "quarters"
        (is (=? [:!=
                 {}
                 [:field {:base-type :type/DateTime, :temporal-unit :quarter-of-year} "field"]
                 "2016-01-01"]
                (params.dates/date-string->filter
                 "exclude-quarters-1"
                 [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))
        (is (=? [:!=
                 {}
                 [:field {:base-type :type/DateTime, :temporal-unit :quarter-of-year} "field"]
                 "2016-04-01"
                 "2016-07-01"
                 "2016-10-01"]
                (params.dates/date-string->filter
                 "exclude-quarters-2-3-4"
                 [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Don't know how to parse date string \"exclude-quarters-Q1\""
             (params.dates/date-string->filter
              "exclude-quarters-Q1"
              [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))))))

(deftest ^:parallel date-string->filter-test-15c
  (testing "exclusions"
    (mt/with-clock #t "2016-06-07T12:13:55Z"
      (testing "days"
        (is (=? [:!=
                 {}
                 [:field {:base-type :type/DateTime, :temporal-unit :day-of-week} "field"]
                 "2016-06-10"
                 "2016-06-07"]
                (params.dates/date-string->filter
                 "exclude-days-Fri-Tue"
                 [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Don't know how to parse date string \"exclude-days-Friday\""
             (params.dates/date-string->filter
              "exclude-days-Friday"
              [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))))))

(deftest ^:parallel date-string->filter-test-15d
  (testing "exclusions"
    (mt/with-clock #t "2016-06-07T12:13:55Z"
      (testing "months"
        (is (=? [:!=
                 {}
                 [:field {:base-type :type/DateTime, :temporal-unit :month-of-year} "field"]
                 "2016-12-01"
                 "2016-04-01"
                 "2016-09-01"]
                (params.dates/date-string->filter
                 "exclude-months-Dec-Apr-Sep"
                 [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Don't know how to parse date string \"exclude-months-April\""
             (params.dates/date-string->filter
              "exclude-months-April"
              [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))))))

(deftest ^:parallel date-string->filter-test-15e
  (testing "exclusions"
    (mt/with-clock #t "2016-06-07T12:13:55Z"
      (testing "minutes"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Don't know how to parse date string \"exclude-minutes-15-30\""
             (params.dates/date-string->filter
              "exclude-minutes-15-30"
              [:field {:lib/uuid "00000000-0000-0000-0000-000000000000", :base-type :type/DateTime} "field"])))))))

(defn do-date-string-range-test
  [s->expected]
  (mt/with-clock #t "2016-06-07T12:13:55Z"
    (doseq [[s ranges] s->expected
            [expected-range option] (map vector ranges [nil
                                                        {:inclusive-start? false}
                                                        {:inclusive-end? false}
                                                        {:inclusive-start? false :inclusive-end? false}])]
      (testing (format "%s with options %s should parse to %s" (pr-str s) (pr-str option) (pr-str ranges))
        (is (= expected-range
               (params.dates/date-string->range s option)))))))

(deftest ^:parallel date-string->range-absolute-datetimes-test
  (do-date-string-range-test
   {"Q1-2016"               [{:start "2016-01-01" :end "2016-03-31"}  ;; inclusive start + end = true (default)
                             {:start "2015-12-31" :end "2016-03-31"}  ;; inclusive start = false
                             {:start "2016-01-01" :end "2016-04-01"}  ;; inclusive end   = false
                             {:start "2015-12-31" :end "2016-04-01"}] ;; inclusive start + end = false
    "2016-02"               [{:start "2016-02-01" :end "2016-02-29"}
                             {:start "2016-01-31" :end "2016-02-29"}
                             {:start "2016-02-01" :end "2016-03-01"}
                             {:start "2016-01-31" :end "2016-03-01"}]
    "2016-04-18"            [{:start "2016-04-18" :end "2016-04-18"}
                             {:start "2016-04-17" :end "2016-04-18"}
                             {:start "2016-04-18" :end "2016-04-19"}
                             {:start "2016-04-17" :end "2016-04-19"}]
    "2016-04-18~2016-04-23" [{:start "2016-04-18" :end "2016-04-23"}
                             {:start "2016-04-17" :end "2016-04-23"}
                             {:start "2016-04-18" :end "2016-04-24"}
                             {:start "2016-04-17" :end "2016-04-24"}]
    "2016-04-18T10:30:00~2016-04-23T10:30:00" [{:start "2016-04-18T10:30:00" :end "2016-04-23T10:30:00"}
                                               {:start "2016-04-18T10:29:00" :end "2016-04-23T10:30:00"}
                                               {:start "2016-04-18T10:30:00" :end "2016-04-23T10:31:00"}
                                               {:start "2016-04-18T10:29:00" :end "2016-04-23T10:31:00"}]

    "2016-04-18~"           [{:start "2016-04-18"}
                             {:start "2016-04-17"}
                             {:start "2016-04-18"}
                             {:start "2016-04-17"}]
    "~2016-04-18"           [{:end "2016-04-18"}
                             {:end "2016-04-18"}
                             {:end "2016-04-19"}
                             {:end "2016-04-19"}]}))

(deftest ^:parallel date-string->range-relative-past-test
  (do-date-string-range-test
   {"past30seconds"  [{:start "2016-06-07T12:13:25" :end "2016-06-07T12:13:54"}
                      {:start "2016-06-07T12:13:24" :end "2016-06-07T12:13:54"}
                      {:start "2016-06-07T12:13:25" :end "2016-06-07T12:13:55"}
                      {:start "2016-06-07T12:13:24" :end "2016-06-07T12:13:55"}]
    "past5minutes~"  [{:start "2016-06-07T12:08:00" :end "2016-06-07T12:13:00"}
                      {:start "2016-06-07T12:07:00" :end "2016-06-07T12:13:00"}
                      {:start "2016-06-07T12:08:00" :end "2016-06-07T12:14:00"}
                      {:start "2016-06-07T12:07:00" :end "2016-06-07T12:14:00"}]
    "past3hours"     [{:start "2016-06-07T09:00:00" :end "2016-06-07T11:00:00"}
                      {:start "2016-06-07T08:00:00" :end "2016-06-07T11:00:00"}
                      {:start "2016-06-07T09:00:00" :end "2016-06-07T12:00:00"}
                      {:start "2016-06-07T08:00:00" :end "2016-06-07T12:00:00"}]
    "past3days"      [{:start "2016-06-04" :end "2016-06-06"}
                      {:start "2016-06-03" :end "2016-06-06"}
                      {:start "2016-06-04" :end "2016-06-07"}
                      {:start "2016-06-03" :end "2016-06-07"}]
    "past3days~"     [{:start "2016-06-04" :end "2016-06-07"}
                      {:start "2016-06-03" :end "2016-06-07"}
                      {:start "2016-06-04" :end "2016-06-08"}
                      {:start "2016-06-03" :end "2016-06-08"}]
    "past7days"      [{:start "2016-05-31" :end "2016-06-06"}
                      {:start "2016-05-30" :end "2016-06-06"}
                      {:start "2016-05-31" :end "2016-06-07"}
                      {:start "2016-05-30" :end "2016-06-07"}]
    "past30days"     [{:start "2016-05-08" :end "2016-06-06"}
                      {:start "2016-05-07" :end "2016-06-06"}
                      {:start "2016-05-08" :end "2016-06-07"}
                      {:start "2016-05-07" :end "2016-06-07"}]
    "past2months"    [{:start "2016-04-01" :end "2016-05-31"}
                      {:start "2016-03-31" :end "2016-05-31"}
                      {:start "2016-04-01" :end "2016-06-01"}
                      {:start "2016-03-31" :end "2016-06-01"}]
    "past2months~"   [{:start "2016-04-01" :end "2016-06-30"}
                      {:start "2016-03-31" :end "2016-06-30"}
                      {:start "2016-04-01" :end "2016-07-01"}
                      {:start "2016-03-31" :end "2016-07-01"}]
    "past13months"   [{:start "2015-05-01" :end "2016-05-31"}
                      {:start "2015-04-30" :end "2016-05-31"}
                      {:start "2015-05-01" :end "2016-06-01"}
                      {:start "2015-04-30" :end "2016-06-01"}]
    "past2quarters"  [{:start "2015-10-01" :end "2016-03-31"}
                      {:start "2015-09-30" :end "2016-03-31"}
                      {:start "2015-10-01" :end "2016-04-01"}
                      {:start "2015-09-30" :end "2016-04-01"}]
    "past2quarters~" [{:start "2015-10-01" :end "2016-06-30"}
                      {:start "2015-09-30" :end "2016-06-30"}
                      {:start "2015-10-01" :end "2016-07-01"}
                      {:start "2015-09-30" :end "2016-07-01"}]
    "past1years"     [{:start "2015-01-01" :end "2015-12-31"}
                      {:start "2014-12-31" :end "2015-12-31"}
                      {:start "2015-01-01" :end "2016-01-01"}
                      {:start "2014-12-31" :end "2016-01-01"}]
    "past1years~"    [{:start "2015-01-01" :end "2016-12-31"}
                      {:start "2014-12-31" :end "2016-12-31"}
                      {:start "2015-01-01" :end "2017-01-01"}
                      {:start "2014-12-31" :end "2017-01-01"}]
    "past16years"    [{:start "2000-01-01" :end "2015-12-31"}
                      {:start "1999-12-31" :end "2015-12-31"}
                      {:start "2000-01-01" :end "2016-01-01"}
                      {:start "1999-12-31" :end "2016-01-01"}]}))

(deftest ^:parallel date-string->range-relative-next-test
  (do-date-string-range-test
   {"next45seconds"  [{:start "2016-06-07T12:13:56" :end "2016-06-07T12:14:40"}
                      {:start "2016-06-07T12:13:55" :end "2016-06-07T12:14:40"}
                      {:start "2016-06-07T12:13:56" :end "2016-06-07T12:14:41"}
                      {:start "2016-06-07T12:13:55" :end "2016-06-07T12:14:41"}]
    "next20minutes"  [{:start "2016-06-07T12:14:00" :end "2016-06-07T12:33:00"}
                      {:start "2016-06-07T12:13:00" :end "2016-06-07T12:33:00"}
                      {:start "2016-06-07T12:14:00" :end "2016-06-07T12:34:00"}
                      {:start "2016-06-07T12:13:00" :end "2016-06-07T12:34:00"}]
    "next6hours"     [{:start "2016-06-07T13:00:00" :end "2016-06-07T18:00:00"}
                      {:start "2016-06-07T12:00:00" :end "2016-06-07T18:00:00"}
                      {:start "2016-06-07T13:00:00" :end "2016-06-07T19:00:00"}
                      {:start "2016-06-07T12:00:00" :end "2016-06-07T19:00:00"}]
    "next3days"      [{:start "2016-06-08" :end "2016-06-10"}
                      {:start "2016-06-07" :end "2016-06-10"}
                      {:start "2016-06-08" :end "2016-06-11"}
                      {:start "2016-06-07" :end "2016-06-11"}]
    "next3days~"     [{:start "2016-06-07" :end "2016-06-10"}
                      {:start "2016-06-06" :end "2016-06-10"}
                      {:start "2016-06-07" :end "2016-06-11"}
                      {:start "2016-06-06" :end "2016-06-11"}]
    "next7days"      [{:start "2016-06-08" :end "2016-06-14"}
                      {:start "2016-06-07" :end "2016-06-14"}
                      {:start "2016-06-08" :end "2016-06-15"}
                      {:start "2016-06-07" :end "2016-06-15"}]
    "next30days"     [{:start "2016-06-08" :end "2016-07-07"}
                      {:start "2016-06-07" :end "2016-07-07"}
                      {:start "2016-06-08" :end "2016-07-08"}
                      {:start "2016-06-07" :end "2016-07-08"}]
    "next2months"    [{:start "2016-07-01" :end "2016-08-31"}
                      {:start "2016-06-30" :end "2016-08-31"}
                      {:start "2016-07-01" :end "2016-09-01"}
                      {:start "2016-06-30" :end "2016-09-01"}]
    "next2months~"   [{:start "2016-06-01" :end "2016-08-31"}
                      {:start "2016-05-31" :end "2016-08-31"}
                      {:start "2016-06-01" :end "2016-09-01"}
                      {:start "2016-05-31" :end "2016-09-01"}]
    "next2quarters"  [{:start "2016-07-01" :end "2016-12-31"}
                      {:start "2016-06-30" :end "2016-12-31"}
                      {:start "2016-07-01" :end "2017-01-01"}
                      {:start "2016-06-30" :end "2017-01-01"}]
    "next2quarters~" [{:start "2016-04-01" :end "2016-12-31"}
                      {:start "2016-03-31" :end "2016-12-31"}
                      {:start "2016-04-01" :end "2017-01-01"}
                      {:start "2016-03-31" :end "2017-01-01"}]
    "next13months"   [{:start "2016-07-01" :end "2017-07-31"}
                      {:start "2016-06-30" :end "2017-07-31"}
                      {:start "2016-07-01" :end "2017-08-01"}
                      {:start "2016-06-30" :end "2017-08-01"}]
    "next1years"     [{:start "2017-01-01" :end "2017-12-31"}
                      {:start "2016-12-31" :end "2017-12-31"}
                      {:start "2017-01-01" :end "2018-01-01"}
                      {:start "2016-12-31" :end "2018-01-01"}]
    "next1years~"    [{:start "2016-01-01" :end "2017-12-31"}
                      {:start "2015-12-31" :end "2017-12-31"}
                      {:start "2016-01-01" :end "2018-01-01"}
                      {:start "2015-12-31" :end "2018-01-01"}]
    "next16years"    [{:start "2017-01-01" :end "2032-12-31"}
                      {:start "2016-12-31" :end "2032-12-31"}
                      {:start "2017-01-01" :end "2033-01-01"}
                      {:start "2016-12-31" :end "2033-01-01"}]}))

(deftest ^:parallel date-string->range-relative-this-test
  (do-date-string-range-test
   {"thissecond"  [{:start "2016-06-07T12:13:55" :end "2016-06-07T12:13:55"}
                   {:start "2016-06-07T12:13:54" :end "2016-06-07T12:13:55"}
                   {:start "2016-06-07T12:13:55" :end "2016-06-07T12:13:56"}
                   {:start "2016-06-07T12:13:54" :end "2016-06-07T12:13:56"}]
    "thisminute"  [{:start "2016-06-07T12:13:00" :end "2016-06-07T12:13:00"}
                   {:start "2016-06-07T12:12:00" :end "2016-06-07T12:13:00"}
                   {:start "2016-06-07T12:13:00" :end "2016-06-07T12:14:00"}
                   {:start "2016-06-07T12:12:00" :end "2016-06-07T12:14:00"}]
    "thishour"    [{:start "2016-06-07T12:00:00" :end "2016-06-07T12:00:00"}
                   {:start "2016-06-07T11:00:00" :end "2016-06-07T12:00:00"}
                   {:start "2016-06-07T12:00:00" :end "2016-06-07T13:00:00"}
                   {:start "2016-06-07T11:00:00" :end "2016-06-07T13:00:00"}]
    "thisday"     [{:start "2016-06-07" :end "2016-06-07"}
                   {:start "2016-06-06" :end "2016-06-07"}
                   {:start "2016-06-07" :end "2016-06-08"}
                   {:start "2016-06-06" :end "2016-06-08"}]
    "thisweek"    [{:start "2016-06-05" :end "2016-06-11"}
                   {:start "2016-06-04" :end "2016-06-11"}
                   {:start "2016-06-05" :end "2016-06-12"}
                   {:start "2016-06-04" :end "2016-06-12"}]
    "thismonth"   [{:start "2016-06-01" :end "2016-06-30"}
                   {:start "2016-05-31" :end "2016-06-30"}
                   {:start "2016-06-01" :end "2016-07-01"}
                   {:start "2016-05-31" :end "2016-07-01"}]
    "thisquarter" [{:start "2016-04-01" :end "2016-06-30"}
                   {:start "2016-03-31" :end "2016-06-30"}
                   {:start "2016-04-01" :end "2016-07-01"}
                   {:start "2016-03-31" :end "2016-07-01"}]
    "thisyear"    [{:start "2016-01-01" :end "2016-12-31"}
                   {:start "2015-12-31" :end "2016-12-31"}
                   {:start "2016-01-01" :end "2017-01-01"}
                   {:start "2015-12-31" :end "2017-01-01"}]}))

(deftest ^:parallel date-string->range-relative-last-test
  (do-date-string-range-test
   {"lastsecond"  [{:start "2016-06-07T12:13:54" :end "2016-06-07T12:13:54"}
                   {:start "2016-06-07T12:13:53" :end "2016-06-07T12:13:54"}
                   {:start "2016-06-07T12:13:54" :end "2016-06-07T12:13:55"}
                   {:start "2016-06-07T12:13:53" :end "2016-06-07T12:13:55"}]
    "lastminute"  [{:start "2016-06-07T12:12:00" :end "2016-06-07T12:12:00"}
                   {:start "2016-06-07T12:11:00" :end "2016-06-07T12:12:00"}
                   {:start "2016-06-07T12:12:00" :end "2016-06-07T12:13:00"}
                   {:start "2016-06-07T12:11:00" :end "2016-06-07T12:13:00"}]
    "lasthour"    [{:start "2016-06-07T11:00:00" :end "2016-06-07T11:00:00"}
                   {:start "2016-06-07T10:00:00" :end "2016-06-07T11:00:00"}
                   {:start "2016-06-07T11:00:00" :end "2016-06-07T12:00:00"}
                   {:start "2016-06-07T10:00:00" :end "2016-06-07T12:00:00"}]
    "lastweek"    [{:start "2016-05-29" :end "2016-06-04"}
                   {:start "2016-05-28" :end "2016-06-04"}
                   {:start "2016-05-29" :end "2016-06-05"}
                   {:start "2016-05-28" :end "2016-06-05"}]
    "lastmonth"   [{:start "2016-05-01" :end "2016-05-31"}
                   {:start "2016-04-30" :end "2016-05-31"}
                   {:start "2016-05-01" :end "2016-06-01"}
                   {:start "2016-04-30" :end "2016-06-01"}]
    "lastquarter" [{:start "2016-01-01" :end "2016-03-31"}
                   {:start "2015-12-31" :end "2016-03-31"}
                   {:start "2016-01-01" :end "2016-04-01"}
                   {:start "2015-12-31" :end "2016-04-01"}]
    "lastyear"    [{:start "2015-01-01" :end "2015-12-31"}
                   {:start "2014-12-31" :end "2015-12-31"}
                   {:start "2015-01-01" :end "2016-01-01"}
                   {:start "2014-12-31" :end "2016-01-01"}]}))

(deftest ^:parallel date-string->range-relative-today-yesterday-test
  (do-date-string-range-test
   {"yesterday" [{:start "2016-06-06" :end "2016-06-06"}
                 {:start "2016-06-05" :end "2016-06-06"}
                 {:start "2016-06-06" :end "2016-06-07"}
                 {:start "2016-06-05" :end "2016-06-07"}]
    "today"     [{:start "2016-06-07" :end "2016-06-07"}
                 {:start "2016-06-06" :end "2016-06-07"}
                 {:start "2016-06-07" :end "2016-06-08"}
                 {:start "2016-06-06" :end "2016-06-08"}]}))

(deftest ^:parallel date-string->range-relative-past-from-test
  (do-date-string-range-test
   {"past1days-from-0days"      [{:start "2016-06-06" :end "2016-06-06"}
                                 {:start "2016-06-05" :end "2016-06-06"}
                                 {:start "2016-06-06" :end "2016-06-07"}
                                 {:start "2016-06-05" :end "2016-06-07"}]
    "past1months-from-0months"  [{:start "2016-05-01" :end "2016-05-31"}
                                 {:start "2016-04-30" :end "2016-05-31"}
                                 {:start "2016-05-01" :end "2016-06-01"}
                                 {:start "2016-04-30" :end "2016-06-01"}]
    "past1months-from-36months" [{:start "2013-05-01" :end "2013-05-31"}
                                 {:start "2013-04-30" :end "2013-05-31"}
                                 {:start "2013-05-01" :end "2013-06-01"}
                                 {:start "2013-04-30" :end "2013-06-01"}]
    "past1years-from-36months"  [{:start "2012-01-01" :end "2012-12-31"}
                                 {:start "2011-12-31" :end "2012-12-31"}
                                 {:start "2012-01-01" :end "2013-01-01"}
                                 {:start "2011-12-31" :end "2013-01-01"}]
    "past3days-from-3years"     [{:start "2013-06-04" :end "2013-06-06"}
                                 {:start "2013-06-03" :end "2013-06-06"}
                                 {:start "2013-06-04" :end "2013-06-07"}
                                 {:start "2013-06-03" :end "2013-06-07"}]}))

(deftest ^:parallel date-string->range-relative-next-from-test
  (do-date-string-range-test
   {"next2days-from-1months"    [{:start "2016-07-08" :end "2016-07-09"}
                                 {:start "2016-07-07" :end "2016-07-09"}
                                 {:start "2016-07-08" :end "2016-07-10"}
                                 {:start "2016-07-07" :end "2016-07-10"}]
    "next1months-from-0months"  [{:start "2016-07-01" :end "2016-07-31"}
                                 {:start "2016-06-30" :end "2016-07-31"}
                                 {:start "2016-07-01" :end "2016-08-01"}
                                 {:start "2016-06-30" :end "2016-08-01"}]
    "next1months-from-36months" [{:start "2019-07-01" :end "2019-07-31"}
                                 {:start "2019-06-30" :end "2019-07-31"}
                                 {:start "2019-07-01" :end "2019-08-01"}
                                 {:start "2019-06-30" :end "2019-08-01"}]
    "next1years-from-36months"  [{:start "2020-01-01" :end "2020-12-31"}
                                 {:start "2019-12-31" :end "2020-12-31"}
                                 {:start "2020-01-01" :end "2021-01-01"}
                                 {:start "2019-12-31" :end "2021-01-01"}]
    "next3days-from-3years"     [{:start "2019-06-08" :end "2019-06-10"}
                                 {:start "2019-06-07" :end "2019-06-10"}
                                 {:start "2019-06-08" :end "2019-06-11"}
                                 {:start "2019-06-07" :end "2019-06-11"}]
    "next7hours-from-13months"  [{:start "2017-07-07T13:00:00" :end "2017-07-07T19:00:00"}
                                 {:start "2017-07-07T12:00:00" :end "2017-07-07T19:00:00"}
                                 {:start "2017-07-07T13:00:00" :end "2017-07-07T20:00:00"}
                                 {:start "2017-07-07T12:00:00" :end "2017-07-07T20:00:00"}]}))

(deftest ^:parallel relative-dates-with-starting-from-zero-must-match
  (testing "relative dates need to behave the same way, offset or not."
    (mt/with-clock #t "2016-06-07T12:13:55Z"
      (testing "'past1months-from-0months' should be the same as: 'past1months'"
        (is (= {:start "2016-05-01" :end "2016-05-31"}
               (params.dates/date-string->range "past1months")
               (params.dates/date-string->range "past1months-from-0months"))))
      (testing "'next1months-from-0months' should be the same as: 'next1months'"
        (is (= {:start "2016-07-01" :end "2016-07-31"}
               (params.dates/date-string->range "next1months")
               (params.dates/date-string->range "next1months-from-0months")))))))

(def ^:private time-range-generator
  (let [time-units (mapv #(str % "s") (keys @#'params.dates/operations-by-date-unit))]
    (gen/fmap
     (fn [[frame n unit unit2]]
       [(str frame n unit)
        (str frame n unit "-from-0" unit2)])
     (gen/tuple
      (gen/elements #{"next" "past"})
      (gen/such-that #(not= % 0) gen/nat)
      (gen/elements time-units)
      (gen/elements time-units)))))

(defspec ^:parallel date-string->range-spec-test 1000
  (prop/for-all [[tr tr+from-zero] time-range-generator]
    (mt/with-clock (t/zoned-date-time)
      (= (params.dates/date-string->range tr)
         (params.dates/date-string->range tr+from-zero)))))

(deftest custom-start-of-week-test
  (testing "Relative filters should respect the custom `start-of-week` Setting (#14294)"
    (mt/with-clock #t "2021-03-01T14:15:00-08:00[US/Pacific]"
      (doseq [[first-day-of-week expected] {"sunday"    {:start "2021-02-21" :end "2021-02-27"}
                                            "monday"    {:start "2021-02-22" :end "2021-02-28"}
                                            "tuesday"   {:start "2021-02-16" :end "2021-02-22"}
                                            "wednesday" {:start "2021-02-17" :end "2021-02-23"}
                                            "thursday"  {:start "2021-02-18" :end "2021-02-24"}
                                            "friday"    {:start "2021-02-19" :end "2021-02-25"}
                                            "saturday"  {:start "2021-02-20" :end "2021-02-26"}}]
        (mt/with-temporary-setting-values [start-of-week first-day-of-week]
          (is (= expected
                 (params.dates/date-string->range "past1weeks"))))))))
