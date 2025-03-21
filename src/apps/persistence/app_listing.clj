(ns apps.persistence.app-listing
  (:use [apps.persistence.app-search :only [count-matching-app-ids find-matching-app-ids]]
        [apps.persistence.entities]
        [apps.util.conversions :only [date->timestamp]]
        [apps.util.db :only [add-date-limits-where-clause]]
        [kameleon.queries]
        [kameleon.util :only [query-spy]]
        [kameleon.util.search]
        [korma.core :exclude [update]])
  (:require [apps.constants :as c]
            [apps.util.assertions :refer [assert-app-version]]
            [clojure.string :as str]))

;; The `app_listing` view has an array aggregate column that we need to be able to extract. This tells
;; `clojure.java.jdbc` how to convert the array to a (possibly nested) vector.
(extend-protocol clojure.java.jdbc/IResultSetReadColumn
  org.postgresql.jdbc.PgArray
  (result-set-read-column [x _2 _3]
    (letfn [(object-array? [x] (instance? (Class/forName "[Ljava.lang.Object;") x))
            (expand [x] (if (object-array? x) (mapv expand x) x))]
      (mapv expand (.getArray x)))))

(defn get-app-listing
  "Retrieves all app listing fields from the database for the given App ID."
  [app-id]
  (first (select app_listing (where {:id app-id}))))

(defn get-app-extra-info
  [version-id]
  (when-let [htcondor (first
                       (select apps_htcondor_extra
                               (fields [:extra_requirements])
                               (where {:app_version_id version-id})))]
    {:htcondor htcondor}))

(defn- get-all-group-ids-subselect
  "Gets a subselect that fetches the app_categories and its subgroup IDs with
   the stored procedure app_category_hierarchy_ids."
  [app_group_id]
  (subselect
   (sqlfn :app_category_hierarchy_ids app_group_id)))

(defn- get-fav-group-id-subselect
  "Gets a subselect that fetches the ID for the Favorites group at the given
   index under the app_categories with the given ID."
  [workspace_root_group_id favorites_group_index]
  (subselect
   :app_category_group
   (fields :child_category_id)
   (where {:parent_category_id workspace_root_group_id
           :child_index favorites_group_index})))

(defn- get-is-fav-sqlfn
  "Gets a sqlfn that returns true if the App ID in its subselect is found in the
   Favorites group with the ID returned by get-fav-group-id-subselect."
  [workspace_root_group_id favorites_group_index app-id-column]
  (let [fav_group_id_subselect (get-fav-group-id-subselect
                                 workspace_root_group_id
                                 favorites_group_index)]
    (sqlfn* :exists
            (subselect
              :app_category_app
              (where {:app_category_app.app_id
                      app-id-column})
              (where {:app_category_app.app_category_id
                      fav_group_id_subselect})))))

(defn- get-app-listing-orphaned-condition
  []
  (raw "NOT EXISTS (SELECT * FROM app_category_app aca WHERE aca.app_id = app_listing.id)"))

(defn- add-app-id-where-clause
  [query {:keys [app-ids orphans public-app-ids]}]
  (if (seq app-ids)
    (if (and orphans (seq public-app-ids))
      (where query (or {:id [in app-ids]}
                       (and {:id [not-in (sequence public-app-ids)]}
                            (get-app-listing-orphaned-condition))))
      (where query {:id [in app-ids]}))
    query))

(defn- add-omitted-app-id-where-clause
  [query {:keys [omitted-app-ids]}]
  (if (seq omitted-app-ids)
    (where query {:id [not-in omitted-app-ids]})
    query))

(defn- add-tapis-pipeline-where-clause
  [query {tapis-enabled? :tapis-enabled :or {tapis-enabled? "false"}}]
  (let [tapis-enabled? (Boolean/parseBoolean tapis-enabled?)]
    (if-not tapis-enabled?
      (where query {:step_count :task_count})
      query)))

(defn- add-app-group-where-clause
  "Adds a where clause to an analysis listing query to restrict app results to
   an app group and all of its descendents."
  [base_listing_query app_group_id]
  (-> base_listing_query
      (join :app_category_app
            (= :app_category_app.app_id
               :app_listing.id))
      (where {:app_category_app.app_category_id
              [in (get-all-group-ids-subselect app_group_id)]})))

