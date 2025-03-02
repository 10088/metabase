(ns metabase.cmd
  "Functions for commands that can be ran from the command-line with the Clojure CLI or the Metabase JAR. These are ran
  as follows:

    <metabase> <command> <options>

  for example, running the `migrate` command and passing it `force` can be done using one of the following ways:

    clojure -M:run migrate force
    java -jar metabase.jar migrate force

  Logic below translates resolves the command itself to a function marked with `^:command` metadata and calls the
  function with arguments as appropriate.

  You can see what commands are available by running the command `help`. This command uses the docstrings and arglists
  associated with each command's entrypoint function to generate descriptions for each command."
  (:refer-clojure :exclude [load import])
  (:require
   [clojure.string :as str]
   [environ.core :as env]
   [medley.core :as m]
   [metabase.config :as config]
   [metabase.mbql.util :as mbql.u]
   [metabase.plugins.classloader :as classloader]
   [metabase.util :as u]
   [metabase.util.i18n :refer [trs]]
   [metabase.util.log :as log]))

(defn- system-exit!
  "Proxy function to System/exit to enable the use of `with-redefs`."
  [return-code]
  (System/exit return-code))

(defn ^:command migrate
  "Run database migrations. Valid options for `direction` are `up`, `force`, `down`, `print`, or `release-locks`."
  [direction]
  (classloader/require 'metabase.cmd.migrate)
  ((resolve 'metabase.cmd.migrate/migrate!) direction))

(defn ^:command load-from-h2
  "Transfer data from existing H2 database to the newly created MySQL or Postgres DB specified by env vars."
  ([]
   (load-from-h2 nil))
  ([h2-connection-string]
   (classloader/require 'metabase.cmd.load-from-h2)
   ((resolve 'metabase.cmd.load-from-h2/load-from-h2!) h2-connection-string)))

(defn ^:command dump-to-h2
  "Transfer data from existing database to newly created H2 DB with specified filename.

  Target H2 file is deleted before dump, unless the --keep-existing flag is given."
  [h2-filename & opts]
  (classloader/require 'metabase.cmd.dump-to-h2)
  (try
    (let [options        {:keep-existing? (boolean (some #{"--keep-existing"} opts))
                          :dump-plaintext? (boolean (some #{"--dump-plaintext"} opts))}]
      ((resolve 'metabase.cmd.dump-to-h2/dump-to-h2!) h2-filename options)
      (println "Dump complete")
      (system-exit! 0))
    (catch Throwable e
      (log/error e "Failed to dump application database to H2 file")
      (system-exit! 1))))

(defn ^:command profile
  "Start Metabase the usual way and exit. Useful for profiling Metabase launch time."
  []
  ;; override env var that would normally make Jetty block forever
  (alter-var-root #'env/env assoc :mb-jetty-join "false")
  (u/profile "start-normally" ((resolve 'metabase.core/start-normally))))

(defn ^:command reset-password
  "Reset the password for a user with `email-address`."
  [email-address]
  (classloader/require 'metabase.cmd.reset-password)
  ((resolve 'metabase.cmd.reset-password/reset-password!) email-address))

(defn ^:command help
  "Show this help message listing valid Metabase commands."
  []
  (println "Valid commands are:")
  (doseq [[symb varr] (sort (ns-interns 'metabase.cmd))
          :when       (:command (meta varr))]
    (println symb (str/join " " (:arglists (meta varr))))
    (println "\t" (when-let [dox (:doc (meta varr))]
                    (str/replace dox #"\s+" " ")))) ; replace newlines or multiple spaces with single spaces
  (println "\nSome other commands you might find useful:\n")
  (println "java -cp metabase.jar org.h2.tools.Shell -url jdbc:h2:/path/to/metabase.db")
  (println "\tOpen an SQL shell for the Metabase H2 DB"))

(defn ^:command version
  "Print version information about Metabase and the current system."
  []
  (println "Metabase version:" config/mb-version-info)
  (println "\nOS:"
           (System/getProperty "os.name")
           (System/getProperty "os.version")
           (System/getProperty "os.arch"))
  (println "\nJava version:"
           (System/getProperty "java.vm.name")
           (System/getProperty "java.version"))
  (println "\nCountry:"       (System/getProperty "user.country"))
  (println "System timezone:" (System/getProperty "user.timezone"))
  (println "Language:"        (System/getProperty "user.language"))
  (println "File encoding:"   (System/getProperty "file.encoding")))

(defn ^:command api-documentation
  "Generate a markdown file containing documentation for all API endpoints. This is written to a file called
  `docs/api-documentation.md`."
  []
  (classloader/require 'metabase.cmd.endpoint-dox)
  ((resolve 'metabase.cmd.endpoint-dox/generate-dox!)))

(defn ^:command environment-variables-documentation
  "Generates a markdown file containing documentation for environment variables relevant to configuring Metabase."
  []
  (classloader/require 'metabase.cmd.env-var-dox)
  ((resolve 'metabase.cmd.env-var-dox/generate-dox!)))

(defn ^:command driver-methods
  "Print a list of all multimethods available for a driver to implement, optionally with their docstrings."
  ([]
   (classloader/require 'metabase.cmd.driver-methods)
   ((resolve 'metabase.cmd.driver-methods/print-available-multimethods) false))
  ([_docs]
   (classloader/require 'metabase.cmd.driver-methods)
   ((resolve 'metabase.cmd.driver-methods/print-available-multimethods) true)))

(defn- cmd-args->map
  [args]
  (into {}
        (for [[k v] (partition 2 args)]
          [(mbql.u/normalize-token (subs k 2)) v])))

(defn- resolve-enterprise-command [symb]
  (try
    (classloader/require (symbol (namespace symb)))
    (resolve symb)
    (catch Throwable e
      (throw (ex-info (trs "The ''{0}'' command is only available in Metabase Enterprise Edition." (name symb))
                      {:command symb}
                      e)))))

;;; [[load]] and [[dump]] are the SerDes v1 versions. [[import]] and [[export]] are SerDes v2 versions of [[load]]
;;; and [[dump]]. The v1 versions are deprecated.

(defn- import*
  "Impl for [[load]] (v1) and [[import]] (v2)."
  ([path v2?]
   (import* path v2? "--mode" :skip "--on-error" :continue))

  ([path v2? & options]
   (when-not v2?
     (log/warn (u/colorize :red (trs "''load'' is deprecated and will be removed in a future release. Please migrate to ''import''."))))
   (let [options (cond-> options
                   v2?
                   (concat ["--v2" "true"]))
         cmd (resolve-enterprise-command 'metabase-enterprise.serialization.cmd/load)]
     (cmd path (->> options
                    cmd-args->map
                    (m/map-vals mbql.u/normalize-token))))))

(defn ^:command ^:deprecated load
  "Deprecated: prefer [[import]] instead.

  Load serialized metabase instance as created by [[dump]] command from directory `path`.

  `--mode` can be one of `:update` or `:skip` (default). `--on-error` can be `:abort` or `:continue` (default)."
  [path & options]
  (apply import* path false options))

(defn ^:command import
  "Load serialized Metabase instance as created by the [[export]] command from directory `path`. Replaces the [[load]]
  command. Options are the same as for `load`.

  `--mode` can be one of `:update` or `:skip` (default). `--on-error` can be `:abort` or `:continue` (default)."
  [path & options]
  (apply import* path true options))

(defn- export*
  ([path v2?]
   (export* path v2? "--state" :active))

  ([path v2? & options]
   (when-not v2?
     (log/warn (u/colorize :red (trs "''dump'' is deprecated and will be removed in a future release. Please migrate to ''export''."))))
   (let [options (cond-> options
                   v2?
                   (concat ["--v2" true]))
         cmd     (resolve-enterprise-command 'metabase-enterprise.serialization.cmd/dump)]
     (cmd path (cmd-args->map options)))))

(defn ^:command ^:deprecated dump
  "Deprecated: prefer [[export]] instead.

  Serialized metabase instance into directory `path`. `args` options may contain --state option with one of
  `active` (default), `all`. With `active` option, do not dump archived entities."
  [path & options]
  (apply export* path false options))

(defn ^:command export
  "Serialize a Metabase into directory `path`. Replaces the [[dump]] command. Options are the same as for `path`.

  `options` may contain `--state` option with one of `active` (default), `all`. With `active` option, do not dump
  archived entities."
  [path & options]
  (apply export* path true options))

(defn ^:command seed-entity-ids
  "Add entity IDs for instances of serializable models that don't already have them."
  [& options]
  (let [cmd     (resolve-enterprise-command 'metabase-enterprise.serialization.cmd/seed-entity-ids)
        options (cmd-args->map options)]
    (system-exit! (if (cmd options)
                    0
                    1))))

(defn ^:command rotate-encryption-key
  "Rotate the encryption key of a metabase database. The MB_ENCRYPTION_SECRET_KEY environment variable has to be set to
  the current key, and the parameter `new-key` has to be the new key. `new-key` has to be at least 16 chars."
  [new-key]
  (classloader/require 'metabase.cmd.rotate-encryption-key)
  (try
    ((resolve 'metabase.cmd.rotate-encryption-key/rotate-encryption-key!) new-key)
    (log/info "Encryption key rotation OK.")
    (system-exit! 0)
    (catch Throwable _e
      (log/error "ERROR ROTATING KEY.")
      (system-exit! 1))))

;;; ------------------------------------------------ Validate Commands ----------------------------------------------

(defn- cmd->var [command-name]
  (ns-resolve 'metabase.cmd (symbol command-name)))

(defn- arg-list-count-ok? [arg-list arg-count]
  (if (some #{'&} arg-list)
    ;; subtract 1 for the & and 1 for the symbol after &
    ;; e.g. [a b & c] => 2
    (>= arg-count (- (count arg-list) 2))
    (= arg-count (count arg-list))))

(defn- arg-count-good? [command-name args]
  (let [arg-lists (-> command-name cmd->var meta :arglists)
        arg-count-matches (mapv #(arg-list-count-ok? % (count args)) arg-lists)]
    (if (some true? arg-count-matches)
      [true]
      [false (str "The '" command-name "' command requires "
                  (when (> 1 (count arg-lists)) "one of ")
                  "the following arguments: "
                  (str/join " | " (map pr-str arg-lists))
                  ", but received: " (pr-str (vec args)) ".")])))

;;; ------------------------------------------------ Running Commands ------------------------------------------------

(defn- cmd->fn
  "Returns [error-message] if there is an error, otherwise [nil command-fn]"
  [command-name args]
  (cond
    (not (seq command-name))
    ["No command given."]

    (nil? (:command (meta (cmd->var command-name))))
    [(str "Unrecognized command: '" command-name "'")]

    (let [[ok? _message] (arg-count-good? command-name args)]
      (not ok?))
    [(second (arg-count-good? command-name args))]

    :else
    [nil @(cmd->var command-name)]))

(defn run-cmd
  "Run `cmd` with `args`. This is a function above. e.g. `clojure -M:run metabase migrate force` becomes
  `(migrate \"force\")`."
  [cmd args]
  (let [[error-msg command-fn] (cmd->fn cmd args)]
    (if error-msg
      (do
        (println (u/format-color 'red error-msg))
        (System/exit 1))
      (try (apply command-fn args)
           (catch Throwable e
             (.printStackTrace e)
             (println (u/format-color 'red "Command failed with exception: %s" (.getMessage e)))
             (System/exit 1)))))
  (System/exit 0))
