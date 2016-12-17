(ns apps.protocols)

(defprotocol Apps
  "A protocol used to provide an abstraction layer for dealing with app metadata."
  (getUser [_])
  (getClientName [_])
  (getJobTypes [_])
  (supportsSystemId [_ system-id])
  (listAppCategories [_ params])
  (hasCategory [_ category-id])
  (listAppsInCategory [_ category-id params])
  (listAppsUnderHierarchy [_ root-iri attr params])
  (adminListAppsUnderHierarchy [_ ontology-version root-iri attr params])
  (searchApps [_ search-term params])
  (adminSearchApps [_ search-term params])
  (canEditApps [_])
  (addApp [_ system-id app])
  (previewCommandLine [_ system-id app])
  (validateDeletionRequest [_ deletion-request])
  (deleteApps [_ deletion-request])
  (getAppJobView [_ system-id app-id])
  (getAppSubmissionInfo [_ system-id app-id])
  (deleteApp [_ system-id app-id])
  (relabelApp [_ system-id app])
  (updateApp [_ system-id app])
  (copyApp [_ app-id] [_ system-id app-id])
  (getAppDetails [_ app-id admin?] [_ system-id app-id admin?])
  (removeAppFavorite [_ app-id] [_ system-id app-id])
  (addAppFavorite [_ app-id] [_ system-id app-id])
  (isAppPublishable [_ app-id] [_ system-id app-id])
  (makeAppPublic [_ app] [_ system-id app])
  (deleteAppRating [_ app-id] [_ system-id app-id])
  (rateApp [_ app-id rating] [_ system-id app-id rating])
  (getAppTaskListing [_ app-id] [_ system-id app-id])
  (getAppToolListing [_ app-id] [_ system-id app-id])
  (getAppUi [_ app-id] [_ system-id app-id])
  (getAppInputIds [_ app-id] [_ system-id app-id])
  (addPipeline [_ pipeline])
  (formatPipelineTasks [_ pipeline])
  (updatePipeline [_ pipeline])
  (copyPipeline [_ app-id])
  (editPipeline [_ app-id])
  (listJobs [_ params])
  (loadAppTables [_ app-ids])
  (submitJob [_ submission])
  (submitJobStep [_ job-id submission])
  (translateJobStatus [_ job-type status])
  (updateJobStatus [_ job-step job status end-date])
  (getDefaultOutputName [_ io-map source-step])
  (getJobStepStatus [_ job-step])
  (buildNextStepSubmission [_ job-step job])
  (prepareStepSubmission [_ job-id submission])
  (getParamDefinitions [_ app-id] [_ system-id app-id])
  (stopJobStep [_ job-step])
  (categorizeApps [_ body])
  (permanentlyDeleteApps [_ body])
  (adminDeleteApp [_ app-id] [_ system-id app-id])
  (adminUpdateApp [_ body] [_ system-id body])
  (getAdminAppCategories [_ params])
  (adminAddCategory [_ body] [_ system-id body])
  (adminDeleteCategories [_ body])
  (adminDeleteCategory [_ category-id] [_ system-id category-id])
  (adminUpdateCategory [_ body] [_ system-id body])
  (getAppDocs [_ app-id] [_ system-id app-id])
  (getAppIntegrationData [_ app-id] [_ system-id app-id])
  (getToolIntegrationData [_ tool-id] [_ system-id tool-id])
  (updateAppIntegrationData [_ app-id integration-data-id] [_ system-id app-id integration-data-id])
  (updateToolIntegrationData [_ tool-id integration-data-id] [_ system-id tool-id integration-data-id])
  (ownerEditAppDocs [_ app-id body] [_ system-id app-id body])
  (ownerAddAppDocs [_ app-id body] [_ system-id app-id body])
  (adminEditAppDocs [_ app-id body] [_ system-id app-id body])
  (adminAddAppDocs [_ app-id body] [_ system-id app-id body])
  (listAppPermissions [_ app-ids])
  (shareApps [_ sharing-requests])
  (shareAppsWithUser [_ app-names sharee user-app-sharing-requests])
  (shareAppWithUser [_ app-names sharee app-id level])
  (unshareApps [_ unsharing-requests])
  (unshareAppsWithUser [_ app-names sharee app-ids])
  (unshareAppWithUser [_ app-names sharee app-id])
  (hasAppPermission [_ username app-id required-level] [_ username system-id app-id required-level])
  (supportsJobSharing [_ _]))
