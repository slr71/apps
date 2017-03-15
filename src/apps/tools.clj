(ns apps.tools
  (:use [apps.containers :only [add-tool-container set-tool-container tool-container-info]]
        [apps.persistence.entities :only [tools tool_types]]
        [apps.util.assertions :only [assert-not-nil]]
        [apps.util.conversions :only [remove-nil-vals]]
        [apps.validation :only [verify-tool-name-location validate-tool-not-used]]
        [clojure.string :only [upper-case]]
        [kameleon.queries]
        [kameleon.util.search]
        [korma.core :exclude [update]]
        [korma.db :only [transaction]]
        [slingshot.slingshot :only [try+]])
  (:require [apps.clients.permissions :as permissions]
            [apps.persistence.app-metadata :as persistence]
            [clojure.tools.logging :as log]))

(defn- add-search-where-clauses
  "Adds where clauses to a base tool search query to restrict results to tools that contain the
   given search term in their name or description."
  [base-query search-term]
  (if search-term
    (let [search-term (format-query-wildcards search-term)
          search-term (str "%" search-term "%")]
      (where base-query
             (or
              {(sqlfn lower :tools.name) [like (sqlfn lower search-term)]}
              {(sqlfn lower :tools.description) [like (sqlfn lower search-term)]})))
    base-query))

(defn- add-hidden-tool-types-clause
  "Adds the clause used to filter out hidden tool types if hidden tool types are not supposed to
   be included in the result set."
  [base-query include-hidden]
  (if-not include-hidden
    (where base-query {:tool_types.hidden false})
    base-query))

(defn tool-listing-base-query
  "Obtains a listing query for tools, with optional search and paging params."
  ([]
   (-> (select* tools)
       (fields [:tools.id :id]
               [:tools.name :name]
               [:tools.description :description]
               [:tools.location :location]
               [:tool_types.name :type]
               [:tools.version :version]
               [:tools.attribution :attribution]
               [:tools.restricted :restricted]
               [:tools.time_limit_seconds :time_limit_seconds])
       (join tool_types)))
  ([{search-term :search :keys [sort-field sort-dir limit offset include-hidden]
                         :or {include-hidden false}}]
   (let [sort-field (when sort-field (keyword (str "tools." sort-field)))
         sort-dir (when sort-dir (keyword (upper-case sort-dir)))]
     (-> (tool-listing-base-query)
         (add-search-where-clauses search-term)
         (add-query-sorting sort-field sort-dir)
         (add-query-limit limit)
         (add-query-offset offset)
         (add-hidden-tool-types-clause include-hidden)))))

(defn format-tool-listing
  [perms public-tool-ids {:keys [id] :as tool}]
  (-> tool
      (assoc :is_public  (contains? public-tool-ids id)
             :permission (or (perms id) ""))
      remove-nil-vals))

(defn search-tools
  "Obtains a listing of tools for the tool search service."
  [{:keys [user] :as params}]
  (let [perms           (permissions/load-tool-permissions user)
        public-tool-ids (permissions/get-public-tool-ids)]
    {:tools
     (map (partial format-tool-listing perms public-tool-ids)
       (select (tool-listing-base-query params)))}))

(defn get-tool
  "Obtains a tool by ID."
  [user tool-id]
  (let [tool           (->> (first (select (tool-listing-base-query) (where {:tools.id tool-id})))
                            (assert-not-nil [:tool-id tool-id])
                            (format-tool-listing (permissions/load-tool-permissions user)
                                                 (permissions/get-public-tool-ids)))
        container      (tool-container-info tool-id)
        implementation (persistence/get-tool-implementation-details tool-id)]
    (assoc tool
      :container container
      :implementation implementation)))

(defn get-tools-by-id
  "Obtains a listing of tools for the given list of IDs."
  [tool-ids]
  (map remove-nil-vals
    (select (tool-listing-base-query) (where {:tools.id [in tool-ids]}))))

(defn- add-new-tool
  [{:keys [container] :as tool}]
  (verify-tool-name-location tool)
  (let [tool-id (persistence/add-tool tool)]
    (when container
      (add-tool-container tool-id container))
    tool-id))

(defn add-tools
  "Adds a list of tools to the database, returning a list of IDs of the tools added."
  [{:keys [tools]}]
  (transaction
    (let [tool-ids (doall (map add-new-tool tools))]
      (dorun (map permissions/register-public-tool tool-ids))
      {:tool_ids tool-ids})))

(defn update-tool
  [user overwrite-public {:keys [id container] :as tool}]
  (persistence/update-tool tool)
  (when container
    (set-tool-container id overwrite-public container))
  (get-tool user id))

(defn delete-tool
  [user tool-id]
  (let [{:keys [name location version]} (get-tool user tool-id)]
    (validate-tool-not-used tool-id)
    (log/warn user "deleting tool" tool-id name version "@" location))
  (delete tools (where {:id tool-id}))
  (try+
    (permissions/delete-tool-resource tool-id)
    (catch [:status 404] _
      (log/warn "tool resource" tool-id "not found by permissions service")))
  nil)
