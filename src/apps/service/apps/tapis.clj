(ns apps.service.apps.tapis
  (:require
   [apps.persistence.jobs :as jp]
   [apps.service.apps.job-listings :as job-listings]
   [apps.service.apps.permissions :as app-permissions]
   [apps.service.apps.tapis.jobs :as tapis-jobs]
   [apps.service.apps.tapis.listings :as listings]
   [apps.service.apps.tapis.pipelines :as pipelines]
   [apps.service.apps.tapis.sharing :as sharing]
   [apps.service.apps.util :as apps-util]
   [apps.util.service :as service]
   [clojure.string :as string]))

(defn- reject-app-versions-request
  []
  (service/bad-request "Cannot list or modify HPC app versions with this service"))

(defn- reject-app-documentation-edit-request
  []
  (service/bad-request "Cannot edit documentation for HPC apps with this service"))

(def app-integration-rejection "Cannot add or modify HPC apps with this service")

(def integration-data-rejection "Cannot list or modify integration data for HPC apps with this service")

(def app-favorite-rejection "Cannot mark an HPC app as a favorite with this service.")

(def app-rating-rejection "Cannot rate an HPC app with this service.")

(def app-categorization-rejection "HPC apps cannot be placed in DE app categories.")

(def app-publishable-error "HPC apps may not be published via the DE at this time.")

(defn- reject-app-integration-request
  []
  (service/bad-request app-integration-rejection))

(defn- reject-app-favorite-request
  []
  (service/bad-request app-favorite-rejection))

(defn- reject-app-rating-request
  []
  (service/bad-request app-rating-rejection))

(defn- reject-categorization-request
  []
  (service/bad-request app-categorization-rejection))