(defn- add-app-group-plus-public-apps-where-clause
  "Adds a where clause to an analysis listing query to restrict app results to
   an app group and all of its descendents plus the set of public apps
   integrated by a user."
  [query app-group-id username public-app-ids]
  (-> query
      (join :app_category_app
            (= :app_category_app.app_id
               :app_listing.id))
      (where (or {:app_category_app.app_category_id
                  [in (get-all-group-ids-subselect app-group-id)]}
                 {:integrator_username username
                  :id                  [in (sequence public-app-ids)]}))))

(defn- add-public-apps-by-user-where-clause
  "Adds a where clause to an analysis listing query to restrict app results to
   the set of public apps integrated by a user."
  [query username {:keys [public-app-ids]}]
  (where query
         {:integrator_username username
          :id                  [in public-app-ids]}))

(defn- app-type-subselect
  "Returns a subselect statement to search for apps with a selected app type."
  [app-type]
  (subselect [:app_steps :s]
             (join [:tasks :t] {:s.task_id :t.id})
             (join [:job_types :jt] {:t.job_type_id :jt.id})
             (where {:s.app_version_id :app_listing.version_id
                     :jt.name  app-type})))

(defn- add-app-type-where-clause
  "Adds a where clause to an app listing query to restrict results to a particular
   app type if an app type was specified in the HTTP query parameters."
  [query {:keys [app-type]}]
  (if app-type
    (where query (exists (app-type-subselect app-type)))
    query))

(defn- get-all-apps-count-base-query
  "Returns a base query for counting the total number of apps in the
   app_listing table."
  [query-opts]
  (-> (select* app_listing)
      (fields (raw "count(DISTINCT app_listing.id) AS total"))
      (add-app-id-where-clause query-opts)
      (add-app-type-where-clause query-opts)
      (add-omitted-app-id-where-clause query-opts)
      (add-tapis-pipeline-where-clause query-opts)))

(defn- get-app-count-base-query
  "Adds a where clause to the get-all-apps-count-base-query, filtering out `deleted` apps."
  [query-opts]
  (-> (get-all-apps-count-base-query query-opts)
      (where {:deleted false})
      (where {:integrator_name [not= c/internal-app-integrator]})))

(defn count-apps-in-group-for-user
  "Counts all of the apps in an app group and all of its descendents."
  ([app-group-id query-opts]
   ((comp :total first)
    (-> (get-app-count-base-query query-opts)
        (add-app-group-where-clause app-group-id)
        (select))))
  ([app-group-id username {:keys [public-app-ids] :as query-opts}]
   ((comp :total first)
    (-> (get-app-count-base-query query-opts)
        (add-app-group-plus-public-apps-where-clause app-group-id username public-app-ids)
        (select)))))

(defn- add-app-listing-base-query-fields
  "Add minimum required columns to apps listing query results"
  [listing-query]
  (fields listing-query
          :id
          :name
          :version
          :version_id
          :lower_case_name
          :description
          :integrator_name
          :integrator_email
          :integration_date
          :edited_date
          :wiki_url
          :average_rating
          :total_ratings
          :step_count
          :tool_count
          :external_app_count
          :task_count
          :deleted
          :disabled
          :overall_job_type))

(defn- add-app-listing-job-types-field
  "Adds the job_types field to apps listing query results"
  [listing-query]
  (fields listing-query :job_types))

(defn- add-app-listing-is-favorite-field
  "Add user's is_favorite column to apps listing query results"
  [listing-query workspace_root_group_id favorites_group_index]
  (let [is_fav_subselect (get-is-fav-sqlfn
                          workspace_root_group_id
                          favorites_group_index
                          :app_listing.id)]
    (fields listing-query [is_fav_subselect :is_favorite])))

(defn- add-app-versions-listing-is-favorite-field
  "Add user's is_favorite column to app versions listing query results"
  [listing-query workspace_root_group_id favorites_group_index]
  (let [is_fav_subselect (get-is-fav-sqlfn
                          workspace_root_group_id
                          favorites_group_index
                          :app_versions_listing.id)]
    (fields listing-query [is_fav_subselect :is_favorite])))

