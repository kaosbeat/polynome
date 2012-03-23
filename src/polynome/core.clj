(ns polynome.core
  (:require  [monome-serial.core :as monome-core]
             [monome-serial.led :as monome]
             [monome-serial.event-handlers :as handlers]))

(defrecord Event [time x y action])

;;TODO implement me
(defn map-coords
  ([m x y] (map-coords m x y 0))
  ([m x y idx]
     (let [x-inc (case idx
                       0 0
                       1 8
                       2 0
                       3 8)]
       [(+ x x-inc) y])))

;;TODO implement me
(defn- frame-rot
  [m]
  0)

;;TODO implement me
(defn- rotate-frame
  [rot row0 row1 row2 row3 row4 row5 row6 row7]
  [row0 row1 row2 row3 row4 row5 row6 row7])

;;TODO implement me
(defn map-frame-idx
  [m idx]
  idx)

(defn toggle-activation
  [val]
  (case val
        :inactive :active
        :active :inactive
        (throw (Exception. (str "Unknown activation value - expected either :inactive or :active, received" [val])))))

(defn toggle-led-activation
  [val]
  (case val
        0 1
        1 0
        (throw (Exception. (str "Unknown activation value - expected either 0 or 1, received " [val])))))

(defn state-agent
  [m]
  (get-in m [::core :state]))

(defn state
  "Return a full data structure representing the current state of monome m"
  [m]
  @(state-agent m))

(defn led-state
  "Returns a sorted map of the current state of the leds (0 for off and 1 for
  on) for all monome m's coords"
  [m]
  (into (sorted-map) (:led-activation (state m))))

(defn button-state
  "Returns a map of the current state of all the buttons (whether pressed or
  not) for all monome m's buttons"
  [m]
  (:button-activation (state m)))

(defn- empty-led-map
  [coords]
  (into {} (map (fn [el] [el 0]) coords)))

(defn- all-lit-led-map
  [coords]
  (into {} (map (fn [el] [el 1]) coords)))

(defn- empty-button-map
  [coords]
  (into {} (map (fn [el] [el :inactive]) coords)))

(declare dummy?)
(declare coords)

(defn- clear-led-state
  "Resets the led state to all off"
  [state m]
  (let [state (assoc state :led-activation (empty-led-map (coords m)))]
    (when (not (dummy? m))
      (monome/clear m))
    state))

(defn- illuminate-all-led-state
  "Resets the led state to all on"
  [state m]
  (let [state (assoc state :led-activation (all-lit-led-map (coords m)))]
    (when (not (dummy? m))
      (monome/all m))
    state))

(defn- toggle-all-led-state
  "Toggle's the led state"
  [state m]
  (let [led-state (:led-activation state)
        led-state (into {} (map (fn [[k v]] [k (toggle-led-activation v)]) led-state))
        state (assoc state :led-activation led-state)]
    (when-not (dummy? m)
      (doall (map (fn [[[x y] led]] (if (= 1 led)
                                     (monome/led-on m x y)
                                     (monome/led-off m x y)))
                  led-state)))
    state))

(defn- update-led-state
  "Given a monome's state, a new led action adn the coordinates for the target
  of that action returns aa new state representing the application of that action
  to the target"
  [state m action x y]
  (let [state   (case action
                      :led-on (-> state
                                  (assoc-in [:led-activation [x y]] 1))
                      :led-off (-> state
                                   (assoc-in [:led-activation [x y]] 0)))]
    (when-not (dummy? m)
      (case action
            :led-on (monome/led-on m x y)
            :led-off (monome/led-off m x y)))
    state))

(defn- toggle-led-state
  [state m x y]
  (let [led-state     (get (:led-activation state) [x y])
        new-led-state (toggle-led-activation led-state)
        state         (assoc-in state [:led-activation [x y]] new-led-state)]

    (when-not (dummy? m)
      (case new-led-state
            1 (monome/led-on m x y)
            0 (monome/led-off m x y)))
    state))

(declare cols)
(declare rows)

