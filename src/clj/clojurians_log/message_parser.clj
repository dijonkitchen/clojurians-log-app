(ns clojurians-log.message-parser
  (:require [instaparse.core :as insta]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def parser
  (insta/parser
   (io/resource "clojurians-log/slack-message.bnf")))

(defn join-adjacent
  "Takes output from an insta-parse parser and returns a new
  vector of tokens with repeated types merged.
  eg. (join-adjacent [[:undecorated \"Hello\"] [:undecorated \" \"] [:undecorated \"World\"]])
  yields: [[:undecorated \"Hello World\"]]"
  [tokens]
  (reduce (fn [result [type content :as token]]
            (let [[prev-type prev-content :as prev-token] (last result)]
              (if (and type
                       (= :undecorated prev-type)
                       (= :undecorated type))
                (assoc result (dec (count result)) [:undecorated (str prev-content content)])
                (conj result token))))
          [] tokens))

(defn parse
  "Returns a vector of [type string] pairs, where type identifies one of the special markup types available in slack.
  eg. (parse \"Hello *bold*!\")
  yields: [[:undecorated \"Hello \"] [:bold \"bold\"] [:undecorated \"!\"]]"
  [message]
  (join-adjacent (parser message)))

(defn re-seq-pos [pattern string]
  (let [m (re-matcher pattern string)]
    ((fn step []
       (when (.find m)
         (cons (cond-> {:start (.start m) :end (.end m) :match (.group m)}
                 (> (.groupCount m) 0)
                 (-> (assoc :matches
                            (re-groups m))
                     (assoc :extents
                            (into []
                             (for [group-idx (range (inc (.groupCount m)))]
                               {:start (.start m group-idx) :end (.end m group-idx)})))))
               (lazy-seq (step))))))))

(def message-patterns
  {:code-block #"```(?s:(.*?))```"
   :inline-code #"(?<=^|_|\s)`(.*?)`"
   :reference #"<((?:#C|@U)[A-Z0-9]{7,})(?:\|(.*?))?>"
   :url #"<((?:http|https):.*?)>"
   :emoji #"(?<!\w):(\w*?):"
   :italic #"\b_(.*?)_"
   :bold #"(?<![a-zA-Z0-9`])\*(.*?)\*(?![a-zA-Z0-9`])"
   :strike-through #"~(.*?)~"})

(defn match-all-patterns [message-patterns message]
  (apply concat
         (for [[pattern-k pattern] message-patterns]
           (if-let [result (re-seq-pos pattern message)]
             (map #(assoc % :type pattern-k) result)))))

(defn- match-compare [m1 m2]
  ;; Order matches such that...
  (cond
    ;; Items with smallest start fields sorts to the top.
    (not= (:start m1) (:start m2))
    (- (:start m1) (:start m2))

    ;; If two blocks/patterns start at the same location then the larger
    ;; block/pattern takes precedence and sorts to the top.
    (not= (:end m1) (:end m2))
    (- (:end m2) (:end m1))

    ;; We shouldn't really reach here.
    :else
    (assert false "missing match comparison case")))

(defn- match->token [match]
  (condp = (:type match)
    :reference
    (let [[_ ref name] (:matches match)
          ref-type (condp = (second ref)
                       \U :user-id
                       \C :channel-id)]
      (cond-> [ref-type (subs ref 1)]
        (not (empty? name))
        (conj name)))

    :code-block
    [(:type match) (str/triml (nth (:matches match) 1))]

    [(:type match) (or (nth (:matches match) 1)
                       (:match match))]))

(defn- replace-html-entities
  "Replace `&amp;`, `&lt;`, and `&gt;` with \"&\", \"<\", and \">\".

  This seems equired as per https://api.slack.com/docs/message-formatting"
  [message]
  (-> message
      (str/replace #"&amp;" "&")
      (str/replace #"&lt;" "<")
      (str/replace #"&gt;" ">")))

(defn- token? [o]
  (keyword? (first o)))

(defn- combine-token [tok1 tok2]
  (cond
    (and (token? tok1)
         (token? tok2))
    [tok1 tok2]

    (and (token? tok1)
         (not (token? tok2)))
    (apply conj [tok1] tok2)

    (and (not (token? tok1))
         (token? tok2))
    (conj tok1 tok2)

    (and (not (token? tok1))
         (not (token? tok2)))
    (apply conj tok1 tok2)

    :else
    (do
      (println "tok1")
      (clojure.pprint/pprint tok1)
      (println "tok2")
      (clojure.pprint/pprint tok2)
      (assert false "Unable to combine tokens"))))

(defn- extract-undecorated-text [message start end]
  [:undecorated (replace-html-entities (subs message start end))])

(defn- can-push-match-stack
  "Determines whether a match can be pushed onto the stack given the current state of the stack.

  This should implement rules when matches should be discarded.
  For example, nothing can be nested in a :code-block, so anything that attempts to push onto the
  stack while a :code-block is on top will be rejected.
  "
  [stack match]
  (let [stack-top (last stack)]
    (if (or (= (:type stack-top) :code-block)
            (= (:type stack-top) :inline-code))
      false
      true)))

(defn- push-match-stack [message stack cursor match]
  (if-not (can-push-match-stack stack match)
    stack
    (cond-> stack
      (and (not (empty? stack))
           (< cursor (:start match)))
      (conj (extract-undecorated-text message cursor (:start match)))
      :finally
      (conj match))))

(defn- match-content-extents [match]
  (-> match
      (:extents)
      (nth 1)))

(defn- collapse-match-stack
  "Returns [popped-stack new-token]"
  [message stack cursor]

  ;; (println "................... collapse-match-stack ................... ")
  ;; (println "message: " message)
  ;; (println "stack")
  ;; (clojure.pprint/pprint stack)
  ;; (println "cursor: " cursor)

  (loop [stack stack
         token nil
         match-end nil] ;; :end position of the last `match` in the stack that is collapsed

    ;; If we have nothing else on the stack, we're done collapsing.
    (if (empty? stack)
      [stack token match-end]

      (let [stack-top (last stack)]

        (cond
          (vector? stack-top)
          (recur (pop stack)
                 (combine-token stack-top token)
                 match-end)

          ;; If it's not time to collapse the item on the top of the stack yet...
          (< cursor (:end stack-top))
          [(conj stack token) nil match-end]

          ;; Close to top item on the stack, by turning it into a token
          :else
          (recur (pop stack)
                 (if (nil? token)
                   (match->token stack-top)
                   [(:type stack-top)
                    (if (not= match-end (:end (match-content-extents stack-top)))
                      (combine-token token (extract-undecorated-text message match-end (:end (match-content-extents stack-top))))
                      token
                      )])
                 (:end stack-top)))))))


(defn- should-close-stack-top?
  "Did the cursor at a position where the top item of the stack should be closed?"
  [stack cursor]
  (let [stack-top (last stack)]
    (if (and stack
             (map? stack-top))
      (>= cursor (:end stack-top))
      false)))

(defn parse2 [message]
  (let [matches (->> (match-all-patterns message-patterns message)
                     (sort match-compare))]

    ;; (clojure.pprint/pprint matches)

    ;; Loop starting from the beginning of the message string...
    (loop [iteration 0
           last-cursor 0
           matches matches
           stack []
           result []]

      ;; (newline)
      ;; (println (str "vvvvvvvvvvvvvv Loop start " iteration " vvvvvvvvvvvvvv"))
      ;; (newline)
      ;; (println "last-cursor" last-cursor )
      ;; (println ">> matches")
      ;; (clojure.pprint/pprint matches)
      ;; (println ">> stack")
      ;; (clojure.pprint/pprint stack)
      ;; (println ">> result")
      ;; (clojure.pprint/pprint result)

      (let [item (first matches)
            cursor (:start item) ;; Cursor is always the starting position of the item being processed
            stack-top (last stack)]
        (cond
          ;; Completed processing for all matches?
          (empty? matches)
          (let [msg-len (count message)
                ;; _ (clojure.pprint/pprint stack)
                [new-stack token cursor] (collapse-match-stack message stack msg-len)
                _ (assert (empty? new-stack))
                cursor (or cursor 0)
                ;; _ (println "result: " (type result))
                ;; _ (clojure.pprint/pprint result)
                ]
            (cond-> result
              (not (nil? token))
              (conj token)
              (not= cursor msg-len)
              (conj (extract-undecorated-text message cursor msg-len))))

          ;; Did we advance past a location where the item on the top of the stack should be closed?
          ;; If so, collapse the top item, add the new token to the results, and keep looping.
          ;; Note that we haven't processed the `item` yet. We're looping only because we want to
          ;; re-bind/re-assign the values of `stack` and `result`.
          ;; The only convenient way seems to just do so through `recur`.
          (should-close-stack-top? stack cursor)
          (let [[next-stack token next-cursor] (collapse-match-stack message stack cursor)]
            (recur (inc iteration)
                   (or next-cursor
                       last-cursor)
                   matches
                   next-stack
                   (if token
                     (conj result token)
                     result)))

          ;; The default thing to do is to push the item into the stack
          :else
          (recur (inc iteration)
                 (:start (match-content-extents item))
                 (rest matches)
                 (push-match-stack message stack last-cursor item)
                 (cond-> result
                   (and (empty? stack)
                        (not= last-cursor cursor))
                   (conj (extract-undecorated-text message last-cursor cursor)))))))))

(defn parse-with-pattern [pattern-k message]
  (let [pattern (get message-patterns pattern-k)]
    (re-seq-pos pattern message)))

(comment

  (let [data (clojurians-log.db.queries/channel-day-messages (user/db) "clojure" "2018-02-02")]
    (time (->> data
               (map #(parse (:message/text %)))
               doall))
    nil)

  (let [data (clojurians-log.db.queries/channel-day-messages (user/db) "clojure" "2018-02-02")]
    (time (->> data
               (map #(parse2 (:message/text %)))
               doall))
    nil)

)