(defn- add-listing-query-ratings-fields
  "Add ratings columns to listing query results"
  [listing-query user-id app-id-column]
  (-> listing-query
      (fields [:ratings.rating :user_rating]
              :ratings.comment_id)
      (join ratings
            (and (= :ratings.app_id
                    app-id-column)
                 (= :ratings.user_id
                    user-id)))))

(defn- add-app-listing-ratings-fields
  "Add ratings columns to apps listing query results"
  [listing-query user-id]
  (add-listing-query-ratings-fields listing-query user-id :app_listing.id))

(defn- add-app-version-listing-ratings-fields
  "Add ratings columns to app versions listing query results"
  [listing-query user-id]
  (add-listing-query-ratings-fields listing-query user-id :app_versions_listing.id))

(defn- get-base-app-listing-base-query
  "Gets an app_listing select query, setting any query limits and sorting
   found in the query_opts, using the given workspace (as returned by
   fetch-workspace-by-user-id) to mark whether each app is a favorite and to
   include the user's rating in each app."
  [workspace favorites_group_index query_opts]
  (let [user_id (:user_id workspace)
        workspace_root_group_id (:root_category_id workspace)
        row_offset (or (:offset query_opts) 0)
        row_limit (or (:limit query_opts) -1)
        sort_field (keyword (or (:sort-field query_opts) (:sortfield query_opts)))
        sort_dir (keyword (or (:sort-dir query_opts) (:sortdir query_opts)))]
    (-> (select* app_listing)
        (modifier "DISTINCT")
        (add-app-listing-base-query-fields)
        (add-app-listing-job-types-field)
        (add-app-listing-is-favorite-field workspace_root_group_id favorites_group_index)
        (add-app-listing-ratings-fields user_id)
        (add-query-limit row_limit)
        (add-query-offset row_offset)
        (add-query-sorting sort_field sort_dir))))

(defn- get-all-apps-listing-base-query
  "Gets an app_listing select query, setting any query limits and sorting
   found in the query_opts, using the given workspace (as returned by
   fetch-workspace-by-user-id) to mark whether each app is a favorite and to
   include the user's rating in each app.."
  [workspace favorites_group_index query_opts]
  (-> (get-base-app-listing-base-query workspace favorites_group_index query_opts)
      (add-app-id-where-clause query_opts)
      (add-app-type-where-clause query_opts)
      (add-omitted-app-id-where-clause query_opts)
      (add-tapis-pipeline-where-clause query_opts)))

(defn- get-app-listing-base-query
  "Adds a where clause to the get-all-apps-listing-base-query, filtering out `deleted` apps."
  [workspace favorites_group_index query_opts]
  (-> (get-all-apps-listing-base-query workspace favorites_group_index query_opts)
      (where {:deleted false})
      (where {:integrator_name [not= c/internal-app-integrator]})))

(defn get-apps-in-group-for-user
  "Lists all of the apps in an app group and all of its descendents, using the
   given workspace (as returned by fetch-workspace-by-user-id) to mark
   whether each app is a favorite and to include the user's rating in each app."
  ([app-group-id workspace faves-index query-opts]
   (-> (get-app-listing-base-query workspace faves-index query-opts)
       (add-app-group-where-clause app-group-id)
       (select)))
  ([app-group-id workspace faves-index {:keys [public-app-ids] :as query-opts} username]
   (-> (get-app-listing-base-query workspace faves-index query-opts)
       (add-app-group-plus-public-apps-where-clause app-group-id username public-app-ids)
       (select))))

;; TODO: reinsert the subselect for the date a job for the app was most recently completed as soon as we
;; can do it efficiently
(defn- get-job-stats-fields
  "Adds query fields via subselects for an app's job_count_completed and job_last_completed timestamp."
  [query query-opts]
  (fields query
          [(subselect [:jobs :j]
                      (aggregate (count :id) :job_count_completed)
                      (where {:app_id (raw "app_listing.id::varchar")
                              :status "Completed"})
                      (add-date-limits-where-clause query-opts)
                      (where (raw "NOT EXISTS (SELECT parent_id FROM jobs jp WHERE jp.parent_id = j.id)")))
           :job_count_completed]))