(defn swap-row-led-state
  [state m x-idx vals]
  (let [led-state   (:led-activation state)
        vals        (map (fn [el] (if (= 0 el) 0 1)) vals)
        coords-vals (for [y (cols m)] [[x-idx y] (nth vals y 0)])
        led-state   (reduce (fn [l-state [[x y] val]] (assoc l-state [x y] val))
                            led-state
                            coords-vals)
        state       (assoc state :led-activation led-state)]

    (when-not (dummy? m)
      (doall (map (fn [[[x y] val]] (if (= 0 val)
                                   (monome/led-off m x y)
                                   (monome/led-on m x y)))
                  coords-vals)))

    state))

(defn swap-col-led-state
  [state m y-idx vals]
  (let [led-state   (:led-activation state)
        vals        (map (fn [el] (if (= 0 el) 0 1)) vals)
        coords-vals (for [x (rows m)] [[y-idx x] (nth vals x 0)])
        led-state   (reduce (fn [l-state [[x y] val]] (assoc l-state [x y] val))
                            led-state
                            coords-vals)
        state       (assoc state :led-activation led-state)]

    (when-not (dummy? m)
      (doall (map (fn [[[x y] val]] (if (= 0 val)
                                   (monome/led-off m x y)
                                   (monome/led-on m x y)))
                  coords-vals)))

    state))

(defn mk-coords-map
  [m idx rows]
  (into {}
        (apply concat
               (map-indexed (fn [y row]
                              (map-indexed (fn [x val]
                                             [(map-coords m x y idx) val]) row)) rows))))

(defn update-frame-state
  [state m idx & rows]
  (let [led-state (:led-activation state)
        new-led-state (merge led-state (mk-coords-map m idx rows))
        state (assoc state :led-activation new-led-state)]
    (when-not (dummy? m)
      (apply monome/frame m idx (apply rotate-frame (frame-rot m) rows)))
    state))

(defn connected?
  "Determines whether the given monome is connected"
  [m]
  (monome-core/connected? m))

(defn disconnect
  "Closes the monome comm port"
  [m]
  (monome-core/disconnect m)
  :disconnected)

(defn kind
  [m]
  (get-in m [::core :kind]))

(defn dummy?
  "Returns a boolean value denoting whether the monome is a dummy"
  [m]
  (get-in m [::core :dummy]))

(defn max-x
  "Returns the monome's maximum x coord"
  [m]
  (get-in m [::core :max-x]))

(defn max-y
  "Returns the monome's maximum y coord"
  [m]
  (get-in m [::core :max-y]))

(defn range-x
  "Returns the number of buttons on the x axis"
  [m]
  (get-in m [::core :range-x]))

(defn range-y
  "Returns the number of buttons on the y axis"
  [m]
  (get-in m [::core :range-y]))

(defn rand-x
  "Returns a random x coordinate"
  [m]
  (rand-nth (range (range-x m))))

(defn rand-y
  "Returns a random y coordinate"
  [m]
  (rand-nth (range (range-y m))))

(defn coords
  "Returns a lazy sequence of all pairs of x y coords"
  [m]
  (get-in m [::core :coords]))

(defn rows
  "Returns a sequence of all row indexes (available x coord vals)"
  [m]
  (range 0 (range-x m)))

(defn cols
  "Returns a sequence of all col indexes (available y coord vals)"
  [m]
  (range 0 (range-y m)))

