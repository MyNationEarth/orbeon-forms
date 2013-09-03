/**
 *  Copyright (C) 2013 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.fr

import FormRunner._
import ProcessParser._
import collection.mutable.ListBuffer
import org.apache.commons.lang3.StringUtils
import org.orbeon.oxf.common.OXFException
import org.orbeon.oxf.util.ScalaUtils._
import org.orbeon.oxf.util.{NetUtils, Logging}
import org.orbeon.oxf.xforms.XFormsProperties
import org.orbeon.oxf.xforms.action.XFormsAPI._
import org.orbeon.oxf.xforms.analysis.model.StaticBind._
import org.orbeon.scaxon.XML._
import util.Try

// Implementation of simple processes
//
// - A process is usually associated with a Form Runner button.
// - A process can have a name which translates into a definition defined in a property.
// - The property specifies a sequence of actions separated by combinators.
// - Actions are predefined, but some of them are configurable.
//
object SimpleProcess extends ProcessInterpreter with FormRunnerActions with XFormsActions with Logging {

    implicit val logger = containingDocument.getIndentedLogger("process")

    override def extensionActions = AllowedFormRunnerActions ++ AllowedXFormsActions

    // All XPath runs in the context of the main form instance's root element
    def xpathContext = topLevelInstance("fr-form-model", "fr-form-instance") map (_.rootElement) orNull

    // NOTE: Clear the PDF URL *before* the process, because if we clear it after, it will be already cleared during the
    // second pass of a two-pass submission.
    // TODO: Delete temp file if any.
    override def beforeProcess() = Try(setvalue(pdfURLInstanceRootElement, ""))

    override def processError(t: Throwable) =
        tryErrorMessage(Map(Some("resource") → "process-error"))

    def writeSuspendedProcess(process: String) =
        setvalue(topLevelInstance("fr-persistence-model", "fr-processes-instance").get.rootElement, process)

    def readSuspendedProcess =
        topLevelInstance("fr-persistence-model", "fr-processes-instance").get.rootElement.stringValue

    // Search first in properties, then try legacy workflow-send
    def findProcessByName(scope: String, name: String) = {
        implicit val formRunnerParams = FormRunnerParams()

        // The scope is interpreted as a property prefix
        formRunnerProperty(scope + '.' + name) flatMap
        nonEmptyOrNone orElse // don't accept an existing but blank property [why?]
        buildProcessFromLegacyProperties(name)
    }

    // Legacy: build "workflow-send" process based on properties
    private def buildProcessFromLegacyProperties(buttonName: String)(implicit p: FormRunnerParams) = {

        def booleanPropertySet(name: String) = booleanFormRunnerProperty(name)
        def stringPropertySet (name: String) = formRunnerProperty(name) flatMap nonEmptyOrNone isDefined

        buttonName match {
            case "workflow-send" ⇒
                val isLegacySendEmail       = booleanPropertySet("oxf.fr.detail.send.email")
                val isLegacyNavigateSuccess = stringPropertySet("oxf.fr.detail.send.success.uri")
                val isLegacyNavigateError   = stringPropertySet("oxf.fr.detail.send.error.uri")

                val buffer = ListBuffer[String]()

                buffer += "require-uploads"
                buffer += ThenCombinator.name
                buffer += "require-valid"
                buffer += ThenCombinator.name
                buffer += "save"
                buffer += ThenCombinator.name
                buffer += """success-message("save-success")"""

                if (isLegacySendEmail) {
                    buffer += ThenCombinator.name
                    buffer += "email"
                }

                // TODO: Pass `content = "pdf-url"` if isLegacyCreatePDF. Requires better parsing of process arguments.
                //def isLegacyCreatePDF = isLegacyNavigateSuccess && booleanPropertySet("oxf.fr.detail.send.pdf")

                // Workaround is to change config from oxf.fr.detail.send.pdf = true to oxf.fr.detail.send.success.content = "pdf-url"
                if (isLegacyNavigateSuccess) {
                    buffer += ThenCombinator.name
                    buffer += """send("oxf.fr.detail.send.success")"""
                }

                if (isLegacyNavigateError) {
                    buffer += RecoverCombinator.name
                    buffer += """send("oxf.fr.detail.send.error")"""
                }

                Some(buffer mkString " ")
            case _ ⇒
                None
        }
    }
}

trait XFormsActions {

    import SimpleProcess._

    def AllowedXFormsActions = Map[String, Action](
        "xf:send"     → tryXFormsSend,
        "xf:dispatch" → tryXFormsDispatch,
        "xf:show"     → tryShowDialog
    )

    def tryXFormsSend(params: ActionParams): Try[Any] =
        Try {
            val submission = paramByNameOrDefault(params, "submission")
            submission foreach (sendThrowOnError(_))
        }

    def tryXFormsDispatch(params: ActionParams): Try[Any] =
        Try {
            val eventName = paramByNameOrDefault(params, "name")
            val eventTargetId = paramByName(params, "targetid") getOrElse "fr-form-model"
            eventName foreach (dispatch(_, eventTargetId))
        }

    def tryShowDialog(params: ActionParams): Try[Any] =
        Try {
            val dialogName = paramByNameOrDefault(params, "dialog")
            dialogName foreach (show(_))
        }
}

trait FormRunnerActions {

    import SimpleProcess._

    def AllowedFormRunnerActions = Map[String, Action](
        "pending-uploads"  → tryPendingUploads,
        "validate"         → tryValidate,
        "save"             → trySaveAttachmentsAndData,
        "success-message"  → trySuccessMessage,
        "error-message"    → tryErrorMessage,
        "email"            → trySendEmail,
        "send"             → trySend,
        "navigate"         → tryNavigate,
        "review"           → tryNavigateToReview,
        "edit"             → tryNavigateToEdit,
        "summary"          → tryNavigateToSummary,
        "visit-all"        → tryVisitAll,
        "unvisit-all"      → tryUnvisitAll,
        "expand-all"       → tryExpandSections,
        "collapse-all"     → tryCollapseSections,
        "result-dialog"    → tryShowResultDialog,
        "captcha"          → tryCaptcha,
        "wizard-prev"      → tryWizardPrev,
        "wizard-next"      → tryWizardNext
    )

    // Check whether there are pending uploads
    def tryPendingUploads(params: ActionParams): Try[Any] =
        Try {
            if (containingDocument.countPendingUploads > 0)
                throw new OXFException("Pending uploads")
        }

    // Validate form data and fail if invalid
    def tryValidate(params: ActionParams): Try[Any] =
        Try {
            val level = paramByNameOrDefault(params, "level") map LevelByName getOrElse ErrorLevel

            if (countValidationsByLevel(level) > 0)
                throw new OXFException(s"Data has failed constraints for level ${level.name}")
        }

    def trySaveAttachmentsAndData(params: ActionParams): Try[Any] =
        Try {
            val FormRunnerParams(app, form, Some(document), _) = FormRunnerParams()
            val isDraft = paramByName(params, "draft").exists(_ == "true")

            val PersistenceModel = "fr-persistence-model"
            val FormModel        = "fr-form-model"

            // Notify that the data is about to be saved
            dispatch(name = "fr-data-save-prepare", targetId = FormModel)

            // Save
            val (beforeURLs, afterURLs) = putWithAttachments(
                data              = formInstance.root,
                toBaseURI         = "", // local save
                fromBasePath      = createFormDataBasePath(app, form, ! isDraft, document),
                toBasePath        = createFormDataBasePath(app, form, isDraft, document),
                filename          = "data.xml",
                commonQueryString = s"valid=$dataValid",
                forceAttachments  = false
            )

            // If we were in new mode, now we must be in edit mode
            setvalue(parametersInstance.rootElement \ "mode", "edit")

            // Manual dependency HACK: RR fr-persistence-model before updating the status because we do a setvalue just
            // before calling the submission
            recalculate(PersistenceModel)
            revalidate(PersistenceModel)
            refresh(PersistenceModel)

            // Mark data clean
            val persistenceInstanceRoot = topLevelInstance(PersistenceModel, "fr-persistence-instance").get.rootElement
            val saveStatus = if (isDraft) Seq() else persistenceInstanceRoot \ "data-status"
            val autoSaveStatus = persistenceInstanceRoot \ "autosave" \ "status"
            (saveStatus ++ autoSaveStatus) foreach (setvalue(_, "clean"))

            // Manual dependency HACK: RR fr-form-model as we have changed mode
            recalculate(FormModel)
            revalidate(FormModel)

            // Notify that the data is saved (2013-09-03: used by FB only)
            dispatch(name = "fr-data-save-done", targetId = FormModel, properties = Map(
                "before-urls" → Some(beforeURLs),
                "after-urls"  → Some(afterURLs)
            ))
        }

    private def messageFromResourceOrParam(params: ActionParams) = {
        def fromResource = paramByNameOrDefault(params, "resource") map (k ⇒ currentFRResources \ "detail" \ "messages" \ k stringValue)
        def fromParam    = paramByName(params, "message")

        fromResource orElse fromParam get
    }

    def trySuccessMessage(params: ActionParams): Try[Any] =
        Try(FormRunner.successMessage(messageFromResourceOrParam(params)))

    def tryErrorMessage(params: ActionParams): Try[Any] =
        Try(FormRunner.errorMessage(messageFromResourceOrParam(params)))

    // TODO: Use xf:show("fr-submission-result-dialog")
    def tryShowResultDialog(params: ActionParams): Try[Any] =
        Try {
            show("fr-submission-result-dialog", Map(
                "fr-content" → Some(topLevelInstance("fr-persistence-model", "fr-create-update-submission-response").get.rootElement)
            ))
        }

    def tryCaptcha(params: ActionParams): Try[Any] =
        Try {
            if (hasCaptcha && (persistenceInstance.rootElement \ "captcha" === "false"))
                dispatch(name = "fr-verify", targetId = "captcha")
        }

    def trySendEmail(params: ActionParams): Try[Any] =
        Try {
            // NOTE: As of 2013-05-15, email-form.xpl recreates the PDF anyway, which is wasteful
//            implicit val formRunnerParams = FormRunnerParams()
//            if (booleanFormRunnerProperty("oxf.fr.email.attach-pdf"))
//                tryCreatePDFIfNeeded(EmptyActionParams).get

            sendThrowOnError("fr-email-service-submission")
        }

    // Defaults except for "uri"
    private val DefaultSendParameters = Map(
        "method"   → "post",
        "prune"    → "true",
        "annotate" → "",
        "replace"  → "none",
        "content"  → "xml"
    )

    private val SendParameterKeys = List("uri") ++ DefaultSendParameters.keys

    def trySend(params: ActionParams): Try[Any] =
        Try {

            implicit val formRunnerParams @ FormRunnerParams(app, form, document, _) = FormRunnerParams()

            val propertyPrefixOpt = paramByNameOrDefault(params, "property")

            def findParamValue(name: String) = {

                def fromParam    = paramByName(params, name)
                def fromProperty = propertyPrefixOpt flatMap (prefix ⇒ formRunnerProperty(prefix + "." + name))
                def fromDefault  = DefaultSendParameters.get(name)

                fromParam orElse fromProperty orElse fromDefault
            }

            val propertiesAsPairs =
                SendParameterKeys map (key ⇒ key → findParamValue(key))

            // Append query parameters to the URL
            val propertiesAsMap =
                propertiesAsPairs map {
                    case ("uri", Some(uri)) ⇒ "uri" → Some(appendQueryString(uri, s"app=$app&form=$form&document=${document.get}&valid=$dataValid"))
                    case other              ⇒ other
                } toMap

            // Create PDF if needed
            if (stringOptionToSet(propertiesAsMap("content")) exists Set("pdf", "pdf-url"))
                tryCreatePDFIfNeeded(EmptyActionParams).get

            // TODO: Remove duplication once @replace is an AVT
            val replace = if (propertiesAsMap.get("replace") exists (_ == Some("all"))) "all" else "none"
            sendThrowOnError(s"fr-send-submission-$replace", propertiesAsMap)
        }

    private def tryNavigateTo(path: String): Try[Any] =
        Try(load(path, progress = false))

    // Navigate to a URL specified in parameters or indirectly in properties
    // If no URL is specified, the action fails
    def tryNavigate(params: ActionParams): Try[Any] =
        Try {
            implicit val formRunnerParams = FormRunnerParams()

            // Heuristic: If the parameter is anonymous, we take it as a URL or path if it looks like one. Otherwise, we
            // consider it is a property. We could also look at whether the value looks like a property. To resolve
            // ambiguity, we'll need to parse named parameters.
            def isAbsolute(s: String) = s.startsWith("/") || NetUtils.urlHasProtocol(s)

            def fromParams = params.get(Some("uri")) orElse (params.get(None) filter isAbsolute)

            def fromProperties = {
                val property =  params.get(Some("property")) orElse (params.get(None) filterNot isAbsolute) getOrElse "oxf.fr.detail.close.uri"
                formRunnerProperty(property)
            }

            // Try to automatically append fr-noscript
            // Heuristic: We append it only if the URL doesn't have a protocol. This might not always be a right guess.
            def appendNoscriptIfNeeded(s: String) =
                if (XFormsProperties.isNoscript(containingDocument) && ! NetUtils.urlHasProtocol(s))
                    appendQueryString(s, "fr-noscript=true")
                else
                    s

            fromParams orElse fromProperties flatMap nonEmptyOrNone map appendNoscriptIfNeeded get
        } flatMap
            tryNavigateTo

    def tryNavigateToReview(params: ActionParams): Try[Any] =
        Try(sendThrowOnError("fr-workflow-review-submission"))

    def tryNavigateToEdit(params: ActionParams): Try[Any] =
        Try(sendThrowOnError("fr-workflow-edit-submission"))

    def tryNavigateToSummary(params: ActionParams): Try[Any]  =
        Try {
            val FormRunnerParams(app, form, _, _) = FormRunnerParams()
            s"/fr/$app/$form/summary"
        } flatMap
            tryNavigateTo

    // Visit/unvisit controls
    def tryVisitAll(params: ActionParams)  : Try[Any] = Try(dispatch(name = "fr-visit-all",   targetId = "fr-error-summary-model"))
    def tryUnvisitAll(params: ActionParams): Try[Any] = Try(dispatch(name = "fr-unvisit-all", targetId = "fr-error-summary-model"))

    // Collapse/expand sections
    def tryCollapseSections(params: ActionParams): Try[Any] = Try(dispatch(name = "fr-collapse-all", targetId = "fr-sections-model"))
    def tryExpandSections(params: ActionParams)  : Try[Any] = Try(dispatch(name = "fr-expand-all",   targetId = "fr-sections-model"))

    // Navigate the wizard to the previous page
    def tryWizardPrev(params: ActionParams): Try[Any] =
        Try (dispatch(name = "fr-prev", targetId = "fr-view-wizard"))

    // Navigate the wizard to the next page
    def tryWizardNext(params: ActionParams): Try[Any] =
        Try (dispatch(name = "fr-next", targetId = "fr-view-wizard"))

    def pdfURLInstanceRootElement = topLevelInstance("fr-persistence-model", "fr-pdf-url-instance").get.rootElement

    def tryCreatePDFIfNeeded(params: ActionParams): Try[Any] =
        Try{
            // Only create if not available yet
            if (StringUtils.isBlank(pdfURLInstanceRootElement.stringValue))
                sendThrowOnError("fr-pdf-service-submission")
        }
}
