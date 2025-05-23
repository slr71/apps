(ns apps.service.apps.de.pipeline-edit
  (:require
   [apps.clients.permissions :as permissions]
   [apps.persistence.app-metadata :as persistence]
   [apps.persistence.entities :refer [app_steps input_mapping]]
   [apps.service.apps.de.constants :as c]
   [apps.service.apps.de.docs :as docs]
   [apps.service.apps.de.edit :refer [add-app-to-user-dev-category app-copy-name]]
   [apps.service.apps.de.listings :as listings]
   [apps.service.apps.de.validation
    :refer [app-tasks-and-tools-publishable?
            validate-app-name
            verify-app-editable
            verify-app-permission]]
   [apps.util.conversions :refer [remove-nil-vals]]
   [apps.util.db :refer [transaction]]
   [apps.validation :refer [validate-external-app-step validate-pipeline]]
   [clojure-commons.exception-util :as ex-util]
   [kameleon.uuids :refer [uuidify]]
   [korma.core :refer [fields group join order select where with]]
   [medley.core :refer [find-first]]
   [slingshot.slingshot :refer [try+]]))

(defn- add-app-type
  [step]
  (assoc step :app_type (if (:external_app_id step) "External" "DE")))

(defn- get-steps
  "Fetches the steps for the given app version ID,
   including their task ID and source/target mapping IDs and step names."
  [app-version-id]
  (select app_steps
          (with input_mapping
                (fields :source_step
                        :target_step)
                (group :source_step
                       :target_step))
          (join [:tasks :t]
                {:task_id :t.id})
          (join [:job_types :jt]
                {:t.job_type_id :jt.id})
          (join [:app_versions :versions]
                {:app_version_id :versions.id})
          (fields :app_steps.id
                  :step
                  :t.name
                  :t.description
                  :jt.system_id
                  :task_id
                  :t.external_app_id)
          (where {:versions.id app-version-id})
          (order :step :ASC)))

(defn- format-step
  "Formats step fields for the client."
  [step]
  (-> step
      add-app-type
      (dissoc :id :step :input_mapping)
      remove-nil-vals))

(defn- get-input-output-mappings
  "Fetches the output->input mapping UUIDs for the given source and target IDs."
  [source target]
  (select input_mapping
          (join [:input_output_mapping :map]
                {:id :map.mapping_id})
          (fields :map.input
                  :map.external_input
                  :map.output
                  :map.external_output)
          (where {:source_step source
                  :target_step target})))

(defn- find-first-key
  [pred m ks]
  (find-first pred ((apply juxt ks) m)))

(defn- format-io-mapping
  [m]
  (let [first-defined (partial find-first-key (complement nil?))]
    (vector (str (first-defined m [:input :external_input]))
            (str (first-defined m [:output :external_output])))))

(defn- format-mapping
  "Formats mapping fields for the client."
  [step-indexes {source-step-id :source_step target-step-id :target_step}]
  (let [input-output-mappings (get-input-output-mappings source-step-id target-step-id)]
    {:source_step (step-indexes source-step-id)
     :target_step (step-indexes target-step-id)
     :map         (into {} (map format-io-mapping input-output-mappings))}))

(defn- get-formatted-mapping
  "Formats a step's list of mapping IDs and step names to fields for the client."
  [step-indexes step]
  (map (partial format-mapping step-indexes) (:input_mapping step)))