(defn map->frame
  "Warning - only works for 8x8 monomes."
  [m mp]
  (partition 8 (map #(get mp %) (coords m))))

(defn button-ids
  "Returns a seq of unique integers - one for each button on the monome."
  [m]
  (range (* (range-x m) (range-y m))))

(defn button-coords
  "Returns a set of coordinates matchine the id passed in. id is an int in the
   range of 0..num-buttons.
   This is the inverse of button-id"
  [m id]
  (let [y (int (/ id (range-x m)))
        x (- id (* (range-x m) y))]
    [x y]))

(defn button-id
  "Returns a unique integer id for a given set of coordinates. This is the
  inverse of button-coords"
  [m x y]
  (+ (* (range-y m) y) x))

(defn clear
  "Turn off all the leds for the specified monome m"
  [m]
  (send (state-agent m) clear-led-state m)
  :cleared)

(defn all
  "Turn on all the leds for the specified monome m"
  [m]
  (send (state-agent m) illuminate-all-led-state m)
  :all-leds-on)

(defn toggle-all
  "Toggle state of all leds for the specified monome m"
  [m]
  (send (state-agent m) toggle-all-led-state m)
  :leds-toggled)

(defn- led-on*
  "actually lights the monome!"
  [m x y]
  (send (state-agent m) update-led-state m :led-on x y))

(defn- led-off*
  "actually lights the monome!"
  [m x y]
  (send (state-agent m) update-led-state m :led-off x y))

(defn row
  "Change the state of monome m's row at idx to the value of the supplied
  seq where 0 is off 1 (or any other value) is on"
  ;;FIXME: shouldn't reverse vals here - need to sort out rotation
  [m idx vals]
  (send (state-agent m) swap-row-led-state m idx (reverse vals)))

(defn col
  "Change the state of monome m's col at idx to the value of the supplied
  seq where 0 is off 1 (or any other value) is on"
  ;;FIXME: shouldn't reverse vals here - need to sort out rotation
  [m idx vals]
  (send (state-agent m) swap-col-led-state m idx (reverse vals)))

(defn led
  "Change the state of monome m's led at coordinate x y to either on or off
   by specifying a val of 1 or 0 respectively."
  [m x y val]
  (if (= 0 val)
    (apply led-off* m (map-coords m x y))
    (apply led-on* m (map-coords m x y)))
  :led-value-altered)

(defn toggle-led
  "Toggle the state of monome m's led at coordinate x y."
  [m x y]
  (send (state-agent m) toggle-led-state m x y)
  :led-toggled)

(defn led-on
  "Turn on monome m's led at coordinate x y"
  [m x y]
  (apply led-on* m (map-coords m x y))
  :led-illuminated)

(defn led-off
  "Turn off monome m's led at coordinate x y"
  [m x y]
  (apply led-off* m (map-coords m x y))
  :led-extinguished)

(defn- valid-single-frame-row?
  "A frame row is valid if it's sequential, its count is 8 and
  consists of only 1s and 0s"
  [row]
  (and (sequential? row)
       (= (count row) 8)
       (every? #(or (= 1 %)
                    (= 0 %))
               row)))

(defn- validate-single-frame!
  [frame]
  (when-not (or (count frame) 8
                (every? #(valid-single-frame-row? %) frame))
    (throw (Exception. (str "Invalid frame:" frame)))))

(defn single-frame
  "Light a single 8x8 frame. For monomes larger than 8x8 use the idx
  arg to specify which frame to light. For example, 128 monomes
  consist of 2 frames and 256 monomes consist of 4 frames."
  ([m row0 row1 row2 row3 row4 row5 row6 row7]
     (single-frame m 0 row0 row1 row2 row3 row4 row5 row6 row7))
  ([m idx row0 row1 row2 row3 row4 row5 row6 row7]
     (validate-single-frame! [row0 row1 row2 row3 row4 row5 row6 row7])
     (send (state-agent m) update-frame-state m idx row0 row1 row2 row3 row4 row5 row6 row7)
     :frame-updated))

(def frame single-frame)

(declare on-press)
(declare on-release)

(defn light-led-on-sustain
  [m]
  (on-press m (fn [x y s] (led-on m x y)) "light led on sustain on")
  (on-release m (fn [x y s] (led-off m x y)) "light led on sustain off")
  :led-sustain-handler-registered)

(defn event-history
  "Return an ordered vector containing records representing all the trigger
  events (button presses and releases) received by the monome"
  [m]
  (:event-history (state m)))

(defn button-activation
  "Returns a map representing the current state of all buttons (either pressed
  or unpressed. If x and y coords are passed as args just returns the activation
  state of the specific button."
  ([m] (:button-activation (state m)))
  ([m x y] (get (:button-activation (state m)) [x y])))

(defn led-activation
  "Returns a map representing the current state of all leds (0 for unlit,
  1 for lit). If x and y coords are passed as args just returns the state of the
  specific led."
  ([m] (:led-activation (state m)))
  ([m x y] (get (:led-activation (state m)) [x y])))

(defn led-activation-row
  "Returns a seq representing the led states of the specified row"
  [m row-idx]
  (reverse (for [y (range (range-y m))] (get (led-activation m) [row-idx y]))))

(defn led-activation-col
  "Returns a seq representing the led states of the specified col"
  [m row-idx]
  (reverse (for [x (range (range-y m))] (get (led-activation m) [row-idx x]))))

(defn press-count
  ([m] (:press-count (state m)))
  ([m x y] (get (:press-count (state m)) [x y])))

(defn find-event
  "Returns the first event for which fn f returns true or nil if no match is
  found."
  [bs f]
  (first (filter f (:event-history bs))))

(defn prev-event
  [bs x y action]
  (find-event bs #(and (= x (:x %))
                       (= y (:y %))
                       (= action (:action %)))))

(defn prev-press
  [bs x y]
  (prev-event bs x y :press))

(defn prev-release
  [bs x y]
  (prev-event bs x y :release))

(defn- callbacks*
  "Return an atom representing the callbacks associated with monome m"
  [m]
  (get-in m [::core :callbacks]))

(defn callbacks
  "Return a list of callbacks associated with monome m"
  [m]
  @(callbacks* m))

(defn remove-callback
  "Removes the callback with the associated handle"
  [m handle]
  (swap! (callbacks* m) dissoc handle))

(defn remove-all-callbacks
  "Removes all callbacks registered with monome m"
  [m]
  (reset! (callbacks* m) {}))

(defn on-action
  "Register a callback to be called every time a button is pressed or released.
  If called with three params the 2nd param is used as the callback handle.
  The callback fn should take four args:
  action (either :press or release)
  x, y   (button coords)
  state  (history of monome activity"
  ([m f] (on-action m f f))
  ([m handle f]
     (swap! (callbacks* m) assoc handle f)))

(defn on-press
  "Register a callback to be called every time a button is pressed.
   If called with three params the 2nd param is used as the callback handle.
   The callback fn should take three args: x y and the state of the monome"
  ([m f] (on-press m f f))
  ([m handle f] (on-action m handle (fn [action x y state]
                                      (if (= :press action)
                                        (f x y state))))))

(defn on-release
  "Register a callback to be called every time a button is released.
   If called with three params the 2nd param is used as the callback handle.
  The callback fn should take three args: x y and the state of the monome"
  ([m f] (on-release m f f))
  ([m handle f] (on-action m handle (fn [action x y state]
                                      (if (= :release action)
                                        (f x y state))))))

(defn on-sustain
  "Register a callback to be called every time a button is released..
  If called with three params the 2nd param is used as the callback handle.
  The callback fn should take four args:
  x, y  (button coords)
  time  (duration of the button press)
  state (history of monome activity"
  ([m f] (on-sustain m f f))
  ([m handle f]
     (on-release m handle (fn [x y state]
                            (let [press (prev-press state x y)
                                  release (prev-release state x y)
                                  time (- (:time release) (:time press))]
                              (f x y time state))))))

(def MONOME-KINDS
  {
   :64n       [[8   8] :north]
   :64e       [[8   8] :east]
   :64s       [[8   8] :south]
   :64w       [[8   8] :west]
   :128ln     [[16  8] :north]
   :128ls     [[16  8] :south]
   :128pw     [[8  16] :west]
   :128pe     [[8  16] :east]
   :256n      [[16 16] :north]
   :256e      [[16 16] :east]
   :256s      [[16 16] :south]
   :256w      [[16 16] :west]
   :dummy64   [[8   8] :north]
   :dummy128l [[16  8] :north]
   :dummy128p [[8  16] :west]
   :dummy256  [[8   8] :north]
   })

(defn- detect-kind
  [path]
  (condp re-find path
    #"-m64-"      :64n
    #"-m128-"     :128ln
    #"-m256-"     :256n
    #"dummy64"    :dummy64
    #"dummy128"   :dummy128l
    #"dummy128l"  :dummy128l
    #"dummy128p"  :dummy128p
    #"dummy256"   :dummy256
    #"dummy"      :dummy64
    :unknown))

(defn- monome-info
  [kind]
  (let [info (get MONOME-KINDS kind)]
    (when-not info
      (throw (Exception. (str "Unknown monome kind " [kind] ". Expected one of " (keys MONOME-KINDS)))))

    info))

(defn- run-handler [f action x y state]
  (try
    (f action x y state)
    (catch Exception e
      (println "Handler Exception - got args:" [action x y state]) (with-out-str (.printStackTrace e)))))


(defn update-button-state
  "Given a monome's state, a new button action and the coordinates for the
  target of that action returns a new state representing the application of that
  action to the target. Also calls all callbacks with the new state."
  [state callbacks action x y]
  (let [event (Event. (System/currentTimeMillis) x y action)
        state (update-in state [:event-history] conj event)
        state (case action
                    :press   (-> state
                                 (assoc-in [:button-activation [x y]] :active)
                                 (update-in [:press-count [x y]] inc))
                    :release (-> state
                                 (assoc-in [:button-activation [x y]] :inactive)))]

    (doseq [[_ callback] @callbacks] (run-handler callback action x y state))
    state))

(defn init
  "Initialise a monome. When passed only a path, will attempt to infer the kind
  of monome from the pathname. Where this isn't possible, you can either specify
  the kind as a keyword (64n, 128pw , 256s etc. where the number represents the
  number of buttons on the specific monome and the letters represent the cable
  position n,e,s,w and orientation for 128 monomes - p and l for portrait and
  landscape).

  It is also possible to explicitly specify the kind, cable orientation (:north
  :east :south or :west) and num cols and rows.

  If you use one of the :dummy kinds then polynome won't attempt to connect to
  a physical monome - allowing for mocking and testing when a real connection
  isn't feasible.

  Raises an exception if the supplied path isn't valid or is already in use"
  ([path] (init path (detect-kind path)))
  ([path kind] (let [[[n-cols n-rows] cable] (monome-info kind)]
                 (init path kind cable n-cols n-rows)))
  ([path kind cable n-cols n-rows]
     (let [
           dummy?                      (= path "dummy")
           m                           (if dummy? {} (monome-core/connect path))
           max-x                       (dec n-cols)
           max-y                       (dec n-rows)
           range-x                     n-cols
           range-y                     n-rows
           coords                      (for [y (range range-y)
                                             x (range range-x)]
                                         [x y])

           history                     (list)
           led-activation              (empty-led-map coords)
           button-activation           (empty-button-map coords)
           press-count                 (into {} (map (fn [el] [el 0]) coords))

           callbacks                   (atom {})
           state                       (agent {:event-history history
                                               :button-activation button-activation
                                               :led-activation led-activation
                                               :press-count press-count})

           poly-m                      (assoc m ::core {:max-x max-x
                                                        :max-y max-y
                                                        :range-x range-x
                                                        :range-y range-y
                                                        :callbacks callbacks
                                                        :coords coords
                                                        :state state
                                                        :dummy dummy?
                                                        :kind kind
                                                        :path path
                                                        :cable cable})

           update-button-state-handler (fn [action x y]
                                         (send state update-button-state callbacks action x y))]

       (if-not dummy? (handlers/on-action poly-m update-button-state-handler ::state "update monome state"))
       (with-meta poly-m {:type ::polynome}))))

(defmethod print-method ::polynome [p w]
  (let [p (get p ::core)]
      (.write w (format "#<polynome: kind[%s] cable[%s] path[%s] dummy?[%s]>" (:kind p) (:cable p) (:path p) (:dummy p))))
)