;; TODO: reinsert the subselect for the date the app was most recently used as soon as we can do it
;; efficiently
(defn- get-admin-job-stats-fields
  "Adds query fields via subselects for an app's job_count, job_count_failed, and last_used timestamp."
  [query query-opts]
  (fields query
          [(subselect [:jobs :j]
                      (aggregate (count :id) :job_count)
                      (where {:app_id (raw "app_listing.id::varchar")})
                      (add-date-limits-where-clause query-opts)
                      (where (raw "NOT EXISTS (SELECT parent_id FROM jobs jp WHERE jp.parent_id = j.id)")))
           :job_count]
          [(subselect [:jobs :j]
                      (aggregate (count :id) :job_count_failed)
                      (where {:app_id (raw "app_listing.id::varchar")
                              :status "Failed"})
                      (add-date-limits-where-clause query-opts)
                      (where (raw "NOT EXISTS (SELECT parent_id FROM jobs jp WHERE jp.parent_id = j.id)")))
           :job_count_failed]))

(defn count-apps-for-user
  "Counts Apps in all public groups and groups under the given workspace_id.
   If search_term is not empty, results are limited to apps that contain search_term in their name,
   description, integrator_name, or tool name(s).

   Note: as far as I can tell the where clauses for the category are defunct. I'm ignoring them in
   this implementation of the function for now. The workspace ID parameter can be removed completely
   once the old implementation of this function has been removed."
  [search-term _ params]
  (count-matching-app-ids search-term params))

(defn count-apps-for-admin
  "Counts Apps in all public groups and groups under the given workspace_id.
   If search_term is not empty, results are limited to apps that contain search_term in their name,
   description, integrator_name, or tool name(s)."
  [search-term _ params]
  (count-matching-app-ids search-term (assoc params :admin true)))

(defn get-apps-for-user
  "Fetches Apps in all public groups and groups in `workspace`
   (as returned by fetch-workspace-by-user-id),
   marking whether each app is a favorite and including the user's rating in each app by the user_id
   found in workspace.
  If search_term is not empty, results are limited to apps that contain search_term in their name,
   description, integrator_name, or tool name(s)."
  [search_term workspace favorites_group_index query_opts]
  (let [app-ids (find-matching-app-ids search_term query_opts)]
    (-> (get-base-app-listing-base-query workspace favorites_group_index query_opts)
        (where {:id [in app-ids]})
        ((partial query-spy "get-apps-for-user::search_query:"))
        select)))

(defn get-apps-for-admin
  "Returns the same results as get-apps-for-user, but also includes deleted apps and job_count,
   job_count_failed, job_count_completed, last_used timestamp, and job_last_completed timestamp fields
   for each result."
  [search_term workspace favorites_group_index query_opts]
  (let [app-ids (find-matching-app-ids search_term (assoc query_opts :admin true))]
    (-> (get-base-app-listing-base-query workspace favorites_group_index query_opts)
        (where {:id [in app-ids]})
        (get-job-stats-fields query_opts)
        (get-admin-job-stats-fields query_opts)
        ((partial query-spy "get-apps-for-admin::search_query:"))
        select)))

(defn get-single-app
  "Fetches a listing for a single app."
  [{workspace-id :id :as workspace} favorites-group-index app-id]
  (as-> (get-app-listing-base-query workspace favorites-group-index {:app-ids [app-id]}) q
    (query-spy "get-single-app::search-query:" q)
    (select q)))

(defn get-app-version-details
  "Retrieves the details for a single app."
  [user-id workspace-root-group-id favorites-group-index app-id version-id]
  (let [rating-avg-subselect (subselect
                               ratings
                               (fields (raw "CAST(COALESCE(AVG(rating), 0.0) AS DOUBLE PRECISION) AS average_rating"))
                               (where {:ratings.app_id
                                       :app_versions_listing.id}))
        rating-total-subselect (subselect
                                 ratings
                                 (aggregate (count :ratings.rating) :total_ratings)
                                 (where {:ratings.app_id
                                         :app_versions_listing.id}))]
    (assert-app-version
      [app-id version-id]
      (-> (select* :app_versions_listing)
          (fields :*
                  [rating-avg-subselect :average_rating]
                  [rating-total-subselect :total_ratings])
          (add-app-versions-listing-is-favorite-field
            workspace-root-group-id
            favorites-group-index)
          (add-app-version-listing-ratings-fields user-id)
          (where {:id         app-id
                  :version_id version-id})
          select
          first))))