(defn- get-mappings
  "Gets a list of formatted mappings for the given steps."
  [steps]
  (let [step-indexes (into {} (map #(vector (:id %) (:step %)) steps))]
    (mapcat (partial get-formatted-mapping step-indexes) steps)))

(defn- format-workflow
  "Prepares a JSON response for editing a Workflow in the client."
  [user app]
  (let [steps (get-steps (:version_id app))
        mappings (get-mappings steps)
        task-ids (set (map :task_id steps))
        tasks (listings/get-tasks-with-file-params user task-ids)
        steps (map format-step steps)]
    (-> app
        (select-keys [:id :name :description :version :version_id])
        (assoc :tasks tasks
               :steps steps
               :mappings mappings
               :versions (persistence/list-app-versions (:id app))
               :system_id c/system-id))))

(defn- convert-app-to-copy
  "Adds copies of the steps and mappings fields to the app, and formats
   appropriate app fields to prepare it for saving as a copy."
  [app]
  (let [steps (get-steps (:version_id app))
        mappings (get-mappings steps)
        steps (map format-step steps)]
    (-> app
        (select-keys [:description])
        (assoc :name (app-copy-name (:name app)))
        (assoc :steps steps)
        (assoc :mappings mappings))))

(defn edit-pipeline
  "This service prepares a JSON response for editing a Pipeline in the client."
  ([user app-id]
   (edit-pipeline user app-id (persistence/get-app-latest-version app-id)))
  ([user app-id version-id]
   (let [app (persistence/get-app-version app-id version-id)]
     (verify-app-editable user app)
     (format-workflow user app))))

(defn- add-app-mapping
  [app-version-id steps {:keys [source_step target_step map]}]
  (persistence/add-mapping {:app_version_id app-version-id
                            :source_step    (nth steps source_step)
                            :target_step    (nth steps target_step)
                            :map            map}))

(defn- generate-external-app-task
  [step]
  {:name            (:name step)
   :description     (:description step)
   :label           (:name step)
   :system_id       (:system_id step)
   :external_app_id (:external_app_id step)})

(defn- add-external-app-task
  [step-number step]
  (validate-external-app-step step-number step)
  (-> step generate-external-app-task persistence/add-task))

(defn- add-pipeline-step
  [app-version-id step-number step]
  (if (nil? (:task_id step))
    (let [task-id (:id (add-external-app-task step-number step))]
      (persistence/add-step app-version-id step-number (assoc step :task_id task-id)))
    (persistence/add-step app-version-id step-number step)))

(defn- add-pipeline-steps
  "Adds steps to a pipeline. The app type isn't stored in the database, but needs to be kept in
   the list of steps so that external steps can be distinguished from DE steps. The two types of
   steps normally can't be distinguished without examining the associated task."
  [app-version-id steps]
  (doall
   (map-indexed (fn [step-number step]
                  (assoc (add-pipeline-step app-version-id step-number step)
                         :app_type (:app_type step)))
                steps)))

(defn- add-app-steps-mappings
  [{app-version-id :version_id steps :steps mappings :mappings}]
  (let [steps (add-pipeline-steps app-version-id steps)]
    (dorun (map (partial add-app-mapping app-version-id steps) mappings))))

(defn- add-pipeline-version*
  [user {app-id :id :as app}]
  (let [app-version-id (-> app (dissoc :id) (assoc :app_id app-id) (persistence/add-app-version user) :id)]
    (add-app-steps-mappings (assoc app :id app-id :version_id app-version-id))
    app-version-id))

(defn- add-pipeline-app
  [user app]
  (validate-pipeline app)
  (transaction
   (let [app-id (:id (persistence/add-app app))]
     (add-pipeline-version* user (assoc app :id app-id))
     (add-app-to-user-dev-category user app-id)
     (permissions/register-private-app (:shortUsername user) app-id)
     app-id)))

(defn- update-pipeline-app
  [user {app-id :id app-version-id :version_id :as app}]
  (validate-pipeline app)
  (transaction
    (verify-app-editable user (persistence/get-app app-id))
    (persistence/update-app app)
    (persistence/update-app-version app)
    (persistence/remove-app-steps app-version-id)
    (add-app-steps-mappings app)
    app-id))

(defn- prepare-pipeline-step
  "Prepares a single step in a pipeline for submission to apps. DE steps can be left as-is.
   External steps need to have the task_id field moved to the external_app_id field."
  [{app-type :app_type :as step}]
  (if (= app-type "External")
    (assoc (dissoc step :task_id) :external_app_id (:task_id step))
    (update-in step [:task_id] uuidify)))

(defn- preprocess-pipeline
  [pipeline]
  (update-in pipeline [:steps] (partial map prepare-pipeline-step)))

(defn add-pipeline
  [user workflow]
  (->> (preprocess-pipeline workflow)
       (add-pipeline-app user)
       (edit-pipeline user)))

(defn add-pipeline-version
  "Adds a single-step app version to an existing app, if the user has write permission on that app."
  [user {app-id :id app-name :name :keys [steps] :as app} admin?]
  (verify-app-permission user (persistence/get-app app-id) "write" admin?)
  (validate-pipeline app)
  (transaction
    (validate-app-name app-name app-id)
    (let [public-app-ids        (permissions/get-public-app-ids)
          app-public            (contains? public-app-ids app-id)
          task-ids              (set (map (comp uuidify :task_id) steps))
          [publishable? reason] (app-tasks-and-tools-publishable? (:shortUsername user)
                                                                  admin?
                                                                  public-app-ids
                                                                  task-ids
                                                                  (persistence/get-task-tools task-ids))]
      (when (and app-public (not publishable?))
        (ex-util/bad-request reason)))
    (persistence/update-app app)
    (let [documentation  (try+
                           (docs/get-app-docs user app-id)
                           (catch [:type :clojure-commons.exception/not-found] _ nil))
          new-version-id (->> app-id
                              persistence/get-app-max-version-order
                              inc
                              (assoc app :version_order)
                              (add-pipeline-version* user))]
      (when documentation
        (docs/add-app-version-docs user
                                   app-id
                                   new-version-id
                                   (select-keys documentation [:documentation])))
      (edit-pipeline user app-id new-version-id))))

(defn update-pipeline
  [user {app-id :id version-id :version_id :as workflow}]
  (let [version-id (or version-id (persistence/get-app-latest-version app-id))]
    (->> (assoc workflow :version_id version-id)
         preprocess-pipeline
         (update-pipeline-app user))
    (edit-pipeline user app-id version-id)))

(defn- copy-pipeline*
  [user app]
  (->> app
       (convert-app-to-copy)
       (add-pipeline-app user)
       (edit-pipeline user)))

(defn copy-pipeline
  "This service makes a copy of a pipeline's latest version for the current user
   and returns the JSON for editing the copy in the client."
  [user app-id]
  (copy-pipeline* user (persistence/get-app app-id)))

(defn copy-pipeline-version
  "This service makes a copy of a pipeline version for the current user
   and returns the JSON for editing the copy in the client."
  [user app-id version-id]
  (copy-pipeline* user (persistence/get-app-version app-id version-id)))