(def ^:private supported-system-ids #{jp/tapis-client-name})
(def ^:private validate-system-id (partial apps-util/validate-system-id supported-system-ids))
(def ^:private validate-system-ids (partial apps-util/validate-system-ids supported-system-ids))

(defn- empty-doc-map [app-id]
  {:app_id        app-id
   :documentation ""
   :references    []})

(deftype TapisApps [tapis user-has-access-token? user]
  apps.protocols.Apps

  (getUser [_]
    user)

  (getClientName [_]
    jp/tapis-client-name)

  (getJobTypes [_]
    [jp/tapis-job-type])

  (listSystemIds [_]
    (vec supported-system-ids))

  (supportsSystemId [_ system-id]
    (supported-system-ids system-id))

  (listAppCategories [_ {:keys [hpc]}]
    (when-not (and hpc (.equalsIgnoreCase hpc "false"))
      [(.hpcAppGroup tapis)]))

  (listAppsInCategory [self system-id category-id params]
    (validate-system-id system-id)
    (if (apps-util/app-type-qualifies? self params)
      (listings/list-apps tapis category-id params)
      (.emptyAppListing tapis)))

  (listAppsUnderHierarchy [self root-iri _attr params]
    (when (user-has-access-token?)
      (if (apps-util/app-type-qualifies? self params)
        (listings/list-apps-with-ontology tapis root-iri params false)
        (.emptyAppListing tapis))))

  (adminListAppsUnderHierarchy [self _ontology-version root-iri _attr params]
    (when (user-has-access-token?)
      (if (apps-util/app-type-qualifies? self params)
        (listings/list-apps-with-ontology tapis root-iri params true)
        (.emptyAppListing tapis))))

  ;; Since Tapis doesn't list apps under ontology hierarchies, we'll use the ontology listing with communities for now.
  (listAppsInCommunity [self community-id params]
    (when (user-has-access-token?)
      (if (apps-util/app-type-qualifies? self params)
        (listings/list-apps-with-ontology tapis community-id params false)
        (.emptyAppListing tapis))))

  (adminListAppsInCommunity [self community-id params]
    (when (user-has-access-token?)
      (if (apps-util/app-type-qualifies? self params)
        (listings/list-apps-with-ontology tapis community-id params true)
        (.emptyAppListing tapis))))

  (searchApps [self search-term params]
    (when (user-has-access-token?)
      (if (apps-util/app-type-qualifies? self params)
        (listings/search-apps tapis search-term params false)
        (.emptyAppListing tapis))))

  (listSingleApp [_ system-id app-id]
    (validate-system-id system-id)
    (listings/list-app tapis app-id))

  (adminSearchApps [self search-term params]
    (when (user-has-access-token?)
      (if (apps-util/app-type-qualifies? self params)
        (listings/search-apps tapis search-term params true)
        (.emptyAppListing tapis))))

  (canEditApps [_]
    false)

  (addApp [_ system-id _]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (addAppVersion [_ system-id _ _]
    (validate-system-id system-id)
    (reject-app-versions-request))

  (previewCommandLine [_ system-id _]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (validateDeletionRequest [_ deletion-request]
    (let [qualified-app-ids (:app_ids deletion-request)]
      (validate-system-ids (set (map :system_id qualified-app-ids)))
      (reject-app-integration-request)))

  (deleteApps [this deletion-request]
    (.validateDeletionRequest this deletion-request))

  (getAppJobView [this system-id app-id]
    (.getAppJobView this system-id app-id false))

  (getAppJobView [_ system-id app-id _]
    (validate-system-id system-id)
    (.getApp tapis app-id))

  (getAppVersionJobView [_ system-id _ _]
    (validate-system-id system-id)
    (reject-app-versions-request))

  (deleteApp [_ system-id _app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (deleteAppVersion [_ system-id _ _]
    (validate-system-id system-id)
    (reject-app-versions-request))

  (relabelApp [_ system-id _app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (updateApp [_ system-id _app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (setAppVersionsOrder [_ system-id _app-id _versions]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (copyApp [_ system-id _app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (copyAppVersion [_ system-id _ _]
    (validate-system-id system-id)
    (reject-app-versions-request))

  (getAppDetails [_ system-id app-id admin?]
    (validate-system-id system-id)
    (listings/get-app-details tapis app-id admin?))

  (getAppVersionDetails [_ system-id _ _ _]
    (validate-system-id system-id)
    (reject-app-versions-request))

  (removeAppFavorite [_ system-id _app-id]
    (validate-system-id system-id)
    (reject-app-favorite-request))

  (addAppFavorite [_ system-id _app-id]
    (validate-system-id system-id)
    (reject-app-favorite-request))

  (isAppPublishable [_ system-id _app-id _]
    (validate-system-id system-id)
    [false app-publishable-error])

  (listToolsInUntrustedRegistries [_ system-id _app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (usesToolsInUntrustedRegistries [_ system-id  _app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (createPublicationRequest [_ system-id _app-id _]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (makeAppPublic [_ system-id _app]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (deleteAppRating [_ system-id _app-id]
    (validate-system-id system-id)
    (reject-app-rating-request))

  (rateApp [_ system-id _app-id _rating]
    (validate-system-id system-id)
    (reject-app-rating-request))

  (getAppTaskListing [_ system-id app-id]
    (validate-system-id system-id)
    (.listAppTasks tapis app-id))

  (getAppVersionTaskListing [_ system-id _ _]
    (validate-system-id system-id)
    (reject-app-versions-request))

  (getAppToolListing [_ system-id app-id]
    (validate-system-id system-id)
    (.getAppToolListing tapis app-id))

  (getAppVersionToolListing [_ system-id _ _]
    (validate-system-id system-id)
    (reject-app-versions-request))

  (getAppUi [_ system-id _app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (getAppVersionUi [_ system-id _ _]
    (validate-system-id system-id)
    (reject-app-versions-request))

  (getAppInputIds [_ system-id app-id _]
    (validate-system-id system-id)
    (.getAppInputIds tapis app-id))

  (formatPipelineTasks [_ pipeline]
    (pipelines/format-pipeline-tasks tapis pipeline))

  (listJobs [self params]
    (job-listings/list-jobs self user params))

  (listJobStats [self params]
    (job-listings/list-job-stats self user params))

  (adminListJobsWithExternalIds [_ external-ids]
    (job-listings/admin-list-jobs-with-external-ids external-ids))

  (loadAppTables [_ qualified-app-ids]
    (validate-system-ids (set (map :system_id qualified-app-ids)))
    (listings/load-app-tables tapis (set (map :app_id qualified-app-ids))))

  (submitJob [_this submission]
    (validate-system-id (:system_id submission))
    (tapis-jobs/submit tapis user submission))

  (submitJobStep [_ job-id submission]
    (tapis-jobs/submit-step tapis job-id submission))

  (translateJobStatus [self job-type status]
    (when (apps-util/supports-job-type? self job-type)
      (or (.translateJobStatus tapis status) status)))

  (updateJobStatus [self job-step job status end-date]
    (when (apps-util/supports-job-type? self (:job_type job-step))
      (tapis-jobs/update-job-status tapis job-step job status end-date)))

  (getDefaultOutputName [_ io-map source-step]
    (tapis-jobs/get-default-output-name tapis io-map source-step))

  (getJobStepStatus [_ job-step]
    (tapis-jobs/get-job-step-status tapis job-step))

  (getJobStepHistory [_ {:keys [external_id]}]
    (.getJobHistory tapis external_id))

  (getParamDefinitions [_ system-id app-id version-id]
    (validate-system-id system-id)
    (listings/get-param-definitions tapis app-id version-id))

  (stopJobStep [self {:keys [job_type external_id]}]
    (when (and (apps-util/supports-job-type? self job_type)
               (not (string/blank? external_id)))
      (.stopJob tapis external_id)))

  (categorizeApps [_ {:keys [categories]}]
    (validate-system-ids (set (map :system_id categories)))
    (validate-system-ids (set (mapcat (fn [m] (map :system_id (:category_ids m))) categories)))
    (reject-categorization-request))

  (listAppPublicationRequests [_ _]
    [])

  (permanentlyDeleteApps [this req]
    (.validateDeletionRequest this req))

  (adminDeleteApp [_ system-id _app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (adminUpdateApp [_ system-id _app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (adminBlessApp [_ system-id _app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (adminRemoveAppBlessing [_ system-id _app-id]
    (validate-system-id system-id)
    (reject-app-integration-request))

  (getAdminAppCategories [_ _]
    [])

  (searchAdminAppCategories [_ _]
    [])

  (adminAddCategory [_ system-id _]
    (validate-system-id system-id)
    (reject-categorization-request))

  (adminDeleteCategory [_ system-id _]
    (validate-system-id system-id)
    (reject-categorization-request))

  (adminUpdateCategory [_ system-id _]
    (validate-system-id system-id)
    (reject-categorization-request))

  (getAppDocs [_ system-id app-id]
    (validate-system-id system-id)
    (empty-doc-map app-id))

  (getAppVersionDocs [_ system-id _ _]
    (validate-system-id system-id)
    (reject-app-versions-request))

  (getAppIntegrationData [_ system-id _app-id]
    (validate-system-id system-id)
    (service/bad-request integration-data-rejection))

  (getAppVersionIntegrationData [_ system-id _ _]
    (validate-system-id system-id)
    (reject-app-versions-request))

  (getToolIntegrationData [_ system-id _tool-id]
    (validate-system-id system-id)
    (service/bad-request integration-data-rejection))

  (updateAppIntegrationData [_ system-id _app-id _integration-data-id]
    (validate-system-id system-id)
    (service/bad-request integration-data-rejection))

  (updateAppVersionIntegrationData [_ system-id _app-id _version-id _integration-data-id]
    (validate-system-id system-id)
    (reject-app-versions-request))

  (updateToolIntegrationData [_ system-id _tool-id _integration-data-id]
    (validate-system-id system-id)
    (service/bad-request integration-data-rejection))

  (ownerEditAppDocs [_ system-id _app-id _]
    (validate-system-id system-id)
    (reject-app-documentation-edit-request))

  (ownerEditAppVersionDocs [_ system-id _ _ _]
    (validate-system-id system-id)
    (reject-app-versions-request))

  (ownerAddAppDocs [_ system-id  _app-id _]
    (validate-system-id system-id)
    (reject-app-documentation-edit-request))

  (ownerAddAppVersionDocs [_ system-id _ _ _]
    (validate-system-id system-id)
    (reject-app-versions-request))

  (adminEditAppDocs [_ system-id _app-id _]
    (validate-system-id system-id)
    (reject-app-documentation-edit-request))

  (adminEditAppVersionDocs [_ system-id _ _ _]
    (validate-system-id system-id)
    (reject-app-versions-request))

  (adminAddAppDocs [_ system-id _app-id _]
    (validate-system-id system-id)
    (reject-app-documentation-edit-request))

  (adminAddAppVersionDocs [_ system-id _ _ _]
    (validate-system-id system-id)
    (reject-app-versions-request))

  (listAppPermissions [_ qualified-app-ids _]
    (validate-system-ids (set (map :system_id qualified-app-ids)))
    (.listAppPermissions tapis (map :app_id qualified-app-ids)))

  (shareApps [self admin? sharing-requests]
    (app-permissions/process-app-sharing-requests self admin? sharing-requests))

  (shareAppsWithSubject [self admin? app-names sharee user-app-sharing-requests]
    (app-permissions/process-subject-app-sharing-requests self admin? app-names sharee user-app-sharing-requests))

  (shareAppWithSubject [_ _ app-names sharee system-id app-id level]
    (validate-system-id system-id)
    (sharing/share-app-with-subject tapis app-names sharee app-id level))

  (unshareApps [self admin? unsharing-requests]
    (app-permissions/process-app-unsharing-requests self admin? unsharing-requests))

  (unshareAppsWithSubject [self admin? app-names sharee user-app-unsharing-requests]
    (app-permissions/process-subject-app-unsharing-requests self admin? app-names sharee user-app-unsharing-requests))

  (unshareAppWithSubject [_ _ app-names sharee system-id app-id]
    (validate-system-id system-id)
    (sharing/unshare-app-with-subject tapis app-names sharee app-id))

  (hasAppPermission [_ username system-id app-id required-level]
    (validate-system-id system-id)
    (.hasAppPermission tapis username app-id required-level))

  (supportsJobSharing [_ _]
    true))
