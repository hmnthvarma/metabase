(ns metabase.models.setting
  "Settings are a fast and simple way to create a setting that can be set from the admin page. They are saved to the
  application Database, but intelligently cached internally for super-fast lookups.

  Define a new Setting with [[defsetting]] (optionally supplying things like default value, type, or custom getters &
  setters):

    (defsetting mandrill-api-key \"API key for Mandrill\")

  The newly-defined Setting will automatically be made available to the frontend client depending on its [[Visibility]].

  You can also set the value via the corresponding env var, which looks like `MB_MANDRILL_API_KEY`, where the name of
  the Setting is converted to uppercase and dashes to underscores.

  The var created with [[defsetting]] can be used as a getter/setter, or you can use [[get]] and [[set!]]:

    (require '[metabase.models.setting :as setting])

    (setting/get :mandrill-api-key) ; only returns values set explicitly from the Admin Panel
    (mandrill-api-key)              ; returns value set in the Admin Panel, OR value of corresponding env var,
                                    ; OR the default value, if any (in that order)

    (setting/set! :mandrill-api-key \"NEW_KEY\")
    (mandrill-api-key! \"NEW_KEY\")

    (setting/set! :mandrill-api-key nil)
    (mandrill-api-key! nil)

  You can define additional Settings types adding implementations of [[default-tag-for-type]], [[get-value-of-type]],
  and [[set-value-of-type!]].

  [[writable-settings]] and [[user-readable-values-map]] can be used to fetch *all* Admin-writable and
  User-readable Settings, respectively. See their docstrings for more information.

  ### User-local and Database-local Settings

  Starting in 0.42.0, some Settings are allowed to have Database-specific values that override the normal site-wide
  value. Similarly, starting in 0.43.0, some Settings are allowed to have User-specific values. These are similar in
  concept to buffer-local variables in Emacs Lisp.

  When a Setting is allowed to be User or Database local, any values in [[*user-local-values*]] or
  [[*database-local-values*]] for that Setting will be returned preferentially to site-wide values of that Setting.
  [[*user-local-values*]] comes from the `User.settings` column in the application DB, and [[*database-local-values*]]
  comes from the `Database.settings` column. `nil` values in [[*user-local-values*]] and [[*database-local-values*]]
  are ignored, i.e. you cannot 'unset' a site-wide value with a User- or Database-local one.

  Whether or not a Setting can be User- or Database-local is controlled by the `:user-local` and `:database-local`
  options passed to [[defsetting]]. A Setting can only be User-local *or* Database-local, not both; this is enforced
  when the Setting is defined. There are three valid values of these options:

  * `:only` means this Setting can *only* have a User- or Database-local value and cannot have a 'normal' site-wide
  value. It cannot be set via env var. Default values are still allowed for User- and Database-local-only Settings.
  Database-local-only Settings are never returned by [[writable-settings]] or [[user-readable-values-map]] regardless of
  their [[Visibility]].

  * `:allowed` means this Setting can be User- or Database-local and can also have a normal site-wide value; if both
  are specified, the User- or Database-specific value will be returned preferentially when we are in the context of a
  specific User or Database (i.e., [[*user-local-values*]] or [[*database-local-values*]] is bound).

  * `:never` means User- or Database-specific values cannot be set for this Setting. Values in [[*user-local-values*]]
  and [[*database-local-values*]] will be ignored.

  `:never` is the default value of both `:user-local` and `:database-local`; to allow User- or Database-local values,
  the Setting definition must explicitly specify `:only` or `:allowed` for the appropriate option.

  If a User-local setting is written in the context of an API request (i.e., when [[metabase.api.common/*current-user*]]
  is bound), the value will be local to the current user. If it is written outside of an API request, a site-wide
  value will be written. (At the time of this writing, there is not yet a FE-client-friendly way to set Database-local
  values. Just set them manually in the application DB until we figure that out.)

  Custom setter functions do not affect User- or Database-local values; they always set the site-wide value.

  See #14055 and #19399 for more information about and motivation behind User- and Database-local Settings."
  (:refer-clojure :exclude [get])
  (:require
   [clojure.core :as core]
   [clojure.data :as data]
   [clojure.data.csv :as csv]
   [clojure.string :as str]
   [environ.core :as env]
   [malli.core :as mc]
   [medley.core :as m]
   [metabase.api.common :as api]
   [metabase.config :as config]
   [metabase.events :as events]
   [metabase.models.serialization :as serdes]
   [metabase.models.setting.cache :as setting.cache]
   [metabase.plugins.classloader :as classloader]
   [metabase.server.middleware.json]
   [metabase.util :as u]
   [metabase.util.date-2 :as u.date]
   [metabase.util.encryption :as encryption]
   [metabase.util.i18n :refer [deferred-trs deferred-tru trs tru]]
   [metabase.util.json :as json]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [methodical.core :as methodical]
   [toucan2.core :as t2])
  (:import
   (clojure.lang Keyword)
   (com.fasterxml.jackson.core JsonParseException)
   (com.fasterxml.jackson.core.io JsonEOFException)
   (java.io StringWriter)
   (java.time.temporal Temporal)
   (java.util.concurrent TimeUnit)
   (java.util.concurrent.locks ReentrantLock)))

;;; this namespace is required for side effects since it has the JSON encoder definitions for `java.time` classes and
;;; other things we need for `:json` settings
(comment metabase.server.middleware.json/keep-me)

(def ^:dynamic *database-local-values*
  "Database-local Settings values (as a map of Setting name -> already-deserialized value). This comes from the value of
  `Database.settings` in the application DB. When bound, any Setting that *can* be Database-local will have a value
  from this map returned preferentially to the site-wide value.

  This is normally bound automatically in Query Processor context
  by [[metabase.query-processor.setup/do-with-database-local-settings]]. You may need to manually bind it in other
  places where you want to use Database-local values."
  nil)

(def ^:dynamic *user-local-values*
  "User-local Settings values (as a map of Setting name -> already-deserialized value). This comes from the value of
  `User.settings` in the application DB. When bound, any Setting that *can* be User-local will have a value from this
  map returned preferentially to the site-wide value.

  This is a delay so that the settings for a user are loaded only if and when they are actually needed during a given
  API request.

  This is normally bound automatically by session middleware, in [[metabase.server.middleware.session]]."
  (delay (atom nil)))

(def ^:private retired-setting-names
  "A set of setting names which existed in previous versions of Metabase, but are no longer used. New settings may not use
  these names to avoid unintended side-effects if an application database still stores values for these settings."
  #{"-site-url"
    "enable-advanced-humanization"
    "metabot-enabled"
    "ldap-sync-admin-group"
    "user-recent-views"
    "most-recently-viewed-dashboard"})

(def ^:dynamic *allow-retired-setting-names*
  "A dynamic val that controls whether it's allowed to use retired settings.
  Primarily used in test to disable retired setting check."
  false)

(declare admin-writable-site-wide-settings get-value-of-type set-value-of-type!)

(methodical/defmethod t2/table-name :model/Setting [_model] :setting)

(doto :model/Setting
  (derive :metabase/model))

(methodical/defmethod t2/primary-keys :model/Setting [_model] [:key])

(defmethod serdes/hash-fields :model/Setting
  [_setting]
  [:key])

(declare export?)

(defmethod serdes/extract-all "Setting" [_model _opts]
  (for [{:keys [key value]} (admin-writable-site-wide-settings
                             :getter (partial get-value-of-type :string))
        :when (export? key)]
    {:serdes/meta [{:model "Setting" :id (name key)}]
     :key key
     :value value}))

(defmethod serdes/load-find-local "Setting" [[{:keys [id]}]]
  (get-value-of-type :string (keyword id)))

(defmethod serdes/load-one! "Setting" [{:keys [key value]} _]
  (set-value-of-type! :string key value))

(def ^:private Type
  [:fn
   {:error/message "Valid Setting :type"}
   (fn [a-type]
     (contains? (set (keys (methods get-value-of-type))) a-type))])

(def ^:private Visibility
  [:enum :public :authenticated :settings-manager :admin :internal])

(defmulti default-tag-for-type
  "Type tag that will be included in the Setting's metadata, so that the getter function will not cause reflection
  warnings."
  {:arglists '([setting-type])}
  keyword)

(defmethod default-tag-for-type :default   [_] `Object)
(defmethod default-tag-for-type :string    [_] `String)
(defmethod default-tag-for-type :boolean   [_] `Boolean)
(defmethod default-tag-for-type :integer   [_] `Long)
(defmethod default-tag-for-type :double    [_] `Double)
(defmethod default-tag-for-type :timestamp [_] `Temporal)
(defmethod default-tag-for-type :keyword   [_] `Keyword)

(defn- validate-default-value-for-type
  "Check whether the `:default` value of a Setting (if provided) agrees with the Setting's `:type` and its `:tag` (which
  usually comes from [[default-tag-for-type]])."
  [{:keys [tag default] :as _setting-definition}]
  ;; the errors below don't need to be i18n'ed since they're definition-time errors rather than user-facing
  (when (some? tag)
    (assert ((some-fn symbol? string?) tag) (format "Setting :tag should be a symbol or string, got: ^%s %s"
                                                    (.getCanonicalName (class tag))
                                                    (pr-str tag))))
  (when (and (some? default)
             (some? tag))
    (let [klass (if (string? tag)
                  (try
                    (Class/forName tag)
                    (catch Throwable e
                      e))
                  (resolve tag))]
      (when-not (class? klass)
        (throw (ex-info (format "Cannot resolve :tag %s to a class. Is it fully qualified?" (pr-str tag))
                        {:tag klass}
                        (when (instance? Throwable klass) klass))))
      (when-not (instance? klass default)
        (throw (ex-info (format "Wrong :default type: got ^%s %s, but expected a %s"
                                (.getCanonicalName (class default))
                                (pr-str default)
                                (.getCanonicalName ^Class klass))
                        {:tag klass}))))))

;; This is called `LocalOption` rather than `DatabaseLocalOption` or something like that because we intend to also add
;; User-Local Settings at some point in the future. The will use the same options
(def ^:private LocalOption
  "Schema for valid values of `:database-local`. See [[metabase.models.setting]] docstring for description of what these
  options mean."
  [:enum :only :allowed :never])

(def ^:private SettingDefinition
  [:map
   [:name        :keyword]
   [:munged-name :string]
   [:namespace   :symbol]

   ;; description is validated via the macro, not schema
   ;; Use `:doc` to include a map with additional documentation, for use when generating the environment variable docs
   ;; from source. To exclude a setting from documenation, set to `false`. See metabase.cmd.env-var-dox.
   [:description :any]

   [:doc     :any]
   [:default :any]

   ;; all values are stored in DB as Strings,
   [:type Type]

   ;; different getters/setters take care of parsing/unparsing
   [:getter ifn?]
   [:setter ifn?]

   ;; an init function can be used to seed initial values
   [:init [:maybe ifn?]]

   ;; type annotation, e.g. ^String, to be applied. Defaults to tag based on :type
   [:tag :symbol]

   ;; is this sensitive (never show in plaintext), like a password? (default: false)
   [:sensitive? :boolean]

   ;; where this setting should be visible (default: :admin)
   [:visibility Visibility]

   ;; should this setting be encrypted. Available options are `:no` or `:when-encryption-key-set` (the setting will be
   ;; encrypted when `MB_ENCRYPTION_SECRET_KEY` is set, otherwise we can't encrypt). This is required for `:timestamp`,
   ;; `:json`, and `:csv`-typed settings. Defaults to `:no` for all other types.
   [:encryption [:enum :no :when-encryption-key-set]]

   ;; should this setting be serialized?
   [:export? :boolean]

   ;; should the getter always fetch this value "fresh" from the DB? (default: false)
   [:cache? :boolean]

   ;; if non-nil, contains the Metabase version in which this setting was deprecated
   [:deprecated [:maybe :string]]

   ;; whether this Setting can be Database-local or User-local. See [[metabase.models.setting]] docstring for more info.
   [:database-local LocalOption]
   [:user-local     LocalOption]

   ;; should this setting be read from env vars?
   [:can-read-from-env? :boolean]

   ;; called whenever setting value changes, whether from update-setting! or a cache refresh. used to handle cases
   ;; where a change to the cache necessitates a change to some value outside the cache, like when a change the
   ;; `:site-locale` setting requires a call to `java.util.Locale/setDefault`
   [:on-change [:maybe ifn?]]

   ;; If non-nil, determines the Enterprise feature flag required to use this setting. If the feature is not enabled,
   ;; the setting will behave the same as if `enabled?` returns `false` (see below).
   [:feature [:maybe :keyword]]

   ;; Function which returns true if the setting should be enabled. If it returns false, the setting will throw an
   ;; exception when it is attempted to be set, and will return its default value when read. Defaults to always enabled.
   [:enabled? [:maybe ifn?]]

   ;; Keyword that determines what kind of audit log entry should be created when this setting is written. Options are
   ;; `:never`, `:no-value`, `:raw-value`, and `:getter`. User- and database-local settings are never audited. `:getter`
   ;; should be used for most non-sensitive settings, and will log the value returned by its getter, which may be a
   ;; the default getter or a custom one.
   ;; (default: `:no-value`)
   [:audit [:maybe [:enum :never :no-value :raw-value :getter]]]])

(defonce ^{:doc "Map of loaded defsettings"}
  registered-settings
  (atom {}))

(defprotocol ^:private Resolvable
  (resolve-setting [setting-definition-or-name]
    "Resolve the definition map for a Setting. `setting-definition-or-name` map be a map, keyword, or string."))

(extend-protocol Resolvable
  clojure.lang.IPersistentMap
  (resolve-setting [this] this)

  String
  (resolve-setting [s]
    (resolve-setting (keyword s)))

  clojure.lang.Keyword
  (resolve-setting [k]
    (or (@registered-settings k)
        (@registered-settings (u/->kebab-case-en k))
        (throw (ex-info (tru "Unknown setting: {0}" k)
                        {::unknown-setting-error true
                         :registered-settings
                         (sort (keys @registered-settings))})))))

(defn registered?
  "Returns true if a setting is registered with the given name."
  [setting-keyword-or-name]
  (contains? @registered-settings (keyword setting-keyword-or-name)))

;; The actual watch that triggers this happens in [[metabase.models.setting.cache/cache*]] because the cache might be
;; swapped out depending on which app DB we have in play
;;
;; this isn't really something that needs to be a multimethod, but I'm using it because the logic can't really live in
;; [[metabase.models.setting.cache]] but the cache has to live here; this is a good enough way to prevent circular
;; references for now
(defmethod setting.cache/call-on-change :default
  [old new]
  (let [rs      @registered-settings
        [d1 d2] (data/diff old new)]
    (doseq [changed-setting (into (set (keys d1))
                                  (set (keys d2)))]
      (when-let [on-change (get-in rs [(keyword changed-setting) :on-change])]
        (on-change (core/get old changed-setting) (core/get new changed-setting))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                      get                                                       |
;;; +----------------------------------------------------------------------------------------------------------------+

(defprotocol ^:private SettingName
  (setting-name ^String [setting-definition-or-name]
    "String name of a Setting, e.g. `\"site-url\"`. Works with strings, keywords, or Setting definition maps."))

(extend-protocol SettingName
  clojure.lang.IPersistentMap
  (setting-name [this]
    (name (:name this)))

  String
  (setting-name [this]
    this)

  clojure.lang.Keyword
  (setting-name [this]
    (name this)))

(defn- database-local-only? [setting]
  (= (:database-local (resolve-setting setting)) :only))

(defn- user-local-only? [setting]
  (= (:user-local (resolve-setting setting)) :only))

(defn- allows-database-local-values? [setting]
  (#{:only :allowed} (:database-local (resolve-setting setting))))

(defn- database-local-value [setting-definition-or-name]
  (let [{setting-name :name, :as setting} (resolve-setting setting-definition-or-name)]
    (when (allows-database-local-values? setting)
      (core/get *database-local-values* setting-name))))

(defn- prohibits-encryption? [setting-or-name]
  (= :no (:encryption (resolve-setting setting-or-name))))

(defn- allows-user-local-values? [setting]
  (#{:only :allowed} (:user-local (resolve-setting setting))))

(defn- allows-site-wide-values? [setting]
  (and
   (not (database-local-only? setting))
   (not (user-local-only? setting))))

(defn- allows-setting-via-env? [setting-definition-or-name]
  (:can-read-from-env? (resolve-setting setting-definition-or-name)))

(defn- site-wide-only? [setting]
  (and
   (not (allows-database-local-values? setting))
   (not (allows-user-local-values? setting))))

(defn- user-local-value [setting-definition-or-name]
  (let [{setting-name :name, :as setting} (resolve-setting setting-definition-or-name)]
    (when (allows-user-local-values? setting)
      (core/get @@*user-local-values* setting-name))))

(defn- should-set-user-local-value? [setting-definition-or-name]
  (let [setting (resolve-setting setting-definition-or-name)]
    (and (allows-user-local-values? setting)
         @@*user-local-values*)))

(defn- set-user-local-value! [setting-definition-or-name value]
  (let [{setting-name :name} (resolve-setting setting-definition-or-name)]
    ;; Update the atom in *user-local-values* with the new value before writing to the DB. This ensures that
    ;; subsequent setting updates within the same API request will not overwrite this value.
    (swap! @*user-local-values* u/assoc-dissoc setting-name value)
    (t2/update! 'User api/*current-user-id* {:settings (json/encode @@*user-local-values*)})))

(def ^:dynamic *enforce-setting-access-checks*
  "A dynamic var that controls whether we should enforce checks on setting access. Defaults to false; should be
  set to true when settings are being written directly via /api/setting endpoints."
  false)

(defn- has-feature?
  [feature]
  (u/ignore-exceptions
    (classloader/require 'metabase.premium-features.token-check))
  (let [has-feature?' (resolve 'metabase.premium-features.token-check/has-feature?)]
    (has-feature?' feature)))

(defn has-advanced-setting-access?
  "If `advanced-permissions` is enabled, check if current user has permissions to edit `setting`.
  Return `false` for all non-admins when `advanced-permissions` is disabled. Return `true` for all admins."
  []
  (or api/*is-superuser?*
      (do
        (when config/ee-available?
          (classloader/require 'metabase-enterprise.advanced-permissions.common
                               'metabase.premium-features.token-check))
        (if-let [current-user-has-application-permissions?
                 (and (has-feature? :advanced-permissions)
                      (resolve 'metabase-enterprise.advanced-permissions.common/current-user-has-application-permissions?))]
          (current-user-has-application-permissions? :setting)
          false))))

(defn- current-user-can-access-setting?
  "This checks whether the current user should have the ability to read or write the provided setting.

  By default this function always returns `true`, but setting access control can be turned on the dynamic var
  `*enforce-setting-access-checks*`. This is because this enforcement is only necessary when settings are being
  accessed directly via the API, but not in most other places on the backend."
  [setting]
  (or (not *enforce-setting-access-checks*)
      (nil? api/*current-user-id*)
      api/*is-superuser?*
      (and
       ;; Non-admin setting managers can only access settings that are not marked as admin-only
       (not api/*is-superuser?*)
       (has-advanced-setting-access?)
       (not= (:visibility setting) :admin))
      (and
       ;; Non-admins can only access user-local settings not marked as admin-only
       (allows-user-local-values? setting)
       (not= (:visibility setting) :admin))))

(defn- munge-setting-name
  "Munge names so that they are legal for bash. Only allows for alphanumeric characters,  underscores, and hyphens."
  [setting-nm]
  (str/replace (name setting-nm) #"[^a-zA-Z0-9_-]*" ""))

(defn- env-var-name
  "Get the env var corresponding to `setting-definition-or-name`. (This is used primarily for documentation purposes)."
  ^String [setting-definition-or-name]
  (str "MB_" (-> (setting-name setting-definition-or-name)
                 munge-setting-name
                 (str/replace "-" "_")
                 u/upper-case-en)))

(def ^:private env-var-translation-cache
  "A simple cache for remembering the translation of setting name `:foo-bar?` to Environ-style key `:mb-foo-bar`."
  (atom {}))

(defn setting-env-map-name
  "Correctly translate a setting to the keyword it will be found at in [[env/env]]."
  [setting-definition-or-name]
  (let [sname (setting-name setting-definition-or-name)]
    (or (@env-var-translation-cache sname)
        ((swap! env-var-translation-cache assoc sname (keyword (str "mb-" (munge-setting-name sname)))) sname))))

(defn env-var-value
  "Get the value of `setting-definition-or-name` from the corresponding env var, if any.
   The name of the Setting is converted to uppercase and dashes to underscores; for example, a setting named
  `default-domain` can be set with the env var `MB_DEFAULT_DOMAIN`. Note that this strips out characters that are not
  legal for shells. Setting `foo-bar?` will expect to find the key `:mb-foo-bar` which will be sourced from the
  environment variable `MB_FOO_BAR`."
  ^String [setting-definition-or-name]
  (let [setting (resolve-setting setting-definition-or-name)]
    (when (and (allows-site-wide-values? setting)
               (allows-setting-via-env? setting))
      (let [v (env/env (setting-env-map-name setting))]
        (when (seq v)
          v)))))

(def ^:private ^:dynamic *disable-init* false)

(declare get)
(declare set!)

(defn read-setting
  "Fetch the value of `setting-definition-or-name`. What this means depends on the Setting's `:getter`; by default, this
  looks for first for a corresponding env var, then checks the cache, then returns the default value of the Setting,
  if any.

  Note: This will bypass initialization, i.e. it could return nil for a nonce"
  [setting-definition-or-name]
  (binding [*disable-init* true]
    (get setting-definition-or-name)))

(defn- db-value [setting-definition-or-name]
  (t2/select-one-fn :value :model/Setting :key (setting-name setting-definition-or-name)))

(def ^:private db-is-set-up-var (atom nil))

(defn- db-is-set-up? []
  ;; this should never be hit. it is just overly cautious against a NPE here. But no way this cannot resolve
  (let [f (or @db-is-set-up-var
              (reset! db-is-set-up-var (requiring-resolve 'metabase.db/db-is-set-up?)))]
    (if f (f) false)))

(defn- db-or-cache-value
  "Get the value, if any, of `setting-definition-or-name` from the DB (using / restoring the cache as needed)."
  ^String [setting-definition-or-name]
  (let [setting  (resolve-setting setting-definition-or-name)]
    ;; cannot use db (and cache populated from db) if db is not set up
    (when (and (db-is-set-up?) (allows-site-wide-values? setting))
      (not-empty
       (if config/*disable-setting-cache*
         (db-value setting)
         (do
            ;; gotcha - returns immediately if another process is restoring it, i.e. before it's been populated
           (setting.cache/restore-cache-if-needed!)
           (let [cache (setting.cache/cache)]
             (if (nil? cache)
                ;; nil if we returned early above, and the cache is still being restored - in that case hit the db
               (db-value setting)
               (core/get cache (setting-name setting-definition-or-name))))))))))

(defonce ^:private ^ReentrantLock init-lock (ReentrantLock.))

(defn- init! [setting-definition-or-name]
  (let [{:keys [init] :as setting} (resolve-setting setting-definition-or-name)]
    (when init
      (when (not (db-is-set-up?))
        (throw (ex-info "Cannot initialize setting before the db is set up" {:setting setting})))
      ;; We do not need to interact with the restore-cache-lock as it is OK to race with it.
      (if-not (.tryLock init-lock 30 TimeUnit/SECONDS)
        (throw (ex-info "Unable to get initialization lock" {:setting setting-definition-or-name}))
        (try
          (u/or-with some?
            ;; perhaps another process initialized this setting while we were waiting for the lock
            (read-setting setting)
            (when init
              (when-let [init-value (init)]
                (metabase.models.setting/set! setting init-value :bypass-read-only? true))))
          (finally
            (.unlock init-lock)))))))

(defn- realize
  "Parsing a setting may result in a lazy value. Use this to ensure we finish parsing."
  [value]
  (when (coll? value)
    (dorun (map realize value)))
  value)

(defn default-value
  "Get the `:default` value of `setting-definition-or-name` if one was specified."
  [setting-definition-or-name]
  (let [{:keys [default]} (resolve-setting setting-definition-or-name)]
    default))

(defmacro ^:private or-some [& clauses]
  ;; Like `clojure.core/or` but for the first non-nil value.
  (let [[x & rst] clauses]
    (if (empty? rst)
      x
      `(if-some [x# ~x] x# (or-some ~@rst)))))

(defn get-raw-value
  "Get the raw value of a Setting from wherever it may be specified. Value is fetched by trying the following sources in
  order:

  1. From [[*user-local-values*]] if this Setting is allowed to have User-local values
  2. From [[*database-local-values*]] if this Setting is allowed to have Database-local values
  3. From the corresponding env var (excluding empty string values)
  4. From the application database (i.e., set via the admin panel) (excluding empty string values)
  5. The default value, if one was specified

  !!!!!!!!!! The value returned MAY OR MAY NOT be a String depending on the source !!!!!!!!!!

  This is the underlying function powering all the other getters such as methods of [[get-value-of-type]]. These
  getter functions *must* be coded to handle either String or non-String values. You can use the three-arity version
  of this function to do that.

  Three-arity version can be used to specify how to parse non-empty String values (`parse-fn`) and under what
  conditions values can be returned directly (`pred`) -- see [[get-value-of-type]] for `:boolean` for example usage."
  ([setting-definition-or-name]
   (let [setting (resolve-setting setting-definition-or-name)]
     (or-some (user-local-value setting)
              (database-local-value setting)
              (env-var-value setting)
              (db-or-cache-value setting)
              (:default setting)
              (when (and (:init setting) (not *disable-init*))
                (init! setting)))))

  ([setting-definition-or-name pred parse-fn]
   (let [parse     (fn [v]
                     (try
                       (realize (parse-fn v))
                       (catch Throwable e
                         (let [{setting-name :name} (resolve-setting setting-definition-or-name)]
                           (throw (ex-info (tru "Error parsing Setting {0}: {1}" setting-name (ex-message e))
                                           {:setting setting-name}
                                           e))))))
         raw-value (get-raw-value setting-definition-or-name)
         v         (cond-> raw-value
                     (string? raw-value) parse)]
     (when (pred v)
       v))))

(defmulti get-value-of-type
  "Get the value of `setting-definition-or-name` as a value of type `setting-type`. This is used as the default getter
  for Settings with `setting-type`.

  Impls should call [[get-raw-value]] to get the underlying possibly-serialized value and parse it appropriately if it
  comes back as a String; impls should only return values that are of the correct type (e.g. the `:boolean` impl
  should only return [[Boolean]] values)."
  {:arglists '([setting-type setting-definition-or-name])}
  (fn [setting-type _]
    (keyword setting-type)))

(defmethod get-value-of-type :string
  [_setting-type setting-definition-or-name]
  (get-raw-value setting-definition-or-name string? identity))

(mu/defn string->boolean :- [:maybe :boolean]
  "Interpret a `string-value` of a Setting as a boolean."
  [string-value :- [:maybe :string]]
  (when (seq string-value)
    (case (u/lower-case-en string-value)
      "true"  true
      "false" false
      (throw (Exception.
              (tru "Invalid value for string: must be either \"true\" or \"false\" (case-insensitive)."))))))

(defn- random-uuid-str []
  (str (random-uuid)))

;; This base allows the bundling of a number of attributes together. In some sense it is defining a subtype / mixin.
(def uuid-nonce-base
  "A random uuid value that should never change again"
  {:type   :string
   :setter :none
   :audit  :never
   :init   random-uuid-str})

;; Strings are parsed as follows:
;;
;; * `true`  if *lowercased* string value is `true`
;; * `false` if *lowercased* string value is `false`.
;; * Otherwise, throw an Exception.
(defmethod get-value-of-type :boolean
  [_setting-type setting-definition-or-name]
  (get-raw-value setting-definition-or-name boolean? string->boolean))

(defmethod get-value-of-type :integer
  [_setting-type setting-definition-or-name]
  (get-raw-value setting-definition-or-name integer? #(Long/parseLong ^String %)))

(defmethod get-value-of-type :positive-integer
  [_setting-type setting-definition-or-name]
  (get-raw-value setting-definition-or-name pos-int? #(Long/parseLong ^String %)))

(defmethod get-value-of-type :double
  [_setting-type setting-definition-or-name]
  (get-raw-value setting-definition-or-name double? #(Double/parseDouble ^String %)))

(defmethod get-value-of-type :keyword
  [_setting-type setting-definition-or-name]
  (get-raw-value setting-definition-or-name keyword? keyword))

(defmethod get-value-of-type :timestamp
  [_setting-type setting-definition-or-name]
  (get-raw-value setting-definition-or-name #(instance? Temporal %) u.date/parse))

(defmethod get-value-of-type :json
  [_setting-type setting-definition-or-name]
  (get-raw-value setting-definition-or-name coll? json/decode+kw))

(defmethod get-value-of-type :csv
  [_setting-type setting-definition-or-name]
  (get-raw-value setting-definition-or-name sequential? (comp first csv/read-csv)))

(defn- default-getter-for-type [setting-type]
  (partial get-value-of-type (keyword setting-type)))

(defn get
  "Fetch the value of `setting-definition-or-name`. What this means depends on the Setting's `:getter`; by default, this
  looks for first for a corresponding env var, then checks the cache, then returns the default value of the Setting,
  if any.

  Note: If the setting has an initializer, and this is the first time accessing, a value will be generated and saved
  unless *disable-init* has been bound to a truthy value."
  [setting-definition-or-name]
  (let [{:keys [cache? getter enabled? default feature]} (resolve-setting setting-definition-or-name)
        disable-cache?                                   (or config/*disable-setting-cache* (not cache?))]
    (if (or (and feature (not (has-feature? feature)))
            (and enabled? (not (enabled?))))
      default
      (if (= config/*disable-setting-cache* disable-cache?) ;; Optimization: only bind dynvar if necessary.
        (getter)
        (binding [config/*disable-setting-cache* disable-cache?]
          (getter))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                      set!                                                      |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- update-setting!
  "Update an existing Setting. Used internally by [[set-value-of-type!]] for `:string` below; do not use directly."
  [setting-name new-value]
  (assert (not= setting-name setting.cache/settings-last-updated-key)
          (tru "You cannot update `settings-last-updated` yourself! This is done automatically."))
  ;; Toucan 2 version of `update!` will do transforms and stuff like that
  (t2/update! :model/Setting :key setting-name {:value new-value}))

(defn- set-new-setting!
  "Insert a new row for a Setting. Used internally by [[set-value-of-type!]] for `:string` below; do not use directly."
  [setting-name new-value]
  (try (first (t2/insert-returning-instances! :model/Setting
                                              :key   setting-name
                                              :value new-value))
       ;; if for some reason inserting the new value fails it almost certainly means the cache is out of date
       ;; and there's actually a row in the DB that's not in the cache for some reason. Go ahead and update the
       ;; existing value and log a warning
       (catch Throwable e
         (log/warn "Error inserting a new Setting:\n"
                   (ex-message e) "\n"
                   "Assuming Setting already exists in DB and updating existing value.")
         (update-setting! setting-name new-value))))

(defn- obfuscated-value? [v]
  (when (seq v)
    (boolean (re-matches #"^\*{10}.{2}$" v))))

(defn obfuscate-value
  "Obfuscate the value of sensitive Setting. We'll still show the last 2 characters so admins can still check that the
  value is what's expected (e.g. the correct password).

    (obfuscate-value \"sensitivePASSWORD123\") ;; -> \"**********23\""
  [s]
  (str "**********" (str/join (take-last 2 (str s)))))

(defmulti set-value-of-type!
  "Set the value of a `setting-type` `setting-definition-or-name`. A `nil` value deletes the current value of the
  Setting (when set in the application database). Returns `new-value`.

  Impls of this method should ultimately call the implementation for `:string`, which handles the low-level logic of
  updating the cache and application database."
  {:arglists '([setting-type setting-definition-or-name new-value])}
  (fn [setting-type _ _]
    (keyword setting-type)))

(mu/defmethod set-value-of-type! :string
  [_setting-type setting-definition-or-name new-value :- [:maybe :string]]
  (let [new-value                         (when (seq new-value)
                                            new-value)
        {:keys [sensitive? deprecated]
         :as setting}                     (resolve-setting setting-definition-or-name)
        obfuscated?                       (and sensitive? (obfuscated-value? new-value))
        setting-name                      (setting-name setting)]
    ;; if someone attempts to set a sensitive setting to an obfuscated value (probably via a misuse of the `set-many!` function, setting values that have not changed), ignore the change. Log a message that we are ignoring it.
    (if obfuscated?
      (log/infof "Attempted to set Setting %s to obfuscated value. Ignoring change." setting-name)
      (do
        (when (and deprecated (not (nil? new-value)))
          (log/warnf "Setting %s is deprecated as of Metabase %s and may be removed in a future version."
                     setting-name deprecated))
        (when (and
               (= :only (:user-local setting))
               (not (should-set-user-local-value? setting)))
          (log/warnf "Setting %s can only be set in a user-local way, but there are no *user-local-values*." setting-name))
        (if (should-set-user-local-value? setting)
          ;; If this is user-local and this is being set in the context of an API call, we don't want to update the
          ;; site-wide value or write or read from the cache
          (set-user-local-value! setting-name new-value)
          (do
            ;; make sure we're not trying to set the value of a Database-local-only Setting
            (when-not (allows-site-wide-values? setting)
              (throw (ex-info (tru "Site-wide values are not allowed for Setting {0}" (:name setting))
                              {:setting (:name setting)})))
            ;; always update the cache entirely when updating a Setting.
            (setting.cache/restore-cache!)
            ;; write to DB
            (cond
              (nil? new-value)
              (t2/delete! (t2/table-name :model/Setting) :key setting-name)

              ;; if there's a value in the cache then the row already exists in the DB; update that
              (contains? (setting.cache/cache) setting-name)
              (update-setting! setting-name new-value)

              ;; if there's nothing in the cache then the row doesn't exist, insert a new one
              :else
              (set-new-setting! setting-name new-value))
            ;; update cached value
            (setting.cache/update-cache! setting-name new-value)
            ;; Record the fact that a Setting has been updated so eventually other instances (if applicable) find out
            ;; about it (For Settings that don't use the Cache, don't update the `last-updated` value, because it will
            ;; cause other instances to do needless reloading of the cache from the DB)
            (when-not config/*disable-setting-cache*
              (setting.cache/update-settings-last-updated!))))
        ;; Now return the `new-value`.
        new-value))))

(defmethod set-value-of-type! :keyword
  [_setting-type setting-definition-or-name new-value]
  (set-value-of-type!
   :string setting-definition-or-name
   (u/qualified-name new-value)))

(defmethod set-value-of-type! :boolean
  [setting-type setting-definition-or-name new-value]
  (if (string? new-value)
    (set-value-of-type! setting-type setting-definition-or-name (string->boolean new-value))
    (let [s (case new-value
              true  "true"
              false "false"
              nil   nil)]
      (set-value-of-type! :string setting-definition-or-name s))))

(defmethod set-value-of-type! :integer
  [_setting-type setting-definition-or-name new-value]
  (set-value-of-type!
   :string setting-definition-or-name
   (when new-value
     (assert (or (integer? new-value)
                 (and (string? new-value)
                      (re-matches #"^-?\d+$" new-value))))
     (str new-value))))

(defmethod set-value-of-type! :positive-integer
  [_setting-type setting-definition-or-name new-value]
  (set-value-of-type!
   :string setting-definition-or-name
   (when new-value
     (assert (or (pos-int? new-value)
                 (and (string? new-value)
                      (re-matches #"^[1-9]\d*$" new-value))))
     (str new-value))))

(defmethod set-value-of-type! :double
  [_setting-type setting-definition-or-name new-value]
  (set-value-of-type!
   :string setting-definition-or-name
   (when new-value
     (assert (or (number? new-value)
                 (and (string? new-value)
                      (re-matches #"[+-]?([0-9]*[.])?[0-9]+" new-value))))
     (str new-value))))

(defmethod set-value-of-type! :json
  [_setting-type setting-definition-or-name new-value]
  (set-value-of-type!
   :string setting-definition-or-name
   (some-> new-value json/encode)))

(defmethod set-value-of-type! :timestamp
  [_setting-type setting-definition-or-name new-value]
  (set-value-of-type!
   :string setting-definition-or-name
   (some-> new-value u.date/format)))

(defn- serialize-csv [value]
  (cond
    ;; if we're passed as string, assume it's already CSV-encoded
    (string? value)
    value

    (sequential? value)
    (let [s (with-open [writer (StringWriter.)]
              (csv/write-csv writer [value])
              (str writer))]
      (first (str/split-lines s)))

    :else
    value))

(defmethod set-value-of-type! :csv
  [_setting-type setting-definition-or-name new-value]
  (set-value-of-type! :string setting-definition-or-name (serialize-csv new-value)))

(defn- default-setter-for-type [setting-type]
  (partial set-value-of-type! (keyword setting-type)))

(defn- audit-setting-change!
  [{:keys [name audit sensitive?]} previous-value new-value]
  (let [maybe-obfuscate #(cond-> % sensitive? obfuscate-value)]
    (events/publish-event!
     :event/setting-update
     {:details (merge {:key name}
                      (when (not= audit :no-value)
                        {:previous-value (maybe-obfuscate previous-value)
                         :new-value      (maybe-obfuscate new-value)}))
      :user-id api/*current-user-id*
      :model  :model/Setting})))

(defn- should-audit?
  "Returns true if the setting change should be written to the `audit_log`."
  [setting]
  (not= (:audit setting) :never))

(defn- set-with-audit-logging!
  "Calls the setting's setter with `new-value`, and then writes the change to the `audit_log` table if necessary."
  [{:keys [getter audit setter] setting-type :type :as setting} new-value bypass-read-only?]
  (let [setter (if (and bypass-read-only? (= :none setter))
                 (partial set-value-of-type! setting-type setting)
                 setter)]
    (if (should-audit? setting)
      (let [audit-value-fn #(condp = audit
                              :no-value nil
                              :raw-value (get-raw-value setting)
                              :getter (getter))
            previous-value (audit-value-fn)]
        (u/prog1 (setter new-value)
          (audit-setting-change! setting previous-value (audit-value-fn))))
      (setter new-value))))

(defn set!
  "Set the value of `setting-definition-or-name`. What this means depends on the Setting's `:setter`; by default, this
  just updates the Settings cache and writes its value to the DB.

    (set :mandrill-api-key \"xyz123\")

  Style note: prefer using the setting directly instead:

    (mandrill-api-key \"xyz123\")

  This method will throw an exception if trying to update a read-only setting, unless `:bypass-read-only?` is set."
  [setting-definition-or-name new-value & {:keys [bypass-read-only?]}]
  (let [{:keys [setter cache? enabled? feature] :as setting} (resolve-setting setting-definition-or-name)
        name                                                 (setting-name setting)]
    (when (and feature (not (has-feature? feature)))
      (throw (ex-info (tru "Setting {0} is not enabled because feature {1} is not available" name feature) setting)))
    (when (and enabled? (not (enabled?)))
      (throw (ex-info (tru "Setting {0} is not enabled" name) setting)))
    (when-not (current-user-can-access-setting? setting)
      (throw (ex-info (tru "You do not have access to the setting {0}" name) setting)))
    (when-not bypass-read-only?
      (when (= setter :none)
        (throw (UnsupportedOperationException. (tru "You cannot set {0}; it is a read-only setting." name)))))
    (binding [config/*disable-setting-cache* (not cache?)]
      (set-with-audit-logging! setting new-value bypass-read-only?))))

(defn- extract-encryption-or-default
  "Encryption is turned off or on according to (in order of preference):

  - the value you specify in `defsetting`,

  - ON for settings marked as `sensitive?`

  - ON for settings with a setter of `:none` (the specific value here doesn't really matter, we just don't want the
  caller to need to provide a value)

  - OFF for types unlikely to contain secrets. As of this writing, that's booleans, numbers, keywords, and timestamps

  If none of these conditions are met (a non-`:sensitive?` string/json/csv value you're storing in the database, and
  you didn't provide a value) then we'll throw an exception telling you that you need to provide it. This way, when we
  add new settings, we'll think about their sensitivity level and make a conscious decision about whether they need to
  be encrypted or not."
  [setting]
  (or
   (:encryption setting)
   ;; NOTE: if none of the below conditions is met, users of `defsetting` will be required to
   ;; provide a value for `:encryption`.
   ;;
   ;; if a setting is `:sensitive?`, default to encrypting it
   (when (:sensitive? setting)
     :when-encryption-key-set)
   ;; if a setting isn't stored in the DB, the value doesn't really matter, but provide
   ;; a default so the caller doesn't have to
   (when (= (:setter setting) :none)
     :when-encryption-key-set)
   ;; if the setting isn't a type likely to contain secrets, default to plaintext
   (when (contains? #{:boolean :integer :positive-integer :double :keyword :timestamp} (:type setting))
     :no)

   (throw (ex-info (trs "`:encryption` is a required option for setting {0}" (:name setting))
                   {:setting setting}))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                               register-setting!                                                |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn register-setting!
  "Register a new Setting with a map of [[SettingDefinition]] attributes. Returns the map it was passed. This is used
  internally by [[defsetting]]; you shouldn't need to use it yourself."
  [{setting-name :name, setting-ns :namespace, setting-type :type, default :default, :as setting}]
  (let [munged-name (munge-setting-name (name setting-name))]
    (u/prog1 (let [setting-type (mc/assert Type (or setting-type :string))]
               (merge
                {:name           setting-name
                 :munged-name    munged-name
                 :namespace      setting-ns
                 :description    nil
                 :doc            nil
                 :type           setting-type
                 :default        default
                 :on-change      nil
                 :getter         (partial (default-getter-for-type setting-type) setting-name)
                 :setter         (partial (default-setter-for-type setting-type) setting-name)
                 :init           nil
                 :tag            (default-tag-for-type setting-type)
                 :visibility     :admin
                 :encryption     (extract-encryption-or-default setting)
                 :export?        false
                 :sensitive?     false
                 :cache?         true
                 :feature        nil
                 :database-local :never
                 :user-local     :never
                 :deprecated     nil
                 :enabled?       nil
                 :can-read-from-env?       true
                 ;; Disable auditing by default for user- or database-local settings
                 :audit          (if (site-wide-only? setting) :no-value :never)}
                (dissoc setting :name :type :default)))
      (mc/assert SettingDefinition <>)
      (validate-default-value-for-type <>)
      ;; eastwood complains about (setting-name @registered-settings) for shadowing the function `setting-name`
      (when-let [registered-setting (core/get @registered-settings setting-name)]
        (when (not= setting-ns (:namespace registered-setting))
          ;; not i18n'ed because this is supposed to be developer-facing only.
          (throw (ex-info (format "Setting %s already registered in %s. You can remove the old definition with (swap! %s dissoc %s)"
                                  setting-name
                                  (:namespace registered-setting)
                                  `registered-settings
                                  (keyword setting-name))
                          {:existing-setting (dissoc registered-setting :on-change :getter :setter)}))))
      (when-let [same-munge (first (filter (comp #{munged-name} :munged-name)
                                           (vals @registered-settings)))]
        (when (not= setting-name (:name same-munge)) ;; redefinitions are fine
          (throw (ex-info (tru "Setting names would collide: {0} and {1}"
                               setting-name (:name same-munge))
                          {:existing-setting (dissoc same-munge :on-change :getter :setter)
                           :new-setting      (dissoc <> :on-change :getter :setter)}))))
      (when (and (retired-setting-names (name setting-name)) (not *allow-retired-setting-names*))
        (throw (ex-info (tru "Setting name ''{0}'' is retired; use a different name instead" (name setting-name))
                        {:retired-setting-name (name setting-name)
                         :new-setting          (dissoc <> :on-change :getter :setter)})))
      (when (and (allows-user-local-values? setting) (allows-database-local-values? setting))
        (throw (ex-info (tru "Setting {0} allows both user-local and database-local values; this is not supported"
                             setting-name)
                        {:setting setting})))
      (when (and (some? (:default setting)) (:init setting))
        (throw (ex-info (tru "Setting {0} uses both :default and :init options, which are mutually exclusive"
                             setting-name)
                        {:setting setting})))
      (when (and (:enabled? setting) (:feature setting))
        (throw (ex-info (tru "Setting {0} uses both :enabled? and :feature options, which are mutually exclusive"
                             setting-name)
                        {:setting setting})))
      (swap! registered-settings assoc setting-name <>))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                defsetting macro                                                |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- setting-fn-docstring [{:keys [default description], setting-type :type, :as setting}]
  ;; indentation below is intentional to make it clearer what shape the generated documentation is going to take.
  (str
   (description) \newline
   \newline
   (format "`%s` is a `%s` Setting. You can get its value by calling:\n" (setting-name setting) setting-type)
   \newline
   (format "    (%s)\n"                                                  (setting-name setting))
   \newline
   "and set its value by calling:\n"
   \newline
   (format "    (%s! <new-value>)\n"                                     (setting-name setting))
   \newline
   (format "You can also set its value with the env var `%s`.\n"         (env-var-name setting))
   \newline
   "Clear its value by calling:\n"
   \newline
   (format "    (%s! nil)\n"                                             (setting-name setting))
   \newline
   (format "Its default value is `%s`."                                  (pr-str default))))

(defn setting-fn-metadata
  "Impl for [[defsetting]]. Create metadata for [[setting-fn]]."
  [getter-or-setter {:keys [tag deprecated], :as setting}]
  {:arglists   (case getter-or-setter
                 :getter (list (with-meta [] {:tag tag}))
                 :setter (list (with-meta '[new-value] {:tag tag})))
   :deprecated deprecated
   :doc        (setting-fn-docstring setting)})

(defn setting-fn
  "Impl for [[defsetting]]. Create the automatically defined `getter-or-setter` function for Settings defined
  by [[defsetting]]."
  [getter-or-setter setting]
  (case getter-or-setter
    :getter (fn setting-getter* []
              (get setting))
    :setter (fn setting-setter* [new-value]
              ;; need to qualify this or otherwise the reader gets this confused with the set! used for things like
              ;; (set! *warn-on-reflection* true)
              ;; :refer-clojure :exclude doesn't seem to work in this case
              (metabase.models.setting/set! setting new-value))))

;; The next few functions are for validating the Setting description (i.e., docstring) at macroexpansion time. They
;; check that the docstring is a valid deferred i18n form (e.g. [[metabase.util.i18n/deferred-tru]]) so the Setting
;; description will be localized properly when it shows up in the FE admin interface.

(def ^:private allowed-deferred-i18n-forms
  #{`deferred-trs `deferred-tru})

(defn- is-form?
  "Whether `form` is a function call/macro call form starting with a symbol in `symbols`.

    (is-form? #{`deferred-tru} `(deferred-tru \"wow\")) ; -> true"
  [symbols form]
  (when (and (list? form)
             (symbol? (first form)))
    ;; resolve the symbol to a var and convert back to a symbol so we can get the actual name rather than whatever
    ;; alias the current namespace happens to be using
    (let [symb (symbol (resolve (first form)))]
      ((set symbols) symb))))

(defn- valid-trs-or-tru? [desc]
  (is-form? allowed-deferred-i18n-forms desc))

(defn- validate-description-form*
  "Check that `description-form` is a i18n form (e.g. [[metabase.util.i18n/deferred-tru]]).
   If not, return a form for an exception to throw at a later stage.
   The reason for this strange behaviour is that we need to build the exception at compile time, but will only know
   whether we should throw it once all the macro arguments have been evaluated at runtime."
  [description-form]
  (when-not (valid-trs-or-tru? description-form)
    ;; this doesn't need to be i18n'ed because it's a compile-time error.
    `(ex-info (str "defsetting docstrings must be a *deferred* i18n form unless the Setting has"
                   " `:visibility` `:internal`, `:setter` `:none`, or is defined in a test namespace."
                   (format " Got: ^%s %s"
                           (some-> ~description-form class (.getCanonicalName))
                           (pr-str ~description-form)))
              {:description-form ~description-form})))

;; This exists as its own method so that we can stub it in tests
(defn- ns-in-test? [ns-name] (str/ends-with? ns-name "-test"))

(defn- requires-i18n?
  [setting-definition]
  (and (not= (:visibility setting-definition) :internal)
       (not= (:setter setting-definition) :none)
       (not (ns-in-test? (:namespace setting-definition)))))

(defn merge-base
  "Use values in the optional `:base` map as default values for `setting-options`"
  [{:keys [base] :as setting-options}]
  (if-not base
    setting-options
    (do (assert (not (contains? base :export)) ":export? must not be set in :base")
        (merge base (dissoc setting-options :base)))))

(defmacro defsetting
  "Defines a new Setting that will be added to the DB at some point in the future.
  Conveniently can be used as a getter/setter as well

  (defsetting mandrill-api-key (trs \"API key for Mandrill.\"))
  (mandrill-api-key)            ; get the value
  (mandrill-api-key! new-value) ; update the value
  (mandrill-api-key! nil)       ; delete the value

  A setting can be set from the Admin Panel or via the corresponding env var, eg. `MB_MANDRILL_API_KEY` for the
  example above.

  You may optionally pass any of the `options` below:

  ###### `:default`

  The default value of the setting. This must be of the same type as the Setting type, e.g. the default for an
  `:integer` setting must be some sort of integer. (default: `nil`)

  ###### `:init`

  A optional 0-arity function which returns the same type as the Setting type, e.g. for an `:integer` setting must be
  some sort of integer. This function will be used to generate an initial value for the setting the first time it is
  accessed. This value will be saved, in case the function is expensive or non-deterministic.

  ###### `:type`

  `:string` (default) or one of the other types that implement [[get-value-of-type]] and [[set-value-of-type]].
  Non-`:string` Settings have special default getters and setters that automatically coerce values to the correct
  types.

  ###### `:visibility`

  Controls where this setting is visibile, and who can update it. Possible values are:

    Visibility       | Who Can See It?              | Who Can Update It?
    ---------------- | ---------------------------- | --------------------
    :public          | The entire world             | Admins and Settings Managers
    :authenticated   | Logged-in Users              | Admins and Settings Managers
    :settings-manager| Admins and Settings Managers | Admins and Settings Managers
    :admin           | Admins                       | Admins
    :internal        | Nobody                       | No one (usually for env-var-only settings)

  'Settings Managers' are non-admin users with the 'settings' permission, which gives them access to the Settings page
  in the Admin Panel.

  ###### `:export?`

  Whether this Setting is included when producing a serializing settings export.

  ###### `:getter`

  A custom getter fn, which takes no arguments. Overrides the default implementation. (This can in turn call functions
  in this namespace like methods of [[get-value-of-type]] to invoke the 'parent' getter behavior.)

  ###### `:setter`

  A custom setter fn, which takes a single argument, or `:none` for read-only settings. Overrides the default
  implementation. (This can in turn call methods of [[set-value-of-type!]] to invoke 'parent' setter behavior. Keep in
  mind that the custom setter may be passed `nil`, which should clear the values of the Setting.)

  ###### `:cache?`

  Should this Setting be cached? (default `true`)? Be careful when disabling this, because it could have a very
  negative performance impact.

  ###### `:sensitive?`

  Is this a sensitive setting, such as a password, that we should never return in plaintext? (Default: `false`).
  Obfuscation is not done by getter functions, but instead by functions that ultimately return these values via the
  API, such as [[writable-settings]] below. (In other words, code in the backend can continute to consume
  sensitive Settings normally; sensitivity is a purely user-facing option.)

  ###### `:database-local`

  The ability of this Setting to be /Database-local/. Valid values are `:only`, `:allowed`, and `:never`. Default:
  `:never`. See docstring for [[metabase.models.setting]] for more information.

  ###### `:user-local`

  Whether this Setting is /User-local/. Valid values are `:only`, `:allowed`, and `:never`. Default: `:never`. See
  docstring for [[metabase.models.setting]] for more info.

  ###### `:deprecated`

  If this setting is deprecated, this should contain a string of the Metabase version in which the setting was
  deprecated. A deprecation notice will be logged whenever the setting is written. (Default: `nil`).

  ###### `:on-change`

  Do you want to update something else when this setting changes? Takes a function which takes 2 arguments, `old`, and
  `new` and calls it with the old and new settings values. By default, the :on-change will be missing, and nothing
  will happen, in [[call-on-change]] below.

  ###### `:feature`

  If non-nil, determines the Enterprise feature flag required to use this setting. If the feature is not enabled,
  the setting will behave the same as if `enabled?` returns `false` (see below).

  ###### `enabled?`

  Function which returns true if the setting should be enabled. If it returns false, the setting will throw an
  exception when it is attempted to be set, and will return its default value when read. Defaults to always enabled.

  ###### `audit`

  Keyword that determines what kind of audit log entry should be created when this setting is written. Options are
  `:never`, `:no-value`, `:raw-value`, and `:getter`. User- and database-local settings are never audited. `:getter`
  should be used for most non-sensitive settings, and will log the value returned by its getter, which may be
  the default getter or a custom one. `:raw-value` will audit the raw string value of the setting in the database.
  (default: `:no-value` for most settings; `:never` for user- and database-local settings, settings with no setter,
  and `:sensitive` settings.)

  ###### `base`

  A map which can provide values for any of the above options, except for :export?.
  Any top level options will override what's in this base map.
  The use case for this map is sharing strongly coupled options between similar settings, see [[uuid-nonce-base]].
  "
  {:style/indent 1}
  [setting-symbol description & {:as options}]
  {:pre [(symbol? setting-symbol)
         (not (namespace setting-symbol))
         ;; don't put exclamation points in your Setting names. We don't want functions like `exciting!` for the getter
         ;; and `exciting!!` for the setter.
         (not (str/includes? (name setting-symbol) "!"))]}
  (let [;; we need the compile-time description form to check whether it supports i18n
        ;; we only build the ex for now - we must check the runtime expanded setting-definition for whether i18n is required
        maybe-i18n-exception     (validate-description-form* description)
        setting-metadata         {:name        (keyword setting-symbol)
                                  ;; wrap the description form in a thunk, so its result updates with its dependencies
                                  :description `(fn [] ~description)
                                  :namespace   (list 'quote (ns-name *ns*))}
        ;; create symbols for the getter and setter functions e.g. `my-setting` and `my-setting!` respectively.
        ;; preserve metadata from the `setting-symbol` passed to `defsetting`.
        setting-getter-fn-symbol setting-symbol
        setting-setter-fn-symbol (-> (symbol (str (name setting-symbol) \!))
                                     (with-meta (meta setting-symbol)))
        setting-definition-symbol (gensym "setting-")]
    `(let [setting-options#          (merge (merge-base ~options) ~setting-metadata)
           ~setting-definition-symbol (register-setting! setting-options#)]
       ~(when maybe-i18n-exception
          `(when (#'requires-i18n? ~setting-definition-symbol)
             (throw ~maybe-i18n-exception)))
       (-> (def ~setting-getter-fn-symbol (setting-fn :getter ~setting-definition-symbol))
           (alter-meta! merge (setting-fn-metadata :getter ~setting-definition-symbol)))
       ;; unfortunately we can't evaluate this condition at compile time, as the options might contain runtime forms.
       (when (not= (:setter ~setting-definition-symbol) :none)
         ;; therefore we need to do some runtime skullduggery to ensure the var is only created conditionally
         (-> (intern (:namespace ~setting-definition-symbol)
                     '~setting-setter-fn-symbol
                     (setting-fn :setter ~setting-definition-symbol))
             (alter-meta! merge (setting-fn-metadata :setter ~setting-definition-symbol)))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                 EXTRA UTIL FNS                                                 |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn set-many!
  "Set the value of several Settings at once.

    (set-many! {:mandrill-api-key \"xyz123\", :another-setting \"ABC\"})"
  [settings]
  ;; if setting any of the settings fails, roll back the entire DB transaction and the restore the cache from the DB
  ;; to revert any changes in the cache
  (try
    (t2/with-transaction [_conn]
      (doseq [[k v] settings]
        (metabase.models.setting/set! k v)))
    settings
    (catch Throwable e
      (setting.cache/restore-cache!)
      (throw e))))

(defn user-facing-value
  "Get the value of a Setting that should be displayed to a User (i.e. via `/api/setting/` endpoints): for Settings set
  via env vars, or Settings whose value has not been set (i.e., Settings whose value is the same as the default value)
  no value is displayed; for sensitive Settings, the value is obfuscated.

  Accepts options:

  * `:getter` -- the getter function to use to fetch the Setting value. By default, uses `setting/get`, which will
  convert the setting to the appropriate type; you can use `(partial get-value-of-type :string)` to get all string
  values of Settings, for example."
  [setting-definition-or-name & {:keys [getter], :or {getter get}}]
  (let [{:keys [sensitive? visibility default], k :name, :as setting} (resolve-setting setting-definition-or-name)
        unparsed-value                                                (get-value-of-type :string k)
        parsed-value                                                  (getter k)
        ;; `default` and `env-var-value` are probably still in serialized form so compare
        value-is-default?                                             (= parsed-value default)
        value-is-from-env-var?                                        (some-> (env-var-value setting) (= unparsed-value))]
    (cond
      (not (current-user-can-access-setting? setting))
      (throw (ex-info (tru "You do not have access to the setting {0}" k) setting))

      ;; Settings set via an env var aren't returned for security purposes.
      (or value-is-default? value-is-from-env-var?)
      nil

      (= visibility :internal)
      (throw (Exception. (tru "Setting {0} is internal" k)))

      sensitive?
      (obfuscate-value parsed-value)

      :else
      parsed-value)))

(defn- set-via-env-var? [setting]
  (some? (env-var-value setting)))

(defn- export? [setting-name]
  (:export? (core/get @registered-settings (keyword setting-name))))

(defn- user-facing-info
  [{:keys [default description], k :name, :as setting} & {:as options}]
  (let [from-env? (set-via-env-var? setting)]
    {:key            k
     :value          (try
                       (m/mapply user-facing-value setting options)
                       (catch Throwable e
                         (log/error e "Error fetching value of Setting")))
     :is_env_setting from-env?
     :env_name       (env-var-name setting)
     :description    (str (description))
     :default        (if from-env?
                       (tru "Using value of env var {0}" (str \$ (env-var-name setting)))
                       default)}))

(defn current-user-readable-visibilities
  "Returns a set of setting visibilities that the current user has read access to."
  []
  (set (concat [:public]
               (when @api/*current-user*
                 [:authenticated])
               (when (has-advanced-setting-access?)
                 [:settings-manager])
               (when api/*is-superuser?*
                 [:admin]))))

(defn current-user-writable-visibilities
  "Returns a set of setting visibilities that the current user has write access to."
  []
  (set (concat []
               (when (has-advanced-setting-access?)
                 [:settings-manager :authenticated :public])
               (when api/*is-superuser?*
                 [:admin]))))

(defn- user-facing-settings-matching
  "Returns the user facing view of the registered settings satisfying the given predicate"
  [pred options]
  (into
   []
   (comp (filter pred)
         (map #(m/mapply user-facing-info % options)))
   (sort-by :name (vals @registered-settings))))

(defn writable-settings
  "Return a sequence of site-wide Settings maps in a format suitable for consumption by the frontend.
  (For security purposes, this doesn't return the value of a Setting if it was set via env var).

  `options` are passed to [[user-facing-value]].

  This is currently used by `GET /api/setting` (admin-only; powers the Admin Settings page) so all admin-visible
  Settings should be included. We *do not* want to return env var values, since admins are not allowed to modify them.

  For settings managers who are not admins, only the subset of settings with the :settings-manager visibility level
  are returned."
  [& {:as options}]
  ;; ignore Database-local values, but not User-local values
  (let [writable-visibilities (current-user-writable-visibilities)]
    (binding [*database-local-values* nil]
      (user-facing-settings-matching
       (fn [setting]
         (and (contains? writable-visibilities (:visibility setting))
              (not= (:database-local setting) :only)))
       options))))

(defn admin-writable-site-wide-settings
  "Returns a sequence of site-wide Settings maps, similar to [[writable-settings]]. However, this function
  excludes User-local Settings in addition to Database-local Settings. Settings that are optionally user-local will
  be included with their site-wide value, if a site-wide value is set.

  `options` are passed to [[user-facing-value]].

  This is used in [[metabase-enterprise.serialization.dump/dump-settings]] to serialize site-wide Settings."
  [& {:as options}]
  ;; ignore User-local and Database-local values
  (binding [*user-local-values* (delay (atom nil))
            *database-local-values* nil]
    (user-facing-settings-matching
     (fn [setting]
       (and (not= (:visibility setting) :internal)
            (allows-site-wide-values? setting)))
     options)))

(defn can-read-setting?
  "Returns true if a setting can be read according to the provided set of `allowed-visibilities`, and false otherwise.
   `allowed-visibilities` is a set of visibilities that the user can read."
  [setting allowed-visibilities]
  (let [setting (resolve-setting setting)]
    (boolean (and (not (:sensitive? setting))
                  (contains? allowed-visibilities (:visibility setting))))))

(defn user-readable-values-map
  "Returns Settings as a map of setting name -> site-wide value for a given set of [[Visibility]] keywords
  e.g. `#{:public :authenticated}`.

  Settings marked `:sensitive?` (e.g. passwords) are excluded.

  This is currently used by `GET /api/session/properties` and
  in [[metabase.server.routes.index/load-entrypoint-template]]. These are used as read-only sources of Settings for
  the frontend client. For that reason, these Settings *should* include values that come back from environment
  variables, *unless* they are marked `:sensitive?`."
  [visibilities]
  ;; ignore Database-local values, but not User-local values
  (binding [*database-local-values* nil]
    (into
     {}
     (comp (filter (fn [[_setting-name setting]]
                     (and (not (database-local-only? setting))
                          (can-read-setting? setting visibilities))))
           (map (fn [[setting-name]]
                  [setting-name (get setting-name)])))
     @registered-settings)))

(defn- redact-parse-ex
  "Substitute an opaque exception to ensure no sensitive information in the raw value is exposed"
  [ex]
  (ex-info (trs "Error of type {0} thrown while parsing a setting" (type ex))
           {:ex-type (type ex)}))

(defmulti may-contain-raw-token?
  "Indicate whether we must redact an exception to avoid leaking sensitive env vars"
  {:arglists '([ex setting])}
  (fn [_ex setting] (:type setting)))

;; fallback to redaction if we have not defined behaviour for a given format
(defmethod may-contain-raw-token? :default [_ _] false)

(defmethod may-contain-raw-token? :boolean [_ _] false)

;; Non EOF exceptions will mention the next character
(defmethod may-contain-raw-token? :csv [ex _] (not (instance? java.io.EOFException ex)))

;; Number parsing exceptions will quote the entire input
(defmethod may-contain-raw-token? :double [_ _] true)
(defmethod may-contain-raw-token? :integer [_ _] true)
(defmethod may-contain-raw-token? :positive-integer [_ _] true)

;; Date parsing may quote the entire input, or a particular sub-portion, e.g. a misspelled month name
(defmethod may-contain-raw-token? :timestamp [_ _] true)

;; Keyword parsing can never fail, but let's be paranoid
(defmethod may-contain-raw-token? :keyword [_ _] true)

(defmethod may-contain-raw-token? :json [ex _]
  (cond
    (instance? JsonEOFException ex) false
    (instance? JsonParseException ex) true
    :else (do (log/warn ex "Unexpected exception while parsing JSON")
              ;; err on the side of caution
              true)))

(defn- redact-sensitive-tokens [ex raw-value]
  (if (may-contain-raw-token? ex raw-value)
    (redact-parse-ex ex)
    ex))

(defn- validate-setting
  "Test whether the value configured for a given setting can be parsed as the expected type.
   Returns an map containing the exception if an issue is encountered, or nil if the value passes validation."
  [setting]
  (try
    (binding [*disable-init* true]
      (get-value-of-type (:type setting) setting))
    nil
    (catch clojure.lang.ExceptionInfo e
      (let [parse-error (or (ex-cause e) e)
            parse-error (redact-sensitive-tokens parse-error setting)
            env-var?    (set-via-env-var? setting)]
        (assoc (select-keys setting [:name :type])
               :parse-error parse-error
               :env-var? env-var?)))))

(defn validate-settings-formatting!
  "Check whether there are any issues with the format of application settings, e.g. an invalid JSON string.

   Note that this will only check settings whose [[defsetting]] forms have already been evaluated."
  []
  (doseq [invalid-setting (keep validate-setting (vals @registered-settings))]
    (if (:env-var? invalid-setting)
      (throw (ex-info (trs "Invalid {0} configuration for setting: {1}"
                           (u/upper-case-en (name (:type invalid-setting)))
                           (name (:name invalid-setting)))
                      (dissoc invalid-setting :parse-error)
                      (:parse-error invalid-setting)))
      (log/warn (:parse-error invalid-setting)
                (format "Unable to parse setting %s" (:name invalid-setting))))))

(defn migrate-encrypted-settings!
  "We have some settings that may currently be encrypted in the database that we'd like to disable encryption for.
  This function just goes through all of them, checks to see if a value exists in the database, and re-saves it if
  so. Toucan will handle decryption on the way out (if necessary) and the new value won't be encrypted.

  Note that we're completely working around the standard getters/setters here. This should be fine in this case
  because:
  - we're only doing anything when a value exists in the database, and
  - we're setting the value to the exact same value that already exists - just a decrypted version."
  []
  ;; If we don't have an encryption key set, don't bother trying to decrypt anything. If stuff is encrypted in the DB,
  ;; we can't do anything about it (since we can't decrypt it). If stuff isn't decrypted in the DB, we have nothing to
  ;; do.
  (when (encryption/default-encryption-enabled?)
    (let [settings (filter prohibits-encryption? (vals @registered-settings))]
      (t2/with-transaction [_conn]
        (doseq [{v :value k :key}
                (t2/select :setting {:for :update :where [:and
                                                          [:in :key (map setting-name settings)]
                                                          ;; these are *definitely* decrypted already, let's not bother looking
                                                          [:not [:in :value ["true" "false"]]]]})
                :let [decrypted-v (encryption/maybe-decrypt v)]
                :when (not= decrypted-v v)]
          (t2/update! :setting :key k {:value decrypted-v}))))))

(defn- maybe-encrypt [setting-model]
  ;; In tests, sometimes we need to insert/update settings that don't have definitions in the code and therefore can't
  ;; be resolved. Fall back to maybe-encrypting these.
  ;; Don't do any automatic handling of the "encryption-check" special setting used by mdb.encryption
  (if (= "encryption-check" (:key setting-model))
    setting-model
    (let [resolved (try (resolve-setting (:key setting-model))
                        (catch clojure.lang.ExceptionInfo e
                          (when (not (::unknown-setting-error (ex-data e)))
                            (throw e))))]
      (cond-> setting-model
        (or (nil? resolved)
            (not (prohibits-encryption? resolved)))
        (update :value encryption/maybe-encrypt)))))

(t2/define-before-update :model/Setting
  [setting]
  (maybe-encrypt setting))

(t2/define-before-insert :model/Setting
  [setting]
  (maybe-encrypt setting))

(t2/define-after-select :model/Setting
  [setting]
  ;; Don't do any automatic handling of the "encryption-check" special setting used by mdb.encryption
  (if (= "encryption-check" (:key setting))
    setting
    (update setting :value encryption/maybe-decrypt)))
