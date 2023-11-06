(ns entity-graph.macros)

(defmacro assert-fail? [form]
  (if (:ns &env) ;; this only exists when expanding CLJS code
    (list 'is (list 'thrown? 'js/Error. form))
    (list 'is (list 'thrown? 'java.lang.AssertionError form))))

(defmacro assert-fail-with-msg? [re form]
  (if (:ns &env) ;; this only exists when expanding CLJS code
    (list 'is (list 'thrown-with-msg? 'js/Error. re form))
    (list 'is (list 'thrown-with-msg? 'java.lang.AssertionError re form))))
