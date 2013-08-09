(ns clojure-mail.core
  (:require [clojure-mail.store :as store]
            [clojure-mail.message :as msg]
            [clojure-mail.folder :as folder]
            [clojure.string :as s])
  (:import [javax.mail Folder Message Flags Flags$Flag]
           [javax.mail.internet InternetAddress]
           [javax.mail.search FlagTerm]))

;; Focus will be more on the reading and parsing of emails.
;; Very rough first draft ideas not suitable for production
;; Sending email is more easily handled by other libs

(def settings (ref {}))

(defn auth! [email pass]
  (dosync
    (ref-set settings
      {:email email :pass pass})))

(def gmail
  {:protocol "imaps"
   :server "imap.gmail.com"})

(defn gen-store
  ([]
    (let [connection (apply store/make-store (cons gmail ((juxt :email :pass) @settings)))]
    (assert (not (string? connection)) connection)
      connection))
  ([user pass]
    (apply store/make-store [gmail user pass])))

(def folder-names
  {:inbox "INBOX"
   :all "[Gmail]/All Mail"
   :sent "[Gmail]/Sent Mail"
   :spam "[Gmail]/Spam"})

(def sub-folder?
  "Check if a folder is a sub folder"
  (fn [folder]
    (if (= 0 (bit-and (.getType folder) Folder/HOLDS_FOLDERS))
      false
      true)))

(defn folder-seq
  "Used to get a sequence of folder names. Note that this does not recursively
   loop through subfolders like the implementation below"
  [store]
  (let [default (store/get-default-folder store)]
    (map (fn [x] (.getName x))
         (.list (store/get-default-folder store)))))

(defn all-messages
  ^{:doc "Given a store and folder returns all messages."}
  [^com.sun.mail.imap.IMAPStore store folder]
  (let [s (.getDefaultFolder store)
        inbox (.getFolder s folder)
        folder (doto inbox (.open Folder/READ_WRITE))]
    (.getMessages folder)))

(defn recent-mail 
  "Just show the last 10 messages from our inbox"
  [store & params] (let [limit (or (first params) 10)]
    (->> (all-messages store (:inbox folder-names))
          reverse
          (take 5))))

(defn message-list
  "A quick summary of your recent emails"
  [store limit]
  (map (comp :subject msg/read-message)
    (recent-mail store limit)))

(defn folders
  "Returns a seq of all IMAP folders inlcuding sub folders"
  ([store] (folders store (.getDefaultFolder store)))
  ([store f]
  (map
    #(cons (.getName %)
      (if (sub-folder? %)
        (folders store %)))
          (.list f))))

(defn message-count
  "Returns the number of messages in a folder"
  [store folder]
  (let [fd (doto (.getFolder store folder)
                 (.open Folder/READ_ONLY))]
    (.getMessageCount fd)))

;; Public api

(defn read-all
  ([folder] (all-messages (gen-store) folder))
  ([folder store] (all-messages store folder)))

(defn get-inbox []
  "Returns all messages from the inbox"
  (read-all
    (get folder-names :inbox)))

(defn recent-first [store folder]
  (->> (all-messages store folder) reverse))

(defn inbox [user pass]
  (let [store (gen-store user pass)]
    (recent-first store "INBOX")))

(defn get-spam []
  (read-all
    (get folder-names :spam)))

(defn read-message
  "Reads a java mail message instance"
  [message]
  (msg/read-message message))

(def flags
  {:answered "ANSWERED"
   :deleted "DELETED"})

(defn user-flags [message]
  (let [flags (msg/flags message)]
    (.getUserFlags flags)))

(defn unread-messages
  "Find unread messages"
  [folder-name]
  (with-open [connection (gen-store)]
    (let [folder (doto (.getFolder connection folder-name) (.open Folder/READ_ONLY))]
      (doall (map read-message (.search folder (FlagTerm. (Flags. Flags$Flag/SEEN) false)))))))

(defn mark-all-read
  [folder-name]
  (with-open [connection (gen-store)]
      (let [folder (doto (.getFolder connection folder-name) (.open Folder/READ_WRITE))
            messages (.search folder (FlagTerm. (Flags. Flags$Flag/SEEN) false))]
         (doall (map #(.setFlags % (Flags. Flags$Flag/SEEN) true) messages))
        nil)))

(defn dump
  "Handy function that dumps out a batch of emails to disk"
  [dir msgs]
  (doseq [msg msgs]
    (.writeTo msg (java.io.FileOutputStream.
      (format "%s%s" dir (str (msg/message-id msg)))))))

(defmacro with-editable-message
  "Render an IMAP message as editable" ;; In order to edit a message, we have to copy it, alter it, append to the folder, and delete the original
  [store folder-name orig-message & body]
  `(let [~'folder (doto (.getFolder ~store ~folder-name) (.open Folder/READ_WRITE))
         ~'message (msg/copy ~orig-message)]
     ~@body
     (.saveChanges ~'message)
     (.appendMessages ~'folder (into-array javax.mail.Message [~'message]))
     (.setFlag ~orig-message javax.mail.Flags$Flag/DELETED true)
     (.expunge ~'folder)
     ~'message))

(defn add-subject-prefix
  "Add a prefix to the subject of a message"
  [store folder-name msg prefix]
   (with-editable-message store folder-name msg
    (let [oldsbj (.getSubject message)]
      (doto message (.setSubject (str prefix oldsbj))))))

(defn flag-message
  "Flag a message"
  [store folder-name msg]
  (with-editable-message store folder-name msg
    (.setFlag message javax.mail.Flags$Flag/FLAGGED true)))
    