(defn- add-deleted-and-orphaned-where-clause
  [query public-app-ids]
  (where query
         (or {:deleted true
              :id      [in (sequence public-app-ids)]}
             (and (get-app-listing-orphaned-condition)
                  {:id [not-in (sequence public-app-ids)]}))))

(defn count-deleted-and-orphaned-apps
  "Counts the number of deleted, public apps, plus apps that are not listed under any category."
  [{:keys [public-app-ids] :as params}]
  ((comp :count first)
   (-> (select* app_listing)
       (aggregate (count :*) :count)
       (add-deleted-and-orphaned-where-clause public-app-ids)
       (add-app-type-where-clause params)
       (select))))

(defn list-deleted-and-orphaned-apps
  "Fetches a list of deleted, public apps, plus apps that are not listed under any category."
  [{:keys [limit offset sort-field sort-dir public-app-ids] :as params}]
  (-> (select* app_listing)
      (add-app-listing-base-query-fields)
      (add-deleted-and-orphaned-where-clause public-app-ids)
      (add-app-type-where-clause params)
      (add-query-limit limit)
      (add-query-offset offset)
      (add-query-sorting (when sort-field (keyword sort-field))
                         (when sort-dir (keyword (str/upper-case sort-dir))))
      (select)))

(defn count-public-apps-by-user
  "Counts the number of apps integrated by a user."
  [username params]
  ((comp :count first)
   (-> (select* app_listing)
       (aggregate (count :*) :count)
       (where {:deleted false})
       (add-public-apps-by-user-where-clause username params)
       (add-app-id-where-clause params)
       (add-app-type-where-clause params)
       (add-omitted-app-id-where-clause params)
       (add-tapis-pipeline-where-clause params)
       (select))))

(defn list-public-apps-by-user
  "Lists the apps integrated by the user with the given"
  [workspace favorites-group-index username query-opts]
  (-> (get-app-listing-base-query workspace favorites-group-index query-opts)
      (add-public-apps-by-user-where-clause username query-opts)
      (select)))

(defn list-shared-apps
  "Lists apps that have been shared with a user. For the time being, this works by listing all non-public
   apps that the user did not integrate."
  [workspace favorites-group-index params]
  (let [params (assoc params :omitted-app-ids (:public-app-ids params))]
    (-> (get-app-listing-base-query workspace favorites-group-index params)
        (where {:integrator_id [not= (:user_id workspace)]})
        select)))

(defn count-shared-apps
  "Lists apps that have been shared with a user. For the time being, this works by counting all non-public
   apps that the user did not integrate."
  [workspace favorites-group-index params]
  (let [params (assoc params :omitted-app-ids (:public-app-ids params))]
    (-> (select* app_listing)
        (aggregate (count :*) :count)
        (where {:deleted false})
        (where {:integrator_id [not= (:user_id workspace)]})
        (add-app-id-where-clause params)
        (add-app-type-where-clause params)
        (add-omitted-app-id-where-clause params)
        select first :count)))

(defn list-apps-by-id
  "Lists all apps with an ID in the the given app-ids list."
  [workspace favorites-group-index app-ids params]
  (get-apps-for-user nil workspace favorites-group-index (assoc params :app-ids app-ids)))

(defn admin-list-apps-by-id
  "Lists all apps with an ID in the the given app-ids list,
   including job_count, job_count_failed, job_count_completed, last_used timestamp, and
   job_last_completed timestamp fields for each result."
  [workspace favorites-group-index app-ids params]
  (let [app-ids (find-matching-app-ids nil (assoc params :app-ids app-ids))]
    (-> (get-base-app-listing-base-query workspace favorites-group-index params)
        (get-job-stats-fields params)
        (get-admin-job-stats-fields params)
        (where {:id [in app-ids]})
        ((partial query-spy "admin-list-apps-by-id::search_query:"))
        select)))

(defn get-all-app-ids
  "Lists all app IDs in the database."
  []
  (->> (select :apps (fields :id))
       (map :id)
       set))
