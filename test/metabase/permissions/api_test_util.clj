(ns metabase.permissions.api-test-util
  (:require
   [malli.core :as mc]
   [malli.transform :as mtx]
   [metabase.permissions.api.permission-graph :as api.permission-graph]
   [metabase.util.malli.registry :as mr]))

(def ^:private graph-output-schema
  [:map-of @#'api.permission-graph/GroupId @#'api.permission-graph/StrictDbGraph])

(defn- decode-and-validate [schema value]
  (mr/validate schema (mc/decode schema value (mtx/string-transformer))))

(defn validate-graph-api-groups
  "Handles string->keyword transformations in DataPerms"
  [graph]
  (decode-and-validate graph-output-schema graph))
