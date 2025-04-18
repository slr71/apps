(ns apps.routes.admin.apps
  (:require [apps.routes.params :refer [SecuredQueryParams SecuredQueryParamsEmailRequired]]
            [apps.routes.schemas.app
             :refer [AdminAppSearchParams
                     AppPublicationRequestSearchParams]]
            [apps.service.apps :as apps]
            [apps.user :refer [current-user]]
            [apps.util.coercions :refer [coerce!]]
            [clojure-commons.exception-util :as cxu]
            [common-swagger-api.routes]                     ;; for :description-file
            [common-swagger-api.schema
             :refer [context defroutes DELETE GET PATCH POST PUT]]
            [common-swagger-api.schema.apps :as apps-schema]
            [common-swagger-api.schema.apps.admin.apps :as schema]
            [common-swagger-api.schema.apps.admin.categories
             :refer [AppCategorizationDocs
                     AppCategorizationRequest
                     AppCategorizationSummary]]
            [common-swagger-api.schema.apps.permission :as permission-schema]
            [common-swagger-api.schema.integration-data
             :refer [IntegrationData
                     IntegrationDataIdPathParam]]
            [ring.util.http-response :refer [ok]]))

(defroutes admin-apps
  (GET "/" []
    :query [params AdminAppSearchParams]
    :summary apps-schema/AppListingSummary
    :return schema/AdminAppListing
    :description schema/AppListingDocs
    (ok (coerce! schema/AdminAppListing
                 (apps/admin-search-apps current-user params))))

  (POST "/" []
    :query [params SecuredQueryParams]
    :body [body AppCategorizationRequest]
    :summary AppCategorizationSummary
    :description AppCategorizationDocs
    (ok (apps/categorize-apps current-user body)))

  (GET "/publication-requests" []
    :query [params AppPublicationRequestSearchParams]
    :summary schema/AppPublicationRequestsSummary
    :description schema/AppPublicationRequestsDocs
    :return schema/AppPublicationRequestListing
    (ok (coerce! schema/AppPublicationRequestListing
                 (apps/list-app-publication-requests current-user params))))

  (POST "/sharing" []
    :query [params SecuredQueryParams]
    :body [{:keys [sharing]} permission-schema/AppSharingRequest]
    :return permission-schema/AppSharingResponse
    :summary permission-schema/AppSharingSummary
    :description permission-schema/AppSharingDocs
    (ok (apps/share-apps current-user true sharing)))

  (POST "/unsharing" []
    :query [params SecuredQueryParams]
    :body [{:keys [unsharing]} permission-schema/AppUnsharingRequest]
    :return permission-schema/AppUnsharingResponse
    :summary permission-schema/AppUnsharingSummary
    :description permission-schema/AppUnsharingDocs
    (ok (apps/unshare-apps current-user true unsharing)))

  (POST "/shredder" []
    :query [params SecuredQueryParams]
    :body [body apps-schema/AppDeletionRequest]
    :summary schema/AppShredderSummary
    :description schema/AppShredderDocs
    (ok (apps/permanently-delete-apps current-user body)))

  (context "/:system-id/:app-id" []
    :path-params [system-id :- apps-schema/SystemId
                  app-id :- apps-schema/StringAppIdParam]

    (DELETE "/" []
      :query [params SecuredQueryParams]
      :summary apps-schema/AppDeleteSummary
      :description schema/AppDeleteDocs
      (ok (apps/admin-delete-app current-user system-id app-id)))

    (PATCH "/" []
      :query [params SecuredQueryParams]
      :body [body schema/AdminAppPatchRequest]
      :return schema/AdminAppDetails
      :summary schema/AdminAppPatchSummary
      :description-file "docs/apps/admin/app-label-update.md"
      (ok (coerce! schema/AdminAppDetails
                   (apps/admin-update-app current-user system-id (assoc body :id app-id)))))

    (POST "/blessing" []
      :query [params SecuredQueryParams]
      :summary schema/BlessAppSummary
      :description schema/BlessAppDescription
      (apps/admin-bless-app current-user system-id app-id)
      (ok))

    (DELETE "/blessing" []
      :query [params SecuredQueryParams]
      :summary schema/RemoveAppBlessingSummary
      :description schema/RemoveAppBlessingDescription
      (apps/admin-remove-app-blessing current-user system-id app-id)
      (ok))

    (GET "/details" []
      :query [params SecuredQueryParams]
      :return schema/AdminAppDetails
      :summary apps-schema/AppDetailsSummary
      :description schema/AppDetailsDocs
      (ok (coerce! schema/AdminAppDetails
                   (apps/admin-get-app-details current-user system-id app-id))))

    (PATCH "/documentation" []
      :query [params SecuredQueryParams]
      :body [body apps-schema/AppDocumentationRequest]
      :return apps-schema/AppDocumentation
      :summary apps-schema/AppDocumentationUpdateSummary
      :description schema/AppDocumentationUpdateDocs
      (ok (coerce! apps-schema/AppDocumentation
                   (apps/admin-edit-app-docs current-user system-id app-id body))))

    (POST "/documentation" []
      :query [params SecuredQueryParams]
      :body [body apps-schema/AppDocumentationRequest]
      :return apps-schema/AppDocumentation
      :summary apps-schema/AppDocumentationAddSummary
      :description schema/AppDocumentationAddDocs
      (ok (coerce! apps-schema/AppDocumentation
                   (apps/admin-add-app-docs current-user system-id app-id body))))

    (PUT "/integration-data/:integration-data-id" []
      :path-params [integration-data-id :- IntegrationDataIdPathParam]
      :query [params SecuredQueryParams]
      :return IntegrationData
      :summary schema/AppIntegrationDataUpdateSummary
      :description schema/AppIntegrationDataUpdateDocs
      (ok (apps/update-app-integration-data current-user system-id app-id integration-data-id)))

    (POST "/publish" []
      :query [params SecuredQueryParamsEmailRequired]
      :body [body apps-schema/PublishAppRequest]
      :summary apps-schema/PublishAppSummary
      :description apps-schema/PublishAppDocs
      (apps/validate-app-publishable current-user system-id app-id true)
      (let [body (assoc body :id app-id)]
        (if (apps/uses-tools-in-untrusted-registries? current-user system-id app-id)
          (cxu/bad-request (str "App " app-id " uses tools in untrusted registries"))
          (ok (apps/make-app-public current-user system-id body)))))

    (context "/versions/:version-id" []
      :path-params [version-id :- apps-schema/AppVersionIdParam]

      (PATCH "/" []
        :query [params SecuredQueryParams]
        :body [body schema/AdminAppPatchRequest]
        :return schema/AdminAppDetails
        :summary schema/AdminAppVersionPatchSummary
        :description-file "docs/apps/admin/app-label-update.md"
        (ok (coerce! schema/AdminAppDetails
                     (apps/admin-update-app current-user
                                            system-id
                                            (assoc body :id         app-id
                                                   :version_id version-id)))))

      (GET "/details" []
        :query [params SecuredQueryParams]
        :return schema/AdminAppDetails
        :summary schema/AppVersionDetailsSummary
        :description schema/AppVersionDetailsDocs
        (ok (coerce! schema/AdminAppDetails
                     (apps/admin-get-app-version-details current-user
                                                         system-id
                                                         app-id
                                                         version-id))))

      (PATCH "/documentation" []
        :query [params SecuredQueryParams]
        :body [body apps-schema/AppDocumentationRequest]
        :return apps-schema/AppDocumentation
        :summary schema/AppVersionDocumentationUpdateSummary
        :description schema/AppVersionDocumentationUpdateDocs
        (ok (coerce! apps-schema/AppDocumentation
                     (apps/admin-edit-app-version-docs current-user
                                                       system-id
                                                       app-id
                                                       version-id
                                                       body))))

      (POST "/documentation" []
        :query [params SecuredQueryParams]
        :body [body apps-schema/AppDocumentationRequest]
        :return apps-schema/AppDocumentation
        :summary schema/AppVersionDocumentationAddSummary
        :description schema/AppVersionDocumentationAddDocs
        (ok (coerce! apps-schema/AppDocumentation
                     (apps/admin-add-app-version-docs current-user
                                                      system-id
                                                      app-id
                                                      version-id
                                                      body))))

      (PUT "/integration-data/:integration-data-id" []
        :path-params [integration-data-id :- IntegrationDataIdPathParam]
        :query [params SecuredQueryParams]
        :return IntegrationData
        :summary schema/AppVersionIntegrationDataUpdateSummary
        :description schema/AppVersionIntegrationDataUpdateDocs
        (ok (apps/update-app-version-integration-data current-user
                                                      system-id
                                                      app-id
                                                      version-id
                                                      integration-data-id))))))
