(ns websocket-echo.core
  (:require [org.httpkit.server :as http-kit]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [clojure.set :as set]
            [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.core.async.impl.channels :as chan]))

;; Структуры данных
(def board-size 40) ; Размер поля

(def tickrate 100) ; Тикрейт в мс

;; Направления для змейки
(def directions {:up [-1 0], :down [1 0], :left [0 -1], :right [0 1]})

;; Функция генерации случайных яблок
(defn generate-food []
  (let [x (rand-int board-size)
        y (rand-int board-size)]
    [x y]))

;; Инициализация состояния игры с использованием STM
(def game-state (atom {:players {}  ; Карта игроков: {id -> состояние змейки}
                       :food nil    ; Позиция яблока
                       :status :running}))

;; Структура для хранения каналов игроков
(def player-channels (atom {}))

(defn random-color []
  (str "#" (apply str (repeatedly 6 #(rand-nth "0123456789ABCDEF")))))

(defn random-position [cellCount] [[0 2] [0 1] [0 0]])

;; Структура для игрока
(defn init-player []
  {:snake (random-position 3) ; Изначальная позиция змейки
   :direction :down  ; Изначальное направление
   :alive true ; Игрок жив
   :color (random-color)})      

;; Обновление позиции змейки
(defn update-snake [snake direction]
  (let [head (first snake)
        [dx dy] (directions direction)
        new-head [(+ (first head) dx) (+ (second head) dy)]
        new-snake (if (= new-head (:food @game-state))  ; Проверяем, съела ли змейка яблоко
                     (cons new-head snake)          ; Если съела, добавляем клетку в начало
                     (cons new-head (butlast snake)))] ; Если не съела, двигаем змейку]
    new-snake))

;; Проверка на столкновение с границей
(defn is-out-of-bounds? [snake]
  (let [[x y] (first snake)]
    (or (neg? x) (neg? y) (>= x board-size) (>= y board-size))))

;; Проверка на столкновение с телом змейки
(defn is-collision? [snake]
  (let [head (first snake)
        body (rest snake)]
    (some #(= head %) body)))

;; Логика для обработки движения игрока
(defn move-player [player-id]
  (swap! game-state
         (fn [state]
           (let [player (get-in state [:players player-id])
                 snake (get player :snake)
                 direction (get player :direction)]
             (if (not (:alive player))
               state
               (let [new-snake (update-snake snake direction)
                     is-collision (or (is-out-of-bounds? new-snake) (is-collision? new-snake))]
                 (if is-collision
                   (
                    (assoc-in state [:players player-id :alive] false)
                    (let [channel (get @player-channels player-id)]
                      (when channel
                        (http-kit/send! channel (json/write-str {:event :died}))))
                    )
                   (let [new-food (if (= (first new-snake) (:food state))
                                    (generate-food)
                                    (:food state))]
                     (assoc state :food new-food
                            :players (assoc-in (:players state) [player-id :snake] new-snake))))))))))

;; Генерация начальной игры для всех игроков
(defn init-game []
  (swap! game-state assoc :food (generate-food)))

;; Отправка обновленного состояния всем игрокам
(defn send-game-update []
  (doseq [player-id (keys (:players @game-state))]
    (let [channel (get @player-channels player-id)]
      (when channel
        (http-kit/send! channel (json/write-str {:type :update :gameState @game-state :event :gameState}))))))

;; Таймер для автоматического обновления состояния игры
(defn start-game-timer []
  (async/go-loop []
    (async/<! (async/timeout tickrate))  ;; каждая секунда
    
    (if (nil? (:food @game-state))
      (init-game)())
    
    ;; Двигаем всех игроков
    (doseq [player-id (keys (:players @game-state))]
      (move-player player-id))
    (send-game-update)  ;; отправка обновленного состояния
    (recur)))  ;; продолжаем цикл

;; Обработчик WebSocket
(defn websocket-handler [req]
  (http-kit/with-channel req channel
    (let [player-id (str (java.util.UUID/randomUUID))]
      ;; Инициализация игрока
      (swap! game-state assoc-in [:players player-id] (init-player))
      ;; Храним канал в отдельной переменной
      (swap! player-channels assoc player-id channel)
      (println "Player connected:" player-id)

      ;; Отправляем стартовое состояние игры
      (http-kit/send! channel (json/write-str {:type :update :gameState @game-state :event :gameState}))

      ;; Обработчик сообщений от клиента
      (http-kit/on-receive channel
                           (fn [msg]
                             (println "Received message:" msg)
                             (let [direction (keyword msg)] ; Получаем направление
                               (println "Changing direction to:" direction)
                               (swap! game-state update :players
                                      #(update % player-id
                                               (fn [player] (assoc player :direction direction)))))
                                   ;; Не нужно снова двигать змейку вручную - она будет двигаться сама
));

      ;; Обработчик закрытия соединения
      (http-kit/on-close channel
                         (fn [status]
                           (println "Player disconnected:" player-id)
                           (swap! game-state update :players dissoc player-id)
                           (swap! player-channels dissoc player-id))))))

(defn -main []
  (init-game)  ;; Инициализация игры
  (start-game-timer) ;; Запуск таймера
  (let [port 3000]
    (println (str "Server running on ws://localhost:" port))
    (http-kit/run-server websocket-handler {:port port})))
